package com.android.sdkparcelables

import org.objectweb.asm.ClassVisitor
import java.util.*

data class Ancestors(val superName: String?, val interfaces: List<String>?)

/** A class that implements an ASM ClassVisitor that collects super class and
 * implemented interfaces for each class that it visits.
 */
class AncestorCollector(api: Int, dest: ClassVisitor?) : ClassVisitor(api, dest) {
    private val _ancestors = LinkedHashMap<String, Ancestors>()

    val ancestors: Map<String, Ancestors>
        get() = _ancestors

    override fun visit(version: Int, access: Int, name: String?, signature: String?,
                       superName: String?, interfaces: Array<out String>?) {
        name!!

        val old = _ancestors.put(name, Ancestors(superName, interfaces?.toList()))
        if (old != null) {
            throw RuntimeException("class $name already found")
        }

        super.visit(version, access, name, signature, superName, interfaces)
    }
}