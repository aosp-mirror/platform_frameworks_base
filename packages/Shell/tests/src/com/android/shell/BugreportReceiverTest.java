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
import static com.android.shell.BugreportProgressService.EXTRA_BUGREPORT;
import static com.android.shell.BugreportProgressService.EXTRA_MAX;
import static com.android.shell.BugreportProgressService.EXTRA_NAME;
import static com.android.shell.BugreportProgressService.EXTRA_PID;
import static com.android.shell.BugreportProgressService.EXTRA_SCREENSHOT;
import static com.android.shell.BugreportProgressService.INTENT_BUGREPORT_FINISHED;
import static com.android.shell.BugreportProgressService.INTENT_BUGREPORT_STARTED;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import libcore.io.Streams;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningServiceInfo;
import android.app.Instrumentation;
import android.app.NotificationManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemProperties;
import android.service.notification.StatusBarNotification;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.UiObject;
import android.test.InstrumentationTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;

import com.android.shell.ActionSendMultipleConsumerActivity.CustomActionSendMultipleListener;

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
public class BugreportReceiverTest extends InstrumentationTestCase {
    private static final String TAG = "BugreportReceiverTest";

    // Timeout for UI operations, in milliseconds.
    private static final int TIMEOUT = (int) BugreportProgressService.POLLING_FREQUENCY * 4;

    private static final String BUGREPORTS_DIR = "bugreports";
    private static final String BUGREPORT_FILE = "test_bugreport.txt";
    private static final String ZIP_FILE = "test_bugreport.zip";
    private static final String SCREENSHOT_FILE = "test_screenshot.png";

    private static final String BUGREPORT_CONTENT = "Dump, might as well dump!\n";
    private static final String SCREENSHOT_CONTENT = "A picture is worth a thousand words!\n";

    private static final int PID = 42;
    private static final String PROGRESS_PROPERTY = "dumpstate.42.progress";
    private static final String MAX_PROPERTY = "dumpstate.42.max";
    private static final String NAME_PROPERTY = "dumpstate.42.name";
    private static final String NAME = "BUG, Y U NO REPORT?";
    private static final String NEW_NAME = "Bug_Forrest_Bug";
    private static final String TITLE = "Wimbugdom Champion 2015";
    private String mDescription;

    private String mPlainTextPath;
    private String mZipPath;
    private String mScreenshotPath;

    private Context mContext;
    private UiBot mUiBot;
    private CustomActionSendMultipleListener mListener;

    @Override
    protected void setUp() throws Exception {
        Instrumentation instrumentation = getInstrumentation();
        mContext = instrumentation.getTargetContext();
        mUiBot = new UiBot(UiDevice.getInstance(instrumentation), TIMEOUT);
        mListener = ActionSendMultipleConsumerActivity.getListener(mContext);

        cancelExistingNotifications();

        mPlainTextPath = getPath(BUGREPORT_FILE);
        mZipPath = getPath(ZIP_FILE);
        mScreenshotPath = getPath(SCREENSHOT_FILE);
        createTextFile(mPlainTextPath, BUGREPORT_CONTENT);
        createTextFile(mScreenshotPath, SCREENSHOT_CONTENT);
        createZipFile(mZipPath, BUGREPORT_FILE, BUGREPORT_CONTENT);

        // Creates a multi-line description.
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            sb.append("All work and no play makes Shell a dull app!\n");
        }
        mDescription = sb.toString();

        BugreportPrefs.setWarningState(mContext, BugreportPrefs.STATE_HIDE);
    }

    public void testProgress() throws Exception {
        resetProperties();
        sendBugreportStarted(1000);

        assertProgressNotification(NAME, "0.00%");

        SystemProperties.set(PROGRESS_PROPERTY, "108");
        assertProgressNotification(NAME, "10.80%");

        SystemProperties.set(PROGRESS_PROPERTY, "500");
        assertProgressNotification(NAME, "50.00%");

        SystemProperties.set(MAX_PROPERTY, "2000");
        assertProgressNotification(NAME, "25.00%");

        Bundle extras =
                sendBugreportFinishedAndGetSharedIntent(PID, mPlainTextPath, mScreenshotPath);
        assertActionSendMultiple(extras, BUGREPORT_CONTENT, SCREENSHOT_CONTENT);

        assertServiceNotRunning();
    }

    public void testProgress_changeDetails() throws Exception {
        resetProperties();
        sendBugreportStarted(1000);

        DetailsUi detailsUi = new DetailsUi(mUiBot);

        // Check initial name.
        String actualName = detailsUi.nameField.getText().toString();
        assertEquals("Wrong value on field 'name'", NAME, actualName);

        // Change name - it should have changed system property once focus is changed.
        detailsUi.nameField.setText(NEW_NAME);
        detailsUi.focusAwayFromName();
        assertPropertyValue(NAME_PROPERTY, NEW_NAME);

        // Cancel the dialog to make sure property was restored.
        detailsUi.clickCancel();
        assertPropertyValue(NAME_PROPERTY, NAME);

        // Now try to set an invalid name.
        detailsUi.reOpen();
        detailsUi.nameField.setText("/etc/passwd");
        detailsUi.clickOk();
        assertPropertyValue(NAME_PROPERTY, "_etc_passwd");

        // Finally, make the real changes.
        detailsUi.reOpen();
        detailsUi.nameField.setText(NEW_NAME);
        detailsUi.titleField.setText(TITLE);
        detailsUi.descField.setText(mDescription);

        detailsUi.clickOk();

        assertPropertyValue(NAME_PROPERTY, NEW_NAME);
        assertProgressNotification(NEW_NAME, "0.00%");

        Bundle extras = sendBugreportFinishedAndGetSharedIntent(PID, mPlainTextPath,
                mScreenshotPath);
        assertActionSendMultiple(extras, TITLE, mDescription, BUGREPORT_CONTENT, SCREENSHOT_CONTENT);

        assertServiceNotRunning();
    }

    public void testProgress_bugreportFinishedWhileChangingDetails() throws Exception {
        resetProperties();
        sendBugreportStarted(1000);

        DetailsUi detailsUi = new DetailsUi(mUiBot);

        // Finish the bugreport while user's still typing the name.
        detailsUi.nameField.setText(NEW_NAME);
        sendBugreportFinished(PID, mPlainTextPath, mScreenshotPath);

        // Wait until the share notifcation is received...
        mUiBot.getNotification(mContext.getString(R.string.bugreport_finished_title));
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
        Bundle extras = acceptBugreportAndGetSharedIntent();
        assertActionSendMultiple(extras, TITLE, mDescription, BUGREPORT_CONTENT,
                SCREENSHOT_CONTENT);

        assertServiceNotRunning();
    }

    public void testBugreportFinished_withWarning() throws Exception {
        // Explicitly shows the warning.
        BugreportPrefs.setWarningState(mContext, BugreportPrefs.STATE_SHOW);

        // Send notification and click on share.
        sendBugreportFinished(null, mPlainTextPath, null);
        acceptBugreport();

        // Handle the warning
        mUiBot.getVisibleObject(mContext.getString(R.string.bugreport_confirm));
        // TODO: get ok and showMessageAgain from the dialog reference above
        UiObject showMessageAgain =
                mUiBot.getVisibleObject(mContext.getString(R.string.bugreport_confirm_repeat));
        mUiBot.click(showMessageAgain, "show-message-again");
        UiObject ok = mUiBot.getVisibleObject(mContext.getString(com.android.internal.R.string.ok));
        mUiBot.click(ok, "ok");

        // Share the bugreport.
        mUiBot.chooseActivity(UI_NAME);
        Bundle extras = mListener.getExtras();
        assertActionSendMultiple(extras, BUGREPORT_CONTENT, null);

        // Make sure it's hidden now.
        int newState = BugreportPrefs.getWarningState(mContext, BugreportPrefs.STATE_UNKNOWN);
        assertEquals("Didn't change state", BugreportPrefs.STATE_HIDE, newState);
    }

    public void testBugreportFinished_plainBugreportAndScreenshot() throws Exception {
        Bundle extras = sendBugreportFinishedAndGetSharedIntent(mPlainTextPath, mScreenshotPath);
        assertActionSendMultiple(extras, BUGREPORT_CONTENT, SCREENSHOT_CONTENT);
    }

    public void testBugreportFinished_zippedBugreportAndScreenshot() throws Exception {
        Bundle extras = sendBugreportFinishedAndGetSharedIntent(mZipPath, mScreenshotPath);
        assertActionSendMultiple(extras, BUGREPORT_CONTENT, SCREENSHOT_CONTENT);
    }

    public void testBugreportFinished_plainBugreportAndNoScreenshot() throws Exception {
        Bundle extras = sendBugreportFinishedAndGetSharedIntent(mPlainTextPath, null);
        assertActionSendMultiple(extras, BUGREPORT_CONTENT, null);
    }

    public void testBugreportFinished_zippedBugreportAndNoScreenshot() throws Exception {
        Bundle extras = sendBugreportFinishedAndGetSharedIntent(mZipPath, null);
        assertActionSendMultiple(extras, BUGREPORT_CONTENT, null);
    }

    private void cancelExistingNotifications() {
        NotificationManager nm = NotificationManager.from(mContext);
        for (StatusBarNotification notification : nm.getActiveNotifications()) {
            int id = notification.getId();
            Log.i(TAG, "Canceling existing notification (id=" + id + ")");
            nm.cancel(id);
        }
    }

    private void assertProgressNotification(String name, String percent) {
        // TODO: it current looks for 3 distinct objects, without taking advantage of their
        // relationship.
        openProgressNotification();
        Log.v(TAG, "Looking for progress notification details: '" + name + "-" + percent + "'");
        mUiBot.getObject(name);
        mUiBot.getObject(percent);
    }

    private void openProgressNotification() {
        String title = mContext.getString(R.string.bugreport_in_progress_title);
        Log.v(TAG, "Looking for progress notification title: '" + title + "'");
        mUiBot.getNotification(title);
    }

    void resetProperties() {
        // TODO: call method to remove property instead
        SystemProperties.set(PROGRESS_PROPERTY, "0");
        SystemProperties.set(MAX_PROPERTY, "0");
    }

    /**
     * Sends a "bugreport started" intent with the default values.
     */
    private void sendBugreportStarted(int max) {
        Intent intent = new Intent(INTENT_BUGREPORT_STARTED);
        intent.putExtra(EXTRA_PID, PID);
        intent.putExtra(EXTRA_NAME, NAME);
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
        return sendBugreportFinishedAndGetSharedIntent(null, bugreportPath, screenshotPath);
    }

    /**
     * Sends a "bugreport finished" intent and waits for the result.
     *
     * @return extras sent in the shared intent.
     */
    private Bundle sendBugreportFinishedAndGetSharedIntent(Integer pid, String bugreportPath,
            String screenshotPath) {
        sendBugreportFinished(pid, bugreportPath, screenshotPath);
        return acceptBugreportAndGetSharedIntent();
    }

    /**
     * Accepts the notification to share the finished bugreport and waits for the result.
     *
     * @return extras sent in the shared intent.
     */
    private Bundle acceptBugreportAndGetSharedIntent() {
        acceptBugreport();
        mUiBot.chooseActivity(UI_NAME);
        return mListener.getExtras();
    }

    /**
     * Accepts the notification to share the finished bugreport.
     */
    private void acceptBugreport() {
        mUiBot.clickOnNotification(mContext.getString(R.string.bugreport_finished_title));
    }

    /**
     * Sends a "bugreport finished" intent.
     */
    private void sendBugreportFinished(Integer pid, String bugreportPath, String screenshotPath) {
        Intent intent = new Intent(INTENT_BUGREPORT_FINISHED);
        if (pid != null) {
            intent.putExtra(EXTRA_PID, pid);
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
     * Asserts the proper ACTION_SEND_MULTIPLE intent was sent.
     */
    private void assertActionSendMultiple(Bundle extras, String bugreportContent,
            String screenshotContent) throws IOException {
        assertActionSendMultiple(extras, ZIP_FILE, null, bugreportContent, screenshotContent);
    }

    private void assertActionSendMultiple(Bundle extras, String subject, String description,
            String bugreportContent, String screenshotContent) throws IOException {
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
        int expectedSize = screenshotContent != null ? 2 : 1;
        assertEquals("wrong number of attachments", expectedSize, attachments.size());

        // Need to interact through all attachments, since order is not guaranteed.
        Uri zipUri = null, screenshotUri = null;
        for (Uri attachment : attachments) {
            if (attachment.getPath().endsWith(".zip")) {
                zipUri = attachment;
            }
            if (attachment.getPath().endsWith(".png")) {
                screenshotUri = attachment;
            }
        }
        assertNotNull("did not get .zip attachment", zipUri);
        assertZipContent(zipUri, BUGREPORT_FILE, BUGREPORT_CONTENT);

        if (screenshotContent != null) {
            assertNotNull("did not get .png attachment", screenshotUri);
            assertContent(screenshotUri, SCREENSHOT_CONTENT);
        } else {
            assertNull("should not have .png attachment", screenshotUri);
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

    private void assertPropertyValue(String key, String expectedValue) {
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

    private static void createTextFile(String path, String content) throws IOException {
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
        File rootDir = new ContextWrapper(mContext).getFilesDir();
        File dir = new File(rootDir, BUGREPORTS_DIR);
        if (!dir.exists()) {
            Log.i(TAG, "Creating directory " + dir);
            assertTrue("Could not create directory " + dir, dir.mkdir());
        }
        String path = new File(dir, file).getAbsolutePath();
        Log.v(TAG, "Path for '" + file + "': " + path);
        return path;
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
         * Gets the UI objects by opening the progress notification and clicking DETAILS.
         */
        DetailsUi(UiBot uiBot) {
            openProgressNotification();
            detailsButton = mUiBot.getVisibleObject(
                    mContext.getString(R.string.bugreport_info_action).toUpperCase());
            mUiBot.click(detailsButton, "details_button");
            // TODO: unhardcode resource ids
            nameField = mUiBot.getVisibleObjectById("com.android.shell:id/name");
            titleField = mUiBot.getVisibleObjectById("com.android.shell:id/title");
            descField = mUiBot.getVisibleObjectById("com.android.shell:id/description");
            okButton = mUiBot.getObjectById("android:id/button1");
            cancelButton = mUiBot.getObjectById("android:id/button2");
        }

        /**
         * Takes focus away from the name field so it can be validated.
         */
        void focusAwayFromName() {
            mUiBot.click(titleField, "title_field"); // Change focus.
            mUiBot.pressBack(); // Dismiss keyboard.
        }

        void reOpen() {
            openProgressNotification();
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
