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

package com.android.settingslib.widget;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class MainSwitchBarTest {

    private Context mContext;
    private MainSwitchBar mBar;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.application;
        mBar = new MainSwitchBar(mContext);
    }

    @Test
    public void setChecked_true_shouldChecked() {
        mBar.setChecked(true);

        assertThat(mBar.isChecked()).isTrue();
    }

    @Test
    public void setTitle_shouldUpdateTitle() {
        final String title = "title";

        mBar.setTitle(title);
        final TextView textView = ((TextView) mBar.findViewById(R.id.switch_text));

        assertThat(textView.getText()).isEqualTo(title);
    }

    @Test
    public void setTitle_switchShouldNotHasContentDescription() {
        final String title = "title";

        mBar.setTitle(title);

        final Switch switchObj = mBar.getSwitch();
        assertThat(TextUtils.isEmpty(switchObj.getContentDescription())).isTrue();
    }

    @Test
    public void getSwitch_shouldNotNull() {
        final Switch switchObj = mBar.getSwitch();

        assertThat(switchObj).isNotNull();
    }

    @Test
    public void getSwitch_shouldNotFocusableAndClickable() {
        final Switch switchObj = mBar.getSwitch();

        assertThat(switchObj.isFocusable()).isFalse();
        assertThat(switchObj.isClickable()).isFalse();
    }

    @Test
    public void show_shouldVisible() {
        mBar.show();

        assertThat(mBar.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void hide_shouldNotVisible() {
        mBar.hide();

        assertThat(mBar.getVisibility()).isEqualTo(View.GONE);
    }
}
