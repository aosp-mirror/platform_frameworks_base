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

package android.processor.view.inspector;

import java.util.Map;
import java.util.Optional;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Utilities for working with {@link AnnotationMirror}.
 */
final class AnnotationUtils {
    private final Elements mElementUtils;
    private final Types mTypeUtils;

    AnnotationUtils(ProcessingEnvironment processingEnv) {
        mElementUtils = processingEnv.getElementUtils();
        mTypeUtils = processingEnv.getTypeUtils();
    }

    /**
     * Get a {@link AnnotationMirror} specified by name from an {@link Element}.
     *
     * @param qualifiedName The fully qualified name of the annotation to search for
     * @param element The element to search for annotations on
     * @return The mirror of the requested annotation
     * @throws ProcessingException If there is not exactly one of the requested annotation.
     */
    AnnotationMirror exactlyOneMirror(String qualifiedName, Element element) {
        final Element targetTypeElment = mElementUtils.getTypeElement(qualifiedName);
        final TypeMirror targetType = targetTypeElment.asType();
        AnnotationMirror result = null;

        for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
            final TypeMirror annotationType = annotation.getAnnotationType().asElement().asType();
            if (mTypeUtils.isSameType(annotationType, targetType)) {
                if (result == null) {
                    result = annotation;
                } else {
                    final String message = String.format(
                            "Element had multiple instances of @%s, expected exactly one",
                            targetTypeElment.getSimpleName());

                    throw new ProcessingException(message, element, annotation);
                }
            }
        }

        if (result == null) {
            final String message = String.format(
                    "Expected an @%s annotation, found none", targetTypeElment.getSimpleName());
            throw new ProcessingException(message, element);
        } else {
            return result;
        }
    }

    /**
     * Extract a string-valued property from an {@link AnnotationMirror}.
     *
     * @param propertyName The name of the requested property
     * @param annotationMirror The mirror to search for the property
     * @return The String value of the annotation or null
     */
    Optional<String> stringProperty(String propertyName, AnnotationMirror annotationMirror) {
        final AnnotationValue value = valueByName(propertyName, annotationMirror);
        if (value != null) {
            return Optional.of((String) value.getValue());
        } else {
            return Optional.empty();
        }
    }


    /**
     * Extract a {@link AnnotationValue} from a mirror by string property name.
     *
     * @param propertyName The name of the property requested property
     * @param annotationMirror
     * @return
     */
    AnnotationValue valueByName(String propertyName, AnnotationMirror annotationMirror) {
        final Map<? extends ExecutableElement, ? extends AnnotationValue> valueMap =
                annotationMirror.getElementValues();

        for (ExecutableElement method : valueMap.keySet()) {
            if (method.getSimpleName().contentEquals(propertyName)) {
                return valueMap.get(method);
            }
        }

        return null;
    }

}
