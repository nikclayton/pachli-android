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

import app.tusky.mkrelease.cmd.BetaRelease
import com.github.ajalt.clikt.output.TermUi
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.revwalk.RevCommit
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Checks to see if the repository's working tree is clean, prints diagnostic
 * message if not, and throws RepositoryIsNotClean
 */
fun Git.ensureClean() {
    val status = this.status().call()

    if (!status.isClean) {
        println("Warning: ${this.repository.workTree} is not clean")
        status.conflicting.forEach{ println(           "Conflict          - $it")}
        status.added.forEach { println(                "Added             - $it") }
        status.changed.forEach { println(              "Changed           - $it") }
        status.missing.forEach { println(              "Missing           - $it") }
        status.modified.forEach { println(             "Modified          - $it") }
        status.removed.forEach { println(              "Removed           - $it") }
        status.uncommittedChanges.forEach { println(   "Uncommitted       - $it") }
        status.untracked.forEach { println(            "Untracked         - $it") }
        status.untrackedFolders.forEach { println(     "Untracked folder  - $it") }
        status.conflictingStageState.forEach { println("Conflicting state - $it") }

        if (TermUi.confirm("See the diffs?") == true) {
            this.diff()
                .setOutputStream(System.out)
                .setCached(true)
                .call()
        }

        if (TermUi.confirm("Reset the tree now?") == true) {
            this.reset().setMode(ResetCommand.ResetType.HARD).call()
            return
        }
        throw BetaRelease.RepositoryIsNotClean
    }
}

fun RevCommit.message(): String {
    // Prepare the pieces
    val justTheAuthorNoTime: String =
        authorIdent.toExternalString().split(">").get(0) + ">"
    val commitInstant: Instant = Instant.ofEpochSecond(commitTime.toLong())
    val zoneId: ZoneId = authorIdent.timeZone.toZoneId()
    val authorDateTime: ZonedDateTime = ZonedDateTime.ofInstant(commitInstant, zoneId)
    val gitDateTimeFormatString = "EEE MMM dd HH:mm:ss yyyy Z"
    val formattedDate: String =
        authorDateTime.format(DateTimeFormatter.ofPattern(gitDateTimeFormatString))
    val tabbedCommitMessage = fullMessage.split("\\r?\\n").joinToString("\n") { "  $it" }

    return """
        commit $name
        Author: $justTheAuthorNoTime
        Date:   $formattedDate

        $tabbedCommitMessage
    """.trimIndent()
}
