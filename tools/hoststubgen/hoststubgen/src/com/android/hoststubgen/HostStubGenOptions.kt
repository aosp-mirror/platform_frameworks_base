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
import java.io.File
import java.io.FileReader

/**
 * Options that can be set from command line arguments.
 */
class HostStubGenOptions(
        /** Input jar file*/
        var inJar: String = "",

        /** Output stub jar file */
        var outStubJar: String = "",

        /** Output implementation jar file */
        var outImplJar: String = "",

        var inputJarDumpFile: String? = null,

        var inputJarAsKeepAllFile: String? = null,

        var stubAnnotations: MutableSet<String> = mutableSetOf(),
        var keepAnnotations: MutableSet<String> = mutableSetOf(),
        var throwAnnotations: MutableSet<String> = mutableSetOf(),
        var removeAnnotations: MutableSet<String> = mutableSetOf(),
        var stubClassAnnotations: MutableSet<String> = mutableSetOf(),
        var keepClassAnnotations: MutableSet<String> = mutableSetOf(),

        var substituteAnnotations: MutableSet<String> = mutableSetOf(),
        var nativeSubstituteAnnotations: MutableSet<String> = mutableSetOf(),
        var classLoadHookAnnotations: MutableSet<String> = mutableSetOf(),
        var stubStaticInitializerAnnotations: MutableSet<String> = mutableSetOf(),

        var packageRedirects: MutableList<Pair<String, String>> = mutableListOf(),

        var defaultClassLoadHook: String? = null,
        var defaultMethodCallHook: String? = null,

        var intersectStubJars: MutableSet<String> = mutableSetOf(),

        var policyOverrideFile: String? = null,

        var defaultPolicy: FilterPolicy = FilterPolicy.Remove,
        var keepAllClasses: Boolean = false,

        var logLevel: LogLevel = LogLevel.Info,

        var cleanUpOnError: Boolean = false,

        var enableClassChecker: Boolean = false,
        var enablePreTrace: Boolean = false,
        var enablePostTrace: Boolean = false,

        var enableNonStubMethodCallDetection: Boolean = true,
) {
    companion object {

        private fun String.ensureFileExists(): String {
            if (!File(this).exists()) {
                throw InputFileNotFoundException(this)
            }
            return this
        }

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

            val ai = ArgIterator(expandAtFiles(args))

            var allAnnotations = mutableSetOf<String>()

            fun ensureUniqueAnnotation(name: String): String {
                if (!allAnnotations.add(name)) {
                    throw DuplicateAnnotationException(ai.current)
                }
                return name
            }

            while (true) {
                val arg = ai.nextArgOptional()
                if (arg == null) {
                    break
                }

                when (arg) {
                    // TODO: Write help
                    "-h", "--h" -> TODO("Help is not implemented yet")

                    "-v", "--verbose" -> ret.logLevel = LogLevel.Verbose
                    "-d", "--debug" -> ret.logLevel = LogLevel.Debug
                    "-q", "--quiet" -> ret.logLevel = LogLevel.None

                    "--in-jar" -> ret.inJar = ai.nextArgRequired(arg).ensureFileExists()
                    "--out-stub-jar" -> ret.outStubJar = ai.nextArgRequired(arg)
                    "--out-impl-jar" -> ret.outImplJar = ai.nextArgRequired(arg)

                    "--policy-override-file" ->
                        ret.policyOverrideFile = ai.nextArgRequired(arg).ensureFileExists()

                    "--clean-up-on-error" -> ret.cleanUpOnError = true
                    "--no-clean-up-on-error" -> ret.cleanUpOnError = false

                    "--default-remove" -> ret.defaultPolicy = FilterPolicy.Remove
                    "--default-throw" -> ret.defaultPolicy = FilterPolicy.Throw
                    "--default-keep" -> ret.defaultPolicy = FilterPolicy.Keep
                    "--default-stub" -> ret.defaultPolicy = FilterPolicy.Stub

                    "--keep-all-classes" -> ret.keepAllClasses = true
                    "--no-keep-all-classes" -> ret.keepAllClasses = false

                    "--stub-annotation" ->
                        ret.stubAnnotations += ensureUniqueAnnotation(ai.nextArgRequired(arg))

                    "--keep-annotation" ->
                        ret.keepAnnotations += ensureUniqueAnnotation(ai.nextArgRequired(arg))

                    "--stub-class-annotation" ->
                        ret.stubClassAnnotations += ensureUniqueAnnotation(ai.nextArgRequired(arg))

                    "--keep-class-annotation" ->
                        ret.keepClassAnnotations += ensureUniqueAnnotation(ai.nextArgRequired(arg))

                    "--throw-annotation" ->
                        ret.throwAnnotations += ensureUniqueAnnotation(ai.nextArgRequired(arg))

                    "--remove-annotation" ->
                        ret.removeAnnotations += ensureUniqueAnnotation(ai.nextArgRequired(arg))

                    "--substitute-annotation" ->
                        ret.substituteAnnotations += ensureUniqueAnnotation(ai.nextArgRequired(arg))

                    "--native-substitute-annotation" ->
                        ret.nativeSubstituteAnnotations +=
                                ensureUniqueAnnotation(ai.nextArgRequired(arg))

                    "--class-load-hook-annotation" ->
                        ret.classLoadHookAnnotations +=
                                ensureUniqueAnnotation(ai.nextArgRequired(arg))

                    "--stub-static-initializer-annotation" ->
                        ret.stubStaticInitializerAnnotations +=
                                ensureUniqueAnnotation(ai.nextArgRequired(arg))

                    "--package-redirect" ->
                        ret.packageRedirects += parsePackageRedirect(ai.nextArgRequired(arg))

                    "--default-class-load-hook" ->
                        ret.defaultClassLoadHook = ai.nextArgRequired(arg)

                    "--default-method-call-hook" ->
                        ret.defaultMethodCallHook = ai.nextArgRequired(arg)

                    "--intersect-stub-jar" ->
                        ret.intersectStubJars += ai.nextArgRequired(arg).ensureFileExists()

                    "--gen-keep-all-file" ->
                        ret.inputJarAsKeepAllFile = ai.nextArgRequired(arg)

                    // Following options are for debugging.
                    "--enable-class-checker" -> ret.enableClassChecker = true
                    "--no-class-checker" -> ret.enableClassChecker = false

                    "--enable-pre-trace" -> ret.enablePreTrace = true
                    "--no-pre-trace" -> ret.enablePreTrace = false

                    "--enable-post-trace" -> ret.enablePostTrace = true
                    "--no-post-trace" -> ret.enablePostTrace = false

                    "--enable-non-stub-method-check" -> ret.enableNonStubMethodCallDetection = true
                    "--no-non-stub-method-check" -> ret.enableNonStubMethodCallDetection = false

                    "--gen-input-dump-file" -> ret.inputJarDumpFile = ai.nextArgRequired(arg)

                    else -> throw ArgumentsException("Unknown option: $arg")
                }
            }
            if (ret.inJar.isEmpty()) {
                throw ArgumentsException("Required option missing: --in-jar")
            }
            if (ret.outStubJar.isEmpty()) {
                throw ArgumentsException("Required option missing: --out-stub-jar")
            }
            if (ret.outImplJar.isEmpty()) {
                throw ArgumentsException("Required option missing: --out-impl-jar")
            }

            return ret
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
    }

    open class ArgumentsException(message: String?) : Exception(message), UserErrorException

    /** Thrown when the same annotation is used with different annotation arguments. */
    class DuplicateAnnotationException(annotationName: String?) :
            ArgumentsException("Duplicate annotation specified: '$annotationName'")

    /** Thrown when an input file does not exist. */
    class InputFileNotFoundException(filename: String) :
            ArgumentsException("File '$filename' not found")

    private class ArgIterator(
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
    }

    override fun toString(): String {
        return """
            HostStubGenOptions{
              inJar='$inJar',
              outStubJar='$outStubJar',
              outImplJar='$outImplJar',
              inputJarDumpFile=$inputJarDumpFile,
              inputJarAsKeepAllFile=$inputJarAsKeepAllFile,
              stubAnnotations=$stubAnnotations,
              keepAnnotations=$keepAnnotations,
              throwAnnotations=$throwAnnotations,
              removeAnnotations=$removeAnnotations,
              stubClassAnnotations=$stubClassAnnotations,
              keepClassAnnotations=$keepClassAnnotations,
              substituteAnnotations=$substituteAnnotations,
              nativeSubstituteAnnotations=$nativeSubstituteAnnotations,
              classLoadHookAnnotations=$classLoadHookAnnotations,
              packageRedirects=$packageRedirects,
              defaultClassLoadHook=$defaultClassLoadHook,
              defaultMethodCallHook=$defaultMethodCallHook,
              intersectStubJars=$intersectStubJars,
              policyOverrideFile=$policyOverrideFile,
              defaultPolicy=$defaultPolicy,
              keepAllClasses=$keepAllClasses,
              logLevel=$logLevel,
              cleanUpOnError=$cleanUpOnError,
              enableClassChecker=$enableClassChecker,
              enablePreTrace=$enablePreTrace,
              enablePostTrace=$enablePostTrace,
              enableNonStubMethodCallDetection=$enableNonStubMethodCallDetection,
            }
            """.trimIndent()
    }
}
