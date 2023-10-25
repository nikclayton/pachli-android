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

import com.github.ajalt.clikt.output.TermUi
import java.io.File
import kotlin.io.path.fileSize

fun getChangelog(changelog: File, nextVersionName: String): String {
    val result = mutableListOf<String>()
    changelog.useLines { lines ->
        var active = false

        for (line in lines) {
            if (line.equals("## v$nextVersionName")) {
                active = true
                continue
            }

            if (line.startsWith("## v")) return@useLines

            // Pull out the bullet points
            if (active) {
                result.add(line)
            }
        }
    }
    return result.joinToString("\n")
}

data class LogEntry(
    val section: Section,
    val text: String
) {
    /** Regex to match "(#<some numbers>)" in a commit title, which refers to the PR */
    private val rxPr = """\((?<text>#(?<pr>\d+))\)""".toRegex()

    // Replace "(#\d+)" in the log message, which refers to the PR, with
    // a markdown link to the PR
    fun withPrLink() = rxPr.replace(text) { mr ->
        "(#[${mr.groups["pr"]?.value}](https://github.com/pachli/pachli-android/pull/${mr.groups["pr"]?.value}))"
    }
}

data class Changes(
    val features: List<String>,
    val fixes: List<String>,
    val translations: List<String>
)

enum class Section {
    Features,
    Fixes,
    Translations,
    Unknown;

    companion object {
        fun fromCommitTitle(title: String) = when {
            title.startsWith("feat:") -> Features
            title.startsWith("feat(l10n)") -> Translations
            title.startsWith("fix(l10n)") -> Translations
            title.startsWith("fix:") -> Fixes
            else -> Unknown
        }
    }
}

/**
 * Gets the change highlights (first level bullets only) for [nextVersionName] from [changelog].
 */
fun getChangelogHighlights(changelog: File, nextVersionName: String): Changes {
    val features = mutableListOf<String>()
    val fixes = mutableListOf<String>()
    val translations = mutableListOf<String>()

    val rxMarkdownLink = """\(.*?\[.+?\]\(.+?\)\)""".toRegex()

    changelog.useLines { lines ->
        var active = false
        var section = Section.Unknown

        for (line in lines) {
            if (line == "## v$nextVersionName") {
                active = true
                continue
            }

            if (line.startsWith("## v")) return@useLines

            if (line.startsWith("### New features")) {
                section = Section.Features
                continue
            }

            if (line.startsWith("### Significant bug fixes")) {
                section = Section.Fixes
                continue
            }

            if (line.startsWith("Translations")) {
                section = Section.Translations
                continue
            }

            // Pull out the bullet points
            if (active && line.startsWith("-")) {
                val entry = rxMarkdownLink.replace(line, "")
                when (section) {
                    Section.Features -> features.add(entry)
                    Section.Fixes -> fixes.add(entry)
                    Section.Translations -> translations.add(entry)
                    Section.Unknown -> throw Exception("Active, found a bullet point, but section is not set")
                }
            }
        }
    }

    return Changes(features, fixes, translations)
}

/**
 * Copies the contents for [nextVersionName] from [changelog] in to [fastlane].
 */
fun createFastlaneFromChangelog(changelog: File, fastlane: File, nextVersionName: String) {
    val changes = getChangelogHighlights(changelog, nextVersionName)
    if (fastlane.exists()) fastlane.delete()
    fastlane.createNewFile()

    val w = fastlane.printWriter()
    w.println(
        """
                Pachli $nextVersionName
        """.trimIndent()
    )

    if (changes.features.isNotEmpty()) {
        w.println(
            """

        New features:

            """.trimIndent()
        )

        changes.features.forEach {
            w.println(
                // Strip out the Markdown formatting and the links at the end
                it
                    .replace("**", "")
                    .replace(", \\[.*".toRegex(), "")
            )
        }
    }

    if (changes.fixes.isNotEmpty()) {
        w.println(
            """

        Fixes:

            """.trimIndent()
        )
        changes.fixes.forEach {
            w.println(
                // Strip out the Markdown formatting and the links at the end
                it
                    .replace("**", "")
                    .replace(", \\[.*".toRegex(), "")
            )
        }
    }

    if (changes.translations.isNotEmpty()) {
        w.println(
            """

        Translations:

            """.trimIndent()
        )
        changes.translations.forEach {
            // Strip out the Markdown formatting and the links at the end
            it
                .replace("**", "")
                .replace(", \\[.*".toRegex(), "")
        }
    }
    w.close()

    while (fastlane.toPath().fileSize() > 500) {
        T.danger("${fastlane.path} is ${fastlane.toPath().fileSize()} bytes, and will be truncated on F-Droid (> 500)")
        if (T.confirm("Open file in editor to modify it?")) {
            TermUi.editFile(fastlane.path)
        } else {
            break
        }
    }
}
