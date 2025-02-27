/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.platform.test.ravenwood.ravenhelper.policytoannot

import com.android.hoststubgen.LogLevel
import com.android.hoststubgen.asm.CLASS_INITIALIZER_NAME
import com.android.hoststubgen.asm.toJvmClassName
import com.android.hoststubgen.filters.FilterPolicyWithReason
import com.android.hoststubgen.filters.PolicyFileProcessor
import com.android.hoststubgen.filters.SpecialClass
import com.android.hoststubgen.filters.TextFileFilterPolicyParser
import com.android.hoststubgen.filters.TextFilePolicyMethodReplaceFilter
import com.android.hoststubgen.log
import com.android.hoststubgen.utils.ClassPredicate
import com.android.platform.test.ravenwood.ravenhelper.SubcommandHandler
import com.android.platform.test.ravenwood.ravenhelper.psi.createUastEnvironment
import com.android.platform.test.ravenwood.ravenhelper.sourcemap.AllClassInfo
import com.android.platform.test.ravenwood.ravenhelper.sourcemap.ClassInfo
import com.android.platform.test.ravenwood.ravenhelper.sourcemap.MethodInfo
import com.android.platform.test.ravenwood.ravenhelper.sourcemap.SourceLoader
import java.io.FileReader
import java.util.regex.Pattern

/**
 * This is the main routine of the "pta" -- policy-to-annotation -- subcommands.
 */
class PtaProcessor : SubcommandHandler {
    override fun handle(args: List<String>) {
        val options = PtaOptions.parseArgs(args)

        log.v("Options: $options")

        val converter = TextPolicyToAnnotationConverter(
            options.policyOverrideFiles,
            options.sourceFilesOrDirectories,
            options.annotationAllowedClassesFile.get,
            Annotations(),
            options.dumpOperations.get || log.isEnabled(LogLevel.Debug),
        )
        converter.process()

        createShellScript(converter.resultOperations, options.outputScriptFile.get)
    }
}

/**
 * This class implements the actual logic.
 */
private class TextPolicyToAnnotationConverter(
    val policyFiles: List<String>,
    val sourceFilesOrDirectories: List<String>,
    val annotationAllowedClassesFile: String?,
    val annotations: Annotations,
    val dumpOperations: Boolean,
) {
    private val annotationAllowedClasses: ClassPredicate = annotationAllowedClassesFile.let { file ->
        if (file == null) {
            ClassPredicate.newConstantPredicate(true) // Allow all classes
        } else {
            ClassPredicate.loadFromFile(file, false)
        }
    }

    val resultOperations = SourceOperations()
    private val classes = AllClassInfo()
    private val policyParser = TextFileFilterPolicyParser()
    private val annotationNeedingClasses = mutableSetOf<String>()

    /**
     * Entry point.
     */
    fun process() {
        // First, load
        val env = createUastEnvironment()
        try {
            loadSources()

            processPolicies()

            addToAnnotationsAllowedListFile()

            if (dumpOperations) {
                log.withIndent {
                    resultOperations.getOperations().toSortedMap().forEach { (file, ops) ->
                        log.i("ops: $file")
                        ops.forEach { op ->
                            log.i("  line: ${op.lineNumber}: ${op.type}: \"${op.text}\" " +
                                    "(${op.description})")
                        }
                    }
                }
            }
        } finally {
            env.dispose()
        }
    }

    /**
     * Load all the java source files into [classes].
     */
    private fun loadSources() {
        val env = createUastEnvironment()
        try {
            val loader = SourceLoader(env)
            loader.load(sourceFilesOrDirectories, classes)
        } finally {
            env.dispose()
        }
    }

    private fun addToAnnotationsAllowedListFile() {
        log.i("Generating operations to update annotation allowlist file...")
        log.withIndent {
            annotationNeedingClasses.sorted().forEach { className ->
                if (!annotationAllowedClasses.matches(className.toJvmClassName())) {
                    resultOperations.add(
                        SourceOperation(
                            annotationAllowedClassesFile!!,
                            -1, // add to the end
                            SourceOperationType.Insert,
                            className,
                            "add to annotation allowlist"
                        ))
                }
            }
        }
    }

    /**
     * Process the policy files with [Processor].
     */
    private fun processPolicies() {
        log.i("Loading the policy files and generating operations...")
        log.withIndent {
            policyFiles.forEach { policyFile ->
                log.i("Parsing $policyFile ...")
                log.withIndent {
                    policyParser.parse(FileReader(policyFile), policyFile, Processor())
                }
            }
        }
    }

    private inner class Processor : PolicyFileProcessor {

        var classPolicyText = ""
        var classPolicyLine = -1

        // Whether the current class has a skip marker, in which case we ignore all members.
        // Applicable only within a "simple class"
        var classSkipping = false

        var classLineConverted = false
        var classHasMember = false

        private fun currentLineHasSkipMarker(): Boolean {
            val ret = policyParser.currentLineText.contains("no-pta")

            if (ret) {
                log.forVerbose {
                    log.v("Current line has a skip marker: ${policyParser.currentLineText}")
                }
            }

            return ret
        }

        private fun shouldSkipCurrentLine(): Boolean {
            // If a line contains a special marker "no-pta", we'll skip it.
            return classSkipping || currentLineHasSkipMarker()
        }

        /** Print a warning about an unsupported policy directive. */
        private fun warnOnPolicy(message: String, policyLine: String, lineNumber: Int) {
            log.w("Warning: $message")
            log.w("  policy: \"$policyLine\"")
            log.w("  at ${policyParser.filename}:$lineNumber")
        }

        /** Print a warning about an unsupported policy directive. */
        private fun warnOnCurrentPolicy(message: String) {
            warnOnPolicy(message, policyParser.currentLineText, policyParser.lineNumber)
        }

        /** Print a warning about an unsupported policy directive on the class line. */
        private fun warnOnClassPolicy(message: String) {
            warnOnPolicy(message, classPolicyText, classPolicyLine)
        }

        override fun onPackage(name: String, policy: FilterPolicyWithReason) {
            warnOnCurrentPolicy("'package' directive isn't supported (yet).")
        }

        override fun onRename(pattern: Pattern, prefix: String) {
            // Rename will never be supported, so don't show a warning.
        }

        private fun addOperation(op: SourceOperation) {
            resultOperations.add(op)
        }

        private fun commentOutPolicy(lineNumber: Int, description: String) {
            addOperation(
                SourceOperation(
                    policyParser.filename,
                    lineNumber,
                    SourceOperationType.Prepend,
                    "#[PTA]: ", // comment out.
                    description,
                )
            )
        }

        override fun onClassStart(className: String) {
            classSkipping = currentLineHasSkipMarker()
            classLineConverted = false
            classHasMember = false
            classPolicyLine = policyParser.lineNumber
            classPolicyText = policyParser.currentLineText
        }

        override fun onClassEnd(className: String) {
            if (classSkipping) {
                classSkipping = false
                return
            }
            if (!classLineConverted) {
                // Class line is still needed in the policy file.
                // (Because the source file wasn't found.)
                return
            }
            if (!classHasMember) {
                commentOutPolicy(classPolicyLine, "remove class policy on $className")
            } else {
                warnOnClassPolicy(
                    "Class policy on $className can't be removed because it still has members.")
            }
        }

        private fun findClass(className: String): ClassInfo? {
            val ci = classes.findClass(className)
            if (ci == null) {
                warnOnCurrentPolicy("Class not found: $className")
            }
            return ci
        }

        private fun addClassAnnotation(
            className: String,
            annotation: String,
        ): Boolean {
            val ci = findClass(className) ?: return false

            // Add the annotation to the source file.
            addOperation(
                SourceOperation(
                    ci.location.file,
                    ci.location.line,
                    SourceOperationType.Insert,
                    ci.location.getIndent() + annotation,
                    "add class annotation to $className"
                )
            )
            annotationNeedingClasses.add(className)
            return true
        }

        override fun onSimpleClassPolicy(className: String, policy: FilterPolicyWithReason) {
            if (shouldSkipCurrentLine()) {
                return
            }
            log.v("Found simple class policy: $className - ${policy.policy}")

            val annot = annotations.get(policy.policy, Annotations.Target.Class)!!
            if (addClassAnnotation(className, annot)) {
                classLineConverted = true
            }
        }

        override fun onSubClassPolicy(superClassName: String, policy: FilterPolicyWithReason) {
            warnOnCurrentPolicy("Subclass policies isn't supported (yet).")
        }

        override fun onRedirectionClass(fromClassName: String, toClassName: String) {
            if (shouldSkipCurrentLine()) {
                return
            }

            log.v("Found class redirection: $fromClassName - $toClassName")

            if (addClassAnnotation(
                    fromClassName,
                    annotations.getRedirectionClassAnnotation(toClassName),
                )) {
                commentOutPolicy(policyParser.lineNumber,
                    "remove class redirection policy on $fromClassName")
            }
        }

        override fun onClassLoadHook(className: String, callback: String) {
            if (shouldSkipCurrentLine()) {
                return
            }

            log.v("Found class load hook: $className - $callback")

            if (addClassAnnotation(
                    className,
                    annotations.getClassLoadHookAnnotation(callback),
                )) {
                commentOutPolicy(policyParser.lineNumber,
                    "remove class load hook policy on $className")
            }
        }

        override fun onSpecialClassPolicy(type: SpecialClass, policy: FilterPolicyWithReason) {
            // This can't be converted to an annotation, so don't show a warning.
        }

        override fun onField(className: String, fieldName: String, policy: FilterPolicyWithReason) {
            if (shouldSkipCurrentLine()) {
                return
            }

            log.v("Found field policy: $className.$fieldName - ${policy.policy}")

            val ci = findClass(className) ?: return

            ci.findField(fieldName)?.let { fi ->
                val annot = annotations.get(policy.policy, Annotations.Target.Field)!!

                addOperation(
                    SourceOperation(
                        fi.location.file,
                        fi.location.line,
                        SourceOperationType.Insert,
                        fi.location.getIndent() + annot,
                        "add annotation to field $className.$fieldName",
                    )
                )
                commentOutPolicy(policyParser.lineNumber,
                    "remove field policy $className.$fieldName")

                annotationNeedingClasses.add(className)
            } ?: {
                warnOnCurrentPolicy("Field not found: $className.$fieldName")
            }
        }

        override fun onSimpleMethodPolicy(
            className: String,
            methodName: String,
            methodDesc: String,
            policy: FilterPolicyWithReason
        ) {
            if (shouldSkipCurrentLine()) {
                return
            }
            val readableName = "$className.$methodName$methodDesc"
            log.v("Found simple method policy: $readableName - ${policy.policy}")


            // Inner method to get the matching methods for this policy.
            //
            // If this policy can't be converted for any reason, it'll return null.
            // Otherwise, it'll return a pair of method list and the annotation string.
            fun getMethods(): Pair<List<MethodInfo>, String>? {
                if (methodName == CLASS_INITIALIZER_NAME) {
                    warnOnClassPolicy("Policy for class initializers not supported.")
                    return null
                }
                val ci = findClass(className) ?: return null
                val methods = ci.findMethods(methodName, methodDesc)
                if (methods == null) {
                    warnOnCurrentPolicy("Method not found: $readableName")
                    return null
                }

                // If the policy is "ignore", we can't convert it to an annotation, in which case
                // annotations.get() will return null.
                val annot = annotations.get(policy.policy, Annotations.Target.Method)
                if (annot == null) {
                    warnOnCurrentPolicy("Annotation for policy '${policy.policy}' isn't available")
                    return null
                }
                return Pair(methods, annot)
            }

            val methodsAndAnnot = getMethods()

            if (methodsAndAnnot == null) {
                classHasMember = true
                return // This policy can't be converted.
            }
            val methods = methodsAndAnnot.first
            val annot = methodsAndAnnot.second

            var found = false
            methods.forEach { mi ->
                found = true
                addOperation(
                    SourceOperation(
                        mi.location.file,
                        mi.location.line,
                        SourceOperationType.Insert,
                        mi.location.getIndent() + annot,
                        "add annotation to method $readableName",
                    )
                )
            }
            if (found) {
                commentOutPolicy(
                    policyParser.lineNumber,
                    "remove method policy $readableName"
                )

                annotationNeedingClasses.add(className)
            } else {
                warnOnCurrentPolicy("Method not found: $readableName")
            }
        }

        override fun onMethodInClassReplace(
            className: String,
            methodName: String,
            methodDesc: String,
            targetName: String,
            policy: FilterPolicyWithReason
        ) {
            warnOnCurrentPolicy("Found method replace but it's not supported yet: "
                + "$className.$methodName$methodDesc - $targetName")
        }

        override fun onMethodOutClassReplace(
            className: String,
            methodName: String,
            methodDesc: String,
            replaceSpec: TextFilePolicyMethodReplaceFilter.MethodCallReplaceSpec,
        ) {
            // This can't be converted to an annotation.
            classHasMember = true
        }
    }
}
