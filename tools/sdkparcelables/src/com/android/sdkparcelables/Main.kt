/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.sdkparcelables

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

fun main(args: Array<String>) {
    if (args.size != 2) {
        usage()
    }

    val zipFileName = args[0]
    val aidlFileName = args[1]

    val zipFile: ZipFile

    try {
        zipFile = ZipFile(zipFileName)
    } catch (e: IOException) {
        System.err.println("error reading input jar: ${e.message}")
        kotlin.system.exitProcess(2)
    }

    val ancestorCollector = AncestorCollector(Opcodes.ASM6, null)

    for (entry in zipFile.entries()) {
        if (entry.name.endsWith(".class")) {
            val reader = ClassReader(zipFile.getInputStream(entry))
            reader.accept(ancestorCollector,
                    ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        }
    }

    val parcelables = ParcelableDetector.ancestorsToParcelables(ancestorCollector.ancestors)

    try {
        val outFile = File(aidlFileName)
        val outWriter = outFile.bufferedWriter()
        for (parcelable in parcelables) {
            outWriter.write("parcelable ")
            outWriter.write(parcelable.replace('/', '.').replace('$', '.'))
            outWriter.write(";\n")
        }
        outWriter.flush()
    } catch (e: IOException) {
        System.err.println("error writing output aidl: ${e.message}")
        kotlin.system.exitProcess(2)
    }
}

fun usage() {
    System.err.println("Usage: <input jar> <output aidl>")
    kotlin.system.exitProcess(1)
}
