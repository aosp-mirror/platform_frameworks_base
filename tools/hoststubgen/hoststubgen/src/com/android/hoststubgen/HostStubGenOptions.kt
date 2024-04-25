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
 * A single value that can only set once.
 */
class SetOnce<T>(
        private var value: T,
) {
    class SetMoreThanOnceException : Exception()

    private var set = false

    fun set(v: T) {
        if (set) {
            throw SetMoreThanOnceException()
        }
        if (v == null) {
            throw NullPointerException("This shouldn't happen")
        }
        set = true
        value = v
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

/**
 * Options that can be set from command line arguments.
 */
class HostStubGenOptions(
        /** Input jar file*/
        var inJar: SetOnce<String> = SetOnce(""),

        /** Output stub jar file */
        var outStubJar: SetOnce<String?> = SetOnce(null),

        /** Output implementation jar file */
        var outImplJar: SetOnce<String?> = SetOnce(null),

        var inputJarDumpFile: SetOnce<String?> = SetOnce(null),

        var inputJarAsKeepAllFile: SetOnce<String?> = SetOnce(null),

        var stubAnnotations: MutableSet<String> = mutableSetOf(),
        var keepAnnotations: MutableSet<String> = mutableSetOf(),
        var throwAnnotations: MutableSet<String> = mutableSetOf(),
        var removeAnnotations: MutableSet<String> = mutableSetOf(),
        var stubClassAnnotations: MutableSet<String> = mutableSetOf(),
        var keepClassAnnotations: MutableSet<String> = mutableSetOf(),

        var substituteAnnotations: MutableSet<String> = mutableSetOf(),
        var nativeSubstituteAnnotations: MutableSet<String> = mutableSetOf(),
        var classLoadHookAnnotations: MutableSet<String> = mutableSetOf(),
        var keepStaticInitializerAnnotations: MutableSet<String> = mutableSetOf(),

        var packageRedirects: MutableList<Pair<String, String>> = mutableListOf(),

        var annotationAllowedClassesFile: SetOnce<String?> = SetOnce(null),

        var defaultClassLoadHook: SetOnce<String?> = SetOnce(null),
        var defaultMethodCallHook: SetOnce<String?> = SetOnce(null),

        var intersectStubJars: MutableSet<String> = mutableSetOf(),

        var policyOverrideFile: SetOnce<String?> = SetOnce(null),

        var defaultPolicy: SetOnce<FilterPolicy> = SetOnce(FilterPolicy.Remove),

        var cleanUpOnError: SetOnce<Boolean> = SetOnce(false),

        var enableClassChecker: SetOnce<Boolean> = SetOnce(false),
        var enablePreTrace: SetOnce<Boolean> = SetOnce(false),
        var enablePostTrace: SetOnce<Boolean> = SetOnce(false),

        var enableNonStubMethodCallDetection: SetOnce<Boolean> = SetOnce(false),

        var statsFile: SetOnce<String?> = SetOnce(null),

        var apiListFile: SetOnce<String?> = SetOnce(null),
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

            fun setLogFile(level: LogLevel, filename: String) {
                log.addFilePrinter(level, filename)
                log.i("$level log file: $filename")
            }

            while (true) {
                val arg = ai.nextArgOptional()
                if (arg == null) {
                    break
                }

                // Define some shorthands...
                fun nextArg(): String = ai.nextArgRequired(arg)
                fun SetOnce<String>.setNextStringArg(): String = nextArg().also { this.set(it) }
                fun SetOnce<String?>.setNextStringArg(): String = nextArg().also { this.set(it) }
                fun MutableSet<String>.addUniqueAnnotationArg(): String =
                        nextArg().also { this += ensureUniqueAnnotation(it) }

                try {
                    when (arg) {
                        // TODO: Write help
                        "-h", "--help" -> TODO("Help is not implemented yet")

                        "-v", "--verbose" -> log.setConsoleLogLevel(LogLevel.Verbose)
                        "-d", "--debug" -> log.setConsoleLogLevel(LogLevel.Debug)
                        "-q", "--quiet" -> log.setConsoleLogLevel(LogLevel.None)

                        "--in-jar" -> ret.inJar.setNextStringArg().ensureFileExists()
                        "--out-stub-jar" -> ret.outStubJar.setNextStringArg()
                        "--out-impl-jar" -> ret.outImplJar.setNextStringArg()

                        "--policy-override-file" ->
                            ret.policyOverrideFile.setNextStringArg().ensureFileExists()

                        "--clean-up-on-error" -> ret.cleanUpOnError.set(true)
                        "--no-clean-up-on-error" -> ret.cleanUpOnError.set(false)

                        "--default-remove" -> ret.defaultPolicy.set(FilterPolicy.Remove)
                        "--default-throw" -> ret.defaultPolicy.set(FilterPolicy.Throw)
                        "--default-keep" -> ret.defaultPolicy.set(FilterPolicy.Keep)
                        "--default-stub" -> ret.defaultPolicy.set(FilterPolicy.Stub)

                        "--stub-annotation" ->
                            ret.stubAnnotations.addUniqueAnnotationArg()

                        "--keep-annotation" ->
                            ret.keepAnnotations.addUniqueAnnotationArg()

                        "--stub-class-annotation" ->
                            ret.stubClassAnnotations.addUniqueAnnotationArg()

                        "--keep-class-annotation" ->
                            ret.keepClassAnnotations.addUniqueAnnotationArg()

                        "--throw-annotation" ->
                            ret.throwAnnotations.addUniqueAnnotationArg()

                        "--remove-annotation" ->
                            ret.removeAnnotations.addUniqueAnnotationArg()

                        "--substitute-annotation" ->
                            ret.substituteAnnotations.addUniqueAnnotationArg()

                        "--native-substitute-annotation" ->
                            ret.nativeSubstituteAnnotations.addUniqueAnnotationArg()

                        "--class-load-hook-annotation" ->
                            ret.classLoadHookAnnotations.addUniqueAnnotationArg()

                        "--keep-static-initializer-annotation" ->
                            ret.keepStaticInitializerAnnotations.addUniqueAnnotationArg()

                        "--package-redirect" ->
                            ret.packageRedirects += parsePackageRedirect(nextArg())

                        "--annotation-allowed-classes-file" ->
                            ret.annotationAllowedClassesFile.setNextStringArg()

                        "--default-class-load-hook" ->
                            ret.defaultClassLoadHook.setNextStringArg()

                        "--default-method-call-hook" ->
                            ret.defaultMethodCallHook.setNextStringArg()

                        "--intersect-stub-jar" ->
                            ret.intersectStubJars += nextArg().ensureFileExists()

                        "--gen-keep-all-file" ->
                            ret.inputJarAsKeepAllFile.setNextStringArg()

                        // Following options are for debugging.
                        "--enable-class-checker" -> ret.enableClassChecker.set(true)
                        "--no-class-checker" -> ret.enableClassChecker.set(false)

                        "--enable-pre-trace" -> ret.enablePreTrace.set(true)
                        "--no-pre-trace" -> ret.enablePreTrace.set(false)

                        "--enable-post-trace" -> ret.enablePostTrace.set(true)
                        "--no-post-trace" -> ret.enablePostTrace.set(false)

                        "--enable-non-stub-method-check" ->
                            ret.enableNonStubMethodCallDetection.set(true)

                        "--no-non-stub-method-check" ->
                            ret.enableNonStubMethodCallDetection.set(false)

                        "--gen-input-dump-file" -> ret.inputJarDumpFile.setNextStringArg()

                        "--verbose-log" -> setLogFile(LogLevel.Verbose, nextArg())
                        "--debug-log" -> setLogFile(LogLevel.Debug, nextArg())

                        "--stats-file" -> ret.statsFile.setNextStringArg()
                        "--supported-api-list-file" -> ret.apiListFile.setNextStringArg()

                        else -> throw ArgumentsException("Unknown option: $arg")
                    }
                } catch (e: SetOnce.SetMoreThanOnceException) {
                    throw ArgumentsException("Duplicate or conflicting argument found: $arg")
                }
            }

            if (!ret.inJar.isSet) {
                throw ArgumentsException("Required option missing: --in-jar")
            }
            if (!ret.outStubJar.isSet && !ret.outImplJar.isSet) {
                log.w("Neither --out-stub-jar nor --out-impl-jar is set." +
                        " $executableName will not generate jar files.")
            }

            if (ret.enableNonStubMethodCallDetection.get) {
                log.w("--enable-non-stub-method-check is not fully implemented yet." +
                    " See the todo in doesMethodNeedNonStubCallCheck().")
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
              keepStaticInitializerAnnotations=$keepStaticInitializerAnnotations,
              packageRedirects=$packageRedirects,
              $annotationAllowedClassesFile=$annotationAllowedClassesFile,
              defaultClassLoadHook=$defaultClassLoadHook,
              defaultMethodCallHook=$defaultMethodCallHook,
              intersectStubJars=$intersectStubJars,
              policyOverrideFile=$policyOverrideFile,
              defaultPolicy=$defaultPolicy,
              cleanUpOnError=$cleanUpOnError,
              enableClassChecker=$enableClassChecker,
              enablePreTrace=$enablePreTrace,
              enablePostTrace=$enablePostTrace,
              enableNonStubMethodCallDetection=$enableNonStubMethodCallDetection,
              statsFile=$statsFile,
              apiListFile=$apiListFile,
            }
            """.trimIndent()
    }
}
