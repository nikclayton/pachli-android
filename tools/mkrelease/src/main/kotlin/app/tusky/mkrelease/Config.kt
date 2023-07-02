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

@file:UseSerializers(FileSerializer::class, UrlSerializer::class)

package app.tusky.mkrelease

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import org.kohsuke.github.GHRepository
import java.io.File
import java.net.URL

/** Configuration information needed to start a release */
@Serializable
data class Config(
    /** The fork of the main repository where branches are created, PRs are created from, etc */
    val repositoryFork: GitHubRepository,

    /** The main repository (where tagging happens, PRs are made against, etc) */
    val repositoryMain: GitHubRepository,

    /** The fork of the F-Droid repository where branches are created, PRs are created from, etc */
    val repositoryFDroidFork: GitlabRepository,

    /** Path to use as the root to create other working directories */
    val workRoot: File,

    /** Path to the root of the Tusky repo clone from the fork */
    val tuskyForkRoot: File,

    /** Path to the root of the Tusky repo clone from the main repo */
    val tuskyMainRoot: File,

    /** Path to the root of the F-Droid repo clone from the fork */
    val fdroidForkRoot: File
) {
    /**
     * Validates the values in the configuration make sense
     */
    fun validate() {
//        this.workRoot.isDirectory() || throw UsageError("${this.workRoot} is not a directory")
//        this.workRoot.isWritable() || throw UsageError("${this.workRoot} is not writable")
    }

    fun save(file: File) {
        file.writeText(Json.encodeToString(this))
    }

    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        fun from(file: File): Config = Json.decodeFromStream(file.inputStream())
    }
}

@Serializable
data class GitHubRepository(
    val owner: String,
    val repo: String
) {
    val githubUrl: URL
        get() = URL("https://www.github.com/$owner/$repo")

    val gitUrl: URL
        get() = URL("https://www.github.com/$owner/$repo.git")

    companion object {
        fun from(url: URL): GitHubRepository {
            val (owner, repo) = url.path.substring(1).split("/", limit = 2)
            return GitHubRepository(owner = owner, repo = repo)
        }

        fun from(url: String) = from(URL(url))

        fun from(apiRepo: GHRepository) = GitHubRepository(
            owner = apiRepo.ownerName,
            repo = apiRepo.name
        )
    }
}

@Serializable
data class GitHubIssue(val url: URL) {
    val number: String
        get() = url.path.split("/").last()
}

@Serializable
data class GitHubPullRequest(val url: URL) {
    val number: String
        get() = url.path.split("/").last()
}

@Serializable
data class GitlabRepository(
    val owner: String,
    val repo: String
) {
    val gitlabUrl = URL("https://www.gitlab.com/$owner/$repo")

    val gitUrl: URL
        get() = URL("https://gitlab.com/$owner/$repo.git")

    companion object {
        fun from(url: URL): GitlabRepository {
            val (owner, repo) = url.path.substring(1).split("/", limit = 2)
            return GitlabRepository(owner, repo)
        }

        fun from(url: String) = from(URL(url))
    }
}
