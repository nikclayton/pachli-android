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

import kotlinx.serialization.Serializable
import java.lang.IllegalStateException

@Serializable
sealed class TuskyVersion : Comparable<TuskyVersion> {
    abstract val major: Int
    abstract val minor: Int
    abstract val versionCode: Int

    open fun versionName() = "$major.$minor"
    open fun versionTag() = "v$major.$minor"

    override fun compareTo(other: TuskyVersion): Int {
        val result = compareByMajorMinorType(other)

        if (result != versionCode.compareTo(other.versionCode)) {
            throw IllegalStateException("Comparison by major/minor/type is not identical to comparison by versionCode")
        }

        return result
    }

    private fun compareByMajorMinorType(other: TuskyVersion): Int {
        if (this == other) return 0

        // Major version number always wins
        (this.major - other.major).takeIf { it != 0 }?.let { return it }

        // Minor version number can be checked next
        (this.minor - other.minor).takeIf { it != 0 }?.let { return it }

        // Identical major and minor numbers, but the objects are not
        // identical. One or both of them is a beta release with a different
        // beta value. The one that's the beta is always less
        if (this is Release) return 1
        if (other is Release) return -1

        // Both are beta, compare by beta number
        return (this as Beta).beta - (other as Beta).beta
    }

    /**
     * Create the next release version
     */
    fun next(releaseType: ReleaseType) = when (this) {
        // The version after a beta is always another beta, with incremented
        // beta count and version code.
        is Beta -> this.copy(
            beta = this.beta + 1,
            versionCode = this.versionCode + 1
        )
        // The version after a release bumps either the major or minor number
        // depending on the release type, and is always a beta.
        is Release -> when (releaseType) {
            ReleaseType.MINOR -> Beta(
                major = this.major,
                minor = this.minor + 1,
                beta = 1,
                versionCode = this.versionCode + 1
            )
            ReleaseType.MAJOR -> Beta(
                major = this.major + 1,
                minor = 1,
                beta = 1,
                versionCode = this.versionCode + 1
            )
        }
    }

    @Serializable
    data class Release(
        override val major: Int,
        override val minor: Int,
        override val versionCode: Int
    ) : TuskyVersion()

    @Serializable
    data class Beta(
        override val major: Int,
        override val minor: Int,
        val beta: Int,
        override val versionCode: Int
    ) : TuskyVersion() {
        override fun versionName() = "$major.$minor beta $beta"
        override fun versionTag() = "v$major.$minor-beta.$beta"

        /**
         * @return A [Release] which represents the version *after* this beta.
         */
        fun release() = Release(
            major = this.major,
            minor = this.minor,
            versionCode = this.versionCode + 1
        )
    }

    companion object {
        private val VERSION_REGEX = "^(\\d+)\\.(\\d+)$".toRegex()
        private val VERSION_BETA_REGEX = "^(\\d+)\\.(\\d+) beta (\\d+)$".toRegex()

        fun from(versionName: String, versionCode: Int): TuskyVersion? {
            VERSION_REGEX.matchEntire(versionName)?.let { result ->
                return Release(
                    major = result.groups[1]!!.value.toInt(),
                    minor = result.groups[2]!!.value.toInt(),
                    versionCode = versionCode
                )
            }

            VERSION_BETA_REGEX.matchEntire(versionName)?.let { result ->
                return Beta(
                    major = result.groups[1]!!.value.toInt(),
                    minor = result.groups[2]!!.value.toInt(),
                    beta = result.groups[3]!!.value.toInt(),
                    versionCode = versionCode
                )
            }

            return null
        }
    }
}
