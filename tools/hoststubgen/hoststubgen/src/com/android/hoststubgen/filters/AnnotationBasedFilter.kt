/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.hoststubgen.filters

import com.android.hoststubgen.ClassParseException
import com.android.hoststubgen.HostStubGenErrors
import com.android.hoststubgen.HostStubGenInternalException
import com.android.hoststubgen.InvalidAnnotationException
import com.android.hoststubgen.addNonNullElement
import com.android.hoststubgen.asm.CLASS_INITIALIZER_DESC
import com.android.hoststubgen.asm.CLASS_INITIALIZER_NAME
import com.android.hoststubgen.asm.ClassNodes
import com.android.hoststubgen.asm.findAnnotationValueAsString
import com.android.hoststubgen.asm.findAnyAnnotation
import com.android.hoststubgen.asm.toHumanReadableClassName
import com.android.hoststubgen.asm.toHumanReadableMethodName
import com.android.hoststubgen.asm.toJvmClassName
import com.android.hoststubgen.log
import com.android.hoststubgen.utils.ClassFilter
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode

// TODO: Detect invalid cases, such as...
// - Class's visibility is lower than the members'.
// - HostSideTestSubstituteWith is set, but it doesn't have @Stub or @Keep

/**
 * [OutputFilter] using Java annotations.
 */
class AnnotationBasedFilter(
        private val errors: HostStubGenErrors,
        private val classes: ClassNodes,
        stubAnnotations_: Set<String>,
        keepAnnotations_: Set<String>,
        stubClassAnnotations_: Set<String>,
        keepClassAnnotations_: Set<String>,
        throwAnnotations_: Set<String>,
        removeAnnotations_: Set<String>,
        substituteAnnotations_: Set<String>,
        nativeSubstituteAnnotations_: Set<String>,
        classLoadHookAnnotations_: Set<String>,
        keepStaticInitializerAnnotations_: Set<String>,
        private val annotationAllowedClassesFilter: ClassFilter,
        fallback: OutputFilter,
) : DelegatingFilter(fallback) {
    private var stubAnnotations = convertToInternalNames(stubAnnotations_)
    private var keepAnnotations = convertToInternalNames(keepAnnotations_)
    private var stubClassAnnotations = convertToInternalNames(stubClassAnnotations_)
    private var keepClassAnnotations = convertToInternalNames(keepClassAnnotations_)
    private var throwAnnotations = convertToInternalNames(throwAnnotations_)
    private var removeAnnotations = convertToInternalNames(removeAnnotations_)
    private var substituteAnnotations = convertToInternalNames(substituteAnnotations_)
    private var nativeSubstituteAnnotations = convertToInternalNames(nativeSubstituteAnnotations_)
    private var classLoadHookAnnotations = convertToInternalNames(classLoadHookAnnotations_)
    private var keepStaticInitializerAnnotations =
            convertToInternalNames(keepStaticInitializerAnnotations_)

    /** Annotations that control API visibility. */
    private var visibilityAnnotations: Set<String> = convertToInternalNames(
        stubAnnotations_ +
        keepAnnotations_ +
        stubClassAnnotations_ +
        keepClassAnnotations_ +
        throwAnnotations_ +
        removeAnnotations_)

    /**
     * All the annotations we use. Note, this one is in a [convertToJvmNames] format unlike
     * other ones, because of how it's used.
     */
    private var allAnnotations: Set<String> = convertToJvmNames(
        stubAnnotations_ +
                keepAnnotations_ +
                stubClassAnnotations_ +
                keepClassAnnotations_ +
                throwAnnotations_ +
                removeAnnotations_ +
                substituteAnnotations_ +
                nativeSubstituteAnnotations_ +
                classLoadHookAnnotations_)

    private val substitutionHelper = SubstitutionHelper()

    private val reasonAnnotation = "annotation"
    private val reasonClassAnnotation = "class-annotation"

    /**
     * Throw if an item has more than one visibility annotations.
     *
     * name1 - 4 are only used in exception messages. We take them as separate strings
     * to avoid unnecessary string concatenations.
     */
    private fun detectInvalidAnnotations(
        visibles: List<AnnotationNode>?,
        invisibles: List<AnnotationNode>?,
        type: String,
        name1: String,
        name2: String,
        name3: String,
    ) {
        var count = 0
        for (an in visibles ?: emptyList()) {
            if (visibilityAnnotations.contains(an.desc)) {
                count++
            }
        }
        for (an in invisibles ?: emptyList()) {
            if (visibilityAnnotations.contains(an.desc)) {
                count++
            }
        }
        if (count > 1) {
            val description = if (name2 == "" && name3 == "") {
                "$type $name1"
            } else {
                "$type $name1.$name2$name3"
            }
            throw InvalidAnnotationException(
                "Found more than one visibility annotations on $description")
        }
    }

    fun findAnyAnnotation(
            className: String,
            anyAnnotations: Set<String>,
            visibleAnnotations: List<AnnotationNode>?,
            invisibleAnnotations: List<AnnotationNode>?,
    ): AnnotationNode? {
        val ret = findAnyAnnotation(anyAnnotations, visibleAnnotations, invisibleAnnotations)

        if (ret != null) {
            if (!annotationAllowedClassesFilter.matches(className)) {
                throw InvalidAnnotationException(
                        "Class ${className.toHumanReadableClassName()} is not allowed to have " +
                                "Ravenwood annotations. Contact g/ravenwood for more details.")
            }
        }

        return ret
    }

    /**
     * Find a visibility annotation.
     *
     * name1 - 4 are only used in exception messages.
     */
    private fun findAnnotation(
            className: String,
            visibles: List<AnnotationNode>?,
            invisibles: List<AnnotationNode>?,
            type: String,
            name1: String,
            name2: String = "",
            name3: String = "",
    ): FilterPolicyWithReason? {
        detectInvalidAnnotations(visibles, invisibles, type, name1, name2, name3)

        findAnyAnnotation(className, stubAnnotations, visibles, invisibles)?.let {
            return FilterPolicy.Stub.withReason(reasonAnnotation)
        }
        findAnyAnnotation(className, stubClassAnnotations, visibles, invisibles)?.let {
            return FilterPolicy.StubClass.withReason(reasonClassAnnotation)
        }
        findAnyAnnotation(className, keepAnnotations, visibles, invisibles)?.let {
            return FilterPolicy.Keep.withReason(reasonAnnotation)
        }
        findAnyAnnotation(className, keepClassAnnotations, visibles, invisibles)?.let {
            return FilterPolicy.KeepClass.withReason(reasonClassAnnotation)
        }
        findAnyAnnotation(className, throwAnnotations, visibles, invisibles)?.let {
            return FilterPolicy.Throw.withReason(reasonAnnotation)
        }
        findAnyAnnotation(className, removeAnnotations, visibles, invisibles)?.let {
            return FilterPolicy.Remove.withReason(reasonAnnotation)
        }

        return null
    }

    override fun getPolicyForClass(className: String): FilterPolicyWithReason {
        val cn = classes.getClass(className)

        findAnnotation(
            cn.name,
            cn.visibleAnnotations,
            cn.invisibleAnnotations,
            "class",
            className)?.let {
            return it
        }

        // If it's any of the annotations, then always keep it.
        if (allAnnotations.contains(className)) {
            return FilterPolicy.KeepClass.withReason("HostStubGen Annotation")
        }

        return super.getPolicyForClass(className)
    }

    override fun getPolicyForField(
            className: String,
            fieldName: String
    ): FilterPolicyWithReason {
        val cn = classes.getClass(className)

        cn.fields?.firstOrNull { it.name == fieldName }?.let {fn ->
            findAnnotation(
                cn.name,
                fn.visibleAnnotations,
                fn.invisibleAnnotations,
                "field",
                className,
                fieldName
                )?.let { policy ->
                // If the item has an annotation, then use it.
                return policy
            }
        }
        return super.getPolicyForField(className, fieldName)
    }

    override fun getPolicyForMethod(
            className: String,
            methodName: String,
            descriptor: String
    ): FilterPolicyWithReason {
        val cn = classes.getClass(className)

        if (methodName == CLASS_INITIALIZER_NAME && descriptor == CLASS_INITIALIZER_DESC) {
            findAnyAnnotation(cn.name, keepStaticInitializerAnnotations,
                    cn.visibleAnnotations, cn.invisibleAnnotations)?.let {
                return FilterPolicy.Keep.withReason(reasonAnnotation)
            }
        }

        cn.methods?.firstOrNull { it.name == methodName && it.desc == descriptor }?.let { mn ->
            // @SubstituteWith is going to complicate the policy here, so we ask helper
            // what to do.
            substitutionHelper.getPolicyFromSubstitution(cn, mn.name, mn.desc)?.let {
                return it
            }

            // If there's no substitution, then we check the annotation.
            findAnnotation(
                cn.name,
                mn.visibleAnnotations,
                mn.invisibleAnnotations,
                "method",
                className,
                methodName,
                descriptor
            )?.let { policy ->
                return policy
            }
        }
        return super.getPolicyForMethod(className, methodName, descriptor)
    }

    override fun getRenameTo(
            className: String,
            methodName: String,
            descriptor: String
    ): String? {
        val cn = classes.getClass(className)

        // If the method has a "substitute with" annotation, then return its "value" parameter.
        cn.methods?.firstOrNull { it.name == methodName && it.desc == descriptor }?.let { mn ->
            return substitutionHelper.getRenameTo(cn, mn.name, mn.desc)
        }
        return null
    }

    override fun getNativeSubstitutionClass(className: String): String? {
        classes.getClass(className).let { cn ->
            findAnyAnnotation(nativeSubstituteAnnotations,
                    cn.visibleAnnotations, cn.invisibleAnnotations)?.let { an ->
                return getAnnotationField(an, "value")?.toJvmClassName()
            }
        }
        return null
    }

    override fun getClassLoadHooks(className: String): List<String> {
        val e = classes.getClass(className).let { cn ->
            findAnyAnnotation(classLoadHookAnnotations,
                cn.visibleAnnotations, cn.invisibleAnnotations)?.let { an ->
                getAnnotationField(an, "value")?.toHumanReadableMethodName()
            }
        }
        return addNonNullElement(super.getClassLoadHooks(className), e)
    }

    private data class MethodKey(val name: String, val desc: String)

    /**
     * In order to handle substitution, we need to build a reverse mapping of substitution
     * methods.
     *
     * This class automatically builds such a map internally that the above methods can
     * take advantage of.
     */
    private inner class SubstitutionHelper {
        private var currentClass: ClassNode? = null

        private var policiesFromSubstitution = mutableMapOf<MethodKey, FilterPolicyWithReason>()
        private var substituteToMethods = mutableMapOf<MethodKey, String>()

        fun getPolicyFromSubstitution(cn: ClassNode, methodName: String, descriptor: String):
                FilterPolicyWithReason? {
            setClass(cn)
            return policiesFromSubstitution[MethodKey(methodName, descriptor)]
        }

        fun getRenameTo(cn: ClassNode, methodName: String, descriptor: String): String? {
            setClass(cn)
            return substituteToMethods[MethodKey(methodName, descriptor)]
        }

        /**
         * Every time we see a different class, we scan all its methods for substitution attributes,
         * and compute (implicit) policies caused by them.
         *
         * For example, for the following methods:
         *
         *   @Stub
         *   @Substitute(suffix = "_host")
         *   private void foo() {
         *      // This isn't supported on the host side.
         *   }
         *   private void foo_host() {
         *      // Host side implementation
         *   }
         *
         * We internally handle them as:
         *
         *   foo() -> Remove
         *   foo_host() -> Stub, and then rename it to foo().
         */
        private fun setClass(cn: ClassNode) {
            if (currentClass == cn) {
                return
            }
            // If the class is changing, we'll rebuild the internal structure.
            currentClass = cn

            policiesFromSubstitution.clear()
            substituteToMethods.clear()

            for (mn in cn.methods ?: emptyList()) {
                findAnyAnnotation(substituteAnnotations,
                        mn.visibleAnnotations,
                        mn.invisibleAnnotations)?.let { an ->

                    // Find the policy for this method.
                    val policy = outermostFilter.getPolicyForMethod(cn.name, mn.name, mn.desc)
                            .policy.resolveClassWidePolicy()
                    // Make sure it's either Stub or Keep.
                    if (!(policy.needsInStub || policy.needsInImpl)) {
                        // TODO: Use the real annotation names in the message
                        errors.onErrorFound("@SubstituteWith must have either @Stub or @Keep")
                        return@let
                    }
                    if (!policy.isUsableWithMethods) {
                        throw HostStubGenInternalException("Policy $policy shouldn't show up here")
                    }

                    val suffix = getAnnotationField(an, "suffix", false) ?: "\$ravenwood"
                    val renameFrom = mn.name + suffix
                    val renameTo = mn.name

                    if (renameFrom == renameTo) {
                        errors.onErrorFound("@SubstituteWith have a different name")
                        return@let
                    }

                    // This mn has "SubstituteWith". This means,
                    // 1. Re move the "rename-to" method, so add it to substitutedMethods.
                    policiesFromSubstitution[MethodKey(renameTo, mn.desc)] =
                            FilterPolicy.Remove.withReason("substitute-to")

                    // If the policy is "stub", use "stub".
                    // Otherwise, it must be "keep" or "throw", but there's no point in using
                    // "throw", so let's use "keep".
                    val newPolicy = if (policy.needsInStub) policy else FilterPolicy.Keep
                    // 2. We also keep the from-to in the map.
                    policiesFromSubstitution[MethodKey(renameFrom, mn.desc)] =
                            newPolicy.withReason("substitute-from")
                    substituteToMethods[MethodKey(renameFrom, mn.desc)] = renameTo

                    log.v("Substitution found: %s%s -> %s", renameFrom, mn.desc, renameTo)
                }
            }
        }
    }

    /**
     * Return the (String) value of 'value' parameter from an annotation.
     */
    private fun getAnnotationField(an: AnnotationNode, name: String,
                                   required: Boolean = true): String? {
        try {
            val suffix = findAnnotationValueAsString(an, name)
            if (suffix == null && required) {
                errors.onErrorFound("Annotation \"${an.desc}\" must have field $name")
            }
            return suffix
        } catch (e: ClassParseException) {
            errors.onErrorFound(e.message!!)
            return null
        }
    }

    companion object {
        /**
         * Convert from human-readable type names (e.g. "com.android.TypeName") to the internal type
         * names (e.g. "Lcom/android/TypeName).
         */
        private fun convertToInternalNames(input: Set<String>): Set<String> {
            val ret = mutableSetOf<String>()
            input.forEach { ret.add("L" + it.toJvmClassName() + ";") }
            return ret
        }

        /**
         * Convert from human-readable type names to JVM type names.
         */
        private fun convertToJvmNames(input: Set<String>): Set<String> {
            val ret = mutableSetOf<String>()
            input.forEach { ret.add(it.toJvmClassName()) }
            return ret
        }
    }
}
