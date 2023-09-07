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
import app.pachli.mkrelease.GitHubIssue
import app.pachli.mkrelease.GlobalFlags
import app.pachli.mkrelease.PachliVersion
import app.pachli.mkrelease.ReleaseSpec
import app.pachli.mkrelease.ReleaseType
import app.pachli.mkrelease.SPEC_FILE
import app.pachli.mkrelease.T
import app.pachli.mkrelease.ensureClean
import app.pachli.mkrelease.ensureRepo
import app.pachli.mkrelease.getGradle
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.android.builder.model.v2.models.AndroidDsl
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.prompt
import com.github.ajalt.clikt.parameters.types.choice
import java.net.URL

/**
 * Start a new release train.
 *
 * start -> beta -> beta -> ... -> final
 *
 * Creates the release specification.
 */
class StartRelease : CliktCommand(name = "start") {
    private val globalFlags by requireObject<GlobalFlags>()

    private val releaseType by option().choice("major", "minor").convert { ReleaseType.from(it) }.prompt(
        text = "[major] release or [minor] release?",
        default = "major",
        showDefault = true
    )

    private val issueUrl by option().convert { GitHubIssue(URL(it)) }.prompt(
        text = "Go to GitHub, create an issue to track this release"
    )

    override fun run() {
        val log = globalFlags.log
        (log.underlyingLogger as Logger).level = if (globalFlags.verbose) Level.INFO else Level.WARN
        log.info("startRelease")

        val config = Config.from(CONFIG_FILE)

        val git = ensureRepo(config.repositoryFork.gitUrl, config.pachliForkRoot)
            .also { it.ensureClean() }

        T.info("${config.pachliForkRoot} is clean")

        // TODO: Should warn if there's a release in progress (e.g., SPEC_FILE exists)

        val connection = getGradle(config.pachliForkRoot)
        val androidDsl = connection.model(AndroidDsl::class.java).get()
        log.info(androidDsl.defaultConfig.versionName)

        val versionCode = androidDsl.defaultConfig.versionCode ?: throw UsageError("No versionCode in Gradle config")
        val versionName = androidDsl.defaultConfig.versionName ?: throw UsageError("No versionName in Gradle config")
        val prevRelease = PachliVersion.from(versionName, versionCode)
            ?: throw UsageError("Could not parse '$versionName' as release version")
        log.info(prevRelease.toString())
        connection.close()

        if (prevRelease is PachliVersion.Beta) {
            // throw UsageError("Current release is a beta, use `beta` subcommand")
        }

//        val finalRelease = when (majorMinor) {
//            "major" -> PachliVersion.Release(
//                major = prevRelease.major + 1,
//                minor = 0
//            )
//            "minor" -> PachliVersion.Release(
//                major = prevRelease.major,
//                minor = prevRelease.minor + 1
//            )
//            else -> throw IllegalStateException("can't happen")
//        }

        // Create a GH issue for the release
//        println("Go to GitHub, create an issue to track this release")
//        val issueUrl = URL(prompt("GitHub issue URL"))

        // Create the initial changelog
        val releaseSpec = ReleaseSpec(
            trackingIssue = issueUrl,
            releaseType = releaseType,
//            finalVersion = finalRelease,
            prevVersion = prevRelease
        )

        releaseSpec.save(SPEC_FILE)
    }
}
