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

import app.pachli.mkrelease.PachliVersion.Beta
import app.pachli.mkrelease.PachliVersion.Release
import app.pachli.mkrelease.ReleaseType.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream
import kotlin.math.sign

@Execution(ExecutionMode.CONCURRENT)
internal class PachliVersionTest {
    @Nested
    @Execution(ExecutionMode.CONCURRENT)
    inner class CompareTo {
        inner class Params(val first: PachliVersion, val second: PachliVersion, val expected: Int)

        private fun getParams(): Stream<Params> {
            return Stream.of(
                // Releases with equal major numbers
                // Equal
                Params(
                    Release(1, 0, 0, 100),
                    Release(1, 0, 0, 100),
                    0
                ),

                // First has greater minor
                Params(
                    Release(1, 1, 0, 101),
                    Release(1, 0, 0, 100),
                    1
                ),

                // Second has greater minor
                Params(
                    Release(1, 0, 0, 100),
                    Release(1, 1, 0, 101),
                    -1
                ),

                // Releases with different major numbers, larger major always wins
                Params(
                    Release(2, 0, 0, 101),
                    Release(1, 0, 0, 100),
                    1
                ),
                Params(
                    Release(2, 0, 0, 101),
                    Release(1, 1, 0, 100),
                    1
                ),

                // Beta is always less than the equivalent release
                Params(
                    Release(1, 0, 0, 101),
                    Beta(1, 0, 0, 1, 100),
                    1
                ),

                // Beta with higher major or minor wins
                Params(
                    Release(1, 1, 0, 100),
                    Beta(1, 2, 0, 1, 101),
                    -1
                ),
                Params(
                    Release(2, 0, 0, 100),
                    Beta(2, 1, 0, 1, 101),
                    -1
                ),

                // Equal betas are equal
                Params(
                    Beta(1, 1, 0, 1, 100),
                    Beta(1, 1, 0, 1, 100),
                    0
                ),

                // Betas with same major/minor differ by beta value
                Params(
                    Beta(1, 1, 0, 0, 100),
                    Beta(1, 1, 0, 2, 102),
                    -1
                )
            )
        }

        @ParameterizedTest
        @MethodSource("getParams")
        fun `compares correctly`(params: CompareTo.Params) {
            assertEquals(params.expected.sign, params.first.compareTo(params.second).sign)
        }
    }

    @Test
    fun `sorts correctly`() {
        val releases = listOf(
            Release(0, 1, 0, 10),
            Release(0, 1, 1, 11),
            Release(1, 0, 0, 100),
            Release(1, 1, 0, 110),
            Release(1, 2, 0, 120),
            Release(1, 2, 1, 121),
            Release(2, 0, 0, 130),
            Beta(1, 1, 0, 1, 105),
            Beta(1, 1, 0, 2, 107),
            Beta(1, 2, 0, 1, 115),
            Beta(2, 0, 0, 1, 125),
            Beta(2, 0, 0, 2, 127)
        )

        val sorted = listOf(
            Release(0, 1, 0, 10),
            Release(0, 1, 1, 11),
            Release(1, 0, 0, 100),
            Beta(1, 1, 0, 1, 105),
            Beta(1, 1, 0, 2, 107),
            Release(1, 1, 0, 110),
            Beta(1, 2, 0, 1, 115),
            Release(1, 2, 0, 120),
            Release(1, 2, 1, 121),
            Beta(2, 0, 0, 1, 125),
            Beta(2, 0, 0, 2, 127),
            Release(2, 0, 0, 130)
        )

        assertEquals(releases.sorted(), sorted)
    }

    @Nested
    @Execution(ExecutionMode.CONCURRENT)
    inner class Next {
        inner class Params(val releaseType: ReleaseType, val got: PachliVersion, val want: PachliVersion)

        private fun getParams(): Stream<Next.Params> {
            return Stream.of(
                Params(MAJOR, Release(1, 0, 0, 1), Beta(2,0, 0, 1,2)),
                Params(MAJOR, Release(1, 1, 0, 1), Beta(2,0, 0, 1,2)),
                Params(MINOR, Release(1, 0, 0, 1), Beta(1, 1, 0, 1, 2)),
                Params(MINOR, Release(1, 1, 0, 2), Beta(1, 2, 0, 1, 3)),
                Params(MAJOR, Beta(2, 0, 0, 1, 2), Beta(2, 0, 0, 2, 3)),
                Params(MINOR, Beta(2, 1, 0, 1, 2), Beta(2, 1, 0, 2, 3))
            )
        }

        @ParameterizedTest
        @MethodSource("getParams")
        fun `next() is correct`(params: Next.Params) {
            assertEquals(params.want, params.got.next(params.releaseType))
        }
    }

    @Nested
    @Execution(ExecutionMode.CONCURRENT)
    inner class From {
        inner class Params(val v: String, val want: PachliVersion)

        private fun getParams(): Stream<From.Params> {
            return Stream.of(
                Params("1.0.0", Release(1, 0, 0, 100)),
                Params("1.0.2", Release(1, 0, 2, 100)),
                Params("1.1.2", Release(1, 1, 2, 100)),
                // Missing patch component is OK
                Params("1.0", Release(1, 0, 0, 100)),
                Params("1.0.0 beta 1", Beta(1, 0, 0, 1, 100)),
                Params("1.0.2 beta 3", Beta(1, 0, 2, 3, 100)),
                Params("1.1.2 beta 15", Beta(1, 1, 2, 15, 100)),
                // Missing patch component is OK
                Params("1.0 beta 12", Beta(1, 0, 0, 12, 100)),
            )
        }

        @ParameterizedTest
        @MethodSource("getParams")
        fun `from() is correct`(params: From.Params) {
            assertEquals(params.want, PachliVersion.from(params.v, 100))
        }
    }
}
