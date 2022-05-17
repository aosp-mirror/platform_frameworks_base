/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.content.pm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.pm.PackageManager.Property;
import android.os.Bundle;
import android.platform.test.annotations.Presubmit;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class PackageManagerPropertyTests {

    @Test
    public void testBooleanProperty() throws Exception {
        final Property p =
                new Property("booleanProperty", true, "android", null);
        assertTrue(p.isBoolean());
        assertFalse(p.isFloat());
        assertFalse(p.isInteger());
        assertFalse(p.isResourceId());
        assertFalse(p.isString());
        assertTrue(p.getBoolean());
        assertEquals(0.0f, p.getFloat(), 0.0f);
        assertEquals(0, p.getInteger());
        assertEquals(0, p.getResourceId());
        assertEquals(null, p.getString());
        assertEquals("android", p.getPackageName());
        assertNull(p.getClassName());
    }

    @Test
    public void testBooleanPropertyToBundle() throws Exception {
        final Bundle b =
                new Property("booleanProperty", true, "android", null).toBundle(null);
        assertTrue(b.getBoolean("booleanProperty"));
    }

    @Test
    public void testFloatProperty() throws Exception {
        final Property p =
                new Property("floatProperty", 3.14f, "android", null);
        assertFalse(p.isBoolean());
        assertTrue(p.isFloat());
        assertFalse(p.isInteger());
        assertFalse(p.isResourceId());
        assertFalse(p.isString());
        assertFalse(p.getBoolean());
        assertEquals(3.14f, p.getFloat(), 0.0f);
        assertEquals(0, p.getInteger());
        assertEquals(0, p.getResourceId());
        assertEquals(null, p.getString());
        assertEquals("android", p.getPackageName());
        assertNull(p.getClassName());
    }

    @Test
    public void testFloatPropertyToBundle() throws Exception {
        final Bundle b =
                new Property("floatProperty", 3.14f, "android", null).toBundle(null);
        assertEquals(3.14f, b.getFloat("floatProperty"), 0.0f);
    }

    @Test
    public void testIntegerProperty() throws Exception {
        final Property p =
                new Property("integerProperty", 42, false, "android", null);
        assertFalse(p.isBoolean());
        assertFalse(p.isFloat());
        assertTrue(p.isInteger());
        assertFalse(p.isResourceId());
        assertFalse(p.isString());
        assertFalse(p.getBoolean());
        assertEquals(0.0f, p.getFloat(), 0.0f);
        assertEquals(42, p.getInteger());
        assertEquals(0, p.getResourceId());
        assertEquals(null, p.getString());
        assertEquals("android", p.getPackageName());
        assertNull(p.getClassName());
    }

    @Test
    public void testIntegerPropertyToBundle() throws Exception {
        final Bundle b =
                new Property("integerProperty", 42, false, "android", null).toBundle(null);
        assertEquals(42, b.getInt("integerProperty"));
    }

    @Test
    public void testResourceProperty() throws Exception {
        final Property p =
                new Property("resourceProperty", 0x7f010001, true, "android", null);
        assertFalse(p.isBoolean());
        assertFalse(p.isFloat());
        assertFalse(p.isInteger());
        assertTrue(p.isResourceId());
        assertFalse(p.isString());
        assertFalse(p.getBoolean());
        assertEquals(0.0f, p.getFloat(), 0.0f);
        assertEquals(0, p.getInteger());
        assertEquals(0x7f010001, p.getResourceId());
        assertEquals(null, p.getString());
        assertEquals("android", p.getPackageName());
        assertNull(p.getClassName());
    }

    @Test
    public void testResourcePropertyToBundle() throws Exception {
        final Bundle b =
                new Property("resourceProperty", 0x7f010001, true, "android", null).toBundle(null);
        assertEquals(0x7f010001, b.getInt("resourceProperty"));
    }

    @Test
    public void testStringProperty() throws Exception {
        final Property p =
                new Property("stringProperty", "koala", "android", null);
        assertFalse(p.isBoolean());
        assertFalse(p.isFloat());
        assertFalse(p.isInteger());
        assertFalse(p.isResourceId());
        assertTrue(p.isString());
        assertFalse(p.getBoolean());
        assertEquals(0.0f, p.getFloat(), 0.0f);
        assertEquals(0, p.getInteger());
        assertEquals(0, p.getResourceId());
        assertEquals("koala", p.getString());
        assertEquals("android", p.getPackageName());
        assertNull(p.getClassName());
    }

    @Test
    public void testStringPropertyToBundle() throws Exception {
        final Bundle b =
                new Property("stringProperty", "koala", "android", null).toBundle(null);
        assertEquals("koala", b.getString("stringProperty"));
    }

    @Test
    public void testProperty_invalidName() throws Exception {
        try {
            final Property p = new Property(null, 1, "android", null);
            fail("expected assertion error");
        } catch (AssertionError expected) {
        }
    }

    @Test
    public void testProperty_invalidType() throws Exception {
        try {
            final Property p = new Property("invalidTypeProperty", 0, "android", null);
            fail("expected assertion error");
        } catch (AssertionError expected) {
        }

        try {
            final Property p = new Property("invalidTypeProperty", 6, "android", null);
            fail("expected assertion error");
        } catch (AssertionError expected) {
        }

        try {
            final Property p = new Property("invalidTypeProperty", -1, "android", null);
            fail("expected assertion error");
        } catch (AssertionError expected) {
        }
    }

    @Test
    public void testProperty_noPackageName() throws Exception {
        try {
            final Property p = new Property(null, 1, null, null);
            fail("expected assertion error");
        } catch (AssertionError expected) {
        }
    }
}
