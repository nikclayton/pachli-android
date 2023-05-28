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
import app.tusky.mkrelease.GitHubRepository
import app.tusky.mkrelease.GitlabRepository
import app.tusky.mkrelease.GlobalFlags
import app.tusky.mkrelease.github.GithubService
import app.tusky.mkrelease.maybeCloneRepo
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.github.ajalt.clikt.completion.CompletionCandidates
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.prompt
import com.github.ajalt.clikt.parameters.types.file
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

class Init : CliktCommand() {
    private val globalFlags by requireObject<GlobalFlags>()

    private val appRepoFork by option().convert { GitHubRepository.from(it) }.prompt("URL of your fork of the Tusky repo")
    private val fdroidRepoFork by option().convert { GitlabRepository.from(it) }.prompt("URL of your fork of the F-Droid repo")
    private val workRoot by option(completionCandidates = CompletionCandidates.Path).file().prompt()

    override fun run() = runBlocking {
        val log = globalFlags.log
        (log.underlyingLogger as Logger).level = if (globalFlags.verbose) Level.INFO else Level.WARN
        log.info("init")

        log.info("creating $workRoot")
        try {
            workRoot.mkdir()
        } catch (e: Exception) {
            println("Error: ${e.cause}")
            confirm("Continue?", abort = true)
        }

        // Get the repository, check it's a fork, get the parent
        val service = GithubService.repositories
        val repo = withContext(Dispatchers.Default) {
            println("https://api.github.com/repos/${appRepoFork.owner}/${appRepoFork.repo}")
            service.getRepo(owner = appRepoFork.owner, repo = appRepoFork.repo)
        }
        println("fetched repo details: $repo")
        if (repo.parent == null) {
            throw UsageError("${appRepoFork.githubUrl} is not a fork of another repository!")
        }

        // Clone the Tusky repo
        val tuskyForkRoot = File(workRoot,"tusky")
        println("cloning $appRepoFork in to $tuskyForkRoot")
        maybeCloneRepo(appRepoFork.gitUrl, tuskyForkRoot, delete = true)

        // TODO: Clone the F-Droid repo
        val fdroidForkRoot = File(workRoot, "fdroiddata")
        println("cloning $fdroidRepoFork in to $fdroidForkRoot")
        maybeCloneRepo(fdroidRepoFork.gitUrl, fdroidForkRoot, delete = true)

        println("Cloned repo")
        val config = Config(
            repositoryFork = GitHubRepository.from(repo),
            repositoryMain = GitHubRepository.from(repo.parent),
            repositoryFDroidFork = fdroidRepoFork,
            workRoot = workRoot,
            tuskyForkRoot = tuskyForkRoot,
            tuskyMainRoot = File(workRoot, "tuskyMain"),
            fdroidForkRoot = fdroidForkRoot
        )

        config.save(CONFIG_FILE)

        println("Saved config")

        GithubService.shutdown()
    }
}
