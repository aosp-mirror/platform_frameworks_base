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

import android.processor.view.inspector.InspectableClassModel.IntEnumEntry;
import android.processor.view.inspector.InspectableClassModel.IntFlagEntry;
import android.processor.view.inspector.InspectableClassModel.Property;

import androidx.annotation.NonNull;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

import javax.annotation.processing.Filer;
import javax.lang.model.element.Modifier;

/**
 * Generates a source file defining a {@link android.view.inspector.InspectionCompanion}.
 */
public final class InspectionCompanionGenerator {
    private final @NonNull Filer mFiler;
    private final @NonNull Class mRequestingClass;

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
     * The class name of {@link android.util.SparseArray}.
     */
    private static final ClassName SPARSE_ARRAY = ClassName.get("android.util", "SparseArray");

    /**
     * The class name of {@link android.view.inspector.IntFlagMapping}.
     */
    private static final ClassName INT_FLAG_MAPPING = ClassName.get(
            "android.view.inspector", "IntFlagMapping");

    /**
     * The suffix of the generated class name after the class's binary name.
     */
    private static final String GENERATED_CLASS_SUFFIX = "$InspectionCompanion";

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
    public InspectionCompanionGenerator(@NonNull Filer filer, @NonNull Class requestingClass) {
        mFiler = filer;
        mRequestingClass = requestingClass;
    }

    /**
     * Generate and write an inspection companion.
     *
     * @param model The model to generated
     * @throws IOException From the Filer
     */
    public void generate(@NonNull InspectableClassModel model) throws IOException {
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
    @NonNull
    JavaFile generateFile(@NonNull InspectableClassModel model) {
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
    @NonNull
    private TypeSpec generateTypeSpec(@NonNull InspectableClassModel model) {
        final List<Property> properties = new ArrayList<>(model.getAllProperties());
        properties.sort(Comparator.comparing(Property::getName));

        final Map<Property, FieldSpec> fields = generateIdFieldSpecs(properties);

        TypeSpec.Builder builder = TypeSpec
                .classBuilder(generateClassName(model))
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(ParameterizedTypeName.get(
                        INSPECTION_COMPANION, model.getClassName()))
                .addJavadoc("Inspection companion for {@link $T}.\n\n", model.getClassName())
                .addJavadoc("Generated by {@link $T}\n", getClass())
                .addJavadoc("on behalf of {@link $T}.\n", mRequestingClass)
                .addField(FieldSpec
                        .builder(TypeName.BOOLEAN, "mPropertiesMapped", Modifier.PRIVATE)
                        .initializer("false")
                        .addJavadoc("Guards against reading properties before mapping them.\n")
                        .build())
                .addFields(properties.stream().map(fields::get).collect(Collectors.toList()))
                .addMethod(generateMapProperties(properties, fields))
                .addMethod(generateReadProperties(properties, fields, model.getClassName()));

        model.getNodeName().ifPresent(name -> builder.addMethod(generateGetNodeName(name)));

        return builder.build();
    }

    /**
     * Map properties to fields to store the mapping IDs in the generated inspection companion.
     *
     * @param properties A list of property models
     * @return A map of properties to their {@link FieldSpec}
     */
    @NonNull
    private Map<Property, FieldSpec> generateIdFieldSpecs(@NonNull List<Property> properties) {
        final Map<Property, FieldSpec> fields = new HashMap<>();
        final NameAllocator fieldNames = new NameAllocator();
        fieldNames.newName("mPropertiesMapped");

        for (Property property : properties) {
            final String memberName = fieldNames.newName(String.format(
                    "m%s%sId",
                    property.getName().substring(0, 1).toUpperCase(),
                    property.getName().substring(1)));

            fields.put(property, FieldSpec
                    .builder(TypeName.INT, memberName, Modifier.PRIVATE)
                    .addJavadoc("Property ID of {@code $L}.\n", property.getName())
                    .build());
        }

        return fields;
    }

    /**
     * Generates an implementation of
     * {@link android.view.inspector.InspectionCompanion#mapProperties(
     * android.view.inspector.PropertyMapper)}.
     *
     * Example:
     * <pre>
     *     @Override
     *     public void mapProperties(PropertyMapper propertyMapper) {
     *         mValueId = propertyMapper.mapInt("value", R.attr.value);
     *         mPropertiesMapped = true;
     *     }
     * </pre>
     *
     * @param properties A sorted list of property models
     * @param fields A map of properties to their ID field specs
     * @return A method definition
     */
    @NonNull
    private MethodSpec generateMapProperties(
            @NonNull List<Property> properties,
            @NonNull Map<Property, FieldSpec> fields) {
        final NameAllocator mappingVariables = new NameAllocator();

        final MethodSpec.Builder builder = MethodSpec.methodBuilder("mapProperties")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(PROPERTY_MAPPER, "propertyMapper");

        // Reserve existing names
        mappingVariables.newName("mPropertiesMapped");
        mappingVariables.newName("propertyMapper");
        properties.forEach(p -> mappingVariables.newName(fields.get(p).name));

        for (Property property : properties) {
            final FieldSpec field = fields.get(property);
            switch (property.getType()) {
                case INT_ENUM:
                    builder.addCode(generateIntEnumPropertyMapperInvocation(
                            property,
                            field,
                            mappingVariables.newName(property.getName() + "EnumMapping")));
                    break;
                case INT_FLAG:
                    builder.addCode(generateIntFlagPropertyMapperInvocation(
                            property,
                            field,
                            mappingVariables.newName(property.getName() + "FlagMapping")));
                    break;
                default:
                    builder.addCode(generateSimplePropertyMapperInvocation(property, field));
            }
        }

        builder.addStatement("mPropertiesMapped = true");

        return builder.build();
    }

    /**
     * Generate a {@link android.view.inspector.PropertyMapper} invocation.
     *
     * Example:
     * <pre>
     *     mValueId = propertyMapper.mapInt("value", R.attr.value);
     * </pre>
     *
     * @param property A property model to map
     * @param field The property ID field for the property
     * @return A code block containing a statement
     */
    @NonNull
    private CodeBlock generateSimplePropertyMapperInvocation(
            @NonNull Property property,
            @NonNull FieldSpec field) {
        return CodeBlock
                .builder()
                .addStatement(
                        "$N = propertyMapper.map$L($S, $L)",
                        field,
                        methodSuffixForPropertyType(property.getType()),
                        property.getName(),
                        generateAttributeId(property))
                .build();
    }

    /**
     * Generate a {@link android.view.inspector.PropertyMapper} invocation for an int enum.
     *
     * Example:
     * <pre>
     *     final SparseArray<String> valueEnumMapping = new SparseArray<>();
     *     valueEnumMapping.put(1, "ONE");
     *     valueEnumMapping.put(2, "TWO");
     *     mValueId = propertyMapper.mapIntEnum("value", R.attr.value, valueEnumMapping::get);
     * </pre>
     *
     * @param property A property model to map
     * @param field The property ID field for the property
     * @param variable The name of a local variable to use to store the mapping in
     * @return A code block containing a series of statements
     */
    @NonNull
    private CodeBlock generateIntEnumPropertyMapperInvocation(
            @NonNull Property property,
            @NonNull FieldSpec field,
            @NonNull String variable) {
        final CodeBlock.Builder builder = CodeBlock.builder();

        final List<IntEnumEntry> enumEntries = property.getIntEnumEntries();
        enumEntries.sort(Comparator.comparing(IntEnumEntry::getValue));

        builder.addStatement(
                "final $1T<$2T> $3N = new $1T<>()",
                SPARSE_ARRAY,
                String.class,
                variable);

        for (IntEnumEntry enumEntry : enumEntries) {
            builder.addStatement(
                    "$N.put($L, $S)",
                    variable,
                    enumEntry.getValue(),
                    enumEntry.getName());
        }

        builder.addStatement(
                "$N = propertyMapper.mapIntEnum($S, $L, $N::get)",
                field,
                property.getName(),
                generateAttributeId(property),
                variable);

        return builder.build();
    }

    /**
     * Generate a {@link android.view.inspector.PropertyMapper} invocation for an int flag.
     *
     * Example:
     * <pre>
     *     final IntFlagMapping valueFlagMapping = new IntFlagMapping();
     *     valueFlagMapping.add(0x00000003, 0x00000001, "ONE");
     *     valueFlagMapping.add(0x00000003, 0x00000002, "TWO");
     *     mValueId = propertyMapper.mapIntFlag("value", R.attr.value, valueFlagMapping::get);
     * </pre>
     *
     * @param property A property model to map
     * @param field The property ID field for the property
     * @param variable The name of a local variable to use to store the mapping in
     * @return A code block containing a series of statements
     */
    @NonNull
    private CodeBlock generateIntFlagPropertyMapperInvocation(
            @NonNull Property property,
            @NonNull FieldSpec field,
            @NonNull String variable) {
        final CodeBlock.Builder builder = CodeBlock.builder();

        final List<IntFlagEntry> flagEntries = property.getIntFlagEntries();
        flagEntries.sort(Comparator.comparing(IntFlagEntry::getName));

        builder.addStatement(
                "final $1T $2N = new $1T()",
                INT_FLAG_MAPPING,
                variable);

        for (IntFlagEntry flagEntry : flagEntries) {
            builder.addStatement(
                    "$N.add($L, $L, $S)",
                    variable,
                    hexLiteral(flagEntry.getMask()),
                    hexLiteral(flagEntry.getTarget()),
                    flagEntry.getName());
        }

        builder.addStatement(
                "$N = propertyMapper.mapIntFlag($S, $L, $N::get)",
                field,
                property.getName(),
                generateAttributeId(property),
                variable);

        return builder.build();
    }

    /**
     * Generate a literal attribute ID or reference to {@link android.R.attr}.
     *
     * Example: {@code R.attr.value} or {@code 0xdecafbad}.
     *
     * @param property A property model
     * @return A code block containing the attribute ID
     */
    @NonNull
    private CodeBlock generateAttributeId(@NonNull Property property) {
        if (property.isAttributeIdInferrableFromR()) {
            return CodeBlock.of("$T.attr.$L", R_CLASS_NAME, property.getName());
        } else {
            if (property.getAttributeId() == ID_NULL) {
                return CodeBlock.of("$L", ID_NULL);
            } else {
                return CodeBlock.of("$L", hexLiteral(property.getAttributeId()));
            }
        }
    }

    /**
     * Generate an implementation of
     * {@link android.view.inspector.InspectionCompanion#readProperties(Object,
     * android.view.inspector.PropertyReader)}.
     *
     * Example:
     * <pre>
     *     @Override
     *     public void readProperties(MyNode node, PropertyReader propertyReader) {
     *         if (!mPropertiesMapped) {
     *             throw new InspectionCompanion.UninitializedPropertyMapException();
     *         }
     *         propertyReader.readInt(mValueId, node.getValue());
     *     }
     * </pre>
     *
     * @param properties An ordered list of property models
     * @param fields A map from properties to their field specs
     * @param nodeClass The class of the node, used for the parameter type
     * @return A method definition
     */
    @NonNull
    private MethodSpec generateReadProperties(
            @NonNull List<Property> properties,
            @NonNull Map<Property, FieldSpec> fields,
            @NonNull ClassName nodeClass) {
        final MethodSpec.Builder builder =  MethodSpec.methodBuilder("readProperties")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(nodeClass, "node")
                .addParameter(PROPERTY_READER, "propertyReader")
                .beginControlFlow("if (!mPropertiesMapped)")
                .addStatement(
                        "throw new $T()",
                        INSPECTION_COMPANION.nestedClass("UninitializedPropertyMapException"))
                .endControlFlow();

        for (Property property : properties) {
            builder.addStatement(
                    "propertyReader.read$L($N, node.$L)",
                    methodSuffixForPropertyType(property.getType()),
                    fields.get(property),
                    property.getAccessor().invocation());
        }

        return builder.build();
    }

    /**
     * Generate an implementation of
     * {@link android.view.inspector.InspectionCompanion#getNodeName()}.
     *
     * Example:
     * <pre>
     *     @Override
     *     public String getNodeName() {
     *         return "nodeName";
     *     }
     * </pre>
     *
     * @param nodeName The name of this node
     * @return A method definition that returns the node name
     */
    @NonNull
    private MethodSpec generateGetNodeName(@NonNull String nodeName) {
        return MethodSpec.methodBuilder("getNodeName")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(String.class)
                .addStatement("return $S", nodeName)
                .build();
    }

    /**
     * Generate the final class name for the inspection companion from the model's class name.
     *
     * The generated class is added to the same package as the source class. If the class in the
     * model is a nested class, the nested class names are joined with {@code "$"}. The suffix
     * {@code "$InspectionCompanion"} is always added to the generated name. E.g.: For modeled
     * class {@code com.example.Outer.Inner}, the generated class name will be
     * {@code com.example.Outer$Inner$InspectionCompanion}.
     *
     * @param model The model to generate from
     * @return A class name for the generated inspection companion class
     */
    @NonNull
    private static ClassName generateClassName(@NonNull InspectableClassModel model) {
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
    @NonNull
    private static String methodSuffixForPropertyType(@NonNull Property.Type type) {
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
            case RESOURCE_ID:
                return "ResourceId";
            default:
                throw new NoSuchElementException(String.format("No such property type, %s", type));
        }
    }

    /**
     * Format an int as an 8 digit hex literal
     *
     * @param value The value to format
     * @return A string representation of the hex literal
     */
    @NonNull
    private static String hexLiteral(int value) {
        return String.format("0x%08x", value);
    }
}
