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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static java.util.Collections.singletonList;

import android.annotation.Nullable;
import android.app.Dialog;
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

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.stubbing.Answer;

import java.util.Arrays;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class KeyboardShortcutsTest extends SysuiTestCase {

    @Rule public MockitoRule mockito = MockitoJUnit.rule();

    private static final int DEVICE_ID = 1;
    private KeyboardShortcuts mKeyboardShortcuts;

    @Mock private Dialog mDialog;
    @Mock WindowManager mWindowManager;
    @Mock Handler mHandler;

    @Before
    public void setUp() {
        mKeyboardShortcuts = new KeyboardShortcuts(mContext, mWindowManager);
        KeyboardShortcuts.sInstance = mKeyboardShortcuts;
        mKeyboardShortcuts.mKeyboardShortcutsDialog = mDialog;
        mKeyboardShortcuts.mContext = mContext;
        mKeyboardShortcuts.mBackgroundHandler = mHandler;
        when(mHandler.post(any()))
                .thenAnswer(
                        new Answer<>() {
                            @Override
                            public Object answer(InvocationOnMock invocation) {
                                ((Runnable) invocation.getArgument(0)).run();
                                return null;
                            }
                        });
    }

    @Test
    public void toggle_isShowingTrue_instanceShouldBeNull() {
        when(mDialog.isShowing()).thenReturn(true);

        KeyboardShortcuts.toggle(mContext, DEVICE_ID);

        assertThat(KeyboardShortcuts.sInstance).isNull();
    }

    @Test
    public void toggle_isShowingFalse_showKeyboardShortcuts() {
        when(mDialog.isShowing()).thenReturn(false);

        KeyboardShortcuts.toggle(mContext, DEVICE_ID);

        verify(mWindowManager).requestAppKeyboardShortcuts(any(), anyInt());
        verify(mWindowManager).requestImeKeyboardShortcuts(any(), anyInt());
    }

    @Test
    public void sanitiseShortcuts_clearsIcons() {
        KeyboardShortcutGroup group = createKeyboardShortcutGroupForIconTests();

        KeyboardShortcuts.sanitiseShortcuts(singletonList(group));

        verify(group.getItems().get(0)).clearIcon();
        verify(group.getItems().get(1)).clearIcon();
    }

    @Test
    public void sanitiseShortcuts_nullPackage_clearsIcons() {
        KeyboardShortcutGroup group = createKeyboardShortcutGroupForIconTests();
        group.setPackageName(null);

        KeyboardShortcuts.sanitiseShortcuts(singletonList(group));

        verify(group.getItems().get(0)).clearIcon();
        verify(group.getItems().get(1)).clearIcon();
    }

    @Test
    @EnableFlags(Flags.FLAG_VALIDATE_KEYBOARD_SHORTCUT_HELPER_ICON_URI)
    public void requestAppKeyboardShortcuts_callback_sanitisesIcons() {
        KeyboardShortcutGroup group = createKeyboardShortcutGroupForIconTests();
        KeyboardShortcuts.toggle(mContext, DEVICE_ID);

        emitAppShortcuts(singletonList(group), DEVICE_ID);

        verify(group.getItems().get(0)).clearIcon();
        verify(group.getItems().get(1)).clearIcon();
    }

    @Test
    @EnableFlags(Flags.FLAG_VALIDATE_KEYBOARD_SHORTCUT_HELPER_ICON_URI)
    public void requestImeKeyboardShortcuts_callback_sanitisesIcons() {
        KeyboardShortcutGroup group = createKeyboardShortcutGroupForIconTests();
        KeyboardShortcuts.toggle(mContext, DEVICE_ID);

        emitImeShortcuts(singletonList(group), DEVICE_ID);

        verify(group.getItems().get(0)).clearIcon();
        verify(group.getItems().get(1)).clearIcon();
    }

    @Test
    public void onImeAndAppShortcutsReceived_appShortcutsNull_doesNotCrash() {
        KeyboardShortcutGroup group = createKeyboardShortcutGroupForIconTests();
        KeyboardShortcuts.toggle(mContext, DEVICE_ID);

        emitImeShortcuts(singletonList(group), DEVICE_ID);
        emitAppShortcuts(/* groups= */ null, DEVICE_ID);
    }

    @Test
    public void onImeAndAppShortcutsReceived_imeShortcutsNull_doesNotCrash() {
        KeyboardShortcutGroup group = createKeyboardShortcutGroupForIconTests();
        KeyboardShortcuts.toggle(mContext, DEVICE_ID);

        emitAppShortcuts(singletonList(group), DEVICE_ID);
        emitImeShortcuts(/* groups= */ null, DEVICE_ID);
    }

    @Test
    public void onImeAndAppShortcutsReceived_bothNull_doesNotCrash() {
        KeyboardShortcuts.toggle(mContext, DEVICE_ID);

        emitImeShortcuts(/* groups= */ null, DEVICE_ID);
        emitAppShortcuts(/* groups= */ null, DEVICE_ID);
    }

    private KeyboardShortcutGroup createKeyboardShortcutGroupForIconTests() {
        Icon icon = mock(Icon.class);

        KeyboardShortcutInfo info1 = mock(KeyboardShortcutInfo.class);
        KeyboardShortcutInfo info2 = mock(KeyboardShortcutInfo.class);
        when(info1.getIcon()).thenReturn(icon);
        when(info2.getIcon()).thenReturn(icon);

        KeyboardShortcutGroup group =
                new KeyboardShortcutGroup("label", Arrays.asList(info1, info2));
        group.setPackageName("com.example");
        return group;
    }

    private void emitImeShortcuts(@Nullable List<KeyboardShortcutGroup> groups, int deviceId) {
        ArgumentCaptor<WindowManager.KeyboardShortcutsReceiver> callbackCaptor =
                ArgumentCaptor.forClass(WindowManager.KeyboardShortcutsReceiver.class);
        verify(mWindowManager).requestImeKeyboardShortcuts(callbackCaptor.capture(), eq(deviceId));
        callbackCaptor.getValue().onKeyboardShortcutsReceived(groups);
    }

    private void emitAppShortcuts(@Nullable List<KeyboardShortcutGroup> groups, int deviceId) {
        ArgumentCaptor<WindowManager.KeyboardShortcutsReceiver> callbackCaptor =
                ArgumentCaptor.forClass(WindowManager.KeyboardShortcutsReceiver.class);
        verify(mWindowManager).requestAppKeyboardShortcuts(callbackCaptor.capture(), eq(deviceId));
        callbackCaptor.getValue().onKeyboardShortcutsReceived(groups);
    }
}
