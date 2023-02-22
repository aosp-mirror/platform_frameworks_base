/*
 * Copyright (c) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Dialog;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class KeyboardShortcutsTest extends SysuiTestCase {

    @Rule public MockitoRule mockito = MockitoJUnit.rule();

    private static int DEVICE_ID = 1;
    private KeyboardShortcuts mKeyboardShortcuts;

    @Mock private Dialog mDialog;
    @Mock WindowManager mWindowManager;

    @Before
    public void setUp() {
        mKeyboardShortcuts = new KeyboardShortcuts(mContext, mWindowManager);
        mKeyboardShortcuts.sInstance = mKeyboardShortcuts;
        mKeyboardShortcuts.mKeyboardShortcutsDialog = mDialog;
        mKeyboardShortcuts.mContext = mContext;
    }

    @Test
    public void toggle_isShowingTrue_instanceShouldBeNull() {
        when(mDialog.isShowing()).thenReturn(true);

        mKeyboardShortcuts.toggle(mContext, DEVICE_ID);

        assertThat(mKeyboardShortcuts.sInstance).isNull();
    }

    @Test
    public void toggle_isShowingFalse_showKeyboardShortcuts() {
        when(mDialog.isShowing()).thenReturn(false);

        mKeyboardShortcuts.toggle(mContext, DEVICE_ID);

        verify(mWindowManager).requestAppKeyboardShortcuts(any(), anyInt());
    }
}
