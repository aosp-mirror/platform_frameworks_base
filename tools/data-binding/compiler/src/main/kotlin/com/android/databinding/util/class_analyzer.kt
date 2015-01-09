/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.databinding.util

import java.net.URLClassLoader
import kotlin.properties.Delegates
import com.android.databinding.ext.toCamelCase
import com.android.databinding.util.Log
import java.lang.reflect.Modifier
import com.android.databinding.parser.Expr
import java.lang.reflect.Method
import kotlin.reflect.jvm.*

public fun Class<*>.isObservable() : Boolean = ClassAnalyzer.instance.observable.isAssignableFrom(this)

public fun Class<*>.getCodeName() : String = getName().replace("$", ".")

public fun Method.canBeCalledWith(name : String, args : List<Class<*>>) : Boolean {
    // TODO this is dumb, we need arg checking
    if (name != getName()) {
        return false
    }
    val params = getParameterTypes()
    if (args.size() != params.size()) {
        return false
    }
    return true
}


public fun Class<*>.findMethodWithAutoCasting(name : String, args : List<Class<*>>) : Method {
    return getMethods().first { it.canBeCalledWith(name, args) }
}

public fun Class<*>.getMethodOrField(identifier : String) : Pair<String, String> {
    val getterName = "get${identifier.toCamelCase()}"
    try {
        val method = getDeclaredMethod(getterName)
        if (Modifier.isPublic(method.getModifiers())) {
            return Pair("$getterName()", method.getReturnType().getCodeName())
        }
    } catch(t : Throwable){
        Log.d("exp: ${t.getMessage()}")
    }
    try {
        val field = getDeclaredField(identifier)
        if (Modifier.isPublic(field.getModifiers())) {
            return Pair("$identifier", field.getType().getCodeName())
        }
    } catch(t : Throwable){
        Log.d("exp: ${t.getMessage()}")
    }
    throw IllegalArgumentException("cannot find $identifier or $getterName() on ${this.getSimpleName()}")
}

class ClassAnalyzer(val classLoader : URLClassLoader) {
    {
        instance = this
    }
    val loadedClasses = hashMapOf<String, Class<*>>()
    val observable = loadClass("com.android.databinding.library.Observable");
    val VOID by Delegates.lazy {
        loadClass("java.lang.Void")
    }

    val STRING  = javaClass<String>()

    val LONG = javaClass<Long>()

    val INTEGER = javaClass<Integer>()

    val DOUBLE = javaClass<Double>()

    val FLOAT = javaClass<Float>()

    val pLONG = javaClass<kotlin.Long>()

    val pINTEGER = javaClass<kotlin.Int>()

    val pDOUBLE = javaClass<kotlin.Double>()

    val pFLOAT = javaClass<kotlin.Float>()

    private val CLASS_PRIORITY = arrayListOf(STRING, DOUBLE, pDOUBLE, FLOAT, pFLOAT, LONG, pLONG, INTEGER, pINTEGER)

    class object {
        var instance : ClassAnalyzer by Delegates.notNull()
    }

    fun loadPrimitive(className : String) : Class<*>? {
        if("int" == className) {
            return javaClass<kotlin.Int>()
        }
        if("short".equals(className)) {
            return javaClass<kotlin.Short>()
        }
        if("long".equals(className)) {
            return javaClass<kotlin.Long>()
        }
        if("float".equals(className)) {
            return javaClass<kotlin.Float>()
        }
        if("double".equals(className)) {
            return javaClass<kotlin.Double>()
        }
        if("boolean".equals(className) || "bool".equals(className)) {
            return javaClass<kotlin.Boolean>()
        }
        return null
    }

    fun getDefaultValue(className : String) : String {
        if("int" == className) {
            return "0";
        }
        if("short".equals(className)) {
            return "0";
        }
        if("long".equals(className)) {
            return "0L"
        }
        if("float".equals(className)) {
            return "0fL";
        }
        if("double".equals(className)) {
            return "0.0";
        }
        if("boolean".equals(className)) {
            return "false";
        }
        return "null";
    }

    fun loadClass(klassName : String) : Class<*> {
        var loaded = loadedClasses[klassName] ?: loadPrimitive(klassName)
        if (loaded == null) {
            loaded = loadRecursively(klassName)
            System.out.println("loaded ${loaded}")
            loaded!!.getInterfaces().forEach {
                System.out.println("interfaces ${it}")
            }
            loadedClasses.put(klassName, loaded)
        }
        return loaded!!
    }

    fun loadRecursively(klassName : String) : Class<*> {
        System.out.println("trying to find class ${klassName}")
        try {
            return classLoader.loadClass(klassName)
        } catch(ex : ClassNotFoundException) {
            val lastIndexOfDot = klassName.lastIndexOf(".")
            if (lastIndexOfDot == -1) {
                throw ex;
            }
            return loadRecursively("${klassName.substring(0, lastIndexOfDot)}\$${klassName.substring(lastIndexOfDot + 1)}")
        }
    }

    fun findMethod(klass: Class<out Any?>, methodName: String, args: List<Expr>): Method {
        val argClasses = args.map { it.resolvedClass }

        try {
            return klass.findMethodWithAutoCasting(methodName, argClasses)
        } catch(t:Throwable) {
            val msg = "Could not find method with name $methodName and args $args as converted to ${argClasses.map { it }.joinToString(",")}"
            Log.d(msg)
            throw RuntimeException(msg)
        }
    }

    fun commonParentOf(klass1 : Class<*>, klass2 : Class<*>): Class<*> {
        val clazz = CLASS_PRIORITY.firstOrNull { klass1 == it || klass2 == it }
        if (clazz == null) {
            throw RuntimeException("cannot find common parent for $klass1 and $klass2")
        }
        return clazz
    }
}