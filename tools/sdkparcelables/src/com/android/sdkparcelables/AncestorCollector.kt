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
