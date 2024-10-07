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
import com.android.hoststubgen.dumper.ApiDumper
import com.android.hoststubgen.filters.AnnotationBasedFilter
import com.android.hoststubgen.filters.ClassWidePolicyPropagatingFilter
import com.android.hoststubgen.filters.ConstantFilter
import com.android.hoststubgen.filters.DefaultHookInjectingFilter
import com.android.hoststubgen.filters.FilterPolicy
import com.android.hoststubgen.filters.FilterRemapper
import com.android.hoststubgen.filters.ImplicitOutputFilter
import com.android.hoststubgen.filters.KeepNativeFilter
import com.android.hoststubgen.filters.OutputFilter
import com.android.hoststubgen.filters.SanitizationFilter
import com.android.hoststubgen.filters.createFilterFromTextPolicyFile
import com.android.hoststubgen.filters.printAsTextPolicy
import com.android.hoststubgen.utils.ClassFilter
import com.android.hoststubgen.visitors.BaseAdapter
import com.android.hoststubgen.visitors.PackageRedirectRemapper
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import org.objectweb.asm.util.CheckClassAdapter

/**
 * Actual main class.
 */
class HostStubGen(val options: HostStubGenOptions) {
    fun run() {
        val errors = HostStubGenErrors()
        val stats = HostStubGenStats()

        // Load all classes.
        val allClasses = ClassNodes.loadClassStructures(options.inJar.get)

        // Dump the classes, if specified.
        options.inputJarDumpFile.ifSet {
            log.iTime("Dump file created at $it") {
                PrintWriter(it).use { pw -> allClasses.dump(pw) }
            }
        }

        options.inputJarAsKeepAllFile.ifSet {
            log.iTime("Dump file created at $it") {
                PrintWriter(it).use { pw ->
                    allClasses.forEach { classNode ->
                        printAsTextPolicy(pw, classNode)
                    }
                }
            }
        }

        // Build the filters.
        val filter = buildFilter(errors, allClasses, options)

        val filterRemapper = FilterRemapper(filter)

        // Transform the jar.
        convert(
            options.inJar.get,
            options.outJar.get,
            filter,
            options.enableClassChecker.get,
            allClasses,
            errors,
            stats,
            filterRemapper,
            options.numShards.get,
            options.shard.get,
        )

        // Dump statistics, if specified.
        options.statsFile.ifSet {
            log.iTime("Dump file created at $it") {
                PrintWriter(it).use { pw -> stats.dumpOverview(pw) }
            }
        }
        options.apiListFile.ifSet {
            log.iTime("API list file created at $it") {
                PrintWriter(it).use { pw ->
                    // TODO, when dumping a jar that's not framework-minus-apex.jar, we need to feed
                    // framework-minus-apex.jar so that we can dump inherited methods from it.
                    ApiDumper(pw, allClasses, null, filter).dump()
                }
            }
        }
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
        var filter: OutputFilter = ConstantFilter(options.defaultPolicy.get, "default-by-options")

        // Next, we build a filter that preserves all native methods by default
        filter = KeepNativeFilter(allClasses, filter)

        // Next, we need a filter that resolves "class-wide" policies.
        // This is used when a member (methods, fields, nested classes) don't get any polices
        // from upper filters. e.g. when a method has no annotations, then this filter will apply
        // the class-wide policy, if any. (if not, we'll fall back to the above filter.)
        filter = ClassWidePolicyPropagatingFilter(allClasses, filter)

        // Inject default hooks from options.
        filter = DefaultHookInjectingFilter(
            options.defaultClassLoadHook.get,
            options.defaultMethodCallHook.get,
            filter
        )

        val annotationAllowedClassesFilter = options.annotationAllowedClassesFile.get.let { file ->
            if (file == null) {
                ClassFilter.newNullFilter(true) // Allow all classes
            } else {
                ClassFilter.loadFromFile(file, false)
            }
        }

        // Next, Java annotation based filter.
        filter = AnnotationBasedFilter(
            errors,
            allClasses,
            options.keepAnnotations,
            options.keepClassAnnotations,
            options.throwAnnotations,
            options.removeAnnotations,
            options.ignoreAnnotations,
            options.substituteAnnotations,
            options.redirectAnnotations,
            options.redirectionClassAnnotations,
            options.classLoadHookAnnotations,
            options.keepStaticInitializerAnnotations,
            annotationAllowedClassesFilter,
            filter
        )

        // Next, "text based" filter, which allows to override polices without touching
        // the target code.
        options.policyOverrideFile.ifSet {
            filter = createFilterFromTextPolicyFile(it, allClasses, filter)
        }

        // Apply the implicit filter.
        filter = ImplicitOutputFilter(errors, allClasses, filter)

        // Add a final sanitization step.
        filter = SanitizationFilter(errors, allClasses, filter)

        return filter
    }

    /**
     * Convert a JAR file into "stub" and "impl" JAR files.
     */
    private fun convert(
        inJar: String,
        outJar: String?,
        filter: OutputFilter,
        enableChecker: Boolean,
        classes: ClassNodes,
        errors: HostStubGenErrors,
        stats: HostStubGenStats,
        remapper: Remapper?,
        numShards: Int,
        shard: Int
    ) {
        log.i("Converting %s into %s ...", inJar, outJar)
        log.i("ASM CheckClassAdapter is %s", if (enableChecker) "enabled" else "disabled")

        log.iTime("Transforming jar") {
            val packageRedirector = PackageRedirectRemapper(options.packageRedirects)

            var itemIndex = 0
            var numItemsProcessed = 0
            var numItems = -1 // == Unknown

            log.withIndent {
                // Open the input jar file and process each entry.
                ZipFile(inJar).use { inZip ->

                    numItems = inZip.size()
                    val shardStart = numItems * shard / numShards
                    val shardNextStart = numItems * (shard + 1) / numShards

                    maybeWithZipOutputStream(outJar) { outStream ->
                        val inEntries = inZip.entries()
                        while (inEntries.hasMoreElements()) {
                            val entry = inEntries.nextElement()
                            val inShard = (shardStart <= itemIndex)
                                    && (itemIndex < shardNextStart)
                            itemIndex++
                            if (!inShard) {
                                continue
                            }
                            convertSingleEntry(
                                inZip, entry, outStream, filter,
                                packageRedirector, remapper, enableChecker,
                                classes, errors, stats
                            )
                            numItemsProcessed++
                        }
                        log.i("Converted all entries.")
                    }
                    outJar?.let { log.i("Created: $it") }
                }
            }
            log.i("%d / %d item(s) processed.", numItemsProcessed, numItems)
        }
    }

    private fun <T> maybeWithZipOutputStream(filename: String?, block: (ZipOutputStream?) -> T): T {
        if (filename == null) {
            return block(null)
        }
        return ZipOutputStream(BufferedOutputStream(FileOutputStream(filename))).use(block)
    }

    /**
     * Convert a single ZIP entry, which may or may not be a class file.
     */
    private fun convertSingleEntry(
        inZip: ZipFile,
        entry: ZipEntry,
        outStream: ZipOutputStream?,
        filter: OutputFilter,
        packageRedirector: PackageRedirectRemapper,
        remapper: Remapper?,
        enableChecker: Boolean,
        classes: ClassNodes,
        errors: HostStubGenErrors,
        stats: HostStubGenStats
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
                processSingleClass(
                    inZip, entry, outStream, filter, packageRedirector,
                    remapper, enableChecker, classes, errors, stats
                )
                return
            }

            // Handle other file types...

            // - *.uau seems to contain hidden API information.
            // -  *_compat_config.xml is also about compat-framework.
            if (name.endsWith(".uau") || name.endsWith("_compat_config.xml")) {
                log.d("Not needed: %s", entry.name)
                return
            }

            // Unknown type, we just copy it to both output zip files.
            log.v("Copying: %s", entry.name)
            outStream?.let { copyZipEntry(inZip, entry, it) }
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
        // TODO: It seems like copying entries this way is _very_ slow,
        // even with out.setLevel(0). Look for other ways to do it.

        inZip.getInputStream(entry).use { ins ->
            // Copy unknown entries as is to the impl out. (but not to the stub out.)
            val outEntry = ZipEntry(entry.name)
            out.putNextEntry(outEntry)
            ins.transferTo(out)
            out.closeEntry()
        }
    }

    /**
     * Convert a single class to "stub" and "impl".
     */
    private fun processSingleClass(
        inZip: ZipFile,
        entry: ZipEntry,
        outStream: ZipOutputStream?,
        filter: OutputFilter,
        packageRedirector: PackageRedirectRemapper,
        remapper: Remapper?,
        enableChecker: Boolean,
        classes: ClassNodes,
        errors: HostStubGenErrors,
        stats: HostStubGenStats
    ) {
        val classInternalName = entry.name.replaceFirst("\\.class$".toRegex(), "")
        val classPolicy = filter.getPolicyForClass(classInternalName)
        if (classPolicy.policy == FilterPolicy.Remove) {
            log.d("Removing class: %s %s", classInternalName, classPolicy)
            return
        }
        // If we're applying a remapper, we need to rename the file too.
        var newName = entry.name
        remapper?.mapType(classInternalName)?.let { remappedName ->
            if (remappedName != classInternalName) {
                log.d("Renaming class file: %s -> %s", classInternalName, remappedName)
                newName = "$remappedName.class"
            }
        }

        if (outStream != null) {
            log.v("Creating class: %s Policy: %s", classInternalName, classPolicy)
            log.withIndent {
                BufferedInputStream(inZip.getInputStream(entry)).use { bis ->
                    val newEntry = ZipEntry(newName)
                    outStream.putNextEntry(newEntry)
                    convertClass(
                        classInternalName, bis,
                        outStream, filter, packageRedirector, remapper,
                        enableChecker, classes, errors, stats
                    )
                    outStream.closeEntry()
                }
            }
        }
    }

    /**
     * Convert a single class to either "stub" or "impl".
     */
    private fun convertClass(
        classInternalName: String,
        input: InputStream,
        out: OutputStream,
        filter: OutputFilter,
        packageRedirector: PackageRedirectRemapper,
        remapper: Remapper?,
        enableChecker: Boolean,
        classes: ClassNodes,
        errors: HostStubGenErrors,
        stats: HostStubGenStats?
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

        // Remapping should happen at the end.
        remapper?.let {
            outVisitor = ClassRemapper(outVisitor, remapper)
        }

        val visitorOptions = BaseAdapter.Options(
            errors = errors,
            stats = stats,
            enablePreTrace = options.enablePreTrace.get,
            enablePostTrace = options.enablePostTrace.get,
        )
        outVisitor = BaseAdapter.getVisitor(
            classInternalName, classes, outVisitor, filter,
            packageRedirector, visitorOptions
        )

        cr.accept(outVisitor, ClassReader.EXPAND_FRAMES)
        val data = cw.toByteArray()
        out.write(data)
    }
}
