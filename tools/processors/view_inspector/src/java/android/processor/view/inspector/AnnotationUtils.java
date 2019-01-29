/*
 * Copyright 2019 The Android Open Source Project
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
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
     * Determine if an annotation with the supplied qualified name is present on the element.
     *
     * @param element The element to check for the presence of an annotation
     * @param annotationQualifiedName The name of the annotation to check for
     * @return True if the annotation is present, false otherwise
     */
    boolean hasAnnotation(Element element, String annotationQualifiedName) {
        final TypeElement namedElement = mElementUtils.getTypeElement(annotationQualifiedName);

        if (namedElement != null) {
            final TypeMirror annotationType = namedElement.asType();

            for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
                if (mTypeUtils.isSubtype(annotation.getAnnotationType(), annotationType)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Get a typed list of values for an annotation array property by name.
     *
     * The returned list will be empty if the value was left at the default.
     *
     * @param propertyName The name of the property to search for
     * @param valueClass The expected class of the property value
     * @param element The element the annotation is on, used for exceptions
     * @param annotationMirror An annotation mirror to search for the property
     * @param <T> The type of the value
     * @return A list containing the requested types
     */
    <T> List<T> typedArrayValuesByName(
            String propertyName,
            Class<T> valueClass,
            Element element,
            AnnotationMirror annotationMirror) {
        return untypedArrayValuesByName(propertyName, element, annotationMirror)
                .stream()
                .map(annotationValue -> {
                    final Object value = annotationValue.getValue();

                    if (value == null) {
                        throw new ProcessingException(
                                "Unexpected null in array.",
                                element,
                                annotationMirror,
                                annotationValue);
                    }

                    if (valueClass.isAssignableFrom(value.getClass())) {
                        return valueClass.cast(value);
                    } else {
                        throw new ProcessingException(
                                String.format(
                                        "Expected array entry to have type %s, but got %s.",
                                        valueClass.getCanonicalName(),
                                        value.getClass().getCanonicalName()),
                                element,
                                annotationMirror,
                                annotationValue);
                    }
                })
                .collect(Collectors.toList());
    }

    /**
     * Get a list of values for an annotation array property by name.
     *
     * @param propertyName The name of the property to search for
     * @param element The element the annotation is on, used for exceptions
     * @param annotationMirror An annotation mirror to search for the property
     * @return A list of annotation values, empty list if none found
     */
    List<AnnotationValue> untypedArrayValuesByName(
            String propertyName,
            Element element,
            AnnotationMirror annotationMirror) {
        return typedValueByName(propertyName, List.class, element, annotationMirror)
                .map(untypedValues -> {
                    List<AnnotationValue> typedValues = new ArrayList<>(untypedValues.size());

                    for (Object untypedValue : untypedValues) {
                        if (untypedValue instanceof AnnotationValue) {
                            typedValues.add((AnnotationValue) untypedValue);
                        } else {
                            throw new ProcessingException(
                                    "Unable to convert array entry to AnnotationValue",
                                    element,
                                    annotationMirror);
                        }
                    }

                    return typedValues;
                }).orElseGet(Collections::emptyList);
    }

    /**
     * Get the typed value of an annotation property by name.
     *
     * The returned optional will be empty if the value was left at the default, or if the value
     * of the property is null.
     *
     * @param propertyName The name of the property to search for
     * @param valueClass The expected class of the property value
     * @param element The element the annotation is on, used for exceptions
     * @param annotationMirror An annotation mirror to search for the property
     * @param <T> The type of the value
     * @return An optional containing the typed value of the named property
     */
    <T> Optional<T> typedValueByName(
            String propertyName,
            Class<T> valueClass,
            Element element,
            AnnotationMirror annotationMirror) {
        return valueByName(propertyName, annotationMirror).map(annotationValue -> {
            final Object value = annotationValue.getValue();

            if (value == null) {
                throw new ProcessingException(
                        String.format(
                                "Unexpected null value for annotation property \"%s\".",
                                propertyName),
                        element,
                        annotationMirror,
                        annotationValue);
            }

            if (valueClass.isAssignableFrom(value.getClass())) {
                return valueClass.cast(value);
            } else {
                throw new ProcessingException(
                        String.format(
                                "Expected annotation property \"%s\" to have type %s, but got %s.",
                                propertyName,
                                valueClass.getCanonicalName(),
                                value.getClass().getCanonicalName()),
                        element,
                        annotationMirror,
                        annotationValue);
            }
        });
    }

    /**
     * Get the untyped value of an annotation property by name.
     *
     * The returned optional will be empty if the value was left at the default, or if the value
     * of the property is null.
     *
     * @param propertyName The name of the property to search for
     * @param element The element the annotation is on, used for exceptions
     * @param annotationMirror An annotation mirror to search for the property
     * @return An optional containing the untyped value of the named property
     * @see AnnotationValue#getValue()
     */
    Optional<Object> untypedValueByName(
            String propertyName,
            Element element,
            AnnotationMirror annotationMirror) {
        return valueByName(propertyName, annotationMirror).map(annotationValue -> {
            final Object value = annotationValue.getValue();

            if (value == null) {
                throw new ProcessingException(
                        String.format(
                                "Unexpected null value for annotation property \"%s\".",
                                propertyName),
                        element,
                        annotationMirror,
                        annotationValue);
            }

            return value;
        });
    }

    /**
     * Extract a {@link AnnotationValue} from a mirror by string property name.
     *
     * @param propertyName The name of the property requested property
     * @param annotationMirror The mirror to search for the property
     * @return The value of the property
     */
    Optional<AnnotationValue> valueByName(String propertyName, AnnotationMirror annotationMirror) {
        final Map<? extends ExecutableElement, ? extends AnnotationValue> valueMap =
                annotationMirror.getElementValues();

        for (ExecutableElement method : valueMap.keySet()) {
            if (method.getSimpleName().contentEquals(propertyName)) {
                return Optional.ofNullable(valueMap.get(method));
            }
        }

        // Property not explicitly defined, use default value.
        return Optional.empty();
    }
}
