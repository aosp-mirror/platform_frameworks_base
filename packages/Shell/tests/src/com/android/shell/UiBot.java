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
import android.support.test.uiautomator.UiScrollable;
import android.support.test.uiautomator.UiSelector;
import android.support.test.uiautomator.Until;
import android.util.Log;
import static junit.framework.Assert.assertTrue;

/**
 * A helper class for UI-related testing tasks.
 */
final class UiBot {

    private static final String TAG = "UiBot";
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";

    private final UiDevice mDevice;
    private final int mTimeout;

    public UiBot(UiDevice device, int timeout) {
        mDevice = device;
        mTimeout = timeout;
    }

    /**
     * Opens the system notification and gets a given notification.
     *
     * @param text Notificaton's text as displayed by the UI.
     * @return notification object.
     */
    public UiObject getNotification(String text) {
        boolean opened = mDevice.openNotification();
        Log.v(TAG, "openNotification(): " + opened);
        boolean gotIt = mDevice.wait(Until.hasObject(By.pkg(SYSTEMUI_PACKAGE)), mTimeout);
        assertTrue("could not get system ui (" + SYSTEMUI_PACKAGE + ")", gotIt);

        return getObject(text);
    }

    /**
     * Opens the system notification and clicks a given notification.
     *
     * @param text Notificaton's text as displayed by the UI.
     */
    public void clickOnNotification(String text) {
        UiObject notification = getNotification(text);
        click(notification, "bug report notification");
    }

    /**
     * Gets an object that might not yet be available in current UI.
     *
     * @param text Object's text as displayed by the UI.
     */
    public UiObject getObject(String text) {
        boolean gotIt = mDevice.wait(Until.hasObject(By.text(text)), mTimeout);
        assertTrue("object with text '(" + text + "') not visible yet", gotIt);
        return getVisibleObject(text);
    }

    /**
     * Gets an object that might not yet be available in current UI.
     *
     * @param id Object's fully-qualified resource id (like {@code android:id/button1})
     */
    public UiObject getObjectById(String id) {
        boolean gotIt = mDevice.wait(Until.hasObject(By.res(id)), mTimeout);
        assertTrue("object with id '(" + id + "') not visible yet", gotIt);
        return getVisibleObjectById(id);
    }

    /**
     * Gets an object which is guaranteed to be present in the current UI.
     *
     * @param text Object's text as displayed by the UI.
     */
    public UiObject getVisibleObject(String text) {
        UiObject uiObject = mDevice.findObject(new UiSelector().text(text));
        assertTrue("could not find object with text '" + text + "'", uiObject.exists());
        return uiObject;
    }

    /**
     * Gets an object which is guaranteed to be present in the current UI.
     *
     * @param text Object's text as displayed by the UI.
     */
    public UiObject getVisibleObjectById(String id) {
        UiObject uiObject = mDevice.findObject(new UiSelector().resourceId(id));
        assertTrue("could not find object with id '" + id+ "'", uiObject.exists());
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
        // First check if the activity is the default option.
        String shareText = "Share with " + name;
        Log.v(TAG, "Waiting for ActivityChooser text: '" + shareText + "'");
        boolean gotIt = mDevice.wait(Until.hasObject(By.text(shareText)), mTimeout);
        boolean justOnceHack = false;

        if (gotIt) {
            Log.v(TAG, "Found activity " + name + ", it's the default action");
            clickJustOnce();
        } else {
            // Since it's not, need to find it in the scrollable list...
            Log.v(TAG, "Activity " + name + " is not default action");
            UiScrollable activitiesList = new UiScrollable(new UiSelector().scrollable(true));
            try {
                activitiesList.scrollForward();
            } catch (UiObjectNotFoundException e) {
                // TODO: for some paranormal issue, the first time a test is run the scrollable
                // activity list is displayed but calling scrollForwad() (or even isScrollable())
                // throws a "UiObjectNotFoundException: UiSelector[SCROLLABLE=true]" exception
                justOnceHack = true;
                Log.d(TAG, "could not scroll forward", e);
            }
            UiObject activity = getVisibleObject(name);
            // ... then select it.
            click(activity, name);
            if (justOnceHack) {
                clickJustOnce();
            }
        }
    }

    private void clickJustOnce() {
        boolean gotIt = mDevice.wait(Until.hasObject(By.res("android", "button_once")), mTimeout);
        assertTrue("'Just Once' button not visible yet", gotIt);

        UiObject justOnce = mDevice
                .findObject(new UiSelector().resourceId("android:id/button_once"));
        assertTrue("'Just Once' button not found", justOnce.exists());

        click(justOnce, "Just Once");
    }

    public void pressBack() {
        mDevice.pressBack();
    }
}
