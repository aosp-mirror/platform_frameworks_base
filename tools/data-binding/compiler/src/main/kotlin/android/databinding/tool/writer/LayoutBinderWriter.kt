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

package android.databinding.tool.writer

import android.databinding.tool.LayoutBinder
import android.databinding.tool.expr.Expr
import kotlin.properties.Delegates
import android.databinding.tool.ext.joinToCamelCaseAsVar
import android.databinding.tool.BindingTarget
import android.databinding.tool.expr.IdentifierExpr
import android.databinding.tool.util.Log
import java.util.BitSet
import android.databinding.tool.expr.ExprModel
import java.util.Arrays
import android.databinding.tool.expr.TernaryExpr
import android.databinding.tool.expr.FieldAccessExpr
import android.databinding.tool.expr.ComparisonExpr
import android.databinding.tool.expr.GroupExpr
import android.databinding.tool.expr.MathExpr
import android.databinding.tool.expr.MethodCallExpr
import android.databinding.tool.expr.StaticIdentifierExpr
import android.databinding.tool.expr.SymbolExpr
import android.databinding.tool.ext.androidId
import android.databinding.tool.ext.lazy
import android.databinding.tool.ext.br
import android.databinding.tool.expr.ResourceExpr
import android.databinding.tool.expr.BracketExpr
import android.databinding.tool.reflection.Callable
import android.databinding.tool.expr.CastExpr
import android.databinding.tool.reflection.ModelAnalyzer

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
    val variableName : String
    if (target.getId() == null) {
        variableName = "boundView" + target.getTag()
    } else {
        variableName = target.getId().androidId().stripNonJava()
    }
    target.getModel().ext.getUniqueFieldName(variableName)
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
            app("", it.getChild().toCode())
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

    val baseClassName = "${layoutBinder.getInterfaceName()}"

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
            nl("import ${layoutBinder.getModulePackage()}.R;")
            nl("import ${layoutBinder.getModulePackage()}.BR;")
            nl("import android.view.View;")
            nl("public class ${className} extends ${baseClassName} {") {
                tab(declareIncludeViews())
                tab(declareViews())
                tab(declareVariables())
                tab(declareConstructor())
                tab(declareInvalidateAll())
                tab(declareLog())
                tab(declareSetVariable())
                tab(variableSettersAndGetters())
                tab(viewGetters())
                tab(onFieldChange())

                tab(executePendingBindings())

                tab(declareDirtyFlags())
            }
            nl("}")
            tab(flagMapping())
            tab("//end")
        }.generate()
    }
    fun declareIncludeViews() = kcode("") {
        nl("private static final android.util.SparseIntArray sIncludes;")
        nl("private static final android.util.SparseIntArray sViewsWithIds;")
        nl("static {") {
            val hasBinders = layoutBinder.getBindingTargets().firstOrNull{ it.isUsed() && it.isBinder()} != null
            if (!hasBinders) {
                tab("sIncludes = null;")
            } else {
                tab("sIncludes = new android.util.SparseIntArray();")
                val numTaggedViews = layoutBinder.getBindingTargets().
                        filter{it.isUsed() && !it.isBinder()}.count()
                layoutBinder.getBindingTargets().filter{ it.isUsed() && it.isBinder()}.withIndex()
                        .forEach {
                    tab("sIncludes.put(${it.value.androidId}, ${it.index + numTaggedViews});")
                }
            }
            val hasViewsWithIds = layoutBinder.getBindingTargets().firstOrNull{ it.isUsed() && !it.supportsTag()} != null
            if (!hasViewsWithIds) {
                tab("sViewsWithIds = null;")
            } else {
                tab("sViewsWithIds = new android.util.SparseIntArray();")
                layoutBinder.getBindingTargets().filter{ it.isUsed() && !it.supportsTag() }.
                        forEach {
                    tab("sViewsWithIds.put(${it.androidId}, ${it.getTag()});")
                }
            }
        }
        nl("}")
    }
    fun declareConstructor() = kcode("") {
        nl("public ${className}(View root) {") {
            tab("super(root, ${model.getObservables().size()});")
            val viewCount = layoutBinder.getBindingTargets().filter{it.isUsed()}.count()
            tab("View[] views = new View[${viewCount}];")
            tab("mapTaggedChildViews(root, views, sIncludes, sViewsWithIds);");
            val taggedViews = layoutBinder.getBindingTargets().filter{it.isUsed() && !it.isBinder()}
            taggedViews.forEach {
                if (it.getTag() == null) {
                    tab("this.${it.fieldName} = (${it.getViewClass()}) root;")
                } else {
                    tab("this.${it.fieldName} = (${it.getViewClass()}) views[${it.getTag()}];")
                    if (it.supportsTag()) {
                        val originalTag = it.getOriginalTag();
                        var tagValue = "null"
                        if (originalTag != null) {
                            tagValue = "\"${originalTag}\""
                            if (originalTag.startsWith("@")) {
                                var packageName = layoutBinder.getModulePackage()
                                if (originalTag.startsWith("@android:")) {
                                    packageName = "android"
                                }
                                val slashIndex = originalTag.indexOf('/')
                                val resourceId = originalTag.substring(slashIndex + 1)
                                tagValue = "root.getResources().getString(${packageName}.R.string.${resourceId})"
                            }
                        }
                        tab("this.${it.fieldName}.setTag(${tagValue});")
                    }
                }
            }
            val taggedCount = taggedViews.count()
            layoutBinder.getBindingTargets().filter{it.isUsed() && it.isBinder()}.withIndex()
                    .forEach {
                tab("this.${it.value.fieldName} = ${it.value.getViewClass()}.bind(views[${it.index + taggedCount}]);")
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
        tab("protected boolean onFieldChange(int localFieldId, Object object, int fieldId) {") {
            tab("switch (localFieldId) {") {
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
            nl("private final ${it.getViewClass()} ${it.fieldName};")
        }
    }

    fun viewGetters() = kcode("// view getters") {
        layoutBinder.getBindingTargets().filter{it.getId() != null}.forEach {
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

    fun executePendingBindings() = kcode("") {
        nl("@Override")
        nl("public void executePendingBindings() {") {
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
                                tab("// api target ${binding.getMinApi()}")
                                val bindingCode = binding.toJavaCode(binding.getTarget().fieldName, binding.getExpr().toCode().generate())
                                if (binding.getMinApi() > 1) {
                                    tab("if(getBuildSdkInt() >= ${binding.getMinApi()}) {") {
                                        tab("$bindingCode;")
                                    }
                                    tab("}")
                                } else {
                                    tab("$bindingCode;")
                                }
                            }
                        }
                        tab("}")
                    }
            //
            includedBinders.filter{it.isUsed()}.forEach { binder ->
                tab("${binder.fieldName}.executePendingBindings();")
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

    public fun writeBaseClass() : String =
        kcode("package ${layoutBinder.getPackage()};") {
            nl("import android.databinding.Bindable;")
            nl("import android.databinding.DataBindingUtil;")
            nl("import android.databinding.ViewDataBinding;")
            nl("public abstract class ${baseClassName} extends ViewDataBinding {")
            tab("protected ${baseClassName}(android.view.View root, int localFieldCount) {") {
                tab("super(root, localFieldCount);")
            }
            tab("}")
            nl("")
            variables.forEach {
                if (it.getUserDefinedType() != null) {
                    tab("@Bindable")
                    //it.getExpandedUserDefinedType(ModelAnalyzer.getInstance());
                    val type = ModelAnalyzer.getInstance().applyImports(it.getUserDefinedType(), model.getImports())
                    tab("public abstract void ${it.setterName}(${type} ${it.readableUniqueName});")
                }
            }
            layoutBinder.getBindingTargets().filter{ it.getId() != null }.forEach {
                tab("public abstract ${it.getInterfaceType()} ${it.getterName}();")
            }
            nl("")
            tab("public static ${baseClassName} inflate(android.view.ViewGroup root) {") {
                tab("return DataBindingUtil.<${baseClassName}>inflate(root.getContext(), ${layoutBinder.getModulePackage()}.R.layout.${layoutBinder.getLayoutname()}, root, true);")
            }
            tab("}")
            tab("public static ${baseClassName} inflate(android.content.Context context) {") {
                tab("return DataBindingUtil.<${baseClassName}>inflate(context, ${layoutBinder.getModulePackage()}.R.layout.${layoutBinder.getLayoutname()}, null, false);")
            }
            tab("}")
            tab("public static ${baseClassName} bind(android.view.View view) {") {
                tab("return (${baseClassName})DataBindingUtil.bindTo(view, ${layoutBinder.getModulePackage()}.R.layout.${layoutBinder.getLayoutname()});")
            }
            tab("}")
            nl("}")
        }.generate()
}