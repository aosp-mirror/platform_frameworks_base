/*
 * Copyright (C) 2020 The Android Open Source Project
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

import com.github.javaparser.JavaParser
import com.github.javaparser.ParseProblemException
import com.github.javaparser.ParseResult
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.AnnotationExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.NormalAnnotationExpr
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration
import com.github.javaparser.resolution.types.ResolvedPrimitiveType
import com.github.javaparser.resolution.types.ResolvedReferenceType
import com.github.javaparser.symbolsolver.JavaSymbolSolver
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserClassDeclaration
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.MemoryTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.ParameterizedTypeName
import com.squareup.javapoet.TypeName
import java.nio.file.Path
import java.util.Optional

class PersistenceInfo(
    val name: String,
    val root: ClassFieldInfo,
    val path: Path
)

sealed class FieldInfo {
    abstract val name: String
    abstract val xmlName: String?
    abstract val type: TypeName
    abstract val isRequired: Boolean
}

class PrimitiveFieldInfo(
    override val name: String,
    override val xmlName: String?,
    override val type: TypeName,
    override val isRequired: Boolean
) : FieldInfo()

class StringFieldInfo(
    override val name: String,
    override val xmlName: String?,
    override val isRequired: Boolean
) : FieldInfo() {
    override val type: TypeName = ClassName.get(String::class.java)
}

class ClassFieldInfo(
    override val name: String,
    override val xmlName: String?,
    override val type: ClassName,
    override val isRequired: Boolean,
    val fields: List<FieldInfo>
) : FieldInfo()

class ListFieldInfo(
    override val name: String,
    override val xmlName: String?,
    override val type: ParameterizedTypeName,
    val element: ClassFieldInfo
) : FieldInfo() {
    override val isRequired: Boolean = true
}

fun parse(files: List<Path>): List<PersistenceInfo> {
    val typeSolver = CombinedTypeSolver().apply { add(ReflectionTypeSolver()) }
    val javaParser = JavaParser(ParserConfiguration()
        .setSymbolResolver(JavaSymbolSolver(typeSolver)))
    val compilationUnits = files.map { javaParser.parse(it).getOrThrow() }
    val memoryTypeSolver = MemoryTypeSolver().apply {
        for (compilationUnit in compilationUnits) {
            for (typeDeclaration in compilationUnit.getNodesByClass<TypeDeclaration<*>>()) {
                val name = typeDeclaration.fullyQualifiedName.getOrNull() ?: continue
                addDeclaration(name, typeDeclaration.resolve())
            }
        }
    }
    typeSolver.add(memoryTypeSolver)
    return mutableListOf<PersistenceInfo>().apply {
        for (compilationUnit in compilationUnits) {
            val classDeclarations = compilationUnit
                .getNodesByClass<ClassOrInterfaceDeclaration>()
                .filter { !it.isInterface && (!it.isNestedType || it.isStatic) }
            this += classDeclarations.mapNotNull { parsePersistenceInfo(it) }
        }
    }
}

private fun parsePersistenceInfo(classDeclaration: ClassOrInterfaceDeclaration): PersistenceInfo? {
    val annotation = classDeclaration.getAnnotationByName("XmlPersistence").getOrNull()
        ?: return null
    val rootClassName = classDeclaration.nameAsString
    val name = annotation.getMemberValue("value")?.stringLiteralValue
        ?: "${rootClassName}Persistence"
    val rootXmlName = classDeclaration.getAnnotationByName("XmlName").getOrNull()
        ?.getMemberValue("value")?.stringLiteralValue
    val root = parseClassFieldInfo(
        rootXmlName ?: rootClassName, rootXmlName, true, classDeclaration
    )
    val path = classDeclaration.findCompilationUnit().get().storage.get().path
        .resolveSibling("$name.java")
    return PersistenceInfo(name, root, path)
}

private fun parseClassFieldInfo(
    name: String,
    xmlName: String?,
    isRequired: Boolean,
    classDeclaration: ClassOrInterfaceDeclaration
): ClassFieldInfo {
    val fields = classDeclaration.fields.filterNot { it.isStatic }.map { parseFieldInfo(it) }
    val type = classDeclaration.resolve().typeName
    return ClassFieldInfo(name, xmlName, type, isRequired, fields)
}

private fun parseFieldInfo(field: FieldDeclaration): FieldInfo {
    require(field.isPublic && field.isFinal)
    val variable = field.variables.single()
    val name = variable.nameAsString
    val annotations = field.annotations + variable.type.annotations
    val annotation = annotations.getByName("XmlName")
    val xmlName = annotation?.getMemberValue("value")?.stringLiteralValue
    val isRequired = annotations.getByName("NonNull") != null
    return when (val type = variable.type.resolve()) {
        is ResolvedPrimitiveType -> {
            val primitiveType = type.typeName
            PrimitiveFieldInfo(name, xmlName, primitiveType, true)
        }
        is ResolvedReferenceType -> {
            when (type.qualifiedName) {
                Boolean::class.javaObjectType.name, Byte::class.javaObjectType.name,
                Short::class.javaObjectType.name, Char::class.javaObjectType.name,
                Integer::class.javaObjectType.name, Long::class.javaObjectType.name,
                Float::class.javaObjectType.name, Double::class.javaObjectType.name ->
                    PrimitiveFieldInfo(name, xmlName, type.typeName, isRequired)
                String::class.java.name -> StringFieldInfo(name, xmlName, isRequired)
                List::class.java.name -> {
                    requireNotNull(xmlName)
                    val elementType = type.typeParametersValues().single()
                    require(elementType is ResolvedReferenceType)
                    val listType = ParameterizedTypeName.get(
                        ClassName.get(List::class.java), elementType.typeName
                    )
                    val element = parseClassFieldInfo(
                        "(element)", xmlName, true, elementType.classDeclaration
                    )
                    ListFieldInfo(name, xmlName, listType, element)
                }
                else -> parseClassFieldInfo(name, xmlName, isRequired, type.classDeclaration)
            }
        }
        else -> error(type)
    }
}

private fun <T> ParseResult<T>.getOrThrow(): T =
    if (isSuccessful) {
        result.get()
    } else {
        throw ParseProblemException(problems)
    }

private inline fun <reified T : Node> Node.getNodesByClass(): List<T> =
    getNodesByClass(T::class.java)

private fun <T : Node> Node.getNodesByClass(klass: Class<T>): List<T> = mutableListOf<T>().apply {
    if (klass.isInstance(this@getNodesByClass)) {
        this += klass.cast(this@getNodesByClass)
    }
    for (childNode in childNodes) {
        this += childNode.getNodesByClass(klass)
    }
}

private fun <T> Optional<T>.getOrNull(): T? = orElse(null)

private fun List<AnnotationExpr>.getByName(name: String): AnnotationExpr? =
    find { it.name.identifier == name }

private fun AnnotationExpr.getMemberValue(name: String): Expression? =
    when (this) {
        is NormalAnnotationExpr -> pairs.find { it.nameAsString == name }?.value
        is SingleMemberAnnotationExpr -> if (name == "value") memberValue else null
        else -> null
    }

private val Expression.stringLiteralValue: String
    get() {
        require(this is StringLiteralExpr)
        return value
    }

private val ResolvedReferenceType.classDeclaration: ClassOrInterfaceDeclaration
    get() {
        val resolvedClassDeclaration = typeDeclaration
        require(resolvedClassDeclaration is JavaParserClassDeclaration)
        return resolvedClassDeclaration.wrappedNode
    }

private val ResolvedPrimitiveType.typeName: TypeName
    get() =
        when (this) {
            ResolvedPrimitiveType.BOOLEAN -> TypeName.BOOLEAN
            ResolvedPrimitiveType.BYTE -> TypeName.BYTE
            ResolvedPrimitiveType.SHORT -> TypeName.SHORT
            ResolvedPrimitiveType.CHAR -> TypeName.CHAR
            ResolvedPrimitiveType.INT -> TypeName.INT
            ResolvedPrimitiveType.LONG -> TypeName.LONG
            ResolvedPrimitiveType.FLOAT -> TypeName.FLOAT
            ResolvedPrimitiveType.DOUBLE -> TypeName.DOUBLE
        }

// This doesn't support type parameters.
private val ResolvedReferenceType.typeName: TypeName
    get() = typeDeclaration.typeName

private val ResolvedReferenceTypeDeclaration.typeName: ClassName
    get() {
        val packageName = packageName
        val classNames = className.split(".")
        val topLevelClassName = classNames.first()
        val nestedClassNames = classNames.drop(1)
        return ClassName.get(packageName, topLevelClassName, *nestedClassNames.toTypedArray())
    }
