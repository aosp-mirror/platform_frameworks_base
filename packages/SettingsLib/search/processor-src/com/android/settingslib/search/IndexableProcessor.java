/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.settingslib.search;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeSpec;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.SimpleElementVisitor8;
import javax.tools.Diagnostic.Kind;

/**
 * Annotation processor for {@link SearchIndexable} that generates {@link SearchIndexableResources}
 * subclasses.
 */
@SupportedSourceVersion(SourceVersion.RELEASE_17)
@SupportedOptions(IndexableProcessor.PACKAGE_KEY)
@SupportedAnnotationTypes({"com.android.settingslib.search.SearchIndexable"})
public class IndexableProcessor extends AbstractProcessor {

    private static final String SETTINGSLIB_SEARCH_PACKAGE = "com.android.settingslib.search";
    private static final String CLASS_BASE = "SearchIndexableResourcesBase";
    private static final String CLASS_MOBILE = "SearchIndexableResourcesMobile";
    private static final String CLASS_TV = "SearchIndexableResourcesTv";
    private static final String CLASS_WEAR = "SearchIndexableResourcesWear";
    private static final String CLASS_AUTO = "SearchIndexableResourcesAuto";
    private static final String CLASS_ARC = "SearchIndexableResourcesArc";

    static final String PACKAGE_KEY = "com.android.settingslib.search.processor.package";

    private String mPackage;
    private Filer mFiler;
    private Messager mMessager;
    private boolean mRanOnce;

    @Override
    public boolean process(Set<? extends TypeElement> annotations,
            RoundEnvironment roundEnvironment) {
        if (mRanOnce) {
            // Will get called once per round, but we only want to run on the first one.
            return true;
        }
        mRanOnce = true;

        final ClassName searchIndexableData =
                ClassName.get(SETTINGSLIB_SEARCH_PACKAGE, "SearchIndexableData");

        final FieldSpec providers = FieldSpec.builder(
                ParameterizedTypeName.get(
                        ClassName.get(Set.class),
                        searchIndexableData),
                "mProviders",
                Modifier.PRIVATE, Modifier.FINAL)
                .initializer("new $T()", HashSet.class)
                .build();

        final MethodSpec addIndex = MethodSpec.methodBuilder("addIndex")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(searchIndexableData, "indexClass")
                .addCode("$N.add(indexClass);\n", providers)
                .build();

        final MethodSpec.Builder baseConstructorBuilder = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC);
        final MethodSpec.Builder mobileConstructorBuilder = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC);
        final MethodSpec.Builder tvConstructorBuilder = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC);
        final MethodSpec.Builder wearConstructorBuilder = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC);
        final MethodSpec.Builder autoConstructorBuilder = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC);
        final MethodSpec.Builder arcConstructorBuilder = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC);

        for (Element element : roundEnvironment.getElementsAnnotatedWith(SearchIndexable.class)) {
            if (element.getKind().isClass()) {
                Name className = element.accept(new SimpleElementVisitor8<Name, Void>() {
                    @Override
                    public Name visitType(TypeElement typeElement, Void aVoid) {
                        return typeElement.getQualifiedName();
                    }
                }, null);
                if (className != null) {
                    SearchIndexable searchIndexable = element.getAnnotation(SearchIndexable.class);

                    int forTarget = searchIndexable.forTarget();
                    MethodSpec.Builder builder = baseConstructorBuilder;

                    if (forTarget == SearchIndexable.ALL) {
                        builder = baseConstructorBuilder;
                    } else if ((forTarget & SearchIndexable.MOBILE) != 0) {
                        builder = mobileConstructorBuilder;
                    } else if ((forTarget & SearchIndexable.TV) != 0) {
                        builder = tvConstructorBuilder;
                    } else if ((forTarget & SearchIndexable.WEAR) != 0) {
                        builder = wearConstructorBuilder;
                    } else if ((forTarget & SearchIndexable.AUTO) != 0) {
                        builder = autoConstructorBuilder;
                    } else if ((forTarget & SearchIndexable.ARC) != 0) {
                        builder = arcConstructorBuilder;
                    }
                    builder.addCode(
                            "$N(new com.android.settingslib.search.SearchIndexableData($L.class, $L"
                                    + ".SEARCH_INDEX_DATA_PROVIDER));\n",
                            addIndex, className, className);
                } else {
                    throw new IllegalStateException("Null classname from " + element);
                }
            }
        }

        final MethodSpec getProviderValues = MethodSpec.methodBuilder("getProviderValues")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(
                        ClassName.get(Collection.class),
                        searchIndexableData))
                .addCode("return $N;\n", providers)
                .build();

        final TypeSpec baseClass = TypeSpec.classBuilder(CLASS_BASE)
                .addModifiers(Modifier.PUBLIC)
                .addSuperinterface(
                        ClassName.get(SETTINGSLIB_SEARCH_PACKAGE, "SearchIndexableResources"))
                .addField(providers)
                .addMethod(baseConstructorBuilder.build())
                .addMethod(addIndex)
                .addMethod(getProviderValues)
                .build();
        final JavaFile searchIndexableResourcesBase = JavaFile.builder(mPackage, baseClass).build();

        final JavaFile searchIndexableResourcesMobile = JavaFile.builder(mPackage,
                TypeSpec.classBuilder(CLASS_MOBILE)
                        .addModifiers(Modifier.PUBLIC)
                        .superclass(ClassName.get(mPackage, baseClass.name))
                        .addMethod(mobileConstructorBuilder.build())
                        .build())
                .build();

        final JavaFile searchIndexableResourcesTv = JavaFile.builder(mPackage,
                TypeSpec.classBuilder(CLASS_TV)
                        .addModifiers(Modifier.PUBLIC)
                        .superclass(ClassName.get(mPackage, baseClass.name))
                        .addMethod(tvConstructorBuilder.build())
                        .build())
                .build();

        final JavaFile searchIndexableResourcesWear = JavaFile.builder(mPackage,
                TypeSpec.classBuilder(CLASS_WEAR)
                        .addModifiers(Modifier.PUBLIC)
                        .superclass(ClassName.get(mPackage, baseClass.name))
                        .addMethod(wearConstructorBuilder.build())
                        .build())
                .build();

        final JavaFile searchIndexableResourcesAuto = JavaFile.builder(mPackage,
                TypeSpec.classBuilder(CLASS_AUTO)
                        .addModifiers(Modifier.PUBLIC)
                        .superclass(ClassName.get(mPackage, baseClass.name))
                        .addMethod(autoConstructorBuilder.build())
                        .build())
                .build();

        final JavaFile searchIndexableResourcesArc = JavaFile.builder(mPackage,
                TypeSpec.classBuilder(CLASS_ARC)
                        .addModifiers(Modifier.PUBLIC)
                        .superclass(ClassName.get(mPackage, baseClass.name))
                        .addMethod(arcConstructorBuilder.build())
                        .build())
                .build();

        try {
            searchIndexableResourcesBase.writeTo(mFiler);
            searchIndexableResourcesMobile.writeTo(mFiler);
            searchIndexableResourcesTv.writeTo(mFiler);
            searchIndexableResourcesWear.writeTo(mFiler);
            searchIndexableResourcesAuto.writeTo(mFiler);
            searchIndexableResourcesArc.writeTo(mFiler);
        } catch (IOException e) {
            mMessager.printMessage(Kind.ERROR, "Error while writing file: " + e);
        }
        return true;
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnvironment) {
        super.init(processingEnvironment);
        mPackage = processingEnvironment.getOptions()
                .getOrDefault(PACKAGE_KEY, SETTINGSLIB_SEARCH_PACKAGE);
        mFiler = processingEnvironment.getFiler();
        mMessager = processingEnvironment.getMessager();
    }
}
