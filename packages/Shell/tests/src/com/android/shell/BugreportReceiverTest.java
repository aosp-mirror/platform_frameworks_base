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
import static com.android.shell.BugreportProgressService.INTENT_BUGREPORT_REQUESTED;
import static com.android.shell.BugreportProgressService.PROPERTY_LAST_ID;
import static com.android.shell.BugreportProgressService.SCREENSHOT_DELAY_SECONDS;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningServiceInfo;
import android.app.Instrumentation;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.BugreportManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IDumpstate;
import android.os.IDumpstateListener;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.rule.ServiceTestRule;
import androidx.test.runner.AndroidJUnit4;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.UiObjectNotFoundException;

import com.android.shell.ActionSendMultipleConsumerActivity.CustomActionSendMultipleListener;

import libcore.io.IoUtils;
import libcore.io.Streams;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Integration tests for {@link BugreportProgressService}.
 * <p>
 * These tests rely on external UI components (like the notificatio bar and activity chooser),
 * which can make them unreliable and slow.
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

    // The default timeout is too short to verify the notification button state. Using a longer
    // timeout in the tests.
    private static final int SCREENSHOT_DELAY_SECONDS = 5;

    // Timeout for when waiting for a screenshot to finish.
    private static final int SAFE_SCREENSHOT_DELAY = SCREENSHOT_DELAY_SECONDS + 10;

    private static final String BUGREPORT_FILE = "test_bugreport.txt";
    private static final String SCREENSHOT_FILE = "test_screenshot.png";
    private static final String BUGREPORT_CONTENT = "Dump, might as well dump!\n";
    private static final String SCREENSHOT_CONTENT = "A picture is worth a thousand words!\n";

    private static final String NAME = "BUG, Y U NO REPORT?";
    private static final String NEW_NAME = "Bug_Forrest_Bug";
    private static final String TITLE = "Wimbugdom Champion 2015";

    private static final String NO_DESCRIPTION = null;
    private static final String NO_NAME = null;
    private static final String NO_SCREENSHOT = null;
    private static final String NO_TITLE = null;

    private String mDescription;
    private String mProgressTitle;
    private int mBugreportId;

    private Context mContext;
    private UiBot mUiBot;
    private CustomActionSendMultipleListener mListener;
    private BugreportProgressService mService;
    private IDumpstateListener mIDumpstateListener;
    private ParcelFileDescriptor mBugreportFd;
    private ParcelFileDescriptor mScreenshotFd;

    @Mock private IDumpstate mMockIDumpstate;

    @Rule public TestName mName = new TestName();
    @Rule public ServiceTestRule mServiceRule = new ServiceTestRule();

    @Before
    public void setUp() throws Exception {
        Log.i(TAG, getName() + ".setup()");
        MockitoAnnotations.initMocks(this);
        Instrumentation instrumentation = getInstrumentation();
        mContext = instrumentation.getTargetContext();
        mUiBot = new UiBot(instrumentation, TIMEOUT);
        mListener = ActionSendMultipleConsumerActivity.getListener(mContext);

        cancelExistingNotifications();

        mBugreportId = getBugreportId();
        mProgressTitle = getBugreportInProgress(mBugreportId);
        // Creates a multi-line description.
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            sb.append("All work and no play makes Shell a dull app!\n");
        }
        mDescription = sb.toString();

        // Mocks BugreportManager and updates tests value to the service
        mService = ((BugreportProgressService.LocalBinder) mServiceRule.bindService(
                new Intent(mContext, BugreportProgressService.class))).getService();
        mService.mBugreportManager = new BugreportManager(mContext, mMockIDumpstate);
        mService.mScreenshotDelaySec = SCREENSHOT_DELAY_SECONDS;
        // Dup the fds which are passing to startBugreport function.
        Mockito.doAnswer(invocation -> {
            final boolean isScreenshotRequested = invocation.getArgument(6);
            if (isScreenshotRequested) {
                mScreenshotFd = ParcelFileDescriptor.dup(invocation.getArgument(3));
            }
            mBugreportFd = ParcelFileDescriptor.dup(invocation.getArgument(2));
            return null;
        }).when(mMockIDumpstate).startBugreport(anyInt(), any(), any(), any(), anyInt(), anyInt(),
                any(), anyBoolean());

        setWarningState(mContext, STATE_HIDE);

        mUiBot.turnScreenOn();
    }

    @After
    public void tearDown() throws Exception {
        Log.i(TAG, getName() + ".tearDown()");
        if (mBugreportFd != null) {
            IoUtils.closeQuietly(mBugreportFd);
        }
        if (mScreenshotFd != null) {
            IoUtils.closeQuietly(mScreenshotFd);
        }
        mContext.sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
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
        sendBugreportStarted();
        waitForScreenshotButtonEnabled(true);
        assertProgressNotification(mProgressTitle, 0f);

        mIDumpstateListener.onProgress(10);
        assertProgressNotification(mProgressTitle, 10);

        mIDumpstateListener.onProgress(95);
        assertProgressNotification(mProgressTitle, 95.00f);

        // ...but never more than the capped value.
        mIDumpstateListener.onProgress(200);
        assertProgressNotification(mProgressTitle, 99);

        mIDumpstateListener.onProgress(300);
        assertProgressNotification(mProgressTitle, 99);

        Bundle extras = sendBugreportFinishedAndGetSharedIntent(mBugreportId);
        assertActionSendMultiple(extras);

        assertServiceNotRunning();
    }

    @Test
    public void testProgress_cancel() throws Exception {
        sendBugreportStarted();
        waitForScreenshotButtonEnabled(true);

        assertProgressNotification(mProgressTitle, 00.00f);

        cancelFromNotification(mProgressTitle);

        assertServiceNotRunning();
    }

    @Test
    public void testProgress_takeExtraScreenshot() throws Exception {
        sendBugreportStarted();

        waitForScreenshotButtonEnabled(true);
        takeScreenshot();
        assertScreenshotButtonEnabled(false);
        waitForScreenshotButtonEnabled(true);

        Bundle extras = sendBugreportFinishedAndGetSharedIntent(mBugreportId);
        assertActionSendMultiple(extras, NO_NAME, NO_TITLE, NO_DESCRIPTION, 1);

        assertServiceNotRunning();
    }

    @Test
    public void testScreenshotFinishesAfterBugreport() throws Exception {
        sendBugreportStarted();
        waitForScreenshotButtonEnabled(true);
        takeScreenshot();
        sendBugreportFinished();
        waitShareNotification(mBugreportId);

        // There's no indication in the UI about the screenshot finish, so just sleep like a baby...
        sleep(SAFE_SCREENSHOT_DELAY * DateUtils.SECOND_IN_MILLIS);

        Bundle extras = acceptBugreportAndGetSharedIntent(mBugreportId);
        assertActionSendMultiple(extras, NO_NAME, NO_TITLE, NO_DESCRIPTION, 1);

        assertServiceNotRunning();
    }

    @Test
    public void testProgress_changeDetailsInvalidInput() throws Exception {
        sendBugreportStarted();
        waitForScreenshotButtonEnabled(true);

        DetailsUi detailsUi = new DetailsUi(mBugreportId);

        // Change name
        detailsUi.focusOnName();
        detailsUi.nameField.setText(NEW_NAME);
        detailsUi.focusAwayFromName();
        detailsUi.clickOk();

        // Now try to set an invalid name.
        detailsUi.reOpen(NEW_NAME);
        detailsUi.nameField.setText("/etc/passwd");
        detailsUi.clickOk();

        // Finally, make the real changes.
        detailsUi.reOpen("_etc_passwd");
        detailsUi.nameField.setText(NEW_NAME);
        detailsUi.titleField.setText(TITLE);
        detailsUi.descField.setText(mDescription);

        detailsUi.clickOk();

        assertProgressNotification(NEW_NAME, 00.00f);

        Bundle extras = sendBugreportFinishedAndGetSharedIntent(TITLE);
        assertActionSendMultiple(extras, NEW_NAME, TITLE, mDescription, 0);

        assertServiceNotRunning();
    }

    @Test
    public void testProgress_cancelBugClosesDetailsDialog() throws Exception {
        sendBugreportStarted();
        waitForScreenshotButtonEnabled(true);

        cancelFromNotification(mProgressTitle);
        mUiBot.collapseStatusBar();

        assertDetailsUiClosed();
        assertServiceNotRunning();
    }

    @Test
    public void testProgress_changeDetailsTest() throws Exception {
        sendBugreportStarted();
        waitForScreenshotButtonEnabled(true);

        DetailsUi detailsUi = new DetailsUi(mBugreportId);

        // Change fields.
        detailsUi.reOpen(mProgressTitle);
        detailsUi.nameField.setText(NEW_NAME);
        detailsUi.titleField.setText(TITLE);
        detailsUi.descField.setText(mDescription);

        detailsUi.clickOk();

        assertProgressNotification(NEW_NAME, 00.00f);

        Bundle extras = sendBugreportFinishedAndGetSharedIntent(TITLE);
        assertActionSendMultiple(extras, NEW_NAME, TITLE, mDescription, 0);

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
        sendBugreportStarted();
        waitForScreenshotButtonEnabled(true);

        DetailsUi detailsUi = new DetailsUi(mBugreportId, touchDetails);

        detailsUi.nameField.setText("");
        detailsUi.titleField.setText("");
        detailsUi.descField.setText(mDescription);
        detailsUi.clickOk();

        Bundle extras = sendBugreportFinishedAndGetSharedIntent(mBugreportId);
        assertActionSendMultiple(extras, NO_NAME, NO_TITLE, mDescription, 0);

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
        sendBugreportStarted();
        if (waitScreenshot) {
            waitForScreenshotButtonEnabled(true);
        }

        DetailsUi detailsUi = new DetailsUi(mBugreportId);

        // Finish the bugreport while user's still typing the name.
        detailsUi.nameField.setText(NEW_NAME);
        sendBugreportFinished();

        // Wait until the share notification is received...
        waitShareNotification(mBugreportId);
        // ...then close notification bar.
        mContext.sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));

        // Make sure UI was updated properly.
        assertFalse("didn't disable name on UI", detailsUi.nameField.isEnabled());
        assertNotEquals("didn't revert name on UI", NAME, detailsUi.nameField.getText());

        // Finish changing other fields.
        detailsUi.titleField.setText(TITLE);
        detailsUi.descField.setText(mDescription);
        detailsUi.clickOk();

        // Finally, share bugreport.
        Bundle extras = acceptBugreportAndGetSharedIntent(mBugreportId);
        assertActionSendMultiple(extras, NO_NAME, TITLE, mDescription, 0);

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
            // Confidence check...
            assertEquals("Did not reset properties", STATE_UNKNOWN,
                    getWarningState(mContext, STATE_UNKNOWN));
        } else {
            setWarningState(mContext, propertyState);
        }

        // Send notification and click on share.
        sendBugreportStarted();
        waitForScreenshotButtonEnabled(true);
        sendBugreportFinished();
        mUiBot.clickOnNotification(mContext.getString(
                R.string.bugreport_finished_title, mBugreportId));

        // Handle the warning
        mUiBot.getObject(mContext.getString(R.string.bugreport_confirm));
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
        assertActionSendMultiple(extras);

        // Make sure it's hidden now.
        int newState = getWarningState(mContext, STATE_UNKNOWN);
        assertEquals("Didn't change state", STATE_HIDE, newState);
    }

    @Test
    public void testBugreportFinished_withEmptyBugreportFile() throws Exception {
        sendBugreportStarted();

        IoUtils.closeQuietly(mBugreportFd);
        mBugreportFd = null;
        sendBugreportFinished();

        assertServiceNotRunning();
    }

    @Test
    public void testShareBugreportAfterServiceDies() throws Exception {
        sendBugreportStarted();
        waitForScreenshotButtonEnabled(true);
        sendBugreportFinished();
        killService();
        assertServiceNotRunning();
        Bundle extras = acceptBugreportAndGetSharedIntent(mBugreportId);
        assertActionSendMultiple(extras);
    }

    @Test
    public void testBugreportRequestTwice_oneStartBugreportInvoked() throws Exception {
        sendBugreportStarted();
        new BugreportRequestedReceiver().onReceive(mContext,
                new Intent(INTENT_BUGREPORT_REQUESTED));
        getInstrumentation().waitForIdleSync();

        verify(mMockIDumpstate, times(1)).startBugreport(anyInt(), any(), any(), any(),
                anyInt(), anyInt(), any(), anyBoolean());
        sendBugreportFinished();
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

    private void cancelFromNotification(String name) {
        openProgressNotification(name);
        UiObject cancelButton = mUiBot.getObject(mContext.getString(
                com.android.internal.R.string.cancel));
        mUiBot.click(cancelButton, "cancel_button");
    }

    private void assertProgressNotification(String name, float percent) {
        openProgressNotification(name);
        // TODO: need a way to get the ProgresBar from the "android:id/progress" UIObject...
    }

    private void openProgressNotification(String title) {
        Log.v(TAG, "Looking for progress notification for '" + title + "'");
        UiObject2 notification = mUiBot.getNotification2(title);
        if (notification != null) {
            mUiBot.expandNotification(notification);
        }
    }

    /**
     * Sends a "bugreport requested" intent with the default values.
     */
    private void sendBugreportStarted() throws Exception {
        Intent intent = new Intent(INTENT_BUGREPORT_REQUESTED);
        // Ideally, we should invoke BugreportRequestedReceiver by sending
        // INTENT_BUGREPORT_REQUESTED. But the intent has been protected broadcast by the system
        // starting from S.
        new BugreportRequestedReceiver().onReceive(mContext, intent);

        ArgumentCaptor<IDumpstateListener> listenerCap = ArgumentCaptor.forClass(
                IDumpstateListener.class);
        verify(mMockIDumpstate, timeout(TIMEOUT)).startBugreport(anyInt(), any(), any(), any(),
                anyInt(), anyInt(), listenerCap.capture(), anyBoolean());
        mIDumpstateListener = listenerCap.getValue();
        assertNotNull("Dumpstate listener should not be null", mIDumpstateListener);
        mIDumpstateListener.onProgress(0);
    }

    /**
     * Sends a "bugreport finished" event and waits for the result.
     *
     * @param id The bugreport id for finished notification string title substitution.
     * @return extras sent in the shared intent.
     */
    private Bundle sendBugreportFinishedAndGetSharedIntent(int id) throws Exception {
        sendBugreportFinished();
        return acceptBugreportAndGetSharedIntent(id);
    }

    /**
     * Sends a "bugreport finished" event and waits for the result.
     *
     * @param notificationTitle The title of finished notification.
     * @return extras sent in the shared intent.
     */
    private Bundle sendBugreportFinishedAndGetSharedIntent(String notificationTitle)
            throws Exception {
        sendBugreportFinished();
        return acceptBugreportAndGetSharedIntent(notificationTitle);
    }

    /**
     * Accepts the notification to share the finished bugreport and waits for the result.
     *
     * @param id The bugreport id for finished notification string title substitution.
     * @return extras sent in the shared intent.
     */
    private Bundle acceptBugreportAndGetSharedIntent(int id) {
        final String notificationTitle = mContext.getString(R.string.bugreport_finished_title, id);
        return acceptBugreportAndGetSharedIntent(notificationTitle);
    }

    /**
     * Accepts the notification to share the finished bugreport and waits for the result.
     *
     * @param notificationTitle The title of finished notification.
     * @return extras sent in the shared intent.
     */
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
     * Callbacks to service to finish the bugreport.
     */
    private void sendBugreportFinished() throws Exception {
        if (mBugreportFd != null) {
            writeZipFile(mBugreportFd, BUGREPORT_FILE, BUGREPORT_CONTENT);
        }
        if (mScreenshotFd != null) {
            writeScreenshotFile(mScreenshotFd, SCREENSHOT_CONTENT);
        }
        mIDumpstateListener.onFinished("");
        getInstrumentation().waitForIdleSync();
    }

    /**
     * Asserts the proper {@link Intent#ACTION_SEND_MULTIPLE} intent was sent.
     */
    private void assertActionSendMultiple(Bundle extras) throws IOException {
        assertActionSendMultiple(extras, NO_NAME, NO_TITLE, NO_DESCRIPTION, 0);
    }

    /**
     * Asserts the proper {@link Intent#ACTION_SEND_MULTIPLE} intent was sent.
     *
     * @param extras extras received in the intent
     * @param name bugreport name as provided by the user (or received by dumpstate)
     * @param title bugreport name as provided by the user
     * @param description bugreport description as provided by the user
     * @param numberScreenshots expected number of screenshots taken by Shell.
     */
    private void assertActionSendMultiple(Bundle extras, String name, String title,
            String description, int numberScreenshots)
            throws IOException {
        String body = extras.getString(Intent.EXTRA_TEXT);
        assertContainsRegex("missing build info",
                SystemProperties.get("ro.build.description"), body);
        assertContainsRegex("missing serial number",
                SystemProperties.get("ro.serialno"), body);
        if (description != null) {
            assertContainsRegex("missing description", description, body);
        }

        final String extrasSubject = extras.getString(Intent.EXTRA_SUBJECT);
        if (title != null) {
            assertEquals("wrong subject", title, extrasSubject);
        } else {
            if (name != null) {
                assertEquals("wrong subject", getBugreportName(name), extrasSubject);
            } else {
                assertTrue("wrong subject", extrasSubject.startsWith(
                        getBugreportPrefixName()));
            }
        }

        List<Uri> attachments = extras.getParcelableArrayList(Intent.EXTRA_STREAM);
        int expectedNumberScreenshots = numberScreenshots;
        if (getScreenshotContent() != null) {
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
        if (getScreenshotContent() != null) {
            assertNotNull("did not get .png attachment for external screenshot",
                    externalScreenshotUri);
            assertContent(externalScreenshotUri, SCREENSHOT_CONTENT);
        } else {
            assertNull("should not have .png attachment for external screenshot",
                    externalScreenshotUri);
        }
        // Check internal screenshots' file names.
        if (name != null) {
            SortedSet<String> expectedNames = new TreeSet<>();
            for (int i = 1; i <= numberScreenshots; i++) {
                String expectedName = "screenshot-" + name + "-" + i + ".png";
                expectedNames.add(expectedName);
            }
            // Ideally we should use MoreAsserts, but the error message in case of failure is not
            // really useful.
            assertEquals("wrong names for internal screenshots",
                    expectedNames, internalScreenshotNames);
        }
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

    private void assertServiceNotRunning() {
        mServiceRule.unbindService();
        waitForService(false);
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
        mServiceRule.unbindService();
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

    private void writeScreenshotFile(ParcelFileDescriptor fd, String content) throws IOException {
        Log.v(TAG, "writeScreenshotFile(" + fd + ")");
        try (Writer writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(fd.getFileDescriptor())))) {
            writer.write(content);
        }
    }

    private void writeZipFile(ParcelFileDescriptor fd, String entryName, String content)
            throws IOException {
        Log.v(TAG, "writeZipFile(" + fd + ", " + entryName + ")");
        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(fd.getFileDescriptor())))) {
            ZipEntry entry = new ZipEntry(entryName);
            zos.putNextEntry(entry);
            byte[] data = content.getBytes();
            zos.write(data, 0, data.length);
            zos.closeEntry();
        }
    }

    /**
     * Gets the notification button used to take a screenshot.
     */
    private UiObject getScreenshotButton() {
        openProgressNotification(mProgressTitle);
        return mUiBot.getObject(
                mContext.getString(R.string.bugreport_screenshot_action));
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

    private int getBugreportId() {
        return SystemProperties.getInt(PROPERTY_LAST_ID, 1);
    }

    private String getBugreportInProgress(int bugreportId) {
        return mContext.getString(R.string.bugreport_in_progress_title, bugreportId);
    }

    private String getBugreportPrefixName() {
        String buildId = SystemProperties.get("ro.build.id", "UNKNOWN_BUILD");
        String deviceName = SystemProperties.get("ro.product.name", "UNKNOWN_DEVICE");
        return String.format("bugreport-%s-%s", deviceName, buildId);
    }

    private String getBugreportName(String name) {
        return String.format("%s-%s.zip", getBugreportPrefixName(), name);
    }

    private String getScreenshotContent() {
        if (mScreenshotFd == null) {
            return NO_SCREENSHOT;
        }
        return SCREENSHOT_CONTENT;
    }

    /**
     * Helper class containing the UiObjects present in the bugreport info dialog.
     */
    private final class DetailsUi {

        final UiObject nameField;
        final UiObject titleField;
        final UiObject descField;
        final UiObject okButton;
        final UiObject cancelButton;

        /**
         * Gets the UI objects by opening the progress notification and clicking on DETAILS.
         *
         * @param id bugreport id
         */
        DetailsUi(int id) throws UiObjectNotFoundException {
            this(id, true);
        }

        /**
         * Gets the UI objects by opening the progress notification and clicking on DETAILS or in
         * the notification itself.
         *
         * @param id bugreport id
         */
        DetailsUi(int id, boolean clickDetails) throws UiObjectNotFoundException {
            openProgressNotification(mProgressTitle);
            final UiObject notification = mUiBot.getObject(mProgressTitle);
            final UiObject detailsButton = mUiBot.getObject(mContext.getString(
                    R.string.bugreport_info_action));

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
            final UiObject detailsButton = mUiBot.getObject(mContext.getString(
                    R.string.bugreport_info_action));
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
