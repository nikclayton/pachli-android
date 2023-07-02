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
import app.tusky.mkrelease.T
import app.tusky.mkrelease.ensureRepo
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

    private val appRepoFork by option().convert { GitHubRepository.from(it) }.prompt("URL of your fork of the Tusky repo")
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

        // Clone the Tusky repo
        val tuskyForkRoot = File(workRoot, "tusky")
        T.info("cloning $appRepoFork in to $tuskyForkRoot")
        ensureRepo(appRepoFork.gitUrl, tuskyForkRoot)

        val fdroidForkRoot = File(workRoot, "fdroiddata")
        T.info("cloning $fdroidRepoFork in to $fdroidForkRoot")
        ensureRepo(fdroidRepoFork.gitUrl, fdroidForkRoot)

        T.success("Cloned repos")
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

        T.success("Saved config")
    }
}
