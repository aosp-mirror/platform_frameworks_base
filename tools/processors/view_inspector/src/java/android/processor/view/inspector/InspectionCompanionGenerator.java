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

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.NameAllocator;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import javax.annotation.processing.Filer;
import javax.lang.model.element.Modifier;

/**
 * Generates a source file defining a {@link android.view.inspector.InspectionCompanion}.
 */
public final class InspectionCompanionGenerator {
    private final Filer mFiler;
    private final Class mRequestingClass;

    /**
     * The class name for {@code R.java}.
     */
    private static final ClassName R_CLASS_NAME = ClassName.get("android", "R");

    /**
     * The class name of {@link android.view.inspector.InspectionCompanion}.
     */
    private static final ClassName INSPECTION_COMPANION = ClassName.get(
            "android.view.inspector", "InspectionCompanion");

    /**
     * The class name of {@link android.view.inspector.PropertyMapper}.
     */
    private static final ClassName PROPERTY_MAPPER = ClassName.get(
            "android.view.inspector", "PropertyMapper");

    /**
     * The class name of {@link android.view.inspector.PropertyReader}.
     */
    private static final ClassName PROPERTY_READER = ClassName.get(
            "android.view.inspector", "PropertyReader");

    /**
     * The {@code mPropertiesMapped} field.
     */
    private static final FieldSpec M_PROPERTIES_MAPPED = FieldSpec
            .builder(TypeName.BOOLEAN, "mPropertiesMapped", Modifier.PRIVATE)
            .initializer("false")
            .addJavadoc(
                    "Set by {@link #mapProperties($T)} once properties have been mapped.\n",
                    PROPERTY_MAPPER)
            .build();

    /**
     * The suffix of the generated class name after the class's binary name.
     */
    private static final String GENERATED_CLASS_SUFFIX = "$$InspectionCompanion";

    /**
     * The null resource ID, copied to avoid a host dependency on platform code.
     *
     * @see android.content.res.Resources#ID_NULL
     */
    private static final int ID_NULL = 0;

    /**
     * @param filer A filer to write the generated source to
     * @param requestingClass A class object representing the class that invoked the generator
     */
    public InspectionCompanionGenerator(Filer filer, Class requestingClass) {
        mFiler = filer;
        mRequestingClass = requestingClass;
    }

    /**
     * Generate and write an inspection companion.
     *
     * @param model The model to generated
     * @throws IOException From the Filer
     */
    public void generate(InspectableClassModel model) throws IOException {
        generateFile(model).writeTo(mFiler);
    }

    /**
     * Generate a {@link JavaFile} from a model.
     *
     * This is package-public for testing.
     *
     * @param model The model to generate from
     * @return A generated file of an {@link android.view.inspector.InspectionCompanion}
     */
    JavaFile generateFile(InspectableClassModel model) {
        return JavaFile
                .builder(model.getClassName().packageName(), generateTypeSpec(model))
                .indent("    ")
                .build();
    }

    /**
     * Generate a {@link TypeSpec} for the {@link android.view.inspector.InspectionCompanion}
     * for the supplied model.
     *
     * @param model The model to generate from
     * @return A TypeSpec of the inspection companion
     */
    private TypeSpec generateTypeSpec(InspectableClassModel model) {
        final List<PropertyIdField> propertyIdFields = generatePropertyIdFields(model);

        TypeSpec.Builder builder = TypeSpec
                .classBuilder(generateClassName(model))
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(ParameterizedTypeName.get(
                        INSPECTION_COMPANION, model.getClassName()))
                .addJavadoc("Inspection companion for {@link $T}.\n\n", model.getClassName())
                .addJavadoc("Generated by {@link $T}\n", getClass())
                .addJavadoc("on behalf of {@link $T}.\n", mRequestingClass)
                .addField(M_PROPERTIES_MAPPED);

        for (PropertyIdField propertyIdField : propertyIdFields) {
            builder.addField(propertyIdField.mFieldSpec);
        }

        builder.addMethod(generateMapProperties(propertyIdFields))
                .addMethod(generateReadProperties(model, propertyIdFields));

        generateGetNodeName(model).ifPresent(builder::addMethod);

        return builder.build();
    }

    /**
     * Build a list of {@link PropertyIdField}'s for a model.
     *
     * To insure idempotency of the generated code, this method sorts the list of properties
     * alphabetically by name.
     *
     * A {@link NameAllocator} is used to ensure that the field names are valid Java identifiers,
     * and it prevents overlaps in names by suffixing them as needed.
     *
     * @param model The model to get properties from
     * @return A list of properties and fields
     */
    private List<PropertyIdField> generatePropertyIdFields(InspectableClassModel model) {
        final NameAllocator nameAllocator = new NameAllocator();
        final List<Property> sortedProperties = new ArrayList<>(model.getAllProperties());
        final List<PropertyIdField> propertyIdFields = new ArrayList<>(sortedProperties.size());

        sortedProperties.sort(Comparator.comparing(Property::getName));

        for (Property property : sortedProperties) {
            // Format a property to a member field name like "someProperty" -> "mSomePropertyId"
            final String memberName = String.format(
                    "m%s%sId",
                    property.getName().substring(0, 1).toUpperCase(),
                    property.getName().substring(1));
            final FieldSpec fieldSpec = FieldSpec
                    .builder(TypeName.INT, nameAllocator.newName(memberName), Modifier.PRIVATE)
                    .addJavadoc("Property ID of {@code $L}.\n", property.getName())
                    .build();

            propertyIdFields.add(new PropertyIdField(fieldSpec, property));
        }

        return propertyIdFields;
    }

    /**
     * Generate a method definition for
     * {@link android.view.inspector.InspectionCompanion#getNodeName()}, if needed.
     *
     * If {@link InspectableClassModel#getNodeName()} is empty, This method returns an empty
     * optional, otherwise, it generates a simple method that returns the string value of the
     * node name.
     *
     * @param model The model to generate from
     * @return The method definition or an empty Optional
     */
    private Optional<MethodSpec> generateGetNodeName(InspectableClassModel model) {
        return model.getNodeName().map(nodeName -> MethodSpec.methodBuilder("getNodeName")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(String.class)
                .addStatement("return $S", nodeName)
                .build());
    }

    /**
     * Generate a method definition for
     * {@link android.view.inspector.InspectionCompanion#mapProperties(
     * android.view.inspector.PropertyMapper)}.
     *
     * @param propertyIdFields A list of properties to map to ID fields
     * @return The method definition
     */
    private MethodSpec generateMapProperties(List<PropertyIdField> propertyIdFields) {
        final MethodSpec.Builder builder = MethodSpec.methodBuilder("mapProperties")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(PROPERTY_MAPPER, "propertyMapper");

        propertyIdFields.forEach(p -> builder.addStatement(generatePropertyMapperInvocation(p)));
        builder.addStatement("$N = true", M_PROPERTIES_MAPPED);

        return builder.build();
    }

    /**
     * Generate a method definition for
     * {@link android.view.inspector.InspectionCompanion#readProperties(
     * Object, android.view.inspector.PropertyReader)}.
     *
     * @param model The model to generate from
     * @param propertyIdFields A list of properties and ID fields to read from
     * @return The method definition
     */
    private MethodSpec generateReadProperties(
            InspectableClassModel model,
            List<PropertyIdField> propertyIdFields) {
        final MethodSpec.Builder builder =  MethodSpec.methodBuilder("readProperties")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(model.getClassName(), "inspectable")
                .addParameter(PROPERTY_READER, "propertyReader")
                .addCode(generatePropertyMapInitializationCheck());

        for (PropertyIdField propertyIdField : propertyIdFields) {
            builder.addStatement(
                    "propertyReader.read$L($N, inspectable.$L())",
                    methodSuffixForPropertyType(propertyIdField.mProperty.getType()),
                    propertyIdField.mFieldSpec,
                    propertyIdField.mProperty.getGetter());
        }

        return builder.build();
    }

    /**
     * Generate a statement maps a property with a {@link android.view.inspector.PropertyMapper}.
     *
     * @param propertyIdField The property model and ID field to generate from
     * @return A statement that invokes property mapper method
     */
    private CodeBlock generatePropertyMapperInvocation(PropertyIdField propertyIdField) {
        final CodeBlock.Builder builder = CodeBlock.builder();
        final Property property = propertyIdField.mProperty;
        final FieldSpec fieldSpec = propertyIdField.mFieldSpec;

        builder.add(
                "$N = propertyMapper.map$L($S,$W",
                fieldSpec,
                methodSuffixForPropertyType(property.getType()),
                property.getName());

        if (property.isAttributeIdInferrableFromR()) {
            builder.add("$T.attr.$L", R_CLASS_NAME, property.getName());
        } else {
            if (property.getAttributeId() == ID_NULL) {
                builder.add("$L", ID_NULL);
            } else {
                builder.add("$L", String.format("0x%08x", property.getAttributeId()));
            }
        }

        switch (property.getType()) {
            case INT_ENUM:
                throw new RuntimeException("IntEnumMapping generation not implemented");
            case INT_FLAG:
                throw new RuntimeException("IntFlagMapping generation not implemented");
            default:
                builder.add(")");
                break;
        }

        return builder.build();
    }

    /**
     * Generate a check that throws
     * {@link android.view.inspector.InspectionCompanion.UninitializedPropertyMapException}
     * if the properties haven't been initialized.
     *
     * <pre>
     *     if (!mPropertiesMapped) {
     *         throw new InspectionCompanion.UninitializedPropertyMapException();
     *     }
     * </pre>
     *
     * @return A codeblock containing the property map initialization check
     */
    private CodeBlock generatePropertyMapInitializationCheck() {
        return CodeBlock.builder()
                .beginControlFlow("if (!$N)", M_PROPERTIES_MAPPED)
                .addStatement(
                        "throw new $T()",
                        INSPECTION_COMPANION.nestedClass("UninitializedPropertyMapException"))
                .endControlFlow()
                .build();
    }

    /**
     * Generate the final class name for the inspection companion from the model's class name.
     *
     * The generated class is added to the same package as the source class. If the class in the
     * model is a nested class, the nested class names are joined with {@code "$"}. The suffix
     * {@code "$$InspectionCompanion"} is always added the the generated name. E.g.: For modeled
     * class {@code com.example.Outer.Inner}, the generated class name will be
     * {@code com.example.Outer$Inner$$InspectionCompanion}.
     *
     * @param model The model to generate from
     * @return A class name for the generated inspection companion class
     */
    private static ClassName generateClassName(InspectableClassModel model) {
        final ClassName className = model.getClassName();

        return ClassName.get(
                className.packageName(),
                String.join("$", className.simpleNames()) + GENERATED_CLASS_SUFFIX);
    }

    /**
     * Get the suffix for a {@code map} or {@code read} method for a property type.
     *
     * @param type The requested property type
     * @return A method suffix
     */
    private static String methodSuffixForPropertyType(Property.Type type) {
        switch (type) {
            case BOOLEAN:
                return "Boolean";
            case BYTE:
                return "Byte";
            case CHAR:
                return "Char";
            case DOUBLE:
                return "Double";
            case FLOAT:
                return "Float";
            case INT:
                return "Int";
            case LONG:
                return "Long";
            case SHORT:
                return "Short";
            case OBJECT:
                return "Object";
            case COLOR:
                return "Color";
            case GRAVITY:
                return "Gravity";
            case INT_ENUM:
                return "IntEnum";
            case INT_FLAG:
                return "IntFlag";
            default:
                throw new NoSuchElementException(String.format("No such property type, %s", type));
        }
    }

    /**
     * Value class that holds a {@link Property} and a {@link FieldSpec} for that property.
     */
    private static final class PropertyIdField {
        private final FieldSpec mFieldSpec;
        private final Property mProperty;

        private PropertyIdField(FieldSpec fieldSpec, Property property) {
            mFieldSpec = fieldSpec;
            mProperty = property;
        }
    }
}
