/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.settingslib.metadata

import java.util.TreeMap
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.ProcessingEnvironment
import javax.annotation.processing.RoundEnvironment
import javax.lang.model.SourceVersion
import javax.lang.model.element.AnnotationMirror
import javax.lang.model.element.AnnotationValue
import javax.lang.model.element.Element
import javax.lang.model.element.ElementKind
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.Modifier
import javax.lang.model.element.TypeElement
import javax.lang.model.type.TypeMirror
import javax.tools.Diagnostic

/** Processor to gather preference screens annotated with `@ProvidePreferenceScreen`. */
class PreferenceScreenAnnotationProcessor : AbstractProcessor() {
    private val screens = TreeMap<String, ConstructorType>()
    private val overlays = mutableMapOf<String, String>()
    private val contextType: TypeMirror by lazy {
        processingEnv.elementUtils.getTypeElement("android.content.Context").asType()
    }

    private var options: Map<String, Any?>? = null
    private lateinit var annotationElement: TypeElement
    private lateinit var optionsElement: TypeElement
    private lateinit var screenType: TypeMirror

    override fun getSupportedAnnotationTypes() = setOf(ANNOTATION, OPTIONS)

    override fun getSupportedSourceVersion(): SourceVersion = SourceVersion.latestSupported()

    override fun init(processingEnv: ProcessingEnvironment) {
        super.init(processingEnv)
        val elementUtils = processingEnv.elementUtils
        annotationElement = elementUtils.getTypeElement(ANNOTATION)
        optionsElement = elementUtils.getTypeElement(OPTIONS)
        screenType = elementUtils.getTypeElement("$PACKAGE.$PREFERENCE_SCREEN_METADATA").asType()
    }

    override fun process(
        annotations: MutableSet<out TypeElement>,
        roundEnv: RoundEnvironment,
    ): Boolean {
        roundEnv.getElementsAnnotatedWith(optionsElement).singleOrNull()?.run {
            if (options != null) error("@$OPTIONS_NAME is already specified: $options", this)
            options =
                annotationMirrors
                    .single { it.isElement(optionsElement) }
                    .elementValues
                    .entries
                    .associate { it.key.simpleName.toString() to it.value.value }
        }
        for (element in roundEnv.getElementsAnnotatedWith(annotationElement)) {
            (element as? TypeElement)?.process()
        }
        if (roundEnv.processingOver()) codegen()
        return false
    }

    private fun TypeElement.process() {
        if (kind != ElementKind.CLASS || modifiers.contains(Modifier.ABSTRACT)) {
            error("@$ANNOTATION_NAME must be added to non abstract class", this)
            return
        }
        if (!processingEnv.typeUtils.isAssignable(asType(), screenType)) {
            error("@$ANNOTATION_NAME must be added to $PREFERENCE_SCREEN_METADATA subclass", this)
            return
        }
        val constructorType = getConstructorType()
        if (constructorType == null) {
            error(
                "Class must be an object, or has single public constructor that " +
                    "accepts no parameter or a Context parameter",
                this,
            )
            return
        }
        val screenQualifiedName = qualifiedName.toString()
        screens[screenQualifiedName] = constructorType
        val annotation = annotationMirrors.single { it.isElement(annotationElement) }
        val overlay = annotation.getOverlay()
        if (overlay != null) {
            overlays.put(overlay, screenQualifiedName)?.let {
                error("$overlay has been overlaid by $it", this)
            }
        }
    }

    private fun codegen() {
        val collector = (options?.get("codegenCollector") as? String) ?: DEFAULT_COLLECTOR
        if (collector.isEmpty()) return
        val parts = collector.split('/')
        if (parts.size == 3) {
            generateCode(parts[0], parts[1], parts[2])
        } else {
            throw IllegalArgumentException(
                "Collector option '$collector' does not follow 'PKG/CLASS/METHOD' format"
            )
        }
    }

    private fun generateCode(outputPkg: String, outputClass: String, outputFun: String) {
        for ((overlay, screen) in overlays) {
            if (screens.remove(overlay) == null) {
                warn("$overlay is overlaid by $screen but not annotated with @$ANNOTATION_NAME")
            } else {
                processingEnv.messager.printMessage(
                    Diagnostic.Kind.NOTE,
                    "$overlay is overlaid by $screen",
                )
            }
        }
        processingEnv.filer.createSourceFile("$outputPkg.$outputClass").openWriter().use {
            it.write("package $outputPkg;\n\n")
            it.write("import $PACKAGE.$PREFERENCE_SCREEN_METADATA;\n\n")
            it.write("// Generated by annotation processor for @$ANNOTATION_NAME\n")
            it.write("public final class $outputClass {\n")
            it.write("  private $outputClass() {}\n\n")
            it.write(
                "  public static java.util.List<$PREFERENCE_SCREEN_METADATA> " +
                    "$outputFun(android.content.Context context) {\n"
            )
            it.write(
                "    java.util.ArrayList<$PREFERENCE_SCREEN_METADATA> screens = " +
                    "new java.util.ArrayList<>(${screens.size});\n"
            )
            for ((screen, constructorType) in screens) {
                when (constructorType) {
                    ConstructorType.DEFAULT -> it.write("    screens.add(new $screen());\n")
                    ConstructorType.CONTEXT -> it.write("    screens.add(new $screen(context));\n")
                    ConstructorType.SINGLETON -> it.write("    screens.add($screen.INSTANCE);\n")
                }
            }
            for ((overlay, screen) in overlays) {
                it.write("    // $overlay is overlaid by $screen\n")
            }
            it.write("    return screens;\n")
            it.write("  }\n")
            it.write("}")
        }
    }

    private fun AnnotationMirror.isElement(element: TypeElement) =
        processingEnv.typeUtils.isSameType(annotationType.asElement().asType(), element.asType())

    private fun AnnotationMirror.getOverlay(): String? {
        for ((key, value) in elementValues) {
            if (key.simpleName.contentEquals("overlay")) {
                return if (value.isDefaultClassValue(key)) null else value.value.toString()
            }
        }
        return null
    }

    private fun AnnotationValue.isDefaultClassValue(key: ExecutableElement) =
        processingEnv.typeUtils.isSameType(
            value as TypeMirror,
            key.defaultValue.value as TypeMirror,
        )

    private fun TypeElement.getConstructorType(): ConstructorType? {
        var constructor: ExecutableElement? = null
        for (element in enclosedElements) {
            if (element.isKotlinObject()) return ConstructorType.SINGLETON
            if (element.kind != ElementKind.CONSTRUCTOR) continue
            if (!element.modifiers.contains(Modifier.PUBLIC)) continue
            if (constructor != null) return null
            constructor = element as ExecutableElement
        }
        return constructor?.parameters?.run {
            when {
                isEmpty() -> ConstructorType.DEFAULT
                size == 1 && processingEnv.typeUtils.isSameType(this[0].asType(), contextType) ->
                    ConstructorType.CONTEXT
                else -> null
            }
        }
    }

    private fun Element.isKotlinObject() =
        kind == ElementKind.FIELD &&
            modifiers.run { contains(Modifier.PUBLIC) && contains(Modifier.STATIC) } &&
            simpleName.toString() == "INSTANCE"

    private fun warn(msg: CharSequence) =
        processingEnv.messager.printMessage(Diagnostic.Kind.WARNING, msg)

    private fun error(msg: CharSequence, element: Element) =
        processingEnv.messager.printMessage(Diagnostic.Kind.ERROR, msg, element)

    private enum class ConstructorType {
        DEFAULT, // default constructor with no parameter
        CONTEXT, // constructor with a Context parameter
        SINGLETON, // Kotlin object class
    }

    companion object {
        private const val PACKAGE = "com.android.settingslib.metadata"
        private const val ANNOTATION_NAME = "ProvidePreferenceScreen"
        private const val ANNOTATION = "$PACKAGE.$ANNOTATION_NAME"
        private const val PREFERENCE_SCREEN_METADATA = "PreferenceScreenMetadata"

        private const val OPTIONS_NAME = "ProvidePreferenceScreenOptions"
        private const val OPTIONS = "$PACKAGE.$OPTIONS_NAME"
        private const val DEFAULT_COLLECTOR = "$PACKAGE/PreferenceScreenCollector/get"
    }
}
