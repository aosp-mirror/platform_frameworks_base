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

package com.android.systemui.accessibility;


import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.provider.Settings;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(AndroidTestingRunner.class)
@SmallTest
public class AccessibilityButtonModeObserverTest extends SysuiTestCase {

    @Rule
    public MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    private AccessibilityButtonModeObserver.ModeChangedListener mListener;

    private AccessibilityButtonModeObserver mAccessibilityButtonModeObserver;

    private static final int TEST_A11Y_BTN_MODE_VALUE =
            Settings.Secure.ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU;

    @Before
    public void setUp() {
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_BUTTON_MODE,
                Settings.Secure.ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR);
        mAccessibilityButtonModeObserver = new AccessibilityButtonModeObserver(mContext);
    }

    @Test
    public void onChange_haveListener_invokeCallback() {
        mAccessibilityButtonModeObserver.addListener(mListener);
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_BUTTON_MODE, TEST_A11Y_BTN_MODE_VALUE);

        mAccessibilityButtonModeObserver.mContentObserver.onChange(false);

        verify(mListener).onAccessibilityButtonModeChanged(TEST_A11Y_BTN_MODE_VALUE);
    }

    @Test
    public void onChange_noListener_noInvokeCallback() {
        mAccessibilityButtonModeObserver.addListener(mListener);
        mAccessibilityButtonModeObserver.removeListener(mListener);
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_BUTTON_MODE, TEST_A11Y_BTN_MODE_VALUE);

        mAccessibilityButtonModeObserver.mContentObserver.onChange(false);

        verify(mListener, never()).onAccessibilityButtonModeChanged(anyInt());
    }

    @Test
    public void getCurrentAccessibilityButtonMode_expectedValue() {
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_BUTTON_MODE, TEST_A11Y_BTN_MODE_VALUE);

        final int actualValue =
                mAccessibilityButtonModeObserver.getCurrentAccessibilityButtonMode();

        assertThat(actualValue).isEqualTo(TEST_A11Y_BTN_MODE_VALUE);
    }
}
