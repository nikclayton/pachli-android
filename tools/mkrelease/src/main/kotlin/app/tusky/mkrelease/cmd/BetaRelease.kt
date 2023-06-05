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

import app.tusky.mkrelease.CONFIG_FILE
import app.tusky.mkrelease.Config
import app.tusky.mkrelease.DelegatingCredentialsProvider
import app.tusky.mkrelease.GitHubPullRequest
import app.tusky.mkrelease.GlobalFlags
import app.tusky.mkrelease.PasswordCredentialsProvider
import app.tusky.mkrelease.ReleaseSpec
import app.tusky.mkrelease.SPEC_FILE
import app.tusky.mkrelease.TuskyVersion
import app.tusky.mkrelease.ensureClean
import app.tusky.mkrelease.getGit
import app.tusky.mkrelease.getGradle
import app.tusky.mkrelease.github.GithubService
import app.tusky.mkrelease.github.PullsApi
import app.tusky.mkrelease.github.ReleasesApi
import app.tusky.mkrelease.maybeCloneRepo
import app.tusky.mkrelease.message
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.android.builder.model.v2.models.AndroidDsl
import com.damnhandy.uri.template.UriTemplate
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.output.TermUi
import com.github.ajalt.clikt.output.TermUi.confirm
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.rendering.TextStyles
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.lib.TextProgressMonitor
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.URIish
import org.gitlab4j.api.GitLabApi
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.createTempFile
import kotlin.reflect.full.primaryConstructor
import kotlin.time.Duration.Companion.seconds


/** Prepare a new beta release, based on the current release */
class BetaRelease : CliktCommand(name = "beta") {
    private val globalFlags by requireObject<GlobalFlags>()
    private val just by option()

    val t = Terminal(AnsiLevel.TRUECOLOR)

    object MissingRepository : Throwable()
    object RepositoryIsNotClean : Throwable()
    class BranchExists(message: String) : Throwable(message)
    class BranchMissing(message: String): Throwable(message)

    @Serializable
    data class PrepareBetaRepository(override val config: Config) : ReleaseStep() {
        override fun run(cmd: CliktCommand): ReleaseStep {
            maybeCloneRepo(config.repositoryFork.gitUrl, config.tuskyForkRoot)
            val git = getGit(config.tuskyForkRoot).also { it.ensureClean() }

            // Checkout `develop` branch,
            git.checkout().setName("develop").call()

            // Pull everything.
            // - FF_ONLY, a non-FF pull indicates a merge commit is needed, which is bad
            git.pull()
                .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
                .call()
            git.ensureClean()

            return CreateBetaReleaseBranch(config)
        }
    }

    @Serializable
    data class CreateBetaReleaseBranch(override val config: Config) : ReleaseStep() {
        override fun run(cmd: CliktCommand): ReleaseStep {
            val git = getGit(config.tuskyForkRoot).also { it.ensureClean() }

            // Figure out the info for the current release
            val gradle = getGradle(config.tuskyForkRoot)
            val androidDsl = gradle.model(AndroidDsl::class.java).get()

            val versionCode = androidDsl.defaultConfig.versionCode ?: throw UsageError("No versionCode in Gradle config")
            val versionName = androidDsl.defaultConfig.versionName ?: throw UsageError("No versionName in Gradle config")
            val currentRelease = TuskyVersion.from(versionName, versionCode)
                ?: throw UsageError("Could not parse '$versionName' as release version")
            println(currentRelease.toString())
            gradle.close()

            var releaseSpec = ReleaseSpec.from(SPEC_FILE)

            println("Current version is $currentRelease")

            releaseSpec = releaseSpec.copy(thisVersion = currentRelease.next(releaseSpec.releaseType))

            println("Upcoming version is ${releaseSpec.thisVersion}, ${releaseSpec.thisVersion?.versionName()}")

            // Create branch (${issue}-${major}.${minor}-b${beta})
            val branch = releaseSpec.releaseBranch()
            println("Creating $branch")

            val branches = git.branchList().call()

            if (branches.indexOfFirst { it.name == "refs/heads/$branch" } != -1) {
                throw BranchExists("Branch $branch already exists")
            }

            git.branchCreate()
                .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.SET_UPSTREAM)
                .setName(branch).call()

            releaseSpec.save(SPEC_FILE)

            return UpdateFilesForBeta(config)
        }
    }

    @Serializable
    data class UpdateFilesForBeta(override val config: Config) : ReleaseStep() {
        override fun run(cmd: CliktCommand): ReleaseStep {
            config.tuskyForkRoot.exists() || throw UsageError("${config.tuskyForkRoot} is missing!")
            val git = getGit(config.tuskyForkRoot).also { it.ensureClean() }

            val releaseSpec = ReleaseSpec.from(SPEC_FILE)
            val branch = releaseSpec.releaseBranch()
            releaseSpec.thisVersion ?: throw UsageError("releaseSpec.thisVersion is null and should not be")

            val branches = git.branchList().call()
            if (branches.indexOfFirst { it.name == "refs/heads/$branch" } == -1) {
                throw BranchMissing("Branch $branch should exist but is missing")
            }

            // Switch to branch
            // TODO: This will fail if the branch doesn't exist, maybe the previous check is unnecessary
            println("git checkout $branch")
            git.checkout().setName(branch).call()

            // No API to update the files, so edit in place
            val buildDotGradleFile = File(File(config.tuskyForkRoot, "app"), "build.gradle")
            val content = buildDotGradleFile.readText()
            content.contains("versionCode ${releaseSpec.prevVersion.versionCode}") || throw UsageError("can't find 'versionCode ${releaseSpec.prevVersion.versionCode}' in $buildDotGradleFile")
            content.contains("versionName \"${releaseSpec.prevVersion.versionName()}\"") || throw UsageError("can't find 'versionName \"${releaseSpec.prevVersion.versionName()}\"' in $buildDotGradleFile")

            buildDotGradleFile.writeText(
                content
                    .replace(
                        "versionCode ${releaseSpec.prevVersion.versionCode}",
                        "versionCode ${releaseSpec.thisVersion.versionCode}"
                    )
                    .replace(
                        "versionName \"${releaseSpec.prevVersion.versionName()}\"",
                        "versionName \"${releaseSpec.thisVersion.versionName()}\""
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
                        w.println("""
                            ## v${releaseSpec.thisVersion.versionName()}

                            ### Significant bug fixes

                            ENTER NEW LOG HERE

                        """.trimIndent())
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
                REPLACE_EXISTING
            )

            println("Edit CHANGELOG.md for this release")
            println("To see what's changed between this release and the last,")
            println("https://github.com/tuskyapp/Tusky/compare/${releaseSpec.prevVersion.versionTag()}...develop")
            TermUi.editFile(ChangelogFile.toString())

            // TODO: Add entry to fastlane/metadata/android/en-US/changelogs/${versionCode}.txt
            // Pull out the new lines from the changelog
            val fastlaneFile = releaseSpec.fastlaneFile(config.tuskyForkRoot)
            createFastlaneFromChangelog(ChangelogFile, fastlaneFile, releaseSpec.thisVersion.versionName())

            git.add()
                .setUpdate(false)
                .addFilepattern("CHANGELOG.md")
                .addFilepattern("app/build.gradle")
                .addFilepattern(releaseSpec.fastlanePath())
                .call()

            var changesAreOk = false
            while (!changesAreOk) {
                git.diff()
                    .setOutputStream(System.out)
                    .setCached(true)
                    .call()
                changesAreOk = confirm("Do these changes look OK?") == true
                if (!changesAreOk) {
                    TermUi.editFile(ChangelogFile.toString())
                    git.add()
                        .addFilepattern("CHANGELOG.md")
                        .addFilepattern(releaseSpec.fastlanePath())
                        .call()
                }
            }

            // Commit
            val commitMsg = "Prepare ${releaseSpec.thisVersion.versionName()} (versionCode ${releaseSpec.thisVersion.versionCode})"
            println("Committing changes.")
            println("""  git commit -m "$commitMsg"""")
            git.commit()
                .setMessage(commitMsg)
                .setSign(null)
                .setCredentialsProvider(PasswordCredentialsProvider())
                .call()

            // Push
            println("Pushing changes to ${config.repositoryFork.githubUrl}")
            git.push()
                .setCredentialsProvider(DelegatingCredentialsProvider(config.tuskyForkRoot.toPath()))
                .setRemote("origin")
                .setRefSpecs(RefSpec("$branch:$branch"))
                .call()

            // Create PR
            return CreatePullRequest(config)
        }
    }



    @Serializable
    data class CreatePullRequest(
        override val config: Config,
    ) : ReleaseStep() {
        override fun run(cmd: CliktCommand): ReleaseStep {
            val releaseSpec = ReleaseSpec.from(SPEC_FILE)

            println("Create pull request at https://github.com/${config.repositoryMain.owner}/${config.repositoryMain.repo}/compare/develop...${config.repositoryFork.owner}:${config.repositoryFork.repo}:${releaseSpec.releaseBranch()}?expand=1")
            return SavePullRequest(config)
        }
    }

    @Serializable
    data class SavePullRequest(override val config: Config) : ReleaseStep() {
        override fun run(cmd: CliktCommand): ReleaseStep {
            val releaseSpec = ReleaseSpec.from(SPEC_FILE)

            val pullRequest = GitHubPullRequest(URL(TermUi.prompt("Enter pull request URL")))
            releaseSpec.copy(pullRequest = pullRequest).save(SPEC_FILE)

            return WaitForApproval(config)
        }
    }

    @Serializable
    data class WaitForApproval(override val config: Config) : ReleaseStep() {
        override fun run(cmd: CliktCommand): ReleaseStep = runBlocking {
            val releaseSpec = ReleaseSpec.from(SPEC_FILE)
            val pullRequest = releaseSpec.pullRequest
                ?: throw UsageError("releaseSpec.pullRequest is null, but should not be")

            var state: PullsApi.PullRequestState

            while (true) {
                state = withContext(Dispatchers.IO) {
                    GithubService.pulls.getPullRequest(
                        config.repositoryMain.owner,
                        config.repositoryMain.repo,
                        pullRequest.number
                    )
                }.state

                // TODO: Can this check to see if it was merged, and not just closed?
                if (state == PullsApi.PullRequestState.CLOSED) break

                println("$pullRequest is still open")

                val t = Terminal()
                repeat(300) {
                    t.cursor.move {
                        up(1)
                        startOfLine()
                        clearLineAfterCursor()
                    }
                    println("Waiting until next check: $it / 300 seconds")
                    delay(1.seconds)
                }
            }

            GithubService.shutdown()

            println("$pullRequest has been closed.")
            confirm("Ready to move to next step?", abort = true)
            MergeDevelopToMain(config)
        }

        override fun desc(): String {
            val releaseSpec = ReleaseSpec.from(SPEC_FILE)
            return "Waiting for ${releaseSpec.pullRequest} to be approved"
        }
    }

    @Serializable
    data class MergeDevelopToMain(override val config: Config) : ReleaseStep() {
        override fun run(cmd: CliktCommand): ReleaseStep {
            // This has to happen on a clone of the main repository, not a fork.
            maybeCloneRepo(config.repositoryMain.gitUrl, config.tuskyMainRoot, delete = true)
            val git = getGit(config.tuskyMainRoot).also { it.ensureClean() }

            // git checkout main
            println("git checkout develop")
            git.checkout()
//                .setName("origin/main")
                .setName("develop")
//                .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
//                .setCreateBranch(true)
                .call()

            // git fetch origin develop:develop
//            println("git fetch origin develop:develop")
//            git.fetch()
//                .setProgressMonitor(TextProgressMonitor())
//                .setRefSpecs("refs/heads/develop:develop")
//                .call()

            // git log
            println("git log")
            git.log()
                .setMaxCount(2)
                .call()
                .forEach { println(it.message())}

            // Verify `develop` branch shows the expected commit
            confirm("Does the `develop` branch show the expected commit?", abort = true)

            // Merge `develop` in to `main`
            // git checkout main
            println("git checkout main")
            git.checkout()
                .setName("main")
                .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                .setCreateBranch(true)
                .call()

            // git merge --ff-only develop
            println("git merge --ff-only develop")
            val mergeBase = git.repository.resolve("develop")
            git.merge()
                .include(mergeBase)
                .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
                .call()

            // Verify the commits are now on main
            println("git log")
            git.log()
                .setMaxCount(5)
                .call()
                .forEach { println(it.message()) }

            confirm("Does the `main` branch show the expected commits?", abort = true)

            return TagMain(config)
        }
    }

    @Serializable
    data class TagMain(override val config: Config) : ReleaseStep() {
        override fun run(cmd: CliktCommand): ReleaseStep {
            maybeCloneRepo(config.repositoryMain.gitUrl, config.tuskyMainRoot, delete = false)
            val git = getGit(config.tuskyMainRoot).also { it.ensureClean() }

            val releaseSpec = ReleaseSpec.from(SPEC_FILE)

            // git checkout main
            git.checkout().setName("main").call()

            // git tag -m v22.0-beta.4 -s v22.0-beta.4
            val tag = releaseSpec.releaseTag() ?: throw UsageError("releaseTag is null but shouldn't be")
            git.tag()
                .setName(tag)
                .setSigned(true)
                .setCredentialsProvider(PasswordCredentialsProvider())
                .call()

            return PushTaggedMain(config)
        }
    }

    @Serializable
    data class PushTaggedMain(override val config: Config) : ReleaseStep() {
        override fun run(cmd: CliktCommand): ReleaseStep? {
            maybeCloneRepo(config.repositoryMain.gitUrl, config.tuskyMainRoot, delete = false)
            val git = getGit(config.tuskyMainRoot).also { it.ensureClean() }
            val releaseSpec = ReleaseSpec.from(SPEC_FILE)

            // TODO: Check current branch is `main`?
            git.push()
                .setCredentialsProvider(DelegatingCredentialsProvider(config.tuskyMainRoot.toPath()))
                .setPushAll()
                .setPushTags()
                .setProgressMonitor(TextProgressMonitor())
                .call()
            return CreateGithubRelease(config)
        }
    }

    @Serializable
    data class CreateGithubRelease(override val config: Config) : ReleaseStep() {
        override fun run(cmd: CliktCommand): ReleaseStep = runBlocking {
            System.getenv("GITHUB_TOKEN") ?: throw UsageError("GITHUB_TOKEN must be defined")

            val releaseSpec = ReleaseSpec.from(SPEC_FILE)
            val thisVersion = releaseSpec.thisVersion ?: throw UsageError("releaseSpec.thisVersion must be defined")

            val ChangelogFile = File(config.tuskyMainRoot, "CHANGELOG.md")
            val changes = getChangelog(ChangelogFile, thisVersion.versionName())

            val request = ReleasesApi.CreateReleaseRequest(
                tagName = releaseSpec.releaseTag(),
                name = releaseSpec.githubReleaseName(),
                body = changes.joinToString("\n"),
                draft = true,
                preRelease = true,
                makeLatest = ReleasesApi.MakeLatest.FALSE
            )

            val githubRelease =
                withContext(Dispatchers.IO) {
                    GithubService.releases.createRelease(
                        owner = config.repositoryMain.owner,
                        repo = config.repositoryMain.repo,
                        request = request
                    )
                }

            println("Created GitHub release: ${githubRelease.htmlUrl}")

            return@runBlocking WaitForBitriseToBuild(config)
        }
    }

    @Serializable
    data class WaitForBitriseToBuild(override val config: Config) : ReleaseStep() {
        override fun run(cmd: CliktCommand): ReleaseStep? {
            println("""
                Wait for Bitrise to build and upload the APK to Google Play.

                Check https://app.bitrise.io/app/a3e773c3c57a894c?workflow=workflow-release
                """.trimIndent())

            while (confirm("Has Bitrise uploaded the APK?") == false) { }

            return MarkAsInternalTestingOnPlay(config)
        }
    }

    @Serializable
    data class MarkAsInternalTestingOnPlay(override val config: Config) : ReleaseStep() {
        override fun run(cmd: CliktCommand): ReleaseStep? {
            val releaseSpec = ReleaseSpec.from(SPEC_FILE)
            val thisVersion = releaseSpec.thisVersion ?: throw UsageError("releaseSpec.thisVersion must be defined")

            println("API access requires @connyduck permission to set up, so not automated yet")
            println("1. Open https://play.google.com/console/u/0/developers/8419715224772184120/app/4973838218515056581/tracks/4699478614741377000")
            println("2. Click 'Create new release' at the top right")
            println("3. In the 'App bundles' section, click 'Add from library'")
            println("4. Select the bundle with versionCode ${thisVersion.versionCode}")
            println("5. Click 'Add to release'")
            println("6. Paste in the following release notes")

            println("-------- 8< -------- 8< -------- 8< cut here -------- 8< -------- 8< -------- 8< ")
            releaseSpec.fastlaneFile(config.tuskyMainRoot).forEachLine { println(it) }
            println("-------- 8< -------- 8< -------- 8< cut here -------- 8< -------- 8< -------- 8< ")

            println("7. Click 'Next'")
            println("8. Click 'Save'")
            println("9. Go to 'Publishing Overview' when prompted")
            println("10. Send the changes for review")

            while (confirm("Have you done all this?") == false) { }

            return DownloadApk(config)
        }
    }

    @Serializable
    data class DownloadApk(override val config: Config) : ReleaseStep() {
        override fun run(cmd: CliktCommand): ReleaseStep? {
            val releaseSpec = ReleaseSpec.from(SPEC_FILE)
            val thisVersion = releaseSpec.thisVersion ?: throw UsageError("releaseSpec.thisVersion must be defined")

            println("1. Open https://play.google.com/console/u/0/developers/8419715224772184120/app/4973838218515056581/bundle-explorer-selector")
            println("2. Click the row for ${thisVersion.versionCode}")
            println("3. Click the 'Downloads' tab")
            println("4. Download the entry 'Signed, universal APK")

            println("This should download ${thisVersion.versionCode}.apk")

            while (confirm("Have you done all this?") == false) { }

            return AttachApkToGithubRelease(config)
        }
    }

    @Serializable
    data class AttachApkToGithubRelease(override val config: Config) : ReleaseStep() {
        override fun run(cmd: CliktCommand): ReleaseStep = runBlocking {
            val releaseSpec = ReleaseSpec.from(SPEC_FILE)
            val thisVersion = releaseSpec.thisVersion ?: throw UsageError("releaseSpec.thisVersion must be defined")

            // As a draft release getReleaseByTagName doesn't find it. So fetch the
            // most recent releases and search for it in those.
            val release = withContext(Dispatchers.IO) {
                GithubService.releases.listReleases(
                    owner = config.repositoryMain.owner,
                    repo = config.repositoryMain.repo
                )
            }.find { it.tagName == thisVersion.versionTag() }
            release ?: throw UsageError("Could not find release with ${thisVersion.versionTag()}")

            println(release)

            val uploadUrl = UriTemplate.fromTemplate(release.uploadUrl)
                .set("name", "${thisVersion.versionCode}.apk")
                .set("label", "${thisVersion.versionCode}.apk")
                .expand()

            println(uploadUrl)

            val apk = File("../../Downloads/${thisVersion.versionCode}.apk")
            apk.exists() || throw UsageError("apk not found")

            val body = apk.asRequestBody("application/vnd.android.package-archive".toMediaTypeOrNull())

            println("Uploading ${apk.path} to release")
            val asset = withContext(Dispatchers.IO) {
                GithubService.releases.uploadReleaseAsset(
                    uploadUrl, body
                )
            }

            println("Uploaded to ${asset.browserDownloadUrl}")
            println("State: ${asset.state}")

            // Mark the release as non-draft
            withContext(Dispatchers.IO) {
                GithubService.releases.updateRelease(
                    owner = config.repositoryMain.owner,
                    repo = config.repositoryMain.repo,
                    releaseId = release.id,
                    request = ReleasesApi.UpdateReleaseRequest(draft = false)
                )
            }

            return@runBlocking SyncFDroidRepository(config)
        }
    }

    @Serializable
    data class SyncFDroidRepository(override val config: Config) : ReleaseStep() {
        override fun run(cmd: CliktCommand): ReleaseStep {
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

            maybeCloneRepo(
                config.repositoryFDroidFork.gitUrl,
                config.fdroidForkRoot,
                delete = true,
                branches = listOf(defaultBranchRef)
            )
            val git = getGit(config.fdroidForkRoot).also { it.ensureClean() }

            // Sync the fork with the parent

            // git remote add upstream https://...
            git.remoteAdd()
                .setName("upstream")
                .setUri(URIish(project.forkedFromProject.httpUrlToRepo))
                .call()

            println("default branch: $defaultBranch")
            // git checkout main
            git.checkout()
                .setName(defaultBranch)
                .call()

            // git fetch upstream
            git.fetch()
                .setRemote("upstream")
                .setProgressMonitor(TextProgressMonitor())
                .call()

            // git pull upstream $defaultBranch
            git.pull()
                .setRemote("upstream")
                .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
                .setProgressMonitor(TextProgressMonitor())
                .call()

            // git push origin $defaultBranch
            git.push()
                .setCredentialsProvider(DelegatingCredentialsProvider(config.fdroidForkRoot.toPath()))
                .setRemote("origin")
                .setProgressMonitor(TextProgressMonitor())
                .call()

            return MakeFDroidReleaseBranch(config)
        }
    }

    @Serializable
    data class MakeFDroidReleaseBranch(override val config: Config) : ReleaseStep() {
        override fun run(cmd: CliktCommand): ReleaseStep? {
//            maybeCloneRepo(
//                config.repositoryFDroidFork.gitUrl,
//                config.fdroidForkRoot,
//                delete = false,
//                branches = listOf(defaultBranchRef)
//            )
            val git = getGit(config.fdroidForkRoot).also { it.ensureClean() }
            val releaseSpec = ReleaseSpec.from(SPEC_FILE)

            val branch = releaseSpec.fdroidReleaseBranch()
            println("Creating $branch in FDroid repo")
            val branches = git.branchList().call()
            if (branches.indexOfFirst { it.name == "refs/heads/$branch" } != -1) {
                throw BranchExists("Branch $branch already exists")
            }

            git.branchCreate()
                .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.SET_UPSTREAM)
                .setName(branch)
                .call()

            git.checkout()
                .setName(branch)
                .call()

            return ModifyFDroidYaml(config)
        }
    }

    @Serializable
    data class ModifyFDroidYaml(override val config: Config) : ReleaseStep() {
        override fun run(cmd: CliktCommand): ReleaseStep {
            val releaseSpec = ReleaseSpec.from(SPEC_FILE)
            val thisVersion = releaseSpec.thisVersion ?: throw UsageError("releaseSpec.thisVersion must be defined")
            val branch = releaseSpec.fdroidReleaseBranch()
            val git = getGit(config.fdroidForkRoot).also { it.ensureClean() }
            git.checkout()
                .setName(branch)
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
            Files.move(tmpFile.toPath(), metadataFile.toPath(), REPLACE_EXISTING)

            git.add()
                .setUpdate(false)
                .addFilepattern(metadataPath)
                .call()

            var changesAreOk = false
            while (!changesAreOk) {
                git.diff().setOutputStream(System.out).setCached(true).call()
                changesAreOk = confirm("Do these changes look OK?") == true
                if (!changesAreOk) {
                    TermUi.editFile(metadataPath)
                    git.add().addFilepattern(metadataPath).call()
                }
            }

            val commitMsg = "Tusky ${thisVersion.versionName()} (${thisVersion.versionCode})"
            println("Committing changes")
            println("  git commit -m \"$commitMsg\"")
            git.commit()
                .setMessage(commitMsg)
                .setSign(null)
                .setCredentialsProvider(PasswordCredentialsProvider())
                .call()

            println("Pushing changes to ${config.repositoryFDroidFork.gitlabUrl}")
            git.push()
                .setCredentialsProvider(DelegatingCredentialsProvider(config.fdroidForkRoot.toPath()))
                .setRemote("origin")
                .setRefSpecs(RefSpec("$branch:$branch"))
                .call()

            return CreateFDroidMergeRequest(config)
        }
    }

    @Serializable
    data class CreateFDroidMergeRequest(override val config: Config) : ReleaseStep() {
        override fun run(cmd: CliktCommand): ReleaseStep? {
            val releaseSpec = ReleaseSpec.from(SPEC_FILE)
            val branch = releaseSpec.fdroidReleaseBranch()

            println("""
                This is done by hand at the moment, to complete the merge request template")

                1. Open ${config.repositoryFDroidFork.gitlabUrl}/-/merge_requests/new?merge_request%5Bsource_branch%5D=${branch}
                2. Set and apply the "App update" template
                3. Tick the relevant boxes
                4. Click "Create merge request"

            """.trimIndent())

            while (confirm("Have you done all this?") == false) { }

            return AnnounceTheBetaRelease(config)
        }
    }

    @Serializable
    data class AnnounceTheBetaRelease(override val config: Config) : ReleaseStep() {
        override fun run(cmd: CliktCommand): ReleaseStep? {
            println("Announce the beta release, and you're done.")

            while (confirm("Have you done all this?") == false) { }

            // Done. This version can now be marked as the previous version
            val releaseSpec = ReleaseSpec.from(SPEC_FILE)
            releaseSpec.copy(
                prevVersion = releaseSpec.thisVersion!!,
                thisVersion = null,
                pullRequest = null
            ).save(SPEC_FILE)

            return null
        }
    }

    override fun run() {
        val log = globalFlags.log
        (log.underlyingLogger as Logger).level = if (globalFlags.verbose) Level.INFO else Level.WARN
        log.info("beta")
        val config = Config.from(CONFIG_FILE)
        log.info(config.toString())

        val releaseSpec = ReleaseSpec.from(SPEC_FILE)

        just?.let {
            val kClass = Class.forName("${this.javaClass.canonicalName}$$it").kotlin
            val step = kClass.primaryConstructor?.call(config) as ReleaseStep
            val nextStep = step.run(this@BetaRelease)
            ReleaseSpec.from(SPEC_FILE).copy(nextStep = nextStep).save(SPEC_FILE)
            GithubService.shutdown()
            return
        }

        val stepStyle = TextStyles.bold

        var step: ReleaseStep? = releaseSpec.nextStep ?: PrepareBetaRepository(config)
        while (step != null) {
            t.println(stepStyle("-> ${step.desc()}"))
            runCatching {
                step!!.run(this)
            }.onSuccess {
                step = it
                ReleaseSpec.from(SPEC_FILE).copy(nextStep = step).save(SPEC_FILE)
            }.onFailure {
                println("Error in step: $step")
                println(it)
                return
            }
        }

        GithubService.shutdown()

        return
    }

    companion object {
        /**
         * Gets the changes for [nextVersionName] from [changelog].
         *
         * TODO: Move elsewhere, to a ChangeLog class or similar.
         */
        private fun getChangelog(changelog: File, nextVersionName: String): List<String> {
            val changes = mutableListOf<String>()
            changelog.useLines { lines ->
                var active = false

                for (line in lines) {
                    if (line.startsWith("## v$nextVersionName")) {
                        active = true
                        continue
                    }

                    if (line.startsWith("## v")) break

                    // Pull out the bullet points
                    if (active) { // TODO: Keep this check? && line.startsWith("-")) {
                        changes.add(line)
                    }
                }
            }

            return changes
        }

        /**
         * Copies the contents for [nextVersionName] from [changelog] in to [fastlane].
         *
         * TODO: Move elsewhere, to a ChangeLog class or similar.
         */
        private fun createFastlaneFromChangelog(changelog: File, fastlane: File, nextVersionName: String) {
            val changes = getChangelog(changelog, nextVersionName)
            if (fastlane.exists()) fastlane.delete()
            fastlane.createNewFile()

            val w = fastlane.printWriter()
            w.println("""
                Tusky $nextVersionName

                Fixes:

            """.trimIndent())
            changes.forEach {
                w.println(
                    // Strip out the Markdown formatting and the links at the end
                    it
                        .replace("**", "")
                        .replace(", \\[.*".toRegex(), "")
                )
            }
            w.close()
        }
    }
}
