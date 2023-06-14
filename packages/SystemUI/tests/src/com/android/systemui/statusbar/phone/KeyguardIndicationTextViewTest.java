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

package com.android.systemui.statusbar.phone;

import static android.view.View.ACCESSIBILITY_LIVE_REGION_NONE;
import static android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE;

import static com.google.common.truth.Truth.assertThat;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class KeyguardIndicationTextViewTest extends SysuiTestCase {

    private KeyguardIndicationTextView mKeyguardIndicationTextView;

    @Before
    public void setup() {
        mKeyguardIndicationTextView = new KeyguardIndicationTextView(mContext);
        mKeyguardIndicationTextView.setAnimationsEnabled(false);
    }

    @After
    public void tearDown() {
        mKeyguardIndicationTextView.setAnimationsEnabled(true);
    }

    @Test
    public void switchIndication_null_hideIndication() {
        mKeyguardIndicationTextView.switchIndication(null /* text */, null);

        assertThat(mKeyguardIndicationTextView.getText()).isEqualTo("");
    }

    @Test
    public void alwaysAnnounce_setsLiveRegionToNone() {
        mKeyguardIndicationTextView.setAlwaysAnnounceEnabled(true);

        assertThat(mKeyguardIndicationTextView.getAccessibilityLiveRegion()).isEqualTo(
                ACCESSIBILITY_LIVE_REGION_NONE);
    }

    @Test
    public void alwaysAnnounce_setsLiveRegionToDefaultPolite_whenDisabled() {
        mKeyguardIndicationTextView.setAlwaysAnnounceEnabled(false);

        assertThat(mKeyguardIndicationTextView.getAccessibilityLiveRegion()).isEqualTo(
                ACCESSIBILITY_LIVE_REGION_POLITE);
    }

    @Test
    public void switchIndication_emptyText_hideIndication() {
        mKeyguardIndicationTextView.switchIndication("" /* text */, null);

        assertThat(mKeyguardIndicationTextView.getText()).isEqualTo("");
    }

    @Test
    public void switchIndication_newText_updateProperly() {
        mKeyguardIndicationTextView.switchIndication("test_indication" /* text */, null);

        assertThat(mKeyguardIndicationTextView.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mKeyguardIndicationTextView.getText()).isEqualTo("test_indication");
    }
}
