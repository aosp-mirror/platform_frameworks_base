/*
 * Copyright (C) 2015 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.databinding.writer

import java.util.BitSet

class KCode (private val s : String? = null){

    private var sameLine = false

    class Appendix(val glue : String, val code : KCode)

    private val nodes = arrayListOf<Any>()

    class object {
        private val cachedIndentations = BitSet()
        private val indentCache = arrayListOf<String>()
        fun indent(n: Int): String {
            if (cachedIndentations.get(n)) {
                return indentCache.get(n)
            }
            val s = (0..n-1).fold(""){prev, next -> "${prev}    "}
            cachedIndentations.set(n, true )
            while (indentCache.size() <= n) {
                indentCache.add("");
            }
            indentCache.set(n, s)
            return s
        }
    }

    fun isNull(kcode : KCode?) = kcode == null || (kcode.nodes.isEmpty() && (kcode.s == null || kcode.s.trim() == ""))

    fun tab(vararg codes : KCode?) : KCode {
        codes.forEach { tab(it) }
        return this
    }

    fun tab(codes : Collection<KCode?> ) : KCode {
        codes.forEach { tab(it) }
        return this
    }

    fun tab(s : String?, init : (KCode.() -> Unit)? = null) : KCode {
        val c = KCode(s)
        if (init != null) {
            c.init()
        }
        return tab(c)
    }

    private fun tab(c : KCode?) : KCode {
        if (isNull(c)) {
            return this
        }
        nodes.add(c)
        return this
    }

    fun nls(vararg codes : KCode?) : KCode {
        codes.forEach { nl(it) }
        return this
    }

    fun nls(codes : Collection<KCode?>) : KCode {
        codes.forEach { nl(it) }
        return this
    }

    fun nl(c : KCode?) : KCode {
        if (isNull(c)) {
            return this
        }
        nodes.add(c)
        c!!.sameLine = true
        return this
    }

    fun nl(s : String?, init : (KCode.() -> Unit)? = null) : KCode {
        val c = KCode(s)
        if (init != null) {
            c.init()
        }
        return nl(c)
    }

    fun apps(glue : String = "", vararg codes : KCode?) : KCode {
        codes.forEach { app(glue, it)}
        return this
    }

    fun apps(glue : String = "", codes : Collection<KCode?>) : KCode {
        codes.forEach { app(glue, it)}
        return this
    }

    fun app(glue : String = "", c : KCode?) : KCode {
        if (isNull(c)) {
            return this
        }
        nodes.add(Appendix(glue, c!!))
        return this
    }

    fun app(s : String) : KCode {
        val c = KCode(s)
        return app("", c)
    }

    fun app(glue : String = "", s : String?, init : (KCode.() -> Unit)? = null) : KCode {
        val c = KCode(s)
        if (init != null) {
            c.init()
        }
        return app(glue, c)
    }


    fun toS(n : Int, sb : StringBuilder) {
        if (s != null) {
            sb.append(s)
        }
        val newlineFirstNode = s != null || (nodes.isNotEmpty() && nodes.first() is Appendix)
        var addedChild = false
        nodes.forEach { when(it) {
            is Appendix -> {
                sb.append(it.glue)
                it.code.toS(n, sb)
            }
            is KCode -> {
                val childTab = n + (if(it.sameLine) 0 else 1)
                if (addedChild || newlineFirstNode) {
                    sb.append(System.lineSeparator())
                    sb.append("${indent(childTab)}")
                }
                it.toS(childTab, sb)
                addedChild = true
            }
        } }

    }

    fun generate() : String {
        val sb = StringBuilder()
        toS(0, sb)
        return sb.toString()
    }
}

fun kcode(s : String?, init : (KCode.() -> Unit)? = null) : KCode {
    val c = KCode(s)
    if (init != null) {
        c.init()
    }
    return c
}