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

package com.google.android.lint.parcel

import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.LintFix
import com.android.tools.lint.detector.api.Location
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiCallExpression
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiIntersectionType
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.PsiWildcardType
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UVariable

/**
 * Subclass this class and override {@link #getBoundingClass} to report an unsafe Parcel API issue
 * with a fix that migrates towards the new safer API by appending an argument in the form of
 * {@code com.package.ItemType.class} coming from the result of the overridden method.
 */
abstract class CallMigrator(
        val method: Method,
        private val rejects: Set<String> = emptySet(),
) {
    open fun report(context: JavaContext, call: UCallExpression, method: PsiMethod) {
        val location = context.getLocation(call)
        val itemType = filter(getBoundingClass(context, call, method))
        val fix = (itemType as? PsiClassType)?.let { type ->
            getParcelFix(location, this.method.name, getArgumentSuffix(type))
        }
        val message = "Unsafe `${this.method.className}.${this.method.name}()` API usage"
        context.report(SaferParcelChecker.ISSUE_UNSAFE_API_USAGE, call, location, message, fix)
    }

    protected open fun getArgumentSuffix(type: PsiClassType) =
            ", ${type.rawType().canonicalText}.class"

    protected open fun getBoundingClass(
            context: JavaContext,
            call: UCallExpression,
            method: PsiMethod,
    ): PsiType? = null

    protected fun getItemType(type: PsiType, container: String): PsiClassType? {
        val supers = getParentTypes(type).mapNotNull { it as? PsiClassType }
        val containerType = supers.firstOrNull { it.rawType().canonicalText == container }
                ?: return null
        val itemType = containerType.parameters.getOrNull(0) ?: return null
        // TODO: Expand to other types, see PsiTypeVisitor
        return when (itemType) {
            is PsiClassType -> itemType
            is PsiWildcardType -> itemType.bound as PsiClassType
            else -> null
        }
    }

    /**
     * Tries to obtain the type expected by the "receiving" end given a certain {@link UElement}.
     *
     * This could be an assignment, an argument passed to a method call, to a constructor call, a
     * type cast, etc. If no receiving end is found, the type of the UExpression itself is returned.
     */
    protected fun getReceivingType(expression: UElement): PsiType? {
        val parent = expression.uastParent
        var type = when (parent) {
            is UCallExpression -> {
                val i = parent.valueArguments.indexOf(expression)
                val psiCall = parent.sourcePsi as? PsiCallExpression ?: return null
                val typeSubstitutor = psiCall.resolveMethodGenerics().substitutor
                val method = psiCall.resolveMethod()!!
                method.getSignature(typeSubstitutor).parameterTypes[i]
            }
            is UVariable -> parent.type
            is UExpression -> parent.getExpressionType()
            else -> null
        }
        if (type == null && expression is UExpression) {
            type = expression.getExpressionType()
        }
        return type
    }

    protected fun filter(type: PsiType?): PsiType? {
        // It's important that PsiIntersectionType case is above the one that check the type in
        // rejects, because for intersect types, the canonicalText is one of the terms.
        if (type is PsiIntersectionType) {
            return type.conjuncts.mapNotNull(this::filter).firstOrNull()
        }
        if (type == null || type.canonicalText in rejects) {
            return null
        }
        if (type is PsiClassType && type.resolve() is PsiTypeParameter) {
            return null
        }
        return type
    }

    private fun getParentTypes(type: PsiType): Set<PsiType> =
            type.superTypes.flatMap(::getParentTypes).toSet() + type

    protected fun getParcelFix(location: Location, method: String, arguments: String) =
            LintFix
                    .create()
                    .name("Migrate to safer Parcel.$method() API")
                    .replace()
                    .range(location)
                    .pattern("$method\\s*\\(((?:.|\\n)*)\\)")
                    .with("\\k<1>$arguments")
                    .autoFix()
                    .build()
}

/**
 * This class derives the type to be appended by inferring the generic type of the {@code container}
 * type (eg. "java.util.List") of the {@code argument}-th argument.
 */
class ContainerArgumentMigrator(
        method: Method,
        private val argument: Int,
        private val container: String,
        rejects: Set<String> = emptySet(),
) : CallMigrator(method, rejects) {
    override fun getBoundingClass(
            context: JavaContext, call: UCallExpression, method: PsiMethod
    ): PsiType? {
        val firstParamType = call.valueArguments[argument].getExpressionType() ?: return null
        return getItemType(firstParamType, container)!!
    }

    /**
     * We need to insert a casting construct in the class parameter. For example:
     *   (Class<Foo<Bar>>) (Class<?>) Foo.class.
     * This is needed for when the arguments of the conflict (eg. when there is List<Foo<Bar>> and
     * class type is Class<Foo?).
     */
    override fun getArgumentSuffix(type: PsiClassType): String {
        if (type.parameters.isNotEmpty()) {
            val rawType = type.rawType()
            return ", (Class<${type.canonicalText}>) (Class<?>) ${rawType.canonicalText}.class"
        }
        return super.getArgumentSuffix(type)
    }
}

/**
 * This class derives the type to be appended by inferring the generic type of the {@code container}
 * type (eg. "java.util.List") of the return type of the method.
 */
class ContainerReturnMigrator(
        method: Method,
        private val container: String,
        rejects: Set<String> = emptySet(),
) : CallMigrator(method, rejects) {
    override fun getBoundingClass(
            context: JavaContext, call: UCallExpression, method: PsiMethod
    ): PsiType? {
        val type = getReceivingType(call.uastParent!!) ?: return null
        return getItemType(type, container)
    }
}

/**
 * This class derives the type to be appended by inferring the expected type for the method result.
 */
class ReturnMigrator(
        method: Method,
        rejects: Set<String> = emptySet(),
) : CallMigrator(method, rejects) {
    override fun getBoundingClass(
            context: JavaContext, call: UCallExpression, method: PsiMethod
    ): PsiType? {
        return getReceivingType(call.uastParent!!)
    }
}

/**
 * This class appends the class loader and the class object by deriving the type from the method
 * result.
 */
class ReturnMigratorWithClassLoader(
        method: Method,
        rejects: Set<String> = emptySet(),
) : CallMigrator(method, rejects) {
    override fun getBoundingClass(
            context: JavaContext, call: UCallExpression, method: PsiMethod
    ): PsiType? {
        return getReceivingType(call.uastParent!!)
    }

    override fun getArgumentSuffix(type: PsiClassType): String =
            "${type.rawType().canonicalText}.class.getClassLoader(), " +
                    "${type.rawType().canonicalText}.class"

}

/**
 * This class derives the type to be appended by inferring the expected array type
 * for the method result.
 */
class ArrayReturnMigrator(
    method: Method,
    rejects: Set<String> = emptySet(),
) : CallMigrator(method, rejects) {
    override fun getBoundingClass(
           context: JavaContext, call: UCallExpression, method: PsiMethod
    ): PsiType? {
        val type = getReceivingType(call.uastParent!!)
        return (type as? PsiArrayType)?.componentType
    }
}
