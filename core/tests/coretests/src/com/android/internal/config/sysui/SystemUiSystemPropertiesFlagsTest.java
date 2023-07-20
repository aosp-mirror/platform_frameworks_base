/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.internal.config.sysui;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.filters.SmallTest;

import com.android.internal.config.sysui.SystemUiSystemPropertiesFlags.Flag;
import com.android.internal.config.sysui.SystemUiSystemPropertiesFlags.FlagResolver;

import junit.framework.TestCase;

import java.util.HashMap;
import java.util.Map;

@SmallTest
public class SystemUiSystemPropertiesFlagsTest extends TestCase {

    public class TestableDebugResolver extends SystemUiSystemPropertiesFlags.DebugResolver {
        final Map<String, Boolean> mTestData = new HashMap<>();

        @Override
        public boolean getBoolean(String key, boolean defaultValue) {
            Boolean testValue = mTestData.get(key);
            return testValue == null ? defaultValue : testValue;
        }

        public void set(Flag flag, Boolean value) {
            mTestData.put(flag.mSysPropKey, value);
        }
    }

    private FlagResolver mProdResolver;
    private TestableDebugResolver mDebugResolver;

    private Flag mReleasedFlag;
    private Flag mTeamfoodFlag;
    private Flag mDevFlag;

    public void setUp() {
        mProdResolver = new SystemUiSystemPropertiesFlags.ProdResolver();
        mDebugResolver = new TestableDebugResolver();
        mReleasedFlag = SystemUiSystemPropertiesFlags.releasedFlag("mReleasedFlag");
        mTeamfoodFlag = SystemUiSystemPropertiesFlags.teamfoodFlag("mTeamfoodFlag");
        mDevFlag = SystemUiSystemPropertiesFlags.devFlag("mDevFlag");
    }

    public void tearDown() {
        SystemUiSystemPropertiesFlags.TEST_RESOLVER = null;
    }

    public void testProdResolverReturnsDefault() {
        assertThat(mProdResolver.isEnabled(mReleasedFlag)).isTrue();
        assertThat(mProdResolver.isEnabled(mTeamfoodFlag)).isFalse();
        assertThat(mProdResolver.isEnabled(mDevFlag)).isFalse();
    }

    public void testDebugResolverAndReleasedFlag() {
        assertThat(mDebugResolver.isEnabled(mReleasedFlag)).isTrue();

        mDebugResolver.set(mReleasedFlag, false);
        assertThat(mDebugResolver.isEnabled(mReleasedFlag)).isFalse();

        mDebugResolver.set(mReleasedFlag, true);
        assertThat(mDebugResolver.isEnabled(mReleasedFlag)).isTrue();
    }

    private void assertTeamfoodFlag(Boolean flagValue, Boolean teamfood, boolean expected) {
        mDebugResolver.set(mTeamfoodFlag, flagValue);
        mDebugResolver.set(SystemUiSystemPropertiesFlags.TEAMFOOD, teamfood);
        assertThat(mDebugResolver.isEnabled(mTeamfoodFlag)).isEqualTo(expected);
    }

    public void testDebugResolverAndTeamfoodFlag() {
        assertTeamfoodFlag(null, null, false);
        assertTeamfoodFlag(true, null, true);
        assertTeamfoodFlag(false, null, false);
        assertTeamfoodFlag(null, true, true);
        assertTeamfoodFlag(true, true, true);
        assertTeamfoodFlag(false, true, false);
        assertTeamfoodFlag(null, false, false);
        assertTeamfoodFlag(true, false, true);
        assertTeamfoodFlag(false, false, false);
    }

    public void testDebugResolverAndDevFlag() {
        assertThat(mDebugResolver.isEnabled(mDevFlag)).isFalse();

        mDebugResolver.set(mDevFlag, true);
        assertThat(mDebugResolver.isEnabled(mDevFlag)).isTrue();

        mDebugResolver.set(mDevFlag, false);
        assertThat(mDebugResolver.isEnabled(mDevFlag)).isFalse();
    }
}
