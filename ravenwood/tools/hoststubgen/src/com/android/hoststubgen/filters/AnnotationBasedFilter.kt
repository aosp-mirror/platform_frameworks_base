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
import com.android.hoststubgen.InvalidAnnotationException
import com.android.hoststubgen.addLists
import com.android.hoststubgen.asm.CLASS_INITIALIZER_DESC
import com.android.hoststubgen.asm.CLASS_INITIALIZER_NAME
import com.android.hoststubgen.asm.ClassNodes
import com.android.hoststubgen.asm.findAllAnnotations
import com.android.hoststubgen.asm.findAnnotationValueAsString
import com.android.hoststubgen.asm.findAnyAnnotation
import com.android.hoststubgen.asm.getPackageNameFromFullClassName
import com.android.hoststubgen.asm.resolveClassNameWithDefaultPackage
import com.android.hoststubgen.asm.toHumanReadableClassName
import com.android.hoststubgen.asm.toHumanReadableMethodName
import com.android.hoststubgen.asm.toJvmClassName
import com.android.hoststubgen.log
import com.android.hoststubgen.utils.ClassFilter
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode

// TODO: Detect invalid cases, such as...
// - Class's visibility is lower than the members'.

/**
 * [OutputFilter] using Java annotations.
 */
class AnnotationBasedFilter(
    private val errors: HostStubGenErrors,
    private val classes: ClassNodes,
    keepAnnotations_: Set<String>,
    keepClassAnnotations_: Set<String>,
    throwAnnotations_: Set<String>,
    removeAnnotations_: Set<String>,
    ignoreAnnotations_: Set<String>,
    substituteAnnotations_: Set<String>,
    redirectAnnotations_: Set<String>,
    redirectionClassAnnotations_: Set<String>,
    classLoadHookAnnotations_: Set<String>,
    keepStaticInitializerAnnotations_: Set<String>,
    private val annotationAllowedClassesFilter: ClassFilter,
    fallback: OutputFilter,
) : DelegatingFilter(fallback) {
    private val keepAnnotations = convertToInternalNames(keepAnnotations_)
    private val keepClassAnnotations = convertToInternalNames(keepClassAnnotations_)
    private val throwAnnotations = convertToInternalNames(throwAnnotations_)
    private val removeAnnotations = convertToInternalNames(removeAnnotations_)
    private val ignoreAnnotations = convertToInternalNames(ignoreAnnotations_)
    private val redirectAnnotations = convertToInternalNames(redirectAnnotations_)
    private val substituteAnnotations = convertToInternalNames(substituteAnnotations_)
    private val redirectionClassAnnotations =
        convertToInternalNames(redirectionClassAnnotations_)
    private val classLoadHookAnnotations = convertToInternalNames(classLoadHookAnnotations_)
    private val keepStaticInitializerAnnotations =
        convertToInternalNames(keepStaticInitializerAnnotations_)

    /** Annotations that control API visibility. */
    private val visibilityAnnotations = keepAnnotations +
            keepClassAnnotations +
            throwAnnotations +
            removeAnnotations +
            ignoreAnnotations +
            redirectAnnotations +
            substituteAnnotations

    /** All the annotations we use. */
    private val allAnnotations = visibilityAnnotations +
            redirectionClassAnnotations +
            classLoadHookAnnotations +
            keepStaticInitializerAnnotations

    /**
     * All the annotations we use. Note, this one is in a [convertToJvmNames] format unlike
     * other ones, because of how it's used.
     */
    private val allAnnotationClasses: Set<String> = convertToJvmNames(
        keepAnnotations_ +
                keepClassAnnotations_ +
                throwAnnotations_ +
                removeAnnotations_ +
                redirectAnnotations_ +
                substituteAnnotations_ +
                redirectionClassAnnotations_ +
                classLoadHookAnnotations_ +
                keepStaticInitializerAnnotations_
    )

    private val policyCache = mutableMapOf<String, ClassAnnotations>()

    private val AnnotationNode.policy: FilterPolicyWithReason? get() {
        return when (desc) {
            in keepAnnotations -> FilterPolicy.Keep.withReason(REASON_ANNOTATION)
            in keepClassAnnotations -> FilterPolicy.KeepClass.withReason(REASON_CLASS_ANNOTATION)
            in substituteAnnotations -> FilterPolicy.Substitute.withReason(REASON_ANNOTATION)
            in throwAnnotations -> FilterPolicy.Throw.withReason(REASON_ANNOTATION)
            in removeAnnotations -> FilterPolicy.Remove.withReason(REASON_ANNOTATION)
            in ignoreAnnotations -> FilterPolicy.Ignore.withReason(REASON_ANNOTATION)
            in redirectAnnotations -> FilterPolicy.Redirect.withReason(REASON_ANNOTATION)
            else -> null
        }
    }

    private fun getAnnotationPolicy(cn: ClassNode): ClassAnnotations {
        return policyCache.getOrPut(cn.name) { ClassAnnotations(cn) }
    }

    override fun getPolicyForClass(className: String): FilterPolicyWithReason {
        // If it's any of the annotations, then always keep it.
        if (allAnnotationClasses.contains(className)) {
            return FilterPolicy.KeepClass.withReason("HostStubGen Annotation")
        }

        val cn = classes.getClass(className)
        return getAnnotationPolicy(cn).classPolicy ?: super.getPolicyForClass(className)
    }

    override fun getPolicyForField(className: String, fieldName: String): FilterPolicyWithReason {
        val cn = classes.getClass(className)
        return getAnnotationPolicy(cn).fieldPolicies[fieldName]
            ?: super.getPolicyForField(className, fieldName)
    }

    override fun getPolicyForMethod(
        className: String,
        methodName: String,
        descriptor: String
    ): FilterPolicyWithReason {
        val cn = classes.getClass(className)
        return getAnnotationPolicy(cn).methodPolicies[MethodKey(methodName, descriptor)]
            ?: super.getPolicyForMethod(className, methodName, descriptor)
    }

    override fun getRenameTo(
        className: String,
        methodName: String,
        descriptor: String
    ): String? {
        val cn = classes.getClass(className)
        return getAnnotationPolicy(cn).renamedMethods[MethodKey(methodName, descriptor)]
            ?: super.getRenameTo(className, methodName, descriptor)
    }

    override fun getRedirectionClass(className: String): String? {
        val cn = classes.getClass(className)
        return getAnnotationPolicy(cn).redirectionClass
    }

    override fun getClassLoadHooks(className: String): List<String> {
        val cn = classes.getClass(className)
        return addLists(super.getClassLoadHooks(className), getAnnotationPolicy(cn).classLoadHooks)
    }

    private data class MethodKey(val name: String, val desc: String)

    /**
     * Every time we see a class, we scan all its methods for substitution attributes,
     * and compute (implicit) policies caused by them.
     *
     * For example, for the following methods:
     *
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
     *   foo() -> Substitute
     *   foo_host() -> Stub, and then rename it to foo().
     */
    private inner class ClassAnnotations(cn: ClassNode) {

        val classPolicy: FilterPolicyWithReason?
        val fieldPolicies = mutableMapOf<String, FilterPolicyWithReason>()
        val methodPolicies = mutableMapOf<MethodKey, FilterPolicyWithReason>()
        val renamedMethods = mutableMapOf<MethodKey, String>()
        val redirectionClass: String?
        val classLoadHooks: List<String>

        init {
            val allowAnnotation = annotationAllowedClassesFilter.matches(cn.name)
            detectInvalidAnnotations(
                cn.name, allowAnnotation,
                cn.visibleAnnotations, cn.invisibleAnnotations,
                "class", cn.name
            )
            classPolicy = cn.findAnyAnnotation(visibilityAnnotations)?.policy
            redirectionClass = cn.findAnyAnnotation(redirectionClassAnnotations)?.let { an ->
                getAnnotationField(an, "value")?.let { resolveRelativeClass(cn, it) }
            }
            classLoadHooks = cn.findAllAnnotations(classLoadHookAnnotations).mapNotNull { an ->
                getAnnotationField(an, "value")?.toHumanReadableMethodName()
            }
            if (cn.findAnyAnnotation(keepStaticInitializerAnnotations) != null) {
                methodPolicies[MethodKey(CLASS_INITIALIZER_NAME, CLASS_INITIALIZER_DESC)] =
                    FilterPolicy.Keep.withReason(REASON_ANNOTATION)
            }

            for (fn in cn.fields ?: emptyList()) {
                detectInvalidAnnotations(
                    cn.name, allowAnnotation,
                    fn.visibleAnnotations, fn.invisibleAnnotations,
                    "field", cn.name, fn.name
                )
                fn.findAnyAnnotation(visibilityAnnotations)?.policy?.let {
                    fieldPolicies[fn.name] = it
                }
            }

            for (mn in cn.methods ?: emptyList()) {
                detectInvalidAnnotations(
                    cn.name, allowAnnotation,
                    mn.visibleAnnotations, mn.invisibleAnnotations,
                    "method", cn.name, mn.name, mn.desc
                )

                val an = mn.findAnyAnnotation(visibilityAnnotations) ?: continue
                val policy = an.policy ?: continue
                methodPolicies[MethodKey(mn.name, mn.desc)] = policy

                if (policy.policy != FilterPolicy.Substitute) continue

                // Handle substitution
                val suffix = getAnnotationField(an, "suffix", false) ?: "\$ravenwood"
                val replacement = mn.name + suffix

                if (replacement == mn.name) {
                    errors.onErrorFound("@SubstituteWith require a different name")
                } else {
                    // The replacement method has to be renamed
                    methodPolicies[MethodKey(replacement, mn.desc)] =
                        FilterPolicy.Keep.withReason(REASON_ANNOTATION)
                    renamedMethods[MethodKey(replacement, mn.desc)] = mn.name

                    log.v("Substitution found: %s%s -> %s", replacement, mn.desc, mn.name)
                }
            }
        }

        /**
         * Throw if an item has more than one visibility annotations, or the class is not allowed
         *
         * name1 - 4 are only used in exception messages. We take them as separate strings
         * to avoid unnecessary string concatenations.
         */
        private fun detectInvalidAnnotations(
            className: String,
            allowAnnotation: Boolean,
            visibles: List<AnnotationNode>?,
            invisibles: List<AnnotationNode>?,
            type: String,
            name1: String,
            name2: String = "",
            name3: String = "",
        ) {
            var count = 0
            var visibleCount = 0
            for (an in visibles ?: emptyList()) {
                if (visibilityAnnotations.contains(an.desc)) {
                    visibleCount++
                }
                if (allAnnotations.contains(an.desc)) {
                    count++
                }
            }
            for (an in invisibles ?: emptyList()) {
                if (visibilityAnnotations.contains(an.desc)) {
                    visibleCount++
                }
                if (allAnnotations.contains(an.desc)) {
                    count++
                }
            }
            if (count > 0 && !allowAnnotation) {
                throw InvalidAnnotationException(
                    "Class ${className.toHumanReadableClassName()} is not allowed to have " +
                            "Ravenwood annotations. Contact g/ravenwood for more details."
                )
            }
            if (visibleCount > 1) {
                val description = if (name2 == "" && name3 == "") {
                    "$type $name1"
                } else {
                    "$type $name1.$name2$name3"
                }
                throw InvalidAnnotationException(
                    "Found more than one visibility annotations on $description"
                )
            }
        }

        /**
         * Return the (String) value of 'value' parameter from an annotation.
         */
        private fun getAnnotationField(
            an: AnnotationNode,
            name: String,
            required: Boolean = true
        ): String? {
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

        /**
         * Resolve the full class name if the class is relative
         */
        private fun resolveRelativeClass(
            cn: ClassNode,
            name: String
        ): String {
            val packageName = getPackageNameFromFullClassName(cn.name)
            return resolveClassNameWithDefaultPackage(name, packageName).toJvmClassName()
        }
    }

    companion object {
        private const val REASON_ANNOTATION = "annotation"
        private const val REASON_CLASS_ANNOTATION = "class-annotation"

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
