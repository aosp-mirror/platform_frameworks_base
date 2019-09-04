/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wm.flicker;

import static android.os.SystemClock.sleep;
import static android.system.helpers.OverviewHelper.isRecentsInLauncher;
import static android.view.Surface.ROTATION_0;

import static com.android.compatibility.common.util.SystemUtil.runShellCommand;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.RemoteException;
import android.support.test.launcherhelper.LauncherStrategyFactory;
import android.support.test.uiautomator.By;
import android.support.test.uiautomator.BySelector;
import android.support.test.uiautomator.Configurator;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.UiObject2;
import android.support.test.uiautomator.Until;
import android.util.Log;
import android.util.Rational;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.test.InstrumentationRegistry;

/**
 * Collection of UI Automation helper functions.
 */
public class AutomationUtils {
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";
    private static final long FIND_TIMEOUT = 10000;
    private static final long LONG_PRESS_TIMEOUT = ViewConfiguration.getLongPressTimeout() * 2L;
    private static final String TAG = "FLICKER";

    public static void wakeUpAndGoToHomeScreen() {
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry
                .getInstrumentation());
        try {
            device.wakeUp();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
        device.pressHome();
    }

    /**
     * Sets {@link android.app.UiAutomation#waitForIdle(long, long)} global timeout to 0 causing
     * the {@link android.app.UiAutomation#waitForIdle(long, long)} function to timeout instantly.
     * This removes some delays when using the UIAutomator library required to create fast UI
     * transitions.
     */
    static void setFastWait() {
        Configurator.getInstance().setWaitForIdleTimeout(0);
    }

    /**
     * Reverts {@link android.app.UiAutomation#waitForIdle(long, long)} to default behavior.
     */
    static void setDefaultWait() {
        Configurator.getInstance().setWaitForIdleTimeout(10000);
    }

    public static boolean isQuickstepEnabled(UiDevice device) {
        return device.findObject(By.res(SYSTEMUI_PACKAGE, "recent_apps")) == null;
    }

    public static void openQuickstep(UiDevice device) {
        if (isQuickstepEnabled(device)) {
            int height = device.getDisplayHeight();
            UiObject2 navBar = device.findObject(By.res(SYSTEMUI_PACKAGE, "navigation_bar_frame"));

            Rect navBarVisibleBounds;

            // TODO(vishnun) investigate why this object cannot be found.
            if (navBar != null) {
                navBarVisibleBounds = navBar.getVisibleBounds();
            } else {
                Log.e(TAG, "Could not find nav bar, infer location");
                navBarVisibleBounds = WindowUtils.getNavigationBarPosition(ROTATION_0);
            }

            // Swipe from nav bar to 2/3rd down the screen.
            device.swipe(
                    navBarVisibleBounds.centerX(), navBarVisibleBounds.centerY(),
                    navBarVisibleBounds.centerX(), height * 2 / 3,
                    (navBarVisibleBounds.centerY() - height * 2 / 3) / 100); // 100 px/step
        } else {
            try {
                device.pressRecentApps();
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        }
        BySelector RECENTS = By.res(SYSTEMUI_PACKAGE, "recents_view");

        // use a long timeout to wait until recents populated
        if (device.wait(
                Until.findObject(isRecentsInLauncher()
                        ? getLauncherOverviewSelector(device) : RECENTS),
                10000) == null) {
            fail("Recents didn't appear");
        }
        device.waitForIdle();
    }

    static void clearRecents(UiDevice device) {
        if (isQuickstepEnabled(device)) {
            openQuickstep(device);

            for (int i = 0; i < 5; i++) {
                device.swipe(device.getDisplayWidth() / 2,
                        device.getDisplayHeight() / 2, device.getDisplayWidth(),
                        device.getDisplayHeight() / 2,
                        5);

                BySelector clearAllSelector = By.res("com.google.android.apps.nexuslauncher",
                        "clear_all_button");
                UiObject2 clearAllButton = device.wait(Until.findObject(clearAllSelector), 100);
                if (clearAllButton != null) {
                    clearAllButton.click();
                    return;
                }
            }
        }
    }

    private static BySelector getLauncherOverviewSelector(UiDevice device) {
        return By.res(device.getLauncherPackageName(), "overview_panel");
    }

    private static void longPressRecents(UiDevice device) {
        BySelector recentsSelector = By.res(SYSTEMUI_PACKAGE, "recent_apps");
        UiObject2 recentsButton = device.wait(Until.findObject(recentsSelector), FIND_TIMEOUT);
        assertNotNull("Unable to find recents button", recentsButton);
        recentsButton.click(LONG_PRESS_TIMEOUT);
    }

    public static void launchSplitScreen(UiDevice device) {
        String mLauncherPackage = LauncherStrategyFactory.getInstance(device)
                .getLauncherStrategy().getSupportedLauncherPackage();

        if (isQuickstepEnabled(device)) {
            // Quickstep enabled
            openQuickstep(device);

            BySelector overviewIconSelector = By.res(mLauncherPackage, "icon")
                    .clazz(View.class);
            UiObject2 overviewIcon = device.wait(Until.findObject(overviewIconSelector),
                    FIND_TIMEOUT);
            assertNotNull("Unable to find app icon in Overview", overviewIcon);
            overviewIcon.click();

            BySelector splitscreenButtonSelector = By.text("Split screen");
            UiObject2 splitscreenButton = device.wait(Until.findObject(splitscreenButtonSelector),
                    FIND_TIMEOUT);
            assertNotNull("Unable to find Split screen button in Overview", splitscreenButton);
            splitscreenButton.click();
        } else {
            // Classic long press recents
            longPressRecents(device);
        }
        // Wait for animation to complete.
        sleep(2000);
    }

    public static void exitSplitScreen(UiDevice device) {
        if (isQuickstepEnabled(device)) {
            // Quickstep enabled
            BySelector dividerSelector = By.res(SYSTEMUI_PACKAGE, "docked_divider_handle");
            UiObject2 divider = device.wait(Until.findObject(dividerSelector), FIND_TIMEOUT);
            assertNotNull("Unable to find Split screen divider", divider);

            // Drag the split screen divider to the top of the screen
            divider.drag(new Point(device.getDisplayWidth() / 2, 0), 400);
        } else {
            // Classic long press recents
            longPressRecents(device);
        }
        // Wait for animation to complete.
        sleep(2000);
    }

    static void resizeSplitScreen(UiDevice device, Rational windowHeightRatio) {
        BySelector dividerSelector = By.res(SYSTEMUI_PACKAGE, "docked_divider_handle");
        UiObject2 divider = device.wait(Until.findObject(dividerSelector), FIND_TIMEOUT);
        assertNotNull("Unable to find Split screen divider", divider);
        int destHeight =
                (int) (WindowUtils.getDisplayBounds().height() * windowHeightRatio.floatValue());
        // Drag the split screen divider to so that the ratio of top window height and bottom
        // window height is windowHeightRatio
        device.drag(divider.getVisibleBounds().centerX(), divider.getVisibleBounds().centerY(),
                device.getDisplayWidth() / 2, destHeight, 10);
        //divider.drag(new Point(device.getDisplayWidth() / 2, destHeight), 400)
        divider = device.wait(Until.findObject(dividerSelector), FIND_TIMEOUT);

        // Wait for animation to complete.
        sleep(2000);
    }

    static void closePipWindow(UiDevice device) {
        UiObject2 pipWindow = device.findObject(
                By.res(SYSTEMUI_PACKAGE, "background"));
        pipWindow.click();
        UiObject2 exitPipObject = device.findObject(
                By.res(SYSTEMUI_PACKAGE, "dismiss"));
        exitPipObject.click();
        // Wait for animation to complete.
        sleep(2000);
    }

    static void expandPipWindow(UiDevice device) {
        UiObject2 pipWindow = device.findObject(
                By.res(SYSTEMUI_PACKAGE, "background"));
        pipWindow.click();
        pipWindow.click();
    }

    public static void stopPackage(Context context, String packageName) {
        runShellCommand("am force-stop " + packageName);
        int packageUid;
        try {
            packageUid = context.getPackageManager().getPackageUid(packageName, /* flags= */0);
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }
        while (targetPackageIsRunning(packageUid)) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                //ignore
            }
        }
    }

    private static boolean targetPackageIsRunning(int uid) {
        final String result = runShellCommand(
                String.format("cmd activity get-uid-state %d", uid));
        return !result.contains("(NONEXISTENT)");
    }
}