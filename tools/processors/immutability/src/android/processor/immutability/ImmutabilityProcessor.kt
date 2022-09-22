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
            "android.os.Parcelable.Creator",
        )

        private val IGNORED_METHODS = listOf(
            "writeToParcel",
        )
    }

    private lateinit var collectionType: TypeMirror
    private lateinit var mapType: TypeMirror

    private lateinit var ignoredTypes: List<TypeMirror>

    private val seenTypesByPolicy = mutableMapOf<Set<Immutable.Policy.Exception>, Set<Type>>()

    override fun getSupportedSourceVersion() = SourceVersion.latest()!!

    override fun getSupportedAnnotationTypes() = setOf(Immutable::class.qualifiedName)

    override fun init(processingEnv: ProcessingEnvironment) {
        super.init(processingEnv)
        collectionType = processingEnv.erasedType("java.util.Collection")!!
        mapType = processingEnv.erasedType("java.util.Map")!!
        ignoredTypes = IGNORED_TYPES.mapNotNull { processingEnv.erasedType(it) }
    }

    override fun process(
        annotations: MutableSet<out TypeElement>,
        roundEnvironment: RoundEnvironment
    ): Boolean {
        annotations.find {
            it.qualifiedName.toString() == IMMUTABLE_ANNOTATION_NAME
        } ?: return false
        roundEnvironment.getElementsAnnotatedWith(Immutable::class.java)
            .forEach {
                visitClass(
                    parentChain = emptyList(),
                    seenTypesByPolicy = seenTypesByPolicy,
                    elementToPrint = it,
                    classType = it as Symbol.TypeSymbol,
                    parentPolicyExceptions = emptySet()
                )
            }
        return true
    }

    /**
     * @return true if any error was encountered at this level or any child level
     */
    private fun visitClass(
        parentChain: List<String>,
        seenTypesByPolicy: MutableMap<Set<Immutable.Policy.Exception>, Set<Type>>,
        elementToPrint: Element,
        classType: Symbol.TypeSymbol,
        parentPolicyExceptions: Set<Immutable.Policy.Exception>,
    ): Boolean {
        if (classType.getAnnotation(Immutable.Ignore::class.java) != null) return false

        val policyAnnotation = classType.getAnnotation(Immutable.Policy::class.java)
        val newPolicyExceptions = parentPolicyExceptions + policyAnnotation?.exceptions.orEmpty()

        // If already seen this type with the same policies applied, skip it
        val seenTypes = seenTypesByPolicy[newPolicyExceptions]
        val type = classType.asType()
        if (seenTypes?.contains(type) == true) return false
        seenTypesByPolicy[newPolicyExceptions] = seenTypes.orEmpty() + type

        val allowFinalClassesFinalFields =
            newPolicyExceptions.contains(Immutable.Policy.Exception.FINAL_CLASSES_WITH_FINAL_FIELDS)

        val filteredElements = classType.enclosedElements
            .filterNot(::isIgnored)

        val hasFieldError = filteredElements
            .filter { it.getKind() == ElementKind.FIELD }
            .fold(false) { anyError, field ->
                if (field.isStatic) {
                    if (!field.isPrivate) {
                        var finalityError = !field.modifiers.contains(Modifier.FINAL)
                        if (finalityError) {
                            printError(parentChain, field, MessageUtils.staticNonFinalFailure())
                        }

                        // Must call visitType first so it doesn't get short circuited by the ||
                        visitType(
                            parentChain = parentChain,
                            seenTypesByPolicy = seenTypesByPolicy,
                            symbol = field,
                            type = field.type,
                            parentPolicyExceptions = parentPolicyExceptions
                        ) || anyError || finalityError
                    }
                    return@fold anyError
                } else {
                    val isFinal = field.modifiers.contains(Modifier.FINAL)
                    if (!isFinal || !allowFinalClassesFinalFields) {
                        printError(parentChain, field, MessageUtils.memberNotMethodFailure())
                        return@fold true
                    }

                    return@fold anyError
                }
            }

        // Scan inner classes before methods so that any violations isolated to the file prints
        // the error on the class declaration rather than on the method that returns the type.
        // Although it doesn't matter too much either way.
        val hasClassError = filteredElements
            .filter { it.getKind() == ElementKind.CLASS }
            .map { it as Symbol.ClassSymbol }
            .fold(false) { anyError, innerClass ->
                // Must call visitClass first so it doesn't get short circuited by the ||
                visitClass(
                    parentChain,
                    seenTypesByPolicy,
                    innerClass,
                    innerClass,
                    newPolicyExceptions
                ) || anyError
            }

        val newChain = parentChain + "$classType"

        val hasMethodError = filteredElements
            .filter { it.getKind() == ElementKind.METHOD }
            .map { it as Symbol.MethodSymbol }
            .filterNot { IGNORED_METHODS.contains(it.name.toString()) }
            .fold(false) { anyError, method ->
                // Must call visitMethod first so it doesn't get short circuited by the ||
                visitMethod(newChain, seenTypesByPolicy, method, newPolicyExceptions) || anyError
            }

        val className = classType.simpleName.toString()
        val isRegularClass = classType.getKind() == ElementKind.CLASS

        var anyError = hasFieldError || hasClassError || hasMethodError

        // If final classes are not considered OR there's a non-field failure, also check for
        // interface/@Immutable, assuming the class is malformed
        if ((isRegularClass && !allowFinalClassesFinalFields) || hasMethodError || hasClassError) {
            if (classType.getAnnotation(Immutable::class.java) == null) {
                printError(
                    parentChain,
                    elementToPrint,
                    MessageUtils.classNotImmutableFailure(className)
                )
                anyError = true
            }

            if (classType.getKind() != ElementKind.INTERFACE) {
                printError(parentChain, elementToPrint, MessageUtils.nonInterfaceClassFailure())
                anyError = true
            }
        }

        if (isRegularClass && !anyError && allowFinalClassesFinalFields
            && !classType.modifiers.contains(Modifier.FINAL)
        ) {
            printError(parentChain, elementToPrint, MessageUtils.classNotFinalFailure(className))
            return true
        }

        return anyError
    }

    /**
     * @return true if any error was encountered at this level or any child level
     */
    private fun visitMethod(
        parentChain: List<String>,
        seenTypesByPolicy: MutableMap<Set<Immutable.Policy.Exception>, Set<Type>>,
        method: Symbol.MethodSymbol,
        parentPolicyExceptions: Set<Immutable.Policy.Exception>,
    ): Boolean {
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
                    return true
                }
            }
            TypeKind.ARRAY -> {
                printError(parentChain, method, MessageUtils.arrayFailure())
                return true
            }
            TypeKind.DECLARED -> {
                return visitType(
                    parentChain,
                    seenTypesByPolicy,
                    method,
                    method.returnType,
                    parentPolicyExceptions
                )
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
            null -> {
                printError(
                    parentChain, method,
                    MessageUtils.genericTypeKindFailure(typeName = typeName)
                )
                return true
            }
            else -> {
                printError(
                    parentChain, method,
                    MessageUtils.genericTypeKindFailure(typeName = typeName)
                )
                return true
            }
        }

        return false
    }

    /**
     * @return true if any error was encountered at this level or any child level
     */
    private fun visitType(
        parentChain: List<String>,
        seenTypesByPolicy: MutableMap<Set<Immutable.Policy.Exception>, Set<Type>>,
        symbol: Symbol,
        type: Type,
        parentPolicyExceptions: Set<Immutable.Policy.Exception>,
        nonInterfaceClassFailure: () -> String = { MessageUtils.nonInterfaceReturnFailure() },
    ): Boolean {
        if (type.isPrimitive) return false
        if (type.isPrimitiveOrVoid) {
            printError(parentChain, symbol, MessageUtils.voidReturnFailure())
            return true
        }

        if (ignoredTypes.any { processingEnv.typeUtils.isAssignable(type, it) }) {
            return false
        }

        val policyAnnotation = symbol.getAnnotation(Immutable.Policy::class.java)
        val newPolicyExceptions = parentPolicyExceptions + policyAnnotation?.exceptions.orEmpty()

        // Collection (and Map) types are ignored for the interface check as they have immutability
        // enforced through a runtime exception which must be verified in a separate runtime test
        val isMap = processingEnv.typeUtils.isAssignable(type, mapType)
        if (!processingEnv.typeUtils.isAssignable(type, collectionType) && !isMap) {
            if (!type.isInterface && !newPolicyExceptions
                    .contains(Immutable.Policy.Exception.FINAL_CLASSES_WITH_FINAL_FIELDS)
            ) {
                printError(parentChain, symbol, nonInterfaceClassFailure())
                return true
            } else {
                return visitClass(
                    parentChain, seenTypesByPolicy, symbol,
                    processingEnv.typeUtils.asElement(type) as Symbol.TypeSymbol,
                    newPolicyExceptions,
                )
            }
        }

        var anyError = false

        type.typeArguments.forEachIndexed { index, typeArg ->
            val argError =
                visitType(parentChain, seenTypesByPolicy, symbol, typeArg, newPolicyExceptions) {
                    MessageUtils.nonInterfaceReturnFailure(
                        prefix = when {
                            !isMap -> ""
                            index == 0 -> "Key " + typeArg.asElement().simpleName
                            else -> "Value " + typeArg.asElement().simpleName
                        }, index = index
                    )
                }
            anyError = anyError || argError
        }

        return anyError
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
        elementUtils.getTypeElement(typeName)?.asType()?.let(typeUtils::erasure)

    private fun isIgnored(symbol: Symbol) =
        symbol.getAnnotation(Immutable.Ignore::class.java) != null
}