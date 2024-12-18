/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.accessibility.accessibilitymenu.tests;

import static android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN;
import static android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS;
import static android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG;
import static android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS;
import static android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS;
import static android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT;

import static com.android.systemui.accessibility.accessibilitymenu.AccessibilityMenuService.INTENT_GLOBAL_ACTION;
import static com.android.systemui.accessibility.accessibilitymenu.AccessibilityMenuService.INTENT_GLOBAL_ACTION_EXTRA;
import static com.android.systemui.accessibility.accessibilitymenu.AccessibilityMenuService.INTENT_HIDE_MENU;
import static com.android.systemui.accessibility.accessibilitymenu.AccessibilityMenuService.INTENT_OPEN_BLOCKED;
import static com.android.systemui.accessibility.accessibilitymenu.AccessibilityMenuService.INTENT_TOGGLE_MENU;
import static com.android.systemui.accessibility.accessibilitymenu.AccessibilityMenuService.PACKAGE_NAME;

import static com.google.common.truth.Truth.assertThat;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.app.UiAutomation;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.BrightnessInfo;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.os.PowerManager;
import android.os.UserManager;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.platform.uiautomator_helpers.WaitUtils;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.Configurator;
import androidx.test.uiautomator.UiDevice;

import com.android.compatibility.common.util.TestUtils;
import com.android.systemui.accessibility.accessibilitymenu.Flags;
import com.android.systemui.accessibility.accessibilitymenu.model.A11yMenuShortcut.ShortcutId;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(AndroidJUnit4.class)
public class AccessibilityMenuServiceTest {
    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    private static final String TAG = "A11yMenuServiceTest";
    private static final int CLICK_ID = AccessibilityNodeInfo.ACTION_CLICK;

    private static final int TIMEOUT_SERVICE_STATUS_CHANGE_S = 5;
    private static final int TIMEOUT_UI_CHANGE_S = 5;
    private static final int NO_GLOBAL_ACTION = -1;
    private static final Intent INTENT_OPEN_MENU =
            new Intent(INTENT_TOGGLE_MENU).setPackage(PACKAGE_NAME);
    private static final String SERVICE_NAME = PACKAGE_NAME + "/.AccessibilityMenuService";

    private static Instrumentation sInstrumentation;
    private static UiAutomation sUiAutomation;
    private static UiDevice sUiDevice;
    private static String sLockSettings;
    private static final AtomicInteger sLastGlobalAction = new AtomicInteger(NO_GLOBAL_ACTION);
    private static final AtomicBoolean sOpenBlocked = new AtomicBoolean(false);

    private static AccessibilityManager sAccessibilityManager;
    private static PowerManager sPowerManager;
    private static KeyguardManager sKeyguardManager;
    private static DisplayManager sDisplayManager;

    @BeforeClass
    public static void classSetup() throws Throwable {
        Configurator.getInstance()
                .setUiAutomationFlags(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        sInstrumentation = InstrumentationRegistry.getInstrumentation();
        sUiAutomation = sInstrumentation.getUiAutomation(
                UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        sUiAutomation.adoptShellPermissionIdentity(
                UiAutomation.ALL_PERMISSIONS.toArray(new String[0]));
        sUiDevice = UiDevice.getInstance(sInstrumentation);
        sLockSettings = sUiDevice.executeShellCommand("locksettings get-disabled");
        Log.i(TAG, "locksettings get-disabled returns " + sLockSettings);
        // Some test in the test class requires the device to be in lock screen
        // ensure we have locksettings enabled before running the tests
        sUiDevice.executeShellCommand("locksettings set-disabled false");

        final Context context = sInstrumentation.getTargetContext();
        sAccessibilityManager = context.getSystemService(AccessibilityManager.class);
        sPowerManager = context.getSystemService(PowerManager.class);
        sKeyguardManager = context.getSystemService(KeyguardManager.class);
        sDisplayManager = context.getSystemService(DisplayManager.class);
        unlockSignal();

        enableA11yMenuService(context);

        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.i(TAG, "Received global action intent.");
                sLastGlobalAction.set(
                        intent.getIntExtra(INTENT_GLOBAL_ACTION_EXTRA, NO_GLOBAL_ACTION));
            }},
                new IntentFilter(INTENT_GLOBAL_ACTION),
                null, null, Context.RECEIVER_EXPORTED);

        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.i(TAG, "Received notification that menu cannot be opened.");
                sOpenBlocked.set(true);
            }},
                new IntentFilter(INTENT_OPEN_BLOCKED),
                null, null, Context.RECEIVER_EXPORTED);
    }

    @AfterClass
    public static void classTeardown() throws IOException {
        Settings.Secure.putString(sInstrumentation.getTargetContext().getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "");
        sUiDevice.executeShellCommand("locksettings set-disabled " + sLockSettings);
    }

    @Before
    public void setup() throws Throwable {
        sOpenBlocked.set(false);
    }

    @After
    public void tearDown() throws Throwable {
        closeMenu();
        sLastGlobalAction.set(NO_GLOBAL_ACTION);
        // Leave the device in clean state when the test finished
        unlockSignal();
        // dismisses screenshot popup if present.
        sUiDevice.pressBack();
        sUiDevice.pressHome();
    }

    private static void enableA11yMenuService(Context context) throws Throwable {
        // Disable all a11yServices if any are active.
        if (!sAccessibilityManager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK).isEmpty()) {
            Settings.Secure.putString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "");
            TestUtils.waitUntil("Failed to disable all services",
                    TIMEOUT_SERVICE_STATUS_CHANGE_S,
                    () -> sAccessibilityManager.getEnabledAccessibilityServiceList(
                            AccessibilityServiceInfo.FEEDBACK_ALL_MASK).isEmpty());
        }

        // Enable a11yMenu service.
        Settings.Secure.putString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, SERVICE_NAME);

        TestUtils.waitUntil("Failed to enable service",
                TIMEOUT_SERVICE_STATUS_CHANGE_S,
                () -> sAccessibilityManager.getEnabledAccessibilityServiceList(
                        AccessibilityServiceInfo.FEEDBACK_ALL_MASK).stream().filter(
                                info -> info.getId().contains(SERVICE_NAME)).count() == 1);
    }

    private static boolean isMenuVisible() {
        sUiDevice.waitForIdle();
        AccessibilityNodeInfo root = sUiAutomation.getRootInActiveWindow();
        return root != null && root.getPackageName().toString().equals(PACKAGE_NAME);
    }

    private static void wakeUpScreen() {
        sUiDevice.pressKeyCode(KeyEvent.KEYCODE_WAKEUP);
        WaitUtils.waitForValueToSettle("Screen On", AccessibilityMenuServiceTest::isScreenOn);
        WaitUtils.ensureThat("Screen is on", AccessibilityMenuServiceTest::isScreenOn);
    }

    private static void closeScreen() {
        // go/adb-cheats#lock-screen
        sUiDevice.pressKeyCode(KeyEvent.KEYCODE_SLEEP);
        WaitUtils.waitForValueToSettle("Screen Off", AccessibilityMenuServiceTest::isScreenOff);
        WaitUtils.ensureThat("Screen is off", AccessibilityMenuServiceTest::isScreenOff);
        WaitUtils.ensureThat(
                "Screen is locked", () -> sKeyguardManager.isKeyguardLocked());
    }

    private static void openMenu() throws Throwable {
        unlockSignal();
        if (!isMenuVisible()) {
            sInstrumentation.getTargetContext().sendBroadcast(INTENT_OPEN_MENU);
            WaitUtils.ensureThat("Accessibility Menu is visible",
                    AccessibilityMenuServiceTest::isMenuVisible);
        }
    }

    private static void closeMenu() throws Throwable {
        if (!isMenuVisible()) {
            return;
        }
        Intent intent = new Intent(INTENT_HIDE_MENU);
        intent.setPackage(PACKAGE_NAME);
        sInstrumentation.getContext().sendBroadcast(intent);
        TestUtils.waitUntil("Timed out before menu could close.",
                TIMEOUT_UI_CHANGE_S, () -> !isMenuVisible());
    }

    /**
     * Provides list of all present shortcut buttons.
     * @return List of shortcut buttons.
     */
    private List<AccessibilityNodeInfo> getGridButtonList() {
        return sUiAutomation.getRootInActiveWindow()
                        .findAccessibilityNodeInfosByViewId(PACKAGE_NAME + ":id/shortcutIconBtn");
    }

    /**
     * Returns the first button whose uniqueID matches the provided text.
     * @param buttons List of buttons.
     * @param text Text to match button's uniqueID to.
     * @return Button whose uniqueID matches text, {@code null} otherwise.
     */
    private AccessibilityNodeInfo findGridButtonInfo(
            List<AccessibilityNodeInfo> buttons, String text) {
        for (AccessibilityNodeInfo button: buttons) {
            if (button.getUniqueId().equals(text)) {
                return button;
            }
        }
        return null;
    }

    @Test
    public void testAdjustBrightness() throws Throwable {
        openMenu();
        Context context = sInstrumentation.getTargetContext();
        DisplayManager displayManager = context.getSystemService(
                DisplayManager.class);
        float resetBrightness = displayManager.getBrightness(context.getDisplayId());

        List<AccessibilityNodeInfo> buttons = getGridButtonList();
        AccessibilityNodeInfo brightnessUpButton = findGridButtonInfo(buttons,
                String.valueOf(ShortcutId.ID_BRIGHTNESS_UP_VALUE.ordinal()));
        AccessibilityNodeInfo brightnessDownButton = findGridButtonInfo(buttons,
                String.valueOf(ShortcutId.ID_BRIGHTNESS_DOWN_VALUE.ordinal()));

        BrightnessInfo brightnessInfo = displayManager.getDisplay(
                context.getDisplayId()).getBrightnessInfo();

        try {
            TestUtils.waitUntil("Could not change to minimum brightness",
                    TIMEOUT_UI_CHANGE_S,
                    () -> {
                        displayManager.setBrightness(
                                context.getDisplayId(), brightnessInfo.brightnessMinimum);
                        return displayManager.getBrightness(context.getDisplayId())
                                == brightnessInfo.brightnessMinimum;
                    });
            brightnessUpButton.performAction(CLICK_ID);
            TestUtils.waitUntil("Did not detect an increase in brightness.",
                    TIMEOUT_UI_CHANGE_S,
                    () -> displayManager.getBrightness(context.getDisplayId())
                            > brightnessInfo.brightnessMinimum);

            TestUtils.waitUntil("Could not change to maximum brightness",
                    TIMEOUT_UI_CHANGE_S,
                    () -> {
                        displayManager.setBrightness(
                                context.getDisplayId(), brightnessInfo.brightnessMaximum);
                        return displayManager.getBrightness(context.getDisplayId())
                                == brightnessInfo.brightnessMaximum;
                    });
            brightnessDownButton.performAction(CLICK_ID);
            TestUtils.waitUntil("Did not detect a decrease in brightness.",
                    TIMEOUT_UI_CHANGE_S,
                    () -> displayManager.getBrightness(context.getDisplayId())
                            < brightnessInfo.brightnessMaximum);
        } finally {
            displayManager.setBrightness(context.getDisplayId(), resetBrightness);
        }
    }

    @Test
    public void testAdjustVolume() throws Throwable {
        openMenu();
        Context context = sInstrumentation.getTargetContext();
        AudioManager audioManager = context.getSystemService(AudioManager.class);
        int resetVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);

        List<AccessibilityNodeInfo> buttons = getGridButtonList();
        AccessibilityNodeInfo volumeUpButton = findGridButtonInfo(buttons,
                String.valueOf(ShortcutId.ID_VOLUME_UP_VALUE.ordinal()));
        AccessibilityNodeInfo volumeDownButton = findGridButtonInfo(buttons,
                String.valueOf(ShortcutId.ID_VOLUME_DOWN_VALUE.ordinal()));

        try {
            int min = audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC);
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, min,
                    AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE);
            TestUtils.waitUntil("Could not change audio stream to minimum volume.",
                    TIMEOUT_UI_CHANGE_S,
                    () -> audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == min);
            volumeUpButton.performAction(CLICK_ID);
            TestUtils.waitUntil("Did not detect an increase in volume.",
                    TIMEOUT_UI_CHANGE_S,
                    () -> audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) > min);

            int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max,
                    AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE);
            TestUtils.waitUntil("Could not change audio stream to maximum volume.",
                    TIMEOUT_UI_CHANGE_S,
                    () -> audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == max);
            volumeDownButton.performAction(CLICK_ID);
            TestUtils.waitUntil("Did not detect a decrease in volume.",
                    TIMEOUT_UI_CHANGE_S,
                    () -> audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) < max);
        } finally {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC,
                    resetVolume, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE);
        }
    }

    @Test
    public void testAssistantButton_opensVoiceAssistant() throws Throwable {
        openMenu();
        AccessibilityNodeInfo assistantButton = findGridButtonInfo(getGridButtonList(),
                String.valueOf(ShortcutId.ID_ASSISTANT_VALUE.ordinal()));
        Intent expectedIntent = new Intent(Intent.ACTION_VOICE_COMMAND);
        ComponentName componentName = expectedIntent.resolveActivity(
                sInstrumentation.getContext().getPackageManager());
        Assume.assumeNotNull(componentName);
        String expectedPackage = componentName.getPackageName();

        sUiAutomation.executeAndWaitForEvent(
                () -> assistantButton.performAction(CLICK_ID),
                (event) ->
                        event.getPackageName() != null
                                && expectedPackage.contains(event.getPackageName()),
                TIMEOUT_UI_CHANGE_S * 1000
        );
    }

    @Test
    public void testAccessibilitySettingsButton_opensAccessibilitySettings() throws Throwable {
        openMenu();
        AccessibilityNodeInfo settingsButton = findGridButtonInfo(getGridButtonList(),
                String.valueOf(ShortcutId.ID_A11YSETTING_VALUE.ordinal()));
        Intent expectedIntent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        String expectedPackage = expectedIntent.resolveActivity(
                sInstrumentation.getContext().getPackageManager()).getPackageName();

        sUiAutomation.executeAndWaitForEvent(
                () -> settingsButton.performAction(CLICK_ID),
                (event) ->
                        event.getPackageName() != null
                                && expectedPackage.contains(event.getPackageName()),
                TIMEOUT_UI_CHANGE_S * 1000
        );
    }

    @Test
    public void testPowerButton_performsGlobalAction() throws Throwable {
        openMenu();
        AccessibilityNodeInfo button = findGridButtonInfo(getGridButtonList(),
                String.valueOf(ShortcutId.ID_POWER_VALUE.ordinal()));

        button.performAction(CLICK_ID);
        TestUtils.waitUntil("Did not detect Power action being performed.",
                TIMEOUT_UI_CHANGE_S,
                () -> sLastGlobalAction.compareAndSet(
                        GLOBAL_ACTION_POWER_DIALOG, NO_GLOBAL_ACTION));
    }

    @Test
    public void testRecentButton_performsGlobalAction() throws Throwable {
        openMenu();
        AccessibilityNodeInfo button = findGridButtonInfo(getGridButtonList(),
                String.valueOf(ShortcutId.ID_RECENT_VALUE.ordinal()));

        button.performAction(CLICK_ID);
        TestUtils.waitUntil("Did not detect Recents action being performed.",
                TIMEOUT_UI_CHANGE_S,
                () -> sLastGlobalAction.compareAndSet(
                        GLOBAL_ACTION_RECENTS, NO_GLOBAL_ACTION));
    }

    @Test
    public void testLockButton_performsGlobalAction() throws Throwable {
        openMenu();
        AccessibilityNodeInfo button = findGridButtonInfo(getGridButtonList(),
                String.valueOf(ShortcutId.ID_LOCKSCREEN_VALUE.ordinal()));

        button.performAction(CLICK_ID);
        TestUtils.waitUntil("Did not detect Lock action being performed.",
                TIMEOUT_UI_CHANGE_S,
                () -> sLastGlobalAction.compareAndSet(
                        GLOBAL_ACTION_LOCK_SCREEN, NO_GLOBAL_ACTION));
    }

    @Test
    public void testQuickSettingsButton_performsGlobalAction() throws Throwable {
        openMenu();
        AccessibilityNodeInfo button = findGridButtonInfo(getGridButtonList(),
                String.valueOf(ShortcutId.ID_QUICKSETTING_VALUE.ordinal()));

        button.performAction(CLICK_ID);
        TestUtils.waitUntil("Did not detect Quick Settings action being performed.",
                TIMEOUT_UI_CHANGE_S,
                () -> sLastGlobalAction.compareAndSet(
                        GLOBAL_ACTION_QUICK_SETTINGS, NO_GLOBAL_ACTION));
    }

    @Test
    public void testNotificationsButton_performsGlobalAction() throws Throwable {
        openMenu();
        AccessibilityNodeInfo button = findGridButtonInfo(getGridButtonList(),
                String.valueOf(ShortcutId.ID_NOTIFICATION_VALUE.ordinal()));

        button.performAction(CLICK_ID);
        TestUtils.waitUntil("Did not detect Notifications action being performed.",
                TIMEOUT_UI_CHANGE_S,
                () -> sLastGlobalAction.compareAndSet(
                        GLOBAL_ACTION_NOTIFICATIONS, NO_GLOBAL_ACTION));
    }

    @Test
    public void testScreenshotButton_performsGlobalAction() throws Throwable {
        openMenu();
        AccessibilityNodeInfo button = findGridButtonInfo(getGridButtonList(),
                String.valueOf(ShortcutId.ID_SCREENSHOT_VALUE.ordinal()));

        button.performAction(CLICK_ID);
        TestUtils.waitUntil("Did not detect Screenshot action being performed.",
                TIMEOUT_UI_CHANGE_S,
                () -> sLastGlobalAction.compareAndSet(
                        GLOBAL_ACTION_TAKE_SCREENSHOT, NO_GLOBAL_ACTION));
    }

    @Test
    public void testOnScreenLock_closesMenu() throws Throwable {
        openMenu();
        closeScreen();
        wakeUpScreen();
        assertThat(sKeyguardManager.isKeyguardLocked()).isTrue();

        TestUtils.waitUntil("Menu did not close.",
                TIMEOUT_UI_CHANGE_S,
                () -> !isMenuVisible()
        );
    }

    @Test
    public void testOnScreenLock_cannotOpenMenu() throws Throwable {
        closeScreen();
        wakeUpScreen();
        assertThat(sKeyguardManager.isKeyguardLocked()).isTrue();

        sInstrumentation.getContext().sendBroadcast(INTENT_OPEN_MENU);
        sUiDevice.waitForIdle();

        TestUtils.waitUntil("Did not receive signal that menu cannot open",
                TIMEOUT_UI_CHANGE_S,
                sOpenBlocked::get);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_HIDE_RESTRICTED_ACTIONS)
    public void testRestrictedActions_BrightnessNotAvailable() throws Throwable {
        try {
            setUserRestriction(UserManager.DISALLOW_CONFIG_BRIGHTNESS, true);
            openMenu();

            List<AccessibilityNodeInfo> buttons = getGridButtonList();
            AccessibilityNodeInfo brightnessUpButton = findGridButtonInfo(buttons,
                    String.valueOf(ShortcutId.ID_BRIGHTNESS_UP_VALUE.ordinal()));
            AccessibilityNodeInfo brightnessDownButton = findGridButtonInfo(buttons,
                    String.valueOf(ShortcutId.ID_BRIGHTNESS_DOWN_VALUE.ordinal()));

            assertThat(brightnessUpButton).isNull();
            assertThat(brightnessDownButton).isNull();
        } finally {
            setUserRestriction(UserManager.DISALLOW_CONFIG_BRIGHTNESS, false);
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_HIDE_RESTRICTED_ACTIONS)
    public void testRestrictedActions_VolumeNotAvailable() throws Throwable {
        try {
            setUserRestriction(UserManager.DISALLOW_ADJUST_VOLUME, true);
            openMenu();

            List<AccessibilityNodeInfo> buttons = getGridButtonList();
            AccessibilityNodeInfo volumeUpButton = findGridButtonInfo(buttons,
                    String.valueOf(ShortcutId.ID_VOLUME_UP_VALUE.ordinal()));
            AccessibilityNodeInfo volumeDownButton = findGridButtonInfo(buttons,
                    String.valueOf(ShortcutId.ID_VOLUME_DOWN_VALUE.ordinal()));

            assertThat(volumeUpButton).isNull();
            assertThat(volumeDownButton).isNull();
        } finally {
            setUserRestriction(UserManager.DISALLOW_ADJUST_VOLUME, false);
        }
    }

    private void setUserRestriction(String restriction, boolean isRestricted) throws Throwable {
        final Context context = sInstrumentation.getTargetContext();
        final UserManager userManager = context.getSystemService(UserManager.class);
        userManager.setUserRestriction(restriction, isRestricted);
    }

    private static void unlockSignal() throws IOException {
        // go/adb-cheats#unlock-screen
        wakeUpScreen();
        if (sKeyguardManager.isKeyguardLocked()) {
            sUiDevice.pressMenu();
        }
        WaitUtils.ensureThat(
                "Device unlocked & isInteractive",
                () -> isScreenOn() && !sKeyguardManager.isKeyguardLocked());
    }

    private static boolean isScreenOn() {
        int display = Display.DEFAULT_DISPLAY;
        return sPowerManager.isInteractive(display)
                && sDisplayManager.getDisplay(display).getState() == Display.STATE_ON;
    }

    private static boolean isScreenOff() {
        int display = Display.DEFAULT_DISPLAY;
        return !sPowerManager.isInteractive(display)
                && sDisplayManager.getDisplay(display).getState() == Display.STATE_OFF;
    }
}
