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

package android.processor.property_cache;

import com.android.internal.annotations.CachedProperty;
import com.android.internal.annotations.CachedPropertyDefaults;

import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.io.Writer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import javax.tools.JavaFileObject;

public class CachedPropertyProcessor extends AbstractProcessor {

    IpcDataCacheComposer mIpcDataCacheComposer =
            new IpcDataCacheComposer();

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return new HashSet<String>(
                ImmutableSet.of(CachedPropertyDefaults.class.getCanonicalName()));
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(CachedPropertyDefaults.class)) {
            try {
                generateCachedClass((TypeElement) element, processingEnv.getFiler());
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
        }
        return false;
    }

    private void generateCachedClass(TypeElement classElement, Filer filer) throws IOException {
        String packageName =
                processingEnv
                        .getElementUtils()
                        .getPackageOf(classElement)
                        .getQualifiedName()
                        .toString();
        String className = classElement.getSimpleName().toString() + "Cache";
        JavaFileObject jfo = filer.createSourceFile(packageName + "." + className);
        Writer writer = jfo.openWriter();
        writer.write("package " + packageName + ";\n\n");
        writer.write("import android.os.IpcDataCache;\n");
        writer.write("\n    /** \n    * This class is auto-generated \n    * @hide \n    **/");
        writer.write("\npublic class " + className + " {\n");

        List<ExecutableElement> methods =
                ElementFilter.methodsIn(classElement.getEnclosedElements());
        String initCache = String.format(Constants.METHOD_COMMENT,
                " - initialise all caches for class " + className)
                + "\npublic static void initCache() {";
        for (ExecutableElement method : methods) {
            if (method.getAnnotation(CachedProperty.class) != null) {
                mIpcDataCacheComposer.generatePropertyCache(writer, classElement, method);
                initCache += "\n    " + mIpcDataCacheComposer.generateInvalidatePropertyCall();
            }
        }
        initCache += "\n}";
        writer.write(initCache);
        writer.write("\n}");
        writer.write("\n");
        writer.close();
    }
}
