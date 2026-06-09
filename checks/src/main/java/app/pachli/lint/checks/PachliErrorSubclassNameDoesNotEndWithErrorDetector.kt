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

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UElement

class PachliErrorSubclassNameDoesNotEndWithErrorDetector : Detector(), Detector.UastScanner {
    override fun getApplicableUastTypes(): List<Class<out UElement>> {
        return listOf(UClass::class.java)
    }

    override fun createUastHandler(context: JavaContext): UElementHandler {
        return object : UElementHandler() {
            override fun visitClass(node: UClass) {
                val className = node.name ?: return

                val targetSuperClass = "app.pachli.core.common.PachliError"
                val requiredSuffix = "Error"

                val evaluator = context.evaluator
                if (evaluator.inheritsFrom(node, targetSuperClass, false)) {
                    if (!className.endsWith(requiredSuffix)) {
                        // Note: Can't apply a quick fix, because only the declaration will be
                        // renamed, not the usages. See https://issuetracker.google.com/issues/369758905.
                        context.report(
                            issue = ISSUE,
                            scopeClass = node,
                            location = context.getNameLocation(node),
                            message = "Classes extending `${targetSuperClass.substringAfterLast(".")}` must end with the suffix `$requiredSuffix`.",
                        )
                    }
                }
            }
        }
    }

    companion object {
        val ISSUE = Issue.create(
            id = "PachliErrorSubclassNameDoesNotEndWithError",
            briefDescription = "`PachliError` subclass names should end with `Error`",
            explanation = "Names of subclasses of `PachliError` should end with `Error` for clarity. Refactor to rename the class..",
            category = Category.CORRECTNESS,
            priority = 6,
            severity = Severity.ERROR,
            implementation = Implementation(
                PachliErrorSubclassNameDoesNotEndWithErrorDetector::class.java,
                Scope.JAVA_FILE_SCOPE,
            ),
        )
    }
}
