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

import android.app.Instrumentation;
import android.app.StatusBarManager;
import android.support.test.uiautomator.By;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.UiObject;
import android.support.test.uiautomator.UiObjectNotFoundException;
import android.support.test.uiautomator.UiSelector;
import android.support.test.uiautomator.Until;
import android.util.Log;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

/**
 * A helper class for UI-related testing tasks.
 */
final class UiBot {

    private static final String TAG = "UiBot";
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";

    private final Instrumentation mInstrumentation;
    private final UiDevice mDevice;
    private final int mTimeout;

    public UiBot(Instrumentation instrumentation, int timeout) {
        mInstrumentation = instrumentation;
        mDevice = UiDevice.getInstance(instrumentation);
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

    public void collapseStatusBar() throws Exception {
        // TODO: mDevice should provide such method..
        StatusBarManager sbm =
                (StatusBarManager) mInstrumentation.getContext().getSystemService("statusbar");
        sbm.collapsePanels();
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
     * Asserts an object is not visible.
     */
    public void assertNotVisibleById(String id) {
        // TODO: not working when the bugreport dialog is shown, it hangs until the dialog is
        // dismissed and hence always work.
        boolean hasIt = mDevice.hasObject(By.res(id));
        assertFalse("should not have found object with id '" + id+ "'", hasIt);
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
     * Chooses a given activity to handle an Intent.
     *
     * @param name name of the activity as displayed in the UI (typically the value set by
     *            {@code android:label} in the manifest).
     */
    public void chooseActivity(String name) {
        // It uses an intent chooser now, so just getting the activity by text is enough...
        UiObject activity = getObject(name);
        click(activity, name);
    }

    public void pressBack() {
        mDevice.pressBack();
    }

    public void turnScreenOn() throws Exception {
        mDevice.executeShellCommand("input keyevent KEYCODE_WAKEUP");
        mDevice.executeShellCommand("wm dismiss-keyguard");
    }

}
