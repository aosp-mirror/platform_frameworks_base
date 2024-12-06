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
package com.android.hoststubgen.asm

import com.android.hoststubgen.ClassParseException
import com.android.hoststubgen.InvalidJarFileException
import com.android.hoststubgen.log
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeAnnotationNode
import java.io.BufferedInputStream
import java.io.PrintWriter
import java.util.Arrays
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Stores all classes loaded from a jar file, in a form of [ClassNode]
 */
class ClassNodes {
    val mAllClasses: MutableMap<String, ClassNode> = HashMap()

    /**
     * Total number of classes registered.
     */
    val size: Int
        get() = mAllClasses.size

    /** Add a [ClassNode] */
    fun addClass(cn: ClassNode): Boolean {
        if (mAllClasses.containsKey(cn.name)) {
            return false
        }
        mAllClasses[cn.name.toJvmClassName()] = cn
        return true
    }

    /** Get a class's [ClassNodes] (which may not exist) */
    fun findClass(name: String): ClassNode? {
        return mAllClasses[name.toJvmClassName()]
    }

    /** Get a class's [ClassNodes] (which must exists) */
    fun getClass(name: String): ClassNode {
        return findClass(name) ?: throw ClassParseException("Class $name not found")
    }

    /** @return whether a class exists or not */
    fun hasClass(name: String): Boolean {
        return mAllClasses.containsKey(name.toJvmClassName())
    }

    /** Find a field, which may not exist. */
    fun findField(
        className: String,
        fieldName: String,
    ): FieldNode? {
        return findClass(className)?.fields?.firstOrNull { it.name == fieldName }?.let { fn ->
            return fn
        }
    }

    /** Find a method, which may not exist. */
    fun findMethod(
        className: String,
        methodName: String,
        descriptor: String,
    ): MethodNode? {
        return findClass(className)?.methods
            ?.firstOrNull { it.name == methodName && it.desc == descriptor }?.let { mn ->
                return mn
            }
    }

    /** @return true if a class has a class initializer. */
    fun hasClassInitializer(className: String): Boolean {
        return findMethod(className, CLASS_INITIALIZER_NAME, CLASS_INITIALIZER_DESC) != null
    }

    /** Run the lambda on each class in alphabetical order. */
    fun forEach(consumer: (classNode: ClassNode) -> Unit) {
        val keys = mAllClasses.keys.toTypedArray()
        Arrays.sort(keys)

        for (name in keys) {
            consumer(mAllClasses[name]!!)
        }
    }

    /**
     * Dump all classes.
     */
    fun dump(pw: PrintWriter) {
        forEach { classNode -> dumpClass(pw, classNode) }
    }

    private fun dumpClass(pw: PrintWriter, cn: ClassNode) {
        pw.printf("Class: %s [access: %x]\n", cn.name, cn.access)
        dumpAnnotations(
            pw, "  ",
            cn.visibleTypeAnnotations, cn.invisibleTypeAnnotations,
            cn.visibleAnnotations, cn.invisibleAnnotations,
        )

        for (f in cn.fields ?: emptyList()) {
            pw.printf(
                "  Field: %s [sig: %s] [desc: %s] [access: %x]\n",
                f.name, f.signature, f.desc, f.access
            )
            dumpAnnotations(
                pw, "    ",
                f.visibleTypeAnnotations, f.invisibleTypeAnnotations,
                f.visibleAnnotations, f.invisibleAnnotations,
            )
        }
        for (m in cn.methods ?: emptyList()) {
            pw.printf(
                "  Method: %s [sig: %s] [desc: %s] [access: %x]\n",
                m.name, m.signature, m.desc, m.access
            )
            dumpAnnotations(
                pw, "    ",
                m.visibleTypeAnnotations, m.invisibleTypeAnnotations,
                m.visibleAnnotations, m.invisibleAnnotations,
            )
        }
    }

    private fun dumpAnnotations(
        pw: PrintWriter,
        prefix: String,
        visibleTypeAnnotations: List<TypeAnnotationNode>?,
        invisibleTypeAnnotations: List<TypeAnnotationNode>?,
        visibleAnnotations: List<AnnotationNode>?,
        invisibleAnnotations: List<AnnotationNode>?,
    ) {
        for (an in visibleTypeAnnotations ?: emptyList()) {
            pw.printf("%sTypeAnnotation(vis): %s\n", prefix, an.desc)
        }
        for (an in invisibleTypeAnnotations ?: emptyList()) {
            pw.printf("%sTypeAnnotation(inv): %s\n", prefix, an.desc)
        }
        for (an in visibleAnnotations ?: emptyList()) {
            pw.printf("%sAnnotation(vis): %s\n", prefix, an.desc)
            if (an.values == null) {
                continue
            }
            var i = 0
            while (i < an.values.size - 1) {
                pw.printf("%s  - %s -> %s \n", prefix, an.values[i], an.values[i + 1])
                i += 2
            }
        }
        for (an in invisibleAnnotations ?: emptyList()) {
            pw.printf("%sAnnotation(inv): %s\n", prefix, an.desc)
            if (an.values == null) {
                continue
            }
            var i = 0
            while (i < an.values.size - 1) {
                pw.printf("%s  - %s -> %s \n", prefix, an.values[i], an.values[i + 1])
                i += 2
            }
        }
    }

    companion object {
        /**
         * Load all the classes, without code.
         */
        fun loadClassStructures(
            inJar: String,
            timeCollector: Consumer<Double>? = null,
        ): ClassNodes {
            val allClasses = ClassNodes()

            // Load classes in parallel.
            val executor = Executors.newFixedThreadPool(4)

            // First exception defected.
            val exception = AtomicReference<Throwable>()

            // Called on a BG thread. Read a single jar entry and add it to [allClasses].
            fun parseClass(inZip: ZipFile, entry: ZipEntry) {
                try {
                    inZip.getInputStream(entry).use { ins ->
                        val cr = ClassReader(BufferedInputStream(ins))
                        val cn = ClassNode()
                        cr.accept(
                            cn, ClassReader.SKIP_CODE
                                    or ClassReader.SKIP_DEBUG
                                    or ClassReader.SKIP_FRAMES
                        )
                        synchronized(allClasses) {
                            if (!allClasses.addClass(cn)) {
                                log.w("Duplicate class found: ${cn.name}")
                            }
                        }
                    }
                } catch (e: Throwable) {
                    log.e("Failed to load class: $e")
                    exception.compareAndSet(null, e)
                }
            }

            // Actually open the jar and read it on worker threads.
            val time = log.iTime("Reading class structure from $inJar") {
                log.withIndent {
                    ZipFile(inJar).use { inZip ->
                        val inEntries = inZip.entries()

                        while (inEntries.hasMoreElements()) {
                            val entry = inEntries.nextElement()

                            if (entry.name.endsWith(".class")) {
                                executor.submit {
                                    parseClass(inZip, entry)
                                }
                            } else if (entry.name.endsWith(".dex")) {
                                // Seems like it's an ART jar file. We can't process it.
                                // It's a fatal error.
                                throw InvalidJarFileException(
                                    "$inJar is not a desktop jar file."
                                            + " It contains a *.dex file."
                                )
                            } else {
                                // Unknown file type. Skip.
                            }
                        }
                        // Wait for all the work to complete. (must do it before closing the zip)
                        log.i("Waiting for all loaders to finish...")
                        executor.shutdown()
                        executor.awaitTermination(5, TimeUnit.MINUTES)
                        log.i("All loaders to finished.")
                    }
                }

                // If any exception is detected, throw it.
                exception.get()?.let {
                    throw it
                }

                if (allClasses.size == 0) {
                    log.w("$inJar contains no *.class files.")
                } else {
                    log.i("Loaded ${allClasses.size} classes from $inJar.")
                }
            }
            timeCollector?.accept(time)
            return allClasses
        }
    }
}