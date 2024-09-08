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
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import java.io.BufferedReader
import java.io.FileReader
import java.io.PrintWriter
import java.util.Objects
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

/** Return true if [access] is either public or protected. */
private fun isVisible(access: Int): Boolean {
    return (access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED)) != 0
}

private const val FILTER_REASON = "file-override"

/**
 * Read a given "policy" file and return as an [OutputFilter]
 */
fun createFilterFromTextPolicyFile(
        filename: String,
        classes: ClassNodes,
        fallback: OutputFilter,
        ): OutputFilter {
    log.i("Loading offloaded annotations from $filename ...")
    log.withIndent {
        val subclassFilter = SubclassFilter(classes, fallback)
        val packageFilter = PackageFilter(subclassFilter)
        val imf = InMemoryOutputFilter(classes, packageFilter)

        var lineNo = 0

        var aidlPolicy: FilterPolicyWithReason? = null
        var featureFlagsPolicy: FilterPolicyWithReason? = null
        var syspropsPolicy: FilterPolicyWithReason? = null
        var rFilePolicy: FilterPolicyWithReason? = null
        val typeRenameSpec = mutableListOf<TextFilePolicyRemapperFilter.TypeRenameSpec>()
        val methodReplaceSpec =
            mutableListOf<TextFilePolicyMethodReplaceFilter.MethodCallReplaceSpec>()

        try {
            BufferedReader(FileReader(filename)).use { reader ->
                var className = ""

                while (true) {
                    var line = reader.readLine() ?: break
                    lineNo++

                    line = normalizeTextLine(line)

                    if (line.isEmpty()) {
                        continue // skip empty lines.
                    }


                    // TODO: Method too long, break it up.

                    val fields = line.split(whitespaceRegex).toTypedArray()
                    when (fields[0].lowercase()) {
                        "p", "package" -> {
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

                        "c", "class" -> {
                            if (fields.size < 3) {
                                throw ParseException("Class ('c') expects 2 fields.")
                            }
                            className = fields[1]

                            // superClass is set when the class name starts with a "*".
                            val superClass = resolveExtendingClass(className)

                            // :aidl, etc?
                            val classType = resolveSpecialClass(className)

                            if (fields[2].startsWith("!")) {
                                if (classType != SpecialClass.NotSpecial) {
                                    // We could support it, but not needed at least for now.
                                    throw ParseException(
                                            "Special class can't have a substitution")
                                }
                                // It's a redirection class.
                                val toClass = fields[2].substring(1)
                                imf.setRedirectionClass(className, toClass)
                            } else if (fields[2].startsWith("~")) {
                                if (classType != SpecialClass.NotSpecial) {
                                    // We could support it, but not needed at least for now.
                                    throw ParseException(
                                            "Special class can't have a class load hook")
                                }
                                // It's a class-load hook
                                val callback = fields[2].substring(1)
                                imf.setClassLoadHook(className, callback)
                            } else {
                                val policy = parsePolicy(fields[2])
                                if (!policy.isUsableWithClasses) {
                                    throw ParseException("Class can't have policy '$policy'")
                                }
                                Objects.requireNonNull(className)

                                when (classType) {
                                    SpecialClass.NotSpecial -> {
                                        // TODO: Duplicate check, etc
                                        if (superClass == null) {
                                            imf.setPolicyForClass(
                                                className, policy.withReason(FILTER_REASON)
                                            )
                                        } else {
                                            subclassFilter.addPolicy(superClass,
                                                policy.withReason("extends $superClass"))
                                        }
                                    }
                                    SpecialClass.Aidl -> {
                                        if (aidlPolicy != null) {
                                            throw ParseException(
                                                    "Policy for AIDL classes already defined")
                                        }
                                        aidlPolicy = policy.withReason(
                                                "$FILTER_REASON (special-class AIDL)")
                                    }
                                    SpecialClass.FeatureFlags -> {
                                        if (featureFlagsPolicy != null) {
                                            throw ParseException(
                                                    "Policy for feature flags already defined")
                                        }
                                        featureFlagsPolicy = policy.withReason(
                                                "$FILTER_REASON (special-class feature flags)")
                                    }
                                    SpecialClass.Sysprops -> {
                                        if (syspropsPolicy != null) {
                                            throw ParseException(
                                                    "Policy for sysprops already defined")
                                        }
                                        syspropsPolicy = policy.withReason(
                                                "$FILTER_REASON (special-class sysprops)")
                                    }
                                    SpecialClass.RFile -> {
                                        if (rFilePolicy != null) {
                                            throw ParseException(
                                                "Policy for R file already defined")
                                        }
                                        rFilePolicy = policy.withReason(
                                            "$FILTER_REASON (special-class R file)")
                                    }
                                }
                            }
                        }

                        "f", "field" -> {
                            if (fields.size < 3) {
                                throw ParseException("Field ('f') expects 2 fields.")
                            }
                            val name = fields[1]
                            val policy = parsePolicy(fields[2])
                            if (!policy.isUsableWithFields) {
                                throw ParseException("Field can't have policy '$policy'")
                            }
                            Objects.requireNonNull(className)

                            // TODO: Duplicate check, etc
                            imf.setPolicyForField(className, name, policy.withReason(FILTER_REASON))
                        }

                        "m", "method" -> {
                            if (fields.size < 4) {
                                throw ParseException("Method ('m') expects 3 fields.")
                            }
                            val name = fields[1]
                            val signature = fields[2]
                            val policy = parsePolicy(fields[3])

                            if (!policy.isUsableWithMethods) {
                                throw ParseException("Method can't have policy '$policy'")
                            }

                            Objects.requireNonNull(className)

                            imf.setPolicyForMethod(className, name, signature,
                                    policy.withReason(FILTER_REASON))
                            if (policy == FilterPolicy.Substitute) {
                                val fromName = fields[3].substring(1)

                                if (fromName == name) {
                                    throw ParseException(
                                            "Substitution must have a different name")
                                }

                                // Set the policy for the "from" method.
                                imf.setPolicyForMethod(className, fromName, signature,
                                    FilterPolicy.Keep.withReason(FILTER_REASON))

                                val classAndMethod = splitWithLastPeriod(fromName)
                                if (classAndMethod != null) {
                                    // If the substitution target contains a ".", then
                                    // it's a method call redirect.
                                    methodReplaceSpec.add(
                                        TextFilePolicyMethodReplaceFilter.MethodCallReplaceSpec(
                                            className.toJvmClassName(),
                                            name,
                                            signature,
                                            classAndMethod.first.toJvmClassName(),
                                            classAndMethod.second,
                                        )
                                    )
                                } else {
                                    // It's an in-class replace.
                                    // ("@RavenwoodReplace" equivalent)
                                    imf.setRenameTo(className, fromName, signature, name)
                                }
                            }
                        }
                        "r", "rename" -> {
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
                                pattern, prefix)
                        }

                        else -> {
                            throw ParseException("Unknown directive \"${fields[0]}\"")
                        }
                    }
                }
            }
        } catch (e: ParseException) {
            throw e.withSourceInfo(filename, lineNo)
        }

        var ret: OutputFilter = imf
        if (typeRenameSpec.isNotEmpty()) {
            ret = TextFilePolicyRemapperFilter(typeRenameSpec, ret)
        }
        if (methodReplaceSpec.isNotEmpty()) {
            ret = TextFilePolicyMethodReplaceFilter(methodReplaceSpec, classes, ret)
        }

        // Wrap the in-memory-filter with AHF.
        ret = AndroidHeuristicsFilter(
                classes, aidlPolicy, featureFlagsPolicy, syspropsPolicy, rFilePolicy, ret)

        return ret
    }
}

private enum class SpecialClass {
    NotSpecial,
    Aidl,
    FeatureFlags,
    Sysprops,
    RFile,
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
