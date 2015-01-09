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

package com.android.databinding.renderer
import com.android.databinding.renderer.BrRenderer
import com.android.databinding.ext.toCamelCase
import com.android.databinding.ext.times
import com.android.databinding.ext.indent
import com.android.databinding.ext.indentExceptFirst
import com.android.databinding.vo.LayoutExprBinding
import com.android.databinding.vo
import com.android.databinding.ext.toCamelCase
import com.android.databinding.ext.toCamelCaseAsVar
import com.android.databinding.ext.joinIndentedExceptFirst
import com.android.databinding.ext.joinIndented

class ViewExprBinderRenderer(val pkg: String, val projectPackage: String, val baseClassName: String,
        val layoutName:String, val lb: LayoutExprBinding) {
    val className = "${baseClassName}Impl"
    val interfaceName = "${baseClassName}"
    val dirtyFlagName = "mDirty"

    fun setChildren(v : vo.Variable) : String = if (v.isPrimitive || v.children.size == 0) ""
    else
        """if (($dirtyFlagName | ${v.childrenDirtyFlagName}) != 0) { // ${v.childrenDirtyFlagsExpr}
            if (${v.localName} != null) {
            ${v.children.map{"""
                ${it.localName} = ${it.getterOnParentLocal};
                ${if (it.isObservable) {
                "updateRegistration(${it.localIdName}, ${it.localName});"
            } else ""}
                ${setChildren(it).indentExceptFirst(1)}
                """}.joinIndented(1)}
            } ${if(v.observableDescendents.size == 0) "" else {"""else {
                ${v.observableDescendents.map{"updateRegistration(${it.localIdName}, null);"}.joinIndentedExceptFirst(5)}
            }"""}}
        }
"""
    public fun render(br : BrRenderer) : String =
"""
package $pkg;
import $projectPackage.R;
import com.android.databinding.library.Observable;

public class $className extends com.android.databinding.library.ViewDataBinder
    implements $interfaceName {

private long $dirtyFlagName = -1;

${lb.rootVariables.values().filterNot{it.static}.map{
    "${it.declare};"
}.joinIndented(1)}

${lb.bindingTargets.filter { it.bindings.size > 0 }.map {
    "${it.viewClass} ${it.resolvedViewName};" }.joinIndented(1)
}
    public $className(android.view.View root) {
        super(root, ${lb.observableVariables.size});
        ${lb.bindingTargets.filter { it.bindings.size > 0 }.map { "this.${it.resolvedViewName} = (${it.viewClass})root.${it.findViewById};" }.join("\n        ") }
    }

    public boolean setVariable(int variableId, Object variable){
        switch(variableId) {
${lb.rootVariables.values().filterNot{it.static}.map { variable -> """
            case ${br.toIntS(variable.name)}: {
                ${variable.setter}((${variable.klassName}) variable);
                return true;
            }
"""}.joinIndentedExceptFirst(1)}
        }
        return false;
    }

${lb.rootVariables.values().filterNot{it.static}.map { variable -> """
    public void ${variable.setter}(${variable.klassName} ${variable.name}) {
        ${if (variable.isObservable) {
            "updateRegistration(${variable.localIdName}, ${variable.name});"
        } else ""}
        this.${variable.name} = ${variable.name};
        $dirtyFlagName |= ${variable.dirtyFlagName};
        super.requestRebind();
    }
"""}.joinIndentedExceptFirst(1)}

${lb.observableVariables.map { variable -> """
    public boolean ${variable.onChange}(${variable.klassName} ${variable.name}, int fieldId) {
        switch (fieldId) {
            ${variable.children.map{"""
            case ${br.toIntS(it.name)}: {
                $dirtyFlagName |= ${it.dirtyFlagName};
                return true;
            }"""}.joinIndentedExceptFirst(1)}
            case ${br.toIntS("")}:
                $dirtyFlagName |= ${variable.dirtyFlagName};
                return true;
        }
        return false;
    }
"""}.joinIndentedExceptFirst(1)}

    @Override
    protected boolean onFieldChange(int mLocalFieldId, Object object, int fieldId) {
        switch (mLocalFieldId) {
        // TODO also handle non-observables. basically, set them
        ${lb.observableVariables.map {"""
           case ${it.localIdName} :
                return ${it.onChange}((${it.klassName})object, fieldId);
        """}.joinIndentedExceptFirst(1)}
        }
        return false;
    }

${lb.bindingTargets.filter { it.bindings.size > 0 }.map { """
    @Override
    public ${it.viewClass} get${it.resolvedUniqueName}() {
        return this.${it.resolvedViewName};
    }"""}.joinIndentedExceptFirst(1) }

    @Override
    public void rebindDirty() {
        final long dirty = $dirtyFlagName;
        $dirtyFlagName = 0L; // mark everything non dirty
        ${lb.dynamicVariables.filterNot{it.isRootVariable}.map{
            "${it.declareLocal}${"= ${it.defaultValue}"};"
        }.joinIndentedExceptFirst(2)}

        // collect variables that exist
        ${lb.rootVariables.filterNot{it.value.static}.map {
            setChildren(it.value)}.joinIndentedExceptFirst(3)
        }
        // TODO find common ancestor from bound variables and do this binding in its setter.
        // common ancestor might be null in which case we should do it here.
        // we can even avoid checking another dirty here if it exactly matches the variable scope
        // (unlikely because it is probably a field)
        ${lb.bindings.map { binding -> """
        if ( (dirty & (${binding.isDirtyName})) != 0 ) {
            // ${binding.expr.toReadableString()}
            // resolved type: ${binding.expr.resolvedClass.getCanonicalName()}
            ${binding.setter};
        }"""}.joinIndented(2)}
    }

${lb.observableVariables.map {
    "private static final int ${it.localIdName} = ${it.localId};"
}.joinIndented(1)}

${lb.dynamicVariables.map {
    "private static final long ${it.dirtyFlagName} = ${it.dirtyFlag}L;"
}.joinIndented(1)}

${lb.dynamicVariables.map {
    "private static final long ${it.childrenDirtyFlagName} = ${it.childrenDirtyFlagsExpr};"
}.joinIndented(1)}

${lb.bindings.map {
    "private static final long ${it.isDirtyName} = ${it.isDirtyExpr};"
}.joinIndented(1)}

/**
${lb.bindingTargets.map{ target ->
    target.bindings.map { binding ->
        "${binding.expr.toReadableString()}"
    }.joinIndentedExceptFirst(1)
}.joinIndentedExceptFirst(2)}
**/
}
"""

    public fun renderInterface(br : BrRenderer) : String =
            """
package $pkg;

import android.binding.Bindable;

public interface ${interfaceName} extends com.android.databinding.library.IViewDataBinder {
${lb.rootVariables.values().filterNot{it.static}.map { variable -> """
    @Bindable
    public void ${variable.setter}(${variable.klassName} ${variable.name});
"""}.joinIndentedExceptFirst(1)}
${lb.bindingTargets.filter { it.bindings.size > 0 }.map { """
    public ${it.viewClass} get${it.resolvedUniqueName}();"""}.joinIndentedExceptFirst(1) }
}
"""

}