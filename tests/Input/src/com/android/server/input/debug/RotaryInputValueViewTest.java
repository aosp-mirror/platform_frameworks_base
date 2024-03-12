/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.server.input.debug;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.view.ViewConfiguration;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Locale;

/**
 * Build/Install/Run:
 * atest RotaryInputValueViewTest
 */
@RunWith(AndroidJUnit4.class)
public class RotaryInputValueViewTest {

    private final Locale mDefaultLocale = Locale.getDefault();

    private RotaryInputValueView mRotaryInputValueView;
    private float mScaledVerticalScrollFactor;

    @Before
    public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getContext();
        mScaledVerticalScrollFactor =
                ViewConfiguration.get(context).getScaledVerticalScrollFactor();

        mRotaryInputValueView = new RotaryInputValueView(context);
    }

    @Test
    public void startsWithDefaultValue() {
        assertEquals("+0.0", mRotaryInputValueView.getText().toString());
    }

    @Test
    public void updateValue_updatesTextWithScrollValue() {
        final float scrollAxisValue = 1000f;
        final String expectedText = String.format(mDefaultLocale, "+%.1f",
                scrollAxisValue * mScaledVerticalScrollFactor);

        mRotaryInputValueView.updateValue(scrollAxisValue);

        assertEquals(expectedText, mRotaryInputValueView.getText().toString());
    }

    @Test
    public void updateActivityStatus_setsAndRemovesColorFilter() {
        // It should not be active initially.
        assertNull(mRotaryInputValueView.getBackground().getColorFilter());

        mRotaryInputValueView.updateActivityStatus(true);
        // It should be active after rotary input.
        assertNotNull(mRotaryInputValueView.getBackground().getColorFilter());

        mRotaryInputValueView.updateActivityStatus(false);
        // It should not be active after waiting for mUpdateActivityStatusCallback.
        assertNull(mRotaryInputValueView.getBackground().getColorFilter());
    }
}
