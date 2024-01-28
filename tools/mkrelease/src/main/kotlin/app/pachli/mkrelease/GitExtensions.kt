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

import com.github.ajalt.mordant.terminal.Terminal
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
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
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.URIish

object RepositoryIsNotClean : Exception()

/**
 * Checks to see if the repository's working tree is clean, prints diagnostic
 * message if not, and throws RepositoryIsNotClean
 */
fun Git.ensureClean(t: Terminal) {
    val status = this.status().info(t).call()

    if (!status.isClean) {
        t.danger("Warning: ${this.repository.workTree} is not clean")
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

        if (t.confirm("See the diffs?")) {
            this.diff()
                .setOutputStream(System.out)
                .call()

            this.diff()
                .setOutputStream(System.out)
                .setCached(true)
                .call()
        }

        if (t.confirm("Reset the tree now?")) {
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

// https://gist.github.com/paulwellnerbou/67c1758055710a7eb88e
fun Git.getActualRefObjectId(ref: Ref): ObjectId {
    val repoPeeled = this.repository.refDatabase.peel(ref)
    return repoPeeled.peeledObjectId ?: ref.objectId
}

fun Git.getActualRefObjectId(refStr: String): ObjectId = getActualRefObjectId(repository.refDatabase.findRef(refStr))

inline fun <reified T : Any, R> T.getPrivateProperty(name: String): R? {
    val field = this::class.java.getDeclaredField(name)
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    return field.get(this) as R?
}

fun AddCommand.info(t: Terminal): AddCommand {
    val update = if (this.getPrivateProperty<AddCommand, Boolean>("update") == true) " --update" else ""
    val filePatterns = this.getPrivateProperty<AddCommand, Collection<String>>("filepatterns")
        ?.joinToString(" ")

    t.info("- git add$update $filePatterns")
    return this
}

fun CheckoutCommand.info(t: Terminal): CheckoutCommand {
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

    t.info("- git checkout$createBranch $name$upstreamMode$startPoint")
    return this
}

fun CloneCommand.info(t: Terminal): CloneCommand {
    val uri = this.getPrivateProperty<CloneCommand, String>("uri")
    val directory = this.getPrivateProperty<CloneCommand, File>("directory")
    t.info("- git clone $uri $directory")
    return this
}

fun CommitCommand.info(t: Terminal): CommitCommand {
    val message = this.getPrivateProperty<CommitCommand, String>("message")
    t.info("- git commit -m '$message'")
    return this
}

fun CreateBranchCommand.info(t: Terminal): CreateBranchCommand {
    val name = this.getPrivateProperty<CreateBranchCommand, String>("name")
    val upstreamMode = when (this.getPrivateProperty<CreateBranchCommand, CreateBranchCommand.SetupUpstreamMode>("upstreamMode")) {
        CreateBranchCommand.SetupUpstreamMode.TRACK -> " --track"
        CreateBranchCommand.SetupUpstreamMode.NOTRACK -> " --no-track"
        CreateBranchCommand.SetupUpstreamMode.SET_UPSTREAM -> " --set-upstream"
        null -> TODO()
    }
    t.info("- git branch$upstreamMode $name")
    return this
}

fun FetchCommand.info(t: Terminal): FetchCommand {
    val remote = this.getPrivateProperty<FetchCommand, String>("remote") ?: ""
    t.info("- git fetch $remote")
    return this
}

fun LogCommand.info(t: Terminal): LogCommand {
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

    t.info("- git log$maxCount ${roots.joinToString("..") { it.name }}")
    return this
}

fun MergeCommand.info(t: Terminal): MergeCommand {
    val commits = this.getPrivateProperty<MergeCommand, List<Ref>>("commits")?.let { refs ->
        refs.map { it.name }
    }?.joinToString(" ") ?: ""
    val fastForwardMode = when (this.getPrivateProperty<MergeCommand, FastForwardMode>("fastForwardMode")) {
        FastForwardMode.FF -> " --ff"
        FastForwardMode.NO_FF -> " --no-ff"
        FastForwardMode.FF_ONLY -> " --ff-only"
        null -> ""
    }
    t.info("- git merge$fastForwardMode $commits")
    return this
}

fun PullCommand.info(t: Terminal): PullCommand {
    val remote = this.getPrivateProperty<PullCommand, String>("remote") ?: ""
    val fastForwardMode = when (this.getPrivateProperty<PullCommand, FastForwardMode>("fastForwardMode")) {
        FastForwardMode.FF -> " --ff"
        FastForwardMode.NO_FF -> " --no-ff"
        FastForwardMode.FF_ONLY -> " --ff-only"
        null -> ""
    }
    t.info("- git pull$fastForwardMode $remote")
    return this
}

fun PushCommand.info(t: Terminal): PushCommand {
    val remote = this.getPrivateProperty<PushCommand, String>("remote")?.let { " $it" } ?: ""
    val refSpecs = this.getPrivateProperty<PushCommand, List<RefSpec>>("refSpecs")?.joinToString(" ")
    t.info("- git push$remote $refSpecs")
    return this
}

fun RemoteAddCommand.info(t: Terminal): RemoteAddCommand {
    val name = this.getPrivateProperty<RemoteAddCommand, String>("name")
    val uri = this.getPrivateProperty<RemoteAddCommand, URIish>("uri")
    t.info("- git remote add $name $uri")
    return this
}

fun StatusCommand.info(t: Terminal): StatusCommand {
    t.info("- git status")
    return this
}

fun TagCommand.info(t: Terminal): TagCommand {
    val name = this.getPrivateProperty<TagCommand, String>("name")?.let { " $it" } ?: ""
    val message = this.getPrivateProperty<TagCommand, String>("message")?.let { " -m '$it'" } ?: ""
    val signed = this.getPrivateProperty<TagCommand, Boolean>("signed")?.let { " -s" } ?: ""
    t.info("- git tag$signed$message$name")
    return this
}
