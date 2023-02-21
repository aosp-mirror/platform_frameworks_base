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

import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Intent;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.flags.FakeFeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.shared.recents.utilities.Utilities;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.quality.Strictness;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class KeyboardShortcutsReceiverTest extends SysuiTestCase {

    @Rule public MockitoRule mockito = MockitoJUnit.rule();

    private KeyboardShortcutsReceiver mKeyboardShortcutsReceiver;
    private Intent mIntent;
    private FakeFeatureFlags mFeatureFlags = new FakeFeatureFlags();

    @Mock private KeyboardShortcuts mKeyboardShortcuts;
    @Mock private KeyboardShortcutListSearch mKeyboardShortcutListSearch;

    @Before
    public void setUp() {
        mIntent = new Intent(Intent.ACTION_SHOW_KEYBOARD_SHORTCUTS);
        mKeyboardShortcuts.mContext = mContext;
        mKeyboardShortcutListSearch.mContext = mContext;
        KeyboardShortcuts.sInstance = mKeyboardShortcuts;
        KeyboardShortcutListSearch.sInstance = mKeyboardShortcutListSearch;
    }

    @Test
    public void onReceive_whenFlagOffDeviceIsTablet_showKeyboardShortcuts() {
        MockitoSession mockitoSession = ExtendedMockito.mockitoSession()
                .spyStatic(Utilities.class)
                .strictness(Strictness.LENIENT)
                .startMocking();
        mFeatureFlags.set(Flags.SHORTCUT_LIST_SEARCH_LAYOUT, false);
        mKeyboardShortcutsReceiver = spy(new KeyboardShortcutsReceiver(mFeatureFlags));
        when(Utilities.isLargeScreen(mContext)).thenReturn(true);

        mKeyboardShortcutsReceiver.onReceive(mContext, mIntent);

        verify(mKeyboardShortcuts).showKeyboardShortcuts(anyInt());
        verify(mKeyboardShortcutListSearch, never()).showKeyboardShortcuts(anyInt());
        mockitoSession.finishMocking();
    }

    @Test
    public void onReceive_whenFlagOffDeviceIsNotTablet_showKeyboardShortcuts() {
        MockitoSession mockitoSession = ExtendedMockito.mockitoSession()
                .spyStatic(Utilities.class)
                .strictness(Strictness.LENIENT)
                .startMocking();
        mFeatureFlags.set(Flags.SHORTCUT_LIST_SEARCH_LAYOUT, false);
        mKeyboardShortcutsReceiver = spy(new KeyboardShortcutsReceiver(mFeatureFlags));
        when(Utilities.isLargeScreen(mContext)).thenReturn(false);

        mKeyboardShortcutsReceiver.onReceive(mContext, mIntent);

        verify(mKeyboardShortcuts).showKeyboardShortcuts(anyInt());
        verify(mKeyboardShortcutListSearch, never()).showKeyboardShortcuts(anyInt());
        mockitoSession.finishMocking();
    }

    @Test
    public void onReceive_whenFlagOnDeviceIsTablet_showKeyboardShortcutListSearch() {
        MockitoSession mockitoSession = ExtendedMockito.mockitoSession()
                .spyStatic(Utilities.class)
                .strictness(Strictness.LENIENT)
                .startMocking();
        mFeatureFlags.set(Flags.SHORTCUT_LIST_SEARCH_LAYOUT, true);
        mKeyboardShortcutsReceiver = spy(new KeyboardShortcutsReceiver(mFeatureFlags));
        when(Utilities.isLargeScreen(mContext)).thenReturn(true);

        mKeyboardShortcutsReceiver.onReceive(mContext, mIntent);

        verify(mKeyboardShortcuts, never()).showKeyboardShortcuts(anyInt());
        verify(mKeyboardShortcutListSearch).showKeyboardShortcuts(anyInt());
        mockitoSession.finishMocking();
    }

    @Test
    public void onReceive_whenFlagOnDeviceIsNotTablet_showKeyboardShortcuts() {
        MockitoSession mockitoSession = ExtendedMockito.mockitoSession()
                .spyStatic(Utilities.class)
                .strictness(Strictness.LENIENT)
                .startMocking();
        mFeatureFlags.set(Flags.SHORTCUT_LIST_SEARCH_LAYOUT, true);
        mKeyboardShortcutsReceiver = spy(new KeyboardShortcutsReceiver(mFeatureFlags));
        when(Utilities.isLargeScreen(mContext)).thenReturn(false);

        mKeyboardShortcutsReceiver.onReceive(mContext, mIntent);

        verify(mKeyboardShortcuts).showKeyboardShortcuts(anyInt());
        verify(mKeyboardShortcutListSearch, never()).showKeyboardShortcuts(anyInt());
        mockitoSession.finishMocking();
    }
}
