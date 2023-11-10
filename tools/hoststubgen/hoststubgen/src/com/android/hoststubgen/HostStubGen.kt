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

import com.android.hoststubgen.asm.ClassNodes
import com.android.hoststubgen.filters.AnnotationBasedFilter
import com.android.hoststubgen.filters.ClassWidePolicyPropagatingFilter
import com.android.hoststubgen.filters.ConstantFilter
import com.android.hoststubgen.filters.DefaultHookInjectingFilter
import com.android.hoststubgen.filters.FilterPolicy
import com.android.hoststubgen.filters.ImplicitOutputFilter
import com.android.hoststubgen.filters.KeepAllClassesFilter
import com.android.hoststubgen.filters.OutputFilter
import com.android.hoststubgen.filters.StubIntersectingFilter
import com.android.hoststubgen.filters.createFilterFromTextPolicyFile
import com.android.hoststubgen.filters.printAsTextPolicy
import com.android.hoststubgen.utils.ClassFilter
import com.android.hoststubgen.visitors.BaseAdapter
import com.android.hoststubgen.visitors.PackageRedirectRemapper
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.util.CheckClassAdapter
import java.io.BufferedInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Actual main class.
 */
class HostStubGen(val options: HostStubGenOptions) {
    fun run() {
        val errors = HostStubGenErrors()

        // Load all classes.
        val allClasses = loadClassStructures(options.inJar)

        // Dump the classes, if specified.
        options.inputJarDumpFile?.let {
            PrintWriter(it).use { pw -> allClasses.dump(pw) }
            log.i("Dump file created at $it")
        }

        options.inputJarAsKeepAllFile?.let {
            PrintWriter(it).use {
                pw -> allClasses.forEach {
                    classNode -> printAsTextPolicy(pw, classNode)
                }
            }
            log.i("Dump file created at $it")
        }

        // Build the filters.
        val filter = buildFilter(errors, allClasses, options)

        // Transform the jar.
        convert(
                options.inJar,
                options.outStubJar,
                options.outImplJar,
                filter,
                options.enableClassChecker,
                allClasses,
                errors,
        )
    }

    /**
     * Load all the classes, without code.
     */
    private fun loadClassStructures(inJar: String): ClassNodes {
        log.i("Reading class structure from $inJar ...")
        val start = System.currentTimeMillis()

        val allClasses = ClassNodes()

        log.withIndent {
            ZipFile(inJar).use { inZip ->
                val inEntries = inZip.entries()

                while (inEntries.hasMoreElements()) {
                    val entry = inEntries.nextElement()

                    BufferedInputStream(inZip.getInputStream(entry)).use { bis ->
                        if (entry.name.endsWith(".class")) {
                            val cr = ClassReader(bis)
                            val cn = ClassNode()
                            cr.accept(cn, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG
                                    or ClassReader.SKIP_FRAMES)
                            if (!allClasses.addClass(cn)) {
                                log.w("Duplicate class found: ${cn.name}")
                            }
                        } else if (entry.name.endsWith(".dex")) {
                            // Seems like it's an ART jar file. We can't process it.
                            // It's a fatal error.
                            throw InvalidJarFileException(
                                    "$inJar is not a desktop jar file. It contains a *.dex file.")
                        } else {
                            // Unknown file type. Skip.
                            while (bis.available() > 0) {
                                bis.skip((1024 * 1024).toLong())
                            }
                        }
                    }
                }
            }
        }
        if (allClasses.size == 0) {
            log.w("$inJar contains no *.class files.")
        }

        val end = System.currentTimeMillis()
        log.v("Done reading class structure in %.1f second(s).", (end - start) / 1000.0)
        return allClasses
    }

    /**
     * Build the filter, which decides what classes/methods/fields should be put in stub or impl
     * jars, and "how". (e.g. with substitution?)
     */
    private fun buildFilter(
            errors: HostStubGenErrors,
            allClasses: ClassNodes,
            options: HostStubGenOptions,
            ): OutputFilter {
        // We build a "chain" of multiple filters here.
        //
        // The filters are build in from "inside", meaning the first filter created here is
        // the last filter used, so it has the least precedence.
        //
        // So, for example, the "remove" annotation, which is handled by AnnotationBasedFilter,
        // can override a class-wide annotation, which is handled by
        // ClassWidePolicyPropagatingFilter, and any annotations can be overridden by the
        // text-file based filter, which is handled by parseTextFilterPolicyFile.

        // The first filter is for the default policy from the command line options.
        var filter: OutputFilter = ConstantFilter(options.defaultPolicy, "default-by-options")

        // Next, we need a filter that resolves "class-wide" policies.
        // This is used when a member (methods, fields, nested classes) don't get any polices
        // from upper filters. e.g. when a method has no annotations, then this filter will apply
        // the class-wide policy, if any. (if not, we'll fall back to the above filter.)
        filter = ClassWidePolicyPropagatingFilter(filter)

        // Inject default hooks from options.
        filter = DefaultHookInjectingFilter(
            options.defaultClassLoadHook,
            options.defaultMethodCallHook,
            filter
        )

        val annotationAllowedClassesFilter = options.annotationAllowedClassesFile.let { filename ->
            if (filename == null) {
                ClassFilter.newNullFilter(true) // Allow all classes
            } else {
                ClassFilter.loadFromFile(filename, false)
            }
        }

        // Next, Java annotation based filter.
        filter = AnnotationBasedFilter(
            errors,
            allClasses,
            options.stubAnnotations,
            options.keepAnnotations,
            options.stubClassAnnotations,
            options.keepClassAnnotations,
            options.throwAnnotations,
            options.removeAnnotations,
            options.substituteAnnotations,
            options.nativeSubstituteAnnotations,
            options.classLoadHookAnnotations,
            options.keepStaticInitializerAnnotations,
            annotationAllowedClassesFilter,
            filter,
        )

        // Next, "text based" filter, which allows to override polices without touching
        // the target code.
        options.policyOverrideFile?.let {
            filter = createFilterFromTextPolicyFile(it, allClasses, filter)
        }

        // If `--intersect-stub-jar` is provided, load from these jar files too.
        // We use this to restrict stub APIs to public/system/test APIs,
        // by intersecting with a stub jar file created by metalava.
        if (options.intersectStubJars.size > 0) {
            val intersectingJars = loadIntersectingJars(options.intersectStubJars)

            filter = StubIntersectingFilter(errors, intersectingJars, filter)
        }

        // Apply the implicit filter.
        filter = ImplicitOutputFilter(errors, allClasses, filter)

        // Optionally keep all classes.
        if (options.keepAllClasses) {
            filter = KeepAllClassesFilter(filter)
        }

        return filter
    }

    /**
     * Load jar files specified with "--intersect-stub-jar".
     */
    private fun loadIntersectingJars(filenames: Set<String>): Map<String, ClassNodes> {
        val intersectingJars = mutableMapOf<String, ClassNodes>()

        filenames.forEach { filename ->
            intersectingJars[filename] = loadClassStructures(filename)
        }
        return intersectingJars
    }

    /**
     * Convert a JAR file into "stub" and "impl" JAR files.
     */
    private fun convert(
            inJar: String,
            outStubJar: String,
            outImplJar: String,
            filter: OutputFilter,
            enableChecker: Boolean,
            classes: ClassNodes,
            errors: HostStubGenErrors,
            ) {
        log.i("Converting %s into [stub: %s, impl: %s] ...", inJar, outStubJar, outImplJar)
        log.i("Checker is %s", if (enableChecker) "enabled" else "disabled")

        val start = System.currentTimeMillis()

        val packageRedirector = PackageRedirectRemapper(options.packageRedirects)

        log.withIndent {
            // Open the input jar file and process each entry.
            ZipFile(inJar).use { inZip ->
                ZipOutputStream(FileOutputStream(outStubJar)).use { stubOutStream ->
                    ZipOutputStream(FileOutputStream(outImplJar)).use { implOutStream ->
                        val inEntries = inZip.entries()
                        while (inEntries.hasMoreElements()) {
                            val entry = inEntries.nextElement()
                            convertSingleEntry(inZip, entry, stubOutStream, implOutStream,
                                    filter, packageRedirector, enableChecker, classes, errors)
                        }
                        log.i("Converted all entries.")
                    }
                }
                log.i("Created stub: $outStubJar")
                log.i("Created impl: $outImplJar")
            }
        }
        val end = System.currentTimeMillis()
        log.v("Done transforming the jar in %.1f second(s).", (end - start) / 1000.0)
    }

    /**
     * Convert a single ZIP entry, which may or may not be a class file.
     */
    private fun convertSingleEntry(
            inZip: ZipFile,
            entry: ZipEntry,
            stubOutStream: ZipOutputStream,
            implOutStream: ZipOutputStream,
            filter: OutputFilter,
            packageRedirector: PackageRedirectRemapper,
            enableChecker: Boolean,
            classes: ClassNodes,
            errors: HostStubGenErrors,
            ) {
        log.d("Entry: %s", entry.name)
        log.withIndent {
            val name = entry.name

            // Just ignore all the directories. (TODO: make sure it's okay)
            if (name.endsWith("/")) {
                return
            }

            // If it's a class, convert it.
            if (name.endsWith(".class")) {
                processSingleClass(inZip, entry, stubOutStream, implOutStream, filter,
                        packageRedirector, enableChecker, classes, errors)
                return
            }

            // Handle other file types...

            // - *.uau seems to contain hidden API information.
            // -  *_compat_config.xml is also about compat-framework.
            if (name.endsWith(".uau") ||
                    name.endsWith("_compat_config.xml")) {
                log.d("Not needed: %s", entry.name)
                return
            }

            // Unknown type, we just copy it to both output zip files.
            // TODO: We probably shouldn't do it for stub jar?
            log.v("Copying: %s", entry.name)
            copyZipEntry(inZip, entry, stubOutStream)
            copyZipEntry(inZip, entry, implOutStream)
        }
    }

    /**
     * Copy a single ZIP entry to the output.
     */
    private fun copyZipEntry(
            inZip: ZipFile,
            entry: ZipEntry,
            out: ZipOutputStream,
            ) {
        BufferedInputStream(inZip.getInputStream(entry)).use { bis ->
            // Copy unknown entries as is to the impl out. (but not to the stub out.)
            val outEntry = ZipEntry(entry.name)
            out.putNextEntry(outEntry)
            while (bis.available() > 0) {
                out.write(bis.read())
            }
            out.closeEntry()
        }
    }

    /**
     * Convert a single class to "stub" and "impl".
     */
    private fun processSingleClass(
            inZip: ZipFile,
            entry: ZipEntry,
            stubOutStream: ZipOutputStream,
            implOutStream: ZipOutputStream,
            filter: OutputFilter,
            packageRedirector: PackageRedirectRemapper,
            enableChecker: Boolean,
            classes: ClassNodes,
            errors: HostStubGenErrors,
            ) {
        val classInternalName = entry.name.replaceFirst("\\.class$".toRegex(), "")
        val classPolicy = filter.getPolicyForClass(classInternalName)
        if (classPolicy.policy == FilterPolicy.Remove) {
            log.d("Removing class: %s %s", classInternalName, classPolicy)
            return
        }
        // Generate stub first.
        if (classPolicy.policy.needsInStub) {
            log.v("Creating stub class: %s Policy: %s", classInternalName, classPolicy)
            log.withIndent {
                BufferedInputStream(inZip.getInputStream(entry)).use { bis ->
                    val newEntry = ZipEntry(entry.name)
                    stubOutStream.putNextEntry(newEntry)
                    convertClass(classInternalName, /*forImpl=*/false, bis,
                            stubOutStream, filter, packageRedirector, enableChecker, classes,
                            errors)
                    stubOutStream.closeEntry()
                }
            }
        }
        log.v("Creating impl class: %s Policy: %s", classInternalName, classPolicy)
        if (classPolicy.policy.needsInImpl) {
            log.withIndent {
                BufferedInputStream(inZip.getInputStream(entry)).use { bis ->
                    val newEntry = ZipEntry(entry.name)
                    implOutStream.putNextEntry(newEntry)
                    convertClass(classInternalName, /*forImpl=*/true, bis,
                            implOutStream, filter, packageRedirector, enableChecker, classes,
                            errors)
                    implOutStream.closeEntry()
                }
            }
        }
    }

    /**
     * Convert a single class to either "stub" or "impl".
     */
    private fun convertClass(
            classInternalName: String,
            forImpl: Boolean,
            input: InputStream,
            out: OutputStream,
            filter: OutputFilter,
            packageRedirector: PackageRedirectRemapper,
            enableChecker: Boolean,
            classes: ClassNodes,
            errors: HostStubGenErrors,
            ) {
        val cr = ClassReader(input)

        // COMPUTE_FRAMES wouldn't be happy if code uses
        val flags = ClassWriter.COMPUTE_MAXS // or ClassWriter.COMPUTE_FRAMES
        val cw = ClassWriter(flags)

        // Connect to the class writer
        var outVisitor: ClassVisitor = cw
        if (enableChecker) {
            outVisitor = CheckClassAdapter(outVisitor)
        }
        val visitorOptions = BaseAdapter.Options(
                enablePreTrace = options.enablePreTrace,
                enablePostTrace = options.enablePostTrace,
                enableNonStubMethodCallDetection = options.enableNonStubMethodCallDetection,
                errors = errors,
        )
        outVisitor = BaseAdapter.getVisitor(classInternalName, classes, outVisitor, filter,
                packageRedirector, forImpl, visitorOptions)

        cr.accept(outVisitor, ClassReader.EXPAND_FRAMES)
        val data = cw.toByteArray()
        out.write(data)
    }
}
