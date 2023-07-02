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

import org.eclipse.jgit.api.AddCommand
import org.eclipse.jgit.api.CheckoutCommand
import org.eclipse.jgit.api.CloneCommand
import org.eclipse.jgit.api.CommitCommand
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.FetchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.LogCommand
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.MergeCommand.FastForwardMode
import org.eclipse.jgit.api.PullCommand
import org.eclipse.jgit.api.PushCommand
import org.eclipse.jgit.api.RemoteAddCommand
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.StatusCommand
import org.eclipse.jgit.api.TagCommand
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.URIish
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object RepositoryIsNotClean : Exception()

/**
 * Checks to see if the repository's working tree is clean, prints diagnostic
 * message if not, and throws RepositoryIsNotClean
 */
fun Git.ensureClean() {
    val status = this.status().info().call()

    if (!status.isClean) {
        T.danger("Warning: ${this.repository.workTree} is not clean")
        status.conflicting.forEach { println("Conflict          - $it") }
        status.added.forEach { println("Added             - $it") }
        status.changed.forEach { println("Changed           - $it") }
        status.missing.forEach { println("Missing           - $it") }
        status.modified.forEach { println("Modified          - $it") }
        status.removed.forEach { println("Removed           - $it") }
        status.uncommittedChanges.forEach { println("Uncommitted       - $it") }
        status.untracked.forEach { println("Untracked         - $it") }
        status.untrackedFolders.forEach { println("Untracked folder  - $it") }
        status.conflictingStageState.forEach { println("Conflicting state - $it") }

        if (T.confirm("See the diffs?")) {
            this.diff()
                .setOutputStream(System.out)
                .call()

            this.diff()
                .setOutputStream(System.out)
                .setCached(true)
                .call()
        }

        if (T.confirm("Reset the tree now?")) {
            this.reset().setMode(ResetCommand.ResetType.HARD).call()
            this.clean().call()
            return
        }
        throw RepositoryIsNotClean
    }
}

fun Git.hasBranch(branch: String) = this.branchList().call().indexOfFirst { it.name == branch } != -1

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

inline fun <reified T : Any, R> T.getPrivateProperty(name: String): R? {
    val field = this::class.java.getDeclaredField(name)
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    return field.get(this) as R?
}

fun AddCommand.info(): AddCommand {
    val update = if (this.getPrivateProperty<AddCommand, Boolean>("update") == true) " --update" else ""
    val filePatterns = this.getPrivateProperty<AddCommand, Collection<String>>("filepatterns")
        ?.joinToString(" ")

    T.info("- git add$update $filePatterns")
    return this
}

fun CheckoutCommand.info(): CheckoutCommand {
    val createBranch = when (this.getPrivateProperty<CheckoutCommand, Boolean>("createBranch")) {
        true -> " -b"
        false, null -> ""
    }
    val upstreamMode = when (this.getPrivateProperty<CheckoutCommand, CreateBranchCommand.SetupUpstreamMode>("upstreamMode")) {
        CreateBranchCommand.SetupUpstreamMode.TRACK -> " --track"
        CreateBranchCommand.SetupUpstreamMode.NOTRACK -> " --no-track"
        CreateBranchCommand.SetupUpstreamMode.SET_UPSTREAM -> " --set-upstream"
        null -> ""
    }
    val name = this.getPrivateProperty<CheckoutCommand, String>("name")
    val startPoint = this.getPrivateProperty<CheckoutCommand, String>("startPoint")?.let { " $it" } ?: ""

    T.info("- git checkout$createBranch $name$upstreamMode$startPoint")
    return this
}

fun CloneCommand.info(): CloneCommand {
    val uri = this.getPrivateProperty<CloneCommand, String>("uri")
    val directory = this.getPrivateProperty<CloneCommand, File>("directory")
    T.info("- git clone $uri $directory")
    return this
}

fun CommitCommand.info(): CommitCommand {
    val message = this.getPrivateProperty<CommitCommand, String>("message")
    T.info("- git commit -m '$message'")
    return this
}

fun CreateBranchCommand.info(): CreateBranchCommand {
    val name = this.getPrivateProperty<CreateBranchCommand, String>("name")
    val upstreamMode = when (this.getPrivateProperty<CreateBranchCommand, CreateBranchCommand.SetupUpstreamMode>("upstreamMode")) {
        CreateBranchCommand.SetupUpstreamMode.TRACK -> " --track"
        CreateBranchCommand.SetupUpstreamMode.NOTRACK -> " --no-track"
        CreateBranchCommand.SetupUpstreamMode.SET_UPSTREAM -> " --set-upstream"
        null -> TODO()
    }
    T.info("- git branch$upstreamMode $name")
    return this
}

fun FetchCommand.info(): FetchCommand {
    val remote = this.getPrivateProperty<FetchCommand, String>("remote") ?: ""
    T.info("- git fetch $remote")
    return this
}

fun LogCommand.info(): LogCommand {
    val maxCount = this.getPrivateProperty<LogCommand, Int>("maxCount")
        .takeIf { it != -1 }
        ?.let { " --max-count=$it" }
        ?: ""
    val startSpecified = this.getPrivateProperty<LogCommand, Boolean>("startSpecified") ?: false
    val roots = if (startSpecified) {
        val walk = this.getPrivateProperty<LogCommand, RevWalk>("walk")
        walk?.getPrivateProperty<RevWalk, List<RevCommit>>("roots") ?: emptyList()
    } else {
        emptyList()
    }

    T.info("- git log$maxCount ${roots.joinToString("..") { it.name }}")
    return this
}

fun MergeCommand.info(): MergeCommand {
    val commits = this.getPrivateProperty<MergeCommand, List<Ref>>("commits")?.let { refs ->
        refs.map { it.name }
    }?.joinToString(" ") ?: ""
    val fastForwardMode = when (this.getPrivateProperty<MergeCommand, FastForwardMode>("fastForwardMode")) {
        FastForwardMode.FF -> " --ff"
        FastForwardMode.NO_FF -> " --no-ff"
        FastForwardMode.FF_ONLY -> " --ff-only"
        null -> ""
    }
    T.info("- git merge$fastForwardMode $commits")
    return this
}

fun PullCommand.info(): PullCommand {
    val remote = this.getPrivateProperty<PullCommand, String>("remote") ?: ""
    val fastForwardMode = when (this.getPrivateProperty<PullCommand, FastForwardMode>("fastForwardMode")) {
        FastForwardMode.FF -> " --ff"
        FastForwardMode.NO_FF -> " --no-ff"
        FastForwardMode.FF_ONLY -> " --ff-only"
        null -> ""
    }
    T.info("- git pull$fastForwardMode $remote")
    return this
}

fun PushCommand.info(): PushCommand {
    val remote = this.getPrivateProperty<PushCommand, String>("remote")?.let { " $it" } ?: ""
    val refSpecs = this.getPrivateProperty<PushCommand, List<RefSpec>>("refSpecs")?.joinToString(" ")
    T.info("- git push$remote $refSpecs")
    return this
}

fun RemoteAddCommand.info(): RemoteAddCommand {
    val name = this.getPrivateProperty<RemoteAddCommand, String>("name")
    val uri = this.getPrivateProperty<RemoteAddCommand, URIish>("uri")
    T.info("- git remote add $name $uri")
    return this
}

fun StatusCommand.info(): StatusCommand {
    T.info("- git status")
    return this
}

fun TagCommand.info(): TagCommand {
    val name = this.getPrivateProperty<TagCommand, String>("name")?.let { " $it" } ?: ""
    val message = this.getPrivateProperty<TagCommand, String>("message")?.let { " -m '$it'" } ?: ""
    val signed = this.getPrivateProperty<TagCommand, Boolean>("signed")?.let { " -s" } ?: ""
    T.info("- git tag$signed$message$name")
    return this
}
