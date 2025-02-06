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
package com.android.platform.test.ravenwood.ravenhelper.sourcemap

import com.android.hoststubgen.GeneralUserErrorException
import com.android.hoststubgen.log
import com.android.platform.test.ravenwood.ravenhelper.SubcommandHandler
import com.android.platform.test.ravenwood.ravenhelper.policytoannot.SourceOperation
import com.android.platform.test.ravenwood.ravenhelper.policytoannot.SourceOperationType
import com.android.platform.test.ravenwood.ravenhelper.policytoannot.SourceOperations
import com.android.platform.test.ravenwood.ravenhelper.policytoannot.createShellScript
import com.android.platform.test.ravenwood.ravenhelper.psi.createUastEnvironment
import java.io.BufferedReader
import java.io.FileReader

/**
 * This is the main routine of the "mm" subcommands, which marks specified methods with
 * a given line, which defaults to "@DisabledOnRavenwood". This can be used to add bulk-annotate
 * tests methods that are failing.
 *
 * See the javadoc of [MarkMethodProcessor] for more details.
 */
class MarkMethodHandler : SubcommandHandler {
    override fun handle(args: List<String>) {
        val options = MapOptions.parseArgs(args)

        log.i("Options: $options")

        // Files from which we load a list of methods.
        val inputFiles = if (options.targetMethodFiles.isEmpty()) {
            log.w("[Reading method list from STDIN...]")
            log.flush()
            listOf("/dev/stdin")
        } else {
            options.targetMethodFiles
        }

        // A string to inject before each method.
        val text = if (options.text.isSet) {
            options.text.get!!
        } else {
            "@android.platform.test.annotations.DisabledOnRavenwood"
        }

        // Process.
        val processor = MarkMethodProcessor(
            options.sourceFilesOrDirectories,
            inputFiles,
            text,
        )
        processor.process()

        // Create the output script.
        createShellScript(processor.resultOperations, options.outputScriptFile.get)
    }
}

/**
 * Load a list of methods / classes from [targetMethodFiles], and inject [textToInsert] to
 * each of them, to the source files under [sourceFilesOrDirectories]
 *
 * An example input files look like this -- this can be generated from atest output.
 * <pre>

 # We add @DisabledOnRavenwood to the following methods.
 com.android.ravenwoodtest.tests.Test1#testA
 com.android.ravenwoodtest.tests.Test1#testB
 com.android.ravenwoodtest.tests.Test1#testC

 # We add @DisabledOnRavenwood to the following class.
 com.android.ravenwoodtest.tests.Test2

 # Special case: we add the annotation to the class too.
 com.android.ravenwoodtest.tests.Test3#initializationError
 </pre>

 */
private class MarkMethodProcessor(
    private val sourceFilesOrDirectories: List<String>,
    private val targetMethodFiles: List<String>,
    private val textToInsert: String,
) {
    private val classes = AllClassInfo()
    val resultOperations = SourceOperations()

    /**
     * Entry point.
     */
    fun process() {
        val env = createUastEnvironment()
        try {
            loadSources()

            processInputFiles()
        } finally {
            env.dispose()
        }
    }

    private fun loadSources() {
        val env = createUastEnvironment()
        try {
            val loader = SourceLoader(env)
            loader.load(sourceFilesOrDirectories, classes)
        } finally {
            env.dispose()
        }
    }

    /**
     * Process liput files. Input files looks like this:
     * <pre>
     * # We add @DisabledOnRavenwood to the following methods.
     * com.android.ravenwoodtest.tests.Test1#testA
     * com.android.ravenwoodtest.tests.Test1#testB
     * com.android.ravenwoodtest.tests.Test1#testC
     *
     * # We add @DisabledOnRavenwood to the following class.
     * com.android.ravenwoodtest.tests.Test2
     *
     * # Special case: we add the annotation to the class too.
     * com.android.ravenwoodtest.tests.Test3#initializationError
     * </pre>
     */
    private fun processInputFiles() {
        targetMethodFiles.forEach { filename ->
            BufferedReader(FileReader(filename)).use { reader ->
                reader.readLines().forEach { line ->
                    if (line.isBlank() || line.startsWith('#')) {
                        return@forEach
                    }
                    processSingleLine(line)
                }
            }
        }
    }

    private fun processSingleLine(line: String) {
        val cm = line.split("#") // Class and method
        if (cm.size > 2) {
            throw GeneralUserErrorException("Input line \"$line\" contains too many #'s")
        }
        val className = cm[0]
        val methodName = if (cm.size == 2 && cm[1] != "initializationError") {
            cm[1]
        } else {
            ""
        }

        // Find class info
        val ci = classes.findClass(className)
            ?: throw GeneralUserErrorException("Class \"$className\" not found\"")

        if (methodName == "") {
            processClass(ci)
        } else {
            processMethod(ci, methodName)
        }
    }

    private fun processClass(ci: ClassInfo) {
        addOperation(ci.location, "Class ${ci.fullName}")
    }

    private fun processMethod(ci: ClassInfo, methodName: String) {
        var methods = ci.methods[methodName]
            ?: throw GeneralUserErrorException("method \"$methodName\" not found\"")
        methods.forEach { mi ->
            addOperation(mi.location, "Method ${mi.name}")
        }
    }

    private fun addOperation(loc: Location, description: String) {
        resultOperations.add(
            SourceOperation(
                loc.file,
                loc.line,
                SourceOperationType.Insert,
                loc.getIndent() + textToInsert,
                description
            )
        )
    }
}
