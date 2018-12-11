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

package com.android.server.am;

import android.content.ContentResolver;
import android.provider.Settings;
import android.test.mock.MockContentResolver;
import android.text.TextUtils;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.Preconditions;
import com.android.internal.util.test.FakeSettingsProvider;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Tests for {@link SettingsToPropertiesMapper}
 *
 *  Build/Install/Run:
 *  atest FrameworksServicesTests:SettingsToPropertiesMapperTest
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class SettingsToPropertiesMapperTest {
    private static final String NAME_VALID_CHARACTERS_REGEX = "^[\\w\\-@:]*$";
    private static final String[] TEST_MAPPING = new String[] {
            Settings.Global.SQLITE_COMPATIBILITY_WAL_FLAGS
    };

    private TestMapper mTestMapper;
    private MockContentResolver mMockContentResolver;

    @Before
    public void setupForEach() {
        // Use FakeSettingsProvider to not affect global state
        mMockContentResolver = new MockContentResolver(InstrumentationRegistry.getContext());
        mMockContentResolver.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
        mTestMapper = new TestMapper(mMockContentResolver);
    }

    @Test
    public void validateRegisteredGlobalSettings() {
        HashSet<String> hashSet = new HashSet<>();
        for (String globalSetting : SettingsToPropertiesMapper.sGlobalSettings) {
            if (hashSet.contains(globalSetting)) {
                Assert.fail("globalSetting "
                        + globalSetting
                        + " is registered more than once in "
                        + "SettingsToPropertiesMapper.sGlobalSettings.");
            }
            hashSet.add(globalSetting);
            if (TextUtils.isEmpty(globalSetting)) {
                Assert.fail("empty globalSetting registered.");
            }
            if (!globalSetting.matches(NAME_VALID_CHARACTERS_REGEX)) {
                Assert.fail(globalSetting + " contains invalid characters. "
                        + "Only alphanumeric characters, '-', '@', ':' and '_' are valid.");
            }
        }
    }

    @Test
    public void validateRegisteredDeviceConfigScopes() {
        HashSet<String> hashSet = new HashSet<>();
        for (String deviceConfigScope : SettingsToPropertiesMapper.sDeviceConfigScopes) {
            if (hashSet.contains(deviceConfigScope)) {
                Assert.fail("deviceConfigScope "
                        + deviceConfigScope
                        + " is registered more than once in "
                        + "SettingsToPropertiesMapper.sDeviceConfigScopes.");
            }
            hashSet.add(deviceConfigScope);
            if (TextUtils.isEmpty(deviceConfigScope)) {
                Assert.fail("empty deviceConfigScope registered.");
            }
            if (!deviceConfigScope.matches(NAME_VALID_CHARACTERS_REGEX)) {
                Assert.fail(deviceConfigScope + " contains invalid characters. "
                        + "Only alphanumeric characters, '-', '@', ':' and '_' are valid.");
            }
        }
    }

    @Test
    public void testUpdatePropertiesFromSettings() {
        Settings.Global.putString(mMockContentResolver,
                Settings.Global.SQLITE_COMPATIBILITY_WAL_FLAGS, "testValue");

        String systemPropertyName = "persist.device_config.global_settings."
                + "sqlite_compatibility_wal_flags";

        mTestMapper.updatePropertiesFromSettings();
        String propValue = mTestMapper.systemPropertiesGet(systemPropertyName);
        Assert.assertEquals("testValue", propValue);

        Settings.Global.putString(mMockContentResolver,
                Settings.Global.SQLITE_COMPATIBILITY_WAL_FLAGS, "testValue2");
        mTestMapper.updatePropertyFromSetting(
                Settings.Global.SQLITE_COMPATIBILITY_WAL_FLAGS,
                systemPropertyName);
        propValue = mTestMapper.systemPropertiesGet(systemPropertyName);
        Assert.assertEquals("testValue2", propValue);

        Settings.Global.putString(mMockContentResolver,
                Settings.Global.SQLITE_COMPATIBILITY_WAL_FLAGS, null);
        mTestMapper.updatePropertyFromSetting(
                Settings.Global.SQLITE_COMPATIBILITY_WAL_FLAGS,
                systemPropertyName);
        propValue = mTestMapper.systemPropertiesGet(systemPropertyName);
        Assert.assertEquals("", propValue);
    }

    @Test
    public void testMakePropertyName() {
        try {
            Assert.assertEquals("persist.device_config.test_category.test_flag",
                    SettingsToPropertiesMapper.makePropertyName("test_category", "test_flag"));
        } catch (Exception e) {
            Assert.fail("Unexpected exception: " + e.getMessage());
        }

        try {
            Assert.assertEquals(null,
                    SettingsToPropertiesMapper.makePropertyName("test_category!!!", "test_flag"));
        } catch (Exception e) {
            Assert.fail("Unexpected exception: " + e.getMessage());
        }

        try {
            Assert.assertEquals(null,
                    SettingsToPropertiesMapper.makePropertyName("test_category", ".test_flag"));
        } catch (Exception e) {
            Assert.fail("Unexpected exception: " + e.getMessage());
        }
    }

    @Test
    public void testUpdatePropertiesFromSettings_PropertyAndSettingNotPresent() {
        // Test that empty property will not not be set if setting is not set
        mTestMapper.updatePropertiesFromSettings();
        String propValue = mTestMapper.systemPropertiesGet("TestProperty");
        Assert.assertNull("Property should not be set if setting is null", propValue);
    }

    @Test
    public void testIsNativeFlagsResetPerformed() {
        mTestMapper.systemPropertiesSet("device_config.reset_performed", "true");
        Assert.assertTrue(mTestMapper.isNativeFlagsResetPerformed());

        mTestMapper.systemPropertiesSet("device_config.reset_performed", "false");
        Assert.assertFalse(mTestMapper.isNativeFlagsResetPerformed());

        mTestMapper.systemPropertiesSet("device_config.reset_performed", "");
        Assert.assertFalse(mTestMapper.isNativeFlagsResetPerformed());
    }

    @Test
    public void testGetResetNativeCategories() {
        mTestMapper.systemPropertiesSet("device_config.reset_performed", "");
        Assert.assertEquals(mTestMapper.getResetNativeCategories().length, 0);

        mTestMapper.systemPropertiesSet("device_config.reset_performed", "true");
        mTestMapper.setFileContent("");
        Assert.assertEquals(mTestMapper.getResetNativeCategories().length, 0);

        mTestMapper.systemPropertiesSet("device_config.reset_performed", "true");
        mTestMapper.setFileContent("persist.device_config.category1.flag;"
                + "persist.device_config.category2.flag;persist.device_config.category3.flag;"
                + "persist.device_config.category3.flag2");
        List<String> categories = Arrays.asList(mTestMapper.getResetNativeCategories());
        Assert.assertEquals(3, categories.size());
        Assert.assertTrue(categories.contains("category1"));
        Assert.assertTrue(categories.contains("category2"));
        Assert.assertTrue(categories.contains("category3"));
    }

    private static class TestMapper extends SettingsToPropertiesMapper {
        private final Map<String, String> mProps = new HashMap<>();

        private String mFileContent = "";

        TestMapper(ContentResolver contentResolver) {
            super(contentResolver, TEST_MAPPING, new String[] {});
        }

        @Override
        protected String systemPropertiesGet(String key) {
            Preconditions.checkNotNull(key);
            return mProps.get(key);
        }

        @Override
        protected void systemPropertiesSet(String key, String value) {
            Preconditions.checkNotNull(value);
            Preconditions.checkNotNull(key);
            mProps.put(key, value);
        }

        protected void setFileContent(String fileContent) {
            mFileContent = fileContent;
        }

        @Override
        protected String getResetFlagsFileContent() {
            return mFileContent;
        }
    }
}
