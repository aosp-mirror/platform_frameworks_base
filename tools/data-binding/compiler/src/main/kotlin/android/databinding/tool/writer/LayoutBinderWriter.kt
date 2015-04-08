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
import java.util.HashMap

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

val ExprModel.ext by Delegates.lazy { target : ExprModel ->
    ExprModelExt()
}

fun ExprModel.getUniqueFieldName(base : String) : String = ext.getUniqueFieldName(base)

fun ExprModel.localizeFlag(set : FlagSet, base : String) : FlagSet = ext.localizeFlag(set, base)

val BindingTarget.readableUniqueName by Delegates.lazy { target: BindingTarget ->
    val variableName : String
    if (target.getId() == null) {
        variableName = "boundView" + target.getTag()
    } else {
        variableName = target.getId().androidId().stripNonJava()
    }
    target.getModel().ext.getUniqueFieldName(variableName)
}

fun BindingTarget.superConversion(variable : String) : String {
    if (isBinder()) {
        return "${getViewClass()}.bind(${variable})"
    } else if (getResolvedType() != null && getResolvedType().extendsViewStub()) {
        return "new android.databinding.ViewStubProxy((android.view.ViewStub) ${variable})"
    } else {
        return "(${interfaceType}) ${variable}"
    }
}

val BindingTarget.fieldName by Delegates.lazy { target : BindingTarget ->
    if (target.getFieldName() == null) {
        if (target.getId() == null) {
            target.setFieldName("m${target.readableUniqueName.capitalize()}")
        } else {
            target.androidId.stripNonJava();
            target.setFieldName(target.readableUniqueName);
        }
    }
    target.getFieldName();
}

val BindingTarget.getterName by Delegates.lazy { target : BindingTarget ->
    "get${target.readableUniqueName.capitalize()}"
}

val BindingTarget.androidId by Delegates.lazy { target : BindingTarget ->
    "R.id.${target.getId().androidId()}"
}

val BindingTarget.interfaceType by Delegates.lazy { target : BindingTarget ->
    if (target.getResolvedType() != null && target.getResolvedType().extendsViewStub()) {
        "android.databinding.ViewStubProxy"
    } else {
        target.getInterfaceType()
    }
}

val Expr.readableUniqueName by Delegates.lazy { expr : Expr ->
    Log.d { "readableUniqueName for ${expr.getUniqueKey()}" }
    val stripped = "${expr.getUniqueKey().stripNonJava()}"
    expr.getModel().ext.getUniqueFieldName(stripped)
}

val Expr.readableName by Delegates.lazy { expr : Expr ->
    Log.d { "readableUniqueName for ${expr.getUniqueKey()}" }
    "${expr.getUniqueKey().stripNonJava()}"
}

val Expr.fieldName by Delegates.lazy { expr : Expr ->
    "m${expr.readableName.capitalize()}"
}

val Expr.hasFlag by Delegates.lazy { expr : Expr ->
    expr.getId() < expr.getModel().getInvalidateableFieldLimit()
}

val Expr.localName by Delegates.lazy { expr : Expr ->
    if(expr.isVariable()) expr.fieldName else "${expr.readableUniqueName}"
}

val Expr.setterName by Delegates.lazy { expr : Expr ->
    "set${expr.readableName.capitalize()}"
}

val Expr.onChangeName by Delegates.lazy { expr : Expr ->
    "onChange${expr.readableUniqueName.capitalize()}"
}

val Expr.getterName by Delegates.lazy { expr : Expr ->
    "get${expr.readableName.capitalize()}"
}

val Expr.dirtyFlagName by Delegates.lazy { expr : Expr ->
    "sFlag${expr.readableUniqueName.capitalize()}"
}

val Expr.shouldReadFlagName by Delegates.lazy { expr : Expr ->
    "sFlagRead${expr.readableUniqueName.capitalize()}"
}

val Expr.invalidateFlagName by Delegates.lazy { expr : Expr ->
    "sFlag${expr.readableUniqueName.capitalize()}Invalid"
}

val Expr.conditionalFlagPrefix by Delegates.lazy { expr : Expr ->
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


val Expr.dirtyFlagSet by Delegates.lazy { expr : Expr ->
    FlagSet(expr.getInvalidFlags(), expr.getModel().getFlagBucketCount())
}

val Expr.invalidateFlagSet by Delegates.lazy { expr : Expr ->
    FlagSet(expr.getId())
}

val Expr.shouldReadFlagSet by Delegates.lazy { expr : Expr ->
    FlagSet(expr.getShouldReadFlags(), expr.getModel().getFlagBucketCount())
}

val Expr.conditionalFlags by Delegates.lazy { expr : Expr ->
    arrayListOf(FlagSet(expr.getRequirementFlagIndex(false)),
            FlagSet(expr.getRequirementFlagIndex(true)))
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
        if (getLocalName() == null) binaryCode(bucketIndex)
        else "${getLocalName()}${getWordSuffix(bucketIndex)}"

fun FlagSet.binaryCode(bucketIndex : Int) = longToBinary(buckets[bucketIndex])


fun longToBinary(l : Long) =
        "0b${java.lang.Long.toBinaryString(l)}L"

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
    val indices = HashMap<BindingTarget, kotlin.Int>()
    val mDirtyFlags by Delegates.lazy {
        val fs = FlagSet(BitSet(), model.getFlagBucketCount());
        Arrays.fill(fs.buckets, -1)
        fs.setDynamic(true)
        model.localizeFlag(fs, "mDirtyFlags")
        fs
    }

    val dynamics by Delegates.lazy { model.getExprMap().values().filter { it.isDynamic() } }
    val className = layoutBinder.getImplementationName()

    val identifiers by Delegates.lazy {
        dynamics.filter { it is IdentifierExpr }
    }

    val baseClassName = "${layoutBinder.getClassName()}"

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
        calculateIndices();
        return kcode("package ${layoutBinder.getPackage()};") {
            nl("import ${layoutBinder.getModulePackage()}.R;")
            nl("import ${layoutBinder.getModulePackage()}.BR;")
            nl("import android.view.View;")
            val classDeclaration : String
            if (layoutBinder.hasVariations()) {
                classDeclaration = "${className} extends ${baseClassName}"
            } else {
                classDeclaration = "${className} extends android.databinding.ViewDataBinding"
            }
            nl("public class ${classDeclaration} {") {
                tab(declareIncludeViews())
                tab(declareViews())
                tab(declareVariables())
                tab(declareConstructor())
                tab(declareInvalidateAll())
                tab(declareLog())
                tab(declareSetVariable())
                tab(variableSettersAndGetters())
                tab(onFieldChange())

                tab(executePendingBindings())

                tab(declareDirtyFlags())
                if (!layoutBinder.hasVariations()) {
                    tab(declareFactories())
                }
            }
            nl("}")
            tab(flagMapping())
            tab("//end")
        }.generate()
    }
    fun calculateIndices() : Unit {
        val numTaggedViews = layoutBinder.getBindingTargets().
                filter{it.isUsed() && it.getTag() != null}.count()
        layoutBinder.getBindingTargets().filter{ it.isUsed() && it.getTag() != null }.forEach {
            indices.put(it, Integer.parseInt(it.getTag()));
        }
        layoutBinder.getBindingTargets().filter{ it.isUsed() && it.getTag() == null && it.getId() != null }.withIndex().forEach {
            indices.put(it.value, it.index + numTaggedViews);
        }
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
                layoutBinder.getBindingTargets().filter{ it.isUsed() && it.isBinder()}.forEach {
                    tab("sIncludes.put(${it.androidId}, ${indices.get(it)});")
                }
            }
            val hasViewsWithIds = layoutBinder.getBindingTargets().firstOrNull{
                it.isUsed() && (!it.supportsTag() || (it.getId() != null && it.getTag() == null))
            } != null
            if (!hasViewsWithIds) {
                tab("sViewsWithIds = null;")
            } else {
                tab("sViewsWithIds = new android.util.SparseIntArray();")
                layoutBinder.getBindingTargets().filter{
                    it.isUsed() && (!it.supportsTag() || (it.getId() != null && it.getTag() == null))
                }.forEach {
                    tab("sViewsWithIds.put(${it.androidId}, ${indices.get(it)});")
                }
            }
        }
        nl("}")
    }
    fun declareConstructor() = kcode("") {
        val viewCount = layoutBinder.getBindingTargets().filter{it.isUsed()}.count()
        if (layoutBinder.hasVariations()) {
            nl("")
            nl("public ${className}(View root) {") {
                tab("this(root, mapChildViews(root, ${viewCount}, sIncludes, sViewsWithIds));")
            }
            nl("}")
            nl("private ${className}(View root, View[] views) {") {
                tab("super(root, ${model.getObservables().size()}") {
                    layoutBinder.getBindingTargets().filter { it.getId() != null }.forEach {
                        tab(", ${fieldConversion(it)}")
                    }
                    tab(");")
                }
            }
        } else {
            nl("${baseClassName}(View root) {") {
                tab("super(root, ${model.getObservables().size()});")
                tab("final View[] views = mapChildViews(root, ${viewCount}, sIncludes, sViewsWithIds);")
            }
        }
        val taggedViews = layoutBinder.getBindingTargets().filter{it.isUsed()}
        taggedViews.forEach {
            if (!layoutBinder.hasVariations() || it.getId() == null) {
                tab("this.${it.fieldName} = ${fieldConversion(it)};")
            }
            if (!it.isBinder()) {
                if (it.getResolvedType() != null && it.getResolvedType().extendsViewStub()) {
                    tab("this.${it.fieldName}.setContainingBinding(this);")
                }
                if (it.supportsTag() && it.getTag() != null) {
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
        tab("invalidateAll();");
        nl("}")
    }

    fun fieldConversion(target : BindingTarget) : String {
        val index = indices.get(target)
        if (!target.isUsed()) {
            return "null"
        } else {
            val variableName: String
            if (index == null) {
                variableName = "root";
            } else {
                variableName = "views[${index}]"
            }
            return target.superConversion(variableName)
        }
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
        nl("@Override")
        nl("protected boolean onFieldChange(int localFieldId, Object object, int fieldId) {") {
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
        nl("}")
        nl("")

        model.getObservables().forEach {
            nl("private boolean ${it.onChangeName}(${it.getResolvedType().toJavaCode()} ${it.readableUniqueName}, int fieldId) {") {
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
            nl("}")
            nl("")
        }
    }

    fun declareViews() = kcode("// views") {
        val oneLayout = !layoutBinder.hasVariations();
        layoutBinder.getBindingTargets().filter {it.isUsed() && (oneLayout || it.getId() == null)}.forEach {
            val access : String
            if (oneLayout && it.getId() != null) {
                access = "public"
            } else {
                access = "private"
            }
            nl("${access} final ${it.interfaceType} ${it.fieldName};")
        }
    }

    fun declareVariables() = kcode("// variables") {
        usedVariables.forEach {
            nl("private ${it.getResolvedType().toJavaCode()} ${it.fieldName};")
        }
    }

    fun declareDirtyFlags() = kcode("// dirty flag") {
        model.ext.localizedFlags.forEach { flag ->
            flag.notEmpty { suffix, value ->
                nl("private")
                app(" ", if(flag.isDynamic()) null else "static final");
                app(" ", " ${flag.type} ${flag.getLocalName()}$suffix = ${longToBinary(value)};")
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
            model.getPendingExpressions().filterNot {it.isVariable()}.forEach {
                tab("${it.getResolvedType().toJavaCode()} ${it.localName} = ${it.getDefaultValue()};")
            }

            do {
                val batch = ExprModel.filterShouldRead(model.getPendingExpressions()).toArrayList()
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
                                val fieldName : String
                                if (binding.getTarget().getViewClass().
                                        equals(binding.getTarget().getInterfaceType())) {
                                    fieldName = "this.${binding.getTarget().fieldName}"
                                } else {
                                    fieldName = "((${binding.getTarget().getViewClass()}) this.${binding.getTarget().fieldName})"
                                }
                                val bindingCode = binding.toJavaCode(fieldName, binding.getExpr().toCode().generate())
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
            includedBinders.filter{it.isUsed()}.forEach { binder ->
                tab("${binder.fieldName}.executePendingBindings();")
            }
            layoutBinder.getBindingTargets().filter{
                it.isUsed() && it.getResolvedType() != null && it.getResolvedType().extendsViewStub()
            }.forEach {
                tab("if (${it.fieldName}.getBinding() != null) {") {
                    tab("${it.fieldName}.getBinding().executePendingBindings();")
                }
                tab("}")
            }
        }
        nl("}")
    }

    fun readWithDependants(expr : Expr, mJustRead : MutableList<Expr>, batch : MutableList<Expr>,
            tmpDirtyFlags : FlagSet, inheritedFlags : FlagSet? = null) : KCode = kcode("") {
        mJustRead.add(expr)
        Log.d { expr.getUniqueKey() }
        val flagSet = expr.shouldReadFlagSet
        val needsIfWrapper = inheritedFlags == null || !flagSet.bitsEqual(inheritedFlags)
        val ifClause = "if (${tmpDirtyFlags.mapOr(flagSet){ suffix, index ->
            "(${tmpDirtyFlags.localValue(index)} & ${flagSet.localValue(index)}) != 0"
        }.joinToString(" || ")
        })"

        val readCode = kcode("") {
            if (!expr.isVariable()) {
                // it is not a variable read it.
                tab("// read ${expr.getUniqueKey()}")
                // create an if case for all dependencies that might be null
                val nullables = expr.getDependencies().filter {
                    it.isMandatory() && it.getOther().getResolvedType().isNullable()
                }.map { it.getOther() }
                if (!expr.isEqualityCheck() && nullables.isNotEmpty()) {
                    tab ("if ( ${nullables.map { "${it.localName} != null" }.joinToString(" && ")}) {") {
                        tab("${expr.localName}").app(" = ", expr.toCode(true)).app(";")
                    }
                    tab("}")
                } else {
                    tab("${expr.localName}").app(" = ", expr.toCode(true)).app(";")
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
                val dependant = it.getDependant()
                batch.contains(dependant) &&
                        dependant.shouldReadFlagSet.andNot(flagSet).isEmpty() &&
                        dependant.shouldReadNow(mJustRead)
            }
            if (chosen.isNotEmpty()) {
                val nextInheritedFlags = if (needsIfWrapper) flagSet else inheritedFlags
                chosen.forEach {
                    nl(readWithDependants(it.getDependant(), mJustRead, batch, tmpDirtyFlags, nextInheritedFlags))
                }
            }
        }
        if (needsIfWrapper) {
            tab(ifClause) {
                app(" {")
                nl(readCode)
            }
            tab("}")
        } else {
            nl(readCode)
        }
    }

    fun declareFactories() = kcode("") {
        nl("public static ${baseClassName} inflate(android.view.ViewGroup root) {") {
            tab("return bind(android.view.LayoutInflater.from(root.getContext()).inflate(${layoutBinder.getModulePackage()}.R.layout.${layoutBinder.getLayoutname()}, root, true));")
        }
        nl("}")
        nl("public static ${baseClassName} inflate(android.content.Context context) {") {
            tab("return bind(android.view.LayoutInflater.from(context).inflate(${layoutBinder.getModulePackage()}.R.layout.${layoutBinder.getLayoutname()}, null, false));")
        }
        nl("}")
        nl("public static ${baseClassName} bind(android.view.View view) {") {
            tab("if (!\"${layoutBinder.getTag()}\".equals(view.getTag())) {") {
                tab("throw new RuntimeException(\"view tag isn't correct on view\");")
            }
            tab("}")
            tab("return new ${baseClassName}(view);")
        }
        nl("}")
    }

    public fun writeBaseClass() : String =
        kcode("package ${layoutBinder.getPackage()};") {
            nl("import android.databinding.Bindable;")
            nl("import android.databinding.DataBindingUtil;")
            nl("import android.databinding.ViewDataBinding;")
            nl("public abstract class ${baseClassName} extends ViewDataBinding {")
            layoutBinder.getBindingTargets().filter{it.getId() != null}.forEach {
                tab("public final ${it.interfaceType} ${it.fieldName};")
            }
            nl("")
            tab("protected ${baseClassName}(android.view.View root_, int localFieldCount") {
                layoutBinder.getBindingTargets().filter{it.getId() != null}.forEach {
                    tab(", ${it.interfaceType} ${it.readableUniqueName}")
                }
            }
            tab(") {") {
                tab("super(root_, localFieldCount);")
                layoutBinder.getBindingTargets().filter{it.getId() != null}.forEach {
                    tab("this.${it.fieldName} = ${it.readableUniqueName};")
                }
            }
            tab("}")
            nl("")
            variables.forEach {
                if (it.getUserDefinedType() != null) {
                    //it.getExpandedUserDefinedType(ModelAnalyzer.getInstance());
                    val type = ModelAnalyzer.getInstance().applyImports(it.getUserDefinedType(), model.getImports())
                    tab("public abstract void ${it.setterName}(${type} ${it.readableUniqueName});")
                }
            }
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
