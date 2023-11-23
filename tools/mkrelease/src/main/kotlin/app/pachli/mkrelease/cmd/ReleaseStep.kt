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
import app.pachli.mkrelease.T
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
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
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.createTempFile
import kotlin.time.Duration.Companion.seconds

/**
 * One or more pieces of work that to complete a release, but that should be completed as an
 * atomic unit
 */
@Serializable
sealed class ReleaseStep {
    abstract fun run(config: Config, spec: ReleaseSpec): ReleaseSpec?
    open fun desc(): String = this.javaClass.simpleName
}

@Serializable
data object EnsureCleanReleaseSpec : ReleaseStep() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec? {
        spec.thisVersion?.let {
            throw UsageError("thisVersion is set in the release spec, there is an ongoing release process: $it")
        }

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
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val repo = config.repositoryFork
        val root = config.pachliForkRoot

        val git = ensureRepo(repo.gitUrl, root)
            .also { it.ensureClean() }

        // git remote add upstream https://...
        git.remoteAdd()
            .setName("upstream")
            .setUri(URIish(config.repositoryMain.gitUrl))
            .info()
            .call()

        val defaultBranchRef = Git.lsRemoteRepository()
            .setRemote(repo.gitUrl.toString())
            .callAsMap()["HEAD"]?.target?.name ?: throw UsageError("Could not determine default branch name")
        T.info("default branch ref is $defaultBranchRef")
        val defaultBranch = defaultBranchRef.split("/").last()

        T.info("default branch: $defaultBranch")

        // git checkout main
        git.checkout()
            .setName(defaultBranch)
            .info()
            .call()

        // git fetch upstream
        git.fetch()
            .setRemote("upstream")
            .setProgressMonitor(TextProgressMonitor())
            .info()
            .call()

        // git pull upstream $defaultBranch
        // - FF_ONLY, a non-FF pull indicates a merge commit is needed, which is bad
        git.pull()
            .setRemote("upstream")
            .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
            .setProgressMonitor(TextProgressMonitor())
            .info()
            .call()

        // git push origin $defaultBranch
        git.push()
            .setCredentialsProvider(DelegatingCredentialsProvider(root.toPath()))
            .setRemote("origin")
            .setProgressMonitor(TextProgressMonitor())
            .info()
            .call()

        // Checkout `main` branch,
        git.checkout().setName("main").info().call()

        // Pull everything.
        // - FF_ONLY, a non-FF pull indicates a merge commit is needed, which is bad
//            git.pull()
//                .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
//                .call()
        git.ensureClean()

        return null
    }
}

/**
 * @return ReleaseSpec with [prevVersion] set to the release that is currently
 * live (i.e., the one this release process will replace).
 */
@Serializable
data object GetCurrentAppVersion : ReleaseStep() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec {
        val repo = config.repositoryFork
        val root = config.pachliForkRoot

        ensureRepo(repo.gitUrl, root)
            .also { it.ensureClean() }

        T.info("- Determining current release version")
        val currentRelease = getGradle(root).use {
            val androidDsl = it.model(AndroidDsl::class.java).get()
            val versionCode = androidDsl.defaultConfig.versionCode
                ?: throw UsageError("No versionCode in Gradle config")
            val versionName = androidDsl.defaultConfig.versionName
                ?: throw UsageError("No versionName in Gradle config")
            PachliVersion.from(versionName, versionCode)
                ?: throw UsageError("Could not parse '$versionName' as release version")
        }

        T.success("  $currentRelease")

        return spec.copy(
            prevVersion = currentRelease
        )
    }
}

/**
 * @throws if [ReleaseSpec.prevVersion] is not a [PachliVersion.Beta]
 */
@Serializable
data object ConfirmCurrentVersionIsBeta : ReleaseStep() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec? {
        if (spec.prevVersion !is PachliVersion.Beta) {
            throw UsageError("Current Pachli version is not a beta")
        }

        T.success("Current version (${spec.prevVersion}) is a beta")
        return null
    }
}

@Serializable
data object SetNextVersionAsBeta : ReleaseStep() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val releaseVersion = spec.prevVersion.next(spec.releaseType)
        T.success("Release version is $releaseVersion")
        return spec.copy(thisVersion = releaseVersion)
    }
}

@Serializable
data object SetNextVersionAsRelease : ReleaseStep() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec {
        val releaseVersion = when (spec.prevVersion) {
            is PachliVersion.Beta -> spec.prevVersion.release()
            is PachliVersion.Release -> spec.prevVersion.release(spec.releaseType)
        }
//        val releaseVersion = (spec.prevVersion as PachliVersion.Beta).release()
        T.success("Release version is $releaseVersion")
        return spec.copy(thisVersion = releaseVersion)
    }
}

class BranchExists(message: String) : Throwable(message)

@Serializable
data object CreateReleaseBranch : ReleaseStep() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val repo = config.repositoryFork
        val root = config.pachliForkRoot

        val git = ensureRepo(repo.gitUrl, root)
            .also { it.ensureClean() }

        // Create branch (${issue}-${major}.${minor}-b${beta})
        val branch = spec.releaseBranch()
        T.info("- Release branch will be $branch")

        try {
            git.branchCreate()
                // Below was SET_UPSTREAM, but that's deprecated in Git docs
                .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                .setName(branch)
                .info()
                .call()
        } catch (e: RefAlreadyExistsException) {
            throw BranchExists("Branch $branch already exists")
        }

        T.success("  ... done.")

        return null
    }
}

class BranchMissing(message: String) : Exception(message)

@Serializable
data object UpdateFilesForRelease : ReleaseStep() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val repo = config.repositoryFork
        val root = config.pachliForkRoot

        val git = ensureRepo(repo.gitUrl, root)
            .also { it.ensureClean() }

        val branch = spec.releaseBranch()
        spec.thisVersion ?: throw UsageError("releaseSpec.thisVersion is null and should not be")

        if (!git.hasBranch("refs/heads/$branch")) {
            throw BranchMissing("Branch $branch should exist but is missing")
        }

        // Switch to branch
        // TODO: This will fail if the branch doesn't exist, maybe the previous check is unnecessary
        git.checkout().setName(branch).info().call()

        // No API to update the files, so edit in place
        val buildDotGradleFile = File(File(root, "app"), "build.gradle")
        val content = buildDotGradleFile.readText()
        content.contains("versionCode ${spec.prevVersion.versionCode}") || throw UsageError("can't find 'versionCode ${spec.prevVersion.versionCode}' in $buildDotGradleFile")
        content.contains("versionName \"${spec.prevVersion.versionName()}\"") || throw UsageError("can't find 'versionName \"${spec.prevVersion.versionName()}\"' in $buildDotGradleFile")

        buildDotGradleFile.writeText(
            content
                .replace(
                    "versionCode ${spec.prevVersion.versionCode}",
                    "versionCode ${spec.thisVersion.versionCode}"
                )
                .replace(
                    "versionName \"${spec.prevVersion.versionName()}\"",
                    "versionName \"${spec.thisVersion.versionName()}\""
                )
        )

        // TODO: Check that spec.prevVersion.versionTag exists, to provide a better
        // error message here. If you don't do this the git.log() command a few lines
        // later dies with "findRef(...) must not be null"

        // Construct the initial change log by looking for conventional commits
        // with type 'feat' and 'fix'.
        val changelogEntries = git.log()
            .addRange(
                git.getActualRefObjectId(spec.prevVersion.versionTag()),
                git.getActualRefObjectId("main")
            )
            .info().call().mapNotNull {
                val components = it.shortMessage.split(":", limit = 2)
                T.info(components)
                if (components.size != 2) return@mapNotNull null

                val section = Section.fromCommitTitle(it.shortMessage)

                if (section == Section.Unknown) return@mapNotNull null

                LogEntry(section, components[1], it.authorIdent)
            }
            .groupBy { it.section }

        T.info(changelogEntries)
        // Add entry to CHANGELOG.md
        // No good third-party libraries for parsing Markdown to an AST **and then manipulating
        // that tree**, so add the new block by hand
        val ChangelogFile = File(config.pachliForkRoot, "CHANGELOG.md")
        val tmpFile = createTempFile().toFile()
        val w = tmpFile.printWriter()
        ChangelogFile.useLines { lines ->
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
"""
                        )
                    }

                    if (changelogEntries[Fixes]?.isNotEmpty() == true) {
                        w.println(
                            """
### Significant bug fixes

${changelogEntries[Fixes]?.joinToString("\n") { "-${it.withLinks()}" }}

""".trimIndent()
                        )
                    }

                    if (changelogEntries[Translations]?.isNotEmpty() == true) {
                        w.println(
                            """
### Translations

${changelogEntries[Translations]?.joinToString("\n") { "-${it.withLinks()}" }}

""".trimIndent()
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
            ChangelogFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING
        )

        T.info("- Edit CHANGELOG.md for this release")
        T.muted("  To see what's changed between this release and the last,")
        T.muted("  https://github.com/pachli/pachli-android/compare/${spec.prevVersion.versionTag()}...main")
        TermUi.editFile(ChangelogFile.toString())

        // TODO: Check the "ENTER NEW LOG HERE" string has been removed

        // Pull out the new lines from the changelog
        val fastlaneFile = spec.fastlaneFile(config.pachliForkRoot)
        createFastlaneFromChangelog(ChangelogFile, fastlaneFile, spec.thisVersion.versionName())

        git.add()
            .setUpdate(false)
            .addFilepattern("CHANGELOG.md")
            .addFilepattern("app/build.gradle")
            .addFilepattern(spec.fastlanePath())
            .info()
            .call()

        var changesAreOk = false
        while (!changesAreOk) {
            git.diff()
                .setOutputStream(System.out)
                .setCached(true)
                .call()
            changesAreOk = T.confirm("Do these changes look OK?")
            if (!changesAreOk) {
                val r = T.prompt(
                    TextColors.yellow("Edit Changelog or Fastlane?"),
                    choices = listOf("c", "f")
                )
                when (r) {
                    "c" -> {
                        TermUi.editFile(ChangelogFile.toString())
                        createFastlaneFromChangelog(ChangelogFile, fastlaneFile, spec.thisVersion.versionName())
                    }
                    "f" -> TermUi.editFile(fastlaneFile.toString())
                }
                git.add()
                    .addFilepattern("CHANGELOG.md")
                    .addFilepattern(spec.fastlanePath())
                    .info()
                    .call()
            }
        }

        // Commit
        val commitMsg =
            "chore: Prepare release ${spec.thisVersion.versionName()} (versionCode ${spec.thisVersion.versionCode})"
        T.info("""- git commit -m "$commitMsg"""")
        git.commit()
            .setMessage(commitMsg)
            .setSign(null)
            .setCredentialsProvider(PasswordCredentialsProvider())
            .info()
            .call()

        // Push
        T.info("- Pushing changes to ${config.repositoryFork.githubUrl}")
        git.push()
            .setCredentialsProvider(DelegatingCredentialsProvider(config.pachliForkRoot.toPath()))
            .setRemote("origin")
            .setRefSpecs(RefSpec("$branch:$branch"))
            .info()
            .call()

        return null
    }
}

@Serializable
data object CreateReleaseBranchPullRequest : ReleaseStep() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec? {
        T.info("- Create pull request at https://github.com/${config.repositoryMain.owner}/${config.repositoryMain.repo}/compare/main...${config.repositoryFork.owner}:${config.repositoryFork.repo}:${spec.releaseBranch()}?expand=1")
        return null
    }
}

/**
 * Prompts for a pull request URL and saves it to [ReleaseSpec.pullRequest].
 */
@Serializable
data object SavePullRequest : ReleaseStep() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec {
        val pullRequest = GitHubPullRequest(
            URL(T.prompt(TextColors.yellow("Enter pull request URL")))
        )
        return spec.copy(pullRequest = pullRequest)
    }
}

/**
 * Waits for [ReleaseSpec.pullRequest] to be merged.
 */
@Serializable
data object WaitForPullRequestMerged : ReleaseStep() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec? = runBlocking {
        val pullRequest = spec.pullRequest
            ?: throw UsageError("pullRequest is null, but should not be")

        val githubToken = System.getenv("GITHUB_TOKEN")
            ?: throw UsageError("GITHUB_TOKEN is null")

        val repo = config.repositoryMain

        val github = GitHubBuilder().withOAuthToken(githubToken).build()
        val pull = github.getRepository("${repo.owner}/${repo.repo}").getPullRequest(pullRequest.number.toInt())

        T.info("- Checking $pullRequest")
        T.success("  Title: ${pull.title}")

        do {
            if (pull.isMerged) {
                T.success("Has been merged")
                break
            }
            var lines = 0
            if (pull.isDraft) {
                T.warning("  Marked as draft")
                lines++
            }
            if (!pull.mergeable) {
                T.warning("  Not currently mergeable")
                lines++
            }

            // If there are no requested reviewers and no reviews then one or more
            // reviewers needs to be assigned.
            val requestedReviewers = pull.requestedReviewers
            val reviews = pull.listReviews().toList()
            if (requestedReviewers.isEmpty() && reviews.isEmpty()) {
                T.warning("  No reviewers are assigned")
                lines++
            } else if (reviews.isEmpty()) {
                T.info("  Waiting for review from ${requestedReviewers.map { it.login }}")
                lines++
            } else {
                for (review in reviews) {
                    when (review.state) {
                        GHPullRequestReviewState.PENDING -> T.info("  ${review.user.login}: PENDING")
                        GHPullRequestReviewState.APPROVED -> T.success("  ${review.user.login}: APPROVED")
                        GHPullRequestReviewState.CHANGES_REQUESTED,
                        GHPullRequestReviewState.REQUEST_CHANGES -> T.danger("  ${review.user.login}: CHANGES_REQUESTED")
                        GHPullRequestReviewState.COMMENTED -> T.warning("  ${review.user.login}: COMMENTED")
                        GHPullRequestReviewState.DISMISSED -> T.warning("  ${review.user.login}: DISMISSED")
                        null -> T.danger("  ${review.user.login}: null state")
                    }
                    lines++
                }
            }
            repeat(300) {
                T.info("Waiting until next check in: $it / 300 seconds")
                delay(1.seconds)
                T.cursor.move {
                    up(1)
                    startOfLine()
                    clearLineAfterCursor()
                }
            }
            T.cursor.move {
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
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val repo = config.repositoryMain
        val root = config.pachliMainRoot

        val pullRequest = spec.pullRequest
            ?: throw UsageError("pullRequest is null, but should not be")

        val githubToken = System.getenv("GITHUB_TOKEN")
            ?: throw UsageError("GITHUB_TOKEN is null")

        val git = ensureRepo(repo.gitUrl, root).also { it.ensureClean() }

        git.fetch()
            .setProgressMonitor(TextProgressMonitor())
            .info()
            .call()
        git.checkout().setName("develop").info().call()
        git.pull()
            .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
            .setProgressMonitor(TextProgressMonitor())
            .info()
            .call()

        val github = GitHubBuilder().withOAuthToken(githubToken).build()
        val pull = github.getRepository("${repo.owner}/${repo.repo}").getPullRequest(pullRequest.number.toInt())

        pull.isMerged || throw UsageError("$pullRequest is not merged!")

        T.info("- Merge commit SHA: ${pull.mergeCommitSha}")
        val mergeCommitSha = ObjectId.fromString(pull.mergeCommitSha)
        git.log()
            .add(mergeCommitSha)
            .setMaxCount(1)
            .info()
            .call()
            .forEach { println(it.message()) }

        T.confirm("Does that look like the correct commit on develop?", abort = true)

        T.info("- Syncing main branch")
        git.checkout()
            .setCreateBranch(!git.hasBranch("refs/heads/main"))
            .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
            .setName("main")
            .setStartPoint("origin/main")
            .info()
            .call()
        git.pull()
            .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
            .setProgressMonitor(TextProgressMonitor())
            .info()
            .call()
        git.log().setMaxCount(1).info().call().forEach { println(it.message()) }

        T.confirm("Does that look like the correct most recent commit on main?", abort = true)

        val headBeforeMerge = git.repository.resolve("main")
        git.merge()
            .include(mergeCommitSha)
            .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
            .info()
            .call()

        // TODO: This should probably show the short title, not the full message
        git.log()
            .addRange(headBeforeMerge, mergeCommitSha)
            .info()
            .call()
            .forEach { println(it.message()) }

        T.confirm("Does that look like the correct commits have been merged to main?", abort = true)
        return null
    }
}

@Serializable
data object FetchMainToTag : ReleaseStep() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val repo = config.repositoryMain
        val root = config.pachliMainRoot

        val pullRequest = spec.pullRequest
            ?: throw UsageError("pullRequest is null, but should not be")

        val githubToken = System.getenv("GITHUB_TOKEN")
            ?: throw UsageError("GITHUB_TOKEN is null")

        val git = ensureRepo(repo.gitUrl, root).also { it.ensureClean() }

        git.fetch()
            .setProgressMonitor(TextProgressMonitor())
            .info()
            .call()
        git.checkout().setName("main").info().call()
        git.pull()
            .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
            .setProgressMonitor(TextProgressMonitor())
            .info()
            .call()

        val github = GitHubBuilder().withOAuthToken(githubToken).build()
        val pull = github.getRepository("${repo.owner}/${repo.repo}").getPullRequest(pullRequest.number.toInt())

        pull.isMerged || throw UsageError("$pullRequest is not merged!")

        T.info("- Merge commit SHA: ${pull.mergeCommitSha}")
        val mergeCommitSha = ObjectId.fromString(pull.mergeCommitSha)
        git.log()
            .add(mergeCommitSha)
            .setMaxCount(1)
            .info()
            .call()
            .forEach { println(it.message()) }

        T.confirm("Does that look like the correct commit on main?", abort = true)

//        T.info("- Syncing main branch")
//        git.checkout()
//            .setCreateBranch(!git.hasBranch("refs/heads/main"))
//            .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
//            .setName("main")
//            .setStartPoint("origin/main")
//            .info()
//            .call()
//        git.pull()
//            .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
//            .setProgressMonitor(TextProgressMonitor())
//            .info()
//            .call()
//        git.log().setMaxCount(1).info().call().forEach { println(it.message()) }
//
//        T.confirm("Does that look like the correct most recent commit on main?", abort = true)

//        val headBeforeMerge = git.repository.resolve("main")
//        git.merge()
//            .include(mergeCommitSha)
//            .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
//            .info()
//            .call()
//
//        // TODO: This should probably show the short title, not the full message
//        git.log()
//            .addRange(headBeforeMerge, mergeCommitSha)
//            .info()
//            .call()
//            .forEach { println(it.message()) }
//
//        T.confirm("Does that look like the correct commits have been merged to main?", abort = true)
        return null    }
}

@Serializable
data object TagMainAsRelease : ReleaseStep() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val repo = config.repositoryMain
        val root = config.pachliMainRoot
        val git = ensureRepo(repo.gitUrl, root).also { it.ensureClean() }

        git.checkout().setName("main").info().call()
        val tag = spec.releaseTag()
        git.tag().setCredentialsProvider(PasswordCredentialsProvider())
            .setName(tag)
            .setMessage(tag)
            .setSigned(true)
            .info()
            .call()

        return null
    }
}

@Serializable
data object PushTaggedMain : ReleaseStep() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val repo = config.repositoryMain
        val root = config.pachliMainRoot
        val git = ensureRepo(repo.gitUrl, root).also { it.ensureClean() }

        git.push()
            .setCredentialsProvider(DelegatingCredentialsProvider(config.pachliMainRoot.toPath()))
            .setProgressMonitor(TextProgressMonitor())
            .setPushAll()
            .setPushTags()
            .info()
            .call()

        return null
    }
}


@Serializable
data object CreateGithubRelease : ReleaseStep() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val githubToken = System.getenv("GITHUB_TOKEN")
            ?: throw UsageError("GITHUB_TOKEN is null")
        val github = GitHubBuilder().withOAuthToken(githubToken).build()

        val thisVersion = spec.thisVersion ?: throw UsageError("spec.thisVersion is null!")

        val changeLogFile = File(config.pachliMainRoot, "CHANGELOG.md")
        val changes = getChangelog(changeLogFile, thisVersion.versionName())

        val repo = github.getRepository(
            "${config.repositoryMain.owner}/${config.repositoryMain.repo}"
        )

        val release = GHReleaseBuilder(repo, spec.releaseTag())
            .name(spec.githubReleaseName())
            .body(changes)
            .draft(true)
            .categoryName("Announcements")
            .prerelease(thisVersion !is PachliVersion.Release)
            .makeLatest(GHReleaseBuilder.MakeLatest.FALSE)
            .create()

        T.success("Created Github release: ${release.htmlUrl}")

        return null
    }
}

@Serializable
data object WaitForBitriseToBuild : ReleaseStep() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec? {
        T.info(
            """
                Wait for Bitrise to build and upload the APK to Google Play.

                Check https://app.bitrise.io/app/a3e773c3c57a894c?workflow=workflow-release
            """.trimIndent()
        )

        while (!T.confirm("Has Bitrise uploaded the APK?")) { }
        return null
    }
}

@Serializable
data object MarkAsBetaOnPlay : ReleaseStep() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec? {
        T.info("Run the workflow https://github.com/pachli/pachli-android/actions/workflows/upload-blue-release-google-play.yml")
        while (!T.confirm("Have you done all this?")) { }
        return null
    }
}

@Serializable
data object DownloadApk : ReleaseStep() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val thisVersion = spec.thisVersion ?: throw UsageError("spec.thisVersion must be defined")

        T.info(
            """
1. Open https://play.google.com/console/u/0/developers/8419715224772184120/app/4973838218515056581/bundle-explorer-selector
2. Click the row for ${thisVersion.versionCode}
3. Click the 'Downloads' tab
4. Download the entry 'Signed, universal APK

This should download ${thisVersion.versionCode}.apk

            """.trimIndent()
        )

        while (!T.confirm("Have you done all this?")) { }
        return null
    }
}

@Serializable
data object AttachApkToGithubRelease : ReleaseStep() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val thisVersion = spec.thisVersion ?: throw UsageError("spec.thisVersion must be defined")
        val githubToken = System.getenv("GITHUB_TOKEN")
            ?: throw UsageError("GITHUB_TOKEN is null")
        val github = GitHubBuilder().withOAuthToken(githubToken).build()

        // As a draft release getReleaseByTagName doesn't find it. So fetch the
        // most recent releases and search for it in those.
        val repo = github.getRepository(
            "${config.repositoryMain.owner}/${config.repositoryMain.repo}"
        )

        val release = repo.listReleases().toList().find { it.tagName == thisVersion.versionTag() }
            ?: throw UsageError("Could not find Github release for tag ${spec.releaseTag()}")

        val apk = File("../../Downloads/${thisVersion.versionCode}.apk")
        T.info("- Uploading ${apk.toPath()}")
        release.uploadAsset(apk, "application/vnd.android.package-archive")

        return null
    }
}

@Serializable
data object PromoteRelease : ReleaseStep() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val thisVersion = spec.thisVersion ?: throw UsageError("spec.thisVersion must be defined")

        T.info(
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
            """.trimIndent()
        )

        while (!T.confirm("Have you done all this?")) { }
        return null
    }
}

@Serializable
data object FinalizeGithubRelease : ReleaseStep() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val thisVersion = spec.thisVersion ?: throw UsageError("spec.thisVersion must be defined")
        val githubToken = System.getenv("GITHUB_TOKEN")
            ?: throw UsageError("GITHUB_TOKEN is null")
        val github = GitHubBuilder().withOAuthToken(githubToken).build()

        // As a draft release getReleaseByTagName doesn't find it. So fetch the
        // most recent releases and search for it in those.
        val repo = github.getRepository(
            "${config.repositoryMain.owner}/${config.repositoryMain.repo}"
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

        T.success("Release has been undrafted")
        if (makeLatest == GHReleaseBuilder.MakeLatest.TRUE) {
            T.success("Release has been marked as the latest")
        }

        return null
    }
}

@Serializable
data object SyncFDroidRepository : ReleaseStep() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec? {
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
            config.repositoryFDroidFork.gitUrl,
            config.fdroidForkRoot
        )
            .also { it.ensureClean() }

        // Sync the fork with the parent

        // git remote add upstream https://...
        git.remoteAdd()
            .setName("upstream")
            .setUri(URIish(project.forkedFromProject.httpUrlToRepo))
            .info()
            .call()

        // git checkout main
        git.checkout()
            .setName(defaultBranch)
            .info()
            .call()

        // git fetch upstream
        git.fetch()
            .setRemote("upstream")
            .setProgressMonitor(TextProgressMonitor())
            .info()
            .call()

        // git pull upstream $defaultBranch
        git.pull()
            .setRemote("upstream")
            .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
            .setProgressMonitor(TextProgressMonitor())
            .info()
            .call()

        // git push origin $defaultBranch
        git.push()
            .setCredentialsProvider(DelegatingCredentialsProvider(config.fdroidForkRoot.toPath()))
            .setRemote("origin")
            .setProgressMonitor(TextProgressMonitor())
            .info()
            .call()

        return null
    }
}

@Serializable
data object MakeFDroidReleaseBranch : ReleaseStep() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val git = ensureRepo(config.repositoryFDroidFork.gitUrl, config.fdroidForkRoot)
            .also { it.ensureClean() }

        val branch = spec.fdroidReleaseBranch()

        try {
            git.branchCreate()
                .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.SET_UPSTREAM)
                .setName(branch)
                .info()
                .call()
        } catch (e: RefAlreadyExistsException) {
            throw BranchExists("Branch $branch already exists")
        }

        git.checkout()
            .setName(branch)
            .info()
            .call()

        return null
    }
}

@Serializable
data object ModifyFDroidYaml : ReleaseStep() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val thisVersion = spec.thisVersion ?: throw UsageError("releaseSpec.thisVersion must be defined")
        val branch = spec.fdroidReleaseBranch()
        val git = ensureRepo(config.repositoryFDroidFork.gitUrl, config.fdroidForkRoot)
            .also { it.ensureClean() }

        git.checkout()
            .setName(branch)
            .info()
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
"""
                )
            }
            w.println(line)
        }
        w.close()
        Files.move(tmpFile.toPath(), metadataFile.toPath(), StandardCopyOption.REPLACE_EXISTING)

        git.add()
            .setUpdate(false)
            .addFilepattern(metadataPath)
            .info()
            .call()

        var changesAreOk = false
        while (!changesAreOk) {
            git.diff().setOutputStream(System.out).setCached(true).call()
            changesAreOk = T.confirm("Do these changes look OK?")
            if (!changesAreOk) {
                TermUi.editFile(metadataPath)
                git.add().addFilepattern(metadataPath).info().call()
            }
        }

        val commitMsg = "Pachli ${thisVersion.versionName()} (${thisVersion.versionCode})"
        git.commit()
            .setMessage(commitMsg)
            .setSign(null)
            .setCredentialsProvider(PasswordCredentialsProvider())
            .info()
            .call()

        git.push()
            .setCredentialsProvider(DelegatingCredentialsProvider(config.fdroidForkRoot.toPath()))
            .setRemote("origin")
            .setRefSpecs(RefSpec("$branch:$branch"))
            .info()
            .call()

        return null
    }
}

@Serializable
data object CreateFDroidMergeRequest : ReleaseStep() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val branch = spec.fdroidReleaseBranch()

        T.info(
            """
                This is done by hand at the moment, to complete the merge request template")

                1. Open ${config.repositoryFDroidFork.gitlabUrl}/-/merge_requests/new?merge_request%5Bsource_branch%5D=$branch
                2. Set and apply the "App update" template
                3. Tick the relevant boxes
                4. Click "Create merge request"

            """.trimIndent()
        )

        while (!T.confirm("Have you done all this?")) { }

        return null
    }
}

@Serializable
data object AnnounceTheBetaRelease : ReleaseStep() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec {
        T.info("- Announce the beta release, and you're done.")

        while (!T.confirm("Have you done all this?")) { }

        // Done. This version can now be marked as the previous version
        val releaseSpec = ReleaseSpec.from(SPEC_FILE)
        return releaseSpec.copy(
            prevVersion = releaseSpec.thisVersion!!,
            thisVersion = null,
            pullRequest = null
        )
    }
}
