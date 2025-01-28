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

/*
 * This file contains classes and functions about file edit operations, such as
 * "insert a line", "delete a line".
 */


import com.android.hoststubgen.log
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

enum class SourceOperationType {
    /** Insert a line */
    Insert,

    /** delete a line */
    Delete,

    /** Insert a text at the beginning of a line */
    Prepend,
}

data class SourceOperation(
    /** Target file to edit. */
    val sourceFile: String,

    /** 1-based line number. Use -1 to add at the end of the file. */
    val lineNumber: Int,

    /** Operation type.*/
    val type: SourceOperationType,

    /** Operand -- text to insert or prepend. Ignored for delete. */
    val text: String = "",

    /** Human-readable description of why this operation was created */
    val description: String,
) {
    override fun toString(): String {
        return "SourceOperation(sourceFile='$sourceFile', " +
                "lineNumber=$lineNumber, type=$type, text='$text' desc='$description')"
    }
}

/**
 * Stores list of [SourceOperation]s for each file.
 */
class SourceOperations {
    var size: Int = 0
        private set
    private val fileOperations = mutableMapOf<String, MutableList<SourceOperation>>()

    fun add(op: SourceOperation) {
        log.forVerbose {
            log.v("Adding operation: $op")
        }
        size++
        fileOperations[op.sourceFile]?.let { ops ->
            ops.add(op)
            return
        }
        fileOperations[op.sourceFile] = mutableListOf(op)
    }

    /**
     * Get the collected [SourceOperation]s for each file.
     */
    fun getOperations(): MutableMap<String, MutableList<SourceOperation>> {
        return fileOperations
    }
}

/**
 * Create a shell script to apply all the operations (using sed).
 */
fun createShellScript(ops: SourceOperations, writer: BufferedWriter) {
    // File header.
    // Note ${'$'} is an ugly way to put a dollar sign ($) in a multi-line string.
    writer.write(
        """
        #!/bin/bash

        set -e # Finish when any command fails.

        function apply() {
            local file="${'$'}1"

            # The script is given via stdin. Write it to file.
            local sed="/tmp/pta-script.sed.tmp"
            cat > "${'$'}sed"

            echo "Running: sed -i -f \"${'$'}sed\" \"${'$'}file\""

            if ! sed -i -f "${'$'}sed" "${'$'}file" ; then
                echo 'Failed!' 1>&2
                return 1
            fi
        }

        """.trimIndent()
    )

    ops.getOperations().toSortedMap().forEach { (origFile, ops) ->
        val file = File(origFile).absolutePath

        writer.write("\n")

        writer.write("#")
        writer.write("=".repeat(78))
        writer.write("\n")

        writer.write("\n")

        writer.write("apply \"$file\" <<'__END_OF_SCRIPT__'\n")
        toSedScript(ops, writer)
        writer.write("__END_OF_SCRIPT__\n")
    }

    writer.write("\n")

    writer.write("echo \"All files updated successfully!\"\n")
    writer.flush()
}

/**
 * Create a sed script to apply a list of operations.
 */
private fun toSedScript(ops: List<SourceOperation>, writer: BufferedWriter) {
    ops.sortedBy { it.lineNumber }.forEach { op ->
        if (op.text.contains('\n')) {
            throw RuntimeException("Operation $op may not contain newlines.")
        }

        // Convert each operation to a sed operation. Examples:
        //
        // - Insert "abc" to line 2
        //   2i\
        //   abc
        //
        // - Insert "abc" to the end of the file
        //   $a\
        //   abc
        //
        // - Delete line 2
        //   2d
        //
        // - Prepend abc to line 2
        //   2s/^/abc/
        //
        // The line numbers are all the line numbers in the original file. Even though
        // the script itself will change them because of inserts and deletes, we don't need to
        // change the line numbers in the script.

        // Write the target line number.
        writer.write("\n")
        writer.write("# ${op.description}\n")
        if (op.lineNumber >= 0) {
            writer.write(op.lineNumber.toString())
        } else {
            writer.write("$")
        }

        when (op.type) {
            SourceOperationType.Insert -> {
                if (op.lineNumber >= 0) {
                    writer.write("i\\\n") // "Insert"
                } else {
                    // If it's the end of the file, we need to use "a" (append)
                    writer.write("a\\\n")
                }
                writer.write(op.text)
                writer.write("\n")
            }
            SourceOperationType.Delete -> {
                writer.write("d\n")
            }
            SourceOperationType.Prepend -> {
                if (op.text.contains('/')) {
                    TODO("Operation $op contains character(s) that needs to be escaped.")
                }
                writer.write("s/^/${op.text}/\n")
            }
        }
    }
}

fun createShellScript(ops: SourceOperations, scriptFile: String?): Boolean {
    if (ops.size == 0) {
        log.i("No files need to be updated.")
        return false
    }

    val scriptWriter = BufferedWriter(
        OutputStreamWriter(
            scriptFile?.let { file ->
            FileOutputStream(file)
        } ?: System.out
    ))

    scriptWriter.use { writer ->
        scriptFile?.let {
            log.i("Creating script file at $it ...")
        }
        createShellScript(ops, writer)
    }
    return true
}