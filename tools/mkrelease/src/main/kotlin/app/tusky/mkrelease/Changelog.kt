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

import java.io.File

/**
 * Gets the changes for [nextVersionName] from [changelog].
 *
 * TODO: Move elsewhere, to a ChangeLog class or similar.
 */
fun getChangelog(changelog: File, nextVersionName: String): List<String> {
    val changes = mutableListOf<String>()
    changelog.useLines { lines ->
        var active = false

        for (line in lines) {
            if (line.startsWith("## v$nextVersionName")) {
                active = true
                continue
            }

            if (line.startsWith("## v")) break

            // Pull out the bullet points
            if (active) { // TODO: Keep this check? && line.startsWith("-")) {
                changes.add(line)
            }
        }
    }

    return changes
}

/**
 * Copies the contents for [nextVersionName] from [changelog] in to [fastlane].
 *
 * TODO: Move elsewhere, to a ChangeLog class or similar.
 */
fun createFastlaneFromChangelog(changelog: File, fastlane: File, nextVersionName: String) {
    val changes = getChangelog(changelog, nextVersionName)
    if (fastlane.exists()) fastlane.delete()
    fastlane.createNewFile()

    val w = fastlane.printWriter()
    w.println("""
                Tusky $nextVersionName

            """.trimIndent())
    changes.forEach {
        w.println(
            // Strip out the Markdown formatting and the links at the end
            it
                .replace("**", "")
                .replace(", \\[.*".toRegex(), "")
        )
    }
    w.close()
}
