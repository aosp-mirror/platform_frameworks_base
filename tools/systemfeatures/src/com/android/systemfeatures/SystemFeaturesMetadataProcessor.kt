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
import com.squareup.javapoet.FieldSpec
import com.squareup.javapoet.JavaFile
import com.squareup.javapoet.TypeSpec
import java.io.IOException
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.ProcessingEnvironment
import javax.annotation.processing.RoundEnvironment
import javax.lang.model.SourceVersion
import javax.lang.model.element.Modifier
import javax.lang.model.element.TypeElement
import javax.tools.Diagnostic

/*
 * Simple Java code generator for computing metadata for system features.
 *
 * <p>The output is a single class file, `com.android.internal.pm.SystemFeaturesMetadata`, with
 * properties computed from feature constant definitions in the PackageManager class. This
 * class is only produced if the processed environment includes PackageManager; all other
 * invocations are ignored.
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

        // We're only interested in feature constants defined in PackageManager.
        var featureCount = 0
        roundEnv.getElementsAnnotatedWith(SdkConstant::class.java).forEach {
            if (
                it.enclosingElement == packageManagerType &&
                    it.getAnnotation(SdkConstant::class.java).value ==
                        SdkConstant.SdkConstantType.FEATURE
            ) {
                featureCount++
            }
        }

        if (featureCount == 0) {
            // This is fine, and happens for any environment that doesn't include PackageManager.
            return false
        }

        val systemFeatureMetadata =
            TypeSpec.classBuilder("SystemFeaturesMetadata")
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addJavadoc("@hide")
                .addField(
                    FieldSpec.builder(Int::class.java, "SDK_FEATURE_COUNT")
                        .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                        .addJavadoc(
                            "The number of `@SdkConstant` features defined in PackageManager."
                        )
                        .addJavadoc("@hide")
                        .initializer("\$L", featureCount)
                        .build()
                )
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

    companion object {
        private val SDK_CONSTANT_ANNOTATION_NAME = SdkConstant::class.qualifiedName
    }
}
