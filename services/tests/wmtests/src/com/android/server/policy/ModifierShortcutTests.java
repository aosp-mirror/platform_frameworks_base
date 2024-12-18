/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.view.KeyEvent.KEYCODE_ALT_LEFT;
import static android.view.KeyEvent.KEYCODE_B;
import static android.view.KeyEvent.KEYCODE_BRIGHTNESS_DOWN;
import static android.view.KeyEvent.KEYCODE_C;
import static android.view.KeyEvent.KEYCODE_CTRL_LEFT;
import static android.view.KeyEvent.KEYCODE_DEL;
import static android.view.KeyEvent.KEYCODE_E;
import static android.view.KeyEvent.KEYCODE_ENTER;
import static android.view.KeyEvent.KEYCODE_H;
import static android.view.KeyEvent.KEYCODE_J;
import static android.view.KeyEvent.KEYCODE_K;
import static android.view.KeyEvent.KEYCODE_M;
import static android.view.KeyEvent.KEYCODE_META_LEFT;
import static android.view.KeyEvent.KEYCODE_N;
import static android.view.KeyEvent.KEYCODE_P;
import static android.view.KeyEvent.KEYCODE_S;
import static android.view.KeyEvent.KEYCODE_SHIFT_LEFT;
import static android.view.KeyEvent.KEYCODE_SLASH;
import static android.view.KeyEvent.KEYCODE_SPACE;
import static android.view.KeyEvent.KEYCODE_TAB;
import static android.view.KeyEvent.KEYCODE_SCREENSHOT;
import static android.view.KeyEvent.KEYCODE_U;
import static android.view.KeyEvent.KEYCODE_Z;

import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.RemoteException;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;
import android.util.SparseArray;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;

@Presubmit
@SmallTest
@EnableFlags(com.android.hardware.input.Flags.FLAG_MODIFIER_SHORTCUT_MANAGER_REFACTOR)
public class ModifierShortcutTests extends ShortcutKeyTestBase {

    private static final SparseArray<String> INTENT_SHORTCUTS =  new SparseArray<>();
    private static final SparseArray<String> ROLE_SHORTCUTS =  new SparseArray<>();
    static {
        // These shortcuts should align with those defined in
        // services/tests/wmtests/res/xml/bookmarks.xml
        INTENT_SHORTCUTS.append(KEYCODE_U, Intent.CATEGORY_APP_CALCULATOR);
        INTENT_SHORTCUTS.append(KEYCODE_C, Intent.CATEGORY_APP_CONTACTS);
        INTENT_SHORTCUTS.append(KEYCODE_E, Intent.CATEGORY_APP_EMAIL);
        INTENT_SHORTCUTS.append(KEYCODE_K, Intent.CATEGORY_APP_CALENDAR);
        INTENT_SHORTCUTS.append(KEYCODE_M, Intent.CATEGORY_APP_MAPS);
        INTENT_SHORTCUTS.append(KEYCODE_P, Intent.CATEGORY_APP_MUSIC);

        ROLE_SHORTCUTS.append(KEYCODE_B, RoleManager.ROLE_BROWSER);
        ROLE_SHORTCUTS.append(KEYCODE_S, RoleManager.ROLE_SMS);
    }
    private static final int ANY_DISPLAY_ID = 123;

    @Before
    public void setUp() {
        setUpPhoneWindowManager();
    }

    /**
     * Test meta+ shortcuts defined in bookmarks.xml.
     */
    @Test
    public void testMetaShortcuts() {
        for (int i = 0; i < INTENT_SHORTCUTS.size(); i++) {
            final int keyCode = INTENT_SHORTCUTS.keyAt(i);
            final String category = INTENT_SHORTCUTS.valueAt(i);

            sendKeyCombination(new int[]{KEYCODE_META_LEFT, keyCode}, 0);
            mPhoneWindowManager.assertLaunchCategory(category);
        }

        mPhoneWindowManager.overrideRoleManager();
        for (int i = 0; i < ROLE_SHORTCUTS.size(); i++) {
            final int keyCode = ROLE_SHORTCUTS.keyAt(i);
            final String role = ROLE_SHORTCUTS.valueAt(i);

            sendKeyCombination(new int[]{KEYCODE_META_LEFT, keyCode}, 0);
            mPhoneWindowManager.assertLaunchRole(role);
        }

        sendKeyCombination(new int[]{KEYCODE_META_LEFT, KEYCODE_SHIFT_LEFT, KEYCODE_B}, 0);
        mPhoneWindowManager.assertLaunchRole(RoleManager.ROLE_BROWSER);

        sendKeyCombination(new int[]{KEYCODE_META_LEFT, KEYCODE_SHIFT_LEFT, KEYCODE_C}, 0);
        mPhoneWindowManager.assertLaunchCategory(Intent.CATEGORY_APP_CONTACTS);

        sendKeyCombination(new int[]{KEYCODE_META_LEFT, KEYCODE_SHIFT_LEFT, KEYCODE_J}, 0);
        mPhoneWindowManager.assertActivityTargetLaunched(
                new ComponentName("com.test", "com.test.BookmarkTest"));

    }

    /**
     * ALT + TAB to show recent apps.
     */
    @Test
    public void testAltTab() {
        mPhoneWindowManager.overrideStatusBarManagerInternal();
        sendKeyCombination(new int[]{KEYCODE_ALT_LEFT, KEYCODE_TAB}, 0);
        mPhoneWindowManager.assertShowRecentApps();
    }

    /**
     * CTRL + SPACE to switch keyboard layout.
     */
    @Test
    public void testCtrlSpace() {
        sendKeyCombination(new int[]{KEYCODE_CTRL_LEFT, KEYCODE_SPACE}, /* duration= */ 0,
                ANY_DISPLAY_ID);
        mPhoneWindowManager.assertSwitchKeyboardLayout(/* direction= */ 1, ANY_DISPLAY_ID);
    }

    /**
     * CTRL + SHIFT + SPACE to switch keyboard layout backwards.
     */
    @Test
    public void testCtrlShiftSpace() {
        sendKeyCombination(new int[]{KEYCODE_CTRL_LEFT, KEYCODE_SHIFT_LEFT, KEYCODE_SPACE},
                /* duration= */ 0, ANY_DISPLAY_ID);
        mPhoneWindowManager.assertSwitchKeyboardLayout(/* direction= */ -1, ANY_DISPLAY_ID);
    }

    /**
     * CTRL + ALT + Z to enable accessibility service.
     */
    @Test
    public void testCtrlAltZ() {
        sendKeyCombination(new int[]{KEYCODE_CTRL_LEFT, KEYCODE_ALT_LEFT, KEYCODE_Z}, 0);
        mPhoneWindowManager.assertAccessibilityKeychordCalled();
    }

    /**
     * META + CTRL+ S to take screenshot.
     */
    @Test
    public void testMetaCtrlS() {
        sendKeyCombination(new int[]{KEYCODE_META_LEFT, KEYCODE_CTRL_LEFT, KEYCODE_S}, 0);
        mPhoneWindowManager.assertTakeScreenshotCalled();
    }

    /**
     * META + N to expand notification panel.
     */
    @Test
    public void testMetaN() throws RemoteException {
        mPhoneWindowManager.overrideTogglePanel();
        sendKeyCombination(new int[]{KEYCODE_META_LEFT, KEYCODE_N}, 0);
        mPhoneWindowManager.assertTogglePanel();
    }

    /**
     * META + SLASH to toggle shortcuts menu.
     */
    @Test
    public void testMetaSlash() {
        mPhoneWindowManager.overrideStatusBarManagerInternal();
        sendKeyCombination(new int[]{KEYCODE_META_LEFT, KEYCODE_SLASH}, 0);
        mPhoneWindowManager.assertToggleShortcutsMenu();
    }

    /**
     * META  + ALT to toggle Cap Lock.
     */
    @Test
    public void testMetaAlt() {
        sendKeyCombination(new int[]{KEYCODE_META_LEFT, KEYCODE_ALT_LEFT}, 0);
        mPhoneWindowManager.assertToggleCapsLock();
    }

    /**
     * META + H to go to homescreen
     */
    @Test
    public void testMetaH() {
        mPhoneWindowManager.overrideLaunchHome();
        sendKeyCombination(new int[]{KEYCODE_META_LEFT, KEYCODE_H}, 0);
        mPhoneWindowManager.assertGoToHomescreen();
    }

    /**
     * META + ENTER to go to homescreen
     */
    @Test
    public void testMetaEnter() {
        mPhoneWindowManager.overrideLaunchHome();
        sendKeyCombination(new int[]{KEYCODE_META_LEFT, KEYCODE_ENTER}, 0);
        mPhoneWindowManager.assertGoToHomescreen();
    }

    /**
     * Sends a KEYCODE_BRIGHTNESS_DOWN event and validates the brightness is decreased as expected;
     */
    @Test
    public void testKeyCodeBrightnessDown() {
        float[] currentBrightness = new float[]{0.1f, 0.05f, 0.0f};
        float[] newBrightness = new float[]{0.065738f, 0.0275134f, 0.0f};

        for (int i = 0; i < currentBrightness.length; i++) {
            mPhoneWindowManager.prepareBrightnessDecrease(currentBrightness[i]);
            sendKey(KEYCODE_BRIGHTNESS_DOWN);
            mPhoneWindowManager.verifyNewBrightness(newBrightness[i]);
        }
    }

    /**
     * Sends a KEYCODE_SCREENSHOT and validates screenshot is taken if flag is enabled
     */
    @Test
    public void testTakeScreenshot_flagEnabled() {
        mSetFlagsRule.enableFlags(com.android.hardware.input.Flags
                .FLAG_EMOJI_AND_SCREENSHOT_KEYCODES_AVAILABLE);
        sendKeyCombination(new int[]{KEYCODE_SCREENSHOT}, 0);
        mPhoneWindowManager.assertTakeScreenshotCalled();
    }

    /**
     * Sends a KEYCODE_SCREENSHOT and validates screenshot is not taken if flag is disabled
     */
    @Test
    public void testTakeScreenshot_flagDisabled() {
        mSetFlagsRule.disableFlags(com.android.hardware.input.Flags
                .FLAG_EMOJI_AND_SCREENSHOT_KEYCODES_AVAILABLE);
        sendKeyCombination(new int[]{KEYCODE_SCREENSHOT}, 0);
        mPhoneWindowManager.assertTakeScreenshotNotCalled();
    }

    /**
     * META+CTRL+BACKSPACE for taking a bugreport when the flag is enabled.
     */
    @Test
    @EnableFlags(com.android.server.flags.Flags.FLAG_NEW_BUGREPORT_KEYBOARD_SHORTCUT)
    public void testTakeBugReport_flagEnabled() throws RemoteException {
        sendKeyCombination(new int[]{KEYCODE_META_LEFT, KEYCODE_CTRL_LEFT, KEYCODE_DEL}, 0);
        mPhoneWindowManager.assertTakeBugreport(true);
    }

    /**
     * META+CTRL+BACKSPACE for taking a bugreport does nothing when the flag is disabledd.
     */
    @Test
    @DisableFlags(com.android.server.flags.Flags.FLAG_NEW_BUGREPORT_KEYBOARD_SHORTCUT)
    public void testTakeBugReport_flagDisabled() throws RemoteException {
        sendKeyCombination(new int[]{KEYCODE_META_LEFT, KEYCODE_CTRL_LEFT, KEYCODE_DEL}, 0);
        mPhoneWindowManager.assertTakeBugreport(false);
    }

}
