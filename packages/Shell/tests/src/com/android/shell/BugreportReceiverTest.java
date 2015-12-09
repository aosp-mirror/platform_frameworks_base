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
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemProperties;
import android.service.notification.StatusBarNotification;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.UiObject;
import android.test.InstrumentationTestCase;
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
public class BugreportReceiverTest extends InstrumentationTestCase {

    private static final String TAG = "BugreportReceiverTest";

    // Timeout for UI operations, in milliseconds.
    private static final int TIMEOUT = (int) BugreportProgressService.POLLING_FREQUENCY * 4;

    private static final String ROOT_DIR = "/data/data/com.android.shell/files/bugreports";
    private static final String BUGREPORT_FILE = "test_bugreport.txt";
    private static final String ZIP_FILE = "test_bugreport.zip";
    private static final String PLAIN_TEXT_PATH = ROOT_DIR + "/" + BUGREPORT_FILE;
    private static final String ZIP_PATH = ROOT_DIR + "/" + ZIP_FILE;
    private static final String SCREENSHOT_PATH = ROOT_DIR + "/test_screenshot.png";

    private static final String BUGREPORT_CONTENT = "Dump, might as well dump!\n";
    private static final String SCREENSHOT_CONTENT = "A picture is worth a thousand words!\n";

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
        BugreportPrefs.setWarningState(mContext, BugreportPrefs.STATE_HIDE);
    }

    public void testFullWorkflow() throws Exception {
        final String name = "BUG, Y U NO REPORT?";
        // TODO: call method to remove property instead
        SystemProperties.set("dumpstate.42.progress", "0");
        SystemProperties.set("dumpstate.42.max", "0");

        Intent intent = new Intent(INTENT_BUGREPORT_STARTED);
        intent.putExtra(EXTRA_PID, 42);
        intent.putExtra(EXTRA_NAME, name);
        intent.putExtra(EXTRA_MAX, 1000);
        mContext.sendBroadcast(intent);

        assertProgressNotification(name, "0.00%");

        SystemProperties.set("dumpstate.42.progress", "108");
        assertProgressNotification(name, "10.80%");

        SystemProperties.set("dumpstate.42.progress", "500");
        assertProgressNotification(name, "50.00%");

        SystemProperties.set("dumpstate.42.max", "2000");
        assertProgressNotification(name, "25.00%");

        createTextFile(PLAIN_TEXT_PATH, BUGREPORT_CONTENT);
        createTextFile(SCREENSHOT_PATH, SCREENSHOT_CONTENT);
        Bundle extras = sendBugreportFinishedIntent(42, PLAIN_TEXT_PATH, SCREENSHOT_PATH);
        assertActionSendMultiple(extras, BUGREPORT_CONTENT, SCREENSHOT_CONTENT);

        String service = BugreportProgressService.class.getName();
        assertFalse("Service '" + service + "' is still running", isServiceRunning(service));
    }

    public void testBugreportFinished_withWarning() throws Exception {
        // Explicitly shows the warning.
        BugreportPrefs.setWarningState(mContext, BugreportPrefs.STATE_SHOW);

        // Send notification and click on share.
        createTextFile(PLAIN_TEXT_PATH, BUGREPORT_CONTENT);
        Intent intent = new Intent(INTENT_BUGREPORT_FINISHED);
        intent.putExtra(EXTRA_BUGREPORT, PLAIN_TEXT_PATH);
        mContext.sendBroadcast(intent);
        mUiBot.clickOnNotification(mContext.getString(R.string.bugreport_finished_title));

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
        createTextFile(PLAIN_TEXT_PATH, BUGREPORT_CONTENT);
        createTextFile(SCREENSHOT_PATH, SCREENSHOT_CONTENT);
        Bundle extras = sendBugreportFinishedIntent(PLAIN_TEXT_PATH, SCREENSHOT_PATH);
        assertActionSendMultiple(extras, BUGREPORT_CONTENT, SCREENSHOT_CONTENT);
    }

    public void testBugreportFinished_zippedBugreportAndScreenshot() throws Exception {
        createZipFile(ZIP_PATH, BUGREPORT_FILE, BUGREPORT_CONTENT);
        createTextFile(SCREENSHOT_PATH, SCREENSHOT_CONTENT);
        Bundle extras = sendBugreportFinishedIntent(ZIP_PATH, SCREENSHOT_PATH);
        assertActionSendMultiple(extras, BUGREPORT_CONTENT, SCREENSHOT_CONTENT);
    }

    public void testBugreportFinished_plainBugreportAndNoScreenshot() throws Exception {
        createTextFile(PLAIN_TEXT_PATH, BUGREPORT_CONTENT);
        Bundle extras = sendBugreportFinishedIntent(PLAIN_TEXT_PATH, null);
        assertActionSendMultiple(extras, BUGREPORT_CONTENT, null);
    }

    public void testBugreportFinished_zippedBugreportAndNoScreenshot() throws Exception {
        createZipFile(ZIP_PATH, BUGREPORT_FILE, BUGREPORT_CONTENT);
        Bundle extras = sendBugreportFinishedIntent(ZIP_PATH, null);
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
        String title = mContext.getString(R.string.bugreport_in_progress_title);
        Log.v(TAG, "Looking for progress notification title: '" + title+ "'");
        mUiBot.getNotification(title);
        Log.v(TAG, "Looking for progress notification details: '" + name + "-" + percent + "'");
        mUiBot.getObject(name);
        mUiBot.getObject(percent);
    }

    /**
     * Sends a "bugreport finished" intent and waits for the result.
     *
     * @return extras sent to the bugreport finished consumer.
     */
    private Bundle sendBugreportFinishedIntent(String bugreportPath, String screenshotPath) {
        return sendBugreportFinishedIntent(null, bugreportPath, screenshotPath);
    }

    private Bundle sendBugreportFinishedIntent(Integer pid, String bugreportPath,
            String screenshotPath) {
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

        mUiBot.clickOnNotification(mContext.getString(R.string.bugreport_finished_title));
        mUiBot.chooseActivity(UI_NAME);
        return mListener.getExtras();
    }

    /**
     * Asserts the proper ACTION_SEND_MULTIPLE intent was sent.
     */
    private void assertActionSendMultiple(Bundle extras, String bugreportContent,
            String screenshotContent) throws IOException {
        String body = extras.getString(Intent.EXTRA_TEXT);
        assertContainsRegex("missing build info",
                SystemProperties.get("ro.build.description"), body);
        assertContainsRegex("missing serial number",
                SystemProperties.get("ro.serialno"), body);

        assertEquals("wrong subject", ZIP_FILE, extras.getString(Intent.EXTRA_SUBJECT));

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
}
