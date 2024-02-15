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

package com.google.android.lint.aidl

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.SourceCodeScanner
import org.jetbrains.uast.UBlockExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UMethod

/**
 * Abstract class for detectors that look for methods implementing
 * generated AIDL interface stubs
 */
abstract class AidlImplementationDetector : Detector(), SourceCodeScanner {
    override fun getApplicableUastTypes(): List<Class<out UElement?>> =
            listOf(UMethod::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler = AidlStubHandler(context)

    private inner class AidlStubHandler(val context: JavaContext) : UElementHandler() {
        override fun visitMethod(node: UMethod) {
            val interfaceName = getContainingAidlInterface(context, node)
                    .takeUnless(EXCLUDED_CPP_INTERFACES::contains) ?: return
            val body = (node.uastBody as? UBlockExpression) ?: return
            visitAidlMethod(context, node, interfaceName, body)
        }
    }

    abstract fun visitAidlMethod(
            context: JavaContext,
            node: UMethod,
            interfaceName: String,
            body: UBlockExpression,
    )
}
