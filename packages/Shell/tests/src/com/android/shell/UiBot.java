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

package com.android.shell;

import android.support.test.uiautomator.By;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.UiObject;
import android.support.test.uiautomator.UiObjectNotFoundException;
import android.support.test.uiautomator.UiSelector;
import android.support.test.uiautomator.Until;
import android.util.Log;
import static junit.framework.Assert.assertTrue;

/**
 * A helper class for UI-related testing tasks.
 */
final class UiBot {

    private static final String TAG = "UiBot";
    private static final String SYSTEMUI_PACKAGED = "com.android.systemui";

    private final UiDevice mDevice;
    private final int mTimeout;

    public UiBot(UiDevice device, int timeout) {
        mDevice = device;
        mTimeout = timeout;
    }

    /**
     * Opens the system notification and clicks a given notification.
     *
     * @param text Notificaton's text as displayed by the UI.
     */
    public void clickOnNotification(String text) {
        boolean opened = mDevice.openNotification();
        Log.v(TAG, "openNotification(): " + opened);
        boolean gotIt = mDevice.wait(Until.hasObject(By.pkg(SYSTEMUI_PACKAGED)), mTimeout);
        assertTrue("could not get system ui (" + SYSTEMUI_PACKAGED + ")", gotIt);

        gotIt = mDevice.wait(Until.hasObject(By.text(text)), mTimeout);
        assertTrue("object with text '(" + text + "') not visible yet", gotIt);

        UiObject notification = getVisibleObject(text);

        click(notification, "bug report notification");
    }

    /**
     * Gets an object which is guaranteed to be present in the current UI.\
     *
     * @param text Object's text as displayed by the UI.
     */
    public UiObject getVisibleObject(String text) {
        UiObject uiObject = mDevice.findObject(new UiSelector().text(text));
        assertTrue("could not find object with text '(" + text + "')", uiObject.exists());
        return uiObject;
    }

    /**
     * Clicks on a UI element.
     *
     * @param uiObject UI element to be clicked.
     * @param description Elements's description used on logging statements.
     */
    public void click(UiObject uiObject, String description) {
        try {
            boolean clicked = uiObject.click();
            // TODO: assertion below fails sometimes, even though the click succeeded,
            // (specially when clicking the "Just Once" button), so it's currently just logged.
            // assertTrue("could not click on object '" + description + "'", clicked);

            Log.v(TAG, "onClick for " + description + ": " + clicked);
        } catch (UiObjectNotFoundException e) {
            throw new IllegalStateException("exception when clicking on object '" + description
                    + "'", e);
        }
    }

    /**
     * Chooses a given activity to handle an Intent, using the "Just Once" button.
     *
     * @param name name of the activity as displayed in the UI (typically the value set by
     *            {@code android:label} in the manifest).
     */
    // TODO: UI Automator should provide such logic.
    public void chooseActivity(String name) {
        // First select activity if it's not the default option.
        boolean gotIt = mDevice.wait(Until.hasObject(By.text(name)), mTimeout);
        // TODO: if the activity is indeed the default option, call above will timeout, which will
        // make the tests run slower. It might be better to change the logic to assume the default
        // first.
        if (gotIt) {
            Log.v(TAG, "Found activity " + name + ", it's not default action");
            UiObject activityChooser = getVisibleObject(name);
            click(activityChooser, "activity chooser");
        } else {
            String text = String.format("Share with %s", name);
            Log.v(TAG, "Didn't find activity " + name
                    + ", assuming it's the default action and search for '" + text + "'");
            gotIt = mDevice.wait(Until.hasObject(By.text(text)), mTimeout);
            assertTrue("did not find text '" + text + "'", gotIt);
        }

        // Then clicks the "Just Once" button.
        gotIt = mDevice
                .wait(Until.hasObject(By.res("android", "button_once")), mTimeout);
        assertTrue("'Just Once' button not visible yet", gotIt);

        UiObject justOnce = mDevice
                .findObject(new UiSelector().resourceId("android:id/button_once"));
        assertTrue("'Just Once' button not found", justOnce.exists());

        click(justOnce, "Just Once");
    }
}
