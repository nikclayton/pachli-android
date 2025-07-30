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
import com.github.ajalt.mordant.terminal.Terminal
import java.io.File
import kotlin.io.path.fileSize
import org.eclipse.jgit.lib.PersonIdent

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
    val text: String,
    val author: PersonIdent,
) {
    /** Regex to match "(#<some numbers>)" in a commit title, which refers to the PR */
    private val rxPr = """\((?<text>#(?<pr>\d+))\)""".toRegex()

    // Returns a link'ified version of the LogEntry.
    //
    // - PR references (if present) are converted to links
    // - A link to the author's commits on GitHub is added
    fun withLinks(): String {
        val newText = rxPr.replace(text) { mr ->
            "(${prLink(mr.groups["pr"]!!.value)}, ${authorLink()})"
        }
        if (newText != text) return newText

        return "$text (${authorLink()})"
    }

    private fun prLink(pr: String) = "#[$pr](https://github.com/pachli/pachli-android/pull/$pr)"

    private fun authorLink() = "[${author.name}](https://github.com/pachli/pachli-android/commits?author=${author.emailAddress})"
}

data class Changes(
    val features: List<String>,
    val fixes: List<String>,
    val translations: List<String>,
)

enum class Section {
    Features,
    Fixes,
    Translations,
    Unknown,
    ;

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

            if (line.startsWith("### Translations")) {
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
 * Creates a [fastlane] that links to the release notes.
 *
 * @param nextVersionName Version name string, format "Major.minor.patch".
 */
fun createFastlaneFromChangelog(t: Terminal, changelog: File, fastlane: File, nextVersionName: String) {
    if (fastlane.exists()) fastlane.delete()
    fastlane.createNewFile()

    val w = fastlane.printWriter()
    w.println("Pachli $nextVersionName\n\nSee https://github.com/pachli/pachli-android/releases/tag/v$nextVersionName.")
    w.close()
}

/**
 * Copies the contents for [nextVersionName] from [changelog] in to [fastlane].
 */
// This is the version of the function that created the initial contents from the
// Changelog file. There are now so many changes that it's difficult to keep this
// under 500 characters. Rather than have to edit this on each release it's easier
// to create a link to the final release notes.
fun createFastlaneFromChangelogOld(t: Terminal, changelog: File, fastlane: File, nextVersionName: String) {
    val changes = getChangelogHighlights(changelog, nextVersionName)
    if (fastlane.exists()) fastlane.delete()
    fastlane.createNewFile()

    val w = fastlane.printWriter()
    w.println(
        """
                Pachli $nextVersionName
        """.trimIndent(),
    )

    if (changes.features.isNotEmpty()) {
        w.println(
            """

        New features:

            """.trimIndent(),
        )

        changes.features.forEach {
            w.println(
                // Strip out the Markdown formatting and the links at the end
                it
                    .replace("**", "")
                    .replace(", \\[.*".toRegex(), "")
                    .trim(),
            )
        }
    }

    if (changes.fixes.isNotEmpty()) {
        w.println(
            """

        Fixes:

            """.trimIndent(),
        )
        changes.fixes.forEach {
            w.println(
                // Strip out the Markdown formatting and the links at the end
                it
                    .replace("**", "")
                    .replace(", \\[.*".toRegex(), "")
                    .trim(),
            )
        }
    }

    if (changes.translations.isNotEmpty()) {
        w.println(
            """

        Translations:

            """.trimIndent(),
        )
        val s = changes.translations
            .map { it.replace("- Update ", "") }
            .map { it.replace(" translations", "") }
            .joinToString(", ") { it.trim() }
        w.println("- $s")
    }
    w.close()

    while (fastlane.toPath().fileSize() > 500) {
        t.danger("${fastlane.path} is ${fastlane.toPath().fileSize()} bytes, and will be truncated on F-Droid (> 500)")
        if (t.confirm("Open file in editor to modify it?")) {
            TermUi.editFile(fastlane.path)
        } else {
            break
        }
    }
}
