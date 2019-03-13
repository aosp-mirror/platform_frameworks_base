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

import android.processor.view.inspector.InspectableClassModel.Accessor;
import android.processor.view.inspector.InspectableClassModel.IntEnumEntry;
import android.processor.view.inspector.InspectableClassModel.IntFlagEntry;
import android.processor.view.inspector.InspectableClassModel.Property;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
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
    private final @NonNull String mQualifiedName;
    private final @NonNull ProcessingEnvironment mProcessingEnv;
    private final @NonNull AnnotationUtils mAnnotationUtils;

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
     *
     * @see android.annotation.ColorLong
     */
    private static final String[] COLOR_LONG_ANNOTATION_NAMES = {
            "android.annotation.ColorLong",
            "androidx.annotation.ColorLong"};

    /**
     * Set of android and androidx annotation qualified names of resource ID annotations.
     */
    private static final String[] RESOURCE_ID_ANNOTATION_NAMES = {
            "android.annotation.AnimatorRes",
            "android.annotation.AnimRes",
            "android.annotation.AnyRes",
            "android.annotation.ArrayRes",
            "android.annotation.BoolRes",
            "android.annotation.DimenRes",
            "android.annotation.DrawableRes",
            "android.annotation.FontRes",
            "android.annotation.IdRes",
            "android.annotation.IntegerRes",
            "android.annotation.InterpolatorRes",
            "android.annotation.LayoutRes",
            "android.annotation.MenuRes",
            "android.annotation.NavigationRes",
            "android.annotation.PluralsRes",
            "android.annotation.RawRes",
            "android.annotation.StringRes",
            "android.annotation.StyleableRes",
            "android.annotation.StyleRes",
            "android.annotation.TransitionRes",
            "android.annotation.XmlRes",
            "androidx.annotation.AnimatorRes",
            "androidx.annotation.AnimRes",
            "androidx.annotation.AnyRes",
            "androidx.annotation.ArrayRes",
            "androidx.annotation.BoolRes",
            "androidx.annotation.DimenRes",
            "androidx.annotation.DrawableRes",
            "androidx.annotation.FontRes",
            "androidx.annotation.IdRes",
            "androidx.annotation.IntegerRes",
            "androidx.annotation.InterpolatorRes",
            "androidx.annotation.LayoutRes",
            "androidx.annotation.MenuRes",
            "androidx.annotation.NavigationRes",
            "androidx.annotation.PluralsRes",
            "androidx.annotation.RawRes",
            "androidx.annotation.StringRes",
            "androidx.annotation.StyleableRes",
            "androidx.annotation.StyleRes",
            "androidx.annotation.TransitionRes",
            "androidx.annotation.XmlRes"
    };

    /**
     * @param annotationQualifiedName The qualified name of the annotation to process
     * @param processingEnv           The processing environment from the parent processor
     */
    public InspectablePropertyProcessor(
            @NonNull String annotationQualifiedName,
            @NonNull ProcessingEnvironment processingEnv) {
        mQualifiedName = annotationQualifiedName;
        mProcessingEnv = processingEnv;
        mAnnotationUtils = new AnnotationUtils(processingEnv);
    }

    @Override
    public void process(@NonNull Element element, @NonNull InspectableClassModel model) {
        try {
            final AnnotationMirror annotation =
                    mAnnotationUtils.exactlyOneMirror(mQualifiedName, element);
            final Property property = buildProperty(element, annotation);

            model.getProperty(property.getName()).ifPresent(p -> {
                throw new ProcessingException(
                        String.format(
                                "Property \"%s\" is already defined on #%s.",
                                p.getName(),
                                p.getAccessor().invocation()),
                        element,
                        annotation);
            });

            model.putProperty(property);
        } catch (ProcessingException processingException) {
            processingException.print(mProcessingEnv.getMessager());
        }
    }


    /**
     * Build a {@link Property} from a getter and an inspectable property annotation.
     *
     * @param accessor An element representing the getter or public field to build from
     * @param annotation A mirror of an inspectable property-shaped annotation
     * @return A property for the getter and annotation
     * @throws ProcessingException If the supplied data is invalid and a property cannot be modeled
     */
    @NonNull
    private Property buildProperty(
            @NonNull Element accessor,
            @NonNull AnnotationMirror annotation) {
        final Property property;
        final Optional<String> nameFromAnnotation = mAnnotationUtils
                .typedValueByName("name", String.class, accessor, annotation);

        validateModifiers(accessor);

        switch (accessor.getKind()) {
            case FIELD:
                property = new Property(
                        nameFromAnnotation.orElseGet(() -> accessor.getSimpleName().toString()),
                        Accessor.ofField(accessor.getSimpleName().toString()),
                        determinePropertyType(accessor, annotation));
                break;
            case METHOD:
                final ExecutableElement getter = ensureGetter(accessor);

                property = new Property(
                        nameFromAnnotation.orElseGet(() -> inferPropertyNameFromGetter(getter)),
                        Accessor.ofGetter(getter.getSimpleName().toString()),
                        determinePropertyType(getter, annotation));
                break;
            default:
                throw new ProcessingException(
                        String.format(
                                "Property must either be a getter method or a field, got %s.",
                                accessor.getKind()
                        ),
                        accessor,
                        annotation);
        }

        mAnnotationUtils
                .typedValueByName("hasAttributeId", Boolean.class, accessor, annotation)
                .ifPresent(property::setAttributeIdInferrableFromR);

        mAnnotationUtils
                .typedValueByName("attributeId", Integer.class, accessor, annotation)
                .ifPresent(property::setAttributeId);

        switch (property.getType()) {
            case INT_ENUM:
                property.setIntEnumEntries(processEnumMapping(accessor, annotation));
                break;
            case INT_FLAG:
                property.setIntFlagEntries(processFlagMapping(accessor, annotation));
                break;
        }

        return property;
    }

    /**
     * Validates that an element is public, concrete, and non-static.
     *
     * @param element The element to check
     * @throws ProcessingException If the element's modifiers are invalid
     */
    private void validateModifiers(@NonNull Element element) {
        final Set<Modifier> modifiers = element.getModifiers();

        if (!modifiers.contains(Modifier.PUBLIC)) {
            throw new ProcessingException(
                    "Property getter methods and fields must be public.",
                    element);
        }

        if (modifiers.contains(Modifier.ABSTRACT)) {
            throw new ProcessingException(
                    "Property getter methods must not be abstract.",
                    element);
        }

        if (modifiers.contains(Modifier.STATIC)) {
            throw new ProcessingException(
                    "Property getter methods and fields must not be static.",
                    element);
        }
    }

    /**
     * Check that an element is shaped like a getter.
     *
     * @param element An element that hopefully represents a getter
     * @return An {@link ExecutableElement} that represents a getter method.
     * @throws ProcessingException if the element isn't a getter
     */
    @NonNull
    private ExecutableElement ensureGetter(@NonNull Element element) {
        if (element.getKind() != ElementKind.METHOD) {
            throw new ProcessingException(
                    String.format("Expected a method, got a %s", element.getKind()),
                    element);
        }

        final ExecutableElement method = (ExecutableElement) element;


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
     * Determine the property type from the annotation, return type, or context clues.
     *
     * @param accessor An element representing the getter or field to determine the type of
     * @param annotation A mirror of an inspectable property-shaped annotation
     * @return The resolved property type
     * @throws ProcessingException If the property type cannot be resolved or is invalid
     * @see android.view.inspector.InspectableProperty#valueType()
     */
    @NonNull
    private Property.Type determinePropertyType(
            @NonNull Element accessor,
            @NonNull AnnotationMirror annotation) {
        final String valueType = mAnnotationUtils
                .untypedValueByName("valueType", accessor, annotation)
                .map(Object::toString)
                .orElse("INFERRED");

        final Property.Type accessorType =
                convertTypeMirrorToPropertyType(extractReturnOrFieldType(accessor), accessor);

        final Optional<AnnotationValue> enumMapping =
                mAnnotationUtils.valueByName("enumMapping", annotation);
        final Optional<AnnotationValue> flagMapping =
                mAnnotationUtils.valueByName("flagMapping", annotation);

        if (accessorType != Property.Type.INT) {
            enumMapping.ifPresent(value -> {
                throw new ProcessingException(
                        String.format(
                                "Can only use enumMapping on int types, got %s.",
                                accessorType.toString().toLowerCase()),
                        accessor,
                        annotation,
                        value);
            });
            flagMapping.ifPresent(value -> {
                throw new ProcessingException(
                        String.format(
                                "Can only use flagMapping on int types, got %s.",
                                accessorType.toString().toLowerCase()),
                        accessor,
                        annotation,
                        value);
            });
        }


        switch (valueType) {
            case "INFERRED":
                final boolean hasColor = hasColorAnnotation(accessor);
                final boolean hasResourceId = hasResourceIdAnnotation(accessor);

                if (hasColor) {
                    enumMapping.ifPresent(value -> {
                        throw new ProcessingException(
                                "Cannot use enumMapping on a color type.",
                                accessor,
                                annotation,
                                value);
                    });
                    flagMapping.ifPresent(value -> {
                        throw new ProcessingException(
                                "Cannot use flagMapping on a color type.",
                                accessor,
                                annotation,
                                value);
                    });
                    if (hasResourceId) {
                        throw new ProcessingException(
                                "Cannot infer type, both color and resource ID annotations "
                                        + "are present.",
                                accessor,
                                annotation);
                    }
                    return Property.Type.COLOR;
                } else if (hasResourceId) {
                    enumMapping.ifPresent(value -> {
                        throw new ProcessingException(
                                "Cannot use enumMapping on a resource ID type.",
                                accessor,
                                annotation,
                                value);
                    });
                    flagMapping.ifPresent(value -> {
                        throw new ProcessingException(
                                "Cannot use flagMapping on a resource ID type.",
                                accessor,
                                annotation,
                                value);
                    });
                    return Property.Type.RESOURCE_ID;
                } else if (enumMapping.isPresent()) {
                    flagMapping.ifPresent(value -> {
                        throw new ProcessingException(
                                "Cannot use flagMapping and enumMapping simultaneously.",
                                accessor,
                                annotation,
                                value);
                    });
                    return Property.Type.INT_ENUM;
                } else if (flagMapping.isPresent()) {
                    return Property.Type.INT_FLAG;
                } else {
                    return accessorType;
                }
            case "NONE":
                return accessorType;
            case "COLOR":
                switch (accessorType) {
                    case COLOR:
                    case INT:
                    case LONG:
                        return Property.Type.COLOR;
                    default:
                        throw new ProcessingException(
                                "Color must be a long, integer, or android.graphics.Color",
                                accessor,
                                annotation);
                }
            case "GRAVITY":
                requirePackedIntToBeInt("Gravity", accessorType, accessor, annotation);
                return Property.Type.GRAVITY;
            case "INT_ENUM":
                requirePackedIntToBeInt("IntEnum", accessorType, accessor, annotation);
                return Property.Type.INT_ENUM;
            case "INT_FLAG":
                requirePackedIntToBeInt("IntFlag", accessorType, accessor, annotation);
                return Property.Type.INT_FLAG;
            case "RESOURCE_ID":
                return Property.Type.RESOURCE_ID;
            default:
                throw new ProcessingException(
                        String.format("Unknown value type enumeration value: %s", valueType),
                        accessor,
                        annotation);
        }
    }

    /**
     * Get the type of a field or the return type of a method.
     *
     * @param element The element to extract a {@link TypeMirror} from
     * @return The return or field type of the element
     * @throws ProcessingException If the element is not a field or a method
     */
    @NonNull
    private TypeMirror extractReturnOrFieldType(@NonNull Element element) {
        switch (element.getKind()) {
            case FIELD:
                return element.asType();
            case METHOD:
                return ((ExecutableElement) element).getReturnType();
            default:
                throw new ProcessingException(
                        String.format(
                                "Unable to determine the type of a %s.",
                                element.getKind()),
                        element);
        }
    }

    /**
     * Get a property type from a type mirror
     *
     * @param typeMirror The type mirror to convert to a property type
     * @param element The element to be used for exceptions
     * @return The property type returned by the getter
     * @throws ProcessingException If the return type is not a primitive or an object
     */
    @NonNull
    private Property.Type convertTypeMirrorToPropertyType(
            @NonNull TypeMirror typeMirror,
            @NonNull Element element) {
        switch (unboxType(typeMirror)) {
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
                if (isColorType(typeMirror)) {
                    return Property.Type.COLOR;
                } else {
                    return Property.Type.OBJECT;
                }
            case ARRAY:
                return Property.Type.OBJECT;
            default:
                throw new ProcessingException(
                        String.format("Unsupported property type %s.", typeMirror),
                        element);
        }
    }

    /**
     * Require that a value type packed into an integer be on a getter that returns an int.
     *
     * @param typeName The name of the type to use in the exception
     * @param returnType The return type of the getter to check
     * @param accessor The getter, to use in the exception
     * @param annotation The annotation, to use in the exception
     * @throws ProcessingException If the return type is not an int
     */
    private static void requirePackedIntToBeInt(
            @NonNull String typeName,
            @NonNull Property.Type returnType,
            @NonNull Element accessor,
            @NonNull AnnotationMirror annotation) {
        if (returnType != Property.Type.INT) {
            throw new ProcessingException(
                    String.format(
                            "%s can only be defined on a method that returns int, got %s.",
                            typeName,
                            returnType.toString().toLowerCase()),
                    accessor,
                    annotation);
        }
    }

    /**
     * Determine if a getter is annotated with color annotation matching its return type.
     *
     * Note that an {@code int} return value annotated with {@link android.annotation.ColorLong} is
     * not considered to be annotated, nor is a {@code long} annotated with
     * {@link android.annotation.ColorInt}.
     *
     * @param accessor The getter or field to query
     * @return True if the getter has a color annotation, false otherwise
     */
    private boolean hasColorAnnotation(@NonNull Element accessor) {
        switch (unboxType(extractReturnOrFieldType(accessor))) {
            case INT:
                for (String name : COLOR_INT_ANNOTATION_NAMES) {
                    if (mAnnotationUtils.hasAnnotation(accessor, name)) {
                        return true;
                    }
                }
                return false;
            case LONG:
                for (String name : COLOR_LONG_ANNOTATION_NAMES) {
                    if (mAnnotationUtils.hasAnnotation(accessor, name)) {
                        return true;
                    }
                }
                return false;
            default:
                return false;
        }
    }

    /**
     * Determine if a getter or a field is annotated with a resource ID annotation.
     *
     * @param accessor The getter or field to query
     * @return True if the accessor is an integer and has a resource ID annotation, false otherwise
     */
    private boolean hasResourceIdAnnotation(@NonNull Element accessor) {
        if (unboxType(extractReturnOrFieldType(accessor)) == TypeKind.INT) {
            for (String name : RESOURCE_ID_ANNOTATION_NAMES) {
                if (mAnnotationUtils.hasAnnotation(accessor, name)) {
                    return true;
                }
            }
        }

        return false;
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
    @NonNull
    private String inferPropertyNameFromGetter(@NonNull ExecutableElement getter) {
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
     * Build a model of an {@code int} enumeration mapping from annotation values.
     *
     * This method only handles the one-to-one mapping of mirrors of
     * {@link android.view.inspector.InspectableProperty.EnumMap} annotations into
     * {@link IntEnumEntry} objects. Further validation should be handled elsewhere
     *
     * @see android.view.inspector.InspectableProperty#enumMapping()
     * @param accessor The accessor of the property, used for exceptions
     * @param annotation The {@link android.view.inspector.InspectableProperty} annotation to
     *                   extract enum mapping values from.
     * @return A list of int enum entries, in the order specified in source
     * @throws ProcessingException if mapping doesn't exist or is invalid
     */
    @NonNull
    private List<IntEnumEntry> processEnumMapping(
            @NonNull Element accessor,
            @NonNull AnnotationMirror annotation) {
        List<AnnotationMirror> enumAnnotations = mAnnotationUtils.typedArrayValuesByName(
                "enumMapping", AnnotationMirror.class, accessor, annotation);
        List<IntEnumEntry> enumEntries = new ArrayList<>(enumAnnotations.size());

        if (enumAnnotations.isEmpty()) {
            throw new ProcessingException(
                    "Encountered an empty array for enumMapping", accessor, annotation);
        }

        for (AnnotationMirror enumAnnotation : enumAnnotations) {
            final String name = mAnnotationUtils.typedValueByName(
                    "name", String.class, accessor, enumAnnotation)
                    .orElseThrow(() -> new ProcessingException(
                            "Name is required for @EnumMap",
                            accessor,
                            enumAnnotation));

            final int value = mAnnotationUtils.typedValueByName(
                    "value", Integer.class, accessor, enumAnnotation)
                    .orElseThrow(() -> new ProcessingException(
                            "Value is required for @EnumMap",
                            accessor,
                            enumAnnotation));

            enumEntries.add(new IntEnumEntry(value, name));
        }

        return enumEntries;
    }

    /**
     * Build a model of an {@code int} flag mapping from annotation values.
     *
     * This method only handles the one-to-one mapping of mirrors of
     * {@link android.view.inspector.InspectableProperty.FlagMap} annotations into
     * {@link IntFlagEntry} objects. Further validation should be handled elsewhere
     *
     * @see android.view.inspector.IntFlagMapping
     * @see android.view.inspector.InspectableProperty#flagMapping()
     * @param accessor The accessor of the property, used for exceptions
     * @param annotation The {@link android.view.inspector.InspectableProperty} annotation to
     *                   extract flag mapping values from.
     * @return A list of int flags entries, in the order specified in source
     * @throws ProcessingException if mapping doesn't exist or is invalid
     */
    @NonNull
    private List<IntFlagEntry> processFlagMapping(
            @NonNull Element accessor,
            @NonNull AnnotationMirror annotation) {
        List<AnnotationMirror> flagAnnotations = mAnnotationUtils.typedArrayValuesByName(
                "flagMapping", AnnotationMirror.class, accessor, annotation);
        List<IntFlagEntry> flagEntries = new ArrayList<>(flagAnnotations.size());

        if (flagAnnotations.isEmpty()) {
            throw new ProcessingException(
                    "Encountered an empty array for flagMapping", accessor, annotation);
        }

        for (AnnotationMirror flagAnnotation : flagAnnotations) {
            final String name = mAnnotationUtils.typedValueByName(
                    "name", String.class, accessor, flagAnnotation)
                    .orElseThrow(() -> new ProcessingException(
                            "Name is required for @FlagMap",
                            accessor,
                            flagAnnotation));

            final int target = mAnnotationUtils.typedValueByName(
                    "target", Integer.class, accessor, flagAnnotation)
                    .orElseThrow(() -> new ProcessingException(
                            "Target is required for @FlagMap",
                            accessor,
                            flagAnnotation));

            final Optional<Integer> mask = mAnnotationUtils.typedValueByName(
                    "mask", Integer.class, accessor, flagAnnotation);

            if (mask.isPresent()) {
                flagEntries.add(new IntFlagEntry(mask.get(), target, name));
            } else {
                flagEntries.add(new IntFlagEntry(target, name));
            }
        }

        return flagEntries;
    }

    /**
     * Determine if a {@link TypeMirror} is a boxed or unboxed boolean.
     *
     * @param type The type mirror to check
     * @return True if the type is a boolean
     */
    private boolean isBoolean(@NonNull TypeMirror type) {
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
    @NonNull
    private TypeKind unboxType(@NonNull TypeMirror typeMirror) {
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
    private boolean isColorType(@NonNull TypeMirror typeMirror) {
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
