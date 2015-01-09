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

package com.android.databinding.vo

import com.android.databinding.parser.ExprModel
import kotlin.properties.Delegates
import org.w3c.dom.Node
import com.android.databinding.parser.Expr
import java.util.TreeMap
import com.android.databinding.parser.VariableRef
import com.android.databinding.ext.toCamelCase
import com.android.databinding.ext.joinToCamelCase
import com.android.databinding.ext.extractAndroidId
import com.android.databinding.ext.getAndroidIdPath
import com.android.databinding.util.ClassAnalyzer
import com.android.databinding.util.getMethodOrField
import com.android.databinding.util.Log
import com.android.databinding.ext.joinToCamelCaseAsVar
import com.android.databinding.util.isObservable
import com.android.databinding.annotationprocessor.BindingAdapterStore

class Binding(val target : BindingTarget, val attr : String, val targetFieldName : String,
        val expr : Expr) {
    // which variables effect the result of this binding
    // ordered by depth
    val relatedVariables : List<Variable> by Delegates.lazy {
        expr.referencedVariables.map {it.variable}
    }

    val isDirtyExpr : String by Delegates.lazy {
        relatedVariables.map {it.dirtyFlagName}.join(" | ")
    }

    val setter by Delegates.lazy {
        val viewType = ClassAnalyzer.instance.loadClass(target.viewClass);
        BindingAdapterStore.get().getSetterCall(attr, viewType, expr.resolvedClass,
                target.resolvedViewName, expr.toJava(), ClassAnalyzer.instance.classLoader)
    }

    val isDirtyName by Delegates.lazy {"sDirtyFlag${target.resolvedUniqueName}_${targetFieldName.capitalize()}"}
}

data class BindingTarget(val node: Node, val id: String, val viewClass: String) {
    val bindings = arrayListOf<Binding>()
    public fun addBinding(attr : String, fieldName : String, expr : Expr) {
        bindings.add(Binding(this, attr, fieldName, expr))
    }
    val rId:String by Delegates.lazy { "R.id.$idName" }
    val idName by Delegates.lazy { id.extractAndroidId() }
    var resolvedUniqueName : String by Delegates.notNull()
    val resolvedViewName by Delegates.lazy{"m${resolvedUniqueName}"}
    val idNamePath by Delegates.lazy {node.getAndroidIdPath(false).reverse().map {it.extractAndroidId()}}
    val findViewById by Delegates.lazy {
        idNamePath.map {"findViewById(R.id.$it)"}.join(".")
    }
}

data class Variable(val fullName : String, val parent : Variable? = null) {
    var static : Boolean = false
    var localId : Int by Delegates.notNull() // assigned if and only if variable is non-static observable
    val path = fullName.split("\\.")
    val name = path.last()
    var uniqueName  = fullName // TODO change if we support variables w/ the same name
    var klassName : String by Delegates.notNull()
    val children = arrayListOf<Variable>()
    val observableChildren by Delegates.lazy {children.filter{it.isObservable}}
    val descendents : List<Variable> by Delegates.lazy {children + children.flatMap{it.descendents}}
    val observableDescendents : List<Variable> by Delegates.lazy {descendents.filter{it.isObservable}}
    var isRootVariable = false
    val defaultValue by Delegates.lazy { ClassAnalyzer.instance.getDefaultValue(klassName)}
    val resolvedClass : Class<*> by Delegates.lazy {
        ClassAnalyzer.instance.loadClass(klassName)
    }
    val getter : String by Delegates.lazy {
        if (parent == null) {
            name
        } else {
            val pair = parent.resolvedClass.getMethodOrField(name)
            klassName = pair.second
            pair.first
        }
    }
    val fullCamelCase = uniqueName.split("\\.").joinToCamelCase()
    // has to be lazy to get up to date static value
    // TODO handle final pbulic instead!
    val localName by Delegates.lazy {if (static) getterGlobal else "${uniqueName.split("\\.").joinToCamelCaseAsVar()}" }
    val localIdName by Delegates.lazy { if(localId > -1) "s$fullCamelCase" else "<ERROR>" }//just adding a reference to localId to detect errors
    val dirtyFlag : Int by Delegates.lazy { (Math.pow(2.toDouble(), localId.toDouble())).toInt() }
    val dirtyFlagName by Delegates.lazy {if (static) "0" else "flag$fullCamelCase"}
    val variablePath : List<Variable> by Delegates.lazy {
        if (parent == null) arrayListOf(this) else parent.variablePath + arrayListOf(this)
    }
    val isDirtyExpr : String by Delegates.lazy {
        variablePath.map {it.dirtyFlagName}.join(" | ")
    }

    val childrenDirtyFlagsExpr: String by Delegates.lazy {
        (children.map{it.childrenDirtyFlagsExpr } + arrayListOf(dirtyFlagName)).join(" | ")
    }

    val isDirtyName : String = "isDirty$fullCamelCase"

    val childrenDirtyFlagName: String = "flagHasDirtyChild$fullCamelCase"

    val onChange : String by Delegates.lazy { "onChange$fullCamelCase"}

    val declare : String by Delegates.lazy { "$klassName $name" }

    val declareLocal : String by Delegates.lazy { "$klassName $localName" }

    val isObservable : Boolean by Delegates.lazy {
        resolvedClass.isObservable()
    }

    val isPrimitive : Boolean by Delegates.lazy {resolvedClass.isPrimitive()}

    val setter by Delegates.lazy {"set$fullCamelCase"}

    val getterGlobal : String by Delegates.lazy {
        if (parent == null) {
            if (static) {
                klassName
            } else {
                "this.$name"
            }
        } else {
            "${parent.getterGlobal}.$getter"
        }
    }

    val getterOnParentLocal by Delegates.lazy {
        if (parent == null) {
            if (static) {
                klassName
            } else {
                "this.$name"
            }
        } else {
            "${parent.localName}.$getter"
        }
    }

    fun markAsStatic() {
        static = true
        children.forEach{it.markAsStatic()}
    }
}

open data class VariableDefinition(val name : String, val klass : String, val static : Boolean = false)

data class VariableScope(val node : Node, val variables : MutableList<VariableDefinition> = arrayListOf())

data class StaticClass(name : String, klass : String) : VariableDefinition(name, klass, true)

data class LayoutExprBinding(val root : Node) {
    var exprModel : ExprModel by Delegates.notNull()
    val variableScopes = hashMapOf<Node, VariableScope>()
    val bindingTargets = arrayListOf<BindingTarget>()
    val variables = TreeMap<String, Variable>()
    val rootVariables = hashMapOf<String, Variable>()
    val dynamicVariables : List<Variable> by Delegates.lazy {variables.values().filterNot{it.static}}
    val observableVariables by Delegates.lazy {dynamicVariables.filter{it.isObservable}}
    val bindings by Delegates.lazy {
        bindingTargets.flatMap{it.bindings}
    }

    val staticVariableScope : VariableScope by Delegates.lazy {
        var existing = variableScopes[root]
        if (existing == null) {
            existing = VariableScope(root)
            variableScopes.put(root, existing)
        }
        existing!!
    }

    public fun addVariableScope(scope : VariableScope) {
        if (variableScopes.put(scope.node, scope) != null) {
            throw IllegalArgumentException("duplicate scope created for node ${scope.node}")
        }
    }

    public fun addStaticClass(name : String, klass : String) {
        staticVariableScope.variables.add(VariableDefinition(name, klass, true))
    }

    private fun toVariable(ref : VariableRef, klassLookup : Map<String, String>) : Variable {
        var existing = variables[ref.fullName]
        if (existing != null) {
            return existing
        }
        var parent = if (ref.parent == null) null else toVariable(ref.parent, klassLookup)
        existing = Variable(ref.fullName, parent)
        if (parent == null) {
            existing.klassName = klassLookup[existing.fullName]!!
            rootVariables[ref.fullName] = existing
            existing.isRootVariable = true
        } else {
            parent!!.children.add(existing)
        }

        variables[ref.fullName] = existing
        ref.variable = existing
        return existing
    }

    fun pack() {
        exprModel.pack() //seal the model, nothing will be added after this

        // assign klass to root variables that we can
        val rootVariableKlasses = TreeMap<String, String>()
        val staticVariableKlasses = TreeMap<String, String>()
        variableScopes.forEach {
            it.value.variables.forEach {
                if (rootVariableKlasses.put(it.name, it.klass) != null) {
                    throw IllegalArgumentException("two variables cannot share the same name yet")
                }
                if (it.static) {
                    staticVariableKlasses.put(it.name, it.klass)
                }
            }
        }

        // convert variable references to variables
        exprModel.variables.forEach { entry ->
            Log.d("converting ${entry.value} to variable")
            toVariable(entry.value, rootVariableKlasses)
        }

        rootVariables.values().forEach {
            if (staticVariableKlasses.contains(it.name)) {
                it.markAsStatic()
            }
        }

        // assign unique names to views
        bindingTargets.groupBy{ it.idName }.forEach {
            if (it.value.size() == 1) {
                it.value[0].resolvedUniqueName = "${it.value[0].idName.toCamelCase()}"
            } else {
                it.value.forEach {
                    it.resolvedUniqueName = "${it.idNamePath.joinToCamelCase()}"
                }
            }
        }
    }

    public fun analyzeVariables() {
        variables.forEach {
            Log.d("resolving method for ${it.key}")
            it.value.getter
            Log.d("resolved method / field for ${it.key} is ${it.value.getter} of type ${it.value.klassName}")
        }

        // set ids on observables first then in other variables
        // observable ids are used for observer references which is why they get first ids
        var id = 0
        observableVariables.forEach { it.localId = id++ }
        dynamicVariables.filterNot { it.isObservable }.forEach { it.localId = id++ }
    }
}