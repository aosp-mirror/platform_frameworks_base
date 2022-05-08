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

package android.provider;

import static android.provider.DeviceConfig.Properties;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.assertThrows;

import android.content.ContentResolver;
import android.os.Bundle;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests that ensure appropriate settings are backed up. */
@Presubmit
@RunWith(AndroidJUnit4.class)
@SmallTest
public class DeviceConfigTest {
    private static final long WAIT_FOR_PROPERTY_CHANGE_TIMEOUT_MILLIS = 2000; // 2 sec
    private static final String DEFAULT_VALUE = "test_default_value";
    private static final String NAMESPACE = "namespace1";
    private static final String KEY = "key1";
    private static final String KEY2 = "key2";
    private static final String KEY3 = "key3";
    private static final String KEY4 = "key4";
    private static final String VALUE = "value1";
    private static final String VALUE2 = "value2";
    private static final String VALUE3 = "value3";
    private static final String NULL_VALUE = "null";

    @After
    public void cleanUp() {
        deleteViaContentProvider(NAMESPACE, KEY);
        deleteViaContentProvider(NAMESPACE, KEY2);
        deleteViaContentProvider(NAMESPACE, KEY3);
    }

    @Test
    public void getProperty_empty() {
        String result = DeviceConfig.getProperty(NAMESPACE, KEY);
        assertThat(result).isNull();
    }

    @Test
    public void getProperty_nullNamespace() {
        try {
            DeviceConfig.getProperty(null, KEY);
            Assert.fail("Null namespace should have resulted in an NPE.");
        } catch (NullPointerException e) {
            // expected
        }
    }

    @Test
    public void getProperty_nullName() {
        try {
            DeviceConfig.getProperty(NAMESPACE, null);
            Assert.fail("Null name should have resulted in an NPE.");
        } catch (NullPointerException e) {
            // expected
        }
    }

    @Test
    public void getString_empty() {
        final String default_value = "default_value";
        final String result = DeviceConfig.getString(NAMESPACE, KEY, default_value);
        assertThat(result).isEqualTo(default_value);
    }

    @Test
    public void getString_nullDefault() {
        final String result = DeviceConfig.getString(NAMESPACE, KEY, null);
        assertThat(result).isNull();
    }

    @Test
    public void getString_nonEmpty() {
        final String value = "new_value";
        final String default_value = "default";
        DeviceConfig.setProperty(NAMESPACE, KEY, value, false);

        final String result = DeviceConfig.getString(NAMESPACE, KEY, default_value);
        assertThat(result).isEqualTo(value);
    }

    @Test
    public void getString_nullNamespace() {
        try {
            DeviceConfig.getString(null, KEY, "default_value");
            Assert.fail("Null namespace should have resulted in an NPE.");
        } catch (NullPointerException e) {
            // expected
        }
    }

    @Test
    public void getString_nullName() {
        try {
            DeviceConfig.getString(NAMESPACE, null, "default_value");
            Assert.fail("Null name should have resulted in an NPE.");
        } catch (NullPointerException e) {
            // expected
        }
    }

    @Test
    public void getBoolean_empty() {
        final boolean default_value = true;
        final boolean result = DeviceConfig.getBoolean(NAMESPACE, KEY, default_value);
        assertThat(result).isEqualTo(default_value);
    }

    @Test
    public void getBoolean_valid() {
        final boolean value = true;
        final boolean default_value = false;
        DeviceConfig.setProperty(NAMESPACE, KEY, String.valueOf(value), false);

        final boolean result = DeviceConfig.getBoolean(NAMESPACE, KEY, default_value);
        assertThat(result).isEqualTo(value);
    }

    @Test
    public void getBoolean_invalid() {
        final boolean default_value = true;
        DeviceConfig.setProperty(NAMESPACE, KEY, "not_a_boolean", false);

        final boolean result = DeviceConfig.getBoolean(NAMESPACE, KEY, default_value);
        // Anything non-null other than case insensitive "true" parses to false.
        assertThat(result).isFalse();
    }

    @Test
    public void getBoolean_nullNamespace() {
        try {
            DeviceConfig.getBoolean(null, KEY, false);
            Assert.fail("Null namespace should have resulted in an NPE.");
        } catch (NullPointerException e) {
            // expected
        }
    }

    @Test
    public void getBoolean_nullName() {
        try {
            DeviceConfig.getBoolean(NAMESPACE, null, false);
            Assert.fail("Null name should have resulted in an NPE.");
        } catch (NullPointerException e) {
            // expected
        }
    }

    @Test
    public void getInt_empty() {
        final int default_value = 999;
        final int result = DeviceConfig.getInt(NAMESPACE, KEY, default_value);
        assertThat(result).isEqualTo(default_value);
    }

    @Test
    public void getInt_valid() {
        final int value = 123;
        final int default_value = 999;
        DeviceConfig.setProperty(NAMESPACE, KEY, String.valueOf(value), false);

        final int result = DeviceConfig.getInt(NAMESPACE, KEY, default_value);
        assertThat(result).isEqualTo(value);
    }

    @Test
    public void getInt_invalid() {
        final int default_value = 999;
        DeviceConfig.setProperty(NAMESPACE, KEY, "not_an_int", false);

        final int result = DeviceConfig.getInt(NAMESPACE, KEY, default_value);
        // Failure to parse results in using the default value
        assertThat(result).isEqualTo(default_value);
    }

    @Test
    public void getInt_nullNamespace() {
        try {
            DeviceConfig.getInt(null, KEY, 0);
            Assert.fail("Null namespace should have resulted in an NPE.");
        } catch (NullPointerException e) {
            // expected
        }
    }

    @Test
    public void getInt_nullName() {
        try {
            DeviceConfig.getInt(NAMESPACE, null, 0);
            Assert.fail("Null name should have resulted in an NPE.");
        } catch (NullPointerException e) {
            // expected
        }
    }

    @Test
    public void getLong_empty() {
        final long default_value = 123456;
        final long result = DeviceConfig.getLong(NAMESPACE, KEY, default_value);
        assertThat(result).isEqualTo(default_value);
    }

    @Test
    public void getLong_valid() {
        final long value = 456789;
        final long default_value = 123456;
        DeviceConfig.setProperty(NAMESPACE, KEY, String.valueOf(value), false);

        final long result = DeviceConfig.getLong(NAMESPACE, KEY, default_value);
        assertThat(result).isEqualTo(value);
    }

    @Test
    public void getLong_invalid() {
        final long default_value = 123456;
        DeviceConfig.setProperty(NAMESPACE, KEY, "not_a_long", false);

        final long result = DeviceConfig.getLong(NAMESPACE, KEY, default_value);
        // Failure to parse results in using the default value
        assertThat(result).isEqualTo(default_value);
    }

    @Test
    public void getLong_nullNamespace() {
        try {
            DeviceConfig.getLong(null, KEY, 0);
            Assert.fail("Null namespace should have resulted in an NPE.");
        } catch (NullPointerException e) {
            // expected
        }
    }

    @Test
    public void getLong_nullName() {
        try {
            DeviceConfig.getLong(NAMESPACE, null, 0);
            Assert.fail("Null name should have resulted in an NPE.");
        } catch (NullPointerException e) {
            // expected
        }
    }

    @Test
    public void getFloat_empty() {
        final float default_value = 123.456f;
        final float result = DeviceConfig.getFloat(NAMESPACE, KEY, default_value);
        assertThat(result).isEqualTo(default_value);
    }

    @Test
    public void getFloat_valid() {
        final float value = 456.789f;
        final float default_value = 123.456f;
        DeviceConfig.setProperty(NAMESPACE, KEY, String.valueOf(value), false);

        final float result = DeviceConfig.getFloat(NAMESPACE, KEY, default_value);
        assertThat(result).isEqualTo(value);
    }

    @Test
    public void getFloat_invalid() {
        final float default_value = 123.456f;
        DeviceConfig.setProperty(NAMESPACE, KEY, "not_a_float", false);

        final float result = DeviceConfig.getFloat(NAMESPACE, KEY, default_value);
        // Failure to parse results in using the default value
        assertThat(result).isEqualTo(default_value);
    }

    @Test
    public void getFloat_nullNamespace() {
        try {
            DeviceConfig.getFloat(null, KEY, 0);
            Assert.fail("Null namespace should have resulted in an NPE.");
        } catch (NullPointerException e) {
            // expected
        }
    }

    @Test
    public void getFloat_nullName() {
        try {
            DeviceConfig.getFloat(NAMESPACE, null, 0);
            Assert.fail("Null name should have resulted in an NPE.");
        } catch (NullPointerException e) {
            // expected
        }
    }

    @Test
    public void setProperty_nullNamespace() {
        try {
            DeviceConfig.setProperty(null, KEY, VALUE, false);
            Assert.fail("Null namespace should have resulted in an NPE.");
        } catch (NullPointerException e) {
            // expected
        }
    }

    @Test
    public void setProperty_nullName() {
        try {
            DeviceConfig.setProperty(NAMESPACE, null, VALUE, false);
            Assert.fail("Null name should have resulted in an NPE.");
        } catch (NullPointerException e) {
            // expected
        }
    }

    @Test
    public void setAndGetProperty_sameNamespace() {
        DeviceConfig.setProperty(NAMESPACE, KEY, VALUE, false);
        String result = DeviceConfig.getProperty(NAMESPACE, KEY);
        assertThat(result).isEqualTo(VALUE);
    }

    @Test
    public void setAndGetProperty_differentNamespace() {
        String newNamespace = "namespace2";
        DeviceConfig.setProperty(NAMESPACE, KEY, VALUE, false);
        String result = DeviceConfig.getProperty(newNamespace, KEY);
        assertThat(result).isNull();
    }

    @Test
    public void setAndGetProperty_multipleNamespaces() {
        String newNamespace = "namespace2";
        String newValue = "value2";
        DeviceConfig.setProperty(NAMESPACE, KEY, VALUE, false);
        DeviceConfig.setProperty(newNamespace, KEY, newValue, false);
        String result = DeviceConfig.getProperty(NAMESPACE, KEY);
        assertThat(result).isEqualTo(VALUE);
        result = DeviceConfig.getProperty(newNamespace, KEY);
        assertThat(result).isEqualTo(newValue);

        // clean up
        deleteViaContentProvider(newNamespace, KEY);
    }

    @Test
    public void setAndGetProperty_overrideValue() {
        String newValue = "value2";
        DeviceConfig.setProperty(NAMESPACE, KEY, VALUE, false);
        DeviceConfig.setProperty(NAMESPACE, KEY, newValue, false);
        String result = DeviceConfig.getProperty(NAMESPACE, KEY);
        assertThat(result).isEqualTo(newValue);
    }

    @Test
    public void resetToDefault_makeDefault() {
        DeviceConfig.setProperty(NAMESPACE, KEY, VALUE, true);
        assertThat(DeviceConfig.getProperty(NAMESPACE, KEY)).isEqualTo(VALUE);

        DeviceConfig.resetToDefaults(Settings.RESET_MODE_PACKAGE_DEFAULTS, NAMESPACE);
        assertThat(DeviceConfig.getProperty(NAMESPACE, KEY)).isEqualTo(VALUE);
    }

    @Test
    public void resetToDefault_doNotMakeDefault() {
        DeviceConfig.setProperty(NAMESPACE, KEY, VALUE, false);
        assertThat(DeviceConfig.getProperty(NAMESPACE, KEY)).isEqualTo(VALUE);

        DeviceConfig.resetToDefaults(Settings.RESET_MODE_PACKAGE_DEFAULTS, NAMESPACE);
        assertThat(DeviceConfig.getProperty(NAMESPACE, KEY)).isNull();
    }

    @Test
    public void getProperties_fullNamespace() {
        Properties properties = DeviceConfig.getProperties(NAMESPACE);
        assertThat(properties.getKeyset()).isEmpty();

        DeviceConfig.setProperty(NAMESPACE, KEY, VALUE, false);
        DeviceConfig.setProperty(NAMESPACE, KEY2, VALUE2, false);
        properties = DeviceConfig.getProperties(NAMESPACE);
        assertThat(properties.getKeyset()).containsExactly(KEY, KEY2);
        assertThat(properties.getString(KEY, DEFAULT_VALUE)).isEqualTo(VALUE);
        assertThat(properties.getString(KEY2, DEFAULT_VALUE)).isEqualTo(VALUE2);

        DeviceConfig.setProperty(NAMESPACE, KEY, VALUE3, false);
        properties = DeviceConfig.getProperties(NAMESPACE);
        assertThat(properties.getKeyset()).containsExactly(KEY, KEY2);
        assertThat(properties.getString(KEY, DEFAULT_VALUE)).isEqualTo(VALUE3);
        assertThat(properties.getString(KEY2, DEFAULT_VALUE)).isEqualTo(VALUE2);

        DeviceConfig.setProperty(NAMESPACE, KEY3, VALUE, false);
        properties = DeviceConfig.getProperties(NAMESPACE);
        assertThat(properties.getKeyset()).containsExactly(KEY, KEY2, KEY3);
        assertThat(properties.getString(KEY, DEFAULT_VALUE)).isEqualTo(VALUE3);
        assertThat(properties.getString(KEY2, DEFAULT_VALUE)).isEqualTo(VALUE2);
        assertThat(properties.getString(KEY3, DEFAULT_VALUE)).isEqualTo(VALUE);
    }

    @Test
    public void getProperties_getString() {
        DeviceConfig.setProperty(NAMESPACE, KEY, VALUE, false);
        DeviceConfig.setProperty(NAMESPACE, KEY2, VALUE2, false);

        Properties properties = DeviceConfig.getProperties(NAMESPACE, KEY, KEY2);
        assertThat(properties.getKeyset()).containsExactly(KEY, KEY2);
        assertThat(properties.getString(KEY, DEFAULT_VALUE)).isEqualTo(VALUE);
        assertThat(properties.getString(KEY2, DEFAULT_VALUE)).isEqualTo(VALUE2);
    }

    @Test
    public void getProperties_getBoolean() {
        DeviceConfig.setProperty(NAMESPACE, KEY, "true", false);
        DeviceConfig.setProperty(NAMESPACE, KEY2, "false", false);
        DeviceConfig.setProperty(NAMESPACE, KEY3, "not a valid boolean", false);

        Properties properties = DeviceConfig.getProperties(NAMESPACE, KEY, KEY2, KEY3);
        assertThat(properties.getKeyset()).containsExactly(KEY, KEY2, KEY3);
        assertThat(properties.getBoolean(KEY, true)).isTrue();
        assertThat(properties.getBoolean(KEY, false)).isTrue();
        assertThat(properties.getBoolean(KEY2, true)).isFalse();
        assertThat(properties.getBoolean(KEY2, false)).isFalse();
        // KEY3 was set to garbage, anything nonnull but "true" will parse as false
        assertThat(properties.getBoolean(KEY3, true)).isFalse();
        assertThat(properties.getBoolean(KEY3, false)).isFalse();
        // If a key was not set, it will return the default value
        assertThat(properties.getBoolean("missing_key", true)).isTrue();
        assertThat(properties.getBoolean("missing_key", false)).isFalse();
    }

    @Test
    public void getProperties_getInt() {
        final int value = 101;

        DeviceConfig.setProperty(NAMESPACE, KEY, Integer.toString(value), false);
        DeviceConfig.setProperty(NAMESPACE, KEY2, "not a valid int", false);

        Properties properties = DeviceConfig.getProperties(NAMESPACE, KEY, KEY2);
        assertThat(properties.getKeyset()).containsExactly(KEY, KEY2);
        assertThat(properties.getInt(KEY, -1)).isEqualTo(value);
        // KEY2 was set to garbage, the default value is returned if an int cannot be parsed
        assertThat(properties.getInt(KEY2, -1)).isEqualTo(-1);
    }

    @Test
    public void getProperties_getFloat() {
        final float value = 101.010f;

        DeviceConfig.setProperty(NAMESPACE, KEY, Float.toString(value), false);
        DeviceConfig.setProperty(NAMESPACE, KEY2, "not a valid float", false);

        Properties properties = DeviceConfig.getProperties(NAMESPACE, KEY, KEY2);
        assertThat(properties.getKeyset()).containsExactly(KEY, KEY2);
        assertThat(properties.getFloat(KEY, -1.0f)).isEqualTo(value);
        // KEY2 was set to garbage, the default value is returned if a float cannot be parsed
        assertThat(properties.getFloat(KEY2, -1.0f)).isEqualTo(-1.0f);
    }

    @Test
    public void getProperties_getLong() {
        final long value = 101;

        DeviceConfig.setProperty(NAMESPACE, KEY, Long.toString(value), false);
        DeviceConfig.setProperty(NAMESPACE, KEY2, "not a valid long", false);

        Properties properties = DeviceConfig.getProperties(NAMESPACE, KEY, KEY2);
        assertThat(properties.getKeyset()).containsExactly(KEY, KEY2);
        assertThat(properties.getLong(KEY, -1)).isEqualTo(value);
        // KEY2 was set to garbage, the default value is returned if a long cannot be parsed
        assertThat(properties.getLong(KEY2, -1)).isEqualTo(-1);
    }

    @Test
    public void getProperties_defaults() {
        DeviceConfig.setProperty(NAMESPACE, KEY, VALUE, false);
        DeviceConfig.setProperty(NAMESPACE, KEY3, VALUE3, false);

        Properties properties = DeviceConfig.getProperties(NAMESPACE, KEY, KEY2);
        assertThat(properties.getKeyset()).containsExactly(KEY);
        assertThat(properties.getString(KEY, DEFAULT_VALUE)).isEqualTo(VALUE);
        // not set in DeviceConfig, but requested in getProperties
        assertThat(properties.getString(KEY2, DEFAULT_VALUE)).isEqualTo(DEFAULT_VALUE);
        // set in DeviceConfig, but not requested in getProperties
        assertThat(properties.getString(KEY3, DEFAULT_VALUE)).isEqualTo(DEFAULT_VALUE);
    }

    @Test
    public void setProperties() throws DeviceConfig.BadConfigException {
        Properties properties = new Properties.Builder(NAMESPACE).setString(KEY, VALUE)
                .setString(KEY2, VALUE2).build();

        DeviceConfig.setProperties(properties);
        properties = DeviceConfig.getProperties(NAMESPACE);
        assertThat(properties.getKeyset()).containsExactly(KEY, KEY2);
        assertThat(properties.getString(KEY, DEFAULT_VALUE)).isEqualTo(VALUE);
        assertThat(properties.getString(KEY2, DEFAULT_VALUE)).isEqualTo(VALUE2);

        properties = new Properties.Builder(NAMESPACE).setString(KEY, VALUE2)
                .setString(KEY3, VALUE3).build();

        DeviceConfig.setProperties(properties);
        properties = DeviceConfig.getProperties(NAMESPACE);
        assertThat(properties.getKeyset()).containsExactly(KEY, KEY3);
        assertThat(properties.getString(KEY, DEFAULT_VALUE)).isEqualTo(VALUE2);
        assertThat(properties.getString(KEY3, DEFAULT_VALUE)).isEqualTo(VALUE3);

        assertThat(properties.getKeyset()).doesNotContain(KEY2);
        assertThat(properties.getString(KEY2, DEFAULT_VALUE)).isEqualTo(DEFAULT_VALUE);
    }

    @Test
    public void setProperties_multipleNamespaces() throws DeviceConfig.BadConfigException {
        final String namespace2 = "namespace2";
        Properties properties1 = new Properties.Builder(NAMESPACE).setString(KEY, VALUE)
                .setString(KEY2, VALUE2).build();
        Properties properties2 = new Properties.Builder(namespace2).setString(KEY2, VALUE)
                .setString(KEY3, VALUE2).build();

        DeviceConfig.setProperties(properties1);
        DeviceConfig.setProperties(properties2);

        Properties properties = DeviceConfig.getProperties(NAMESPACE);
        assertThat(properties.getKeyset()).containsExactly(KEY, KEY2);
        assertThat(properties.getString(KEY, DEFAULT_VALUE)).isEqualTo(VALUE);
        assertThat(properties.getString(KEY2, DEFAULT_VALUE)).isEqualTo(VALUE2);

        assertThat(properties.getKeyset()).doesNotContain(KEY3);
        assertThat(properties.getString(KEY3, DEFAULT_VALUE)).isEqualTo(DEFAULT_VALUE);

        properties = DeviceConfig.getProperties(namespace2);
        assertThat(properties.getKeyset()).containsExactly(KEY2, KEY3);
        assertThat(properties.getString(KEY2, DEFAULT_VALUE)).isEqualTo(VALUE);
        assertThat(properties.getString(KEY3, DEFAULT_VALUE)).isEqualTo(VALUE2);

        assertThat(properties.getKeyset()).doesNotContain(KEY);
        assertThat(properties.getString(KEY, DEFAULT_VALUE)).isEqualTo(DEFAULT_VALUE);

        // clean up
        deleteViaContentProvider(namespace2, KEY);
        deleteViaContentProvider(namespace2, KEY2);
        deleteViaContentProvider(namespace2, KEY3);
    }

    @Test
    public void propertiesBuilder() {
        boolean booleanValue = true;
        int intValue = 123;
        float floatValue = 4.56f;
        long longValue = -789L;
        String key4 = "key4";
        String key5 = "key5";

        Properties properties = new Properties.Builder(NAMESPACE).setString(KEY, VALUE)
                .setBoolean(KEY2, booleanValue).setInt(KEY3, intValue).setLong(key4, longValue)
                .setFloat(key5, floatValue).build();
        assertThat(properties.getNamespace()).isEqualTo(NAMESPACE);
        assertThat(properties.getString(KEY, "defaultValue")).isEqualTo(VALUE);
        assertThat(properties.getBoolean(KEY2, false)).isEqualTo(booleanValue);
        assertThat(properties.getInt(KEY3, 0)).isEqualTo(intValue);
        assertThat(properties.getLong("key4", 0L)).isEqualTo(longValue);
        assertThat(properties.getFloat("key5", 0f)).isEqualTo(floatValue);
    }

    @Test
    public void banNamespaceProperties() throws DeviceConfig.BadConfigException {
        // Given namespace will be permanently banned, thus it needs to be different every time
        final String namespaceToBan = NAMESPACE + System.currentTimeMillis();
        Properties properties = new Properties.Builder(namespaceToBan).setString(KEY, VALUE)
                .setString(KEY4, NULL_VALUE).build();
        // Set namespace properties
        DeviceConfig.setProperties(properties);
        // Ban namespace with related properties
        DeviceConfig.resetToDefaults(Settings.RESET_MODE_PACKAGE_DEFAULTS, namespaceToBan);
        // Verify given namespace properties are banned
        assertThrows(DeviceConfig.BadConfigException.class,
                () -> DeviceConfig.setProperties(properties));
        // Modify properties and verify we can set them
        Properties modifiedProperties = new Properties.Builder(namespaceToBan).setString(KEY, VALUE)
                .setString(KEY4, NULL_VALUE).setString(KEY2, VALUE2).build();
        DeviceConfig.setProperties(modifiedProperties);
        modifiedProperties = DeviceConfig.getProperties(namespaceToBan);
        assertThat(modifiedProperties.getKeyset()).containsExactly(KEY, KEY2, KEY4);
        assertThat(modifiedProperties.getString(KEY, DEFAULT_VALUE)).isEqualTo(VALUE);
        assertThat(modifiedProperties.getString(KEY2, DEFAULT_VALUE)).isEqualTo(VALUE2);
        // Since value is null DEFAULT_VALUE should be returned
        assertThat(modifiedProperties.getString(KEY4, DEFAULT_VALUE)).isEqualTo(DEFAULT_VALUE);
    }

    @Test
    public void banEntireDeviceConfig() throws DeviceConfig.BadConfigException {
        // Given namespaces will be permanently banned, thus they need to be different every time
        final String namespaceToBan1 = NAMESPACE + System.currentTimeMillis();
        final String namespaceToBan2 = NAMESPACE + System.currentTimeMillis() + 1;

        // Set namespaces properties
        Properties properties1 = new Properties.Builder(namespaceToBan1).setString(KEY, VALUE)
                .setString(KEY4, NULL_VALUE).build();
        DeviceConfig.setProperties(properties1);
        Properties properties2 = new Properties.Builder(namespaceToBan2).setString(KEY2, VALUE2)
                .setString(KEY4, NULL_VALUE).build();
        DeviceConfig.setProperties(properties2);

        // Ban entire DeviceConfig
        DeviceConfig.resetToDefaults(Settings.RESET_MODE_PACKAGE_DEFAULTS, null);

        // Verify given namespace properties are banned
        assertThrows(DeviceConfig.BadConfigException.class,
                () -> DeviceConfig.setProperties(properties1));
        assertThrows(DeviceConfig.BadConfigException.class,
                () -> DeviceConfig.setProperties(properties2));

        // Modify properties and verify we can set them
        Properties modifiedProperties1 = new Properties.Builder(namespaceToBan1).setString(KEY,
                VALUE)
                .setString(KEY4, NULL_VALUE).setString(KEY2, VALUE2).build();
        DeviceConfig.setProperties(modifiedProperties1);
        modifiedProperties1 = DeviceConfig.getProperties(namespaceToBan1);
        assertThat(modifiedProperties1.getKeyset()).containsExactly(KEY, KEY2, KEY4);
        assertThat(modifiedProperties1.getString(KEY, DEFAULT_VALUE)).isEqualTo(VALUE);
        assertThat(modifiedProperties1.getString(KEY2, DEFAULT_VALUE)).isEqualTo(VALUE2);
        // Since value is null DEFAULT_VALUE should be returned
        assertThat(modifiedProperties1.getString(KEY4, DEFAULT_VALUE)).isEqualTo(DEFAULT_VALUE);

        Properties modifiedProperties2 = new Properties.Builder(namespaceToBan2).setString(KEY,
                VALUE)
                .setString(KEY3, NULL_VALUE).setString(KEY4, VALUE2).build();
        DeviceConfig.setProperties(modifiedProperties2);
        modifiedProperties2 = DeviceConfig.getProperties(namespaceToBan2);
        assertThat(modifiedProperties2.getKeyset()).containsExactly(KEY, KEY3, KEY4);
        assertThat(modifiedProperties2.getString(KEY, DEFAULT_VALUE)).isEqualTo(VALUE);
        assertThat(modifiedProperties2.getString(KEY4, DEFAULT_VALUE)).isEqualTo(VALUE2);
        // Since value is null DEFAULT_VALUE should be returned
        assertThat(modifiedProperties2.getString(KEY3, DEFAULT_VALUE)).isEqualTo(DEFAULT_VALUE);
    }

    @Test
    public void allConfigsUnbannedIfAnyUnbannedConfigUpdated()
            throws DeviceConfig.BadConfigException {
        // Given namespaces will be permanently banned, thus they need to be different every time
        final String namespaceToBan1 = NAMESPACE + System.currentTimeMillis();
        final String namespaceToBan2 = NAMESPACE + System.currentTimeMillis() + 1;

        // Set namespaces properties
        Properties properties1 = new Properties.Builder(namespaceToBan1).setString(KEY, VALUE)
                .setString(KEY4, NULL_VALUE).build();
        DeviceConfig.setProperties(properties1);
        Properties properties2 = new Properties.Builder(namespaceToBan2).setString(KEY2, VALUE2)
                .setString(KEY4, NULL_VALUE).build();
        DeviceConfig.setProperties(properties2);

        // Ban namespace with related properties
        DeviceConfig.resetToDefaults(Settings.RESET_MODE_PACKAGE_DEFAULTS, namespaceToBan1);
        DeviceConfig.resetToDefaults(Settings.RESET_MODE_PACKAGE_DEFAULTS, namespaceToBan2);

        // Verify given namespace properties are banned
        assertThrows(DeviceConfig.BadConfigException.class,
                () -> DeviceConfig.setProperties(properties1));
        assertThrows(DeviceConfig.BadConfigException.class,
                () -> DeviceConfig.setProperties(properties2));

        // Modify properties and verify we can set them
        Properties modifiedProperties1 = new Properties.Builder(namespaceToBan1).setString(KEY,
                VALUE)
                .setString(KEY4, NULL_VALUE).setString(KEY2, VALUE2).build();
        DeviceConfig.setProperties(modifiedProperties1);
        modifiedProperties1 = DeviceConfig.getProperties(namespaceToBan1);
        assertThat(modifiedProperties1.getKeyset()).containsExactly(KEY, KEY2, KEY4);
        assertThat(modifiedProperties1.getString(KEY, DEFAULT_VALUE)).isEqualTo(VALUE);
        assertThat(modifiedProperties1.getString(KEY2, DEFAULT_VALUE)).isEqualTo(VALUE2);
        // Since value is null DEFAULT_VALUE should be returned
        assertThat(modifiedProperties1.getString(KEY4, DEFAULT_VALUE)).isEqualTo(DEFAULT_VALUE);

        // verify that other banned namespaces are unbanned now.
        DeviceConfig.setProperties(properties2);
        Properties result = DeviceConfig.getProperties(namespaceToBan2);
        assertThat(result.getKeyset()).containsExactly(KEY2, KEY4);
        assertThat(result.getString(KEY2, DEFAULT_VALUE)).isEqualTo(VALUE2);
        // Since value is null DEFAULT_VALUE should be returned
        assertThat(result.getString(KEY4, DEFAULT_VALUE)).isEqualTo(DEFAULT_VALUE);
    }

    // TODO(mpape): resolve b/142727848 and re-enable listener tests
//    @Test
//    public void onPropertiesChangedListener_setPropertyCallback() throws InterruptedException {
//        final CountDownLatch countDownLatch = new CountDownLatch(1);
//
//        DeviceConfig.OnPropertiesChangedListener changeListener = (properties) -> {
//            assertThat(properties.getNamespace()).isEqualTo(NAMESPACE);
//            assertThat(properties.getKeyset()).contains(KEY);
//            assertThat(properties.getString(KEY, "default_value")).isEqualTo(VALUE);
//            countDownLatch.countDown();
//        };
//
//        try {
//            DeviceConfig.addOnPropertiesChangedListener(NAMESPACE,
//                    ActivityThread.currentApplication().getMainExecutor(), changeListener);
//            DeviceConfig.setProperty(NAMESPACE, KEY, VALUE, false);
//            assertThat(countDownLatch.await(
//                    WAIT_FOR_PROPERTY_CHANGE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)).isTrue();
//        } catch (InterruptedException e) {
//            Assert.fail(e.getMessage());
//        } finally {
//            DeviceConfig.removeOnPropertiesChangedListener(changeListener);
//        }
//    }
//
//    @Test
//    public void onPropertiesChangedListener_setPropertiesCallback() throws InterruptedException {
//        final CountDownLatch countDownLatch = new CountDownLatch(1);
//        DeviceConfig.setProperty(NAMESPACE, KEY, VALUE, false);
//        DeviceConfig.setProperty(NAMESPACE, KEY2, VALUE2, false);
//
//        Map<String, String> keyValues = new HashMap<>(2);
//        keyValues.put(KEY, VALUE2);
//        keyValues.put(KEY3, VALUE3);
//        Properties setProperties = new Properties(NAMESPACE, keyValues);
//
//        DeviceConfig.OnPropertiesChangedListener changeListener = (properties) -> {
//            assertThat(properties.getNamespace()).isEqualTo(NAMESPACE);
//            assertThat(properties.getKeyset()).containsExactly(KEY, KEY2, KEY3);
//            // KEY updated from VALUE to VALUE2
//            assertThat(properties.getString(KEY, "default_value")).isEqualTo(VALUE2);
//            // KEY2 deleted (returns default_value)
//            assertThat(properties.getString(KEY2, "default_value")).isEqualTo("default_value");
//            //KEY3 added with VALUE3
//            assertThat(properties.getString(KEY3, "default_value")).isEqualTo(VALUE3);
//            countDownLatch.countDown();
//        };
//
//        try {
//            DeviceConfig.addOnPropertiesChangedListener(NAMESPACE,
//                    ActivityThread.currentApplication().getMainExecutor(), changeListener);
//            DeviceConfig.setProperties(setProperties);
//            assertThat(countDownLatch.await(
//                    WAIT_FOR_PROPERTY_CHANGE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)).isTrue();
//        } catch (InterruptedException e) {
//            Assert.fail(e.getMessage());
//        } finally {
//            DeviceConfig.removeOnPropertiesChangedListener(changeListener);
//        }
//    }

    @Test
    public void syncDisabling() throws Exception {
        Properties properties1 = new Properties.Builder(NAMESPACE)
                .setString(KEY, VALUE)
                .build();
        Properties properties2 = new Properties.Builder(NAMESPACE)
                .setString(KEY, VALUE2)
                .build();

        try {
            // Ensure the device starts in a known state.
            DeviceConfig.setSyncDisabled(Settings.Config.SYNC_DISABLED_MODE_NONE);

            // Assert starting state.
            assertThat(DeviceConfig.isSyncDisabled()).isFalse();
            assertThat(DeviceConfig.setProperties(properties1)).isTrue();
            assertThat(DeviceConfig.getProperties(NAMESPACE, KEY).getString(KEY, DEFAULT_VALUE))
                    .isEqualTo(VALUE);

            // Test disabled (persistent). Persistence is not actually tested, that would require
            // a host test.
            DeviceConfig.setSyncDisabled(Settings.Config.SYNC_DISABLED_MODE_PERSISTENT);
            assertThat(DeviceConfig.isSyncDisabled()).isTrue();
            assertThat(DeviceConfig.setProperties(properties2)).isFalse();
            assertThat(DeviceConfig.getProperties(NAMESPACE, KEY).getString(KEY, DEFAULT_VALUE))
                    .isEqualTo(VALUE);

            // Return to not disabled.
            DeviceConfig.setSyncDisabled(Settings.Config.SYNC_DISABLED_MODE_NONE);
            assertThat(DeviceConfig.isSyncDisabled()).isFalse();
            assertThat(DeviceConfig.setProperties(properties2)).isTrue();
            assertThat(DeviceConfig.getProperties(NAMESPACE, KEY).getString(KEY, DEFAULT_VALUE))
                    .isEqualTo(VALUE2);

            // Test disabled (persistent). Absence of persistence is not actually tested, that would
            // require a host test.
            DeviceConfig.setSyncDisabled(Settings.Config.SYNC_DISABLED_MODE_UNTIL_REBOOT);
            assertThat(DeviceConfig.isSyncDisabled()).isTrue();
            assertThat(DeviceConfig.setProperties(properties1)).isFalse();
            assertThat(DeviceConfig.getProperties(NAMESPACE, KEY).getString(KEY, DEFAULT_VALUE))
                    .isEqualTo(VALUE2);

            // Return to not disabled.
            DeviceConfig.setSyncDisabled(Settings.Config.SYNC_DISABLED_MODE_NONE);
            assertThat(DeviceConfig.isSyncDisabled()).isFalse();
            assertThat(DeviceConfig.setProperties(properties1)).isTrue();
            assertThat(DeviceConfig.getProperties(NAMESPACE, KEY).getString(KEY, DEFAULT_VALUE))
                    .isEqualTo(VALUE);
        } finally {
            // Try to return to the default sync disabled state in case of failure.
            DeviceConfig.setSyncDisabled(Settings.Config.SYNC_DISABLED_MODE_NONE);

            // NAMESPACE will be cleared by cleanUp()
        }
    }

    private static boolean deleteViaContentProvider(String namespace, String key) {
        ContentResolver resolver = InstrumentationRegistry.getContext().getContentResolver();
        String compositeName = namespace + "/" + key;
        Bundle result = resolver.call(
                DeviceConfig.CONTENT_URI, Settings.CALL_METHOD_DELETE_CONFIG, compositeName, null);
        assertThat(result).isNotNull();
        return compositeName.equals(result.getString(Settings.NameValueTable.VALUE));
    }

    @Test
    public void deleteProperty_nullNamespace() {
        try {
            DeviceConfig.deleteProperty(null, KEY);
            Assert.fail("Null namespace should have resulted in an NPE.");
        } catch (NullPointerException e) {
            // expected
        }
    }

    @Test
    public void deleteProperty_nullName() {
        try {
            DeviceConfig.deleteProperty(NAMESPACE, null);
            Assert.fail("Null name should have resulted in an NPE.");
        } catch (NullPointerException e) {
            // expected
        }
    }

    @Test
    public void deletePropertyString() {
        final String value = "new_value";
        final String default_value = "default";
        DeviceConfig.setProperty(NAMESPACE, KEY, value, false);
        DeviceConfig.deleteProperty(NAMESPACE, KEY);
        final String result = DeviceConfig.getString(NAMESPACE, KEY, default_value);
        assertThat(result).isEqualTo(default_value);
    }

    @Test
    public void deletePropertyBoolean() {
        final boolean value = true;
        final boolean default_value = false;
        DeviceConfig.setProperty(NAMESPACE, KEY, String.valueOf(value), false);
        DeviceConfig.deleteProperty(NAMESPACE, KEY);
        final boolean result = DeviceConfig.getBoolean(NAMESPACE, KEY, default_value);
        assertThat(result).isEqualTo(default_value);
    }

    @Test
    public void deletePropertyInt() {
        final int value = 123;
        final int default_value = 999;
        DeviceConfig.setProperty(NAMESPACE, KEY, String.valueOf(value), false);
        DeviceConfig.deleteProperty(NAMESPACE, KEY);
        final int result = DeviceConfig.getInt(NAMESPACE, KEY, default_value);
        assertThat(result).isEqualTo(default_value);
    }

    @Test
    public void deletePropertyLong() {
        final long value = 456789;
        final long default_value = 123456;
        DeviceConfig.setProperty(NAMESPACE, KEY, String.valueOf(value), false);
        DeviceConfig.deleteProperty(NAMESPACE, KEY);
        final long result = DeviceConfig.getLong(NAMESPACE, KEY, default_value);
        assertThat(result).isEqualTo(default_value);
    }

    @Test
    public void deletePropertyFloat() {
        final float value = 456.789f;
        final float default_value = 123.456f;
        DeviceConfig.setProperty(NAMESPACE, KEY, String.valueOf(value), false);
        DeviceConfig.deleteProperty(NAMESPACE, KEY);
        final float result = DeviceConfig.getFloat(NAMESPACE, KEY, default_value);
        assertThat(result).isEqualTo(default_value);
    }

    @Test
    public void deleteProperty_empty() {
        assertThat(DeviceConfig.deleteProperty(NAMESPACE, KEY)).isTrue();
        final String result = DeviceConfig.getString(NAMESPACE, KEY, null);
        assertThat(result).isNull();
    }
}
