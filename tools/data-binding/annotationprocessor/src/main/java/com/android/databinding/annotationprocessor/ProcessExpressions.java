/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.databinding.annotationprocessor;

import com.android.databinding.CompilerChef;
import com.android.databinding.reflection.SdkUtil;
import com.android.databinding.store.ResourceBundle;
import com.android.databinding.util.L;
import com.android.databinding.writer.AnnotationJavaFileWriter;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;

import android.binding.BinderBundle;
import android.binding.BindingAppInfo;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;

@SupportedAnnotationTypes({"android.binding.BinderBundle", "android.binding.BindingAppInfo"})
@SupportedSourceVersion(SourceVersion.RELEASE_7)
public class ProcessExpressions extends AbstractProcessor {

    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        ResourceBundle resourceBundle = null;
        for (Element element : roundEnv.getElementsAnnotatedWith(BindingAppInfo.class)) {
            final BindingAppInfo appInfo = element.getAnnotation(BindingAppInfo.class);
            if (appInfo == null) {
                continue; // It gets confused between BindingAppInfo and BinderBundle
            }
            SdkUtil.initialize(appInfo.minSdk(), new File(appInfo.sdkRoot()));
            if (element.getKind() != ElementKind.CLASS) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "BindingAppInfo associated with wrong type. Should be a class.", element);
                continue;
            }
            if (resourceBundle == null) {
                resourceBundle = new ResourceBundle(appInfo.applicationPackage());
                processLayouts(resourceBundle, roundEnv);
            } else {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "BindingAppInfo must be applied to only one class.", element);
            }
        }

        return true;
    }

    private void processLayouts(ResourceBundle resourceBundle, RoundEnvironment roundEnv) {
        Unmarshaller unmarshaller = null;
        for (Element element : roundEnv.getElementsAnnotatedWith(BinderBundle.class)) {
            final BinderBundle binderBundle = element.getAnnotation(BinderBundle.class);
            if (binderBundle == null) {
                continue;
            }
            if (element.getKind() != ElementKind.CLASS) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "BinderBundle associated with wrong type. Should be a class.", element);
                continue;
            }
            ByteArrayInputStream in = null;
            try {
                if (unmarshaller == null) {
                    JAXBContext context =
                            JAXBContext.newInstance(ResourceBundle.LayoutFileBundle.class);
                    unmarshaller = context.createUnmarshaller();
                }
                String binderBundle64 = binderBundle.value();
                byte[] buf = Base64.decodeBase64(binderBundle64);
                in = new ByteArrayInputStream(buf);
                Reader reader = new InputStreamReader(in);
                ResourceBundle.LayoutFileBundle layoutFileBundle
                        = (ResourceBundle.LayoutFileBundle)
                        unmarshaller.unmarshal(reader);
                resourceBundle
                        .addLayoutBundle(layoutFileBundle, layoutFileBundle.getLayoutId());
            } catch (Exception e) {
                StringWriter stringWriter = new StringWriter();
                e.printStackTrace(new PrintWriter(stringWriter));
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Could not generate Binders from binder data store: " +
                                stringWriter.getBuffer().toString(), element);
            } finally {
                if (in != null) {
                    IOUtils.closeQuietly(in);
                }
            }

        }

        CompilerChef compilerChef = CompilerChef.createChef(resourceBundle,
                new AnnotationJavaFileWriter(processingEnv));
        if (compilerChef.hasAnythingToGenerate()) {
            compilerChef.writeDbrFile();
            compilerChef.writeViewBinderInterfaces();
            compilerChef.writeViewBinders();
        }
    }
}
