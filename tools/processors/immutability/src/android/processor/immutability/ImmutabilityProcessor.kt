/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.processor.immutability

import com.sun.tools.javac.code.Symbol
import com.sun.tools.javac.code.Type
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.ProcessingEnvironment
import javax.annotation.processing.RoundEnvironment
import javax.lang.model.SourceVersion
import javax.lang.model.element.Element
import javax.lang.model.element.ElementKind
import javax.lang.model.element.Modifier
import javax.lang.model.element.TypeElement
import javax.lang.model.type.TypeKind
import javax.lang.model.type.TypeMirror
import javax.tools.Diagnostic

val IMMUTABLE_ANNOTATION_NAME = Immutable::class.qualifiedName

class ImmutabilityProcessor : AbstractProcessor() {

    companion object {
        /**
         * Types that are already immutable.
         */
        private val IGNORED_TYPES = listOf(
            "java.io.File",
            "java.lang.Boolean",
            "java.lang.Byte",
            "java.lang.CharSequence",
            "java.lang.Character",
            "java.lang.Double",
            "java.lang.Float",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Short",
            "java.lang.String",
            "java.lang.Void",
        )
    }

    private lateinit var collectionType: TypeMirror
    private lateinit var mapType: TypeMirror

    private lateinit var ignoredTypes: List<TypeMirror>

    private val seenTypes = mutableSetOf<Type>()

    override fun getSupportedSourceVersion() = SourceVersion.latest()!!

    override fun getSupportedAnnotationTypes() = setOf(Immutable::class.qualifiedName)

    override fun init(processingEnv: ProcessingEnvironment) {
        super.init(processingEnv)
        collectionType = processingEnv.erasedType("java.util.Collection")
        mapType = processingEnv.erasedType("java.util.Map")
        ignoredTypes = IGNORED_TYPES.map { processingEnv.elementUtils.getTypeElement(it).asType() }
    }

    override fun process(
        annotations: MutableSet<out TypeElement>,
        roundEnvironment: RoundEnvironment
    ): Boolean {
        annotations.find {
            it.qualifiedName.toString() == IMMUTABLE_ANNOTATION_NAME
        } ?: return false
        roundEnvironment.getElementsAnnotatedWith(Immutable::class.java)
            .forEach { visitClass(emptyList(), seenTypes, it, it as Symbol.TypeSymbol) }
        return true
    }

    private fun visitClass(
        parentChain: List<String>,
        seenTypes: MutableSet<Type>,
        elementToPrint: Element,
        classType: Symbol.TypeSymbol,
    ) {
        if (!seenTypes.add(classType.asType())) return
        if (classType.getAnnotation(Immutable.Ignore::class.java) != null) return

        if (classType.getAnnotation(Immutable::class.java) == null) {
            printError(parentChain, elementToPrint,
                MessageUtils.classNotImmutableFailure(classType.simpleName.toString()))
        }

        if (classType.getKind() != ElementKind.INTERFACE) {
            printError(parentChain, elementToPrint, MessageUtils.nonInterfaceClassFailure())
        }

        val filteredElements = classType.enclosedElements
            .filterNot(::isIgnored)

        filteredElements
            .filter { it.getKind() == ElementKind.FIELD }
            .forEach {
                if (it.isStatic) {
                    if (!it.isPrivate) {
                        if (!it.modifiers.contains(Modifier.FINAL)) {
                            printError(parentChain, it, MessageUtils.staticNonFinalFailure())
                        }

                        visitType(parentChain, seenTypes, it, it.type)
                    }
                } else {
                    printError(parentChain, it, MessageUtils.memberNotMethodFailure())
                }
            }

        // Scan inner classes before methods so that any violations isolated to the file prints
        // the error on the class declaration rather than on the method that returns the type.
        // Although it doesn't matter too much either way.
        filteredElements
            .filter { it.getKind() == ElementKind.CLASS }
            .map { it as Symbol.ClassSymbol }
            .forEach {
                visitClass(parentChain, seenTypes, it, it)
            }

        val newChain = parentChain + "$classType"

        filteredElements
            .filter { it.getKind() == ElementKind.METHOD }
            .map { it as Symbol.MethodSymbol }
            .forEach {
                visitMethod(newChain, seenTypes, it)
            }
    }

    private fun visitMethod(
        parentChain: List<String>,
        seenTypes: MutableSet<Type>,
        method: Symbol.MethodSymbol,
    ) {
        val returnType = method.returnType
        val typeName = returnType.toString()
        when (returnType.kind) {
            TypeKind.BOOLEAN,
            TypeKind.BYTE,
            TypeKind.SHORT,
            TypeKind.INT,
            TypeKind.LONG,
            TypeKind.CHAR,
            TypeKind.FLOAT,
            TypeKind.DOUBLE,
            TypeKind.NONE,
            TypeKind.NULL -> {
                // Do nothing
            }
            TypeKind.VOID -> {
                if (!method.isConstructor) {
                    printError(parentChain, method, MessageUtils.voidReturnFailure())
                }
            }
            TypeKind.ARRAY -> {
                printError(parentChain, method, MessageUtils.arrayFailure())
            }
            TypeKind.DECLARED -> {
                visitType(parentChain, seenTypes, method, method.returnType)
            }
            TypeKind.ERROR,
            TypeKind.TYPEVAR,
            TypeKind.WILDCARD,
            TypeKind.PACKAGE,
            TypeKind.EXECUTABLE,
            TypeKind.OTHER,
            TypeKind.UNION,
            TypeKind.INTERSECTION,
            // Java 9+
            // TypeKind.MODULE,
            null -> printError(parentChain, method,
                MessageUtils.genericTypeKindFailure(typeName = typeName))
            else -> printError(parentChain, method,
                MessageUtils.genericTypeKindFailure(typeName = typeName))
        }
    }

    private fun visitType(
        parentChain: List<String>,
        seenTypes: MutableSet<Type>,
        symbol: Symbol,
        type: Type,
        nonInterfaceClassFailure: () -> String = { MessageUtils.nonInterfaceReturnFailure() },
    ) {
        if (type.isPrimitive) return
        if (type.isPrimitiveOrVoid) {
            printError(parentChain, symbol, MessageUtils.voidReturnFailure())
            return
        }

        if (ignoredTypes.any { processingEnv.typeUtils.isSameType(it, type) }) {
            return
        }

        // Collection (and Map) types are ignored for the interface check as they have immutability
        // enforced through a runtime exception which must be verified in a separate runtime test
        val isMap = processingEnv.typeUtils.isAssignable(type, mapType)
        if (!processingEnv.typeUtils.isAssignable(type, collectionType) && !isMap) {
            if (type.isInterface) {
                visitClass(parentChain, seenTypes, symbol,
                    processingEnv.typeUtils.asElement(type) as Symbol.TypeSymbol)
            } else {
                printError(parentChain, symbol, nonInterfaceClassFailure())
                // If the type already isn't an interface, don't scan deeper children
                // to avoid printing an excess amount of errors for a known bad type.
                return
            }
        }

        type.typeArguments.forEachIndexed { index, typeArg ->
            visitType(parentChain, seenTypes, symbol, typeArg) {
                MessageUtils.nonInterfaceReturnFailure(prefix = when {
                    !isMap -> ""
                    index == 0 -> "Key " + typeArg.asElement().simpleName
                    else -> "Value " + typeArg.asElement().simpleName
                }, index = index)
            }
        }
    }

    private fun printError(
        parentChain: List<String>,
        element: Element,
        message: String,
    ) = processingEnv.messager.printMessage(
        Diagnostic.Kind.ERROR,
        // Drop one from the parent chain so that the directly enclosing class isn't logged.
        // It exists in the list at this point in the traversal so that further children can
        // include the right reference.
        parentChain.dropLast(1).joinToString() + "\n\t" + message,
        element,
    )

    private fun ProcessingEnvironment.erasedType(typeName: String) =
        typeUtils.erasure(elementUtils.getTypeElement(typeName).asType())

    private fun isIgnored(symbol: Symbol) =
        symbol.getAnnotation(Immutable.Ignore::class.java) != null
}