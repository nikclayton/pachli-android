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

import app.tusky.mkrelease.TuskyVersion.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

@Execution(ExecutionMode.CONCURRENT)
internal class TuskyVersionTest {
    @Nested
    @Execution(ExecutionMode.CONCURRENT)
    inner class CompareTo {
        inner class Params(val first: TuskyVersion, val second: TuskyVersion, val expected: Int)

        private fun getParams(): Stream<Params> {
            return Stream.of(
                // Releases with equal major numbers
                // Equal
                Params(Release(1, 0), Release(1, 0), 0),
                // First has greater minor
                Params(Release(1, 1), Release(1, 0), 1),
                // Second has greater minor
                Params(Release(1, 0), Release(1, 1), -1),

                // Releases with different major numbers, larger major always wins
                Params(Release(2, 0), Release(1,0), 1),
                Params(Release(2, 0), Release(1, 1), 1),

                // Beta is always less than the equivalent release
                Params(Release(1,0), Beta(1,0,1), 1),

                // Beta with higher major or minor wins
                Params(Release(1,1), Beta(1,2, 1), -1),
                Params(Release(2, 0), Beta(2, 1, 1), -1),

                // Equal betas are equal
                Params(Beta(1, 1, 1), Beta(1, 1, 1), 0),

                // Betas with same major/minor differ by beta value
                Params(Beta(1, 1, 1), Beta(1, 1, 2), -1),
            )
        }

        @ParameterizedTest
        @MethodSource("getParams")
        fun `compares correctly`(params: CompareTo.Params) {
            assertEquals(params.expected, params.first.compareTo(params.second))
        }
    }

    @Test
    fun `sorts correctly`() {
        val releases = listOf(
            Release(1,0),
            Release(1,1),
            Release(1,2),
            Release(2,0),
            Beta(1,1,1),
            Beta(1,1,2),
            Beta(1,2,1),
            Beta(2,0,1),
            Beta(2,0,2)
        )

        val sorted = listOf(
            Release(1,0),
            Beta(1,1,1),
            Beta(1,1,2),
            Release(1,1),
            Beta(1,2,1),
            Release(1,2),
            Beta(2,0,1),
            Beta(2,0,2),
            Release(2,0)
        )

        assertEquals(releases.sorted(), sorted)
    }
}
