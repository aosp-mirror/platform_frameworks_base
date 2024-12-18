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

package com.android.systemfeatures

import android.annotation.SdkConstant
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.CodeBlock
import com.squareup.javapoet.FieldSpec
import com.squareup.javapoet.JavaFile
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.ParameterizedTypeName
import com.squareup.javapoet.TypeSpec
import java.io.IOException
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.ProcessingEnvironment
import javax.annotation.processing.RoundEnvironment
import javax.lang.model.SourceVersion
import javax.lang.model.element.Modifier
import javax.lang.model.element.TypeElement
import javax.lang.model.element.VariableElement
import javax.tools.Diagnostic

/*
 * Simple Java code generator for computing metadata for system features.
 *
 * <p>The output is a single class file, `com.android.internal.pm.SystemFeaturesMetadata`, with
 * properties computed from feature constant definitions in the PackageManager class. This
 * class is only produced if the processed environment includes PackageManager; all other
 * invocations are ignored. The generated API is as follows:
 *
 * <pre>
 * package android.content.pm;
 * public final class SystemFeaturesMetadata {
 *     public static final int SDK_FEATURE_COUNT;
 *     // @return [0, SDK_FEATURE_COUNT) if an SDK-defined system feature, -1 otherwise.
 *     public static int maybeGetSdkFeatureIndex(String featureName);
 * }
 * </pre>
 */
class SystemFeaturesMetadataProcessor : AbstractProcessor() {

    private lateinit var packageManagerType: TypeElement

    override fun getSupportedSourceVersion(): SourceVersion = SourceVersion.latestSupported()

    override fun getSupportedAnnotationTypes() = setOf(SDK_CONSTANT_ANNOTATION_NAME)

    override fun init(processingEnv: ProcessingEnvironment) {
        super.init(processingEnv)
        packageManagerType =
            processingEnv.elementUtils.getTypeElement("android.content.pm.PackageManager")!!
    }

    override fun process(annotations: Set<TypeElement>, roundEnv: RoundEnvironment): Boolean {
        if (roundEnv.processingOver()) {
            return false
        }

        // Collect all FEATURE-annotated fields from PackageManager, and
        //  1) Use the field values to de-duplicate, as there can be multiple FEATURE_* fields that
        //     map to the same feature string name value.
        //  2) Ensure they're sorted to ensure consistency and determinism between builds.
        val featureVarNames =
            roundEnv
                .getElementsAnnotatedWith(SdkConstant::class.java)
                .asSequence()
                .filter {
                    it.enclosingElement == packageManagerType &&
                        it.getAnnotation(SdkConstant::class.java).value ==
                            SdkConstant.SdkConstantType.FEATURE
                }
                .mapNotNull { element ->
                    (element as? VariableElement)?.let { varElement ->
                        varElement.getConstantValue()?.toString() to
                            varElement.simpleName.toString()
                    }
                }
                .toMap()
                .values
                .sorted()
                .toList()

        if (featureVarNames.isEmpty()) {
            // This is fine, and happens for any environment that doesn't include PackageManager.
            return false
        }

        val systemFeatureMetadata =
            TypeSpec.classBuilder("SystemFeaturesMetadata")
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addJavadoc("@hide")
                .addFeatureCount(featureVarNames)
                .addFeatureIndexLookup(featureVarNames)
                .build()

        try {
            JavaFile.builder("com.android.internal.pm", systemFeatureMetadata)
                .skipJavaLangImports(true)
                .build()
                .writeTo(processingEnv.filer)
        } catch (e: IOException) {
            processingEnv.messager.printMessage(
                Diagnostic.Kind.ERROR,
                "Failed to write file: ${e.message}",
            )
        }

        return true
    }

    private fun TypeSpec.Builder.addFeatureCount(
        featureVarNames: Collection<String>
    ): TypeSpec.Builder {
        return addField(
            FieldSpec.builder(Int::class.java, "SDK_FEATURE_COUNT")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .addJavadoc(
                    "# of {@link android.annotation.SdkConstant}` features in PackageManager."
                )
                .addJavadoc("\n\n@hide")
                .initializer("\$L", featureVarNames.size)
                .build()
        )
    }

    private fun TypeSpec.Builder.addFeatureIndexLookup(
        featureVarNames: Collection<String>
    ): TypeSpec.Builder {
        // NOTE: This was initially implemented in terms of a single, long switch() statement.
        // However, this resulted in:
        //   1) relatively large compiled code size for the lookup method (~20KB)
        //   2) worse runtime lookup performance than a simple ArraySet
        // The ArraySet approach adds just ~1KB to the code/image and is 2x faster at runtime.

        // Provide the initial capacity of the ArraySet for efficiency.
        addField(
            FieldSpec.builder(
                    ParameterizedTypeName.get(ARRAYSET_CLASS, ClassName.get(String::class.java)),
                    "sFeatures",
                )
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("new ArraySet<>(\$L)", featureVarNames.size)
                .build()
        )

        // Use a temp array + Collections.addAll() to minimizes the generated code size.
        addStaticBlock(
            CodeBlock.builder()
                .add("final \$T[] features = {\n", String::class.java)
                .indent()
                .apply { featureVarNames.forEach { add("\$T.\$N,\n", PACKAGEMANAGER_CLASS, it) } }
                .unindent()
                .addStatement("}")
                .addStatement("\$T.addAll(sFeatures, features)", COLLECTIONS_CLASS)
                .build()
        )

        // Use ArraySet.indexOf to provide the implicit feature index mapping.
        return addMethod(
            MethodSpec.methodBuilder("maybeGetSdkFeatureIndex")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addJavadoc("@return an index in [0, SDK_FEATURE_COUNT) for features defined ")
                .addJavadoc("in PackageManager, else -1.")
                .addJavadoc("\n\n@hide")
                .returns(Int::class.java)
                .addParameter(String::class.java, "featureName")
                .addStatement("return sFeatures.indexOf(featureName)")
                .build()
        )
    }

    companion object {
        private val SDK_CONSTANT_ANNOTATION_NAME = SdkConstant::class.qualifiedName
        private val PACKAGEMANAGER_CLASS = ClassName.get("android.content.pm", "PackageManager")
        private val ARRAYSET_CLASS = ClassName.get("android.util", "ArraySet")
        private val COLLECTIONS_CLASS = ClassName.get("java.util", "Collections")
    }
}
