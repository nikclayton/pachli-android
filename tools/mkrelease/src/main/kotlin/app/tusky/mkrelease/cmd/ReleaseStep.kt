/*
 * Copyright 2023 Tusky Contributors
 *
 * This file is a part of Tusky.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation; either version 3 of the
 * License, or (at your option) any later version.
 *
 * Tusky is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Tusky; if not,
 * see <http://www.gnu.org/licenses>.
 */

package app.tusky.mkrelease.cmd

import app.tusky.mkrelease.Config
import app.tusky.mkrelease.DelegatingCredentialsProvider
import app.tusky.mkrelease.GitHubPullRequest
import app.tusky.mkrelease.PasswordCredentialsProvider
import app.tusky.mkrelease.ReleaseSpec
import app.tusky.mkrelease.SPEC_FILE
import app.tusky.mkrelease.T
import app.tusky.mkrelease.TuskyVersion
import app.tusky.mkrelease.confirm
import app.tusky.mkrelease.createFastlaneFromChangelog
import app.tusky.mkrelease.ensureClean
import app.tusky.mkrelease.ensureRepo
import app.tusky.mkrelease.getChangelog
import app.tusky.mkrelease.getGradle
import app.tusky.mkrelease.hasBranch
import app.tusky.mkrelease.info
import app.tusky.mkrelease.message
import com.android.builder.model.v2.models.AndroidDsl
import com.github.ajalt.clikt.core.CliktCommand
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
    abstract val config: Config

    /**
     * Do the work to move on to the next step.
     *
     * @return The next step in the process, or null if this is the final step.
     */
    abstract fun run(cmd: CliktCommand): ReleaseStep?

    open fun desc(): String = this.javaClass.simpleName
}

@Serializable
sealed class ReleaseStep2 {
    abstract fun run(config: Config, spec: ReleaseSpec): ReleaseSpec?
    open fun desc(): String = this.javaClass.simpleName
}


/**
 * Prepares the copy of the Tusky repository fork.
 *
 * - Ensures repo exists, is checked out, and clean.
 * - Sets upstream remote
 */
@Serializable
object PrepareTuskyForkRepository : ReleaseStep2() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val repo = config.repositoryFork
        val root = config.tuskyForkRoot

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

        // Checkout `develop` branch,
        git.checkout().setName("develop").info().call()

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
object GetCurrentAppVersion : ReleaseStep2() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec {
        val repo = config.repositoryFork
        val root = config.tuskyForkRoot

        ensureRepo(repo.gitUrl, root)
            .also { it.ensureClean() }

        T.info("- Determining current release version")
        val currentRelease = getGradle(root).use {
            val androidDsl = it.model(AndroidDsl::class.java).get()
            val versionCode = androidDsl.defaultConfig.versionCode
                ?: throw UsageError("No versionCode in Gradle config")
            val versionName = androidDsl.defaultConfig.versionName
                ?: throw UsageError("No versionName in Gradle config")
            TuskyVersion.from(versionName, versionCode)
                ?: throw UsageError("Could not parse '$versionName' as release version")
        }

        T.success("  $currentRelease")

        return spec.copy(
            prevVersion = currentRelease,
        )
    }
}

/**
 * @throws if [ReleaseSpec.prevVersion] is not a [TuskyVersion.Beta]
 */
@Serializable
object ConfirmCurrentVersionIsBeta : ReleaseStep2() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec? {
        if (spec.prevVersion !is TuskyVersion.Beta) {
            throw UsageError("Current Tusky version is not a beta")
        }

        T.success("Current version (${spec.prevVersion}) is a beta")
        return null
    }
}

@Serializable
object SetNextVersionAsBeta : ReleaseStep2() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val releaseVersion = spec.prevVersion.next(spec.releaseType)
        T.success("Release version is $releaseVersion")
        return spec.copy(thisVersion = releaseVersion)
    }
}

@Serializable
object SetNextVersionAsRelease : ReleaseStep2() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec {
        val releaseVersion = (spec.prevVersion as TuskyVersion.Beta).release()
        T.success("Release version is $releaseVersion")
        return spec.copy(thisVersion = releaseVersion)
    }
}

class BranchExists(message: String) : Throwable(message)

@Serializable
object CreateReleaseBranch : ReleaseStep2() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val repo = config.repositoryFork
        val root = config.tuskyForkRoot

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

class BranchMissing(message: String): Exception(message)

@Serializable
object UpdateFilesForRelease : ReleaseStep2() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val repo = config.repositoryFork
        val root = config.tuskyForkRoot

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

        // Add entry to CHANGELOG.md
        // No good third-party libraries for parsing Markdown to an AST **and then manipulating
        // that tree**, so add the new block by hand
        val ChangelogFile = File(config.tuskyForkRoot, "CHANGELOG.md")
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
                    w.println(
                        """
                            ## v${spec.thisVersion.versionName()}

                            ### New features and other improvements

                            ### Significant bug fixes

                            ENTER NEW LOG HERE

                        """.trimIndent()
                    )
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
        T.muted("  https://github.com/tuskyapp/Tusky/compare/${spec.prevVersion.versionTag()}...develop")
        TermUi.editFile(ChangelogFile.toString())

        // TODO: Check the "ENTER NEW LOG HERE" string has been removed

        // Pull out the new lines from the changelog
        val fastlaneFile = spec.fastlaneFile(config.tuskyForkRoot)
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
            "Prepare ${spec.thisVersion.versionName()} (versionCode ${spec.thisVersion.versionCode})"
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
            .setCredentialsProvider(DelegatingCredentialsProvider(config.tuskyForkRoot.toPath()))
            .setRemote("origin")
            .setRefSpecs(RefSpec("$branch:$branch"))
            .info()
            .call()

        return null
    }
}

@Serializable
object CreateReleaseBranchPullRequest : ReleaseStep2() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec? {
        T.info("- Create pull request at https://github.com/${config.repositoryMain.owner}/${config.repositoryMain.repo}/compare/develop...${config.repositoryFork.owner}:${config.repositoryFork.repo}:${spec.releaseBranch()}?expand=1")
        return null
    }
}

/**
 * Prompts for a pull request URL and saves it to [ReleaseSpec.pullRequest].
 */
@Serializable
object SavePullRequest : ReleaseStep2() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec {
        val pullRequest = GitHubPullRequest(
            URL(T.prompt(TextColors.yellow("Enter pull request URL"))))
        return spec.copy(pullRequest = pullRequest)
    }
}

/**
 * Waits for [ReleaseSpec.pullRequest] to be merged.
 */
@Serializable
object WaitForPullRequestMerged : ReleaseStep2() {
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
            repeat (300) {
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
 * Merge the Tusky `develop` branch in to the `main` branch. This happens on a
 * clone of the primary repository, not a fork.
 */
@Serializable
object MergeDevelopToMain : ReleaseStep2() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val repo = config.repositoryMain
        val root = config.tuskyMainRoot

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
            .setCreateBranch(true)
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
object TagMainAsRelease : ReleaseStep2() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val repo = config.repositoryMain
        val root = config.tuskyMainRoot
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
object PushTaggedMain : ReleaseStep2() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val repo = config.repositoryMain
        val root = config.tuskyMainRoot
        val git = ensureRepo(repo.gitUrl, root).also { it.ensureClean() }

        git.push()
            .setCredentialsProvider(DelegatingCredentialsProvider(config.tuskyMainRoot.toPath()))
            .setProgressMonitor(TextProgressMonitor())
            .setPushAll()
            .setPushTags()
            .info()
            .call()

        return null
    }
}

@Serializable
object CreateGithubRelease : ReleaseStep2() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val githubToken = System.getenv("GITHUB_TOKEN")
            ?: throw UsageError("GITHUB_TOKEN is null")
        val github = GitHubBuilder().withOAuthToken(githubToken).build()

        val thisVersion = spec.thisVersion ?: throw UsageError("spec.thisVersion is null!")

        val changeLogFile = File(config.tuskyMainRoot, "CHANGELOG.md")
        val changes = getChangelog(changeLogFile, thisVersion.versionName())

        val repo = github.getRepository(
            "${config.repositoryMain.owner}/${config.repositoryMain.repo}")

        val release = GHReleaseBuilder(repo, spec.releaseTag())
            .name(spec.githubReleaseName())
            .body(changes)
            .draft(true)
            .prerelease(thisVersion !is TuskyVersion.Release)
            .makeLatest(GHReleaseBuilder.MakeLatest.FALSE)
            .create()

        T.success("Created Github release: ${release.htmlUrl}")

        return null
    }
}

@Serializable
object WaitForBitriseToBuild : ReleaseStep2() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec? {
        T.info("""
                Wait for Bitrise to build and upload the APK to Google Play.

                Check https://app.bitrise.io/app/a3e773c3c57a894c?workflow=workflow-release
                """.trimIndent())

        while (!T.confirm("Has Bitrise uploaded the APK?")) { }
        return null
    }
}

@Serializable
object MarkAsBetaOnPlay : ReleaseStep2() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val thisVersion = spec.thisVersion ?: throw UsageError("spec.thisVersion must be defined")

        T.info("""
API access requires @connyduck permission to set up, so not automated yet

1. Open https://play.google.com/console/u/0/developers/8419715224772184120/app/4973838218515056581/tracks/4699478614741377000
2. Click 'Create new release' at the top right
3. In the 'App bundles' section, click 'Add from library'
4. Select the bundle with versionCode ${thisVersion.versionCode}
5. Click 'Add to release'
6. Paste in the following release notes

-------- 8< -------- 8< -------- 8< cut here -------- 8< -------- 8< --------
${spec.fastlaneFile(config.tuskyMainRoot).readLines().joinToString("\n")}
-------- 8< -------- 8< -------- 8< cut here -------- 8< -------- 8< --------

7. Click 'Next'
8. Click 'Save'
9. Go to 'Publishing Overview' when prompted
10. Send the changes for review

        """.trimIndent())

        while (!T.confirm("Have you done all this?")) { }
        return null
    }
}

@Serializable
object DownloadApk : ReleaseStep2() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val thisVersion = spec.thisVersion ?: throw UsageError("spec.thisVersion must be defined")

        T.info("""
1. Open https://play.google.com/console/u/0/developers/8419715224772184120/app/4973838218515056581/bundle-explorer-selector
2. Click the row for ${thisVersion.versionCode}
3. Click the 'Downloads' tab
4. Download the entry 'Signed, universal APK

This should download ${thisVersion.versionCode}.apk

        """.trimIndent())

        while (!T.confirm("Have you done all this?")) { }
        return null
    }
}

@Serializable
object AttachApkToGithubRelease : ReleaseStep2() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val thisVersion = spec.thisVersion ?: throw UsageError("spec.thisVersion must be defined")
        val githubToken = System.getenv("GITHUB_TOKEN")
            ?: throw UsageError("GITHUB_TOKEN is null")
        val github = GitHubBuilder().withOAuthToken(githubToken).build()

        // As a draft release getReleaseByTagName doesn't find it. So fetch the
        // most recent releases and search for it in those.
        val repo = github.getRepository(
            "${config.repositoryMain.owner}/${config.repositoryMain.repo}")

        val release = repo.listReleases().toList().find { it.tagName == thisVersion.versionTag()}
            ?: throw UsageError("Could not find Github release for tag ${spec.releaseTag()}")

        val apk = File("../../Downloads/${thisVersion.versionCode}.apk")
        T.info("- Uploading ${apk.toPath()}")
        release.uploadAsset(apk, "application/vnd.android.package-archive")

        return null
    }
}

@Serializable
object PromoteRelease : ReleaseStep2() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val thisVersion = spec.thisVersion ?: throw UsageError("spec.thisVersion must be defined")

        T.info("""
1. Open the open testing track, https://play.google.com/console/u/0/developers/8419715224772184120/app/4973838218515056581/tracks/open-testing
2. Confirm the testing version is ${thisVersion.versionCode}
3. Click "Promote release" and choose "Production"
4. Confirm correct version code and release notes are present
5. Click "Next"
6. Set the rollout percentage to 100%
7. Click "Save"
8. Go to the publishing overview when prompted
9. Send the change for review
        """.trimIndent())

        while (!T.confirm("Have you done all this?")) { }
        return null
    }
}

@Serializable
object FinalizeGithubRelease : ReleaseStep2() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val thisVersion = spec.thisVersion ?: throw UsageError("spec.thisVersion must be defined")
        val githubToken = System.getenv("GITHUB_TOKEN")
            ?: throw UsageError("GITHUB_TOKEN is null")
        val github = GitHubBuilder().withOAuthToken(githubToken).build()

        // As a draft release getReleaseByTagName doesn't find it. So fetch the
        // most recent releases and search for it in those.
        val repo = github.getRepository(
            "${config.repositoryMain.owner}/${config.repositoryMain.repo}")

        val release = repo.listReleases().toList().find { it.tagName == thisVersion.versionTag()}
            ?: throw UsageError("Could not find Github release for tag ${spec.releaseTag()}")

        val makeLatest = when (thisVersion) {
            is TuskyVersion.Beta -> GHReleaseBuilder.MakeLatest.FALSE
            is TuskyVersion.Release -> GHReleaseBuilder.MakeLatest.TRUE
        }

        release.update()
            .draft(false)
            .makeLatest(makeLatest)
            .prerelease(thisVersion !is TuskyVersion.Release)
            .update()

        T.success("Release has been undrafted")
        if (makeLatest == GHReleaseBuilder.MakeLatest.TRUE) {
            T.success("Release has been marked as the latest")
        }

        return null
    }
}

@Serializable
object SyncFDroidRepository : ReleaseStep2() {
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
            config.fdroidForkRoot,
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
object MakeFDroidReleaseBranch : ReleaseStep2() {
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
object ModifyFDroidYaml : ReleaseStep2() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val thisVersion = spec.thisVersion ?: throw UsageError("releaseSpec.thisVersion must be defined")
        val branch = spec.fdroidReleaseBranch()
        val git = ensureRepo(config.repositoryFDroidFork.gitUrl, config.fdroidForkRoot)
            .also { it.ensureClean() }

        git.checkout()
            .setName(branch)
            .info()
            .call()

        val metadataPath = "metadata/com.keylesspalace.tusky.yml"

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
                w.println("""  - versionName: ${thisVersion.versionName()}
    versionCode: ${thisVersion.versionCode}
    commit: ${thisVersion.versionTag()}
    subdir: app
    gradle:
      - blue
""")
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

        val commitMsg = "Tusky ${thisVersion.versionName()} (${thisVersion.versionCode})"
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
object CreateFDroidMergeRequest : ReleaseStep2() {
    override fun run(config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val branch = spec.fdroidReleaseBranch()

        T.info("""
                This is done by hand at the moment, to complete the merge request template")

                1. Open ${config.repositoryFDroidFork.gitlabUrl}/-/merge_requests/new?merge_request%5Bsource_branch%5D=${branch}
                2. Set and apply the "App update" template
                3. Tick the relevant boxes
                4. Click "Create merge request"

            """.trimIndent())

        while (!T.confirm("Have you done all this?")) { }

        return null
    }
}

@Serializable
object AnnounceTheBetaRelease : ReleaseStep2() {
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
