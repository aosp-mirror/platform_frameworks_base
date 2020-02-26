package com.android.codegen

import com.github.javaparser.JavaParser
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.expr.ClassExpr
import com.github.javaparser.ast.expr.Name
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.type.ArrayType
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.javadoc.Javadoc

data class FieldInfo(
    val index: Int,
    val fieldAst: FieldDeclaration,
    private val classInfo: ClassInfo
) {

    val classPrinter = classInfo as ClassPrinter

    // AST
    internal val variableAst = fieldAst.variables[0]
    val typeAst = variableAst.type

    // Field type
    val Type = typeAst.asString()
    val FieldClass = Type.takeWhile { it != '<' }
    val isPrimitive = Type in PRIMITIVE_TYPES

    // Javadoc
    val javadoc: Javadoc? = fieldAst.javadoc.orElse(null)
    private val javadocText = javadoc?.toText()?.let {
        // Workaround for a bug in Javaparser for javadocs starting with {
        if (it.hasUnbalancedCurlyBrace()) "{$it" else it
    }
    val javadocTextNoAnnotationLines = javadocText
            ?.lines()
            ?.dropLastWhile { it.startsWith("@") || it.isBlank() }
            ?.let { if (it.isEmpty()) null else it }
    val javadocFull = javadocText
            ?.trimBlankLines()
            ?.mapLines { " * $this" }
            ?.let { "/**\n$it\n */" }


    // Field name
    val name = variableAst.name.asString()!!
    private val isNameHungarian = name[0] == 'm' && name[1].isUpperCase()
    val NameUpperCamel = if (isNameHungarian) name.substring(1) else name.capitalize()
    val nameLowerCamel = if (isNameHungarian) NameUpperCamel.decapitalize() else name
    val _name = if (name != nameLowerCamel) nameLowerCamel else "_$nameLowerCamel"
    val SingularNameOrNull by lazy {
        classPrinter {
            fieldAst.annotations
                    .find { it.nameAsString == PluralOf }
                    ?.let { it as? SingleMemberAnnotationExpr }
                    ?.memberValue
                    ?.let { it as? StringLiteralExpr }
                    ?.value
                    ?.toLowerCamel()
                    ?.capitalize()
        }
    }
    val SingularName by lazy { SingularNameOrNull ?: NameUpperCamel }


    // Field value
    val mayBeNull: Boolean
        get() = when {
            isPrimitive -> false
            "@${classPrinter.NonNull}" in annotations -> false
            "@${classPrinter.NonEmpty}" in annotations -> false
            isNullable -> true
            lazyInitializer != null -> true
            else -> classPrinter { !FeatureFlag.IMPLICIT_NONNULL() }
        }
    val lazyInitializer
        get() = classInfo.classAst.methods.find { method ->
            method.nameAsString == "lazyInit$NameUpperCamel" && method.parameters.isEmpty()
        }?.nameAsString
    val internalGetter get() = if (lazyInitializer != null) "get$NameUpperCamel()" else name
    val defaultExpr: Any?
        get() {
            variableAst.initializer.orElse(null)?.let { return it }
            classInfo.classAst.methods.find {
                it.nameAsString == "default$NameUpperCamel" && it.parameters.isEmpty()
            }?.run { return "$nameAsString()" }
            return null
        }
    val hasDefault get() = defaultExpr != null


    // Generic args
    val isArray = Type.endsWith("[]")
    val isList = FieldClass == "List" || FieldClass == "ArrayList"
    val isMap = FieldClass == "Map" || FieldClass == "ArrayMap"
            || FieldClass == "HashMap" || FieldClass == "LinkedHashMap"
    val fieldBit = bitAtExpr(index)
    var isLast = false
    val isFinal = fieldAst.isFinal
    val fieldTypeGenegicArgs = when (typeAst) {
        is ArrayType -> listOf(fieldAst.elementType.asString())
        is ClassOrInterfaceType -> {
            typeAst.typeArguments.orElse(null)?.map { it.asString() } ?: emptyList()
        }
        else -> emptyList()
    }
    val FieldInnerType = fieldTypeGenegicArgs.firstOrNull()
    val FieldInnerClass = FieldInnerType?.takeWhile { it != '<' }


    // Annotations
    var intOrStringDef = null as ConstDef?
    val annotations by lazy {
        if (FieldClass in BUILTIN_SPECIAL_PARCELLINGS) {
            classPrinter {
                fileInfo.apply {
                    fieldAst.addAnnotation(SingleMemberAnnotationExpr(
                            Name(ParcelWith),
                            ClassExpr(parseJava(JavaParser::parseClassOrInterfaceType,
                                    "$Parcelling.BuiltIn.For$FieldClass"))))
                }
            }
        }
        fieldAst.annotations.map { it.removeComment().toString() }
    }
    val annotationsNoInternal by lazy {
        annotations.filterNot { ann ->
            classPrinter {
                internalAnnotations.any {
                    it in ann
                }
            }
        }
    }

    fun hasAnnotation(a: String) = annotations.any { it.startsWith(a) }
    val isNullable by lazy { hasAnnotation("@Nullable") }
    val isNonEmpty by lazy { hasAnnotation("@${classPrinter.NonEmpty}") }
    val customParcellingClass by lazy {
        fieldAst.annotations.find { it.nameAsString == classPrinter.ParcelWith }
                ?.singleArgAs<ClassExpr>()
                ?.type
                ?.asString()
    }
    val annotationsAndType by lazy { (annotationsNoInternal + Type).joinToString(" ") }
    val sParcelling by lazy { customParcellingClass?.let { "sParcellingFor$NameUpperCamel" } }

    val SetterParamType = if (isArray) "$FieldInnerType..." else Type
    val annotationsForSetterParam by lazy {
        buildList<String> {
            addAll(annotationsNoInternal)
            classPrinter {
                if ("@$Nullable" in annotations
                        && "@$MaySetToNull" !in annotations) {
                    remove("@$Nullable")
                    add("@$NonNull")
                }
            }
        }.joinToString(" ")
    }
    val annotatedTypeForSetterParam by lazy { "$annotationsForSetterParam $SetterParamType" }

    // Utilities

    /**
     * `mFoo.size()`
     */
    val ClassPrinter.sizeExpr get() = when {
        isArray && FieldInnerClass !in PRIMITIVE_TYPES ->
            memberRef("com.android.internal.util.ArrayUtils.size") + "($name)"
        isArray -> "$name.length"
        listOf("List", "Set", "Map").any { FieldClass.endsWith(it) } ->
            memberRef("com.android.internal.util.CollectionUtils.size") + "($name)"
        Type == "String" -> memberRef("android.text.TextUtils.length") + "($name)"
        Type == "CharSequence" -> "$name.length()"
        else -> "$name.size()"
    }
    /**
     * `mFoo.get(0)`
     */
    fun elemAtIndexExpr(indexExpr: String) = when {
        isArray -> "$name[$indexExpr]"
        FieldClass == "ArraySet" -> "$name.valueAt($indexExpr)"
        else -> "$name.get($indexExpr)"
    }
    /**
     * `mFoo.isEmpty()`
     */
    val ClassPrinter.isEmptyExpr get() = when {
        isArray || Type == "CharSequence" -> "$sizeExpr == 0"
        else -> "$name.isEmpty()"
    }

    /**
     * `mFoo == that` or `Objects.equals(mFoo, that)`, etc.
     */
    fun ClassPrinter.isEqualToExpr(that: String) = when {
        Type in PRIMITIVE_TYPES -> "$internalGetter == $that"
        isArray -> "${memberRef("java.util.Arrays.equals")}($internalGetter, $that)"
        else -> "${memberRef("java.util.Objects.equals")}($internalGetter, $that)"
    }

    /**
     * Parcel.write* and Parcel.read* method name wildcard values
     */
    val ParcelMethodsSuffix = when {
        FieldClass in PRIMITIVE_TYPES - "char" - "boolean" + BOXED_PRIMITIVE_TYPES +
                listOf("String", "CharSequence", "Exception", "Size", "SizeF", "Bundle",
                        "FileDescriptor", "SparseBooleanArray", "SparseIntArray", "SparseArray") ->
            FieldClass
        isMap && fieldTypeGenegicArgs[0] == "String" -> "Map"
        isArray -> when {
            FieldInnerType!! in (PRIMITIVE_TYPES + "String") -> FieldInnerType + "Array"
            isBinder(FieldInnerType) -> "BinderArray"
            else -> "TypedArray"
        }
        isList -> when {
            FieldInnerType == "String" -> "StringList"
            isBinder(FieldInnerType!!) -> "BinderList"
            else -> "ParcelableList"
        }
        isIInterface(Type) -> "StrongInterface"
        isBinder(Type) -> "StrongBinder"
        else -> "TypedObject"
    }.capitalize()

    private fun isBinder(type: String) = type == "Binder" || type == "IBinder" || isIInterface(type)
    private fun isIInterface(type: String) = type.length >= 2 && type[0] == 'I' && type[1].isUpperCase()
}