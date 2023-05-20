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

import org.eclipse.jgit.errors.UnsupportedCredentialItem
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.TemporaryBuffer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Path
import java.util.Arrays
import java.util.Optional
import java.util.function.Function
import java.util.regex.Matcher
import java.util.regex.Pattern


class DelegatingCredentialsProvider(projectDir: Path) :
    CredentialsProvider() {
    private val additionalNativeGitEnvironment: Map<String, String> = HashMap()
    private val logger: Logger = LoggerFactory.getLogger(DelegatingCredentialsProvider::class.java)
    private val projectDir: Path
    private val credentials: MutableMap<URIish, CredentialsPair> = HashMap()

    init {
        this.projectDir = projectDir
    }

    // possibly interactive in case some credential helper asks for input
    override fun isInteractive() = true

    override fun supports(vararg items: CredentialItem?): Boolean {
        return Arrays.stream(items)
            .allMatch { item -> item is CredentialItem.Username || item is CredentialItem.Password }
    }

    @Throws(UnsupportedCredentialItem::class)
    override operator fun get(uri: URIish, vararg items: CredentialItem?): Boolean {
        val credentialsPair: CredentialsPair = credentials.computeIfAbsent(uri,
            Function<URIish, CredentialsPair> { u: URIish? ->
                try {
                    lookupCredentials(uri)
                } catch (e: IOException) {
                    logger.warn(
                        "Failed to look up credentials via 'git credential fill' for: $uri",
                        e
                    )
                    null
                } catch (e: InterruptedException) {
                    logger.warn(
                        "Failed to look up credentials via 'git credential fill' for: $uri",
                        e
                    )
                    null
                } catch (e: RuntimeException) {
                    logger.warn(
                        "Failed to look up credentials via 'git credential fill' for: $uri",
                        e
                    )
                    null
                }
            }) ?: return false

        // map extracted credentials to CredentialItems, see also: org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
        for (item: CredentialItem? in items) {
            item ?: continue
            if (item is CredentialItem.Username) {
                item.value = credentialsPair.username
            } else if (item is CredentialItem.Password) {
                item.value = credentialsPair.password
            } else if (item is CredentialItem.StringType && item.getPromptText()
                    .equals("Password: ")
            ) {
                item.value = String(credentialsPair.password)
            } else {
                throw UnsupportedCredentialItem(
                    uri,
                    "${item.javaClass.name}:${item.promptText}"
                )
            }
        }
        return true
    }

    // see also: org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider.clear()
    override fun reset(uri: URIish) {
        Optional.ofNullable(credentials.remove(uri))
    }

    fun resetAll() {
        HashSet(credentials.keys).forEach { uri: URIish -> reset(uri) }
    }

    @Throws(IOException::class, InterruptedException::class)
    private fun lookupCredentials(uri: URIish): CredentialsPair {
        // utilize JGit command execution capabilities
        val fs: FS = FS.detect()
        val procBuilder: ProcessBuilder = fs.runInShell("git", arrayOf("credential", "fill"))

        // prevent native git from requesting console input (not implemented)
        procBuilder.environment()["GIT_TERMINAL_PROMPT"] = "0"

        // add additional environment entries, if present (test only)
        if (additionalNativeGitEnvironment.isNotEmpty()) {
            procBuilder.environment().putAll(additionalNativeGitEnvironment)
        }
        procBuilder.directory(projectDir.toFile())
        val result: FS.ExecutionResult =
            fs.execute(procBuilder, ByteArrayInputStream(buildGitCommandInput(uri).toByteArray()))
        if (result.rc != 0) {
            logger.info(bufferToString(result.stdout))
            logger.error(bufferToString(result.stderr))
            throw IllegalStateException(
                ("Native Git invocation failed with return code " + result.rc
                    ) + ". See previous log output for more details."
            )
        }
        return extractCredentials(bufferToString(result.stdout))
    }

    // build input for "git credential fill" as per https://git-scm.com/docs/git-credential#_typical_use_of_git_credential
    private fun buildGitCommandInput(uri: URIish): String {
        val builder = StringBuilder()
        builder.append("protocol=").append(uri.scheme).append("\n")
        builder.append("host=").append(uri.host)
        if (uri.port != -1) {
            builder.append(":").append(uri.port)
        }
        builder.append("\n")
        Optional.ofNullable(uri.path)
            .map { path -> if (path.startsWith("/")) path.substring(1) else path }
            .ifPresent { path -> builder.append("path=").append(path).append("\n") }
        Optional.ofNullable(uri.user)
            .ifPresent { user -> builder.append("username=").append(user).append("\n") }
        return builder.toString()
    }

    @Throws(IOException::class)
    private fun bufferToString(buffer: TemporaryBuffer): String {
        val baos = ByteArrayOutputStream()
        buffer.writeTo(baos, null)
        return baos.toString()
    }

    private fun extractCredentials(nativeGitOutput: String): CredentialsPair {
        val matcher: Matcher =
            Pattern.compile("(?<=username=).+|(?<=password=).+").matcher(nativeGitOutput)
        if (!matcher.find()) {
            throw IllegalStateException("Could not find username in native Git output")
        }
        val username: String = matcher.group()
        if (!matcher.find()) {
            throw IllegalStateException("Could not find password in native Git output")
        }
        val password: CharArray = matcher.group().toCharArray()
        return CredentialsPair(
            username = username,
            password = password
        )
    }

    private data class CredentialsPair(val username: String?, val password: CharArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as CredentialsPair

            if (username != other.username) return false
            if (!password.contentEquals(other.password)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = username?.hashCode() ?: 0
            result = 31 * result + password.contentHashCode()
            return result
        }
    }
}
