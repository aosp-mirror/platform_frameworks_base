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

import static android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.android.settingslib.widget.mainswitch.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class MainSwitchBarTest {

    private final Context mContext = ApplicationProvider.getApplicationContext();
    private final MainSwitchBar mBar = new MainSwitchBar(mContext);

    private final CompoundButton mSwitch = mBar.findViewById(android.R.id.switch_widget);

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

        assertThat(textView.getText().toString()).isEqualTo(title);
    }

    @Test
    public void setTitle_switchShouldNotHasContentDescription() {
        final String title = "title";

        mBar.setTitle(title);

        assertThat(mSwitch.getContentDescription()).isNull();
    }

    @Test
    public void getSwitch_shouldNotNull() {
        assertThat(mSwitch).isNotNull();
    }

    @Test
    public void getSwitch_shouldNotFocusableAndClickable() {
        assertThat(mSwitch.isFocusable()).isFalse();
        assertThat(mSwitch.isClickable()).isFalse();
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

    @Test
    public void setTitle_shouldSetCorrectLineBreakStyle() {
        final String title = "title";

        mBar.setTitle(title);
        final TextView textView = ((TextView) mBar.findViewById(R.id.switch_text));

        assertThat(textView.getLineBreakWordStyle()).isEqualTo(LINE_BREAK_WORD_STYLE_PHRASE);
    }
}
