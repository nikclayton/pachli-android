/*
 * Copyright (c) 2026 Pachli Association
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

package app.pachli.lint.checks

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue

@Suppress("ktlint:standard:function-naming")
class PachliErrorSubclassNameDoesNotEndWithErrorDetectorTest : LintDetectorTest() {
    override fun getDetector(): Detector = PachliErrorSubclassNameDoesNotEndWithErrorDetector()

    override fun getIssues(): List<Issue> = listOf(PachliErrorSubclassNameDoesNotEndWithErrorDetector.ISSUE)

    fun `test error missing suffix emits warning`() {
        lint().files(
            PachliError,
            kotlin(
                """
package test.pkg

import app.pachli.core.common.PachliError

sealed interface SomeError : PachliError {
    object ItFailed : SomeError
}
                """,
            ).indented(),
        ).allowMissingSdk().run().expect(
            """src/test/pkg/SomeError.kt:6: Error: Classes extending PachliError must end with the suffix Error. [IncorrectClassNamingSuffix]
    object ItFailed : SomeError
           ~~~~~~~~
1 error""",
        )
    }

    fun `test error with suffix does not emit warning`() {
        lint().files(
            PachliError,
            kotlin(
                """
package test.pkg

import app.pachli.core.common.PachliError

sealed interface SomeError : PachliError {
    object ItFailedError : SomeError
}
                """,
            ).indented(),
        ).allowMissingSdk().run().expectClean()
    }

    companion object Stubs {
        /** Stub for app.pachli.core.common.PachliError. */
        private val PachliError = kotlin(
            """
            package app.pachli.core.common

            interface PachliError
            """,
        ).indented()
    }
}
