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

package app.tusky.mkrelease

import app.tusky.mkrelease.cmd.BetaRelease
import app.tusky.mkrelease.cmd.Init
import app.tusky.mkrelease.cmd.StartRelease
import app.tusky.mkrelease.cmd.State
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.findOrSetObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.github.oshai.KLogger
import io.github.oshai.KotlinLogging
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.TextProgressMonitor
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import java.io.File
import java.net.URL


private val log = KotlinLogging.logger {}

// Subcommands:
// init - Ask questions, create config
// beta - Creates a new beta from the current state
// release - Creates a new release from the current state

val CONFIG_FILE = File("mkrelease.json")

val SPEC_FILE = File("release-spec.json")

data class GlobalFlags(
    var verbose: Boolean = false,
    var log: KLogger = KotlinLogging.logger {}
)

class App : CliktCommand() {
    private val verbose by option().flag("--no-verbose")
    private val globalFlags by findOrSetObject { GlobalFlags() }
    override fun run() {
        globalFlags.verbose = verbose
    }
}

fun maybeCloneRepo(repoUrl: URL, root: File, delete: Boolean = false, branches: List<String> = emptyList()) {
    println("maybeCloneRepo: $repoUrl, $root, $delete")
    if (root.exists()) {
        if (delete) {
            root.deleteRecursively()
        } else {
            return
        }
    }

    // This is default branches for Tusky repo, F-Droid needs `master` only. Should probably
    // encapsulate this better.
    val branchesToClone = branches.toMutableList()
    if (branchesToClone.isEmpty()) {
        branchesToClone.add("refs/heads/main")
        branchesToClone.add("refs/heads/develop")
    }

    println("cloning $repoUrl in to $root, for $branchesToClone")
    Git.cloneRepository()
        .setURI(repoUrl.toString())
        .setDirectory(root)
        .setBranchesToClone(branchesToClone)
        .setProgressMonitor(TextProgressMonitor())
//        .setDepth(1)
        .call()
}

/**
 * @return A [Git] object configured for the repository at [workTree]
 */
fun getGit(workTree: File): Git {
    val builder = FileRepositoryBuilder()
    val tuskyRepo = builder
        .setWorkTree(workTree)
        .readEnvironment()
        .build()
    return Git(tuskyRepo)
}

/**
 * @return GradleConnector for the build in `[workTree]/app`
 */
fun getGradle(workTree: File): ProjectConnection = GradleConnector.newConnector()
    .forProjectDirectory(File(workTree, "app"))
    .connect()

fun main(args: Array<String>) = App().subcommands(
    Init(), StartRelease(), BetaRelease(), State()
).main(args)

