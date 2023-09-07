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
import app.pachli.mkrelease.GitHubRepository
import app.pachli.mkrelease.GitlabRepository
import app.pachli.mkrelease.GlobalFlags
import app.pachli.mkrelease.T
import app.pachli.mkrelease.ensureRepo
import com.github.ajalt.clikt.completion.CompletionCandidates
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.prompt
import com.github.ajalt.clikt.parameters.types.file
import kotlinx.coroutines.runBlocking
import org.kohsuke.github.GitHubBuilder
import java.io.File

class Init : CliktCommand() {
    private val globalFlags by requireObject<GlobalFlags>()

    private val appRepoFork by option().convert { GitHubRepository.from(it) }.prompt("URL of your fork of the Pachli repo")
    private val fdroidRepoFork by option().convert { GitlabRepository.from(it) }.prompt("URL of your fork of the F-Droid repo")
    private val workRoot by option(completionCandidates = CompletionCandidates.Path).file().prompt()

    override fun run() = runBlocking {
        T.info("creating $workRoot")

        try {
            workRoot.mkdir()
        } catch (e: Exception) {
            T.warning("Error: ${e.cause}")
            confirm("Continue?", abort = true)
        }

        val githubToken = System.getenv("GITHUB_TOKEN")
            ?: throw UsageError("GITHUB_TOKEN is null")
        val github = GitHubBuilder().withOAuthToken(githubToken).build()
        val repo = github.getRepository("${appRepoFork.owner}/${appRepoFork.repo}")

        T.info("fetched repo details: $repo")
        if (repo.parent == null) {
            throw UsageError("${appRepoFork.githubUrl} is not a fork of another repository!")
        }

        // Clone the Pachli repo
        val pachliForkRoot = File(workRoot, "pachli")
        T.info("cloning $appRepoFork in to $pachliForkRoot")
        ensureRepo(appRepoFork.gitUrl, pachliForkRoot)

        val fdroidForkRoot = File(workRoot, "fdroiddata")
        T.info("cloning $fdroidRepoFork in to $fdroidForkRoot")
        ensureRepo(fdroidRepoFork.gitUrl, fdroidForkRoot)

        T.success("Cloned repos")
        val config = Config(
            repositoryFork = GitHubRepository.from(repo),
            repositoryMain = GitHubRepository.from(repo.parent),
            repositoryFDroidFork = fdroidRepoFork,
            workRoot = workRoot,
            pachliForkRoot = pachliForkRoot,
            pachliMainRoot = File(workRoot, "pachliMain"),
            fdroidForkRoot = fdroidForkRoot
        )

        config.save(CONFIG_FILE)

        T.success("Saved config")
    }
}
