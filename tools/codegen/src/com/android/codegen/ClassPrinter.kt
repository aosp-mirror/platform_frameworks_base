package com.android.codegen

import com.github.javaparser.ast.Modifier
import com.github.javaparser.ast.body.CallableDeclaration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.*
import com.github.javaparser.ast.type.ClassOrInterfaceType

/**
 * [ClassInfo] + utilities for printing out new class code with proper indentation and imports
 */
class ClassPrinter(
        classAst: ClassOrInterfaceDeclaration,
        fileInfo: FileInfo
) : ClassInfo(classAst, fileInfo), Printer<ClassPrinter>, ImportsProvider {

    val GENERATED_MEMBER_HEADER by lazy { "@$GeneratedMember" }

    init {
        val fieldsWithMissingNullablity = fields.filter { field ->
            !field.isPrimitive
                    && field.fieldAst.modifiers.none { it.keyword == Modifier.Keyword.TRANSIENT }
                    && "@$Nullable" !in field.annotations
                    && "@$NonNull" !in field.annotations
        }
        if (fieldsWithMissingNullablity.isNotEmpty()) {
            abort("Non-primitive fields must have @$Nullable or @$NonNull annotation.\n" +
                    "Missing nullability annotations on: "
                    + fieldsWithMissingNullablity.joinToString(", ") { it.name })
        }

        if (!classAst.isFinal &&
                classAst.extendedTypes.any { it.nameAsString == Parcelable }) {
            abort("Parcelable classes must be final")
        }
    }

    val cliArgs get() = fileInfo.cliArgs

    fun print() {
        currentIndent = fileInfo.sourceLines
                .find { "class $ClassName" in it }!!
                .takeWhile { it.isWhitespace() }
                .plus(INDENT_SINGLE)

        +fileInfo.generatedWarning

        if (FeatureFlag.CONST_DEFS()) generateConstDefs()


        if (FeatureFlag.CONSTRUCTOR()) {
            generateConstructor("public")
        } else if (FeatureFlag.BUILDER()
                || FeatureFlag.COPY_CONSTRUCTOR()
                || FeatureFlag.WITHERS()) {
            generateConstructor("/* package-private */")
        }
        if (FeatureFlag.COPY_CONSTRUCTOR()) generateCopyConstructor()

        if (FeatureFlag.GETTERS()) generateGetters()
        if (FeatureFlag.SETTERS()) generateSetters()
        if (FeatureFlag.TO_STRING()) generateToString()
        if (FeatureFlag.EQUALS_HASH_CODE()) generateEqualsHashcode()

        if (FeatureFlag.FOR_EACH_FIELD()) generateForEachField()

        if (FeatureFlag.WITHERS()) generateWithers()

        if (FeatureFlag.PARCELABLE()) generateParcelable()

        if (FeatureFlag.BUILDER() && FeatureFlag.BUILD_UPON()) generateBuildUpon()
        if (FeatureFlag.BUILDER()) generateBuilder()

        if (FeatureFlag.AIDL()) fileInfo.generateAidl() //TODO guard against nested classes requesting aidl

        generateMetadata(fileInfo.file)

        +"""
        //@formatter:on
        $GENERATED_END
        
        """

        rmEmptyLine()
    }

    override var currentIndent: String
        get() = fileInfo.currentIndent
        set(value) { fileInfo.currentIndent = value }
    override val stringBuilder get() = fileInfo.stringBuilder


    val dataClassAnnotationFeatures = classAst.annotations
            .find { it.nameAsString == DataClass }
            ?.let { it as? NormalAnnotationExpr }
            ?.pairs
            ?.map { pair -> pair.nameAsString to (pair.value as BooleanLiteralExpr).value }
            ?.toMap()
            ?: emptyMap()

    val internalAnnotations = setOf(ParcelWith, DataClassEnum, PluralOf, UnsupportedAppUsage,
            DataClassSuppressConstDefs, MaySetToNull, Each, DataClass)
    val knownNonValidationAnnotations = internalAnnotations + Each + Nullable

    /**
     * @return whether the given feature is enabled
     */
    operator fun FeatureFlag.invoke(): Boolean {
        if (cliArgs.contains("--no-$kebabCase")) return false
        if (cliArgs.contains("--$kebabCase")) return true

        val annotationKey = "gen$upperCamelCase"
        val annotationHiddenKey = "genHidden$upperCamelCase"
        if (dataClassAnnotationFeatures.containsKey(annotationKey)) {
            return dataClassAnnotationFeatures[annotationKey]!!
        }
        if (dataClassAnnotationFeatures.containsKey(annotationHiddenKey)) {
            return dataClassAnnotationFeatures[annotationHiddenKey]!!
        }

        if (cliArgs.contains("--all")) return true
        if (hidden) return true

        return when (this) {
            FeatureFlag.SETTERS ->
                !FeatureFlag.CONSTRUCTOR() && !FeatureFlag.BUILDER() && fields.any { !it.isFinal }
            FeatureFlag.BUILDER -> cliArgs.contains(FLAG_BUILDER_PROTECTED_SETTERS)
                    || fields.any { it.hasDefault }
                    || onByDefault
            FeatureFlag.CONSTRUCTOR -> !FeatureFlag.BUILDER()
            FeatureFlag.PARCELABLE -> "Parcelable" in superInterfaces
            FeatureFlag.AIDL -> fileInfo.mainClass.nameAsString == ClassName && FeatureFlag.PARCELABLE()
            FeatureFlag.IMPLICIT_NONNULL -> fields.any { it.isNullable }
                    && fields.none { "@$NonNull" in it.annotations }
            else -> onByDefault
        }
    }

    val FeatureFlag.hidden: Boolean
        get(): Boolean {
            val annotationHiddenKey = "genHidden$upperCamelCase"
            if (dataClassAnnotationFeatures.containsKey(annotationHiddenKey)) {
                return dataClassAnnotationFeatures[annotationHiddenKey]!!
            }
            return when {
                cliArgs.contains("--hidden-$kebabCase") -> true
                this == FeatureFlag.BUILD_UPON -> FeatureFlag.BUILDER.hidden
                else -> false
            }
        }



    inline operator fun <R> invoke(f: ClassPrinter.() -> R): R = run(f)

    var BuilderClass = CANONICAL_BUILDER_CLASS
    var BuilderType = BuilderClass + genericArgs
    val customBaseBuilderAst: ClassOrInterfaceDeclaration? by lazy {
        nestedClasses.find { it.nameAsString == BASE_BUILDER_CLASS }
    }

    val suppressedMembers by lazy {
        getSuppressedMembers(classAst)
    }
    val builderSuppressedMembers by lazy {
        getSuppressedMembers(customBaseBuilderAst) + suppressedMembers.mapNotNull {
            if (it.startsWith("$CANONICAL_BUILDER_CLASS.")) {
                it.removePrefix("$CANONICAL_BUILDER_CLASS.")
            } else {
                null
            }
        }
    }

    private fun getSuppressedMembers(clazz: ClassOrInterfaceDeclaration?): List<String> {
        return clazz
                ?.annotations
                ?.find { it.nameAsString == DataClassSuppress }
                ?.as_<SingleMemberAnnotationExpr>()
                ?.memberValue
                ?.run {
                    when (this) {
                        is ArrayInitializerExpr -> values.map { it.asLiteralStringValueExpr().value }
                        is StringLiteralExpr -> listOf(value)
                        else -> abort("Can't parse annotation arg: $this")
                    }
                }
                ?: emptyList()
    }

    fun isMethodGenerationSuppressed(name: String, vararg argTypes: String): Boolean {
        return name in suppressedMembers || hasMethod(name, *argTypes)
    }

    fun hasMethod(name: String, vararg argTypes: String): Boolean {
        val members: List<CallableDeclaration<*>> =
                if (name == ClassName) classAst.constructors else classAst.methods
        return members.any {
            it.name.asString() == name &&
                    it.parameters.map { it.type.asString() } == argTypes.toList()
        }
    }

    val lazyTransientFields = classAst.fields
            .filter { it.isTransient && !it.isStatic }
            .mapIndexed { i, node -> FieldInfo(index = i, fieldAst = node, classInfo = this) }
            .filter { hasMethod("lazyInit${it.NameUpperCamel}") }

    val extendsParcelableClass by lazy {
        Parcelable !in superInterfaces && superClass != null
    }

    init {
        val builderFactoryOverride = classAst.methods.find {
            it.isStatic && it.nameAsString == "builder"
        }
        if (builderFactoryOverride != null) {
            BuilderClass = (builderFactoryOverride.type as ClassOrInterfaceType).nameAsString
            BuilderType = builderFactoryOverride.type.asString()
        } else {
            val builderExtension = classAst
                    .childNodes
                    .filterIsInstance(TypeDeclaration::class.java)
                    .find { it.nameAsString == CANONICAL_BUILDER_CLASS }
            if (builderExtension != null) {
                BuilderClass = BASE_BUILDER_CLASS
                val tp = (builderExtension as ClassOrInterfaceDeclaration).typeParameters
                BuilderType = if (tp.isEmpty()) BuilderClass
                else "$BuilderClass<${tp.map { it.nameAsString }.joinToString(", ")}>"
            }
        }
    }
}