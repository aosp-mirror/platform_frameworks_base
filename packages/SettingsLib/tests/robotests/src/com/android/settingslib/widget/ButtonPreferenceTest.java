/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.widget;

import static com.google.common.truth.Truth.assertThat;

import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.preference.PreferenceViewHolder;
import androidx.preference.R;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowDrawable;

@RunWith(RobolectricTestRunner.class)
public class ButtonPreferenceTest {

    private final Context mContext = ApplicationProvider.getApplicationContext();
    private ButtonPreference mPreference;
    private PreferenceViewHolder mHolder;

    private boolean mClickListenerCalled;
    private final View.OnClickListener mClickListener = v -> mClickListenerCalled = true;

    @Before
    public void setUp() {
        mClickListenerCalled = false;
        mPreference = new ButtonPreference(mContext);
        setUpViewHolder();
    }

    @Test
    public void onBindViewHolder_whenTitleSet_shouldSetButtonText() {
        final String testTitle = "Test title";
        mPreference.setTitle(testTitle);

        mPreference.onBindViewHolder(mHolder);

        final Button button = mPreference.getButton();
        assertThat(button.getText().toString()).isEqualTo(testTitle);
    }

    @Test
    public void onBindViewHolder_whenIconSet_shouldSetIcon() {
        mPreference.setIcon(R.drawable.settingslib_ic_cross);

        mPreference.onBindViewHolder(mHolder);

        final Button button = mPreference.getButton();
        final Drawable icon = button.getCompoundDrawablesRelative()[0];
        final ShadowDrawable shadowDrawable = shadowOf(icon);
        assertThat(shadowDrawable.getCreatedFromResId()).isEqualTo(R.drawable.settingslib_ic_cross);
    }

    @Test
    public void onBindViewHolder_setEnable_shouldSetButtonEnabled() {
        mPreference.setEnabled(true);

        mPreference.onBindViewHolder(mHolder);

        final Button button = mPreference.getButton();
        assertThat(button.isEnabled()).isTrue();
    }

    @Test
    public void onBindViewHolder_setDisable_shouldSetButtonDisabled() {
        mPreference.setEnabled(false);

        mPreference.onBindViewHolder(mHolder);

        final Button button = mPreference.getButton();
        assertThat(button.isEnabled()).isFalse();
    }

    @Test
    public void onBindViewHolder_default_shouldReturnButtonGravityStart() {
        mPreference.onBindViewHolder(mHolder);

        final Button button = mPreference.getButton();
        final LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) button.getLayoutParams();
        assertThat(lp.gravity).isEqualTo(Gravity.START);
    }

    @Test
    public void onBindViewHolder_setGravityStart_shouldReturnButtonGravityStart() {
        mPreference.setGravity(Gravity.START);

        mPreference.onBindViewHolder(mHolder);

        final Button button = mPreference.getButton();
        final LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) button.getLayoutParams();
        assertThat(lp.gravity).isEqualTo(Gravity.START);
    }

    @Test
    public void onBindViewHolder_setGravityCenter_shouldReturnButtonGravityCenterHorizontal() {
        mPreference.setGravity(Gravity.CENTER_HORIZONTAL);

        mPreference.onBindViewHolder(mHolder);

        final Button button = mPreference.getButton();
        final LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) button.getLayoutParams();
        assertThat(lp.gravity).isEqualTo(Gravity.CENTER_HORIZONTAL);

        mPreference.setGravity(Gravity.CENTER_VERTICAL);
        mPreference.onBindViewHolder(mHolder);
        assertThat(lp.gravity).isEqualTo(Gravity.CENTER_HORIZONTAL);

        mPreference.setGravity(Gravity.CENTER);
        mPreference.onBindViewHolder(mHolder);
        assertThat(lp.gravity).isEqualTo(Gravity.CENTER_HORIZONTAL);
    }

    @Test
    public void onBindViewHolder_setUnsupportedGravity_shouldReturnButtonGravityStart() {
        mPreference.setGravity(Gravity.END);

        mPreference.onBindViewHolder(mHolder);

        final Button button = mPreference.getButton();
        final LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) button.getLayoutParams();
        assertThat(lp.gravity).isEqualTo(Gravity.START);
    }

    @Test
    public void setButtonOnClickListener_setsClickListener() {
        mPreference.setOnClickListener(mClickListener);

        mPreference.onBindViewHolder(mHolder);
        final Button button = mPreference.getButton();
        button.callOnClick();

        assertThat(mClickListenerCalled).isTrue();
    }

    private void setUpViewHolder() {
        final View rootView =
                View.inflate(mContext, mPreference.getLayoutResource(), null /* parent */);
        mHolder = PreferenceViewHolder.createInstanceForTests(rootView);
    }
}
