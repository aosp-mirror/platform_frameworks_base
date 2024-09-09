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
package com.android.hoststubgen.visitors

import com.android.hoststubgen.HostStubGenErrors
import com.android.hoststubgen.HostStubGenStats
import com.android.hoststubgen.LogLevel
import com.android.hoststubgen.asm.ClassNodes
import com.android.hoststubgen.asm.UnifiedVisitor
import com.android.hoststubgen.asm.getPackageNameFromFullClassName
import com.android.hoststubgen.filters.FilterPolicy
import com.android.hoststubgen.filters.FilterPolicyWithReason
import com.android.hoststubgen.filters.OutputFilter
import com.android.hoststubgen.hosthelper.HostStubGenProcessedAsKeep
import com.android.hoststubgen.log
import java.io.PrintWriter
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.util.TraceClassVisitor

const val OPCODE_VERSION = Opcodes.ASM9

abstract class BaseAdapter(
    protected val classes: ClassNodes,
    nextVisitor: ClassVisitor,
    protected val filter: OutputFilter,
    protected val options: Options,
) : ClassVisitor(OPCODE_VERSION, nextVisitor) {

    /**
     * Options to control the behavior.
     */
    data class Options(
        val errors: HostStubGenErrors,
        val stats: HostStubGenStats?,
        val enablePreTrace: Boolean,
        val enablePostTrace: Boolean
    )

    protected lateinit var currentPackageName: String
    protected lateinit var currentClassName: String
    protected var redirectionClass: String? = null
    protected lateinit var classPolicy: FilterPolicyWithReason

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<String>,
    ) {
        super.visit(version, access, name, signature, superName, interfaces)
        currentClassName = name
        currentPackageName = getPackageNameFromFullClassName(name)
        classPolicy = filter.getPolicyForClass(currentClassName)
        redirectionClass = filter.getRedirectionClass(currentClassName)

        log.d("[%s] visit: %s (package: %s)", this.javaClass.simpleName, name, currentPackageName)
        log.indent()
        log.v("Emitting class: %s", name)
        log.indent()

        // Inject annotations to generated classes.
        UnifiedVisitor.on(this).visitAnnotation(HostStubGenProcessedAsKeep.CLASS_DESCRIPTOR, true)
    }

    override fun visitEnd() {
        log.unindent()
        log.unindent()
        super.visitEnd()
    }

    var skipMemberModificationNestCount = 0

    /**
     * This method allows writing class members without any modifications.
     */
    protected inline fun writeRawMembers(callback: () -> Unit) {
        skipMemberModificationNestCount++
        try {
            callback()
        } finally {
            skipMemberModificationNestCount--
        }
    }

    override fun visitField(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        value: Any?,
    ): FieldVisitor? {
        if (skipMemberModificationNestCount > 0) {
            return super.visitField(access, name, descriptor, signature, value)
        }
        val policy = filter.getPolicyForField(currentClassName, name)
        log.d("visitField: %s %s [%x] Policy: %s", name, descriptor, access, policy)

        log.withIndent {
            if (policy.policy == FilterPolicy.Remove) {
                log.d("Removing %s %s", name, policy)
                return null
            }

            log.v("Emitting field: %s %s %s", name, descriptor, policy)
            val ret = super.visitField(access, name, descriptor, signature, value)

            UnifiedVisitor.on(ret)
                .visitAnnotation(HostStubGenProcessedAsKeep.CLASS_DESCRIPTOR, true)

            return ret
        }
    }

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<String>?,
    ): MethodVisitor? {
        if (skipMemberModificationNestCount > 0) {
            return super.visitMethod(access, name, descriptor, signature, exceptions)
        }
        val p = filter.getPolicyForMethod(currentClassName, name, descriptor)
        log.d("visitMethod: %s%s [%x] [%s] Policy: %s", name, descriptor, access, signature, p)
        options.stats?.onVisitPolicyForMethod(currentClassName, name, descriptor, p, access)

        log.withIndent {
            // If it's a substitute-from method, then skip (== remove).
            // Instead of this method, we rename the substitute-to method with the original
            // name, in the "Maybe rename the method" part below.
            val policy = filter.getPolicyForMethod(currentClassName, name, descriptor)
            if (policy.policy == FilterPolicy.Substitute) {
                log.d("Skipping %s%s %s", name, descriptor, policy)
                return null
            }
            if (p.policy == FilterPolicy.Remove) {
                log.d("Removing %s%s %s", name, descriptor, policy)
                return null
            }

            var newAccess = access

            // Maybe rename the method.
            val newName: String
            val renameTo = filter.getRenameTo(currentClassName, name, descriptor)
            if (renameTo != null) {
                newName = renameTo

                // It's confusing, but here, `newName` is the original method name
                // (the one with the @substitute/replace annotation).
                // `name` is the name of the method we're currently visiting, so it's usually a
                // "...$ravewnwood" name.
                newAccess = checkSubstitutionMethodCompatibility(
                    classes, currentClassName, newName, name, descriptor, options.errors
                )
                if (newAccess == NOT_COMPATIBLE) {
                    return null
                }

                log.v(
                    "Emitting %s.%s%s as %s %s", currentClassName, name, descriptor,
                    newName, policy
                )
            } else {
                log.v("Emitting method: %s%s %s", name, descriptor, policy)
                newName = name
            }

            // Let subclass update the flag.
            // But note, we only use it when calling the super's method,
            // but not for visitMethodInner(), because when subclass wants to change access,
            // it can do so inside visitMethodInner().
            newAccess = updateAccessFlags(newAccess, name, descriptor, policy.policy)

            val ret = visitMethodInner(
                access, newName, descriptor, signature, exceptions, policy,
                renameTo != null,
                super.visitMethod(newAccess, newName, descriptor, signature, exceptions)
            )

            ret?.let {
                UnifiedVisitor.on(ret)
                    .visitAnnotation(HostStubGenProcessedAsKeep.CLASS_DESCRIPTOR, true)
            }

            return ret
        }
    }

    open fun updateAccessFlags(
        access: Int,
        name: String,
        descriptor: String,
        policy: FilterPolicy,
    ): Int {
        return access
    }

    abstract fun visitMethodInner(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<String>?,
        policy: FilterPolicyWithReason,
        substituted: Boolean,
        superVisitor: MethodVisitor?,
    ): MethodVisitor?

    companion object {
        fun getVisitor(
            classInternalName: String,
            classes: ClassNodes,
            nextVisitor: ClassVisitor,
            filter: OutputFilter,
            packageRedirector: PackageRedirectRemapper,
            options: Options,
        ): ClassVisitor {
            var next = nextVisitor

            val verbosePrinter = PrintWriter(log.getWriter(LogLevel.Verbose))

            // Inject TraceClassVisitor for debugging.
            if (options.enablePostTrace) {
                next = TraceClassVisitor(next, verbosePrinter)
            }

            // Handle --package-redirect
            if (!packageRedirector.isEmpty) {
                // Don't apply the remapper on redirect-from classes.
                // Otherwise, if the target jar actually contains the "from" classes (which
                // may or may not be the case) they'd be renamed.
                // But we update all references in other places, so, a method call to a "from" class
                // would be replaced with the "to" class. All type references (e.g. variable types)
                // will be updated too.
                if (!packageRedirector.isTarget(classInternalName)) {
                    next = ClassRemapper(next, packageRedirector)
                } else {
                    log.v(
                        "Class $classInternalName is a redirect-from class, not applying" +
                                " --package-redirect"
                    )
                }
            }

            next = ImplGeneratingAdapter(classes, next, filter, options)

            // Inject TraceClassVisitor for debugging.
            if (options.enablePreTrace) {
                next = TraceClassVisitor(next, verbosePrinter)
            }
            return next
        }
    }
}
