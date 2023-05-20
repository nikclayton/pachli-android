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

package app.tusky.mkrelease.cmd

import app.tusky.mkrelease.Config
import com.github.ajalt.clikt.core.CliktCommand
import kotlinx.serialization.Serializable

/**
 * One or more pieces of work that to complete a release, but that should be completed as an
 * atomic unit
 */
@Serializable
sealed class ReleaseStep {
    abstract val config: Config

    /**
     * Do the work to move on to the next step.
     *
     * @return The next step in the process, or null if this is the final step.
     */
    abstract fun run(cmd: CliktCommand): ReleaseStep?

    open fun desc(): String = this.toString()
}
