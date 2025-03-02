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

import com.android.hoststubgen.ParseException
import com.android.hoststubgen.asm.ClassNodes
import com.android.hoststubgen.asm.splitWithLastPeriod
import com.android.hoststubgen.asm.toHumanReadableClassName
import com.android.hoststubgen.asm.toJvmClassName
import com.android.hoststubgen.log
import com.android.hoststubgen.normalizeTextLine
import com.android.hoststubgen.whitespaceRegex
import org.objectweb.asm.tree.ClassNode
import java.io.BufferedReader
import java.io.FileReader
import java.io.PrintWriter
import java.io.Reader
import java.util.regex.Pattern

/**
 * Print a class node as a "keep" policy.
 */
fun printAsTextPolicy(pw: PrintWriter, cn: ClassNode) {
    pw.printf("class %s %s\n", cn.name.toHumanReadableClassName(), "keep")

    cn.fields?.let {
        for (f in it.sortedWith(compareBy({ it.name }))) {
            pw.printf("    field %s %s\n", f.name, "keep")
        }
    }
    cn.methods?.let {
        for (m in it.sortedWith(compareBy({ it.name }, { it.desc }))) {
            pw.printf("    method %s %s %s\n", m.name, m.desc, "keep")
        }
    }
}

private const val FILTER_REASON = "file-override"

enum class SpecialClass {
    NotSpecial,
    Aidl,
    FeatureFlags,
    Sysprops,
    RFile,
}

/**
 * This receives [TextFileFilterPolicyBuilder] parsing result.
 */
interface PolicyFileProcessor {
    /** "package" directive. */
    fun onPackage(name: String, policy: FilterPolicyWithReason)

    /** "rename" directive. */
    fun onRename(pattern: Pattern, prefix: String)

    /** "class" directive. */
    fun onClassStart(className: String)
    fun onSimpleClassPolicy(className: String, policy: FilterPolicyWithReason)
    fun onClassEnd(className: String)

    fun onSubClassPolicy(superClassName: String, policy: FilterPolicyWithReason)
    fun onRedirectionClass(fromClassName: String, toClassName: String)
    fun onClassLoadHook(className: String, callback: String)
    fun onSpecialClassPolicy(type: SpecialClass, policy: FilterPolicyWithReason)

    /** "field" directive. */
    fun onField(className: String, fieldName: String, policy: FilterPolicyWithReason)

    /** "method" directive. */
    fun onSimpleMethodPolicy(
        className: String,
        methodName: String,
        methodDesc: String,
        policy: FilterPolicyWithReason,
    )
    fun onMethodInClassReplace(
        className: String,
        methodName: String,
        methodDesc: String,
        targetName: String,
        policy: FilterPolicyWithReason,
    )
    fun onMethodOutClassReplace(
        className: String,
        methodName: String,
        methodDesc: String,
        replaceSpec: TextFilePolicyMethodReplaceFilter.MethodCallReplaceSpec,
    )
}

class TextFileFilterPolicyBuilder(
    private val classes: ClassNodes,
    fallback: OutputFilter
) {
    private val parser = TextFileFilterPolicyParser()

    private val subclassFilter = SubclassFilter(classes, fallback)
    private val packageFilter = PackageFilter(subclassFilter)
    private val imf = InMemoryOutputFilter(classes, packageFilter)
    private var aidlPolicy: FilterPolicyWithReason? = null
    private var featureFlagsPolicy: FilterPolicyWithReason? = null
    private var syspropsPolicy: FilterPolicyWithReason? = null
    private var rFilePolicy: FilterPolicyWithReason? = null
    private val typeRenameSpec = mutableListOf<TextFilePolicyRemapperFilter.TypeRenameSpec>()
    private val methodReplaceSpec =
        mutableListOf<TextFilePolicyMethodReplaceFilter.MethodCallReplaceSpec>()

    /**
     * Fields for a filter chain used for "partial allowlisting", which are used by
     * [AnnotationBasedFilter].
     */
    private val annotationAllowedInMemoryFilter: InMemoryOutputFilter
    val annotationAllowedMembersFilter: OutputFilter

    private val annotationAllowedPolicy = FilterPolicy.AnnotationAllowed.withReason(FILTER_REASON)

    init {
        // Create a filter that checks "partial allowlisting".
        var aaf: OutputFilter = ConstantFilter(FilterPolicy.Remove, "default disallowed")

        aaf = InMemoryOutputFilter(classes, aaf)
        annotationAllowedInMemoryFilter = aaf

        annotationAllowedMembersFilter = annotationAllowedInMemoryFilter
    }

    /**
     * Parse a given policy file. This method can be called multiple times to read from
     * multiple files. To get the resulting filter, use [createOutputFilter]
     */
    fun parse(file: String) {
        // We may parse multiple files, but we reuse the same parser, because the parser
        // will make sure there'll be no dupplicating "special class" policies.
        parser.parse(FileReader(file), file, Processor())
    }

    /**
     * Generate the resulting [OutputFilter].
     */
    fun createOutputFilter(): OutputFilter {
        var ret: OutputFilter = imf
        if (typeRenameSpec.isNotEmpty()) {
            ret = TextFilePolicyRemapperFilter(typeRenameSpec, ret)
        }
        if (methodReplaceSpec.isNotEmpty()) {
            ret = TextFilePolicyMethodReplaceFilter(methodReplaceSpec, classes, ret)
        }

        // Wrap the in-memory-filter with AHF.
        ret = AndroidHeuristicsFilter(
            classes, aidlPolicy, featureFlagsPolicy, syspropsPolicy, rFilePolicy, ret
        )

        return ret
    }

    private inner class Processor : PolicyFileProcessor {
        override fun onPackage(name: String, policy: FilterPolicyWithReason) {
            if (policy.policy == FilterPolicy.AnnotationAllowed) {
                throw ParseException("${FilterPolicy.AnnotationAllowed.policyStringOrPrefix}" +
                        " on `package` isn't supported yet.")
                return
            }
            packageFilter.addPolicy(name, policy)
        }

        override fun onRename(pattern: Pattern, prefix: String) {
            typeRenameSpec += TextFilePolicyRemapperFilter.TypeRenameSpec(
                pattern, prefix
            )
        }

        override fun onClassStart(className: String) {
        }

        override fun onClassEnd(className: String) {
        }

        override fun onSimpleClassPolicy(className: String, policy: FilterPolicyWithReason) {
            if (policy.policy == FilterPolicy.AnnotationAllowed) {
                annotationAllowedInMemoryFilter.setPolicyForClass(
                    className, annotationAllowedPolicy)
                return
            }
            imf.setPolicyForClass(className, policy)
        }

        override fun onSubClassPolicy(
            superClassName: String,
            policy: FilterPolicyWithReason,
            ) {
            log.i("class extends $superClassName")
            subclassFilter.addPolicy( superClassName, policy)
        }

        override fun onRedirectionClass(fromClassName: String, toClassName: String) {
            imf.setRedirectionClass(fromClassName, toClassName)
        }

        override fun onClassLoadHook(className: String, callback: String) {
            imf.setClassLoadHook(className, callback)
        }

        override fun onSpecialClassPolicy(
            type: SpecialClass,
            policy: FilterPolicyWithReason,
        ) {
            log.i("class special $type $policy")
            when (type) {
                SpecialClass.NotSpecial -> {} // Shouldn't happen

                SpecialClass.Aidl -> {
                    aidlPolicy = policy
                }

                SpecialClass.FeatureFlags -> {
                    featureFlagsPolicy = policy
                }

                SpecialClass.Sysprops -> {
                    syspropsPolicy = policy
                }

                SpecialClass.RFile -> {
                    rFilePolicy = policy
                }
            }
        }

        override fun onField(className: String, fieldName: String, policy: FilterPolicyWithReason) {
            imf.setPolicyForField(className, fieldName, policy)
        }

        override fun onSimpleMethodPolicy(
            className: String,
            methodName: String,
            methodDesc: String,
            policy: FilterPolicyWithReason,
        ) {
            if (policy.policy == FilterPolicy.AnnotationAllowed) {
                annotationAllowedInMemoryFilter.setPolicyForMethod(
                    className, methodName, methodDesc, annotationAllowedPolicy)
                return
            }
            imf.setPolicyForMethod(className, methodName, methodDesc, policy)
        }

        override fun onMethodInClassReplace(
            className: String,
            methodName: String,
            methodDesc: String,
            targetName: String,
            policy: FilterPolicyWithReason,
        ) {
            imf.setPolicyForMethod(className, methodName, methodDesc, policy)

            // Make sure to keep the target method.
            imf.setPolicyForMethod(
                className,
                targetName,
                methodDesc,
                FilterPolicy.Keep.withReason(FILTER_REASON)
            )
            // Set up the rename.
            imf.setRenameTo(className, targetName, methodDesc, methodName)
        }

        override fun onMethodOutClassReplace(
            className: String,
            methodName: String,
            methodDesc: String,
            replaceSpec: TextFilePolicyMethodReplaceFilter.MethodCallReplaceSpec,
        ) {
            // Keep the source method, because the target method may call it.
            imf.setPolicyForMethod(className, methodName, methodDesc,
                FilterPolicy.Keep.withReason(FILTER_REASON))
            methodReplaceSpec.add(replaceSpec)
        }
    }
}

/**
 * Parses a filer policy text file.
 */
class TextFileFilterPolicyParser {
    private lateinit var processor: PolicyFileProcessor
    private var currentClassName: String? = null

    private var aidlPolicy: FilterPolicyWithReason? = null
    private var featureFlagsPolicy: FilterPolicyWithReason? = null
    private var syspropsPolicy: FilterPolicyWithReason? = null
    private var rFilePolicy: FilterPolicyWithReason? = null

    /** Name of the file that's currently being processed.  */
    var filename: String = ""
        private set

    /** 1-based line number in the current file */
    var lineNumber = -1
        private set

    /** Current line */
    var currentLineText = ""
        private set

    /**
     * Parse a given "policy" file.
     */
    fun parse(reader: Reader, inputName: String, processor: PolicyFileProcessor) {
        filename = inputName

        this.processor = processor
        BufferedReader(reader).use { rd ->
            lineNumber = 0
            try {
                while (true) {
                    var line = rd.readLine()
                    if (line == null) {
                        break
                    }
                    lineNumber++
                    currentLineText = line
                    line = normalizeTextLine(line) // Remove comment and trim.
                    if (line.isEmpty()) {
                        continue
                    }
                    parseLine(line)
                }
                finishLastClass()
            } catch (e: ParseException) {
                throw e.withSourceInfo(inputName, lineNumber)
            }
        }
    }

    private fun finishLastClass() {
        currentClassName?.let { className ->
            processor.onClassEnd(className)
            currentClassName = null
        }
    }

    private fun ensureInClass(directive: String): String {
        return currentClassName ?:
            throw ParseException("Directive '$directive' must follow a 'class' directive")
    }

    private fun parseLine(line: String) {
        val fields = line.split(whitespaceRegex).toTypedArray()
        when (fields[0].lowercase()) {
            "p", "package" -> {
                finishLastClass()
                parsePackage(fields)
            }
            "c", "class" -> {
                finishLastClass()
                parseClass(fields)
            }
            "f", "field" -> {
                ensureInClass("field")
                parseField(fields)
            }
            "m", "method" -> {
                ensureInClass("method")
                parseMethod(fields)
            }
            "r", "rename" -> {
                finishLastClass()
                parseRename(fields)
            }
            else -> throw ParseException("Unknown directive \"${fields[0]}\"")
        }
    }

    private fun resolveSpecialClass(className: String): SpecialClass {
        if (!className.startsWith(":")) {
            return SpecialClass.NotSpecial
        }
        when (className.lowercase()) {
            ":aidl" -> return SpecialClass.Aidl
            ":feature_flags" -> return SpecialClass.FeatureFlags
            ":sysprops" -> return SpecialClass.Sysprops
            ":r" -> return SpecialClass.RFile
        }
        throw ParseException("Invalid special class name \"$className\"")
    }

    private fun resolveExtendingClass(className: String): String? {
        if (!className.startsWith("*")) {
            return null
        }
        return className.substring(1)
    }

    private fun parsePolicy(s: String): FilterPolicy {
        return when (s.lowercase()) {
            "k", FilterPolicy.Keep.policyStringOrPrefix -> FilterPolicy.Keep
            "t", FilterPolicy.Throw.policyStringOrPrefix -> FilterPolicy.Throw
            "r", FilterPolicy.Remove.policyStringOrPrefix -> FilterPolicy.Remove
            "kc", FilterPolicy.KeepClass.policyStringOrPrefix -> FilterPolicy.KeepClass
            "i", FilterPolicy.Ignore.policyStringOrPrefix -> FilterPolicy.Ignore
            "rdr", FilterPolicy.Redirect.policyStringOrPrefix -> FilterPolicy.Redirect
            FilterPolicy.AnnotationAllowed.policyStringOrPrefix -> FilterPolicy.AnnotationAllowed
            else -> {
                if (s.startsWith(FilterPolicy.Substitute.policyStringOrPrefix)) {
                    FilterPolicy.Substitute
                } else {
                    throw ParseException("Invalid policy \"$s\"")
                }
            }
        }
    }

    private fun parsePackage(fields: Array<String>) {
        if (fields.size < 3) {
            throw ParseException("Package ('p') expects 2 fields.")
        }
        val name = fields[1]
        val rawPolicy = fields[2]
        if (resolveExtendingClass(name) != null) {
            throw ParseException("Package can't be a super class type")
        }
        if (resolveSpecialClass(name) != SpecialClass.NotSpecial) {
            throw ParseException("Package can't be a special class type")
        }
        if (rawPolicy.startsWith("!")) {
            throw ParseException("Package can't have a substitution")
        }
        if (rawPolicy.startsWith("~")) {
            throw ParseException("Package can't have a class load hook")
        }
        val policy = parsePolicy(rawPolicy)
        if (!policy.isUsableWithClasses) {
            throw ParseException("Package can't have policy '$policy'")
        }
        processor.onPackage(name, policy.withReason(FILTER_REASON))
    }

    private fun parseClass(fields: Array<String>) {
        if (fields.size <= 1) {
            throw ParseException("Class ('c') expects 1 or 2 fields.")
        }
        val className = fields[1].toHumanReadableClassName()

        // superClass is set when the class name starts with a "*".
        val superClass = resolveExtendingClass(className)

        // :aidl, etc?
        val classType = resolveSpecialClass(className)

        val policyStr = if (fields.size > 2) { fields[2] } else { "" }

        if (policyStr.startsWith("!")) {
            if (classType != SpecialClass.NotSpecial) {
                // We could support it, but not needed at least for now.
                throw ParseException(
                    "Special class can't have a substitution"
                )
            }
            // It's a redirection class.
            val toClass = policyStr.substring(1)

            currentClassName = className
            processor.onClassStart(className)
            processor.onRedirectionClass(className, toClass)
        } else if (policyStr.startsWith("~")) {
            if (classType != SpecialClass.NotSpecial) {
                // We could support it, but not needed at least for now.
                throw ParseException(
                    "Special class can't have a class load hook"
                )
            }
            // It's a class-load hook
            val callback = policyStr.substring(1)

            currentClassName = className
            processor.onClassStart(className)
            processor.onClassLoadHook(className, callback)
        } else {
            // Special case: if it's a class directive with no policy, then it encloses
            // members, but we don't apply any policy to the class itself.
            // This is only allowed in a not-special case.
            if (policyStr == "") {
                if (classType == SpecialClass.NotSpecial && superClass == null) {
                    currentClassName = className
                    return
                }
                throw ParseException("Special class or subclass directive must have a policy")
            }

            val policy = parsePolicy(policyStr)
            if (!policy.isUsableWithClasses) {
                throw ParseException("Class can't have policy '$policy'")
            }

            when (classType) {
                SpecialClass.NotSpecial -> {
                    // TODO: Duplicate check, etc
                    if (superClass == null) {
                        currentClassName = className
                        processor.onClassStart(className)
                        processor.onSimpleClassPolicy(className, policy.withReason(FILTER_REASON))
                    } else {
                        processor.onSubClassPolicy(
                            superClass,
                            policy.withReason("extends $superClass"),
                        )
                    }
                }
                SpecialClass.Aidl -> {
                    if (aidlPolicy != null) {
                        throw ParseException(
                            "Policy for AIDL classes already defined"
                        )
                    }
                    val p = policy.withReason(
                        "$FILTER_REASON (special-class AIDL)"
                    )
                    processor.onSpecialClassPolicy(classType, p)
                    aidlPolicy = p
                }

                SpecialClass.FeatureFlags -> {
                    if (featureFlagsPolicy != null) {
                        throw ParseException(
                            "Policy for feature flags already defined"
                        )
                    }
                    val p = policy.withReason(
                        "$FILTER_REASON (special-class feature flags)"
                    )
                    processor.onSpecialClassPolicy(classType, p)
                    featureFlagsPolicy = p
                }

                SpecialClass.Sysprops -> {
                    if (syspropsPolicy != null) {
                        throw ParseException(
                            "Policy for sysprops already defined"
                        )
                    }
                    val p = policy.withReason(
                        "$FILTER_REASON (special-class sysprops)"
                    )
                    processor.onSpecialClassPolicy(classType, p)
                    syspropsPolicy = p
                }

                SpecialClass.RFile -> {
                    if (rFilePolicy != null) {
                        throw ParseException(
                            "Policy for R file already defined"
                        )
                    }
                    val p = policy.withReason(
                        "$FILTER_REASON (special-class R file)"
                    )
                    processor.onSpecialClassPolicy(classType, p)
                    rFilePolicy = p
                }
            }
        }
    }

    private fun parseField(fields: Array<String>) {
        if (fields.size < 3) {
            throw ParseException("Field ('f') expects 2 fields.")
        }
        val name = fields[1]
        val policy = parsePolicy(fields[2])
        if (!policy.isUsableWithFields) {
            throw ParseException("Field can't have policy '$policy'")
        }

        // TODO: Duplicate check, etc
        processor.onField(currentClassName!!, name, policy.withReason(FILTER_REASON))
    }

    private fun parseMethod(fields: Array<String>) {
        if (fields.size < 3 || fields.size > 4) {
            throw ParseException("Method ('m') expects 3 or 4 fields.")
        }
        val methodName = fields[1]
        val signature: String
        val policyStr: String
        if (fields.size <= 3) {
            signature = "*"
            policyStr = fields[2]
        } else {
            signature = fields[2]
            policyStr = fields[3]
        }

        val policy = parsePolicy(policyStr)

        if (!policy.isUsableWithMethods) {
            throw ParseException("Method can't have policy '$policy'")
        }

        val className = currentClassName!!

        val policyWithReason = policy.withReason(FILTER_REASON)
        if (policy != FilterPolicy.Substitute) {
            processor.onSimpleMethodPolicy(className, methodName, signature, policyWithReason)
        } else {
            val targetName = policyStr.substring(1)

            if (targetName == methodName) {
                throw ParseException(
                    "Substitution must have a different name"
                )
            }

            val classAndMethod = splitWithLastPeriod(targetName)
            if (classAndMethod != null) {
                // If the substitution target contains a ".", then
                // it's a method call redirect.
                val spec = TextFilePolicyMethodReplaceFilter.MethodCallReplaceSpec(
                        currentClassName!!.toJvmClassName(),
                        methodName,
                        signature,
                        classAndMethod.first.toJvmClassName(),
                        classAndMethod.second,
                    )
                processor.onMethodOutClassReplace(
                    className,
                    methodName,
                    signature,
                    spec,
                )
            } else {
                // It's an in-class replace.
                // ("@RavenwoodReplace" equivalent)
                processor.onMethodInClassReplace(
                    className,
                    methodName,
                    signature,
                    targetName,
                    policyWithReason,
                )
            }
        }
    }

    private fun parseRename(fields: Array<String>) {
        if (fields.size < 3) {
            throw ParseException("Rename ('r') expects 2 fields.")
        }
        // Add ".*" to make it a prefix match.
        val pattern = Pattern.compile(fields[1] + ".*")

        // Removing the leading /'s from the prefix. This allows
        // using a single '/' as an empty suffix, which is useful to have a
        // "negative" rename rule to avoid subsequent raname's from getting
        // applied. (Which is needed for services.jar)
        val prefix = fields[2].trimStart('/')

        processor.onRename(
            pattern, prefix
        )
    }
}
