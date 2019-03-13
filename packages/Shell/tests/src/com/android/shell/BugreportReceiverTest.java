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

import static android.test.MoreAsserts.assertContainsRegex;

import static com.android.shell.ActionSendMultipleConsumerActivity.UI_NAME;
import static com.android.shell.BugreportPrefs.PREFS_BUGREPORT;
import static com.android.shell.BugreportPrefs.STATE_HIDE;
import static com.android.shell.BugreportPrefs.STATE_SHOW;
import static com.android.shell.BugreportPrefs.STATE_UNKNOWN;
import static com.android.shell.BugreportPrefs.getWarningState;
import static com.android.shell.BugreportPrefs.setWarningState;
import static com.android.shell.BugreportProgressService.EXTRA_BUGREPORT;
import static com.android.shell.BugreportProgressService.EXTRA_ID;
import static com.android.shell.BugreportProgressService.EXTRA_MAX;
import static com.android.shell.BugreportProgressService.EXTRA_NAME;
import static com.android.shell.BugreportProgressService.EXTRA_PID;
import static com.android.shell.BugreportProgressService.EXTRA_SCREENSHOT;
import static com.android.shell.BugreportProgressService.INTENT_BUGREPORT_FINISHED;
import static com.android.shell.BugreportProgressService.INTENT_BUGREPORT_STARTED;
import static com.android.shell.BugreportProgressService.SCREENSHOT_DELAY_SECONDS;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningServiceInfo;
import android.app.Instrumentation;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.service.notification.StatusBarNotification;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.UiObject;
import android.support.test.uiautomator.UiObjectNotFoundException;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.shell.ActionSendMultipleConsumerActivity.CustomActionSendMultipleListener;

import libcore.io.Streams;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Integration tests for {@link BugreportReceiver}.
 * <p>
 * These tests don't mock any component and rely on external UI components (like the notification
 * bar and activity chooser), which can make them unreliable and slow.
 * <p>
 * The general workflow is:
 * <ul>
 * <li>creates the bug report files
 * <li>generates the BUGREPORT_FINISHED intent
 * <li>emulate user actions to share the intent with a custom activity
 * <li>asserts the extras received by the custom activity
 * </ul>
 * <p>
 * <strong>NOTE</strong>: these tests only work if the device is unlocked.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class BugreportReceiverTest {
    private static final String TAG = "BugreportReceiverTest";

    // Timeout for UI operations, in milliseconds.
    private static final int TIMEOUT = (int) (5 * DateUtils.SECOND_IN_MILLIS);

    // Timeout for when waiting for a screenshot to finish.
    private static final int SAFE_SCREENSHOT_DELAY = SCREENSHOT_DELAY_SECONDS + 10;

    private static final String BUGREPORTS_DIR = "bugreports";
    private static final String BUGREPORT_FILE = "test_bugreport.txt";
    private static final String ZIP_FILE = "test_bugreport.zip";
    private static final String ZIP_FILE2 = "test_bugreport2.zip";
    private static final String SCREENSHOT_FILE = "test_screenshot.png";

    private static final String BUGREPORT_CONTENT = "Dump, might as well dump!\n";
    private static final String SCREENSHOT_CONTENT = "A picture is worth a thousand words!\n";

    private static final int PID = 42;
    private static final int PID2 = 24;
    private static final int ID = 108;
    private static final int ID2 = 801;
    private static final String PROGRESS_PROPERTY = "dumpstate." + PID + ".progress";
    private static final String MAX_PROPERTY = "dumpstate." + PID + ".max";
    private static final String NAME_PROPERTY = "dumpstate." + PID + ".name";
    private static final String NAME = "BUG, Y U NO REPORT?";
    private static final String NAME2 = "A bugreport's life";
    private static final String NEW_NAME = "Bug_Forrest_Bug";
    private static final String NEW_NAME2 = "BugsyReportsy";
    private static final String TITLE = "Wimbugdom Champion 2015";
    private static final String TITLE2 = "Master of the Universe";
    private static final String DESCRIPTION = "One's description...";
    private static final String DESCRIPTION2 = "...is another's treasure.";

    private static final String NO_DESCRIPTION = null;
    private static final String NO_NAME = null;
    private static final String NO_SCREENSHOT = null;
    private static final String NO_TITLE = null;
    private static final int NO_ID = 0;
    private static final boolean RENAMED_SCREENSHOTS = true;
    private static final boolean DIDNT_RENAME_SCREENSHOTS = false;

    private String mDescription;

    private String mPlainTextPath;
    private String mZipPath;
    private String mZipPath2;
    private String mScreenshotPath;

    private Context mContext;
    private UiBot mUiBot;
    private CustomActionSendMultipleListener mListener;

    @Rule public TestName mName = new TestName();

    @Before
    public void setUp() throws Exception {
        Log.i(TAG, getName() + ".setup()");
        Instrumentation instrumentation = getInstrumentation();
        mContext = instrumentation.getTargetContext();
        mUiBot = new UiBot(instrumentation, TIMEOUT);
        mListener = ActionSendMultipleConsumerActivity.getListener(mContext);

        cancelExistingNotifications();

        mPlainTextPath = getPath(BUGREPORT_FILE);
        mZipPath = getPath(ZIP_FILE);
        mZipPath2 = getPath(ZIP_FILE2);
        mScreenshotPath = getPath(SCREENSHOT_FILE);
        createTextFile(mPlainTextPath, BUGREPORT_CONTENT);
        createTextFile(mScreenshotPath, SCREENSHOT_CONTENT);
        createZipFile(mZipPath, BUGREPORT_FILE, BUGREPORT_CONTENT);
        createZipFile(mZipPath2, BUGREPORT_FILE, BUGREPORT_CONTENT);

        // Creates a multi-line description.
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            sb.append("All work and no play makes Shell a dull app!\n");
        }
        mDescription = sb.toString();

        setWarningState(mContext, STATE_HIDE);

        mUiBot.turnScreenOn();
    }

    @After
    public void tearDown() throws Exception {
        Log.i(TAG, getName() + ".tearDown()");
        try {
            cancelExistingNotifications();
        } finally {
            // Collapses just in case, so a failure here does not compromise tests on other classes.
            mUiBot.collapseStatusBar();
        }
    }

    /*
     * TODO: this test is incomplete because:
     * - the assertProgressNotification() is not really asserting the progress because the
     *   UI automation API doesn't provide a way to check the notification progress bar value
     * - it should use the binder object instead of SystemProperties to update progress
     */
    @Test
    public void testProgress() throws Exception {
        resetProperties();
        sendBugreportStarted(1000);
        waitForScreenshotButtonEnabled(true);

        assertProgressNotification(NAME, 0f);

        SystemProperties.set(PROGRESS_PROPERTY, "108");
        assertProgressNotification(NAME, 10.80f);

        assertProgressNotification(NAME, 50.00f);

        SystemProperties.set(PROGRESS_PROPERTY, "950");
        assertProgressNotification(NAME, 95.00f);

        // Make sure progress never goes back...
        SystemProperties.set(MAX_PROPERTY, "2000");
        assertProgressNotification(NAME, 95.00f);

        SystemProperties.set(PROGRESS_PROPERTY, "1000");
        assertProgressNotification(NAME, 95.00f);

        // ...only forward...
        SystemProperties.set(PROGRESS_PROPERTY, "1902");
        assertProgressNotification(NAME, 95.10f);

        SystemProperties.set(PROGRESS_PROPERTY, "1960");
        assertProgressNotification(NAME, 98.00f);

        // ...but never more than the capped value.
        SystemProperties.set(PROGRESS_PROPERTY, "2000");
        assertProgressNotification(NAME, 99.00f);

        SystemProperties.set(PROGRESS_PROPERTY, "3000");
        assertProgressNotification(NAME, 99.00f);

        Bundle extras =
                sendBugreportFinishedAndGetSharedIntent(ID, mPlainTextPath, mScreenshotPath);
        assertActionSendMultiple(extras, BUGREPORT_CONTENT, SCREENSHOT_CONTENT, ID, PID, ZIP_FILE,
                NAME, NO_TITLE, NO_DESCRIPTION, 0, RENAMED_SCREENSHOTS);

        assertServiceNotRunning();
    }

    @Test
    public void testProgress_cancel() throws Exception {
        resetProperties();
        sendBugreportStarted(1000);
        waitForScreenshotButtonEnabled(true);

        final NumberFormat nf = NumberFormat.getPercentInstance();
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);

        assertProgressNotification(NAME, 00.00f);

        cancelFromNotification();

        waitForService(false);
    }

    @Test
    public void testProgress_takeExtraScreenshot() throws Exception {
        resetProperties();
        sendBugreportStarted(1000);

        waitForScreenshotButtonEnabled(true);
        takeScreenshot();
        assertScreenshotButtonEnabled(false);
        waitForScreenshotButtonEnabled(true);

        sendBugreportFinished(ID, mPlainTextPath, mScreenshotPath);

        Bundle extras = acceptBugreportAndGetSharedIntent(ID);
        assertActionSendMultiple(extras, BUGREPORT_CONTENT, SCREENSHOT_CONTENT, ID, PID, ZIP_FILE,
                NAME, NO_TITLE, NO_DESCRIPTION, 1, RENAMED_SCREENSHOTS);

        assertServiceNotRunning();
    }

    @Test
    public void testScreenshotFinishesAfterBugreport() throws Exception {
        resetProperties();

        sendBugreportStarted(1000);
        waitForScreenshotButtonEnabled(true);
        takeScreenshot();
        sendBugreportFinished(ID, mPlainTextPath, NO_SCREENSHOT);
        waitShareNotification(ID);

        // There's no indication in the UI about the screenshot finish, so just sleep like a baby...
        sleep(SAFE_SCREENSHOT_DELAY * DateUtils.SECOND_IN_MILLIS);

        Bundle extras = acceptBugreportAndGetSharedIntent(ID);
        assertActionSendMultiple(extras, BUGREPORT_CONTENT, NO_SCREENSHOT, ID, PID, ZIP_FILE,
                NAME, NO_TITLE, NO_DESCRIPTION, 1, RENAMED_SCREENSHOTS);

        assertServiceNotRunning();
    }

    @Test
    public void testProgress_changeDetailsInvalidInput() throws Exception {
        resetProperties();
        sendBugreportStarted(1000);
        waitForScreenshotButtonEnabled(true);

        DetailsUi detailsUi = new DetailsUi(mUiBot, ID, NAME);

        // Check initial name.
        detailsUi.assertName(NAME);

        // Change name - it should have changed system property once focus is changed.
        detailsUi.focusOnName();
        detailsUi.nameField.setText(NEW_NAME);
        detailsUi.focusAwayFromName();
        assertPropertyValue(NAME_PROPERTY, NEW_NAME);

        // Cancel the dialog to make sure property was restored.
        detailsUi.clickCancel();
        assertPropertyValue(NAME_PROPERTY, NAME);

        // Now try to set an invalid name.
        detailsUi.reOpen(NAME);
        detailsUi.nameField.setText("/etc/passwd");
        detailsUi.clickOk();
        assertPropertyValue(NAME_PROPERTY, "_etc_passwd");

        // Finally, make the real changes.
        detailsUi.reOpen("_etc_passwd");
        detailsUi.nameField.setText(NEW_NAME);
        detailsUi.titleField.setText(TITLE);
        detailsUi.descField.setText(mDescription);

        detailsUi.clickOk();

        assertPropertyValue(NAME_PROPERTY, NEW_NAME);
        assertProgressNotification(NEW_NAME, 00.00f);

        Bundle extras = sendBugreportFinishedAndGetSharedIntent(ID, mPlainTextPath,
                mScreenshotPath, TITLE);
        assertActionSendMultiple(extras, BUGREPORT_CONTENT, SCREENSHOT_CONTENT, ID, PID, TITLE,
                NEW_NAME, TITLE, mDescription, 0, RENAMED_SCREENSHOTS);

        assertServiceNotRunning();
    }

    @Test
    public void testProgress_cancelBugClosesDetailsDialog() throws Exception {
        resetProperties();
        sendBugreportStarted(1000);
        waitForScreenshotButtonEnabled(true);

        DetailsUi detailsUi = new DetailsUi(mUiBot, ID, NAME);
        detailsUi.assertName(NAME);  // Sanity check

        cancelFromNotification();
        mUiBot.collapseStatusBar();

        assertDetailsUiClosed();
        assertServiceNotRunning();
    }

    @Test
    public void testProgress_changeDetailsPlainBugreport() throws Exception {
        changeDetailsTest(true);
    }

    @Test
    public void testProgress_changeDetailsZippedBugreport() throws Exception {
        changeDetailsTest(false);
    }

    private void changeDetailsTest(boolean plainText) throws Exception {
        resetProperties();
        sendBugreportStarted(1000);
        waitForScreenshotButtonEnabled(true);

        DetailsUi detailsUi = new DetailsUi(mUiBot, ID, NAME);

        // Check initial name.
        detailsUi.assertName(NAME);

        // Change fields.
        detailsUi.reOpen(NAME);
        detailsUi.nameField.setText(NEW_NAME);
        detailsUi.titleField.setText(TITLE);
        detailsUi.descField.setText(mDescription);

        detailsUi.clickOk();

        assertPropertyValue(NAME_PROPERTY, NEW_NAME);
        assertProgressNotification(NEW_NAME, 00.00f);

        Bundle extras = sendBugreportFinishedAndGetSharedIntent(ID,
                plainText? mPlainTextPath : mZipPath, mScreenshotPath, TITLE);
        assertActionSendMultiple(extras, BUGREPORT_CONTENT, SCREENSHOT_CONTENT, ID, PID, TITLE,
                NEW_NAME, TITLE, mDescription, 0, RENAMED_SCREENSHOTS);

        assertServiceNotRunning();
    }

    @Test
    public void testProgress_changeJustDetailsTouchingDetails() throws Exception {
        changeJustDetailsTest(true);
    }

    @Test
    public void testProgress_changeJustDetailsTouchingNotification() throws Exception {
        changeJustDetailsTest(false);
    }

    private void changeJustDetailsTest(boolean touchDetails) throws Exception {
        resetProperties();
        sendBugreportStarted(1000);
        waitForScreenshotButtonEnabled(true);

        DetailsUi detailsUi = new DetailsUi(mUiBot, ID, NAME, touchDetails);

        detailsUi.nameField.setText("");
        detailsUi.titleField.setText("");
        detailsUi.descField.setText(mDescription);
        detailsUi.clickOk();

        Bundle extras = sendBugreportFinishedAndGetSharedIntent(ID, mZipPath, mScreenshotPath);
        assertActionSendMultiple(extras, BUGREPORT_CONTENT, SCREENSHOT_CONTENT, ID, PID, ZIP_FILE,
                NO_NAME, NO_TITLE, mDescription, 0, DIDNT_RENAME_SCREENSHOTS);

        assertServiceNotRunning();
    }

    @Test
    public void testProgress_changeJustDetailsIsClearedOnSecondBugreport() throws Exception {
        resetProperties();
        sendBugreportStarted(ID, PID, NAME, 1000);
        waitForScreenshotButtonEnabled(true);

        DetailsUi detailsUi = new DetailsUi(mUiBot, ID, NAME);
        detailsUi.assertName(NAME);
        detailsUi.assertTitle("");
        detailsUi.assertDescription("");
        assertTrue("didn't enable name on UI", detailsUi.nameField.isEnabled());
        detailsUi.nameField.setText(NEW_NAME);
        detailsUi.titleField.setText(TITLE);
        detailsUi.descField.setText(DESCRIPTION);
        detailsUi.clickOk();

        sendBugreportStarted(ID2, PID2, NAME2, 1000);

        sendBugreportFinished(ID, mZipPath, mScreenshotPath);
        Bundle extras = acceptBugreportAndGetSharedIntent(TITLE);

        detailsUi = new DetailsUi(mUiBot, ID2, NAME2);
        detailsUi.assertName(NAME2);
        detailsUi.assertTitle("");
        detailsUi.assertDescription("");
        assertTrue("didn't enable name on UI", detailsUi.nameField.isEnabled());
        detailsUi.nameField.setText(NEW_NAME2);
        detailsUi.titleField.setText(TITLE2);
        detailsUi.descField.setText(DESCRIPTION2);
        detailsUi.clickOk();

        // Must use a different zip file otherwise it will fail because zip already contains
        // title.txt and description.txt entries.
        extras = sendBugreportFinishedAndGetSharedIntent(ID2, mZipPath2, NO_SCREENSHOT, TITLE2);
        assertActionSendMultiple(extras, BUGREPORT_CONTENT, NO_SCREENSHOT, ID2, PID2, TITLE2,
                NEW_NAME2, TITLE2, DESCRIPTION2, 0, RENAMED_SCREENSHOTS);

        assertServiceNotRunning();
    }

    /**
     * Tests the scenario where the initial screenshot and dumpstate are finished while the user
     * is changing the info in the details screen.
     */
    @Test
    public void testProgress_bugreportAndScreenshotFinishedWhileChangingDetails() throws Exception {
        bugreportFinishedWhileChangingDetailsTest(false);
    }

    /**
     * Tests the scenario where dumpstate is finished while the user is changing the info in the
     * details screen, but the initial screenshot finishes afterwards.
     */
    @Test
    public void testProgress_bugreportFinishedWhileChangingDetails() throws Exception {
        bugreportFinishedWhileChangingDetailsTest(true);
    }

    private void bugreportFinishedWhileChangingDetailsTest(boolean waitScreenshot) throws Exception {
        resetProperties();
        sendBugreportStarted(1000);
        if (waitScreenshot) {
            waitForScreenshotButtonEnabled(true);
        }

        DetailsUi detailsUi = new DetailsUi(mUiBot, ID, NAME);

        // Finish the bugreport while user's still typing the name.
        detailsUi.nameField.setText(NEW_NAME);
        sendBugreportFinished(ID, mPlainTextPath, mScreenshotPath);

        // Wait until the share notification is received...
        waitShareNotification(ID);
        // ...then close notification bar.
        mContext.sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));

        // Make sure UI was updated properly.
        assertFalse("didn't disable name on UI", detailsUi.nameField.isEnabled());
        assertEquals("didn't revert name on UI", NAME, detailsUi.nameField.getText().toString());

        // Finish changing other fields.
        detailsUi.titleField.setText(TITLE);
        detailsUi.descField.setText(mDescription);
        detailsUi.clickOk();

        // Finally, share bugreport.
        Bundle extras = acceptBugreportAndGetSharedIntent(ID);
        assertActionSendMultiple(extras, BUGREPORT_CONTENT, SCREENSHOT_CONTENT, ID, PID, TITLE,
                NAME, TITLE, mDescription, 0, RENAMED_SCREENSHOTS);

        assertServiceNotRunning();
    }

    @Test
    public void testBugreportFinished_withWarningFirstTime() throws Exception {
        bugreportFinishedWithWarningTest(null);
    }

    @Test
    public void testBugreportFinished_withWarningUnknownState() throws Exception {
        bugreportFinishedWithWarningTest(STATE_UNKNOWN);
    }

    @Test
    public void testBugreportFinished_withWarningShowAgain() throws Exception {
        bugreportFinishedWithWarningTest(STATE_SHOW);
    }

    private void bugreportFinishedWithWarningTest(Integer propertyState) throws Exception {
        if (propertyState == null) {
            // Clear properties
            mContext.getSharedPreferences(PREFS_BUGREPORT, Context.MODE_PRIVATE)
                    .edit().clear().commit();
            // Sanity check...
            assertEquals("Did not reset properties", STATE_UNKNOWN,
                    getWarningState(mContext, STATE_UNKNOWN));
        } else {
            setWarningState(mContext, propertyState);
        }

        // Send notification and click on share.
        sendBugreportFinished(NO_ID, mPlainTextPath, null);
        mUiBot.clickOnNotification(mContext.getString(R.string.bugreport_finished_title, NO_ID));

        // Handle the warning
        mUiBot.getVisibleObject(mContext.getString(R.string.bugreport_confirm));
        // TODO: get ok and dontShowAgain from the dialog reference above
        UiObject dontShowAgain =
                mUiBot.getVisibleObject(mContext.getString(R.string.bugreport_confirm_dont_repeat));
        final boolean firstTime = propertyState == null || propertyState == STATE_UNKNOWN;
        if (firstTime) {
            if (Build.IS_USER) {
                assertFalse("Checkbox should NOT be checked by default on user builds",
                        dontShowAgain.isChecked());
                mUiBot.click(dontShowAgain, "dont-show-again");
            } else {
                assertTrue("Checkbox should be checked by default on build type " + Build.TYPE,
                        dontShowAgain.isChecked());
            }
        } else {
            assertFalse("Checkbox should not be checked", dontShowAgain.isChecked());
            mUiBot.click(dontShowAgain, "dont-show-again");
        }
        UiObject ok = mUiBot.getVisibleObject(mContext.getString(com.android.internal.R.string.ok));
        mUiBot.click(ok, "ok");

        // Share the bugreport.
        mUiBot.chooseActivity(UI_NAME);
        Bundle extras = mListener.getExtras();
        assertActionSendMultiple(extras, BUGREPORT_CONTENT, NO_SCREENSHOT);

        // Make sure it's hidden now.
        int newState = getWarningState(mContext, STATE_UNKNOWN);
        assertEquals("Didn't change state", STATE_HIDE, newState);
    }

    @Test
    public void testShareBugreportAfterServiceDies() throws Exception {
        sendBugreportFinished(NO_ID, mPlainTextPath, NO_SCREENSHOT);
        waitForService(false);
        Bundle extras = acceptBugreportAndGetSharedIntent(NO_ID);
        assertActionSendMultiple(extras, BUGREPORT_CONTENT, NO_SCREENSHOT);
    }

    @Test
    public void testBugreportFinished_plainBugreportAndScreenshot() throws Exception {
        Bundle extras = sendBugreportFinishedAndGetSharedIntent(mPlainTextPath, mScreenshotPath);
        assertActionSendMultiple(extras, BUGREPORT_CONTENT, SCREENSHOT_CONTENT);
    }

    @Test
    public void testBugreportFinished_zippedBugreportAndScreenshot() throws Exception {
        Bundle extras = sendBugreportFinishedAndGetSharedIntent(mZipPath, mScreenshotPath);
        assertActionSendMultiple(extras, BUGREPORT_CONTENT, SCREENSHOT_CONTENT);
    }

    @Test
    public void testBugreportFinished_plainBugreportAndNoScreenshot() throws Exception {
        Bundle extras = sendBugreportFinishedAndGetSharedIntent(mPlainTextPath, NO_SCREENSHOT);
        assertActionSendMultiple(extras, BUGREPORT_CONTENT, NO_SCREENSHOT);
    }

    @Test
    public void testBugreportFinished_zippedBugreportAndNoScreenshot() throws Exception {
        Bundle extras = sendBugreportFinishedAndGetSharedIntent(mZipPath, NO_SCREENSHOT);
        assertActionSendMultiple(extras, BUGREPORT_CONTENT, NO_SCREENSHOT);
    }

    private void cancelExistingNotifications() {
        // Must kill service first, because notifications from a foreground service cannot be
        // canceled.
        killService();

        NotificationManager nm = NotificationManager.from(mContext);
        StatusBarNotification[] activeNotifications = nm.getActiveNotifications();
        if (activeNotifications.length == 0) {
            return;
        }

        Log.w(TAG, getName() + ": " + activeNotifications.length + " active notifications");

        nm.cancelAll();

        // Wait a little bit...
        for (int i = 1; i < 5; i++) {
            int total = nm.getActiveNotifications().length;
            if (total == 0) {
                return;
            }
            Log.d(TAG, total + "notifications are still active; sleeping ");
            nm.cancelAll();
            sleep(1000);
        }
        assertEquals("old notifications were not cancelled", 0, nm.getActiveNotifications().length);
    }

    private void cancelFromNotification() {
        openProgressNotification(NAME);
        UiObject cancelButton = mUiBot.getVisibleObject(mContext.getString(
                com.android.internal.R.string.cancel).toUpperCase());
        mUiBot.click(cancelButton, "cancel_button");
    }

    private void assertProgressNotification(String name, float percent) {
        openProgressNotification(name);
        // TODO: need a way to get the ProgresBar from the "android:id/progress" UIObject...
    }

    private UiObject openProgressNotification(String bugreportName) {
        Log.v(TAG, "Looking for progress notification for '" + bugreportName + "'");
        return mUiBot.getNotification(bugreportName);
    }

    void resetProperties() {
        // TODO: call method to remove property instead
        SystemProperties.set(PROGRESS_PROPERTY, "Reset");
        SystemProperties.set(MAX_PROPERTY, "Reset");
        SystemProperties.set(NAME_PROPERTY, "Reset");
    }

    /**
     * Sends a "bugreport started" intent with the default values.
     */
    private void sendBugreportStarted(int max) throws Exception {
        sendBugreportStarted(ID, PID, NAME, max);
    }

    private void sendBugreportStarted(int id, int pid, String name, int max) throws Exception {
        Intent intent = new Intent(INTENT_BUGREPORT_STARTED);
        intent.setPackage("com.android.shell");
        intent.setFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        intent.putExtra(EXTRA_ID, id);
        intent.putExtra(EXTRA_PID, pid);
        intent.putExtra(EXTRA_NAME, name);
        intent.putExtra(EXTRA_MAX, max);
        mContext.sendBroadcast(intent);
    }

    /**
     * Sends a "bugreport finished" intent and waits for the result.
     *
     * @return extras sent in the shared intent.
     */
    private Bundle sendBugreportFinishedAndGetSharedIntent(String bugreportPath,
            String screenshotPath) {
        return sendBugreportFinishedAndGetSharedIntent(NO_ID, bugreportPath, screenshotPath);
    }

    /**
     * Sends a "bugreport finished" intent and waits for the result.
     *
     * @return extras sent in the shared intent.
     */
    private Bundle sendBugreportFinishedAndGetSharedIntent(int id, String bugreportPath,
            String screenshotPath) {
        sendBugreportFinished(id, bugreportPath, screenshotPath);
        return acceptBugreportAndGetSharedIntent(id);
    }

    // TODO: document / merge these 3 sendBugreportFinishedAndGetSharedIntent methods
    private Bundle sendBugreportFinishedAndGetSharedIntent(int id, String bugreportPath,
            String screenshotPath, String notificationTitle) {
        sendBugreportFinished(id, bugreportPath, screenshotPath);
        return acceptBugreportAndGetSharedIntent(notificationTitle);
    }

    /**
     * Accepts the notification to share the finished bugreport and waits for the result.
     *
     * @return extras sent in the shared intent.
     */
    private Bundle acceptBugreportAndGetSharedIntent(int id) {
        final String notificationTitle = mContext.getString(R.string.bugreport_finished_title, id);
        return acceptBugreportAndGetSharedIntent(notificationTitle);
    }

    // TODO: document and/or merge these 2 acceptBugreportAndGetSharedIntent methods
    private Bundle acceptBugreportAndGetSharedIntent(String notificationTitle) {
        mUiBot.clickOnNotification(notificationTitle);
        mUiBot.chooseActivity(UI_NAME);
        return mListener.getExtras();
    }

    /**
     * Waits for the notification to share the finished bugreport.
     */
    private void waitShareNotification(int id) {
        mUiBot.getNotification(mContext.getString(R.string.bugreport_finished_title, id));
    }

    /**
     * Sends a "bugreport finished" intent.
     */
    private void sendBugreportFinished(int id, String bugreportPath, String screenshotPath) {
        Intent intent = new Intent(INTENT_BUGREPORT_FINISHED);
        intent.setPackage("com.android.shell");
        intent.setFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        if (id != NO_ID) {
            intent.putExtra(EXTRA_ID, id);
        }
        if (bugreportPath != null) {
            intent.putExtra(EXTRA_BUGREPORT, bugreportPath);
        }
        if (screenshotPath != null) {
            intent.putExtra(EXTRA_SCREENSHOT, screenshotPath);
        }

        mContext.sendBroadcast(intent);
    }

    /**
     * Asserts the proper {@link Intent#ACTION_SEND_MULTIPLE} intent was sent.
     */
    private void assertActionSendMultiple(Bundle extras, String bugreportContent,
            String screenshotContent) throws IOException {
        assertActionSendMultiple(extras, bugreportContent, screenshotContent, ID, PID, ZIP_FILE,
                NO_NAME, NO_TITLE, NO_DESCRIPTION, 0, DIDNT_RENAME_SCREENSHOTS);
    }

    /**
     * Asserts the proper {@link Intent#ACTION_SEND_MULTIPLE} intent was sent.
     *
     * @param extras extras received in the intent
     * @param bugreportContent expected content in the bugreport file
     * @param screenshotContent expected content in the screenshot file (sent by dumpstate), if any
     * @param id emulated dumpstate id
     * @param pid emulated dumpstate pid
     * @param name expected subject
     * @param name bugreport name as provided by the user (or received by dumpstate)
     * @param title bugreport name as provided by the user
     * @param description bugreport description as provided by the user
     * @param numberScreenshots expected number of screenshots taken by Shell.
     * @param renamedScreenshots whether the screenshots are expected to be renamed
     */
    private void assertActionSendMultiple(Bundle extras, String bugreportContent,
            String screenshotContent, int id, int pid, String subject,
            String name, String title, String description,
            int numberScreenshots, boolean renamedScreenshots) throws IOException {
        String body = extras.getString(Intent.EXTRA_TEXT);
        assertContainsRegex("missing build info",
                SystemProperties.get("ro.build.description"), body);
        assertContainsRegex("missing serial number",
                SystemProperties.get("ro.serialno"), body);
        if (description != null) {
            assertContainsRegex("missing description", description, body);
        }

        assertEquals("wrong subject", subject, extras.getString(Intent.EXTRA_SUBJECT));

        List<Uri> attachments = extras.getParcelableArrayList(Intent.EXTRA_STREAM);
        int expectedNumberScreenshots = numberScreenshots;
        if (screenshotContent != null) {
            expectedNumberScreenshots ++; // Add screenshot received by dumpstate
        }
        int expectedSize = expectedNumberScreenshots + 1; // All screenshots plus the bugreport file
        assertEquals("wrong number of attachments (" + attachments + ")",
                expectedSize, attachments.size());

        // Need to interact through all attachments, since order is not guaranteed.
        Uri zipUri = null;
        List<Uri> screenshotUris = new ArrayList<>(expectedNumberScreenshots);
        for (Uri attachment : attachments) {
            if (attachment.getPath().endsWith(".zip")) {
                zipUri = attachment;
            }
            if (attachment.getPath().endsWith(".png")) {
                screenshotUris.add(attachment);
            }
        }
        assertNotNull("did not get .zip attachment", zipUri);
        assertZipContent(zipUri, BUGREPORT_FILE, BUGREPORT_CONTENT);
        if (!TextUtils.isEmpty(title)) {
            assertZipContent(zipUri, "title.txt", title);
        }
        if (!TextUtils.isEmpty(description)) {
            assertZipContent(zipUri, "description.txt", description);
        }

        // URI of the screenshot taken by dumpstate.
        Uri externalScreenshotUri = null;
        SortedSet<String> internalScreenshotNames = new TreeSet<>();
        for (Uri screenshotUri : screenshotUris) {
            String screenshotName = screenshotUri.getLastPathSegment();
            if (screenshotName.endsWith(SCREENSHOT_FILE)) {
                externalScreenshotUri = screenshotUri;
            } else {
                internalScreenshotNames.add(screenshotName);
            }
        }
        // Check external screenshot
        if (screenshotContent != null) {
            assertNotNull("did not get .png attachment for external screenshot",
                    externalScreenshotUri);
            assertContent(externalScreenshotUri, SCREENSHOT_CONTENT);
        } else {
            assertNull("should not have .png attachment for external screenshot",
                    externalScreenshotUri);
        }
        // Check internal screenshots.
        SortedSet<String> expectedNames = new TreeSet<>();
        for (int i = 1 ; i <= numberScreenshots; i++) {
            String prefix = renamedScreenshots  ? name : Integer.toString(pid);
            String expectedName = "screenshot-" + prefix + "-" + i + ".png";
            expectedNames.add(expectedName);
        }
        // Ideally we should use MoreAsserts, but the error message in case of failure is not
        // really useful.
        assertEquals("wrong names for internal screenshots",
                expectedNames, internalScreenshotNames);
    }

    private void assertContent(Uri uri, String expectedContent) throws IOException {
        Log.v(TAG, "assertContents(uri=" + uri);
        try (InputStream is = mContext.getContentResolver().openInputStream(uri)) {
            String actualContent = new String(Streams.readFully(is));
            assertEquals("wrong content for '" + uri + "'", expectedContent, actualContent);
        }
    }

    private void assertZipContent(Uri uri, String entryName, String expectedContent)
            throws IOException, IOException {
        Log.v(TAG, "assertZipEntry(uri=" + uri + ", entryName=" + entryName);
        try (ZipInputStream zis = new ZipInputStream(mContext.getContentResolver().openInputStream(
                uri))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Log.v(TAG, "Zip entry: " + entry.getName());
                if (entry.getName().equals(entryName)) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    Streams.copy(zis, bos);
                    String actualContent = new String(bos.toByteArray(), "UTF-8");
                    bos.close();
                    assertEquals("wrong content for zip entry'" + entryName + "' on '" + uri + "'",
                            expectedContent, actualContent);
                    return;
                }
            }
        }
        fail("Did not find entry '" + entryName + "' on file '" + uri + "'");
    }

    private void assertPropertyValue(String key, String expectedValue) {
        // Since the property is set in a different thread by BugreportProgressService, we need to
        // poll it a couple times...

        for (int i = 1; i <= 5; i++) {
            String actualValue = SystemProperties.get(key);
            if (expectedValue.equals(actualValue)) {
                return;
            }
            Log.d(TAG, "Value of property " + key + " (" + actualValue
                    + ") does not match expected value (" + expectedValue
                    + ") on attempt " + i + ". Sleeping before next attempt...");
            sleep(1000);
        }
        // Final try...
        String actualValue = SystemProperties.get(key);
        assertEquals("Wrong value for property '" + key + "'", expectedValue, actualValue);
    }

    private void assertServiceNotRunning() {
        String service = BugreportProgressService.class.getName();
        assertFalse("Service '" + service + "' is still running", isServiceRunning(service));
    }

    private boolean isServiceRunning(String name) {
        ActivityManager manager = (ActivityManager) mContext
                .getSystemService(Context.ACTIVITY_SERVICE);
        for (RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (service.service.getClassName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private void waitForService(boolean expectRunning) {
        String service = BugreportProgressService.class.getName();
        boolean actualRunning;
        for (int i = 1; i <= 5; i++) {
            actualRunning = isServiceRunning(service);
            Log.d(TAG, "Attempt " + i + " to check status of service '"
                    + service + "': expected=" + expectRunning + ", actual= " + actualRunning);
            if (actualRunning == expectRunning) {
                return;
            }
            sleep(DateUtils.SECOND_IN_MILLIS);
        }

        fail("Service status didn't change to " + expectRunning);
    }

    private void killService() {
        String service = BugreportProgressService.class.getName();

        if (!isServiceRunning(service)) return;

        Log.w(TAG, "Service '" + service + "' is still running, killing it");
        silentlyExecuteShellCommand("am stopservice com.android.shell/.BugreportProgressService");

        waitForService(false);
    }

    private void silentlyExecuteShellCommand(String cmd) {
        Log.w(TAG, "silentlyExecuteShellCommand: '" + cmd + "'");
        try {
            UiDevice.getInstance(getInstrumentation()).executeShellCommand(cmd);
        } catch (IOException e) {
            Log.w(TAG, "error executing shell comamand '" + cmd + "'", e);
        }
    }

    private void createTextFile(String path, String content) throws IOException {
        Log.v(TAG, "createFile(" + path + ")");
        try (Writer writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(path)))) {
            writer.write(content);
        }
    }

    private void createZipFile(String path, String entryName, String content) throws IOException {
        Log.v(TAG, "createZipFile(" + path + ", " + entryName + ")");
        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(path)))) {
            ZipEntry entry = new ZipEntry(entryName);
            zos.putNextEntry(entry);
            byte[] data = content.getBytes();
            zos.write(data, 0, data.length);
            zos.closeEntry();
        }
    }

    private String getPath(String file) {
        final File rootDir = mContext.getFilesDir();
        final File dir = new File(rootDir, BUGREPORTS_DIR);
        if (!dir.exists()) {
            Log.i(TAG, "Creating directory " + dir);
            assertTrue("Could not create directory " + dir, dir.mkdir());
        }
        String path = new File(dir, file).getAbsolutePath();
        Log.v(TAG, "Path for '" + file + "': " + path);
        return path;
    }

    /**
     * Gets the notification button used to take a screenshot.
     */
    private UiObject getScreenshotButton() {
        openProgressNotification(NAME);
        return mUiBot.getVisibleObject(
                mContext.getString(R.string.bugreport_screenshot_action).toUpperCase());
    }

    /**
     * Takes a screenshot using the system notification.
     */
    private void takeScreenshot() throws Exception {
        UiObject screenshotButton = getScreenshotButton();
        mUiBot.click(screenshotButton, "screenshot_button");
    }

    private UiObject waitForScreenshotButtonEnabled(boolean expectedEnabled) throws Exception {
        UiObject screenshotButton = getScreenshotButton();
        int maxAttempts = SAFE_SCREENSHOT_DELAY;
        int i = 0;
        do {
            boolean enabled = screenshotButton.isEnabled();
            if (enabled == expectedEnabled) {
                return screenshotButton;
            }
            i++;
            Log.v(TAG, "Sleeping for 1 second while waiting for screenshot.enable to be "
                    + expectedEnabled + " (attempt " + i + ")");
            Thread.sleep(DateUtils.SECOND_IN_MILLIS);
        } while (i <= maxAttempts);
        fail("screenshot.enable didn't change to " + expectedEnabled + " in " + maxAttempts + "s");
        return screenshotButton;
    }

    private void assertScreenshotButtonEnabled(boolean expectedEnabled) throws Exception {
        UiObject screenshotButton = getScreenshotButton();
        assertEquals("wrong state for screenshot button ", expectedEnabled,
                screenshotButton.isEnabled());
    }

    private void assertDetailsUiClosed() {
        // TODO: unhardcode resource ids
        mUiBot.assertNotVisibleById("android:id/alertTitle");
    }

    private String getName() {
        return mName.getMethodName();
    }

    private Instrumentation getInstrumentation() {
        return InstrumentationRegistry.getInstrumentation();
    }

    private static void sleep(long ms) {
        Log.d(TAG, "sleeping for " + ms + "ms");
        SystemClock.sleep(ms);
        Log.d(TAG, "woke up");
    }

    /**
     * Helper class containing the UiObjects present in the bugreport info dialog.
     */
    private final class DetailsUi {

        final UiObject detailsButton;
        final UiObject nameField;
        final UiObject titleField;
        final UiObject descField;
        final UiObject okButton;
        final UiObject cancelButton;

        /**
         * Gets the UI objects by opening the progress notification and clicking on DETAILS.
         *
         * @param id bugreport id
         * @param id bugreport name
         */
        DetailsUi(UiBot uiBot, int id, String name) throws UiObjectNotFoundException {
            this(uiBot, id, name, true);
        }

        /**
         * Gets the UI objects by opening the progress notification and clicking on DETAILS or in
         * the notification itself.
         *
         * @param id bugreport id
         * @param id bugreport name
         */
        DetailsUi(UiBot uiBot, int id, String name, boolean clickDetails)
                throws UiObjectNotFoundException {
            final UiObject notification = openProgressNotification(name);
            detailsButton = mUiBot.getVisibleObject(mContext.getString(
                    R.string.bugreport_info_action).toUpperCase());

            if (clickDetails) {
                mUiBot.click(detailsButton, "details_button");
            } else {
                mUiBot.click(notification, "notification");
            }
            // TODO: unhardcode resource ids
            UiObject dialogTitle = mUiBot.getVisibleObjectById("android:id/alertTitle");
            assertEquals("Wrong title", mContext.getString(R.string.bugreport_info_dialog_title,
                    id), dialogTitle.getText().toString());
            nameField = mUiBot.getVisibleObjectById("com.android.shell:id/name");
            titleField = mUiBot.getVisibleObjectById("com.android.shell:id/title");
            descField = mUiBot.getVisibleObjectById("com.android.shell:id/description");
            okButton = mUiBot.getObjectById("android:id/button1");
            cancelButton = mUiBot.getObjectById("android:id/button2");
        }

        private void assertField(String name, UiObject field, String expected)
                throws UiObjectNotFoundException {
            String actual = field.getText().toString();
            assertEquals("Wrong value on field '" + name + "'", expected, actual);
        }

        void assertName(String expected) throws UiObjectNotFoundException {
            assertField("name", nameField, expected);
        }

        void assertTitle(String expected) throws UiObjectNotFoundException {
            assertField("title", titleField, expected);
        }

        void assertDescription(String expected) throws UiObjectNotFoundException {
            assertField("description", descField, expected);
        }

        /**
         * Set focus on the name field so it can be validated once focus is lost.
         */
        void focusOnName() throws UiObjectNotFoundException {
            mUiBot.click(nameField, "name_field");
            assertTrue("name_field not focused", nameField.isFocused());
        }

        /**
         * Takes focus away from the name field so it can be validated.
         */
        void focusAwayFromName() throws UiObjectNotFoundException {
            mUiBot.click(titleField, "title_field"); // Change focus.
            assertFalse("name_field is focused", nameField.isFocused());
        }

        void reOpen(String name) {
            openProgressNotification(name);
            mUiBot.click(detailsButton, "details_button");
        }

        void clickOk() {
            mUiBot.click(okButton, "details_ok_button");
        }

        void clickCancel() {
            mUiBot.click(cancelButton, "details_cancel_button");
        }
    }
}
