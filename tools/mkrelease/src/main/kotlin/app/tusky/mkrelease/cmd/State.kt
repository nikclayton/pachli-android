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
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject

/** Show the current state of a release */
class State : CliktCommand(name = "state") {
    private val globalFlags by requireObject<GlobalFlags>()

    override fun run() {
        val log = globalFlags.log
        (log.underlyingLogger as Logger).level = if (globalFlags.verbose) Level.INFO else Level.WARN

        val config = try {
            Config.from(CONFIG_FILE)
        } catch (e: Exception) {
            println("Could not load $CONFIG_FILE: ${e.message}")
            println("Have you run `mkrelease init` ?")
            return
        }

        println("Configured:")
        println("  Fork repo: ${config.repositoryFork.githubUrl}")
        println("  Parent repo: ${config.repositoryMain.githubUrl}")
        println("  Working tree: ${config.workRoot}")
//        println(config.toString())

        var releaseSpec = try {
            ReleaseSpec.from(SPEC_FILE)
        } catch (e: Exception) {
            println("Could not load $SPEC_FILE: $e.message")
            println("Have you run `mkrelease start` ?")
            return
        }

        println("Active release process:")
        println("  Tracking Issue: ${releaseSpec.trackingIssue}")
//        println("  Next version: ${releaseSpec.finalVersion.versionName()}")
        releaseSpec.nextStep?.let {
            println("  Next step: ${it.desc()}")
        }
        releaseSpec.pullRequest?.let {
            println("  Pull request: $it")
        }

        println(releaseSpec.asReleaseComment())

        return
    }
}
