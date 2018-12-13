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

import android.processor.view.inspector.InspectableClassModel.Property;

import java.util.Set;
import java.util.regex.Pattern;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.NoType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * Process {@code @InspectableProperty} annotations.
 *
 * @see android.view.inspector.InspectableProperty
 */
public final class InspectablePropertyProcessor implements ModelProcessor {
    private final String mQualifiedName;
    private final ProcessingEnvironment mProcessingEnv;
    private final AnnotationUtils mAnnotationUtils;

    /**
     * Regex that matches methods names of the form {@code #getValue()}.
     */
    private static final Pattern GETTER_GET_PREFIX = Pattern.compile("\\Aget[A-Z]");

    /**
     * Regex that matches method name of the form {@code #isPredicate()}.
     */
    private static final Pattern GETTER_IS_PREFIX = Pattern.compile("\\Ais[A-Z]");

    /**
     * Set of android and androidx annotation qualified names for colors packed into {@code int}.
     *
     * @see android.annotation.ColorInt
     */
    private static final String[] COLOR_INT_ANNOTATION_NAMES = {
            "android.annotation.ColorInt",
            "androidx.annotation.ColorInt"};

    /**
     * Set of android and androidx annotation qualified names for colors packed into {@code long}.
     * @see android.annotation.ColorLong
     */
    private static final String[] COLOR_LONG_ANNOTATION_NAMES = {
            "android.annotation.ColorLong",
            "androidx.annotation.ColorLong"};

    /**
     * @param annotationQualifiedName The qualified name of the annotation to process
     * @param processingEnv The processing environment from the parent processor
     */
    public InspectablePropertyProcessor(
            String annotationQualifiedName,
            ProcessingEnvironment processingEnv) {
        mQualifiedName = annotationQualifiedName;
        mProcessingEnv = processingEnv;
        mAnnotationUtils = new AnnotationUtils(processingEnv);
    }

    @Override
    public void process(Element element, InspectableClassModel model) {
        try {
            final AnnotationMirror annotation =
                    mAnnotationUtils.exactlyOneMirror(mQualifiedName, element);
            final ExecutableElement getter = ensureGetter(element);
            final Property property = buildProperty(getter, annotation);

            model.getProperty(property.getName()).ifPresent(p -> {
                throw new ProcessingException(
                        String.format(
                                "Property \"%s\" is already defined on #%s().",
                                p.getName(),
                                p.getGetter()),
                        getter,
                        annotation);
            });

            model.putProperty(property);
        } catch (ProcessingException processingException) {
            processingException.print(mProcessingEnv.getMessager());
        }
    }

    /**
     * Check that an element is shaped like a getter.
     *
     * @param element An element that hopefully represents a getter
     * @throws ProcessingException if the element isn't a getter
     * @return An {@link ExecutableElement} that represents a getter method.
     */
    private ExecutableElement ensureGetter(Element element) {
        if (element.getKind() != ElementKind.METHOD) {
            throw new ProcessingException(
                    String.format("Expected a method, got a %s", element.getKind()),
                    element);
        }

        final ExecutableElement method = (ExecutableElement) element;
        final Set<Modifier> modifiers = method.getModifiers();

        if (modifiers.contains(Modifier.PRIVATE)) {
            throw new ProcessingException(
                    "Property getter methods must not be private.",
                    element);
        }

        if (modifiers.contains(Modifier.ABSTRACT)) {
            throw new ProcessingException(
                    "Property getter methods must not be abstract.",
                    element);
        }

        if (modifiers.contains(Modifier.STATIC)) {
            throw new ProcessingException(
                    "Property getter methods must not be static.",
                    element);
        }

        if (!method.getParameters().isEmpty()) {
            throw new ProcessingException(
                    String.format(
                            "Expected a getter method to take no parameters, "
                            + "but got %d parameters.",
                            method.getParameters().size()),
                    element);
        }

        if (method.isVarArgs()) {
            throw new ProcessingException(
                    "Expected a getter method to take no arguments, but got a var args method.",
                    element);
        }

        if (method.getReturnType() instanceof NoType) {
            throw new ProcessingException(
                    "Expected a getter to have a return type, got void.",
                    element);
        }

        return method;
    }

    /**
     * Build a {@link Property} from a getter and an inspectable property annotation.
     *
     * @param getter An element representing the getter to build from
     * @param annotation A mirror of an inspectable property-shaped annotation
     * @throws ProcessingException If the supplied data is invalid and a property cannot be modeled
     * @return A property for the getter and annotation
     */
    private Property buildProperty(ExecutableElement getter, AnnotationMirror annotation) {
        final String name = mAnnotationUtils
                .typedValueByName("name", String.class, getter, annotation)
                .orElseGet(() -> inferPropertyNameFromGetter(getter));

        final Property property = new Property(
                name,
                getter.getSimpleName().toString(),
                determinePropertyType(getter, annotation));

        mAnnotationUtils
                .typedValueByName("hasAttributeId", Boolean.class, getter, annotation)
                .ifPresent(property::setAttributeIdInferrableFromR);

        mAnnotationUtils
                .typedValueByName("attributeId", Integer.class, getter, annotation)
                .ifPresent(property::setAttributeId);

        return property;
    }

    /**
     * Determine the property type from the annotation, return type, or context clues.
     *
     * @param getter An element representing the getter to build from
     * @param annotation A mirror of an inspectable property-shaped annotation
     * @return The resolved property type
     * @throws ProcessingException If the property type cannot be resolved
     * @see android.view.inspector.InspectableProperty#valueType()
     */
    private Property.Type determinePropertyType(
            ExecutableElement getter,
            AnnotationMirror annotation) {

        final String valueType = mAnnotationUtils
                .untypedValueByName("valueType", getter, annotation)
                .map(Object::toString)
                .orElse("INFERRED");

        final Property.Type returnType = convertReturnTypeToPropertyType(getter);

        switch (valueType) {
            case "INFERRED":
                if (hasColorAnnotation(getter)) {
                    return Property.Type.COLOR;
                } else {
                    return returnType;
                }
            case "NONE":
                return returnType;
            case "COLOR":
                switch (returnType) {
                    case COLOR:
                    case INT:
                    case LONG:
                        return Property.Type.COLOR;
                    default:
                        throw new ProcessingException(
                                "Color must be a long, integer, or android.graphics.Color",
                                getter,
                                annotation);
                }
            case "GRAVITY":
                if (returnType == Property.Type.INT) {
                    return Property.Type.GRAVITY;
                } else {
                    throw new ProcessingException(
                            String.format("Gravity must be an integer, got %s", returnType),
                            getter,
                            annotation);
                }
            case "INT_ENUM":
            case "INT_FLAG":
                throw new ProcessingException("Not implemented", getter, annotation);
            default:
                throw new ProcessingException(
                        String.format("Unknown value type enumeration value: %s", valueType),
                        getter,
                        annotation);
        }
    }

    /**
     * Get a property type from the return type of a getter.
     *
     * @param getter The getter to extract the return type of
     * @throws ProcessingException If the return type is not a primitive or an object
     * @return The property type returned by the getter
     */
    private Property.Type convertReturnTypeToPropertyType(ExecutableElement getter) {
        final TypeMirror returnType = getter.getReturnType();

        switch (unboxType(returnType)) {
            case BOOLEAN:
                return Property.Type.BOOLEAN;
            case BYTE:
                return Property.Type.BYTE;
            case CHAR:
                return Property.Type.CHAR;
            case DOUBLE:
                return Property.Type.DOUBLE;
            case FLOAT:
                return Property.Type.FLOAT;
            case INT:
                return Property.Type.INT;
            case LONG:
                return Property.Type.LONG;
            case SHORT:
                return Property.Type.SHORT;
            case DECLARED:
                if (isColorType(returnType)) {
                    return Property.Type.COLOR;
                } else {
                    return Property.Type.OBJECT;
                }
            default:
                throw new ProcessingException(
                        String.format("Unsupported return type %s.", returnType),
                        getter);
        }
    }

    /**
     * Determine if a getter is annotated with color annotation matching its return type.
     *
     * Note that an {@code int} return value annotated with {@link android.annotation.ColorLong} is
     * not considered to be annotated, nor is a {@code long} annotated with
     * {@link android.annotation.ColorInt}.
     *
     * @param getter The getter to query
     * @return True if the getter has a color annotation, false otherwise
     *
     */
    private boolean hasColorAnnotation(ExecutableElement getter) {
        switch (unboxType(getter.getReturnType())) {
            case INT:
                for (String name : COLOR_INT_ANNOTATION_NAMES) {
                    if (mAnnotationUtils.hasAnnotation(getter, name)) {
                        return true;
                    }
                }
                return false;
            case LONG:
                for (String name : COLOR_LONG_ANNOTATION_NAMES) {
                    if (mAnnotationUtils.hasAnnotation(getter, name)) {
                        return true;
                    }
                }
                return false;
            default:
                return false;
        }
    }

    /**
     * Infer a property name from a getter method.
     *
     * If the method is prefixed with {@code get}, the prefix will be stripped, and the
     * capitalization fixed. E.g.: {@code getSomeProperty} to {@code someProperty}.
     *
     * Additionally, if the method's return type is a boolean, an {@code is} prefix will also be
     * stripped. E.g.: {@code isPropertyEnabled} to {@code propertyEnabled}.
     *
     * Failing that, this method will just return the full name of the getter.
     *
     * @param getter An element representing a getter
     * @return A string property name
     */
    private String inferPropertyNameFromGetter(ExecutableElement getter) {
        final String name = getter.getSimpleName().toString();

        if (GETTER_GET_PREFIX.matcher(name).find()) {
            return name.substring(3, 4).toLowerCase() + name.substring(4);
        } else if (isBoolean(getter.getReturnType()) && GETTER_IS_PREFIX.matcher(name).find()) {
            return name.substring(2, 3).toLowerCase() + name.substring(3);
        } else {
            return name;
        }
    }

    /**
     * Determine if a {@link TypeMirror} is a boxed or unboxed boolean.
     *
     * @param type The type mirror to check
     * @return True if the type is a boolean
     */
    private boolean isBoolean(TypeMirror type) {
        if (type.getKind() == TypeKind.DECLARED) {
            return mProcessingEnv.getTypeUtils().unboxedType(type).getKind() == TypeKind.BOOLEAN;
        } else {
            return type.getKind() == TypeKind.BOOLEAN;
        }
    }

    /**
     * Unbox a type mirror if it represents a boxed type, otherwise pass it through.
     *
     * @param typeMirror The type mirror to unbox
     * @return The same type mirror, or an unboxed primitive version
     */
    private TypeKind unboxType(TypeMirror typeMirror) {
        final TypeKind typeKind = typeMirror.getKind();

        if (typeKind.isPrimitive()) {
            return typeKind;
        } else if (typeKind == TypeKind.DECLARED) {
            try {
                return mProcessingEnv.getTypeUtils().unboxedType(typeMirror).getKind();
            } catch (IllegalArgumentException e) {
                return typeKind;
            }
        } else {
            return typeKind;
        }
    }

    /**
     * Determine if a type mirror represents a subtype of {@link android.graphics.Color}.
     *
     * @param typeMirror The type mirror to test
     * @return True if it represents a subclass of color, false otherwise
     */
    private boolean isColorType(TypeMirror typeMirror) {
        final TypeElement colorType = mProcessingEnv
                .getElementUtils()
                .getTypeElement("android.graphics.Color");

        if (colorType == null) {
            return false;
        } else {
            return mProcessingEnv.getTypeUtils().isSubtype(typeMirror, colorType.asType());
        }
    }
}
