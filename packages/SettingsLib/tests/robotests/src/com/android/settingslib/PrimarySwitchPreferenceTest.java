/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.settingslib;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.view.LayoutInflater;
import android.widget.CompoundButton;
import android.widget.LinearLayout;

import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.PreferenceViewHolder;

import com.android.settingslib.RestrictedLockUtils.EnforcedAdmin;
import com.android.settingslib.testutils.shadow.ShadowInteractionJankMonitor;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = {ShadowInteractionJankMonitor.class})
public class PrimarySwitchPreferenceTest {

    private Context mContext;
    private PrimarySwitchPreference mPreference;
    private PreferenceViewHolder mHolder;
    private LinearLayout mWidgetView;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.application;
        mPreference = new PrimarySwitchPreference(mContext);
        LayoutInflater inflater = LayoutInflater.from(mContext);
        mHolder = PreferenceViewHolder.createInstanceForTests(inflater.inflate(
                com.android.settingslib.widget.preference.twotarget.R.layout.preference_two_target,
                null));
        mWidgetView = mHolder.itemView.findViewById(android.R.id.widget_frame);
        inflater.inflate(androidx.preference.R.layout.preference_widget_switch_compat, mWidgetView,
                true);
    }

    @Test
    public void setChecked_shouldUpdateButtonCheckedState() {
        final CompoundButton toggle =
                (CompoundButton) mHolder.findViewById(androidx.preference.R.id.switchWidget);
        mPreference.onBindViewHolder(mHolder);

        mPreference.setChecked(true);
        assertThat(toggle.isChecked()).isTrue();

        mPreference.setChecked(false);
        assertThat(toggle.isChecked()).isFalse();
    }

    @Test
    public void setSwitchEnabled_shouldUpdateButtonEnabledState() {
        final CompoundButton toggle =
                (CompoundButton) mHolder.findViewById(androidx.preference.R.id.switchWidget);
        mPreference.onBindViewHolder(mHolder);

        mPreference.setSwitchEnabled(true);
        assertThat(toggle.isEnabled()).isTrue();

        mPreference.setSwitchEnabled(false);
        assertThat(toggle.isEnabled()).isFalse();
    }

    @Test
    public void setSwitchEnabled_shouldUpdateButtonEnabledState_beforeViewBound() {
        final CompoundButton toggle =
                (CompoundButton) mHolder.findViewById(androidx.preference.R.id.switchWidget);

        mPreference.setSwitchEnabled(false);
        mPreference.onBindViewHolder(mHolder);
        assertThat(toggle.isEnabled()).isFalse();
    }

    @Test
    public void clickWidgetView_shouldToggleButton() {
        assertThat(mWidgetView).isNotNull();

        final CompoundButton toggle =
                (CompoundButton) mHolder.findViewById(androidx.preference.R.id.switchWidget);
        mPreference.onBindViewHolder(mHolder);

        toggle.performClick();
        assertThat(toggle.isChecked()).isTrue();

        toggle.performClick();
        assertThat(toggle.isChecked()).isFalse();
    }

    @Test
    public void clickWidgetView_shouldNotToggleButtonIfDisabled() {
        assertThat(mWidgetView).isNotNull();

        final CompoundButton toggle =
                (CompoundButton) mHolder.findViewById(androidx.preference.R.id.switchWidget);
        mPreference.onBindViewHolder(mHolder);
        toggle.setEnabled(false);

        mWidgetView.performClick();
        assertThat(toggle.isChecked()).isFalse();
    }

    @Test
    public void clickWidgetView_shouldNotifyPreferenceChanged() {

        final CompoundButton toggle =
                (CompoundButton) mHolder.findViewById(androidx.preference.R.id.switchWidget);

        final OnPreferenceChangeListener listener = mock(OnPreferenceChangeListener.class);
        mPreference.setOnPreferenceChangeListener(listener);
        mPreference.onBindViewHolder(mHolder);

        mPreference.setChecked(false);
        toggle.performClick();
        verify(listener).onPreferenceChange(mPreference, true);

        mPreference.setChecked(true);
        toggle.performClick();
        verify(listener).onPreferenceChange(mPreference, false);
    }

    @Test
    public void setDisabledByAdmin_hasEnforcedAdmin_shouldDisableButton() {
        final CompoundButton toggle =
                (CompoundButton) mHolder.findViewById(androidx.preference.R.id.switchWidget);
        toggle.setEnabled(true);
        mPreference.onBindViewHolder(mHolder);

        mPreference.setDisabledByAdmin(mock(EnforcedAdmin.class));
        assertThat(toggle.isEnabled()).isFalse();
    }

    @Test
    public void setDisabledByAdmin_noEnforcedAdmin_shouldEnableButton() {
        final CompoundButton toggle =
                (CompoundButton) mHolder.findViewById(androidx.preference.R.id.switchWidget);
        toggle.setEnabled(false);
        mPreference.onBindViewHolder(mHolder);

        mPreference.setDisabledByAdmin(null);
        assertThat(toggle.isEnabled()).isTrue();
    }

    @Test
    public void onBindViewHolder_toggleButtonShouldHaveContentDescription() {
        final CompoundButton toggle =
                (CompoundButton) mHolder.findViewById(androidx.preference.R.id.switchWidget);
        final String label = "TestButton";
        mPreference.setTitle(label);

        mPreference.onBindViewHolder(mHolder);

        assertThat(toggle.getContentDescription()).isEqualTo(label);
    }
}
