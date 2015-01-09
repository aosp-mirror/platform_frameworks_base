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

package com.android.databinding.parser

import kotlin.properties.Delegates
import java.util.HashSet
import com.android.databinding.vo.Variable
import com.android.databinding.util.Log
import com.android.databinding.util.ClassAnalyzer

public open class ExprModel {
    var start : Expr by Delegates.notNull()
    val variables = hashMapOf<String, VariableRef>()

    var rootVariables = HashSet<VariableRef>()

    var variableIdGenerator = 0;

    public fun pack() {
        variables.mapTo(rootVariables){it.value}
    }
    public fun getVariable(name : String) : VariableRef? = variables[name]

    public fun getOrCreateVariable(name : String, parent : VariableRef?) : VariableRef =
        variables.getOrElse(name, {
            Log.d("creating variable for $name")
            val variable = VariableRef(variableIdGenerator++, name, parent)
            variables.put(name, variable)
            variable
        })
}
abstract public class Expr {
    // ordered by depth
    var referencedVariables = arrayListOf<VariableRef>()

    val resolvedClass by Delegates.lazy {
        resolveValueType(ClassAnalyzer.instance)
    }

    fun setReferencedVariables(refs : Collection<VariableRef>) {
        referencedVariables.clear()
        refs.groupBy{it.depth}.toSortedMap().forEach { referencedVariables.addAll(it.value)}
    }
    abstract fun toReadableString() : String

    abstract fun toJava() : String

    abstract fun resolveValueType(classAnalyzer : ClassAnalyzer) : Class<*>
}

public class VariableRef(val id : Int, val fullName: String, val parent : VariableRef? = null) {
    var variable : Variable by Delegates.notNull()
    val depth : Int by Delegates.lazy {
        if (parent == null) {
            1
        } else {
            parent.depth + 1
        }
    }
    override fun toString(): String = "VariableRef(id=$id name=$fullName hasParent:${parent != null})"
}

public data class SymbolExpr(val text : String, val javaType : Class<out Any?>) : Expr() {
    override fun toJava(): String = text
    override fun toReadableString() = text
    override fun resolveValueType(classAnalyzer: ClassAnalyzer): Class<out Any?> {
        return javaType
    }

}

public data class FieldExpr(val rootVariable : VariableRef, val subVariables : List<VariableRef>) : Expr() {
    override fun resolveValueType(classAnalyzer: ClassAnalyzer): Class<out Any?> =
            if (subVariables.size == 0) rootVariable.variable.resolvedClass else subVariables.last!!.variable.resolvedClass

    override fun toJava(): String = if (subVariables.size == 0) rootVariable.variable.localName else subVariables.last!!.variable.localName

    override fun toReadableString(): String = if (subVariables.size == 0) rootVariable.fullName else subVariables.last!!.fullName
}

public data class GlobalMethodCallExpr(val methodName : String, val args : List<Expr>) : Expr() {
    override fun resolveValueType(classAnalyzer: ClassAnalyzer): Class<out Any?> {
        throw UnsupportedOperationException()
    }

    override fun toJava(): String {
        throw UnsupportedOperationException()
    }

    override fun toReadableString(): String = "$methodName(" + args.map { it.toReadableString() }.join(",") + ")"

}

public data class MethodCallExpr(val owner : Expr, val methodName : String, val args : List<Expr>) : Expr() {
    override fun resolveValueType(classAnalyzer: ClassAnalyzer): Class<out Any?> {
        val method = classAnalyzer.findMethod(owner.resolvedClass, methodName, args)
        return method.getReturnType()
    }
    override fun toJava(): String =
        "${owner.toJava()}.$methodName(${args.map{it.toJava()}.join(", ")})"

    override fun toReadableString(): String = "${owner.toReadableString()}.$methodName(" + args.map { it.toReadableString() }.join(",") + ")"

}

public data class TernaryExpr(val predicate : Expr, val ifTrue : Expr, val ifFalse : Expr) : Expr() {
    override fun resolveValueType(classAnalyzer: ClassAnalyzer): Class<out Any?> =
            classAnalyzer.commonParentOf(ifTrue.resolvedClass, ifFalse.resolvedClass)

    override fun toJava(): String = "${predicate.toJava()} ? ${ifTrue.toJava()} : ${ifFalse.toJava()}"

    override fun toReadableString(): String = "${predicate.toReadableString()} ? ${ifTrue.toReadableString()} : ${ifFalse.toReadableString()}"

}

public open data class OpExpr(val left : Expr, val op : String, val right : Expr) : Expr() {
    override fun toJava(): String = "${left.toJava()} $op ${right.toJava()}"

    override fun toReadableString(): String = "${left.toReadableString()} $op ${right.toReadableString()}"

    override fun resolveValueType(classAnalyzer: ClassAnalyzer): Class<out Any?> {
        return classAnalyzer.commonParentOf(left.resolvedClass, right.resolvedClass)
    }
}

public data class BitShiftOpExpr(left : Expr, op : String, right : Expr) : OpExpr(left, op, right) {
    override fun resolveValueType(classAnalyzer: ClassAnalyzer): Class<out Any?> = left.resolvedClass
}

public data class BinaryOpExpr(left : Expr, op : String, right : Expr) : OpExpr(left, op, right) {
    override fun resolveValueType(classAnalyzer: ClassAnalyzer): Class<out Any?> = left.resolvedClass
}

public data class ComparisonOpExpr(left : Expr, op : String, right : Expr) : OpExpr(left, op, right) {
    override fun resolveValueType(classAnalyzer: ClassAnalyzer): Class<out Any?> = javaClass<Boolean>()
}

public data class AndOrOpExpr(left : Expr, op : String, right : Expr) : OpExpr(left, op, right) {
    override fun resolveValueType(classAnalyzer: ClassAnalyzer): Class<out Any?> = javaClass<Boolean>()
}
