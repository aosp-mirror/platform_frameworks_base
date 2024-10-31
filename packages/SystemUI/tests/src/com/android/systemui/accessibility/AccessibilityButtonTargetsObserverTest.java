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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.util.settings.SecureSettings;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Test for {@link AccessibilityButtonTargetsObserver}. */
@RunWith(AndroidTestingRunner.class)
@SmallTest
public class AccessibilityButtonTargetsObserverTest extends SysuiTestCase {
    private static final int MY_USER_ID = ActivityManager.getCurrentUser();

    @Rule
    public MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    private UserTracker mUserTracker;
    @Mock
    private AccessibilityButtonTargetsObserver.TargetsChangedListener mListener;

    private AccessibilityButtonTargetsObserver mAccessibilityButtonTargetsObserver;

    private static final String TEST_A11Y_BTN_TARGETS = "Magnification";

    @Before
    public void setUp() {
        when(mUserTracker.getUserId()).thenReturn(MY_USER_ID);
        mAccessibilityButtonTargetsObserver = new AccessibilityButtonTargetsObserver(mContext,
                mUserTracker, Mockito.mock(SecureSettings.class));
    }

    @Test
    public void onChange_haveListener_invokeCallback() {
        mAccessibilityButtonTargetsObserver.addListener(mListener);
        Settings.Secure.putStringForUser(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS, TEST_A11Y_BTN_TARGETS,
                MY_USER_ID);

        mAccessibilityButtonTargetsObserver.mContentObserver.onChange(false);

        verify(mListener).onAccessibilityButtonTargetsChanged(TEST_A11Y_BTN_TARGETS);
    }

    @Test
    public void onChange_listenerRemoved_noInvokeCallback() {
        mAccessibilityButtonTargetsObserver.addListener(mListener);
        mAccessibilityButtonTargetsObserver.removeListener(mListener);
        Settings.Secure.putStringForUser(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS, TEST_A11Y_BTN_TARGETS,
                MY_USER_ID);

        mAccessibilityButtonTargetsObserver.mContentObserver.onChange(false);

        verify(mListener, never()).onAccessibilityButtonTargetsChanged(anyString());
    }

    @Test
    public void getCurrentAccessibilityButtonTargets_expectedValue() {
        Settings.Secure.putStringForUser(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS, TEST_A11Y_BTN_TARGETS,
                MY_USER_ID);

        final String actualValue =
                mAccessibilityButtonTargetsObserver.getCurrentAccessibilityButtonTargets();

        assertThat(actualValue).isEqualTo(TEST_A11Y_BTN_TARGETS);
    }
}
