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

package app.pachli.mkserverlist

import app.pachli.mkserverlist.fediverseobserver.ServerListQuery
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.apollographql.apollo3.ApolloClient
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.github.oshai.kotlinlogging.DelegatingKLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries

private val log = KotlinLogging.logger {}

/**
 * Constructs the `language_entries` and `language_values` string arrays in donottranslate.xml.
 *
 * - Finds all the `values-*` directories that contain `strings.xml`
 * - Parses out the language code from the directory name
 * - Uses the ICU libraries to determine the correct name for the language
 * - Sorts the list of languages using ICU collation rules
 * - Updates donottranslate.xml with the new data
 *
 * Run this after creating a new translation.
 *
 * Run with `gradlew :tools:mklanguages:run` or `runtools mklanguages`.
 */
class App : CliktCommand(help = """Update languages in donottranslate.xml""") {
    private val verbose by option("-n", "--verbose", help = "show additional information").flag()

    /**
     * Returns the full path to the Pachli `.../app/src/main/res` directory, starting from the
     * given [start] directory, walking up the tree if it can't be found there.
     *
     * @return the path, or null if it's not a subtree of [start] or any of its parents.
     */
    private fun findResourcePath(start: Path): Path? {
        val suffix = Path("app/src/main/res")

        var prefix = start
        var resourcePath: Path
        do {
            resourcePath = prefix / suffix
            if (resourcePath.exists()) return resourcePath
            prefix = prefix.parent
        } while (prefix != prefix.root)

        return null
    }

    override fun run() = runBlocking {
        System.setProperty("file.encoding", "UTF8")
        ((log as? DelegatingKLogger<*>)?.underlyingLogger as Logger).level = if (verbose) Level.INFO else Level.WARN

        val cwd = Paths.get("").toAbsolutePath()
        log.info { "working directory: $cwd" }

        val resourcePath = findResourcePath(cwd) ?: throw UsageError("could not find app/src/main/res in tree")

        // Enumerate all the values-* directories that contain a strings.xml file
        val resourceDirs = resourcePath.listDirectoryEntries("values-*")
            .filter { entry -> entry.isDirectory() }
            .filter { dir -> (dir / "strings.xml").isRegularFile() }

        if (resourceDirs.isEmpty()) throw UsageError("no strings.xml files found in $resourcePath/values-*")

        val apolloClient = ApolloClient.Builder()
            .serverUrl("https://api.fediverse.observer/")
            .build()

        val response = apolloClient.query(ServerListQuery()).execute()

        if (response.hasErrors()) {
            response.errors?.forEach {
                log.error { it }
            }
            return@runBlocking
        }

        val serverlist_xml = resourcePath / "values" / "serverlist.xml"
        val w = serverlist_xml.toFile().printWriter()

        w.println("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        w.println("<!-- DO NOT EDIT, auto generated, see tools/mkserverlist -->")
        w.println("<resources>")
        w.println("    <string-array name=\"server_list\" translatable=\"false\">")

        response.data?.nodes
            ?.asSequence()
            ?.filterNotNull()
            ?.filter { it.total_users != null && it.total_users >= 50 }
            ?.filterNot { it.softwarename == "activity-relay" }
            ?.filterNot { it.softwarename == "kbin" }
            ?.filterNot { it.softwarename == "lemmy" }
            ?.filterNot { it.softwarename == "mbin" }
            ?.filterNot { it.softwarename == "peertube" }
            ?.filterNot { it.softwarename == "wordpress" }
            ?.filterNot { it.softwarename == "writefreely" }
            ?.sortedByDescending { it.total_users }
            ?.toList()
            ?.forEach {
                w.println("        <item>${it.domain}</item> <!-- ${it.softwarename} ${it.total_users } -->")
            }
        w.println("    </string-array>")
        w.println("</resources>")
        w.close()
    }
}

fun main(args: Array<String>) = App().main(args)
