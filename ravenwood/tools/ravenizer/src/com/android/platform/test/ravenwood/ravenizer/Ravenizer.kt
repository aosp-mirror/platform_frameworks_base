/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.platform.test.ravenwood.ravenizer

import com.android.hoststubgen.GeneralUserErrorException
import com.android.hoststubgen.asm.ClassNodes
import com.android.hoststubgen.asm.zipEntryNameToClassName
import com.android.hoststubgen.executableName
import com.android.hoststubgen.log
import com.android.platform.test.ravenwood.ravenizer.adapter.RunnerRewritingAdapter
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.util.CheckClassAdapter
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Various stats on Ravenizer.
 */
data class RavenizerStats(
    /** Total end-to-end time. */
    var totalTime: Double = .0,

    /** Time took to build [ClasNodes] */
    var loadStructureTime: Double = .0,

    /** Time took to validate the classes */
    var validationTime: Double = .0,

    /** Total real time spent for converting the jar file */
    var totalProcessTime: Double = .0,

    /** Total real time spent for converting class files (except for I/O time). */
    var totalConversionTime: Double = .0,

    /** Total real time spent for copying class files without modification. */
    var totalCopyTime: Double = .0,

    /** # of entries in the input jar file */
    var totalEntiries: Int = 0,

    /** # of *.class files in the input jar file */
    var totalClasses: Int = 0,

    /** # of *.class files that have been processed. */
    var processedClasses: Int = 0,
) {
    override fun toString(): String {
        return """
            RavenizerStats{
              totalTime=$totalTime,
              loadStructureTime=$loadStructureTime,
              validationTime=$validationTime,
              totalProcessTime=$totalProcessTime,
              totalConversionTime=$totalConversionTime,
              totalCopyTime=$totalCopyTime,
              totalEntiries=$totalEntiries,
              totalClasses=$totalClasses,
              processedClasses=$processedClasses,
            }
            """.trimIndent()
    }
}

/**
 * Main class.
 */
class Ravenizer(val options: RavenizerOptions) {
    fun run() {
        val stats = RavenizerStats()

        val fatalValidation = options.fatalValidation.get

        stats.totalTime = log.nTime {
            process(
                options.inJar.get,
                options.outJar.get,
                options.enableValidation.get,
                fatalValidation,
                stats,
            )
        }
        log.i(stats.toString())
    }

    private fun process(
        inJar: String,
        outJar: String,
        enableValidation: Boolean,
        fatalValidation: Boolean,
        stats: RavenizerStats,
    ) {
        var allClasses = ClassNodes.loadClassStructures(inJar) {
            time -> stats.loadStructureTime = time
        }
        if (enableValidation) {
            stats.validationTime = log.iTime("Validating classes") {
                if (!validateClasses(allClasses)) {
                    var message = "Invalid test class(es) detected." +
                            " See error log for details."
                    if (fatalValidation) {
                        throw RavenizerInvalidTestException(message)
                    } else {
                        log.w("Warning: $message")
                    }
                }
            }
        }

        stats.totalProcessTime = log.vTime("$executableName processing $inJar") {
            ZipFile(inJar).use { inZip ->
                val inEntries = inZip.entries()

                stats.totalEntiries = inZip.size()

                ZipOutputStream(BufferedOutputStream(FileOutputStream(outJar))).use { outZip ->
                    while (inEntries.hasMoreElements()) {
                        val entry = inEntries.nextElement()

                        if (entry.name.endsWith(".dex")) {
                            // Seems like it's an ART jar file. We can't process it.
                            // It's a fatal error.
                            throw GeneralUserErrorException(
                                "$inJar is not a desktop jar file. It contains a *.dex file."
                            )
                        }

                        val className = zipEntryNameToClassName(entry.name)

                        if (className != null) {
                            stats.totalClasses += 1
                        }

                        if (className != null && shouldProcessClass(allClasses, className)) {
                            stats.processedClasses += 1
                            processSingleClass(inZip, entry, outZip, allClasses, stats)
                        } else {
                            // Too slow, let's use merge_zips to bring back the original classes.
                            copyZipEntry(inZip, entry, outZip, stats)
                        }
                    }
                }
            }
        }
    }

    /**
     * Copy a single ZIP entry to the output.
     */
    private fun copyZipEntry(
        inZip: ZipFile,
        entry: ZipEntry,
        out: ZipOutputStream,
        stats: RavenizerStats,
    ) {
        stats.totalCopyTime += log.nTime {
            inZip.getInputStream(entry).use { ins ->
                // Copy unknown entries as is to the impl out. (but not to the stub out.)
                val outEntry = ZipEntry(entry.name)
                outEntry.method = 0
                outEntry.size = entry.size
                outEntry.crc = entry.crc
                out.putNextEntry(outEntry)

                ins.transferTo(out)

                out.closeEntry()
            }
        }
    }

    private fun processSingleClass(
        inZip: ZipFile,
        entry: ZipEntry,
        outZip: ZipOutputStream,
        allClasses: ClassNodes,
        stats: RavenizerStats,
    ) {
        val newEntry = ZipEntry(entry.name)
        outZip.putNextEntry(newEntry)

        BufferedInputStream(inZip.getInputStream(entry)).use { bis ->
            processSingleClass(entry, bis, outZip, allClasses, stats)
        }
        outZip.closeEntry()
    }

    /**
     * Whether a class needs to be processed. This must be kept in sync with [processSingleClass].
     */
    private fun shouldProcessClass(classes: ClassNodes, classInternalName: String): Boolean {
        return !classInternalName.shouldByBypassed()
                && RunnerRewritingAdapter.shouldProcess(classes, classInternalName)
    }

    private fun processSingleClass(
        entry: ZipEntry,
        input: InputStream,
        output: OutputStream,
        allClasses: ClassNodes,
        stats: RavenizerStats,
    ) {
        val cr = ClassReader(input)

        lateinit var data: ByteArray
        stats.totalConversionTime += log.vTime("Modify ${entry.name}") {

            val classInternalName = zipEntryNameToClassName(entry.name)
                ?: throw RavenizerInternalException("Unexpected zip entry name: ${entry.name}")
            val flags = ClassWriter.COMPUTE_MAXS
            val cw = ClassWriter(flags)
            var outVisitor: ClassVisitor = cw

            val enableChecker = false
            if (enableChecker) {
                outVisitor = CheckClassAdapter(outVisitor)
            }

            // This must be kept in sync with shouldProcessClass.
            outVisitor = RunnerRewritingAdapter.maybeApply(
                classInternalName, allClasses, outVisitor)

            cr.accept(outVisitor, ClassReader.EXPAND_FRAMES)

            data = cw.toByteArray()
        }
        output.write(data)
    }
}
