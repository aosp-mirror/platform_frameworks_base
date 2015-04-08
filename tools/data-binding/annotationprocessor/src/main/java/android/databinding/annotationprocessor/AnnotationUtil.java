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
package android.databinding.annotationprocessor;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;

class AnnotationUtil {

    /**
     * Returns only the elements that are annotated with the given class. For some reason
     * RoundEnvironment is returning elements annotated by other annotations.
     */
    static List<Element> getElementsAnnotatedWith(RoundEnvironment roundEnv,
            Class<? extends Annotation> annotationClass) {
        ArrayList<Element> elements = new ArrayList<>();
        for (Element element : roundEnv.getElementsAnnotatedWith(annotationClass)) {
            if (element.getAnnotation(annotationClass) != null) {
                elements.add(element);
            }
        }
        return elements;
    }
}
