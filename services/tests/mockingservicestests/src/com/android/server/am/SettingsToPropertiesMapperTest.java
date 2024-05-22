/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyString;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import android.content.ContentResolver;
import android.os.SystemProperties;
import android.provider.Settings;
import android.provider.DeviceConfig.Properties;
import android.text.TextUtils;

import com.android.dx.mockito.inline.extended.ExtendedMockito;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Test SettingsToPropertiesMapper.
 */
public class SettingsToPropertiesMapperTest {
    private static final String NAME_VALID_CHARACTERS_REGEX = "^[\\w\\-@:]*$";
    private static final String[] TEST_MAPPING = new String[] {
            Settings.Global.SQLITE_COMPATIBILITY_WAL_FLAGS
    };

    private MockitoSession mSession;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ContentResolver mMockContentResolver;

    private SettingsToPropertiesMapper mTestMapper;

    private HashMap<String, String> mSystemSettingsMap;
    private HashMap<String, String> mGlobalSettingsMap;
    private HashMap<String, String> mConfigSettingsMap;

    @Before
    public void setUp() throws Exception {
        mSession =
                ExtendedMockito.mockitoSession().initMocks(
                        this)
                        .strictness(Strictness.LENIENT)
                        .spyStatic(SystemProperties.class)
                        .spyStatic(Settings.Global.class)
                        .spyStatic(SettingsToPropertiesMapper.class)
                        .spyStatic(Settings.Config.class)
                        .startMocking();
        mSystemSettingsMap = new HashMap<>();
        mGlobalSettingsMap = new HashMap<>();
        mConfigSettingsMap = new HashMap<>();

        // Mock SystemProperties setter and various getters
        doAnswer((Answer<Void>) invocationOnMock -> {
                    String key = invocationOnMock.getArgument(0);
                    String value = invocationOnMock.getArgument(1);

                    mSystemSettingsMap.put(key, value);
                    return null;
                }
        ).when(() -> SystemProperties.set(anyString(), anyString()));

        doAnswer((Answer<String>) invocationOnMock -> {
                    String key = invocationOnMock.getArgument(0);

                    String storedValue = mSystemSettingsMap.get(key);
                    return storedValue == null ? "" : storedValue;
                }
        ).when(() -> SystemProperties.get(anyString()));

        // Mock Settings.Global method
        doAnswer((Answer<String>) invocationOnMock -> {
                    String key = invocationOnMock.getArgument(1);

                    return mGlobalSettingsMap.get(key);
                }
        ).when(() -> Settings.Global.getString(any(), anyString()));

        // Mock Settings.Config getstrings method
        doAnswer((Answer<Map<String, String>>) invocationOnMock -> {
                    String namespace = invocationOnMock.getArgument(0);
                    List<String> flags = invocationOnMock.getArgument(1);
                    HashMap<String, String> values = new HashMap<>();
                    for (String flag : flags) {
                      String value = mConfigSettingsMap.get(namespace + "/" + flag);
                      if (value != null) {
                        values.put(flag, value);
                      }
                    }
                    return values;
                }
        ).when(() -> Settings.Config.getStrings(anyString(), any()));

        mTestMapper = new SettingsToPropertiesMapper(
            mMockContentResolver, TEST_MAPPING, new String[] {}, new String[] {});
    }

    @After
    public void tearDown() throws Exception {
        mSession.finishMocking();
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
        mGlobalSettingsMap.put(Settings.Global.SQLITE_COMPATIBILITY_WAL_FLAGS, "testValue");

        String systemPropertyName = "persist.device_config.global_settings."
                + "sqlite_compatibility_wal_flags";

        mTestMapper.updatePropertiesFromSettings();
        String propValue = mSystemSettingsMap.get(systemPropertyName);
        Assert.assertEquals("testValue", propValue);

        mGlobalSettingsMap.put(Settings.Global.SQLITE_COMPATIBILITY_WAL_FLAGS, "testValue2");
        mTestMapper.updatePropertyFromSetting(
                Settings.Global.SQLITE_COMPATIBILITY_WAL_FLAGS,
                systemPropertyName);
        propValue = mSystemSettingsMap.get(systemPropertyName);
        Assert.assertEquals("testValue2", propValue);

        mGlobalSettingsMap.put(Settings.Global.SQLITE_COMPATIBILITY_WAL_FLAGS, null);
        mTestMapper.updatePropertyFromSetting(
                Settings.Global.SQLITE_COMPATIBILITY_WAL_FLAGS,
                systemPropertyName);
        propValue = mSystemSettingsMap.get(systemPropertyName);
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
        // Test that empty property will not be set if setting is not set
        mTestMapper.updatePropertiesFromSettings();
        String propValue = mSystemSettingsMap.get("TestProperty");
        Assert.assertNull("Property should not be set if setting is null", propValue);
    }

    @Test
    public void testIsNativeFlagsResetPerformed() {
        mSystemSettingsMap.put("device_config.reset_performed", "true");
        Assert.assertTrue(mTestMapper.isNativeFlagsResetPerformed());

        mSystemSettingsMap.put("device_config.reset_performed", "false");
        Assert.assertFalse(mTestMapper.isNativeFlagsResetPerformed());

        mSystemSettingsMap.put("device_config.reset_performed", "");
        Assert.assertFalse(mTestMapper.isNativeFlagsResetPerformed());
    }

    @Test
    public void testGetResetNativeCategories() {
        doReturn("persist.device_config.category1.flag;"
                + "persist.device_config.category2.flag;persist.device_config.category3.flag;"
                + "persist.device_config.category3.flag2")
            .when(() -> SettingsToPropertiesMapper.getResetFlagsFileContent());

        mSystemSettingsMap.put("device_config.reset_performed", "");
        Assert.assertEquals(mTestMapper.getResetNativeCategories().length, 0);

        mSystemSettingsMap.put("device_config.reset_performed", "true");
        List<String> categories = Arrays.asList(mTestMapper.getResetNativeCategories());
        Assert.assertEquals(3, categories.size());
        Assert.assertTrue(categories.contains("category1"));
        Assert.assertTrue(categories.contains("category2"));
        Assert.assertTrue(categories.contains("category3"));
    }

  @Test
  public void testGetStagedFlagsWithValueChange() {
    // mock up what is in the setting already
    mConfigSettingsMap.put("namespace_1/flag_1", "true");
    mConfigSettingsMap.put("namespace_1/flag_2", "true");

    // mock up input
    String namespace = "staged";
    Map<String, String> keyValueMap = new HashMap<>();
    // case 1: existing prop, stage the same value
    keyValueMap.put("namespace_1*flag_1", "true");
    // case 2: existing prop, stage a different value
    keyValueMap.put("namespace_1*flag_2", "false");
    // case 3: new prop
    keyValueMap.put("namespace_2*flag_1", "true");
    Properties props = new Properties(namespace, keyValueMap);

    HashMap<String, HashMap<String, String>> toStageProps =
        SettingsToPropertiesMapper.getStagedFlagsWithValueChange(props);

    HashMap<String, String> namespace_1_to_stage = toStageProps.get("namespace_1");
    HashMap<String, String> namespace_2_to_stage = toStageProps.get("namespace_2");
    Assert.assertTrue(namespace_1_to_stage != null);
    Assert.assertTrue(namespace_2_to_stage != null);

    String namespace_1_flag_1 = namespace_1_to_stage.get("flag_1");
    String namespace_1_flag_2 = namespace_1_to_stage.get("flag_2");
    String namespace_2_flag_1 = namespace_2_to_stage.get("flag_1");
    Assert.assertTrue(namespace_1_flag_1 == null);
    Assert.assertTrue(namespace_1_flag_2 != null);
    Assert.assertTrue(namespace_2_flag_1 != null);
    Assert.assertTrue(namespace_1_flag_2.equals("false"));
    Assert.assertTrue(namespace_2_flag_1.equals("true"));
  }
}
