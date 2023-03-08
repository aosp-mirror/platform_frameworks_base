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

import static com.android.systemui.accessibility.accessibilitymenu.AccessibilityMenuService.INTENT_HIDE_MENU;
import static com.android.systemui.accessibility.accessibilitymenu.AccessibilityMenuService.INTENT_TOGGLE_MENU;
import static com.android.systemui.accessibility.accessibilitymenu.AccessibilityMenuService.PACKAGE_NAME;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.BrightnessInfo;
import android.hardware.display.DisplayManager;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.TestUtils;
import com.android.systemui.accessibility.accessibilitymenu.model.A11yMenuShortcut.ShortcutId;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@RunWith(AndroidJUnit4.class)
public class AccessibilityMenuServiceTest {
    private static final String TAG = "A11yMenuServiceTest";

    private static final int TIMEOUT_SERVICE_STATUS_CHANGE_S = 5;
    private static final int TIMEOUT_UI_CHANGE_S = 5;

    private static Instrumentation sInstrumentation;
    private static UiAutomation sUiAutomation;

    private static AccessibilityManager sAccessibilityManager;

    @BeforeClass
    public static void classSetup() throws Throwable {
        final String serviceName = PACKAGE_NAME + "/.AccessibilityMenuService";
        sInstrumentation = InstrumentationRegistry.getInstrumentation();
        sUiAutomation = sInstrumentation.getUiAutomation(
                UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        final Context context = sInstrumentation.getContext();
        sAccessibilityManager = context.getSystemService(AccessibilityManager.class);

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
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, serviceName);

        TestUtils.waitUntil("Failed to enable service",
                TIMEOUT_SERVICE_STATUS_CHANGE_S,
                () -> sAccessibilityManager.getEnabledAccessibilityServiceList(
                        AccessibilityServiceInfo.FEEDBACK_ALL_MASK).stream().filter(
                                info -> info.getId().contains(serviceName)).count() == 1);
    }

    @AfterClass
    public static void classTeardown() throws Throwable {
        Settings.Secure.putString(sInstrumentation.getTargetContext().getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "");
    }

    private boolean isMenuVisible() {
        return sUiAutomation.getRootInActiveWindow() != null
                && sUiAutomation.getRootInActiveWindow().getPackageName().toString().equals(
                PACKAGE_NAME);
    }

    private void openMenu() throws Throwable {
        if (isMenuVisible()) {
            return;
        }
        Intent intent = new Intent(PACKAGE_NAME + INTENT_TOGGLE_MENU);
        sInstrumentation.getContext().sendBroadcast(intent);
        TestUtils.waitUntil("Timed out before menu could appear.",
                TIMEOUT_UI_CHANGE_S, () -> isMenuVisible());
    }

    private void closeMenu() throws Throwable {
        if (!isMenuVisible()) {
            return;
        }
        Intent intent = new Intent(PACKAGE_NAME + INTENT_HIDE_MENU);
        sInstrumentation.getContext().sendBroadcast(intent);
        TestUtils.waitUntil("Timed out before menu could close.",
                TIMEOUT_UI_CHANGE_S, () -> !isMenuVisible());
    }

    private List<AccessibilityNodeInfo> getGridButtonList() {
        return sUiAutomation.getRootInActiveWindow()
                        .findAccessibilityNodeInfosByViewId(PACKAGE_NAME + ":id/shortcutIconBtn");
    }

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

        int clickId = AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK.getId();
        BrightnessInfo brightnessInfo = displayManager.getDisplay(
                context.getDisplayId()).getBrightnessInfo();

        try {
            displayManager.setBrightness(context.getDisplayId(), brightnessInfo.brightnessMinimum);
            TestUtils.waitUntil("Could not change to minimum brightness",
                    TIMEOUT_UI_CHANGE_S,
                    () -> displayManager.getBrightness(context.getDisplayId())
                            == brightnessInfo.brightnessMinimum);
            brightnessUpButton.performAction(clickId);
            TestUtils.waitUntil("Did not detect an increase in brightness.",
                    TIMEOUT_UI_CHANGE_S,
                    () -> displayManager.getBrightness(context.getDisplayId())
                            > brightnessInfo.brightnessMinimum);

            displayManager.setBrightness(context.getDisplayId(), brightnessInfo.brightnessMaximum);
            TestUtils.waitUntil("Could not change to maximum brightness",
                    TIMEOUT_UI_CHANGE_S,
                    () -> displayManager.getBrightness(context.getDisplayId())
                            == brightnessInfo.brightnessMaximum);
            brightnessDownButton.performAction(clickId);
            TestUtils.waitUntil("Did not detect a decrease in brightness.",
                    TIMEOUT_UI_CHANGE_S,
                    () -> displayManager.getBrightness(context.getDisplayId())
                            < brightnessInfo.brightnessMaximum);
        } finally {
            displayManager.setBrightness(context.getDisplayId(), resetBrightness);
            closeMenu();
        }
    }
}
