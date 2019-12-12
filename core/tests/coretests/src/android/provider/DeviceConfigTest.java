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

import java.util.HashMap;
import java.util.Map;

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
    private static final String VALUE = "value1";
    private static final String VALUE2 = "value2";
    private static final String VALUE3 = "value3";

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
        Map<String, String> keyValues = new HashMap<>();
        keyValues.put(KEY, VALUE);
        keyValues.put(KEY2, VALUE2);

        DeviceConfig.setProperties(new Properties(NAMESPACE, keyValues));
        Properties properties = DeviceConfig.getProperties(NAMESPACE);
        assertThat(properties.getKeyset()).containsExactly(KEY, KEY2);
        assertThat(properties.getString(KEY, DEFAULT_VALUE)).isEqualTo(VALUE);
        assertThat(properties.getString(KEY2, DEFAULT_VALUE)).isEqualTo(VALUE2);

        Map<String, String> newKeyValues = new HashMap<>();
        newKeyValues.put(KEY, VALUE2);
        newKeyValues.put(KEY3, VALUE3);

        DeviceConfig.setProperties(new Properties(NAMESPACE, newKeyValues));
        properties = DeviceConfig.getProperties(NAMESPACE);
        assertThat(properties.getKeyset()).containsExactly(KEY, KEY3);
        assertThat(properties.getString(KEY, DEFAULT_VALUE)).isEqualTo(VALUE2);
        assertThat(properties.getString(KEY3, DEFAULT_VALUE)).isEqualTo(VALUE3);

        assertThat(properties.getKeyset()).doesNotContain(KEY2);
        assertThat(properties.getString(KEY2, DEFAULT_VALUE)).isEqualTo(DEFAULT_VALUE);
    }

    @Test
    public void setProperties_multipleNamespaces() throws DeviceConfig.BadConfigException {
        Map<String, String> keyValues = new HashMap<>();
        keyValues.put(KEY, VALUE);
        keyValues.put(KEY2, VALUE2);

        Map<String, String> keyValues2 = new HashMap<>();
        keyValues2.put(KEY2, VALUE);
        keyValues2.put(KEY3, VALUE2);

        final String namespace2 = "namespace2";
        DeviceConfig.setProperties(new Properties(NAMESPACE, keyValues));
        DeviceConfig.setProperties(new Properties(namespace2, keyValues2));

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

    private static boolean deleteViaContentProvider(String namespace, String key) {
        ContentResolver resolver = InstrumentationRegistry.getContext().getContentResolver();
        String compositeName = namespace + "/" + key;
        Bundle result = resolver.call(
                DeviceConfig.CONTENT_URI, Settings.CALL_METHOD_DELETE_CONFIG, compositeName, null);
        assertThat(result).isNotNull();
        return compositeName.equals(result.getString(Settings.NameValueTable.VALUE));
    }

}
