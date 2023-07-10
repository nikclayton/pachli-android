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

@file:UseSerializers(UrlSerializer::class)

package app.tusky.mkrelease

import app.tusky.mkrelease.cmd.ReleaseStep
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.File

enum class ReleaseType {
    MINOR,
    MAJOR;

    companion object {
        fun from(s: String) = enumValueOf<ReleaseType>(s.uppercase())
    }
}

/**
 * Information needed to release a given version of the app, and collected by the different
 * release steps as they progress.
 *
 * Also stores the next step, so that the release process can be resumed if it is interrupted.
 */
@Serializable
data class ReleaseSpec(
    /** Tracking issue for this release on GitHub */
    val trackingIssue: GitHubIssue,

    val releaseType: ReleaseType,

    /** The full release we're moving towards */
//    val finalVersion: TuskyVersion.Release,

    /** The previous release */
    val prevVersion: TuskyVersion,

    /** The version being released now */
    val thisVersion: TuskyVersion? = null,

    /** The next release step. Null if no steps carried out */
    val nextStep: ReleaseStep? = null,

    /** The pull request that contains the new version code, name, changelog, etc */
    val pullRequest: GitHubPullRequest? = null
) {
    fun save(file: File) {
        val json = Json { prettyPrint = true }
        file.writeText(json.encodeToString(this))
    }

    /** Branch name for this this version */
    fun releaseBranch() = when (thisVersion) {
        is TuskyVersion.Beta -> "${trackingIssue.number}-${thisVersion.major}.${thisVersion.minor}-b${thisVersion.beta}"
        is TuskyVersion.Release -> "${trackingIssue.number}-${thisVersion.major}.${thisVersion.minor}"
        else -> throw(Exception("releaseBranch() without setting thisVersion first"))
    }

    /**
     * FDroid branch for this version
     *
     * Since there may be many branches from other apps, prepending the Tusky package ID
     * seems like a good idea.
     */
    fun fdroidReleaseBranch(): String {
        val branch = releaseBranch()
        return "com.keylesspalace.tusky-$branch"
    }

    /** Git tag to use for this version */
    fun releaseTag() = when (thisVersion) {
        is TuskyVersion.Beta -> "v${thisVersion.major}.${thisVersion.minor}-beta.${thisVersion.beta}"
        is TuskyVersion.Release -> "v${thisVersion.major}.${thisVersion.minor}"
        else -> throw(Exception("releaseTag() without setting thisVersion first"))
    }

    /** Path to the fastlane file, relative to the repo root */
    fun fastlanePath(): String {
        val versionCode = thisVersion?.versionCode ?: throw(Exception("fastlaneFile() without setting thisVersion first"))
        return "fastlane/metadata/android/en-US/changelogs/$versionCode.txt"
    }

    /** Fastlane path for this version */
    fun fastlaneFile(root: File): File {
        val versionCode = thisVersion?.versionCode ?: throw(Exception("fastlaneFile() without setting thisVersion first"))
        return root.resolve(fastlanePath())
    }

    /** Title of this release on the Github "Releases" page */
    fun githubReleaseName() = when (thisVersion) {
        is TuskyVersion.Beta -> "Tusky ${thisVersion.major}.${thisVersion.minor} beta ${thisVersion.beta}"
        is TuskyVersion.Release -> "Tusky ${thisVersion.major}.${thisVersion.minor}"
        else -> throw(Exception("githubReleaseName() without setting thisVersion first"))
    }

    fun asReleaseComment(): String {
        return """
            # ${prevVersion.versionName()} -> ${thisVersion?.versionName()}

            - ${asCheckbox(pullRequest)} Prep the release code changes
            - [ ] Create GitHub release
        """.trimIndent()
    }

    private fun asCheckbox(v: Any?) = "[${v?.let { "x" } ?: " "}]"

    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        fun from(file: File): ReleaseSpec = Json.decodeFromStream(file.inputStream())
    }
}
