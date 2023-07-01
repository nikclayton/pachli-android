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
import app.tusky.mkrelease.GlobalFlags
import app.tusky.mkrelease.PasswordCredentialsProvider
import app.tusky.mkrelease.ReleaseSpec
import app.tusky.mkrelease.SPEC_FILE
import app.tusky.mkrelease.T
import app.tusky.mkrelease.confirm
import app.tusky.mkrelease.ensureClean
import app.tusky.mkrelease.ensureRepo
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.output.TermUi
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.rendering.TextStyles
import kotlinx.serialization.Serializable
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.errors.RefAlreadyExistsException
import org.eclipse.jgit.lib.TextProgressMonitor
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.URIish
import org.gitlab4j.api.GitLabApi
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.createTempFile


/** Prepare a new beta release, based on the current release */
class BetaRelease : CliktCommand(name = "beta") {
    private val globalFlags by requireObject<GlobalFlags>()
    private val just by option()

    object RepositoryIsNotClean : Throwable()

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
        override fun run(cmd: CliktCommand): ReleaseStep {
            val git = ensureRepo(config.repositoryFDroidFork.gitUrl, config.fdroidForkRoot)
                .also { it.ensureClean() }
            val releaseSpec = ReleaseSpec.from(SPEC_FILE)

            val branch = releaseSpec.fdroidReleaseBranch()

            T.info("- Creating $branch in FDroid repo")

            try {
                git.branchCreate()
                    .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.SET_UPSTREAM)
                    .setName(branch)
                    .call()
            } catch (e: RefAlreadyExistsException) {
                throw BranchExists("Branch $branch already exists")
            }

            T.info("- git checkout $branch")
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
            val git = ensureRepo(config.repositoryFDroidFork.gitUrl, config.fdroidForkRoot)
                .also { it.ensureClean() }

            T.info("- git checkout $branch")
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
                changesAreOk = T.confirm("Do these changes look OK?")
                if (!changesAreOk) {
                    TermUi.editFile(metadataPath)
                    git.add().addFilepattern(metadataPath).call()
                }
            }

            val commitMsg = "Tusky ${thisVersion.versionName()} (${thisVersion.versionCode})"
            T.info("- git commit -m \"$commitMsg\"")
            git.commit()
                .setMessage(commitMsg)
                .setSign(null)
                .setCredentialsProvider(PasswordCredentialsProvider())
                .call()

            T.info("- Pushing changes to ${config.repositoryFDroidFork.gitlabUrl}")
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
        override fun run(cmd: CliktCommand): ReleaseStep {
            val releaseSpec = ReleaseSpec.from(SPEC_FILE)
            val branch = releaseSpec.fdroidReleaseBranch()

            T.info("""
                This is done by hand at the moment, to complete the merge request template")

                1. Open ${config.repositoryFDroidFork.gitlabUrl}/-/merge_requests/new?merge_request%5Bsource_branch%5D=${branch}
                2. Set and apply the "App update" template
                3. Tick the relevant boxes
                4. Click "Create merge request"

            """.trimIndent())

            while (!T.confirm("Have you done all this?")) { }

            return AnnounceTheBetaRelease(config)
        }
    }

    @Serializable
    data class AnnounceTheBetaRelease(override val config: Config) : ReleaseStep() {
        override fun run(cmd: CliktCommand): ReleaseStep? {
            T.info("- Announce the beta release, and you're done.")

            while (!T.confirm("Have you done all this?")) { }

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
        val config = Config.from(CONFIG_FILE)
        var releaseSpec = ReleaseSpec.from(SPEC_FILE)

        val stepStyle = TextStyles.bold

        val steps = listOf(
            PrepareTuskyForkRepository,
            GetCurrentAppVersion,
            SetNextVersionAsBeta,
            CreateReleaseBranch,
            UpdateFilesForRelease,
            CreateReleaseBranchPullRequest,
            SavePullRequest,
            WaitForPullRequestMerged,
            MergeDevelopToMain,
            TagMainAsRelease,
            PushTaggedMain,
            CreateGithubRelease,
            WaitForBitriseToBuild,
            MarkAsBetaOnPlay,
            DownloadApk,
            AttachApkToGithubRelease
        )

        // TODO: Reimplement --just

        val firstStep: ReleaseStep2 = releaseSpec.nextStep ?: steps.first()

        val si = steps.indexOf(firstStep).takeIf { it != -1 } ?: 0

        for (step in steps.subList(si, steps.size)) {
            releaseSpec = releaseSpec.copy(nextStep = step)
            T.println(stepStyle("-> ${step.desc()}"))
            runCatching {
                step.run(config, releaseSpec)
            }.onSuccess { spec ->
                spec?.let { releaseSpec = it }
                releaseSpec.save(SPEC_FILE)
            }.onFailure { t ->
                T.danger(t.message)
                return
            }
        }

        return
    }
}
