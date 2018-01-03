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
 * limitations under the License
 */

package com.android.server.am;

import android.content.ContentResolver;
import android.provider.Settings;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.test.mock.MockContentResolver;

import com.android.internal.util.Preconditions;
import com.android.internal.util.test.FakeSettingsProvider;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.Map;

/**
 * Tests for {@link GlobalSettingsToPropertiesMapper}
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class GlobalSettingsToPropertiesMapperTest {
    private static final String[][] TEST_MAPPING = new String[][] {
        {Settings.Global.SQLITE_COMPATIBILITY_WAL_FLAGS, "TestProperty"}
    };

    private TestMapper mTestMapper;
    private MockContentResolver mMockContentResolver;

    @Before
    public void setup() {
        // Use FakeSettingsProvider to not affect global state
        mMockContentResolver = new MockContentResolver(InstrumentationRegistry.getContext());
        mMockContentResolver.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
        mTestMapper = new TestMapper(mMockContentResolver);
    }

    @Test
    public void testUpdatePropertiesFromGlobalSettings() {
        Settings.Global.putString(mMockContentResolver,
                Settings.Global.SQLITE_COMPATIBILITY_WAL_FLAGS, "testValue");

        mTestMapper.updatePropertiesFromGlobalSettings();
        String propValue = mTestMapper.systemPropertiesGet("TestProperty");
        Assert.assertEquals("testValue", propValue);

        Settings.Global.putString(mMockContentResolver,
                Settings.Global.SQLITE_COMPATIBILITY_WAL_FLAGS, "testValue2");
        mTestMapper.updatePropertyFromSetting(Settings.Global.SQLITE_COMPATIBILITY_WAL_FLAGS,
                "TestProperty");
        propValue = mTestMapper.systemPropertiesGet("TestProperty");
        Assert.assertEquals("testValue2", propValue);

        Settings.Global.putString(mMockContentResolver,
                Settings.Global.SQLITE_COMPATIBILITY_WAL_FLAGS, null);
        mTestMapper.updatePropertyFromSetting(Settings.Global.SQLITE_COMPATIBILITY_WAL_FLAGS,
                "TestProperty");
        propValue = mTestMapper.systemPropertiesGet("TestProperty");
        Assert.assertEquals("", propValue);
    }

    @Test
    public void testUpdatePropertiesFromGlobalSettings_PropertyAndSettingNotPresent() {
        // Test that empty property will not not be set if setting is not set
        mTestMapper.updatePropertiesFromGlobalSettings();
        String propValue = mTestMapper.systemPropertiesGet("TestProperty");
        Assert.assertNull("Property should not be set if setting is null", propValue);
    }

    private static class TestMapper extends GlobalSettingsToPropertiesMapper {
        private final Map<String, String> mProps = new HashMap<>();

        TestMapper(ContentResolver contentResolver) {
            super(contentResolver, TEST_MAPPING);
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
    }

}

