/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.policy;

import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_ALLOW_ACTION_KEY_EVENTS;

import static com.google.common.truth.Truth.assertThat;

import android.view.KeyEvent;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;

import com.android.internal.policy.KeyInterceptionInfo;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * Testing {@link PhoneWindowManager} functionality of letting app intercepting key events
 * containing META.
 */
@SmallTest
public class MetaKeyEventsInterceptionTests extends ShortcutKeyTestBase {

    private static final List<KeyEvent> META_KEY_EVENTS = Arrays.asList(
            new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_META_LEFT),
            new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_META_RIGHT),
            new KeyEvent(/* downTime= */ 0, /* eventTime= */
                    0, /* action= */ 0, /* code= */ 0, /* repeat= */ 0,
                    /* metaState= */ KeyEvent.META_META_ON));

    @Before
    public void setUp() {
        setUpPhoneWindowManager();
    }

    @Test
    public void doesntInterceptMetaKeyEvents_whenWindowAskedForIt() {
        mPhoneWindowManager.overrideFocusedWindowButtonOverridePermission(/* granted= */ true);
        setWindowKeyInterceptionWithPrivateFlags(PRIVATE_FLAG_ALLOW_ACTION_KEY_EVENTS);

        META_KEY_EVENTS.forEach(keyEvent -> {
            assertKeyInterceptionResult(keyEvent, /* intercepted= */ false);
        });
    }

    @Test
    public void interceptsMetaKeyEvents_whenWindowDoesntHaveFlagSet() {
        mPhoneWindowManager.overrideFocusedWindowButtonOverridePermission(/* granted= */ true);
        setWindowKeyInterceptionWithPrivateFlags(0);

        META_KEY_EVENTS.forEach(keyEvent -> {
            assertKeyInterceptionResult(keyEvent, /* intercepted= */ true);
        });
    }

    @Test
    public void interceptsMetaKeyEvents_whenWindowDoesntHavePermission() {
        mPhoneWindowManager.overrideFocusedWindowButtonOverridePermission(/* granted= */ false);
        setWindowKeyInterceptionWithPrivateFlags(PRIVATE_FLAG_ALLOW_ACTION_KEY_EVENTS);

        META_KEY_EVENTS.forEach(keyEvent -> {
            assertKeyInterceptionResult(keyEvent, /* intercepted= */ true);
        });
    }

    private void setWindowKeyInterceptionWithPrivateFlags(int privateFlags) {
        KeyInterceptionInfo info = new KeyInterceptionInfo(
                WindowManager.LayoutParams.TYPE_APPLICATION, privateFlags, "title", 0);
        mPhoneWindowManager.overrideWindowKeyInterceptionInfo(info);
    }

    private void assertKeyInterceptionResult(KeyEvent keyEvent, boolean intercepted) {
        long result = mPhoneWindowManager.interceptKeyBeforeDispatching(keyEvent);
        int expected = intercepted ? -1 : 0;
        assertThat(result).isEqualTo(expected);
    }
}
