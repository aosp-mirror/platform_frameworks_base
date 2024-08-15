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

import com.google.common.base.CaseFormat
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.JavaFile
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.TypeSpec
import javax.lang.model.element.Modifier

/*
 * Simple Java code generator that takes as input a list of defined features and generates an
 * accessory class based on the provided versions.
 *
 * <p>Example:
 *
 * <pre>
 *   <cmd> com.foo.RoSystemFeatures --readonly=true \
 *           --feature=WATCH:0 --feature=AUTOMOTIVE: --feature=VULKAN:9348
 * </pre>
 *
 * This generates a class that has the following signature:
 *
 * <pre>
 * package com.foo;
 * public final class RoSystemFeatures {
 *     @AssumeTrueForR8
 *     public static boolean hasFeatureWatch(Context context);
 *     @AssumeFalseForR8
 *     public static boolean hasFeatureAutomotive(Context context);
 *     @AssumeTrueForR8
 *     public static boolean hasFeatureVulkan(Context context);
 *     public static Boolean maybeHasFeature(String feature, int version);
 * }
 * </pre>
 */
object SystemFeaturesGenerator {
    private const val FEATURE_ARG = "--feature="
    private const val READONLY_ARG = "--readonly="
    private val PACKAGEMANAGER_CLASS = ClassName.get("android.content.pm", "PackageManager")
    private val CONTEXT_CLASS = ClassName.get("android.content", "Context")
    private val ASSUME_TRUE_CLASS =
        ClassName.get("com.android.aconfig.annotations", "AssumeTrueForR8")
    private val ASSUME_FALSE_CLASS =
        ClassName.get("com.android.aconfig.annotations", "AssumeFalseForR8")

    private fun usage() {
        println("Usage: SystemFeaturesGenerator <outputClassName> [options]")
        println(" Options:")
        println("  --readonly=true|false    Whether to encode features as build-time constants")
        println("  --feature=\$NAME:\$VER   A feature+version pair (blank version == disabled)")
    }

    /** Main entrypoint for build-time system feature codegen. */
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 1) {
            usage()
            return
        }

        var readonly = false
        var outputClassName: ClassName? = null
        val features = mutableListOf<FeatureInfo>()
        for (arg in args) {
            when {
                arg.startsWith(READONLY_ARG) ->
                    readonly = arg.substring(READONLY_ARG.length).toBoolean()
                arg.startsWith(FEATURE_ARG) -> {
                    features.add(parseFeatureArg(arg))
                }
                else -> outputClassName = ClassName.bestGuess(arg)
            }
        }

        outputClassName
            ?: run {
                println("Output class name must be provided.")
                usage()
                return
            }

        val classBuilder =
            TypeSpec.classBuilder(outputClassName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addJavadoc("@hide")

        addFeatureMethodsToClass(classBuilder, readonly, features)
        addMaybeFeatureMethodToClass(classBuilder, readonly, features)

        // TODO(b/203143243): Add validation of build vs runtime values to ensure consistency.
        JavaFile.builder(outputClassName.packageName(), classBuilder.build())
            .build()
            .writeTo(System.out)
    }

    /*
     * Parses a feature argument of the form "--feature=$NAME:$VER", where "$VER" is optional.
     *   * "--feature=WATCH:0" -> Feature enabled w/ version 0 (default version when enabled)
     *   * "--feature=WATCH:7" -> Feature enabled w/ version 7
     *   * "--feature=WATCH:"  -> Feature disabled
     */
    private fun parseFeatureArg(arg: String): FeatureInfo {
        val featureArgs = arg.substring(FEATURE_ARG.length).split(":")
        val name = featureArgs[0].let { if (!it.startsWith("FEATURE_")) "FEATURE_$it" else it }
        val version = featureArgs.getOrNull(1)?.toIntOrNull()
        return FeatureInfo(name, version)
    }

    /*
     * Adds per-feature query methods to the class with the form:
     * {@code public static boolean hasFeatureX(Context context)},
     * returning the fallback value from PackageManager if not readonly.
     */
    private fun addFeatureMethodsToClass(
        builder: TypeSpec.Builder,
        readonly: Boolean,
        features: List<FeatureInfo>
    ) {
        for (feature in features) {
            // Turn "FEATURE_FOO" into "hasFeatureFoo".
            val methodName =
                "has" + CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, feature.name)
            val methodBuilder =
                MethodSpec.methodBuilder(methodName)
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .returns(Boolean::class.java)
                    .addParameter(CONTEXT_CLASS, "context")

            if (readonly) {
                val featureEnabled = compareValues(feature.version, 0) >= 0
                methodBuilder.addAnnotation(
                    if (featureEnabled) ASSUME_TRUE_CLASS else ASSUME_FALSE_CLASS
                )
                methodBuilder.addStatement("return $featureEnabled")
            } else {
                methodBuilder.addStatement(
                    "return hasFeatureFallback(context, \$T.\$N)",
                    PACKAGEMANAGER_CLASS,
                    feature.name
                )
            }
            builder.addMethod(methodBuilder.build())
        }

        if (!readonly) {
            builder.addMethod(
                MethodSpec.methodBuilder("hasFeatureFallback")
                    .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                    .returns(Boolean::class.java)
                    .addParameter(CONTEXT_CLASS, "context")
                    .addParameter(String::class.java, "featureName")
                    .addStatement(
                        "return context.getPackageManager().hasSystemFeature(featureName, 0)"
                    )
                    .build()
            )
        }
    }

    /*
     * Adds a generic query method to the class with the form: {@code public static boolean
     * maybeHasFeature(String featureName, int version)}, returning null if the feature version is
     * undefined or not readonly.
     *
     * This method is useful for internal usage within the framework, e.g., from the implementation
     * of {@link android.content.pm.PackageManager#hasSystemFeature(Context)}, when we may only
     * want a valid result if it's defined as readonly, and we want a custom fallback otherwise
     * (e.g., to the existing runtime binder query).
     */
    private fun addMaybeFeatureMethodToClass(
        builder: TypeSpec.Builder,
        readonly: Boolean,
        features: List<FeatureInfo>
    ) {
        val methodBuilder =
            MethodSpec.methodBuilder("maybeHasFeature")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addAnnotation(ClassName.get("android.annotation", "Nullable"))
                .returns(Boolean::class.javaObjectType) // Use object type for nullability
                .addParameter(String::class.java, "featureName")
                .addParameter(Int::class.java, "version")

        if (readonly) {
            methodBuilder.beginControlFlow("switch (featureName)")
            for (feature in features) {
                methodBuilder.addCode("case \$T.\$N: ", PACKAGEMANAGER_CLASS, feature.name)
                if (feature.version != null) {
                    methodBuilder.addStatement("return \$L >= version", feature.version)
                } else {
                    methodBuilder.addStatement("return false")
                }
            }
            methodBuilder.addCode("default: ")
            methodBuilder.addStatement("break")
            methodBuilder.endControlFlow()
        }
        methodBuilder.addStatement("return null")
        builder.addMethod(methodBuilder.build())
    }

    private data class FeatureInfo(val name: String, val version: Int?)
}
