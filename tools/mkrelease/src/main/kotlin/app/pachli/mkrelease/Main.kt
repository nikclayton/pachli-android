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

package app.pachli.mkrelease

import app.pachli.mkrelease.cmd.BetaRelease
import app.pachli.mkrelease.cmd.FinalRelease
import app.pachli.mkrelease.cmd.Init
import app.pachli.mkrelease.cmd.StartRelease
import app.pachli.mkrelease.cmd.State
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.findOrSetObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.terminal.Terminal
import io.github.oshai.KLogger
import io.github.oshai.KotlinLogging
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ConfigConstants.CONFIG_REMOTE_SECTION
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

val T = Terminal(AnsiLevel.TRUECOLOR)

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

object MissingGitRepository : Exception()
object WrongOrigin : Exception()
object MissingRefs : Exception()

/**
 * Ensures that [root] contains a clone of [repo]
 */
fun ensureRepo(repoUrl: URL, root: File): Git {
    T.info("- Checking $root is a clone of $repoUrl")

    if (!root.exists()) {
        T.info("  $root is missing")
        return Git.cloneRepository()
            .setURI(repoUrl.toString())
            .setDirectory(root)
            .setProgressMonitor(TextProgressMonitor())
            .info()
            .call()
    }

    T.success("  $root exists...")

    val builder = FileRepositoryBuilder()
    builder.findGitDir(root)
    if (builder.gitDir == null) {
        T.danger("  ... but is not a git directory!")
        if (T.confirm("Do you want to recursively remove $root?")) {
            root.deleteRecursively() && return ensureRepo(repoUrl, root)
        }
        throw MissingGitRepository
    }

    T.success("  ... and is a git directory ")

    // Must have the correct origin
    val repo = builder.setWorkTree(root).setMustExist(true).readEnvironment().build()
    val originUrl = repo.config.getString(CONFIG_REMOTE_SECTION, "origin", "url")

    if (repoUrl.toString() != originUrl) {
        T.danger("  ... with the wrong origin!")
        T.info("Actual origin: $originUrl")
        T.info("Wanted origin: $repoUrl")
        if (T.confirm("Do you want to recursively remove $root?")) {
            root.deleteRecursively() && return ensureRepo(repoUrl, root)
        }
        throw WrongOrigin
    }

    T.success("  ... and has the correct origin ...")

    // Must have at least one one-null ref if the clone was success
    val idx = repo.refDatabase.refs.indexOfFirst { it.objectId != null }
    if (idx == -1) {
        T.danger("  ... without any non-null refs!")
        if (T.confirm("Do you want to recursively remove $root?")) {
            root.deleteRecursively() && return ensureRepo(repoUrl, root)
        }
        throw MissingRefs
    }

    T.success("  ... and is not missing refs.")

    return Git(repo)
}

class ConfirmException(msg: String) : Exception(msg)

fun Terminal.confirm(prompt: String, abort: Boolean = false): Boolean {
    val ok = this.prompt(
        TextColors.yellow(prompt),
        choices = listOf("y", "n"),
        default = "n",
        showDefault = true
    ) == "y"
    if (!ok && abort) {
        throw ConfirmException(prompt)
    }
    return ok
}

/**
 * @return GradleConnector for the build in `[workTree]/app`
 */
fun getGradle(workTree: File): ProjectConnection = GradleConnector.newConnector()
    .forProjectDirectory(File(workTree, "app"))
    .connect()

fun main(args: Array<String>) = App().subcommands(
    Init(),
    StartRelease(),
    BetaRelease(),
    FinalRelease(),
    State()
).main(args)
