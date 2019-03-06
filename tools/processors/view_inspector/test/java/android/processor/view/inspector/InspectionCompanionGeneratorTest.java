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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;


import android.processor.view.inspector.InspectableClassModel.Accessor;
import android.processor.view.inspector.InspectableClassModel.IntEnumEntry;
import android.processor.view.inspector.InspectableClassModel.IntFlagEntry;
import android.processor.view.inspector.InspectableClassModel.Property;

import com.google.common.base.Charsets;
import com.google.common.io.Resources;
import com.squareup.javapoet.ClassName;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.Optional;

/**
 * Tests for {@link InspectionCompanionGenerator}
 */
public class InspectionCompanionGeneratorTest {
    private static final String RESOURCE_PATH_TEMPLATE =
            "android/processor/view/inspector/InspectionCompanionGeneratorTest/%s.java.txt";
    private static final ClassName TEST_CLASS_NAME =
            ClassName.get("com.android.node", "TestNode");
    private InspectableClassModel mModel;
    private InspectionCompanionGenerator mGenerator;

    @Before
    public void setup() {
        mModel = new InspectableClassModel(TEST_CLASS_NAME);
        mGenerator = new InspectionCompanionGenerator(null, getClass());
    }

    @Test
    public void testNodeName() {
        mModel.setNodeName(Optional.of("NodeName"));
        assertGeneratedFileEquals("NodeName");
    }

    @Test
    public void testNestedClass() {
        mModel = new InspectableClassModel(
                ClassName.get("com.android.node", "Outer", "Inner"));
        assertGeneratedFileEquals("NestedClass");
    }

    @Test
    public void testSimpleProperties() {
        addProperty("boolean", "getBoolean", Property.Type.BOOLEAN);
        addProperty("byte", "getByte", Property.Type.BYTE);
        addProperty("char", "getChar", Property.Type.CHAR);
        addProperty("double", "getDouble", Property.Type.DOUBLE);
        addProperty("float", "getFloat", Property.Type.FLOAT);
        addProperty("int", "getInt", Property.Type.INT);
        addProperty("long", "getLong", Property.Type.LONG);
        addProperty("short", "getShort", Property.Type.SHORT);

        addProperty("object", "getObject", Property.Type.OBJECT);
        addProperty("color", "getColor", Property.Type.COLOR);
        addProperty("gravity", "getGravity", Property.Type.GRAVITY);
        addProperty("resourceId", "getResourceId", Property.Type.RESOURCE_ID);

        assertGeneratedFileEquals("SimpleProperties");
    }

    @Test
    public void testNoAttributeId() {
        final Property property = addProperty(
                "noAttributeProperty",
                "getNoAttributeProperty",
                Property.Type.INT);
        property.setAttributeIdInferrableFromR(false);
        mModel.putProperty(property);

        assertGeneratedFileEquals("NoAttributeId");
    }

    @Test
    public void testSuppliedAttributeId() {
        final Property property = addProperty(
                "suppliedAttributeProperty",
                "getSuppliedAttributeProperty",
                Property.Type.INT);
        property.setAttributeId(0xdecafbad);

        assertGeneratedFileEquals("SuppliedAttributeId");
    }

    @Test
    public void testIntEnum() {
        final Property property = addProperty(
                "intEnumProperty",
                "getIntEnumProperty",
                Property.Type.INT_ENUM);

        property.setIntEnumEntries(Arrays.asList(
                new IntEnumEntry("THREE", 3),
                new IntEnumEntry("TWO", 2),
                new IntEnumEntry("ONE", 1)));

        mModel.putProperty(property);

        assertGeneratedFileEquals("IntEnum");
    }

    @Test
    public void testIntFlag() {
        final Property property = addProperty(
                "intFlag",
                "getIntFlag",
                Property.Type.INT_FLAG);

        property.setAttributeIdInferrableFromR(false);
        property.setIntFlagEntries(Arrays.asList(
                new IntFlagEntry("TURBO", 0x1, 0x3),
                new IntFlagEntry("OVERDRIVE", 0x2, 0x3),
                new IntFlagEntry("WARP", 0x4)
        ));

        assertGeneratedFileEquals("IntFlag");
    }

    @Test
    public void testFieldProperty() {
        mModel.putProperty(new Property(
                "fieldProperty",
                Accessor.ofField("fieldProperty"),
                Property.Type.INT));

        assertGeneratedFileEquals("FieldProperty");
    }

    private Property addProperty(String name, String getter, Property.Type type) {
        final Property property = new Property(name, Accessor.ofGetter(getter), type);
        mModel.putProperty(property);
        return property;
    }

    private void assertGeneratedFileEquals(String fileName) {
        assertEquals(
                loadTextResource(String.format(RESOURCE_PATH_TEMPLATE, fileName)),
                mGenerator.generateFile(mModel).toString());
    }

    private String loadTextResource(String path) {
        try {
            final URL url = Resources.getResource(path);
            assertNotNull(String.format("Resource file not found: %s", path), url);
            return Resources.toString(url, Charsets.UTF_8);
        } catch (IOException e) {
            fail(e.getMessage());
            return null;
        }
    }
}
