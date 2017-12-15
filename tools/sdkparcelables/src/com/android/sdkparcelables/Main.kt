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