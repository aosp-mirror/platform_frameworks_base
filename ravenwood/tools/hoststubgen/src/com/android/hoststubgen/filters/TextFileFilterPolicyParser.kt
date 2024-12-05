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
import java.io.File
import java.io.PrintWriter
import java.util.regex.Pattern
import org.objectweb.asm.tree.ClassNode

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

private enum class SpecialClass {
    NotSpecial,
    Aidl,
    FeatureFlags,
    Sysprops,
    RFile,
}

class TextFileFilterPolicyParser(
    private val classes: ClassNodes,
    fallback: OutputFilter
) {
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

    private lateinit var currentClassName: String

    /**
     * Read a given "policy" file and return as an [OutputFilter]
     */
    fun parse(file: String) {
        log.i("Loading offloaded annotations from $file ...")
        log.withIndent {
            var lineNo = 0
            try {
                File(file).forEachLine {
                    lineNo++
                    val line = normalizeTextLine(it)
                    if (line.isEmpty()) {
                        return@forEachLine // skip empty lines.
                    }
                    parseLine(line)
                }
            } catch (e: ParseException) {
                throw e.withSourceInfo(file, lineNo)
            }
        }
    }

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

    private fun parseLine(line: String) {
        val fields = line.split(whitespaceRegex).toTypedArray()
        when (fields[0].lowercase()) {
            "p", "package" -> parsePackage(fields)
            "c", "class" -> parseClass(fields)
            "f", "field" -> parseField(fields)
            "m", "method" -> parseMethod(fields)
            "r", "rename" -> parseRename(fields)
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
            "k", "keep" -> FilterPolicy.Keep
            "t", "throw" -> FilterPolicy.Throw
            "r", "remove" -> FilterPolicy.Remove
            "kc", "keepclass" -> FilterPolicy.KeepClass
            "i", "ignore" -> FilterPolicy.Ignore
            "rdr", "redirect" -> FilterPolicy.Redirect
            else -> {
                if (s.startsWith("@")) {
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
        packageFilter.addPolicy(name, policy.withReason(FILTER_REASON))
    }

    private fun parseClass(fields: Array<String>) {
        if (fields.size < 3) {
            throw ParseException("Class ('c') expects 2 fields.")
        }
        currentClassName = fields[1]

        // superClass is set when the class name starts with a "*".
        val superClass = resolveExtendingClass(currentClassName)

        // :aidl, etc?
        val classType = resolveSpecialClass(currentClassName)

        if (fields[2].startsWith("!")) {
            if (classType != SpecialClass.NotSpecial) {
                // We could support it, but not needed at least for now.
                throw ParseException(
                    "Special class can't have a substitution"
                )
            }
            // It's a redirection class.
            val toClass = fields[2].substring(1)
            imf.setRedirectionClass(currentClassName, toClass)
        } else if (fields[2].startsWith("~")) {
            if (classType != SpecialClass.NotSpecial) {
                // We could support it, but not needed at least for now.
                throw ParseException(
                    "Special class can't have a class load hook"
                )
            }
            // It's a class-load hook
            val callback = fields[2].substring(1)
            imf.setClassLoadHook(currentClassName, callback)
        } else {
            val policy = parsePolicy(fields[2])
            if (!policy.isUsableWithClasses) {
                throw ParseException("Class can't have policy '$policy'")
            }

            when (classType) {
                SpecialClass.NotSpecial -> {
                    // TODO: Duplicate check, etc
                    if (superClass == null) {
                        imf.setPolicyForClass(
                            currentClassName, policy.withReason(FILTER_REASON)
                        )
                    } else {
                        subclassFilter.addPolicy(
                            superClass,
                            policy.withReason("extends $superClass")
                        )
                    }
                }

                SpecialClass.Aidl -> {
                    if (aidlPolicy != null) {
                        throw ParseException(
                            "Policy for AIDL classes already defined"
                        )
                    }
                    aidlPolicy = policy.withReason(
                        "$FILTER_REASON (special-class AIDL)"
                    )
                }

                SpecialClass.FeatureFlags -> {
                    if (featureFlagsPolicy != null) {
                        throw ParseException(
                            "Policy for feature flags already defined"
                        )
                    }
                    featureFlagsPolicy = policy.withReason(
                        "$FILTER_REASON (special-class feature flags)"
                    )
                }

                SpecialClass.Sysprops -> {
                    if (syspropsPolicy != null) {
                        throw ParseException(
                            "Policy for sysprops already defined"
                        )
                    }
                    syspropsPolicy = policy.withReason(
                        "$FILTER_REASON (special-class sysprops)"
                    )
                }

                SpecialClass.RFile -> {
                    if (rFilePolicy != null) {
                        throw ParseException(
                            "Policy for R file already defined"
                        )
                    }
                    rFilePolicy = policy.withReason(
                        "$FILTER_REASON (special-class R file)"
                    )
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
        require(this::currentClassName.isInitialized)

        // TODO: Duplicate check, etc
        imf.setPolicyForField(currentClassName, name, policy.withReason(FILTER_REASON))
    }

    private fun parseMethod(fields: Array<String>) {
        if (fields.size < 3 || fields.size > 4) {
            throw ParseException("Method ('m') expects 3 or 4 fields.")
        }
        val name = fields[1]
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

        require(this::currentClassName.isInitialized)

        imf.setPolicyForMethod(
            currentClassName, name, signature,
            policy.withReason(FILTER_REASON)
        )
        if (policy == FilterPolicy.Substitute) {
            val fromName = policyStr.substring(1)

            if (fromName == name) {
                throw ParseException(
                    "Substitution must have a different name"
                )
            }

            // Set the policy for the "from" method.
            imf.setPolicyForMethod(
                currentClassName, fromName, signature,
                FilterPolicy.Keep.withReason(FILTER_REASON)
            )

            val classAndMethod = splitWithLastPeriod(fromName)
            if (classAndMethod != null) {
                // If the substitution target contains a ".", then
                // it's a method call redirect.
                methodReplaceSpec.add(
                    TextFilePolicyMethodReplaceFilter.MethodCallReplaceSpec(
                        currentClassName.toJvmClassName(),
                        name,
                        signature,
                        classAndMethod.first.toJvmClassName(),
                        classAndMethod.second,
                    )
                )
            } else {
                // It's an in-class replace.
                // ("@RavenwoodReplace" equivalent)
                imf.setRenameTo(currentClassName, fromName, signature, name)
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

        typeRenameSpec += TextFilePolicyRemapperFilter.TypeRenameSpec(
            pattern, prefix
        )
    }
}
