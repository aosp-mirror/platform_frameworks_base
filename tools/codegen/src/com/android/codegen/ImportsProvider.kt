/*
 * Copyright (C) 2019 The Android Open Source Project
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


package com.android.codegen

import com.github.javaparser.ast.CompilationUnit

/**
 * Mixin for optionally shortening references based on existing imports
 */
interface ImportsProvider {

    abstract val fileAst: CompilationUnit

    val NonNull: String get() { return classRef("android.annotation.NonNull") }
    val NonEmpty: String get() { return classRef("android.annotation.NonEmpty") }
    val Nullable: String get() { return classRef("android.annotation.Nullable") }
    val TextUtils: String get() { return classRef("android.text.TextUtils") }
    val LinkedHashMap: String get() { return classRef("java.util.LinkedHashMap") }
    val Collections: String get() { return classRef("java.util.Collections") }
    val Preconditions: String get() { return classRef("com.android.internal.util.Preconditions") }
    val ArrayList: String get() { return classRef("java.util.ArrayList") }
    val DataClass: String get() { return classRef("com.android.internal.util.DataClass") }
    val DataClassEnum: String get() { return classRef("com.android.internal.util.DataClass.Enum") }
    val ParcelWith: String get() { return classRef("com.android.internal.util.DataClass.ParcelWith") }
    val PluralOf: String get() { return classRef("com.android.internal.util.DataClass.PluralOf") }
    val Each: String get() { return classRef("com.android.internal.util.DataClass.Each") }
    val MaySetToNull: String get() { return classRef("com.android.internal.util.DataClass.MaySetToNull") }
    val DataClassGenerated: String get() { return classRef("com.android.internal.util.DataClass.Generated") }
    val DataClassSuppressConstDefs: String get() { return classRef("com.android.internal.util.DataClass.SuppressConstDefsGeneration") }
    val DataClassSuppress: String get() { return classRef("com.android.internal.util.DataClass.Suppress") }
    val GeneratedMember: String get() { return classRef("com.android.internal.util.DataClass.Generated.Member") }
    val Parcelling: String get() { return classRef("com.android.internal.util.Parcelling") }
    val Parcelable: String get() { return classRef("android.os.Parcelable") }
    val Parcel: String get() { return classRef("android.os.Parcel") }
    val UnsupportedAppUsage: String get() { return classRef("android.compat.annotation.UnsupportedAppUsage") }

    /**
     * Optionally shortens a class reference if there's a corresponding import present
     */
    fun classRef(fullName: String): String {

        val pkg = fullName.substringBeforeLast(".")
        val simpleName = fullName.substringAfterLast(".")
        if (fileAst.imports.any { imprt ->
                    imprt.nameAsString == fullName
                            || (imprt.isAsterisk && imprt.nameAsString == pkg)
                }) {
            return simpleName
        } else {
            val outerClass = pkg.substringAfterLast(".", "")
            if (outerClass.firstOrNull()?.isUpperCase() == true) {
                return classRef(pkg) + "." + simpleName
            }
        }
        return fullName
    }

    /** @see classRef */
    fun memberRef(fullName: String): String {
        val className = fullName.substringBeforeLast(".")
        val methodName = fullName.substringAfterLast(".")
        return if (fileAst.imports.any {
                    it.isStatic
                            && (it.nameAsString == fullName
                            || (it.isAsterisk && it.nameAsString == className))
                }) {
            className.substringAfterLast(".") + "." + methodName
        } else {
            classRef(className) + "." + methodName
        }
    }
}

/** @see classRef */
inline fun <reified T : Any> ImportsProvider.classRef(): String {
    return classRef(T::class.java.name)
}