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

import static com.android.systemui.Flags.FLAG_KEYBOARD_SHORTCUT_HELPER_REWRITE;

import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.content.Intent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.dx.mockito.inline.extended.StaticMockitoSession;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.flags.FakeFeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.shared.recents.utilities.Utilities;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.quality.Strictness;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class KeyboardShortcutsReceiverTest extends SysuiTestCase {

    private static final Intent SHOW_INTENT = new Intent(Intent.ACTION_SHOW_KEYBOARD_SHORTCUTS);
    private static final Intent DISMISS_INTENT =
            new Intent(Intent.ACTION_DISMISS_KEYBOARD_SHORTCUTS);

    private StaticMockitoSession mockitoSession;
    private KeyboardShortcutsReceiver mKeyboardShortcutsReceiver;
    private final FakeFeatureFlags mFeatureFlags = new FakeFeatureFlags();

    @Mock private KeyboardShortcuts mKeyboardShortcuts;
    @Mock private KeyboardShortcutListSearch mKeyboardShortcutListSearch;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mSetFlagsRule.disableFlags(FLAG_KEYBOARD_SHORTCUT_HELPER_REWRITE);
        mKeyboardShortcuts.mContext = mContext;
        mKeyboardShortcutListSearch.mContext = mContext;
        KeyboardShortcuts.sInstance = mKeyboardShortcuts;
        KeyboardShortcutListSearch.sInstance = mKeyboardShortcutListSearch;

        mKeyboardShortcutsReceiver = spy(new KeyboardShortcutsReceiver(mFeatureFlags));
    }

    @Before
    public void startStaticMocking() {
        mockitoSession =
                ExtendedMockito.mockitoSession()
                        .spyStatic(Utilities.class)
                        .strictness(Strictness.LENIENT)
                        .startMocking();
    }

    @After
    public void endStaticMocking() {
        mockitoSession.finishMocking();
    }

    @Test
    public void onReceive_whenFlagOffDeviceIsTablet_showKeyboardShortcuts() {
        mFeatureFlags.set(Flags.SHORTCUT_LIST_SEARCH_LAYOUT, false);
        when(Utilities.isLargeScreen(mContext)).thenReturn(true);

        mKeyboardShortcutsReceiver.onReceive(mContext, SHOW_INTENT);

        verify(mKeyboardShortcuts).showKeyboardShortcuts(anyInt());
        verify(mKeyboardShortcutListSearch, never()).showKeyboardShortcuts(anyInt());
    }

    @Test
    public void onReceive_whenFlagOffDeviceIsNotTablet_showKeyboardShortcuts() {
        mFeatureFlags.set(Flags.SHORTCUT_LIST_SEARCH_LAYOUT, false);
        when(Utilities.isLargeScreen(mContext)).thenReturn(false);

        mKeyboardShortcutsReceiver.onReceive(mContext, SHOW_INTENT);

        verify(mKeyboardShortcuts).showKeyboardShortcuts(anyInt());
        verify(mKeyboardShortcutListSearch, never()).showKeyboardShortcuts(anyInt());
    }

    @Test
    public void onReceive_whenFlagOnDeviceIsTablet_showKeyboardShortcutListSearch() {
        mFeatureFlags.set(Flags.SHORTCUT_LIST_SEARCH_LAYOUT, true);
        when(Utilities.isLargeScreen(mContext)).thenReturn(true);

        mKeyboardShortcutsReceiver.onReceive(mContext, SHOW_INTENT);

        verify(mKeyboardShortcuts, never()).showKeyboardShortcuts(anyInt());
        verify(mKeyboardShortcutListSearch).showKeyboardShortcuts(anyInt());
    }

    @Test
    public void onReceive_whenFlagOnDeviceIsNotTablet_showKeyboardShortcuts() {
        mFeatureFlags.set(Flags.SHORTCUT_LIST_SEARCH_LAYOUT, true);
        when(Utilities.isLargeScreen(mContext)).thenReturn(false);

        mKeyboardShortcutsReceiver.onReceive(mContext, SHOW_INTENT);

        verify(mKeyboardShortcuts).showKeyboardShortcuts(anyInt());
        verify(mKeyboardShortcutListSearch, never()).showKeyboardShortcuts(anyInt());
    }

    @Test
    public void onShowIntent_rewriteFlagOn_oldFlagOn_isLargeScreen_doesNotLaunchOldVersions() {
        mFeatureFlags.set(Flags.SHORTCUT_LIST_SEARCH_LAYOUT, true);
        mSetFlagsRule.enableFlags(FLAG_KEYBOARD_SHORTCUT_HELPER_REWRITE);
        when(Utilities.isLargeScreen(mContext)).thenReturn(true);

        mKeyboardShortcutsReceiver.onReceive(mContext, SHOW_INTENT);

        verifyZeroInteractions(mKeyboardShortcuts, mKeyboardShortcutListSearch);
    }

    @Test
    public void onShowIntent_rewriteFlagOn_oldFlagOn_isSmallScreen_doesNotLaunchOldVersions() {
        mFeatureFlags.set(Flags.SHORTCUT_LIST_SEARCH_LAYOUT, true);
        mSetFlagsRule.enableFlags(FLAG_KEYBOARD_SHORTCUT_HELPER_REWRITE);
        when(Utilities.isLargeScreen(mContext)).thenReturn(false);

        mKeyboardShortcutsReceiver.onReceive(mContext, SHOW_INTENT);

        verifyZeroInteractions(mKeyboardShortcuts, mKeyboardShortcutListSearch);
    }

    @Test
    public void onDismissIntent_rewriteFlagOn_oldFlagOn_isLargeScreen_doesNotDismissOldVersions() {
        mFeatureFlags.set(Flags.SHORTCUT_LIST_SEARCH_LAYOUT, true);
        mSetFlagsRule.enableFlags(FLAG_KEYBOARD_SHORTCUT_HELPER_REWRITE);
        when(Utilities.isLargeScreen(mContext)).thenReturn(true);

        mKeyboardShortcutsReceiver.onReceive(mContext, DISMISS_INTENT);

        verifyZeroInteractions(mKeyboardShortcuts, mKeyboardShortcutListSearch);
    }

    @Test
    public void onDismissIntent_rewriteFlagOn_oldFlagOn_isSmallScreen_doesNotDismissOldVersions() {
        mFeatureFlags.set(Flags.SHORTCUT_LIST_SEARCH_LAYOUT, true);
        mSetFlagsRule.enableFlags(FLAG_KEYBOARD_SHORTCUT_HELPER_REWRITE);
        when(Utilities.isLargeScreen(mContext)).thenReturn(false);

        mKeyboardShortcutsReceiver.onReceive(mContext, DISMISS_INTENT);

        verifyZeroInteractions(mKeyboardShortcuts, mKeyboardShortcutListSearch);
    }
}
