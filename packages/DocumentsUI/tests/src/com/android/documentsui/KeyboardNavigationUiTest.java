/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.documentsui;

import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.Suppress;
import android.view.KeyEvent;

@LargeTest
public class KeyboardNavigationUiTest extends ActivityTest<FilesActivity> {

    public KeyboardNavigationUiTest() {
        super(FilesActivity.class);
    }

    // Tests that pressing tab switches focus between the roots and directory listings.
    @Suppress
    public void testKeyboard_tab() throws Exception {
        bots.keyboard.pressKey(KeyEvent.KEYCODE_TAB);
        bots.roots.assertHasFocus();
        bots.keyboard.pressKey(KeyEvent.KEYCODE_TAB);
        bots.directory.assertHasFocus();
    }

    // Tests that arrow keys do not switch focus away from the dir list.
    @Suppress
    public void testKeyboard_arrowsDirList() throws Exception {
        for (int i = 0; i < 10; i++) {
            bots.keyboard.pressKey(KeyEvent.KEYCODE_DPAD_LEFT);
            bots.directory.assertHasFocus();
        }
        for (int i = 0; i < 10; i++) {
            bots.keyboard.pressKey(KeyEvent.KEYCODE_DPAD_RIGHT);
            bots.directory.assertHasFocus();
        }
    }

    // Tests that arrow keys do not switch focus away from the roots list.
    public void testKeyboard_arrowsRootsList() throws Exception {
        bots.keyboard.pressKey(KeyEvent.KEYCODE_TAB);
        for (int i = 0; i < 10; i++) {
            bots.keyboard.pressKey(KeyEvent.KEYCODE_DPAD_RIGHT);
            bots.roots.assertHasFocus();
        }
        for (int i = 0; i < 10; i++) {
            bots.keyboard.pressKey(KeyEvent.KEYCODE_DPAD_LEFT);
            bots.roots.assertHasFocus();
        }
    }
}
