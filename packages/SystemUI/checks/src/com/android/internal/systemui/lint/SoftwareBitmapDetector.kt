/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.systemui.lint

import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import org.jetbrains.uast.UReferenceExpression

@Suppress("UnstableApiUsage")
class SoftwareBitmapDetector : Detector(), SourceCodeScanner {

    override fun getApplicableReferenceNames(): List<String> {
        return mutableListOf(
            "ALPHA_8", "RGB_565", "ARGB_4444", "ARGB_8888", "RGBA_F16", "RGBA_1010102")
    }

    override fun visitReference(
            context: JavaContext,
            reference: UReferenceExpression,
            referenced: PsiElement
    ) {
        val evaluator = context.evaluator
        if (evaluator.isMemberInClass(referenced as? PsiField, "android.graphics.Bitmap.Config")) {
            context.report(
                    issue = ISSUE,
                    location = context.getNameLocation(reference),
                    message = "Replace software bitmap with `Config.HARDWARE`"
            )
        }
    }

    companion object {
        @JvmField
        val ISSUE: Issue =
            Issue.create(
                id = "SoftwareBitmap",
                briefDescription = "Software bitmap",
                explanation = """
                        Software bitmaps occupy twice as much memory as `Config.HARDWARE` bitmaps \
                        do. However, hardware bitmaps are read-only. If you need to manipulate the \
                        pixels, use a shader (preferably) or a short lived software bitmap.""",
                category = Category.PERFORMANCE,
                priority = 8,
                severity = Severity.WARNING,
                implementation = Implementation(SoftwareBitmapDetector::class.java,
                        Scope.JAVA_FILE_SCOPE)
            )
    }
}
