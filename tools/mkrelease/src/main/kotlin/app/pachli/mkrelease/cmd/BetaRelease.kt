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

import app.pachli.mkrelease.CONFIG_FILE
import app.pachli.mkrelease.Config
import app.pachli.mkrelease.GlobalFlags
import app.pachli.mkrelease.ReleaseSpec
import app.pachli.mkrelease.SPEC_FILE
import app.pachli.mkrelease.T
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.rendering.TextStyles

/** Prepare a new beta release, based on the current release */
class BetaRelease : CliktCommand(name = "beta") {
    private val globalFlags by requireObject<GlobalFlags>()
    private val just by option()

    object RepositoryIsNotClean : Throwable()

    override fun run() {
        val config = Config.from(CONFIG_FILE)
        var releaseSpec = ReleaseSpec.from(SPEC_FILE)

        val stepStyle = TextStyles.bold

        val steps = listOf(
            EnsureCleanReleaseSpec,
            PreparePachliForkRepository,
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
            AttachApkToGithubRelease,
            FinalizeGithubRelease,
            SyncFDroidRepository,
            MakeFDroidReleaseBranch,
            ModifyFDroidYaml,
            CreateFDroidMergeRequest,
            AnnounceTheBetaRelease
        )

        // TODO: Reimplement --just

        val firstStep: ReleaseStep = releaseSpec.nextStep ?: steps.first()

        val si = steps.indexOf(firstStep).takeIf { it != -1 } ?: 0

        for (step in steps.subList(si, steps.size)) {
            releaseSpec = releaseSpec.copy(nextStep = step)
            releaseSpec.save(SPEC_FILE)
            T.println(stepStyle("-> ${step.desc()}"))
            runCatching {
                step.run(config, releaseSpec)
            }.onSuccess { spec ->
                spec?.let { releaseSpec = it }
            }.onFailure { t ->
                T.danger(t.message)
                return
            }
        }

        releaseSpec.copy(
            nextStep = null,
            thisVersion = null
        ).save(SPEC_FILE)
        return
    }
}
