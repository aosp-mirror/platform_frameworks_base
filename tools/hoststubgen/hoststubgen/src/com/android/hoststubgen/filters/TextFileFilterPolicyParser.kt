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
import com.android.hoststubgen.log
import com.android.hoststubgen.normalizeTextLine
import com.android.hoststubgen.whitespaceRegex
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import java.io.BufferedReader
import java.io.FileReader
import java.io.PrintWriter
import java.util.Objects

/**
 * Print a class node as a "keep" policy.
 */
fun printAsTextPolicy(pw: PrintWriter, cn: ClassNode) {
    pw.printf("class %s\t%s\n", cn.name, "keep")

    for (f in cn.fields ?: emptyList()) {
        pw.printf("  field %s\t%s\n", f.name, "keep")
    }
    for (m in cn.methods ?: emptyList()) {
        pw.printf("  method %s\t%s\t%s\n", m.name, m.desc, "keep")
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
        val imf = InMemoryOutputFilter(classes, fallback)

        var lineNo = 0

        var aidlPolicy: FilterPolicyWithReason? = null
        var featureFlagsPolicy: FilterPolicyWithReason? = null
        var syspropsPolicy: FilterPolicyWithReason? = null

        try {
            BufferedReader(FileReader(filename)).use { reader ->
                var className = ""

                while (true) {
                    var line = reader.readLine()
                    if (line == null) {
                        break
                    }
                    lineNo++

                    line = normalizeTextLine(line)

                    if (line.isEmpty()) {
                        continue // skip empty lines.
                    }


                    // TODO: Method too long, break it up.

                    val fields = line.split(whitespaceRegex).toTypedArray()
                    when (fields[0].lowercase()) {
                        "c", "class" -> {
                            if (fields.size < 3) {
                                throw ParseException("Class ('c') expects 2 fields.")
                            }
                            className = fields[1]

                            val classType = resolveSpecialClass(className)

                            if (fields[2].startsWith("!")) {
                                if (classType != SpecialClass.NotSpecial) {
                                    // We could support it, but not needed at least for now.
                                    throw ParseException(
                                            "Special class can't have a substitution")
                                }
                                // It's a native-substitution.
                                val toClass = fields[2].substring(1)
                                imf.setNativeSubstitutionClass(className, toClass)
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
                                        imf.setPolicyForClass(
                                                className, policy.withReason(FILTER_REASON))
                                    }
                                    SpecialClass.Aidl -> {
                                        if (aidlPolicy != null) {
                                            throw ParseException(
                                                    "Policy for AIDL classes already defined")
                                        }
                                        aidlPolicy = policy.withReason("$FILTER_REASON (AIDL)")
                                    }
                                    SpecialClass.FeatureFlags -> {
                                        if (featureFlagsPolicy != null) {
                                            throw ParseException(
                                                    "Policy for feature flags already defined")
                                        }
                                        featureFlagsPolicy =
                                                policy.withReason("$FILTER_REASON (feature flags)")
                                    }
                                    SpecialClass.Sysprops -> {
                                        if (syspropsPolicy != null) {
                                            throw ParseException(
                                                    "Policy for sysprops already defined")
                                        }
                                        syspropsPolicy =
                                                policy.withReason("$FILTER_REASON (sysprops)")
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
                            if (policy.isSubstitute) {
                                val fromName = fields[3].substring(1)

                                if (fromName == name) {
                                    throw ParseException(
                                            "Substitution must have a different name")
                                }

                                // Set the policy  for the "from" method.
                                imf.setPolicyForMethod(className, fromName, signature,
                                        policy.getSubstitutionBasePolicy()
                                                .withReason(FILTER_REASON))

                                // Keep "from" -> "to" mapping.
                                imf.setRenameTo(className, fromName, signature, name)
                            }
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
        if (aidlPolicy != null || featureFlagsPolicy != null || syspropsPolicy != null) {
            log.d("AndroidHeuristicsFilter enabled")
            ret = AndroidHeuristicsFilter(
                    classes, aidlPolicy, featureFlagsPolicy, syspropsPolicy, imf)
        }
        return ret
    }
}

private enum class SpecialClass {
    NotSpecial,
    Aidl,
    FeatureFlags,
    Sysprops,
}

private fun resolveSpecialClass(className: String): SpecialClass {
    if (!className.startsWith(":")) {
        return SpecialClass.NotSpecial
    }
    when (className.lowercase()) {
        ":aidl" -> return SpecialClass.Aidl
        ":feature_flags" -> return SpecialClass.FeatureFlags
        ":sysprops" -> return SpecialClass.Sysprops
    }
    throw ParseException("Invalid special class name \"$className\"")
}

private fun parsePolicy(s: String): FilterPolicy {
    return when (s.lowercase()) {
        "s", "stub" -> FilterPolicy.Stub
        "k", "keep" -> FilterPolicy.Keep
        "t", "throw" -> FilterPolicy.Throw
        "r", "remove" -> FilterPolicy.Remove
        "sc", "stubclass" -> FilterPolicy.StubClass
        "kc", "keepclass" -> FilterPolicy.KeepClass
        else -> {
            if (s.startsWith("@")) {
                FilterPolicy.SubstituteAndStub
            } else if (s.startsWith("%")) {
                FilterPolicy.SubstituteAndKeep
            } else {
                throw ParseException("Invalid policy \"$s\"")
            }
        }
    }
}
