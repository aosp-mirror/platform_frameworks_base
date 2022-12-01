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

package com.google.android.lint

import com.android.tools.lint.detector.api.getUMethod
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UParameter

fun isPermissionMethodCall(callExpression: UCallExpression): Boolean {
    val method = callExpression.resolve()?.getUMethod() ?: return false
    return hasPermissionMethodAnnotation(method)
}

fun hasPermissionMethodAnnotation(method: UMethod): Boolean =
        getPermissionMethodAnnotation(method) != null

fun getPermissionMethodAnnotation(method: UMethod?): UAnnotation? = method?.uAnnotations
        ?.firstOrNull { it.qualifiedName == ANNOTATION_PERMISSION_METHOD }

fun hasPermissionNameAnnotation(parameter: UParameter) = parameter.annotations.any {
    it.hasQualifiedName(ANNOTATION_PERMISSION_NAME)
}
