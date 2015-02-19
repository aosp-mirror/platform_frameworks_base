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

import com.android.databinding.LayoutBinder
import com.android.databinding.expr.Expr
import com.android.databinding.reflection.ModelAnalyzer
import kotlin.properties.Delegates
import com.android.databinding.ext.joinToCamelCaseAsVar
import com.android.databinding.BindingTarget
import com.android.databinding.expr.IdentifierExpr
import com.android.databinding.util.Log
import java.util.BitSet
import java.util.HashSet
import com.android.databinding.util.L
import org.apache.commons.lang3.NotImplementedException
import com.android.databinding.expr.ExprModel
import com.android.databinding.writer.FlagSet
import java.util.Arrays
import java.lang
import com.android.databinding.expr.TernaryExpr
import com.android.databinding.ext.androidId
import com.android.databinding.expr.FieldAccessExpr
import com.android.databinding.expr.ComparisonExpr
import com.android.databinding.expr.GroupExpr
import com.android.databinding.expr.MathExpr
import com.android.databinding.expr.MethodCallExpr
import com.android.databinding.expr.StaticIdentifierExpr
import com.android.databinding.expr.SymbolExpr
import com.android.databinding.ext.androidId
import com.android.databinding.ext.lazy
import com.android.databinding.ext.br
import com.android.databinding.ext.toJavaCode
import com.android.databinding.expr.ResourceExpr
import com.android.databinding.expr.BracketExpr
import com.android.databinding.reflection.Callable
import com.android.databinding.expr.CastExpr
import com.android.databinding.expr.StaticAccessExpr

fun String.stripNonJava() = this.split("[^a-zA-Z0-9]").map{ it.trim() }.joinToCamelCaseAsVar()

class ExprModelExt {
    val usedFieldNames = hashSetOf<String>()
    val localizedFlags = arrayListOf<FlagSet>()

    fun localizeFlag(set : FlagSet, name:String) : FlagSet {
        localizedFlags.add(set)
        val result = getUniqueFieldName(name)
        set.setLocalName(result)
        return set
    }

    fun getUniqueFieldName(base : String) : String {
        var candidate = base
        var i = 0
        while (usedFieldNames.contains(candidate)) {
            i ++
            candidate = base + i
        }
        usedFieldNames.add(candidate)
        return candidate
    }
}

val ExprModel.ext by Delegates.lazy { (target : ExprModel) ->
    ExprModelExt()
}

fun ExprModel.getUniqueFieldName(base : String) : String = ext.getUniqueFieldName(base)

fun ExprModel.localizeFlag(set : FlagSet, base : String) : FlagSet = ext.localizeFlag(set, base)

val BindingTarget.readableUniqueName by Delegates.lazy {(target: BindingTarget) ->
    val stripped = target.getId().androidId().stripNonJava()
    target.getModel().ext.getUniqueFieldName(stripped)
}

val BindingTarget.fieldName by Delegates.lazy { (target : BindingTarget) ->
    "m${target.readableUniqueName.capitalize()}"
}

val BindingTarget.getterName by Delegates.lazy { (target : BindingTarget) ->
    "get${target.readableUniqueName.capitalize()}"
}

val BindingTarget.androidId by Delegates.lazy { (target : BindingTarget) ->
    "R.id.${target.getId().androidId()}"
}

val Expr.readableUniqueName by Delegates.lazy { (expr : Expr) ->
    Log.d { "readableUniqueName for ${expr.getUniqueKey()}" }
    val stripped = "${expr.getUniqueKey().stripNonJava()}"
    expr.getModel().ext.getUniqueFieldName(stripped)
}

val Expr.fieldName by Delegates.lazy { (expr : Expr) ->
    "m${expr.readableUniqueName.capitalize()}"
}

val Expr.hasFlag by Delegates.lazy { (expr : Expr) ->
    expr.getId() < expr.getModel().getInvalidateableFieldLimit()
}

val Expr.localName by Delegates.lazy { (expr : Expr) ->
    if(expr.isVariable()) expr.fieldName else "${expr.readableUniqueName}"
}

val Expr.setterName by Delegates.lazy { (expr : Expr) ->
    "set${expr.readableUniqueName.capitalize()}"
}

val Expr.onChangeName by Delegates.lazy { (expr : Expr) ->
    "onChange${expr.readableUniqueName.capitalize()}"
}

val Expr.getterName by Delegates.lazy { (expr : Expr) ->
    "get${expr.readableUniqueName.capitalize()}"
}

val Expr.staticFieldName by Delegates.lazy { (expr : Expr) ->
    "s${expr.readableUniqueName.capitalize()}"
}

val Expr.dirtyFlagName by Delegates.lazy { (expr : Expr) ->
    "sFlag${expr.readableUniqueName.capitalize()}"
}

val Expr.shouldReadFlagName by Delegates.lazy { (expr : Expr) ->
    "sFlagRead${expr.readableUniqueName.capitalize()}"
}

val Expr.invalidateFlagName by Delegates.lazy { (expr : Expr) ->
    "sFlag${expr.readableUniqueName.capitalize()}Invalid"
}

val Expr.conditionalFlagPrefix by Delegates.lazy { (expr : Expr) ->
    "sFlag${expr.readableUniqueName.capitalize()}Is"
}


fun Expr.toCode(full : Boolean = false) : KCode {
    val it = this
    if (isDynamic() && !full) {
        return kcode(localName)
    }
    return when (it) {
        is ComparisonExpr -> kcode("") {
            app("", it.getLeft().toCode())
            app(it.getOp())
            app("", it.getRight().toCode())
        }
        is FieldAccessExpr -> kcode("") {
            app("", it.getParent().toCode())
            if (it.getGetter().type == Callable.Type.FIELD) {
                app(".", it.getGetter().name)
            } else {
                app(".", it.getGetter().name).app("()")
            }
        }
        is GroupExpr -> kcode("(").app("", it.getWrapped().toCode()).app(")")
        is StaticIdentifierExpr -> kcode(it.getResolvedType().toJavaCode())
        is IdentifierExpr -> kcode(it.localName)
        is MathExpr -> kcode("") {
            app("", it.getLeft().toCode())
            app(it.getOp())
            app("", it.getRight().toCode())
        }
        is MethodCallExpr -> kcode("") {
            app("", it.getTarget().toCode())
            app(".", it.getGetter().name)
            app("(")
            var first = true
            it.getArgs().forEach {
                apps(if (first) "" else ",", it.toCode())
                first = false
            }
            app(")")
        }
        is SymbolExpr -> kcode(it.getText()) // TODO
        is TernaryExpr -> kcode("") {
            app("", it.getPred().toCode())
            app("?", it.getIfTrue().toCode())
            app(":", it.getIfFalse().toCode())
        }
        is ResourceExpr -> kcode("") {
            app("", it.toJava())
        }
        is BracketExpr -> kcode("") {
            app("", it.getTarget().toCode())
            val bracketType = it.getAccessor();
            when (bracketType) {
                BracketExpr.BracketAccessor.ARRAY -> {
                    app("[", it.getArg().toCode())
                    app("]")
                }
                BracketExpr.BracketAccessor.LIST -> {
                    app(".get(")
                    if (it.argCastsInteger()) {
                        app("(Integer)")
                    }
                    app("", it.getArg().toCode())
                    app(")")
                }
                BracketExpr.BracketAccessor.MAP -> {
                    app(".get(", it.getArg().toCode())
                    app(")")
                }
            }
        }
        is CastExpr -> kcode("") {
            app("(", it.getCastType())
            app(") ", it.getCastExpr().toCode())
        }
        is StaticAccessExpr -> kcode("") {
            app("", it.getResolvedType().toJavaCode())
        }
        else -> kcode("//NOT IMPLEMENTED YET")
    }

}

fun Expr.isVariable() = this is IdentifierExpr && this.isDynamic()

fun Expr.conditionalFlagName(output : Boolean, suffix : String) = "${dirtyFlagName}_${output}$suffix"


val Expr.dirtyFlagSet by Delegates.lazy { (expr : Expr) ->
    val fs = FlagSet(expr.getInvalidFlags(), expr.getModel().getFlagBucketCount())
    expr.getModel().localizeFlag(fs, expr.dirtyFlagName)
}

val Expr.invalidateFlagSet by Delegates.lazy { (expr : Expr) ->
    val fs = FlagSet(expr.getId())
    expr.getModel().localizeFlag(fs, expr.invalidateFlagName)
}

val Expr.shouldReadFlagSet by Delegates.lazy { (expr : Expr) ->
    val fs = FlagSet(expr.getShouldReadFlags(), expr.getModel().getFlagBucketCount())
    expr.getModel().localizeFlag(fs, expr.shouldReadFlagName)
}

val Expr.conditionalFlags by Delegates.lazy { (expr : Expr) ->
    val model = expr.getModel()
    arrayListOf(model.localizeFlag(FlagSet(expr.getRequirementFlagIndex(false)),
            "${expr.conditionalFlagPrefix}False"),
            model.localizeFlag(FlagSet(expr.getRequirementFlagIndex(true)),
                    "${expr.conditionalFlagPrefix}True"))
}

fun Expr.getRequirementFlagSet(expected : Boolean) : FlagSet = conditionalFlags[if(expected) 1 else 0]

fun FlagSet.notEmpty(cb : (suffix : String, value : Long) -> Unit) {
    buckets.withIndex().forEach {
        if (it.value != 0L) {
            cb(getWordSuffix(it.index), buckets[it.index])
        }
    }
}

fun FlagSet.getBitSuffix(bitIndex : Int) : String {
    val word = bitIndex / FlagSet.sBucketSize
    return getWordSuffix(word)
}

fun FlagSet.getWordSuffix(wordIndex : Int) : String {
    return if(wordIndex == 0) "" else "_${wordIndex}"
}

fun FlagSet.localValue(bucketIndex : Int) =
        if (getLocalName() == null) buckets[bucketIndex]
        else "${getLocalName()}${getWordSuffix(bucketIndex)}"

fun FlagSet.or(other : FlagSet, cb : (suffix : String) -> Unit) {
    val min = Math.min(buckets.size(), other.buckets.size())
    for (i in 0..(min - 1)) {
        // if these two can match by any chance, call the callback
        if (intersect(other, i)) {
            cb(getWordSuffix(i))
        }
    }
}

fun <T> FlagSet.mapOr(other : FlagSet, cb : (suffix : String, index : Int) -> T) : List<T> {
    val min = Math.min(buckets.size(), other.buckets.size())
    val result = arrayListOf<T>()
    for (i in 0..(min - 1)) {
        // if these two can match by any chance, call the callback
        if (intersect(other, i)) {
            result.add(cb(getWordSuffix(i), i))
        }
    }
    return result
}

class LayoutBinderWriter(val layoutBinder : LayoutBinder) {
    val model = layoutBinder.getModel()
    val mDirtyFlags by Delegates.lazy {
        val fs = FlagSet(BitSet(), model.getFlagBucketCount());
        Arrays.fill(fs.buckets, -1)
        fs.setDynamic(true)
        model.localizeFlag(fs, "mDirtyFlags")
        fs
    }

    val dynamics by Delegates.lazy { model.getExprMap().values().filter { it.isDynamic() } }
    val className = layoutBinder.getClassName()

    val identifiers by Delegates.lazy {
        dynamics.filter { it is IdentifierExpr }
    }

    val interfaceName = "${layoutBinder.getInterfaceName()}"

    val includedBinders by Delegates.lazy {
        layoutBinder.getBindingTargets().filter { it.isBinder() }
    }

    val variables by Delegates.lazy {
        model.getExprMap().values().filterIsInstance(javaClass<IdentifierExpr>()).filter { it.isVariable() }
    }

    val usedVariables by Delegates.lazy {
        variables.filter {it.isUsed()}
    }

    public fun write() : String  {
        layoutBinder.resolveWhichExpressionsAreUsed()
        return kcode("package ${layoutBinder.getPackage()};") {
            nl("import ${layoutBinder.getProjectPackage()}.R;")
            nl("import android.view.View;")
            nl("public class ${className} extends com.android.databinding.library.ViewDataBinder implements ${interfaceName} {") {
                tab(declareViews())
                tab(declareVariables())
                tab(declareConstructor())
                tab(declareInvalidateAll())
                tab(declareLog())
                tab(declareSetVariable())
                tab(variableSettersAndGetters())
                tab(viewGetters())
                tab(onFieldChange())

                tab(rebindDirty())

                tab(declareDirtyFlags())
            }
            nl("}")
            tab(flagMapping())
            tab("//end")
        }.generate()
    }
    fun declareConstructor() = kcode("") {
        nl("public ${className}(View root) {") {
            tab("super(root, ${model.getObservables().size()});")
            layoutBinder.getBindingTargets().filter{it.isUsed()}.forEach {
                if (it.isBinder()) {
                    tab("this.${it.fieldName} = com.android.databinding.library.DataBinder.createBinder(root.findViewById(${it.androidId}), R.layout.${it.getIncludedLayout()});")
                } else {
                    tab("this.${it.fieldName} = (${it.getViewClass()}) root.findViewById(${it.androidId});")
                }
            }
            tab("invalidateAll();");
        }
        nl("}")
    }

    fun declareInvalidateAll() = kcode("") {
        nl("@Override")
        nl("public void invalidateAll() {") {
            val bs = BitSet()
            bs.set(0, model.getInvalidateableFieldLimit())
            val fs = FlagSet(bs, mDirtyFlags.buckets.size())
            for (i in (0..(mDirtyFlags.buckets.size() - 1))) {
                tab("${mDirtyFlags.localValue(i)} = ${fs.localValue(i)};")
            }
            includedBinders.filter{it.isUsed()}.forEach { binder ->
                tab("${binder.fieldName}.invalidateAll();")
            }
        }
        nl("}")
    }

    fun declareSetVariable() = kcode("") {
        nl("public boolean setVariable(int variableId, Object variable) {") {
            tab("switch(variableId) {") {
                usedVariables.forEach {
                    tab ("case ${it.getName().br()} :") {
                        tab("${it.setterName}((${it.getResolvedType().toJavaCode()}) variable);")
                        tab("return true;")
                    }
                }
            }
            tab("}")
            tab("return false;")
        }
        nl("}")
    }

    fun declareLog() = kcode("") {
        nl("private void log(String msg, long i) {") {
            tab("""android.util.Log.d("BINDER", msg + ":" + Long.toHexString(i));""")
        }
        nl("}")
    }

    fun variableSettersAndGetters() = kcode("") {
        variables.filterNot{it.isUsed()}.forEach {
            nl("public void ${it.setterName}(${it.getResolvedType().toJavaCode()} ${it.readableUniqueName}) {") {
                tab("// not used, ignore")
            }
            nl("}")
            nl("")
            nl("public ${it.getResolvedType().toJavaCode()} ${it.getterName}() {") {
                tab("return ${it.getDefaultValue()};")
            }
            nl("}")
        }
        usedVariables.forEach {
            if (it.getUserDefinedType() != null) {
                nl("public void ${it.setterName}(${it.getResolvedType().toJavaCode()} ${it.readableUniqueName}) {") {
                    if (it.isObservable()) {
                        tab("updateRegistration(${it.getId()}, ${it.readableUniqueName});");
                    }
                    tab("this.${it.fieldName} = ${it.readableUniqueName};")
                    // set dirty flags!
                    val flagSet = it.invalidateFlagSet
                    mDirtyFlags.mapOr(flagSet) { suffix, index ->
                        tab("${mDirtyFlags.getLocalName()}$suffix |= ${flagSet.localValue(index)};")
                    }
                    tab("super.requestRebind();")
                }
                nl("}")
                nl("")
                nl("public ${it.getResolvedType().toJavaCode()} ${it.getterName}() {") {
                    tab("return ${it.fieldName};")
                }
                nl("}")
            }
        }
    }

    fun onFieldChange() = kcode("") {
        tab("@Override")
        tab("protected boolean onFieldChange(int mLocalFieldId, Object object, int fieldId) {") {
            tab("switch (mLocalFieldId) {") {
                model.getObservables().forEach {
                    tab("case ${it.getId()} :") {
                        tab("return ${it.onChangeName}((${it.getResolvedType().toJavaCode()}) object, fieldId);")
                    }
                }
            }
            tab("}")
            tab("return false;")
        }
        tab("}")

        model.getObservables().forEach {
            tab("private boolean ${it.onChangeName}(${it.getResolvedType().toJavaCode()} ${it.readableUniqueName}, int fieldId) {") {
                tab("switch (fieldId) {", {
                    val accessedFields: List<FieldAccessExpr> = it.getParents().filterIsInstance(javaClass<FieldAccessExpr>())
                    accessedFields.filter { it.canBeInvalidated() }
                            .groupBy { it.getName() }
                            .forEach {
                                tab("case ${it.key.br()}:") {
                                    val field = it.value.first()
                                    mDirtyFlags.mapOr(field.invalidateFlagSet) { suffix, index ->
                                        tab("${mDirtyFlags.localValue(index)} |= ${field.invalidateFlagSet.localValue(index)};")
                                    }
                                    tab("return true;")
                                }

                            }
                    tab("case ${"".br()}:") {
                        val flagSet = it.invalidateFlagSet
                        mDirtyFlags.mapOr(flagSet) { suffix, index ->
                            tab("${mDirtyFlags.getLocalName()}$suffix |= ${flagSet.localValue(index)};")
                        }
                        tab("return true;")
                    }

                })
                tab("}")
                tab("return false;")
            }
            tab("}")
        }
    }

    fun declareViews() = kcode("// views") {
        layoutBinder.getBindingTargets().filter{it.isUsed()}.forEach {
            nl("private ${it.getViewClass()} ${it.fieldName};")
        }
    }

    fun viewGetters() = kcode("// view getters") {
        layoutBinder.getBindingTargets().forEach {
            nl("@Override")
            nl("public ${it.getInterfaceType()} ${it.getterName}() {") {
                if (it.isUsed()) {
                    tab("return ${it.fieldName};")
                } else {
                    tab("return null;")
                }

            }
            nl("}")
        }
    }

    fun declareVariables() = kcode("// variables") {
        usedVariables.forEach {
            nl("private ${it.getResolvedType().toJavaCode()} ${it.fieldName};")
        }
    }

    fun declareDirtyFlags() = kcode("// dirty flag") {
        model.ext.localizedFlags.forEach { flag ->
            flag.notEmpty { (suffix, value) ->
                nl("private")
                app(" ", if(flag.isDynamic()) null else "static final");
                app(" ", " ${flag.type} ${flag.getLocalName()}$suffix = $value;")
            }
        }
    }

    fun flagMapping() = kcode("/* flag mapping") {
        if (model.getFlagMapping() != null) {
            val mapping = model.getFlagMapping()
            for (i in mapping.indices) {
                tab("flag $i: ${mapping[i]}")
            }
        }
        nl("flag mapping end*/")
    }

    fun rebindDirty() = kcode("") {
        nl("@Override")
        nl("public void rebindDirty() {") {
            val tmpDirtyFlags = FlagSet(mDirtyFlags.buckets)
            tmpDirtyFlags.setLocalName("dirtyFlags");
            for (i in (0..mDirtyFlags.buckets.size() - 1)) {
                tab("${tmpDirtyFlags.type} ${tmpDirtyFlags.localValue(i)} = ${mDirtyFlags.localValue(i)};")
                tab("${mDirtyFlags.localValue(i)} = 0;")
            }
            //tab("""log("dirty flags", mDirtyFlags);""")
            model.getPendingExpressions().filterNot {it.isVariable()}.forEach {
                tab("${it.getResolvedType().toJavaCode()} ${it.localName} = ${it.getDefaultValue()};")
            }

            do {
                val batch = model.filterShouldRead(model.getPendingExpressions()).toArrayList()
                val mJustRead = arrayListOf<Expr>()
                while (!batch.none()) {
                    val readNow = batch.filter { it.shouldReadNow(mJustRead) }
                    if (readNow.isEmpty()) {
                        throw IllegalStateException("do not know what I can read. bailing out ${batch.joinToString("\n")}")
                    }
                    Log.d { "new read now. batch size: ${batch.size()}, readNow size: ${readNow.size()}" }

                    readNow.forEach {
                        nl(readWithDependants(it, mJustRead, batch, tmpDirtyFlags))
                    }
                    batch.removeAll(mJustRead)
                }
                tab("// batch finished")
            } while(model.markBitsRead())

            //
            layoutBinder.getBindingTargets().filter { it.isUsed() }
                    .flatMap { it.getBindings() }
                    .groupBy { it.getExpr() }
                    .forEach {
                        val flagSet = it.key.dirtyFlagSet
                        tab("if (${tmpDirtyFlags.mapOr(flagSet){ suffix, index ->
                            "(${tmpDirtyFlags.localValue(index)} & ${flagSet.localValue(index)}) != 0"
                        }.joinToString(" || ")
                        }) {") {
                            it.value.forEach { binding ->
                                tab("${binding.toJavaCode(binding.getTarget().fieldName, binding.getExpr().toCode().generate())};")
                            }
                        }
                        tab("}")
                    }
            //
            includedBinders.filter{it.isUsed()}.forEach { binder ->
                tab("${binder.fieldName}.rebindDirty();")
            }
        }
        nl("}")
    }

    fun readWithDependants(expr : Expr, mJustRead : MutableList<Expr>, batch : MutableList<Expr>, tmpDirtyFlags : FlagSet) : KCode = kcode("") {
        mJustRead.add(expr)
        Log.d { expr.getUniqueKey() }
        val flagSet = expr.shouldReadFlagSet
        tab("if (${tmpDirtyFlags.mapOr(flagSet){ suffix, index ->
            "(${tmpDirtyFlags.localValue(index)} & ${flagSet.localValue(index)}) != 0"
        }.joinToString(" || ")
        }) {") {
            if (!expr.isVariable()) {
                // it is not a variable read it.
                tab("// read ${expr.getUniqueKey()}")
                // create an if case for all dependencies that might be null
                val nullables = expr.getDependencies().filter {
                    it.isMandatory() && it.getOther().getResolvedType().isNullable()
                }
                        .map { it.getOther() }
                if (!expr.isEqualityCheck() && nullables.isNotEmpty()) {
                    tab ("if ( ${nullables.map { "${it.localName} != null" }.joinToString(" && ")}) {") {
                        tab("${expr.localName}").app(" = ", expr.toCode(true)).app(";")
                        //tab("""log("${expr}" + ${expr.localName},0);""")
                    }
                    tab("}")
                } else {
                    tab("${expr.localName}").app(" = ", expr.toCode(true)).app(";")
                    //tab("""log("${expr}" + ${expr.localName},0);""")
                }
                if (expr.isObservable()) {
                    tab("updateRegistration(${expr.getId()}, ${expr.localName});")
                }
            }

            // if I am the condition for an expression, set its flag
            val conditionals = expr.getDependants().filter { !it.isConditional()
                    && it.getDependant() is TernaryExpr && (it.getDependant() as TernaryExpr).getPred() == expr }
                    .map { it.getDependant() }
            if (conditionals.isNotEmpty()) {
                tab("// setting conditional flags")
                tab("if (${expr.localName}) {") {
                    conditionals.forEach {
                        val set = it.getRequirementFlagSet(true)
                        mDirtyFlags.mapOr(set) { suffix , index ->
                            tab("${tmpDirtyFlags.localValue(index)} |= ${set.localValue(index)};")
                        }
                    }
                }
                tab("} else {") {
                    conditionals.forEach {
                        val set = it.getRequirementFlagSet(false)
                        mDirtyFlags.mapOr(set) { suffix , index ->
                            tab("${tmpDirtyFlags.localValue(index)} |= ${set.localValue(index)};")
                        }
                    }
                } tab("}")
            }

            val chosen = expr.getDependants().filter {
                batch.contains(it.getDependant()) && it.getDependant().shouldReadNow(mJustRead)
            }
            if (chosen.isNotEmpty()) {
                chosen.forEach {
                    nl(readWithDependants(it.getDependant(), mJustRead, batch, tmpDirtyFlags))
                }
            }
        }
        tab("}")
    }

    public fun writeInterface() : String =
        kcode("package ${layoutBinder.getPackage()};") {
            nl("import android.binding.Bindable;")
            nl("import com.android.databinding.library.IViewDataBinder;")

            nl("public interface ${interfaceName} extends IViewDataBinder {")
            variables.forEach {
                if (it.getUserDefinedType() != null) {
                    tab("@Bindable")
                    tab("public void ${it.setterName}(${it.getUserDefinedType()} ${it.readableUniqueName});")
                }
            }
            layoutBinder.getBindingTargets().forEach {
                tab("public ${it.getInterfaceType()} ${it.getterName}();")
            }
            nl("}")
        }.generate()
}