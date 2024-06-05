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
import android.os.SystemClock;
import android.text.format.DateUtils;
import android.util.Log;

import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.UiObjectNotFoundException;
import androidx.test.uiautomator.UiSelector;
import androidx.test.uiautomator.Until;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertTrue;

import java.util.List;

/**
 * A helper class for UI-related testing tasks.
 */
final class UiBot {

    private static final String TAG = "UiBot";
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";
    private static final String ANDROID_PACKAGE = "android";

    private static final long SHORT_UI_TIMEOUT_MS = (3 * DateUtils.SECOND_IN_MILLIS);

    private final Instrumentation mInstrumentation;
    private final UiDevice mDevice;
    private final int mTimeout;

    public UiBot(Instrumentation instrumentation, int timeout) {
        mInstrumentation = instrumentation;
        mDevice = UiDevice.getInstance(instrumentation);
        mTimeout = timeout;
    }

    /**
     * Opens the system notification and gets a UiObject with the text.
     *
     * @param text Notification's text as displayed by the UI.
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
     * Opens the system notification and gets a notification containing the text.
     *
     * @param text Notification's text as displayed by the UI.
     * @return notification object.
     */
    public UiObject2 getNotification2(String text) {
        boolean opened = mDevice.openNotification();
        Log.v(TAG, "openNotification(): " + opened);
        final UiObject2 notificationScroller = mDevice.wait(Until.findObject(
                By.res(SYSTEMUI_PACKAGE, "notification_stack_scroller")), mTimeout);
        assertNotNull("could not get notification stack scroller", notificationScroller);
        final List<UiObject2> notificationList = notificationScroller.getChildren();
        for (UiObject2 notification: notificationList) {
            final UiObject2 notificationText = notification.findObject(By.textContains(text));
            if (notificationText != null) {
                return notification;
            }
        }
        return null;
    }

    /**
     * Expands the notification.
     *
     * @param notification The notification object returned by {@link #getNotification2(String)}.
     */
    public void expandNotification(UiObject2 notification) {
        final UiObject2 expandBtn =  notification.findObject(
                By.res(ANDROID_PACKAGE, "expand_button"));
        if (expandBtn.getContentDescription().equals("Collapse")) {
            return;
        }
        expandBtn.click();
        mDevice.waitForIdle();
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
        final String share = mInstrumentation.getContext().getString(
                com.android.internal.R.string.share);
        boolean gotIt = mDevice.wait(Until.hasObject(By.text(share)), mTimeout);
        assertTrue("could not get share activity (" + share + ")", gotIt);
        swipeUp();
        SystemClock.sleep(SHORT_UI_TIMEOUT_MS);
        UiObject activity = getObject(name);
        click(activity, name);
    }

    public void pressBack() {
        mDevice.pressBack();
    }

    public void turnScreenOn() throws Exception {
        mDevice.executeShellCommand("input keyevent KEYCODE_WAKEUP");
        mDevice.executeShellCommand("wm dismiss-keyguard");
        mDevice.waitForIdle();
    }

    public void swipeUp() {
        mDevice.swipe(mDevice.getDisplayWidth() / 2, mDevice.getDisplayHeight() * 3 / 4,
                mDevice.getDisplayWidth() / 2, 0, 30);
    }
}
