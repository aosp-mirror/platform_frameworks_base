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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.graphics.drawable.Icon;
import android.os.Handler;
import android.platform.test.annotations.EnableFlags;
import android.view.KeyboardShortcutGroup;
import android.view.KeyboardShortcutInfo;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.Flags;
import com.android.systemui.SysuiTestCase;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.Arrays;
import java.util.Collections;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class KeyboardShortcutListSearchTest extends SysuiTestCase {

    @Rule public MockitoRule mockito = MockitoJUnit.rule();

    private static int DEVICE_ID = 1;
    private KeyboardShortcutListSearch mKeyboardShortcutListSearch;

    @Mock private BottomSheetDialog mBottomSheetDialog;
    @Mock WindowManager mWindowManager;
    @Mock Handler mHandler;

    @Before
    public void setUp() {
        mKeyboardShortcutListSearch = new KeyboardShortcutListSearch(mContext, mWindowManager);
        mKeyboardShortcutListSearch.sInstance = mKeyboardShortcutListSearch;
        mKeyboardShortcutListSearch.mKeyboardShortcutsBottomSheetDialog = mBottomSheetDialog;
        mKeyboardShortcutListSearch.mContext = mContext;
        mKeyboardShortcutListSearch.mBackgroundHandler = mHandler;
    }

    @Test
    public void toggle_isShowingTrue_instanceShouldBeNull() {
        when(mBottomSheetDialog.isShowing()).thenReturn(true);

        mKeyboardShortcutListSearch.toggle(mContext, DEVICE_ID);

        assertThat(mKeyboardShortcutListSearch.sInstance).isNull();
    }

    @Test
    public void toggle_isShowingFalse_showKeyboardShortcuts() {
        when(mBottomSheetDialog.isShowing()).thenReturn(false);

        mKeyboardShortcutListSearch.toggle(mContext, DEVICE_ID);

        verify(mWindowManager).requestAppKeyboardShortcuts(any(), anyInt());
        verify(mWindowManager).requestImeKeyboardShortcuts(any(), anyInt());
    }

    @Test
    @EnableFlags(Flags.FLAG_VALIDATE_KEYBOARD_SHORTCUT_HELPER_ICON_URI)
    public void requestAppKeyboardShortcuts_callback_sanitisesIcons() {
        KeyboardShortcutGroup group = createKeyboardShortcutGroupForIconTests();

        mKeyboardShortcutListSearch.toggle(mContext, DEVICE_ID);

        ArgumentCaptor<WindowManager.KeyboardShortcutsReceiver> callbackCaptor =
                ArgumentCaptor.forClass(WindowManager.KeyboardShortcutsReceiver.class);
        ArgumentCaptor<Runnable> handlerRunnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(mWindowManager).requestAppKeyboardShortcuts(callbackCaptor.capture(), anyInt());
        callbackCaptor.getValue().onKeyboardShortcutsReceived(Collections.singletonList(group));
        verify(mHandler).post(handlerRunnableCaptor.capture());
        handlerRunnableCaptor.getValue().run();

        verify(group.getItems().get(0)).clearIcon();
        verify(group.getItems().get(1)).clearIcon();
    }

    @Test
    @EnableFlags(Flags.FLAG_VALIDATE_KEYBOARD_SHORTCUT_HELPER_ICON_URI)
    public void requestImeKeyboardShortcuts_callback_sanitisesIcons() {
        KeyboardShortcutGroup group = createKeyboardShortcutGroupForIconTests();

        mKeyboardShortcutListSearch.toggle(mContext, DEVICE_ID);

        ArgumentCaptor<WindowManager.KeyboardShortcutsReceiver> callbackCaptor =
                ArgumentCaptor.forClass(WindowManager.KeyboardShortcutsReceiver.class);
        ArgumentCaptor<Runnable> handlerRunnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(mWindowManager).requestImeKeyboardShortcuts(callbackCaptor.capture(), anyInt());
        callbackCaptor.getValue().onKeyboardShortcutsReceived(Collections.singletonList(group));
        verify(mHandler).post(handlerRunnableCaptor.capture());
        handlerRunnableCaptor.getValue().run();

        verify(group.getItems().get(0)).clearIcon();
        verify(group.getItems().get(1)).clearIcon();

    }

    private KeyboardShortcutGroup createKeyboardShortcutGroupForIconTests() {
        Icon icon = mock(Icon.class);

        KeyboardShortcutInfo info1 = mock(KeyboardShortcutInfo.class);
        KeyboardShortcutInfo info2 = mock(KeyboardShortcutInfo.class);
        when(info1.getIcon()).thenReturn(icon);
        when(info2.getIcon()).thenReturn(icon);
        when(info1.getLabel()).thenReturn("label");
        when(info2.getLabel()).thenReturn("label");

        KeyboardShortcutGroup group = new KeyboardShortcutGroup("label",
                Arrays.asList(new KeyboardShortcutInfo[]{ info1, info2}));
        group.setPackageName("com.example");
        return group;
    }
}
