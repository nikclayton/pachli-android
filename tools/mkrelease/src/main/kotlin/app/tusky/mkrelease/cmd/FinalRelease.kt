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
import com.github.ajalt.mordant.rendering.TextStyles

class FinalRelease : CliktCommand(name = "final") {
    private val globalFlags by requireObject<GlobalFlags>()

    override fun run() {
        val config = Config.from(CONFIG_FILE)
        var releaseSpec = ReleaseSpec.from(SPEC_FILE)

        val stepStyle = TextStyles.bold

        val steps = listOf(
            PrepareTuskyForkRepository,
            GetCurrentAppVersion,
            ConfirmCurrentVersionIsBeta,
            SetNextVersionAsRelease,
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
            // Promote the release to the non-testing track
            PromoteRelease,
            // Mark the release as non-draft
            // Mark the release as latest
            FinalizeGithubRelease

            // Update the download link on the home page

            // Update Open Collective

            // Announce from @Tusky account

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

        // releaseSpec.copy(nextStep = null).save(SPEC_FILE)
    }
}
