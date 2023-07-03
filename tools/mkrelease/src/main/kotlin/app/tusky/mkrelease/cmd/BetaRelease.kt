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
import app.tusky.mkrelease.GlobalFlags
import app.tusky.mkrelease.ReleaseSpec
import app.tusky.mkrelease.SPEC_FILE
import app.tusky.mkrelease.T
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
