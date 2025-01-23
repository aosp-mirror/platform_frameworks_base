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
    private val screens = mutableListOf<Screen>()
    private val bundleType: TypeMirror by lazy {
        processingEnv.elementUtils.getTypeElement("android.os.Bundle").asType()
    }
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
        fun reportConstructorError() =
            error(
                "Must have only one public constructor: constructor(), " +
                    "constructor(Context), constructor(Bundle) or constructor(Context, Bundle)",
                this,
            )
        val constructor = findConstructor()
        if (constructor == null || constructor.parameters.size > 2) {
            reportConstructorError()
            return
        }
        val constructorHasContextParameter = constructor.hasParameter(0, contextType)
        var index = if (constructorHasContextParameter) 1 else 0
        val annotation = annotationMirrors.single { it.isElement(annotationElement) }
        val key = annotation.fieldValue<String>("value")!!
        val overlay = annotation.fieldValue<Boolean>("overlay") == true
        val parameterized = annotation.fieldValue<Boolean>("parameterized") == true
        var parametersHasContextParameter = false
        if (parameterized) {
            val parameters = findParameters()
            if (parameters == null) {
                error("require a static 'parameters()' or 'parameters(Context)' method", this)
                return
            }
            parametersHasContextParameter = parameters
            if (constructor.hasParameter(index, bundleType)) {
                index++
            } else {
                error(
                    "Parameterized screen constructor must be" +
                        "constructor(Bundle) or constructor(Context, Bundle)",
                    this,
                )
                return
            }
        }
        if (index == constructor.parameters.size) {
            screens.add(
                Screen(
                    key,
                    overlay,
                    parameterized,
                    annotation.fieldValue<Boolean>("parameterizedMigration") == true,
                    qualifiedName.toString(),
                    constructorHasContextParameter,
                    parametersHasContextParameter,
                )
            )
        } else {
            reportConstructorError()
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
        // sort by screen keys to make the output deterministic and naturally fit to FixedArrayMap
        screens.sort()
        processingEnv.filer.createSourceFile("$outputPkg.$outputClass").openWriter().use {
            it.write("package $outputPkg;\n\n")
            it.write("import android.content.Context;\n")
            it.write("import android.os.Bundle;\n")
            it.write("import $PACKAGE.FixedArrayMap;\n")
            it.write("import $PACKAGE.FixedArrayMap.OrderedInitializer;\n")
            it.write("import $PACKAGE.$PREFERENCE_SCREEN_METADATA;\n")
            it.write("import $PACKAGE.$FACTORY;\n")
            it.write("import $PACKAGE.$PARAMETERIZED_FACTORY;\n")
            it.write("import kotlinx.coroutines.flow.Flow;\n")
            it.write("\n// Generated by annotation processor for @$ANNOTATION_NAME\n")
            it.write("public final class $outputClass {\n")
            it.write("  private $outputClass() {}\n\n")
            it.write("  public static FixedArrayMap<String, $FACTORY> $outputFun() {\n")
            val size = screens.size
            it.write("    return new FixedArrayMap<>($size, $outputClass::init);\n")
            it.write("  }\n\n")
            fun Screen.write() {
                it.write("    screens.put(\"$key\", ")
                if (parameterized) {
                    it.write("new $PARAMETERIZED_FACTORY() {\n")
                    it.write("      @Override public PreferenceScreenMetadata create")
                    it.write("(Context context, Bundle args) {\n")
                    it.write("        return new $klass(")
                    if (constructorHasContextParameter) it.write("context, ")
                    it.write("args);\n")
                    it.write("      }\n\n")
                    it.write("      @Override public Flow<Bundle> parameters(Context context) {\n")
                    it.write("        return $klass.parameters(")
                    if (parametersHasContextParameter) it.write("context")
                    it.write(");\n")
                    it.write("      }\n")
                    if (parameterizedMigration) {
                        it.write("\n      @Override public boolean acceptEmptyArguments()")
                        it.write(" { return true; }\n")
                    }
                    it.write("    });")
                } else {
                    it.write("context -> new $klass(")
                    if (constructorHasContextParameter) it.write("context")
                    it.write("));")
                }
                if (overlay) it.write(" // overlay")
                it.write("\n")
            }
            it.write("  private static void init(OrderedInitializer<String, $FACTORY> screens) {\n")
            var index = 0
            while (index < size) {
                val screen = screens[index]
                var next = index + 1
                while (next < size && screen.key == screens[next].key) next++
                val n = next - index
                if (n == 1) {
                    screen.write()
                } else if (n == 2 && screen.overlay && !screens[index + 1].overlay) {
                    it.write("    // ${screen.klass} overlays ${screens[index + 1].klass}\n")
                    screen.write()
                } else {
                    val msg = StringBuilder("${screen.key} is associated to")
                    for (i in index until next) msg.append(" ${screens[i]}")
                    processingEnv.messager.printMessage(Diagnostic.Kind.ERROR, msg)
                }
                index = next
            }
            it.write("  }\n}")
        }
    }

    private fun AnnotationMirror.isElement(element: TypeElement) =
        annotationType.asElement().asType().isSameType(element.asType())

    @Suppress("UNCHECKED_CAST")
    private fun <T> AnnotationMirror.fieldValue(name: String): T? = field(name)?.value as? T

    private fun AnnotationMirror.field(name: String): AnnotationValue? {
        for ((key, value) in elementValues) {
            if (key.simpleName.contentEquals(name)) return value
        }
        return null
    }

    private fun TypeElement.findConstructor(): ExecutableElement? {
        var constructor: ExecutableElement? = null
        for (element in enclosedElements) {
            if (element.kind != ElementKind.CONSTRUCTOR) continue
            if (!element.modifiers.contains(Modifier.PUBLIC)) continue
            if (constructor != null) return null
            constructor = element as ExecutableElement
        }
        return constructor
    }

    private fun TypeElement.findParameters(): Boolean? {
        for (element in enclosedElements) {
            if (element.kind != ElementKind.METHOD) continue
            if (!element.modifiers.contains(Modifier.PUBLIC)) continue
            if (!element.modifiers.contains(Modifier.STATIC)) continue
            if (!element.simpleName.contentEquals("parameters")) return null
            val parameters = (element as ExecutableElement).parameters
            if (parameters.isEmpty()) return false
            if (parameters.size == 1 && parameters[0].asType().isSameType(contextType)) return true
            error("parameters method should have no parameter or a Context parameter", element)
            return null
        }
        return null
    }

    private fun ExecutableElement.hasParameter(index: Int, typeMirror: TypeMirror) =
        index < parameters.size && parameters[index].asType().isSameType(typeMirror)

    private fun TypeMirror.isSameType(typeMirror: TypeMirror) =
        processingEnv.typeUtils.isSameType(this, typeMirror)

    private fun warn(msg: CharSequence) =
        processingEnv.messager.printMessage(Diagnostic.Kind.WARNING, msg)

    private fun error(msg: CharSequence, element: Element) =
        processingEnv.messager.printMessage(Diagnostic.Kind.ERROR, msg, element)

    private data class Screen(
        val key: String,
        val overlay: Boolean,
        val parameterized: Boolean,
        val parameterizedMigration: Boolean,
        val klass: String,
        val constructorHasContextParameter: Boolean,
        val parametersHasContextParameter: Boolean,
    ) : Comparable<Screen> {
        override fun compareTo(other: Screen): Int {
            val diff = key.compareTo(other.key)
            return if (diff != 0) diff else other.overlay.compareTo(overlay)
        }
    }

    companion object {
        private const val PACKAGE = "com.android.settingslib.metadata"
        private const val ANNOTATION_NAME = "ProvidePreferenceScreen"
        private const val ANNOTATION = "$PACKAGE.$ANNOTATION_NAME"
        private const val PREFERENCE_SCREEN_METADATA = "PreferenceScreenMetadata"
        private const val FACTORY = "PreferenceScreenMetadataFactory"
        private const val PARAMETERIZED_FACTORY = "PreferenceScreenMetadataParameterizedFactory"

        private const val OPTIONS_NAME = "ProvidePreferenceScreenOptions"
        private const val OPTIONS = "$PACKAGE.$OPTIONS_NAME"
        private const val DEFAULT_COLLECTOR = "$PACKAGE/PreferenceScreenCollector/get"
    }
}
