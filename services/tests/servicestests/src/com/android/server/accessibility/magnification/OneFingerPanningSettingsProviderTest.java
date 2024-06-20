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

package com.android.server.accessibility.magnification;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import android.provider.Settings;
import android.testing.TestableContext;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.accessibility.magnification.OneFingerPanningSettingsProvider.State;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class OneFingerPanningSettingsProviderTest {

    @Rule
    public final TestableContext mContext = new TestableContext(getInstrumentation().getContext());

    private boolean mDefaultValue;
    private boolean mOriginalIsOneFingerPanningEnabled;

    private OneFingerPanningSettingsProvider mProvider;

    @Before
    public void setup() {
        mDefaultValue = OneFingerPanningSettingsProvider.isOneFingerPanningEnabledDefault(mContext);
        mOriginalIsOneFingerPanningEnabled = isSecureSettingsEnabled();
    }

    @After
    public void tearDown() {
        enableSecureSettings(mOriginalIsOneFingerPanningEnabled);
        if (mProvider != null) {
            mProvider.unregister();
        }
    }

    @Test
    public void isOneFingerPanningEnabled_flagDisabled_matchesDefault() {
        mProvider = new OneFingerPanningSettingsProvider(mContext, /* featureFlagEnabled */ false);

        assertThat(mProvider.isOneFingerPanningEnabled()).isEqualTo(mDefaultValue);
    }

    @Test
    public void isOneFingerPanningEnabled_flagEnabledSettingEnabled_true() {
        enableSecureSettings(true);
        mProvider = new OneFingerPanningSettingsProvider(mContext, /* featureFlagEnabled */ true);

        assertTrue(mProvider.isOneFingerPanningEnabled());
    }

    @Test
    public void isOneFingerPanningEnabled_flagEnabledSettingDisabled_false() {
        enableSecureSettings(false);
        mProvider = new OneFingerPanningSettingsProvider(mContext, /* featureFlagEnabled */ true);

        assertFalse(mProvider.isOneFingerPanningEnabled());
    }

    @Test
    public void isOneFingerPanningEnabled_flagEnabledSettingsFalse_false() {
        mProvider = new OneFingerPanningSettingsProvider(mContext, /* featureFlagEnabled */ true);

        // Simulate observer triggered.
        enableSecureSettings(false);
        mProvider.mObserver.onChange(/* selfChange= */ false);

        assertFalse(mProvider.isOneFingerPanningEnabled());
    }

    @Test
    public void isOneFingerPanningEnabled_flagEnabledSettingsTrue_true() {
        mProvider = new OneFingerPanningSettingsProvider(mContext, /* featureFlagEnabled */ true);

        // Simulate observer triggered.
        enableSecureSettings(true);
        mProvider.mObserver.onChange(/* selfChange= */ false);

        assertTrue(mProvider.isOneFingerPanningEnabled());
    }

    @Test
    public void isOneFingerPanningEnabled_flagDisabledSettingsChanges_valueUnchanged() {
        mProvider = new OneFingerPanningSettingsProvider(mContext, /* featureFlagEnabled */ false);
        var previousValue = mProvider.isOneFingerPanningEnabled();

        enableSecureSettings(!previousValue);

        assertThat(mProvider.isOneFingerPanningEnabled()).isEqualTo(previousValue);
        assertThat(mProvider.isOneFingerPanningEnabled()).isEqualTo(mDefaultValue);
    }

    @Test
    public void unregister_featureEnabled_contentResolverNull() {
        var provider = new OneFingerPanningSettingsProvider(
                mContext, /* featureFlagEnabled */ true);

        provider.unregister();

        assertThat(provider.mContentResolver).isNull();
    }

    @Test
    public void unregister_featureDisabled_noError() {
        var provider = new OneFingerPanningSettingsProvider(
                mContext, /* featureFlagEnabled */ false);

        provider.unregister();
    }

    private void enableSecureSettings(boolean enable) {
        Settings.Secure.putIntForUser(
                mContext.getContentResolver(),
                OneFingerPanningSettingsProvider.KEY,
                enable ? State.ON : State.OFF,
                mContext.getUserId());
    }

    private boolean isSecureSettingsEnabled() {
        return State.ON == Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                OneFingerPanningSettingsProvider.KEY,
                mDefaultValue ? State.ON : State.OFF,
                mContext.getUserId());
    }
}
