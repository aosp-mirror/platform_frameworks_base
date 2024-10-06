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
package com.android.hoststubgen

import com.android.hoststubgen.filters.FilterPolicy
import java.io.BufferedReader
import java.io.FileReader

/**
 * A single value that can only set once.
 */
open class SetOnce<T>(private var value: T) {
    class SetMoreThanOnceException : Exception()

    private var set = false

    fun set(v: T): T {
        if (set) {
            throw SetMoreThanOnceException()
        }
        if (v == null) {
            throw NullPointerException("This shouldn't happen")
        }
        set = true
        value = v
        return v
    }

    val get: T
        get() = this.value

    val isSet: Boolean
        get() = this.set

    fun <R> ifSet(block: (T & Any) -> R): R? {
        if (isSet) {
            return block(value!!)
        }
        return null
    }

    override fun toString(): String {
        return "$value"
    }
}

class IntSetOnce(value: Int) : SetOnce<Int>(value) {
    fun set(v: String): Int {
        try {
            return this.set(v.toInt())
        } catch (e: NumberFormatException) {
            throw ArgumentsException("Invalid integer $v")
        }
    }
}

/**
 * Options that can be set from command line arguments.
 */
class HostStubGenOptions(
        /** Input jar file*/
        var inJar: SetOnce<String> = SetOnce(""),

        /** Output jar file */
        var outJar: SetOnce<String?> = SetOnce(null),

        var inputJarDumpFile: SetOnce<String?> = SetOnce(null),

        var inputJarAsKeepAllFile: SetOnce<String?> = SetOnce(null),

        var keepAnnotations: MutableSet<String> = mutableSetOf(),
        var throwAnnotations: MutableSet<String> = mutableSetOf(),
        var removeAnnotations: MutableSet<String> = mutableSetOf(),
        var ignoreAnnotations: MutableSet<String> = mutableSetOf(),
        var keepClassAnnotations: MutableSet<String> = mutableSetOf(),
        var redirectAnnotations: MutableSet<String> = mutableSetOf(),

        var substituteAnnotations: MutableSet<String> = mutableSetOf(),
        var redirectionClassAnnotations: MutableSet<String> = mutableSetOf(),
        var classLoadHookAnnotations: MutableSet<String> = mutableSetOf(),
        var keepStaticInitializerAnnotations: MutableSet<String> = mutableSetOf(),

        var packageRedirects: MutableList<Pair<String, String>> = mutableListOf(),

        var annotationAllowedClassesFile: SetOnce<String?> = SetOnce(null),

        var defaultClassLoadHook: SetOnce<String?> = SetOnce(null),
        var defaultMethodCallHook: SetOnce<String?> = SetOnce(null),

        var policyOverrideFile: SetOnce<String?> = SetOnce(null),

        var defaultPolicy: SetOnce<FilterPolicy> = SetOnce(FilterPolicy.Remove),

        var cleanUpOnError: SetOnce<Boolean> = SetOnce(false),

        var enableClassChecker: SetOnce<Boolean> = SetOnce(false),
        var enablePreTrace: SetOnce<Boolean> = SetOnce(false),
        var enablePostTrace: SetOnce<Boolean> = SetOnce(false),

        var statsFile: SetOnce<String?> = SetOnce(null),

        var apiListFile: SetOnce<String?> = SetOnce(null),

        var numShards: IntSetOnce = IntSetOnce(1),
        var shard: IntSetOnce = IntSetOnce(0),
) {
    companion object {

        private fun parsePackageRedirect(fromColonTo: String): Pair<String, String> {
            val colon = fromColonTo.indexOf(':')
            if ((colon < 1) || (colon + 1 >= fromColonTo.length)) {
                throw ArgumentsException("--package-redirect must be a colon-separated string")
            }
            // TODO check for duplicates
            return Pair(fromColonTo.substring(0, colon), fromColonTo.substring(colon + 1))
        }

        fun parseArgs(args: Array<String>): HostStubGenOptions {
            val ret = HostStubGenOptions()

            val ai = ArgIterator.withAtFiles(args)

            var allAnnotations = mutableSetOf<String>()

            fun ensureUniqueAnnotation(name: String): String {
                if (!allAnnotations.add(name)) {
                    throw DuplicateAnnotationException(ai.current)
                }
                return name
            }

            while (true) {
                val arg = ai.nextArgOptional() ?: break

                // Define some shorthands...
                fun nextArg(): String = ai.nextArgRequired(arg)
                fun MutableSet<String>.addUniqueAnnotationArg(): String =
                        nextArg().also { this += ensureUniqueAnnotation(it) }

                if (log.maybeHandleCommandLineArg(arg) { nextArg() }) {
                    continue
                }
                try {
                    when (arg) {
                        // TODO: Write help
                        "-h", "--help" -> TODO("Help is not implemented yet")

                        "--in-jar" -> ret.inJar.set(nextArg()).ensureFileExists()
                        // We support both arguments because some AOSP dependencies
                        // still use the old argument
                        "--out-jar", "--out-impl-jar" -> ret.outJar.set(nextArg())

                        "--policy-override-file" ->
                            ret.policyOverrideFile.set(nextArg())!!.ensureFileExists()

                        "--clean-up-on-error" -> ret.cleanUpOnError.set(true)
                        "--no-clean-up-on-error" -> ret.cleanUpOnError.set(false)

                        "--default-remove" -> ret.defaultPolicy.set(FilterPolicy.Remove)
                        "--default-throw" -> ret.defaultPolicy.set(FilterPolicy.Throw)
                        "--default-keep" -> ret.defaultPolicy.set(FilterPolicy.Keep)

                        "--keep-annotation" ->
                            ret.keepAnnotations.addUniqueAnnotationArg()

                        "--keep-class-annotation" ->
                            ret.keepClassAnnotations.addUniqueAnnotationArg()

                        "--throw-annotation" ->
                            ret.throwAnnotations.addUniqueAnnotationArg()

                        "--remove-annotation" ->
                            ret.removeAnnotations.addUniqueAnnotationArg()

                        "--ignore-annotation" ->
                            ret.ignoreAnnotations.addUniqueAnnotationArg()

                        "--substitute-annotation" ->
                            ret.substituteAnnotations.addUniqueAnnotationArg()

                        "--redirect-annotation" ->
                            ret.redirectAnnotations.addUniqueAnnotationArg()

                        "--redirection-class-annotation" ->
                            ret.redirectionClassAnnotations.addUniqueAnnotationArg()

                        "--class-load-hook-annotation" ->
                            ret.classLoadHookAnnotations.addUniqueAnnotationArg()

                        "--keep-static-initializer-annotation" ->
                            ret.keepStaticInitializerAnnotations.addUniqueAnnotationArg()

                        "--package-redirect" ->
                            ret.packageRedirects += parsePackageRedirect(nextArg())

                        "--annotation-allowed-classes-file" ->
                            ret.annotationAllowedClassesFile.set(nextArg())

                        "--default-class-load-hook" ->
                            ret.defaultClassLoadHook.set(nextArg())

                        "--default-method-call-hook" ->
                            ret.defaultMethodCallHook.set(nextArg())

                        "--gen-keep-all-file" ->
                            ret.inputJarAsKeepAllFile.set(nextArg())

                        // Following options are for debugging.
                        "--enable-class-checker" -> ret.enableClassChecker.set(true)
                        "--no-class-checker" -> ret.enableClassChecker.set(false)

                        "--enable-pre-trace" -> ret.enablePreTrace.set(true)
                        "--no-pre-trace" -> ret.enablePreTrace.set(false)

                        "--enable-post-trace" -> ret.enablePostTrace.set(true)
                        "--no-post-trace" -> ret.enablePostTrace.set(false)

                        "--gen-input-dump-file" -> ret.inputJarDumpFile.set(nextArg())

                        "--stats-file" -> ret.statsFile.set(nextArg())
                        "--supported-api-list-file" -> ret.apiListFile.set(nextArg())

                        "--num-shards" -> ret.numShards.set(nextArg()).also {
                            if (it < 1) {
                                throw ArgumentsException("$arg must be positive integer")
                            }
                        }
                        "--shard-index" -> ret.shard.set(nextArg()).also {
                            if (it < 0) {
                                throw ArgumentsException("$arg must be positive integer or zero")
                            }
                        }

                        else -> throw ArgumentsException("Unknown option: $arg")
                    }
                } catch (e: SetOnce.SetMoreThanOnceException) {
                    throw ArgumentsException("Duplicate or conflicting argument found: $arg")
                }
            }

            if (!ret.inJar.isSet) {
                throw ArgumentsException("Required option missing: --in-jar")
            }
            if (!ret.outJar.isSet) {
                log.w("--out-jar is not set. $executableName will not generate jar files.")
            }
            if (ret.numShards.isSet != ret.shard.isSet) {
                throw ArgumentsException("--num-shards and --shard-index must be used together")
            }

            if (ret.numShards.isSet) {
                if (ret.shard.get >= ret.numShards.get) {
                    throw ArgumentsException("--shard-index must be smaller than --num-shards")
                }
            }

            return ret
        }
    }

    override fun toString(): String {
        return """
            HostStubGenOptions{
              inJar='$inJar',
              outJar='$outJar',
              inputJarDumpFile=$inputJarDumpFile,
              inputJarAsKeepAllFile=$inputJarAsKeepAllFile,
              keepAnnotations=$keepAnnotations,
              throwAnnotations=$throwAnnotations,
              removeAnnotations=$removeAnnotations,
              ignoreAnnotations=$ignoreAnnotations,
              keepClassAnnotations=$keepClassAnnotations,
              substituteAnnotations=$substituteAnnotations,
              nativeSubstituteAnnotations=$redirectionClassAnnotations,
              classLoadHookAnnotations=$classLoadHookAnnotations,
              keepStaticInitializerAnnotations=$keepStaticInitializerAnnotations,
              packageRedirects=$packageRedirects,
              annotationAllowedClassesFile=$annotationAllowedClassesFile,
              defaultClassLoadHook=$defaultClassLoadHook,
              defaultMethodCallHook=$defaultMethodCallHook,
              policyOverrideFile=$policyOverrideFile,
              defaultPolicy=$defaultPolicy,
              cleanUpOnError=$cleanUpOnError,
              enableClassChecker=$enableClassChecker,
              enablePreTrace=$enablePreTrace,
              enablePostTrace=$enablePostTrace,
              statsFile=$statsFile,
              apiListFile=$apiListFile,
              numShards=$numShards,
              shard=$shard,
            }
            """.trimIndent()
    }
}

class ArgIterator(
    private val args: List<String>,
    private var currentIndex: Int = -1
) {
    val current: String
        get() = args.get(currentIndex)

    /**
     * Get the next argument, or [null] if there's no more arguments.
     */
    fun nextArgOptional(): String? {
        if ((currentIndex + 1) >= args.size) {
            return null
        }
        return args.get(++currentIndex)
    }

    /**
     * Get the next argument, or throw if
     */
    fun nextArgRequired(argName: String): String {
        nextArgOptional().let {
            if (it == null) {
                throw ArgumentsException("Missing parameter for option $argName")
            }
            if (it.isEmpty()) {
                throw ArgumentsException("Parameter can't be empty for option $argName")
            }
            return it
        }
    }

    companion object {
        fun withAtFiles(args: Array<String>): ArgIterator {
            return ArgIterator(expandAtFiles(args))
        }
    }
}

/**
 * Scan the arguments, and if any of them starts with an `@`, then load from the file
 * and use its content as arguments.
 *
 * In this file, each line is treated as a single argument.
 *
 * The file can contain '#' as comments.
 */
private fun expandAtFiles(args: Array<String>): List<String> {
    val ret = mutableListOf<String>()

    args.forEach { arg ->
        if (!arg.startsWith('@')) {
            ret += arg
            return@forEach
        }
        // Read from the file, and add each line to the result.
        val filename = arg.substring(1).ensureFileExists()

        log.v("Expanding options file $filename")

        BufferedReader(FileReader(filename)).use { reader ->
            while (true) {
                var line = reader.readLine()
                if (line == null) {
                    break // EOF
                }

                line = normalizeTextLine(line)
                if (line.isNotEmpty()) {
                    ret += line
                }
            }
        }
    }
    return ret
}
