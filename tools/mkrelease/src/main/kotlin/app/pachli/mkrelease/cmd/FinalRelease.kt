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
import com.github.ajalt.mordant.rendering.TextStyles

class FinalRelease : CliktCommand(name = "final") {
    private val globalFlags by requireObject<GlobalFlags>()

    override fun run() {
        val config = Config.from(CONFIG_FILE)
        var releaseSpec = ReleaseSpec.from(SPEC_FILE)

        val stepStyle = TextStyles.bold

        val steps = listOf(
            EnsureCleanReleaseSpec,
            PreparePachliForkRepository,
            GetCurrentAppVersion,
//            ConfirmCurrentVersionIsBeta,
            SetNextVersionAsRelease,
            CreateReleaseBranch,
            UpdateFilesForRelease,
            CreateReleaseBranchPullRequest,
            SavePullRequest,
            WaitForPullRequestMerged,
//            MergeDevelopToMain,
            FetchMainToTag,
            TagMainAsRelease,
            PushTaggedMain,
            CreateGithubRelease,
//            WaitForBitriseToBuild,
            MarkAsBetaOnPlay,
            DownloadApk,
            AttachApkToGithubRelease,
            // Promote the release to the non-testing track
            PromoteRelease,
            // Mark the release as non-draft
            // Mark the release as latest
            FinalizeGithubRelease

            // Update the download link on the home page

            // Update Open Collective

            // Announce from @Pachli account

            // Close out the release in the spec so that a future beta/final can bail
        )

        val firstStep: ReleaseStep = releaseSpec.nextStep ?: steps.first()

//        firstStep?.let {
//            if (!it.javaClass.name.startsWith(this.javaClass.name)) {
//                T.danger("Active step is ${it.javaClass.name}")
//                T.danger("This is *not* a valid release step for this process")
//                if (T.confirm("Reset to first step for this process?")) {
//                    firstStep = steps.first()
//                } else {
//                    return
//                }
//            }
//        }

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
    }
}
