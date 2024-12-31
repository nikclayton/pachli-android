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

import kotlin.math.sign
import kotlinx.serialization.Serializable

@Serializable
sealed class PachliVersion : Comparable<PachliVersion> {
    abstract val major: Int
    abstract val minor: Int
    abstract val patch: Int
    abstract val versionCode: Int

    /** Version in format "$major.$minor.$patch" */
    open fun versionName() = "$major.$minor.$patch"

    /** Version in format "v$major.$minor.$patch" */
    open fun versionTag() = "v$major.$minor.$patch"

    override fun compareTo(other: PachliVersion): Int {
        val resultByMMP = compareByMajorMinorPatchType(other)

        // Belt and braces -- version code should also compare identically to the
        // major/minor/patch version.
        val resultByVC = versionCode.compareTo(other.versionCode)

        // Same? All good.
        if (resultByMMP == resultByVC) return resultByMMP

        // Both have the same sign? Also good.
        if (resultByMMP.sign == resultByVC.sign) return resultByMMP

        // They differ. Not good.
        throw IllegalStateException("Comparison by major/minor/patch type is not identical to comparison by versionCode")
    }

    private fun compareByMajorMinorPatchType(other: PachliVersion): Int {
        if (this == other) return 0

        // Major version number always wins
        (this.major - other.major).takeIf { it != 0 }?.let { return it }

        // Minor version number can be checked next
        (this.minor - other.minor).takeIf { it != 0 }?.let { return it }

        // Patch version
        (this.patch - other.patch).takeIf { it != 0 }?.let { return it }

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
            ReleaseType.PATCH -> Beta(
                major = this.major,
                minor = this.minor,
                patch = this.patch + 1,
                beta = 1,
                versionCode = this.versionCode + 1
            )
            ReleaseType.MINOR -> Beta(
                major = this.major,
                minor = this.minor + 1,
                patch = 0,
                beta = 1,
                versionCode = this.versionCode + 1
            )
            ReleaseType.MAJOR -> Beta(
                major = this.major + 1,
                minor = 0,
                patch = 0,
                beta = 1,
                versionCode = this.versionCode + 1
            )
        }
    }

    @Serializable
    data class Release(
        override val major: Int,
        override val minor: Int,
        override val patch: Int,
        override val versionCode: Int
    ) : PachliVersion() {
        /** Move from Release to Release without an intervening beta */
        fun release(releaseType: ReleaseType) = when (releaseType) {
            ReleaseType.PATCH -> this.copy(
                patch = this.patch + 1,
                versionCode = this.versionCode + 1
            )
            ReleaseType.MINOR -> this.copy(
                patch = 0,
                minor = this.minor + 1,
                versionCode = this.versionCode + 1
            )
            ReleaseType.MAJOR -> this.copy(
                patch = 0,
                minor = 0,
                major = this.major + 1,
                versionCode = this.versionCode + 1
            )
        }
    }

    @Serializable
    data class Beta(
        override val major: Int,
        override val minor: Int,
        override val patch: Int,
        val beta: Int,
        override val versionCode: Int
    ) : PachliVersion() {
        override fun versionName() = "$major.$minor.$patch beta $beta"
        override fun versionTag() = "v$major.$minor.$patch-beta.$beta"

        /**
         * @return A [Release] which represents the version *after* this beta.
         */
        fun release() = Release(
            major = this.major,
            minor = this.minor,
            patch = this.patch,
            versionCode = this.versionCode + 1
        )
    }

    companion object {
        private val VERSION_REGEX = "^(\\d+)\\.(\\d+)(?:\\.(\\d+))?$".toRegex()
        private val VERSION_BETA_REGEX = "^(\\d+)\\.(\\d+)(?:\\.(\\d+))? beta (\\d+)$".toRegex()

        fun from(versionName: String, versionCode: Int): PachliVersion? {
            VERSION_REGEX.matchEntire(versionName)?.let { result ->
                return Release(
                    major = result.groups[1]!!.value.toInt(),
                    minor = result.groups[2]!!.value.toInt(),
                    patch = result.groups[3]?.value?.toInt() ?: 0,
                    versionCode = versionCode
                )
            }

            VERSION_BETA_REGEX.matchEntire(versionName)?.let { result ->
                return Beta(
                    major = result.groups[1]!!.value.toInt(),
                    minor = result.groups[2]!!.value.toInt(),
                    patch = result.groups[3]?.value?.toInt() ?: 0,
                    beta = result.groups[4]!!.value.toInt(),
                    versionCode = versionCode
                )
            }

            return null
        }
    }
}