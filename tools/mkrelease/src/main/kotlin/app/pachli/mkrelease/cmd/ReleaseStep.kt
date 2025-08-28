/*
 * Copyright 2023 Pachli Association
 *
 * This file is a part of Pachli.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation; either version 3 of the
 * License, or (at your option) any later version.
 *
 * Pachli is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Pachli; if not,
 * see <http://www.gnu.org/licenses>.
 */

package app.pachli.mkrelease.cmd

import app.pachli.mkrelease.Config
import app.pachli.mkrelease.DelegatingCredentialsProvider
import app.pachli.mkrelease.GitHubPullRequest
import app.pachli.mkrelease.LogEntry
import app.pachli.mkrelease.PachliVersion
import app.pachli.mkrelease.PasswordCredentialsProvider
import app.pachli.mkrelease.ReleaseSpec
import app.pachli.mkrelease.SPEC_FILE
import app.pachli.mkrelease.Section
import app.pachli.mkrelease.Section.Features
import app.pachli.mkrelease.Section.Fixes
import app.pachli.mkrelease.Section.Translations
import app.pachli.mkrelease.confirm
import app.pachli.mkrelease.createFastlaneFromChangelog
import app.pachli.mkrelease.ensureClean
import app.pachli.mkrelease.ensureRepo
import app.pachli.mkrelease.getActualRefObjectId
import app.pachli.mkrelease.getChangelog
import app.pachli.mkrelease.getGradle
import app.pachli.mkrelease.hasBranch
import app.pachli.mkrelease.info
import app.pachli.mkrelease.message
import com.android.builder.model.v2.models.AndroidDsl
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.output.TermUi
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.terminal.Terminal
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.createTempFile
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.errors.RefAlreadyExistsException
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.TextProgressMonitor
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.URIish
import org.gitlab4j.api.GitLabApi
import org.kohsuke.github.GHPullRequestReviewState
import org.kohsuke.github.GHReleaseBuilder
import org.kohsuke.github.GitHubBuilder

/**
 * One or more pieces of work that to complete a release, but that should be completed as an
 * atomic unit
 */
@Serializable
sealed class ReleaseStep {
    abstract fun run(t: Terminal, config: Config, spec: ReleaseSpec): ReleaseSpec?
    open fun desc(): String = this.javaClass.simpleName
}

@Serializable
data object EnsureCleanReleaseSpec : ReleaseStep() {
    override fun run(t: Terminal, config: Config, spec: ReleaseSpec): ReleaseSpec? {
        spec.thisVersion?.let {
            throw UsageError("thisVersion is set in the release spec, there is an ongoing release process: $it")
        }

        return null
    }
}

/**
 * Throws if the environment is missing mandatory API tokens:
 *
 * - GITHUB_TOKEN
 * - WEBLATE_TOKEN
 */
@Serializable
data object EnsureEnvironmentHasTokens : ReleaseStep() {
    class NeedGithubToken : Exception("GITHUB_TOKEN not in environment")
    class NeedWeblateToken : Exception("WEBLATE_TOKEN not in environment")

    override fun run(t: Terminal, config: Config, spec: ReleaseSpec): ReleaseSpec? {
        System.getenv("GITHUB_TOKEN") ?: throw NeedGithubToken()
        System.getenv("WEBLATE_TOKEN") ?: throw NeedWeblateToken()
        return null
    }
}

@Serializable
@JsonIgnoreUnknownKeys
data class WeblateProjectResponse(
    @SerialName("lock_url")
    val lockUrl: String? = null,
    val locked: Boolean? = null,
)

@Serializable
data object LockWeblate : ReleaseStep() {
    @Serializable
    @JsonIgnoreUnknownKeys
    data class WeblateLockResponse(
        val locked: Boolean,
    )

    class WeblateNotLocked : Exception("Weblate project is not locked")

    override fun run(t: Terminal, config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val client = HttpClient(CIO) {
            install(ContentNegotiation) { json() }
        }

        val project: WeblateProjectResponse = runBlocking {
            client.get("https://hosted.weblate.org/api/projects/pachli/") {
                bearerAuth(System.getenv("WEBLATE_TOKEN"))
            }.body()
        }

        if (project.lockUrl == null) {
            t.warning("Weblate does not support locking in the API, skipping")
            return null
        }

        if (project.locked == true) {
            t.info("Project is already locked, skipping")
            return null
        }

        val response: WeblateLockResponse = runBlocking {
            client.post(project.lockUrl) {
                bearerAuth(System.getenv("WEBLATE_TOKEN"))
                contentType(ContentType("application", "json"))
                setBody("""{"lock":true}""")
            }.body()
        }

        if (!response.locked) throw WeblateNotLocked()

        return null
    }
}

@Serializable
data object UnlockWeblate : ReleaseStep() {
    @Serializable
    @JsonIgnoreUnknownKeys
    data class WeblateLockResponse(
        val locked: Boolean,
    )

    class WeblateLocked : Exception("Weblate project is locked")

    override fun run(t: Terminal, config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val client = HttpClient(CIO) {
            install(ContentNegotiation) { json() }
        }

        val project: WeblateProjectResponse = runBlocking {
            client.get("https://hosted.weblate.org/api/projects/pachli/") {
                bearerAuth(System.getenv("WEBLATE_TOKEN"))
            }.body()
        }

        if (project.lockUrl == null) {
            t.warning("Weblate does not support locking in the API, skipping")
            return null
        }

        if (project.locked == false) {
            t.info("Project is already unlocked, skipping")
            return null
        }

        val response: LockWeblate.WeblateLockResponse = runBlocking {
            client.post(project.lockUrl) {
                bearerAuth(System.getenv("WEBLATE_TOKEN"))
                contentType(ContentType("application", "json"))
                setBody("""{"lock":false}""")
            }.body()
        }

        if (response.locked) throw WeblateLocked()

        return null
    }
}

/**
 * Checks there are no outstanding changes at Weblate that need to be
 * committed, merged, or pushed. Throws if there are.
 */
@Serializable
data object EnsureUpToDateTranslations : ReleaseStep() {
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    @JsonIgnoreUnknownKeys
    data class WeblateRepositoryResponse(
        @SerialName("needs_commit")
        val needsCommit: Boolean,

        @SerialName("needs_merge")
        val needsMerge: Boolean,

        @SerialName("needs_push")
        val needsPush: Boolean,
    )

    class WeblateNeedsCommit : Exception("Weblate has pending changes to commit")
    class WeblateNeedsMerge : Exception("Weblate has upstream changes to merge")
    class WeblateNeedsPush : Exception("Weblate has local changes to push")

    override fun run(t: Terminal, config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val client = HttpClient(CIO) {
            install(ContentNegotiation) { json() }
        }

        val response: WeblateRepositoryResponse = runBlocking {
            client.get("https://hosted.weblate.org/api/projects/pachli/repository/") {
                bearerAuth(System.getenv("WEBLATE_TOKEN"))
            }.body()
        }

        if (response.needsCommit) throw WeblateNeedsCommit()
        if (response.needsMerge) throw WeblateNeedsMerge()
        if (response.needsPush) throw WeblateNeedsPush()

        return null
    }
}

/**
 * Prepares the copy of the Pachli repository fork.
 *
 * - Ensures repo exists, is checked out, and clean.
 * - Sets upstream remote
 */
@Serializable
data object PreparePachliForkRepository : ReleaseStep() {
    override fun run(t: Terminal, config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val repo = config.repositoryFork
        val root = config.pachliForkRoot

        val git = ensureRepo(t, repo.gitUrl, root).also { it.ensureClean(t) }

        // git remote add upstream https://...
        git.remoteAdd()
            .setName("upstream")
            .setUri(URIish(config.repositoryMain.gitUrl))
            .info(t)
            .call()

        val defaultBranchRef = Git.lsRemoteRepository()
            .setRemote(repo.gitUrl.toString())
            .callAsMap()["HEAD"]?.target?.name ?: throw UsageError("Could not determine default branch name")
        t.info("default branch ref is $defaultBranchRef")
        val defaultBranch = defaultBranchRef.split("/").last()

        t.info("default branch: $defaultBranch")

        // git checkout main
        git.checkout()
            .setName(defaultBranch)
            .info(t)
            .call()

        // git fetch upstream
        git.fetch()
            .setRemote("upstream")
            .setProgressMonitor(TextProgressMonitor())
            .info(t)
            .call()

        // git pull upstream $defaultBranch
        // - FF_ONLY, a non-FF pull indicates a merge commit is needed, which is bad
        git.pull()
            .setRemote("upstream")
            .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
            .setProgressMonitor(TextProgressMonitor())
            .info(t)
            .call()

        // git push origin $defaultBranch
        git.push()
            .setCredentialsProvider(DelegatingCredentialsProvider(root.toPath()))
            .setRemote("origin")
            .setProgressMonitor(TextProgressMonitor())
            .info(t)
            .call()

        // Checkout `main` branch,
        git.checkout().setName("main").info(t).call()

        // Pull everything.
        // - FF_ONLY, a non-FF pull indicates a merge commit is needed, which is bad
//            git.pull()
//                .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
//                .call()
        git.ensureClean(t)

        return null
    }
}

/**
 * @return ReleaseSpec with [prevVersion] set to the release that is currently
 * live (i.e., the one this release process will replace).
 */
@Serializable
data object GetCurrentAppVersion : ReleaseStep() {
    override fun run(t: Terminal, config: Config, spec: ReleaseSpec): ReleaseSpec {
        val repo = config.repositoryFork
        val root = config.pachliForkRoot

        ensureRepo(t, repo.gitUrl, root)
            .also { it.ensureClean(t) }

        t.info("- Determining current release version")
        val currentRelease = getGradle(root).use {
            val androidDsl = it.model(AndroidDsl::class.java).get()
            val versionCode = androidDsl.defaultConfig.versionCode
                ?: throw UsageError("No versionCode in Gradle config")
            val versionName = androidDsl.defaultConfig.versionName
                ?: throw UsageError("No versionName in Gradle config")
            PachliVersion.from(versionName, versionCode)
                ?: throw UsageError("Could not parse '$versionName' as release version")
        }

        t.success("  $currentRelease")

        return spec.copy(
            prevVersion = currentRelease,
        )
    }
}

/**
 * @throws if [ReleaseSpec.prevVersion] is not a [PachliVersion.Beta]
 */
@Serializable
data object ConfirmCurrentVersionIsBeta : ReleaseStep() {
    override fun run(t: Terminal, config: Config, spec: ReleaseSpec): ReleaseSpec? {
        if (spec.prevVersion !is PachliVersion.Beta) {
            throw UsageError("Current Pachli version is not a beta")
        }

        t.success("Current version (${spec.prevVersion}) is a beta")
        return null
    }
}

@Serializable
data object SetNextVersionAsBeta : ReleaseStep() {
    override fun run(t: Terminal, config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val releaseVersion = spec.prevVersion.next(spec.releaseType)
        t.success("Release version is $releaseVersion")
        return spec.copy(thisVersion = releaseVersion)
    }
}

@Serializable
data object SetNextVersionAsRelease : ReleaseStep() {
    override fun run(t: Terminal, config: Config, spec: ReleaseSpec): ReleaseSpec {
        val releaseVersion = when (spec.prevVersion) {
            is PachliVersion.Beta -> spec.prevVersion.release()
            is PachliVersion.Release -> spec.prevVersion.release(spec.releaseType)
        }
//        val releaseVersion = (spec.prevVersion as PachliVersion.Beta).release()
        t.success("Release version is $releaseVersion")
        return spec.copy(thisVersion = releaseVersion)
    }
}

class BranchExists(message: String) : Throwable(message)

@Serializable
data object CreateReleaseBranch : ReleaseStep() {
    override fun run(t: Terminal, config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val repo = config.repositoryFork
        val root = config.pachliForkRoot

        val git = ensureRepo(t, repo.gitUrl, root)
            .also { it.ensureClean(t) }

        // Create branch (${issue}-${major}.${minor}-b${beta})
        val branch = spec.releaseBranch()
        t.info("- Release branch will be $branch")

        try {
            git.branchCreate()
                // Below was SET_UPSTREAM, but that's deprecated in Git docs
                .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                .setName(branch)
                .info(t)
                .call()
        } catch (e: RefAlreadyExistsException) {
            throw BranchExists("Branch $branch already exists")
        }

        t.success("  ... done.")

        return null
    }
}

class BranchMissing(message: String) : Exception(message)

@Serializable
data object UpdateFilesForRelease : ReleaseStep() {
    override fun run(t: Terminal, config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val repo = config.repositoryFork
        val root = config.pachliForkRoot

        val git = ensureRepo(t, repo.gitUrl, root)
            .also { it.ensureClean(t) }

        val branch = spec.releaseBranch()
        spec.thisVersion ?: throw UsageError("releaseSpec.thisVersion is null and should not be")

        if (!git.hasBranch("refs/heads/$branch")) {
            throw BranchMissing("Branch $branch should exist but is missing")
        }

        // Switch to branch
        // TODO: This will fail if the branch doesn't exist, maybe the previous check is unnecessary
        git.checkout().setName(branch).info(t).call()

        // No API to update the files, so edit in place
        val buildDotGradleKtsFile = File(File(root, "app"), "build.gradle.kts")
        val content = buildDotGradleKtsFile.readText()
        content.contains("versionCode = ${spec.prevVersion.versionCode}") || throw UsageError("can't find 'versionCode ${spec.prevVersion.versionCode}' in $buildDotGradleKtsFile")
        content.contains("versionName = \"${spec.prevVersion.versionName()}\"") || throw UsageError("can't find 'versionName \"${spec.prevVersion.versionName()}\"' in $buildDotGradleKtsFile")

        buildDotGradleKtsFile.writeText(
            content
                .replace(
                    "versionCode = ${spec.prevVersion.versionCode}",
                    "versionCode = ${spec.thisVersion.versionCode}",
                )
                .replace(
                    "versionName = \"${spec.prevVersion.versionName()}\"",
                    "versionName = \"${spec.thisVersion.versionName()}\"",
                ),
        )

        // TODO: Check that spec.prevVersion.versionTag exists, to provide a better
        // error message here. If you don't do this the git.log() command a few lines
        // later dies with "findRef(...) must not be null"

        // Construct the initial change log by looking for conventional commits
        // with type 'feat' and 'fix'.
        val changelogEntries = git.log()
            .addRange(
                git.getActualRefObjectId(spec.prevVersion.versionTag()),
                git.getActualRefObjectId("main"),
            )
            .info(t).call().mapNotNull {
                val components = it.shortMessage.split(":", limit = 2)
                t.info(components)
                if (components.size != 2) return@mapNotNull null

                val section = Section.fromCommitTitle(it.shortMessage)

                if (section == Section.Unknown) return@mapNotNull null

                LogEntry(section, components[1], it.authorIdent)
            }
            .groupBy { it.section }
            .toMutableMap()

        changelogEntries[Translations] = changelogEntries[Translations].orEmpty()
            .asSequence()
            .filterNot { it.author.name == "Anonymous" }
            .filterNot { it.author.name == "LibreTranslate" }
            .filterNot { it.author.name == "Weblate Translation Memory" }
            .filterNot { it.author.name == "Weblate (bot)" }
            .distinctBy { it.author.emailAddress }
            .sortedBy { it.text }
            .toList()

        t.info(changelogEntries)
        // Add entry to CHANGELOG.md
        // No good third-party libraries for parsing Markdown to an AST **and then manipulating
        // that tree**, so add the new block by hand
        val changeLogFile = File(config.pachliForkRoot, "CHANGELOG.md")
        val tmpFile = createTempFile().toFile()
        val w = tmpFile.printWriter()
        changeLogFile.useLines { lines ->
            var done = false
            for (line in lines) {
                if (done) {
                    w.println(line)
                    continue
                }

                // The first entry with a version number -- must be the most recent version,
                // so insert the placeholder immediately before.
                if (line.startsWith("## v")) {
                    w.println("## v${spec.thisVersion.versionName()}")
                    if (changelogEntries[Features]?.isNotEmpty() == true) {
                        w.println(
                            """
### New features and other improvements

${changelogEntries[Features]?.joinToString("\n") { "-${it.withLinks()}" }}
""",
                        )
                    }

                    if (changelogEntries[Fixes]?.isNotEmpty() == true) {
                        w.println(
                            """
### Significant bug fixes

${changelogEntries[Fixes]?.joinToString("\n") { "-${it.withLinks()}" }}

                            """.trimIndent(),
                        )
                    }

                    if (changelogEntries[Translations]?.isNotEmpty() == true) {
                        w.println(
                            """
### Translations

${changelogEntries[Translations]?.joinToString("\n") { "-${it.withLinks()}" }}

                            """.trimIndent(),
                        )
                    }
                    w.println(line)
                    done = true
                    continue
                }
                w.println(line)
            }
        }
        w.close()
        Files.move(
            tmpFile.toPath(),
            changeLogFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )

        t.info("- Edit CHANGELOG.md for this release")
        t.muted("  To see what's changed between this release and the last,")
        t.muted("  https://github.com/pachli/pachli-android/compare/${spec.prevVersion.versionTag()}...main")
        TermUi.editFile(changeLogFile.toString())

        // TODO: Check the "ENTER NEW LOG HERE" string has been removed

        // Pull out the new lines from the changelog
        val fastlaneFile = spec.fastlaneFile(config.pachliForkRoot)
        createFastlaneFromChangelog(t, changeLogFile, fastlaneFile, spec.thisVersion.versionName())

        git.add()
            .setUpdate(false)
            .addFilepattern("CHANGELOG.md")
            .addFilepattern("app/build.gradle.kts")
            .addFilepattern(spec.fastlanePath())
            .info(t)
            .call()

        var changesAreOk = false
        while (!changesAreOk) {
            git.diff()
                .setOutputStream(System.out)
                .setCached(true)
                .call()
            changesAreOk = t.confirm("Do these changes look OK?")
            if (!changesAreOk) {
                val r = t.prompt(
                    TextColors.yellow("Edit Changelog or Fastlane?"),
                    choices = listOf("c", "f"),
                )
                when (r) {
                    "c" -> {
                        TermUi.editFile(changeLogFile.toString())
                        createFastlaneFromChangelog(t, changeLogFile, fastlaneFile, spec.thisVersion.versionName())
                    }
                    "f" -> TermUi.editFile(fastlaneFile.toString())
                }
                git.add()
                    .addFilepattern("CHANGELOG.md")
                    .addFilepattern(spec.fastlanePath())
                    .info(t)
                    .call()
            }
        }

        // Commit
        val commitMsg =
            "chore: Prepare release ${spec.thisVersion.versionName()} (versionCode ${spec.thisVersion.versionCode})"
        t.info("""- git commit -m "$commitMsg"""")
        git.commit()
            .setMessage(commitMsg)
            .setSign(null)
            .setCredentialsProvider(PasswordCredentialsProvider(t))
            .info(t)
            .call()

        // Push
        t.info("- Pushing changes to ${config.repositoryFork.githubUrl}")
        git.push()
            .setCredentialsProvider(DelegatingCredentialsProvider(config.pachliForkRoot.toPath()))
            .setRemote("origin")
            .setRefSpecs(RefSpec("$branch:$branch"))
            .info(t)
            .call()

        return null
    }
}

@Serializable
data object CreateReleaseBranchPullRequest : ReleaseStep() {
    override fun run(t: Terminal, config: Config, spec: ReleaseSpec): ReleaseSpec? {
        t.info("- Create pull request at https://github.com/${config.repositoryMain.owner}/${config.repositoryMain.repo}/compare/main...${config.repositoryFork.owner}:${config.repositoryFork.repo}:${spec.releaseBranch()}?expand=1")
        return null
    }
}

/**
 * Prompts for a pull request URL and saves it to [ReleaseSpec.pullRequest].
 */
@Serializable
data object SavePullRequest : ReleaseStep() {
    override fun run(t: Terminal, config: Config, spec: ReleaseSpec): ReleaseSpec {
        val pullRequest = GitHubPullRequest(
            URL(t.prompt(TextColors.yellow("Enter pull request URL"))),
        )
        return spec.copy(pullRequest = pullRequest)
    }
}

/**
 * Waits for [ReleaseSpec.pullRequest] to be merged.
 */
@Serializable
data object WaitForPullRequestMerged : ReleaseStep() {
    override fun run(t: Terminal, config: Config, spec: ReleaseSpec): ReleaseSpec? = runBlocking {
        val pullRequest = spec.pullRequest
            ?: throw UsageError("pullRequest is null, but should not be")

        val githubToken = System.getenv("GITHUB_TOKEN")
            ?: throw UsageError("GITHUB_TOKEN is null")

        val repo = config.repositoryMain

        val github = GitHubBuilder().withOAuthToken(githubToken).build()
        val pull = github.getRepository("${repo.owner}/${repo.repo}").getPullRequest(pullRequest.number.toInt())

        t.info("- Checking $pullRequest")
        t.success("  Title: ${pull.title}")

        do {
            if (pull.isMerged) {
                t.success("Has been merged")
                break
            }
            var lines = 0
            if (pull.isDraft) {
                t.warning("  Marked as draft")
                lines++
            }
            if (!pull.mergeable) {
                t.warning("  Not currently mergeable")
                lines++
            }

            // If there are no requested reviewers and no reviews then one or more
            // reviewers needs to be assigned.
            val requestedReviewers = pull.requestedReviewers
            val reviews = pull.listReviews().toList()
            if (requestedReviewers.isEmpty() && reviews.isEmpty()) {
                t.warning("  No reviewers are assigned")
                lines++
            } else if (reviews.isEmpty()) {
                t.info("  Waiting for review from ${requestedReviewers.map { it.login }}")
                lines++
            } else {
                for (review in reviews) {
                    when (review.state) {
                        GHPullRequestReviewState.PENDING -> t.info("  ${review.user.login}: PENDING")
                        GHPullRequestReviewState.APPROVED -> t.success("  ${review.user.login}: APPROVED")
                        GHPullRequestReviewState.CHANGES_REQUESTED,
                        GHPullRequestReviewState.REQUEST_CHANGES,
                        -> t.danger("  ${review.user.login}: CHANGES_REQUESTED")
                        GHPullRequestReviewState.COMMENTED -> t.warning("  ${review.user.login}: COMMENTED")
                        GHPullRequestReviewState.DISMISSED -> t.warning("  ${review.user.login}: DISMISSED")
                        null -> t.danger("  ${review.user.login}: null state")
                    }
                    lines++
                }
            }
            repeat(300) {
                t.info("Waiting until next check in: $it / 300 seconds")
                delay(1.seconds)
                t.cursor.move {
                    up(1)
                    startOfLine()
                    clearLineAfterCursor()
                }
            }
            t.cursor.move {
                up(lines)
                startOfLine()
                clearScreenAfterCursor()
            }
            pull.refresh()
        } while (true)

        return@runBlocking null
    }
}

/**
 * Merge the Pachli `develop` branch in to the `main` branch. This happens on a
 * clone of the primary repository, not a fork.
 */
@Serializable
data object MergeDevelopToMain : ReleaseStep() {
    override fun run(t: Terminal, config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val repo = config.repositoryMain
        val root = config.pachliMainRoot

        val pullRequest = spec.pullRequest
            ?: throw UsageError("pullRequest is null, but should not be")

        val githubToken = System.getenv("GITHUB_TOKEN")
            ?: throw UsageError("GITHUB_TOKEN is null")

        val git = ensureRepo(t, repo.gitUrl, root).also { it.ensureClean(t) }

        git.fetch()
            .setProgressMonitor(TextProgressMonitor())
            .info(t)
            .call()
        git.checkout().setName("develop").info(t).call()
        git.pull()
            .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
            .setProgressMonitor(TextProgressMonitor())
            .info(t)
            .call()

        val github = GitHubBuilder().withOAuthToken(githubToken).build()
        val pull = github.getRepository("${repo.owner}/${repo.repo}").getPullRequest(pullRequest.number.toInt())

        pull.isMerged || throw UsageError("$pullRequest is not merged!")

        t.info("- Merge commit SHA: ${pull.mergeCommitSha}")
        val mergeCommitSha = ObjectId.fromString(pull.mergeCommitSha)
        git.log()
            .add(mergeCommitSha)
            .setMaxCount(1)
            .info(t)
            .call()
            .forEach { println(it.message()) }

        t.confirm("Does that look like the correct commit on develop?", abort = true)

        t.info("- Syncing main branch")
        git.checkout()
            .setCreateBranch(!git.hasBranch("refs/heads/main"))
            .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
            .setName("main")
            .setStartPoint("origin/main")
            .info(t)
            .call()
        git.pull()
            .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
            .setProgressMonitor(TextProgressMonitor())
            .info(t)
            .call()
        git.log().setMaxCount(1).info(t).call().forEach { println(it.message()) }

        t.confirm("Does that look like the correct most recent commit on main?", abort = true)

        val headBeforeMerge = git.repository.resolve("main")
        git.merge()
            .include(mergeCommitSha)
            .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
            .info(t)
            .call()

        // TODO: This should probably show the short title, not the full message
        git.log()
            .addRange(headBeforeMerge, mergeCommitSha)
            .info(t)
            .call()
            .forEach { println(it.message()) }

        t.confirm("Does that look like the correct commits have been merged to main?", abort = true)
        return null
    }
}

@Serializable
data object FetchMainToTag : ReleaseStep() {
    override fun run(t: Terminal, config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val repo = config.repositoryMain
        val root = config.pachliMainRoot

        val pullRequest = spec.pullRequest
            ?: throw UsageError("pullRequest is null, but should not be")

        val githubToken = System.getenv("GITHUB_TOKEN")
            ?: throw UsageError("GITHUB_TOKEN is null")

        val git = ensureRepo(t, repo.gitUrl, root).also { it.ensureClean(t) }

        git.fetch()
            .setProgressMonitor(TextProgressMonitor())
            .info(t)
            .call()
        git.checkout().setName("main").info(t).call()
        git.pull()
            .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
            .setProgressMonitor(TextProgressMonitor())
            .info(t)
            .call()

        val github = GitHubBuilder().withOAuthToken(githubToken).build()
        val pull = github.getRepository("${repo.owner}/${repo.repo}").getPullRequest(pullRequest.number.toInt())

        pull.isMerged || throw UsageError("$pullRequest is not merged!")

        t.info("- Merge commit SHA: ${pull.mergeCommitSha}")
        val mergeCommitSha = ObjectId.fromString(pull.mergeCommitSha)
        git.log()
            .add(mergeCommitSha)
            .setMaxCount(1)
            .info(t)
            .call()
            .forEach { println(it.message()) }

        t.confirm("Does that look like the correct commit on main?", abort = true)

//        t.info("- Syncing main branch")
//        git.checkout()
//            .setCreateBranch(!git.hasBranch("refs/heads/main"))
//            .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
//            .setName("main")
//            .setStartPoint("origin/main")
//            .info(t)
//            .call()
//        git.pull()
//            .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
//            .setProgressMonitor(TextProgressMonitor())
//            .info(t)
//            .call()
//        git.log().setMaxCount(1).info(t).call().forEach { println(it.message()) }
//
//        t.confirm("Does that look like the correct most recent commit on main?", abort = true)

//        val headBeforeMerge = git.repository.resolve("main")
//        git.merge()
//            .include(mergeCommitSha)
//            .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
//            .info(t)
//            .call()
//
//        // TODO: This should probably show the short title, not the full message
//        git.log()
//            .addRange(headBeforeMerge, mergeCommitSha)
//            .info(t)
//            .call()
//            .forEach { println(it.message()) }
//
//        t.confirm("Does that look like the correct commits have been merged to main?", abort = true)
        return null
    }
}

@Serializable
data object TagMainAsRelease : ReleaseStep() {
    override fun run(t: Terminal, config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val repo = config.repositoryMain
        val root = config.pachliMainRoot
        val git = ensureRepo(t, repo.gitUrl, root).also { it.ensureClean(t) }

        git.checkout().setName("main").info(t).call()
        val tag = spec.releaseTag()
        git.tag().setCredentialsProvider(PasswordCredentialsProvider(t))
            .setName(tag)
            .setMessage(tag)
            .setSigned(true)
            .info(t)
            .call()

        return null
    }
}

@Serializable
data object PushTaggedMain : ReleaseStep() {
    override fun run(t: Terminal, config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val repo = config.repositoryMain
        val root = config.pachliMainRoot
        val git = ensureRepo(t, repo.gitUrl, root).also { it.ensureClean(t) }

        git.push()
            .setCredentialsProvider(DelegatingCredentialsProvider(config.pachliMainRoot.toPath()))
            .setProgressMonitor(TextProgressMonitor())
            .setPushAll()
            .setPushTags()
            .info(t)
            .call()

        return null
    }
}

@Serializable
data object CreateGithubRelease : ReleaseStep() {
    override fun run(t: Terminal, config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val githubToken = System.getenv("GITHUB_TOKEN")
            ?: throw UsageError("GITHUB_TOKEN is null")
        val github = GitHubBuilder().withOAuthToken(githubToken).build()

        val thisVersion = spec.thisVersion ?: throw UsageError("spec.thisVersion is null!")

        val changeLogFile = File(config.pachliMainRoot, "CHANGELOG.md")
        val changes = getChangelog(changeLogFile, thisVersion.versionName())

        val repo = github.getRepository(
            "${config.repositoryMain.owner}/${config.repositoryMain.repo}",
        )

        val release = GHReleaseBuilder(repo, spec.releaseTag())
            .name(spec.githubReleaseName())
            .body(changes)
            .draft(true)
            .categoryName("Announcements")
            .prerelease(thisVersion !is PachliVersion.Release)
            .makeLatest(GHReleaseBuilder.MakeLatest.FALSE)
            .create()

        t.success("Created Github release: ${release.htmlUrl}")

        return null
    }
}

@Serializable
data object RunOrangeReleaseWorkflow : ReleaseStep() {
    override fun run(t: Terminal, config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val releaseWorkflowName = "upload-orange-release.yml"
        val releaseTag = spec.releaseTag()
        t.info("Running orange release workflow with $releaseTag")
        t.info("Triggering https://github.com/pachli/pachli-android/actions/workflows/$releaseWorkflowName")

        val githubToken = System.getenv("GITHUB_TOKEN")
            ?: throw UsageError("GITHUB_TOKEN is null")
        val github = GitHubBuilder().withOAuthToken(githubToken).build()
        val repo = github.getRepository(
            "${config.repositoryMain.owner}/${config.repositoryMain.repo}",
        )
        val releaseWorkflow = repo.getWorkflow(releaseWorkflowName)
        releaseWorkflow.dispatch(releaseTag)

        return null
    }
}

@Serializable
data object RunReleaseWorkflow : ReleaseStep() {
    override fun run(t: Terminal, config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val releaseWorkflowName = "upload-blue-release-google-play.yml"
        val releaseTag = spec.releaseTag()
        t.info("Running release workflow with $releaseTag")
        t.info("Triggering https://github.com/pachli/pachli-android/actions/workflows/$releaseWorkflowName")

        val githubToken = System.getenv("GITHUB_TOKEN")
            ?: throw UsageError("GITHUB_TOKEN is null")
        val github = GitHubBuilder().withOAuthToken(githubToken).build()
        val repo = github.getRepository(
            "${config.repositoryMain.owner}/${config.repositoryMain.repo}",
        )
        val releaseWorkflow = repo.getWorkflow(releaseWorkflowName)
        releaseWorkflow.dispatch(releaseTag)

        return null
    }
}

@Serializable
data object WaitForReleaseWorkflowToComplete : ReleaseStep() {
    override fun run(t: Terminal, config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val releaseWorkflowName = "upload-blue-release-google-play.yml"

        // TODO - poll for workflow state every minute to see if it completes

        t.info("Wait for workflow to complete: https://github.com/pachli/pachli-android/actions/workflows/$releaseWorkflowName")
        while (!t.confirm("Has it completed?")) { }
        return null
    }
}

@Serializable
data object DownloadApk : ReleaseStep() {
    override fun run(t: Terminal, config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val thisVersion = spec.thisVersion ?: throw UsageError("spec.thisVersion must be defined")

        t.info(
            """
1. Open the action for the most recent "Upload blueRelease to Google Play"
2. Download "app-release.apk" (which will download a .zip file)
3. Extract the contents of the .zip file to the Downloads directory

            """.trimIndent(),
        )

        while (!t.confirm("Have you done all this?")) { }
        return null
    }
}

@Serializable
data object AttachApkToGithubRelease : ReleaseStep() {
    override fun run(t: Terminal, config: Config, spec: ReleaseSpec): ReleaseSpec? {
        t.info(config)
        t.info(spec)

        val thisVersion = spec.thisVersion ?: throw UsageError("spec.thisVersion must be defined")

        // Equivalent to "git rev-parse --short HEAD"
        val abbreviatedSha = run {
            val repo = config.repositoryMain
            val root = config.pachliMainRoot
            val git = ensureRepo(t, repo.gitUrl, root).also { it.ensureClean(t) }
            git.repository.exactRef("refs/heads/main").objectId.abbreviate(8).name()
        }

        // e.g., "Pachli_2.2.0_11_741bf56f_blueGithub_release-signed.apk"
        val apkFilename = "Pachli_${thisVersion.versionName()}_${thisVersion.versionCode}_${abbreviatedSha}_blueGithub_release-signed.apk"

        val githubToken = System.getenv("GITHUB_TOKEN")
            ?: throw UsageError("GITHUB_TOKEN is null")
        val github = GitHubBuilder().withOAuthToken(githubToken).build()

        // As a draft release getReleaseByTagName doesn't find it. So fetch the
        // most recent releases and search for it in those.
        val repo = github.getRepository(
            "${config.repositoryMain.owner}/${config.repositoryMain.repo}",
        )

        val release = repo.listReleases().toList().find { it.tagName == thisVersion.versionTag() }
            ?: throw UsageError("Could not find Github release for tag ${spec.releaseTag()}")

        val apk = File("../../Downloads/$apkFilename")
        t.info("- Uploading ${apk.toPath()}")
        release.uploadAsset(apk, "application/vnd.android.package-archive")

        return null
    }
}

@Serializable
data object PromoteRelease : ReleaseStep() {
    override fun run(t: Terminal, config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val thisVersion = spec.thisVersion ?: throw UsageError("spec.thisVersion must be defined")

        t.info(
            """
1. Open the "Internal testing" release track https://play.google.com/console/u/0/developers/6635489183012320500/app/4974842442400419596/tracks/internal-testing
2. Confirm the testing version is ${thisVersion.versionCode}
3. Click "Promote release" and choose "Production"
4. Confirm correct version code and release notes are present
5. Click "Next"
6. Set the rollout percentage to 100%
7. Click "Save"
8. Go to the publishing overview when prompted
9. Send the change for review
            """.trimIndent(),
        )

        while (!t.confirm("Have you done all this?")) { }
        return null
    }
}

@Serializable
data object FinalizeGithubRelease : ReleaseStep() {
    override fun run(t: Terminal, config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val thisVersion = spec.thisVersion ?: throw UsageError("spec.thisVersion must be defined")
        val githubToken = System.getenv("GITHUB_TOKEN")
            ?: throw UsageError("GITHUB_TOKEN is null")
        val github = GitHubBuilder().withOAuthToken(githubToken).build()

        // As a draft release getReleaseByTagName doesn't find it. So fetch the
        // most recent releases and search for it in those.
        val repo = github.getRepository(
            "${config.repositoryMain.owner}/${config.repositoryMain.repo}",
        )

        val release = repo.listReleases().toList().find { it.tagName == thisVersion.versionTag() }
            ?: throw UsageError("Could not find Github release for tag ${spec.releaseTag()}")

        val makeLatest = when (thisVersion) {
            is PachliVersion.Beta -> GHReleaseBuilder.MakeLatest.FALSE
            is PachliVersion.Release -> GHReleaseBuilder.MakeLatest.TRUE
        }

        release.update()
            .draft(false)
            .makeLatest(makeLatest)
            .prerelease(thisVersion !is PachliVersion.Release)
            .update()

        t.success("Release has been undrafted")
        if (makeLatest == GHReleaseBuilder.MakeLatest.TRUE) {
            t.success("Release has been marked as the latest")
        }

        return null
    }
}

@Serializable
data object SyncFDroidRepository : ReleaseStep() {
    override fun run(t: Terminal, config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val api = GitLabApi("https://gitlab.com", System.getenv("GITLAB_TOKEN"))
        val forkedRepo = "nikclayton/fdroiddata"

        val project = api.projectApi.getProject(forkedRepo)
        if (project.forkedFromProject.httpUrlToRepo != "https://gitlab.com/fdroid/fdroiddata.git") {
            throw UsageError("$forkedRepo is not forked from fdroid/fdroiddata")
        }
        println(project)

        val defaultBranchRef = Git.lsRemoteRepository()
            .setRemote(config.repositoryFDroidFork.gitUrl.toString())
            .callAsMap()["HEAD"]?.target?.name ?: throw UsageError("Could not determine default branch name")
        println("default branch ref is $defaultBranchRef")
        val defaultBranch = defaultBranchRef.split("/").last()

        val git = ensureRepo(
            t,
            config.repositoryFDroidFork.gitUrl,
            config.fdroidForkRoot,
        )
            .also { it.ensureClean(t) }

        // Sync the fork with the parent

        // git remote add upstream https://...
        git.remoteAdd()
            .setName("upstream")
            .setUri(URIish(project.forkedFromProject.httpUrlToRepo))
            .info(t)
            .call()

        // git checkout main
        git.checkout()
            .setName(defaultBranch)
            .info(t)
            .call()

        // git fetch upstream
        git.fetch()
            .setRemote("upstream")
            .setProgressMonitor(TextProgressMonitor())
            .info(t)
            .call()

        // git pull upstream $defaultBranch
        git.pull()
            .setRemote("upstream")
            .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
            .setProgressMonitor(TextProgressMonitor())
            .info(t)
            .call()

        // git push origin $defaultBranch
        git.push()
            .setCredentialsProvider(DelegatingCredentialsProvider(config.fdroidForkRoot.toPath()))
            .setRemote("origin")
            .setProgressMonitor(TextProgressMonitor())
            .info(t)
            .call()

        return null
    }
}

@Serializable
data object MakeFDroidReleaseBranch : ReleaseStep() {
    override fun run(t: Terminal, config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val git = ensureRepo(t, config.repositoryFDroidFork.gitUrl, config.fdroidForkRoot)
            .also { it.ensureClean(t) }

        val branch = spec.fdroidReleaseBranch()

        try {
            git.branchCreate()
                .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.SET_UPSTREAM)
                .setName(branch)
                .info(t)
                .call()
        } catch (e: RefAlreadyExistsException) {
            throw BranchExists("Branch $branch already exists")
        }

        git.checkout()
            .setName(branch)
            .info(t)
            .call()

        return null
    }
}

@Serializable
data object ModifyFDroidYaml : ReleaseStep() {
    override fun run(t: Terminal, config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val thisVersion = spec.thisVersion ?: throw UsageError("releaseSpec.thisVersion must be defined")
        val branch = spec.fdroidReleaseBranch()
        val git = ensureRepo(t, config.repositoryFDroidFork.gitUrl, config.fdroidForkRoot)
            .also { it.ensureClean(t) }

        git.checkout()
            .setName(branch)
            .info(t)
            .call()

        val metadataPath = "metadata/app.pachli.yml"

        // Parsing the YAML in to a data class, amending the data, and then
        // writing it back out is problematic for a few reasons:
        //
        // 1. Order of items is not guaranteed, resulting in spurious diffs
        // 2. Comments are probably not preserved
        // 3. Failing to fully specify all the YAML elements would result in
        //    missing data in the result.
        //
        // Simpler to treat it as line-oriented records, and emit the correct
        // data at the correct place.

        val metadataFile = File(config.fdroidForkRoot, metadataPath)
        val tmpFile = createTempFile().toFile()
        val w = tmpFile.printWriter()
        metadataFile.forEachLine { line ->
            if (line == "AutoUpdateMode: Version") {
                w.println(
                    """  - versionName: ${thisVersion.versionName()}
    versionCode: ${thisVersion.versionCode}
    commit: ${thisVersion.versionTag()}
    subdir: app
    gradle:
      - blue
""",
                )
            }
            w.println(line)
        }
        w.close()
        Files.move(tmpFile.toPath(), metadataFile.toPath(), StandardCopyOption.REPLACE_EXISTING)

        git.add()
            .setUpdate(false)
            .addFilepattern(metadataPath)
            .info(t)
            .call()

        var changesAreOk = false
        while (!changesAreOk) {
            git.diff().setOutputStream(System.out).setCached(true).call()
            changesAreOk = t.confirm("Do these changes look OK?")
            if (!changesAreOk) {
                TermUi.editFile(metadataPath)
                git.add().addFilepattern(metadataPath).info(t).call()
            }
        }

        val commitMsg = "Pachli ${thisVersion.versionName()} (${thisVersion.versionCode})"
        git.commit()
            .setMessage(commitMsg)
            .setSign(null)
            .setCredentialsProvider(PasswordCredentialsProvider(t))
            .info(t)
            .call()

        git.push()
            .setCredentialsProvider(DelegatingCredentialsProvider(config.fdroidForkRoot.toPath()))
            .setRemote("origin")
            .setRefSpecs(RefSpec("$branch:$branch"))
            .info(t)
            .call()

        return null
    }
}

@Serializable
data object CreateFDroidMergeRequest : ReleaseStep() {
    override fun run(t: Terminal, config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val branch = spec.fdroidReleaseBranch()

        t.info(
            """
                This is done by hand at the moment, to complete the merge request template")

                1. Open ${config.repositoryFDroidFork.gitlabUrl}/-/merge_requests/new?merge_request%5Bsource_branch%5D=$branch
                2. Set and apply the "App update" template
                3. Tick the relevant boxes
                4. Click "Create merge request"

            """.trimIndent(),
        )

        while (!t.confirm("Have you done all this?")) { }

        return null
    }
}

@Serializable
data object AnnounceTheBetaRelease : ReleaseStep() {
    override fun run(t: Terminal, config: Config, spec: ReleaseSpec): ReleaseSpec {
        t.info("- Announce the beta release, and you're done.")

        while (!t.confirm("Have you done all this?")) { }

        // Done. This version can now be marked as the previous version
        // See SaveFinalRelease]
        val releaseSpec = ReleaseSpec.from(SPEC_FILE)
        return releaseSpec.copy(
            prevVersion = releaseSpec.thisVersion!!,
            thisVersion = null,
            pullRequest = null,
        )
    }
}

@Serializable
data object SaveFinalRelease : ReleaseStep() {
    override fun run(t: Terminal, config: Config, spec: ReleaseSpec): ReleaseSpec? {
        return spec.copy(
            prevVersion = spec.thisVersion!!,
            thisVersion = null,
            pullRequest = null,
        )
    }
}
