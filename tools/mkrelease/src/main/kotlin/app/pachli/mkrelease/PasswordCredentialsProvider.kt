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
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.URIish

/**
 * Credentials provider that only asks for a password, assumes that the caller already
 * has the username.
 *
 * For example, prompting a GPG password for a Git operation, where Git already knows which
 * signing key (effectively the username) that the password is for.
 */
class PasswordCredentialsProvider(private val t: Terminal) : CredentialsProvider() {
    private var password: String? = null

    override fun isInteractive() = true

    override fun supports(vararg items: CredentialItem?): Boolean {
        for (item in items) {
            when (item) {
                is CredentialItem.InformationalMessage -> continue
                is CredentialItem.Password -> continue
                else -> {
                    t.println("Unsupported item type in GpgCredentialsProvider: $item")
                    return false
                }
            }
        }
        return true
    }

    override fun get(uri: URIish?, vararg items: CredentialItem?): Boolean {
        println("get: $uri")
        for (item in items) {
            when (item) {
                is CredentialItem.InformationalMessage -> {
                    password = t.prompt(item.promptText, hideInput = true)
                }
                is CredentialItem.Password -> item.value = password?.toCharArray()
            }
        }

        return true
    }

    override fun reset(uri: URIish?) {
        password = null
    }
}
