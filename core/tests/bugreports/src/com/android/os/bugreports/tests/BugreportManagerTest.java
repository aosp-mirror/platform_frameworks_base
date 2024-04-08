/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.os.bugreports.tests;

import static android.content.Context.RECEIVER_EXPORTED;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BugreportManager;
import android.os.BugreportManager.BugreportCallback;
import android.os.BugreportParams;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.StrictMode;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import com.google.common.io.ByteStreams;
import com.google.common.io.Files;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExternalResource;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Tests for BugreportManager API.
 */
@RunWith(JUnit4.class)
public class BugreportManagerTest {
    @Rule public TestName name = new TestName();
    @Rule public ExtendedStrictModeVmPolicy mTemporaryVmPolicy = new ExtendedStrictModeVmPolicy();

    private static final String TAG = "BugreportManagerTest";
    private static final long BUGREPORT_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(10);
    private static final long DUMPSTATE_STARTUP_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10);
    private static final long DUMPSTATE_TEARDOWN_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10);
    private static final long UIAUTOMATOR_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10);


    // A small timeout used when waiting for the result of a BugreportCallback to be received.
    // This value must be at least 1000ms since there is an intentional delay in
    // BugreportManagerServiceImpl in the error case.
    private static final long CALLBACK_RESULT_TIMEOUT_MS = 1500;

    // Sent by Shell when its bugreport finishes (contains final bugreport/screenshot file name
    // associated with the bugreport).
    private static final String INTENT_BUGREPORT_FINISHED =
            "com.android.internal.intent.action.BUGREPORT_FINISHED";
    private static final String EXTRA_BUGREPORT = "android.intent.extra.BUGREPORT";
    private static final String EXTRA_SCREENSHOT = "android.intent.extra.SCREENSHOT";

    private static final Path[] UI_TRACES_PREDUMPED = {
            Paths.get("/data/misc/perfetto-traces/bugreport/systrace.pftrace"),
            Paths.get("/data/misc/wmtrace/ime_trace_clients.winscope"),
            Paths.get("/data/misc/wmtrace/ime_trace_managerservice.winscope"),
            Paths.get("/data/misc/wmtrace/ime_trace_service.winscope"),
            Paths.get("/data/misc/wmtrace/wm_trace.winscope"),
            Paths.get("/data/misc/wmtrace/wm_log.winscope"),
    };

    private Handler mHandler;
    private Executor mExecutor;
    private BugreportManager mBrm;
    private File mBugreportFile;
    private File mScreenshotFile;
    private ParcelFileDescriptor mBugreportFd;
    private ParcelFileDescriptor mScreenshotFd;

    @Before
    public void setup() throws Exception {
        mHandler = createHandler();
        mExecutor = (runnable) -> {
            if (mHandler != null) {
                mHandler.post(() -> {
                    runnable.run();
                });
            }
        };

        mBrm = getBugreportManager();
        mBugreportFile = createTempFile("bugreport_" + name.getMethodName(), ".zip");
        mScreenshotFile = createTempFile("screenshot_" + name.getMethodName(), ".png");
        mBugreportFd = parcelFd(mBugreportFile);
        mScreenshotFd = parcelFd(mScreenshotFile);

        getPermissions();
    }

    @After
    public void teardown() throws Exception {
        dropPermissions();
        FileUtils.closeQuietly(mBugreportFd);
        FileUtils.closeQuietly(mScreenshotFd);
    }

    @Test
    public void normalFlow_wifi() throws Exception {
        BugreportCallbackImpl callback = new BugreportCallbackImpl();
        // wifi bugreport does not take screenshot
        mBrm.startBugreport(mBugreportFd, null /*screenshotFd = null*/, wifi(),
                mExecutor, callback);
        shareConsentDialog(ConsentReply.ALLOW);
        waitTillDoneOrTimeout(callback);

        assertThat(callback.isDone()).isTrue();
        // Wifi bugreports should not receive any progress.
        assertThat(callback.hasReceivedProgress()).isFalse();
        assertThat(mBugreportFile.length()).isGreaterThan(0L);
        assertThat(callback.hasEarlyReportFinished()).isTrue();
        assertFdsAreClosed(mBugreportFd);
    }

    @LargeTest
    @Test
    public void normalFlow_interactive() throws Exception {
        BugreportCallbackImpl callback = new BugreportCallbackImpl();
        // interactive bugreport does not take screenshot
        mBrm.startBugreport(mBugreportFd, null /*screenshotFd = null*/, interactive(),
                mExecutor, callback);
        shareConsentDialog(ConsentReply.ALLOW);
        waitTillDoneOrTimeout(callback);

        assertThat(callback.isDone()).isTrue();
        // Interactive bugreports show progress updates.
        assertThat(callback.hasReceivedProgress()).isTrue();
        assertThat(mBugreportFile.length()).isGreaterThan(0L);
        assertThat(callback.hasEarlyReportFinished()).isTrue();
        assertFdsAreClosed(mBugreportFd);
    }

    @LargeTest
    @Test
    public void normalFlow_full() throws Exception {
        BugreportCallbackImpl callback = new BugreportCallbackImpl();
        mBrm.startBugreport(mBugreportFd, mScreenshotFd, full(), mExecutor, callback);
        shareConsentDialog(ConsentReply.ALLOW);
        waitTillDoneOrTimeout(callback);

        assertThat(callback.isDone()).isTrue();
        // bugreport and screenshot files shouldn't be empty when user consents.
        assertThat(mBugreportFile.length()).isGreaterThan(0L);
        assertThat(mScreenshotFile.length()).isGreaterThan(0L);
        assertFdsAreClosed(mBugreportFd, mScreenshotFd);
    }

    @LargeTest
    @Test
    public void preDumpUiData_then_fullWithUsePreDumpFlag() throws Exception {
        startPreDumpedUiTraces();

        mBrm.preDumpUiData();
        waitTillDumpstateExitedOrTimeout();
        List<File> expectedPreDumpedTraceFiles = copyFiles(UI_TRACES_PREDUMPED);

        BugreportCallbackImpl callback = new BugreportCallbackImpl();
        mBrm.startBugreport(mBugreportFd, null, fullWithUsePreDumpFlag(), mExecutor,
                callback);
        shareConsentDialog(ConsentReply.ALLOW);
        waitTillDoneOrTimeout(callback);

        stopPreDumpedUiTraces();

        assertThat(callback.isDone()).isTrue();
        assertThat(mBugreportFile.length()).isGreaterThan(0L);
        assertFdsAreClosed(mBugreportFd);

        assertThatBugreportContainsFiles(UI_TRACES_PREDUMPED);

        List<File> actualPreDumpedTraceFiles = extractFilesFromBugreport(UI_TRACES_PREDUMPED);
        assertThatAllFileContentsAreEqual(actualPreDumpedTraceFiles, expectedPreDumpedTraceFiles);
    }

    @LargeTest
    @Test
    public void preDumpData_then_fullWithoutUsePreDumpFlag_ignoresPreDump() throws Exception {
        startPreDumpedUiTraces();

        // Simulate pre-dump, instead of taking a real one.
        // In some corner cases, data dumped as part of the full bugreport could be the same as the
        // pre-dumped data and this test would fail. Hence, here we create fake/artificial
        // pre-dumped data that we know it won't match with the full bugreport data.
        createFakeTraceFiles(UI_TRACES_PREDUMPED);

        List<File> preDumpedTraceFiles = copyFiles(UI_TRACES_PREDUMPED);

        BugreportCallbackImpl callback = new BugreportCallbackImpl();
        mBrm.startBugreport(mBugreportFd, null, full(), mExecutor,
                callback);
        shareConsentDialog(ConsentReply.ALLOW);
        waitTillDoneOrTimeout(callback);

        stopPreDumpedUiTraces();

        assertThat(callback.isDone()).isTrue();
        assertThat(mBugreportFile.length()).isGreaterThan(0L);
        assertFdsAreClosed(mBugreportFd);

        assertThatBugreportContainsFiles(UI_TRACES_PREDUMPED);

        List<File> actualTraceFiles = extractFilesFromBugreport(UI_TRACES_PREDUMPED);
        assertThatAllFileContentsAreDifferent(preDumpedTraceFiles, actualTraceFiles);
    }

    @LargeTest
    @Test
    public void noPreDumpData_then_fullWithUsePreDumpFlag_ignoresFlag() throws Exception {
        startPreDumpedUiTraces();

        mBrm.preDumpUiData();
        waitTillDumpstateExitedOrTimeout();

        // Simulate lost of pre-dumped data.
        // For example it can happen in this scenario:
        // 1. Pre-dump data
        // 2. Start bugreport + "use pre-dump" flag (USE AND REMOVE THE PRE-DUMP FROM DISK)
        // 3. Start bugreport + "use pre-dump" flag (NO PRE-DUMP AVAILABLE ON DISK)
        removeFilesIfNeeded(UI_TRACES_PREDUMPED);

        // Start bugreport with "use predump" flag. Because the pre-dumped data is not available
        // the flag will be ignored and data will be dumped as in normal flow.
        BugreportCallbackImpl callback = new BugreportCallbackImpl();
        mBrm.startBugreport(mBugreportFd, null, fullWithUsePreDumpFlag(), mExecutor,
                callback);
        shareConsentDialog(ConsentReply.ALLOW);
        waitTillDoneOrTimeout(callback);

        stopPreDumpedUiTraces();

        assertThat(callback.isDone()).isTrue();
        assertThat(mBugreportFile.length()).isGreaterThan(0L);
        assertFdsAreClosed(mBugreportFd);

        assertThatBugreportContainsFiles(UI_TRACES_PREDUMPED);
    }

    @Test
    public void simultaneousBugreportsNotAllowed() throws Exception {
        // Start bugreport #1
        BugreportCallbackImpl callback = new BugreportCallbackImpl();
        mBrm.startBugreport(mBugreportFd, mScreenshotFd, wifi(), mExecutor, callback);
        // TODO(b/162389762) Make sure the wait time is reasonable
        shareConsentDialog(ConsentReply.ALLOW);

        // Before #1 is done, try to start #2.
        assertThat(callback.isDone()).isFalse();
        BugreportCallbackImpl callback2 = new BugreportCallbackImpl();
        File bugreportFile2 = createTempFile("bugreport_2_" + name.getMethodName(), ".zip");
        File screenshotFile2 = createTempFile("screenshot_2_" + name.getMethodName(), ".png");
        ParcelFileDescriptor bugreportFd2 = parcelFd(bugreportFile2);
        ParcelFileDescriptor screenshotFd2 = parcelFd(screenshotFile2);
        mBrm.startBugreport(bugreportFd2, screenshotFd2, wifi(), mExecutor, callback2);
        Thread.sleep(CALLBACK_RESULT_TIMEOUT_MS);

        // Verify #2 encounters an error.
        assertThat(callback2.getErrorCode()).isEqualTo(
                BugreportCallback.BUGREPORT_ERROR_ANOTHER_REPORT_IN_PROGRESS);
        assertFdsAreClosed(bugreportFd2, screenshotFd2);

        // Cancel #1 so we can move on to the next test.
        mBrm.cancelBugreport();
        waitTillDoneOrTimeout(callback);
        assertThat(callback.isDone()).isTrue();
        assertFdsAreClosed(mBugreportFd, mScreenshotFd);
    }

    @Test
    public void cancelBugreport() throws Exception {
        // Start a bugreport.
        BugreportCallbackImpl callback = new BugreportCallbackImpl();
        mBrm.startBugreport(mBugreportFd, mScreenshotFd, wifi(), mExecutor, callback);

        // Verify it's not finished yet.
        assertThat(callback.isDone()).isFalse();

        // Try to cancel it, but first without DUMP permission.
        dropPermissions();
        try {
            mBrm.cancelBugreport();
            fail("Expected cancelBugreport to throw SecurityException without DUMP permission");
        } catch (SecurityException expected) {
        }
        assertThat(callback.isDone()).isFalse();

        // Try again, with DUMP permission.
        getPermissions();
        mBrm.cancelBugreport();
        waitTillDoneOrTimeout(callback);
        assertThat(callback.isDone()).isTrue();
        assertFdsAreClosed(mBugreportFd, mScreenshotFd);
    }

    @Test
    public void cancelBugreport_noReportStarted() throws Exception {
        // Without the native DumpstateService running, we don't get a SecurityException.
        mBrm.cancelBugreport();
    }

    @LargeTest
    @Test
    public void cancelBugreport_fromDifferentUid() throws Exception {
        assertThat(Process.myUid()).isNotEqualTo(Process.SHELL_UID);

        // Start a bugreport through ActivityManager's shell command - this starts a BR from the
        // shell UID rather than our own.
        BugreportBroadcastReceiver br = new BugreportBroadcastReceiver();
        InstrumentationRegistry.getContext()
                .registerReceiver(
                        br,
                        new IntentFilter(INTENT_BUGREPORT_FINISHED),
                        RECEIVER_EXPORTED);
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                .executeShellCommand("am bug-report");

        // The command triggers the report through a broadcast, so wait until dumpstate actually
        // starts up, which may take a bit.
        waitTillDumpstateRunningOrTimeout();

        try {
            mBrm.cancelBugreport();
            fail("Expected cancelBugreport to throw SecurityException when report started by "
                    + "different UID");
        } catch (SecurityException expected) {
        } finally {
            // Do this in the finally block so that even if this test case fails, we don't break
            // other test cases unexpectedly due to the still-running shell report.
            try {
                // The shell's BR is still running and should complete successfully.
                br.waitForBugreportFinished();
            } finally {
                // The latch may fail for a number of reasons but we still need to unregister the
                // BroadcastReceiver.
                InstrumentationRegistry.getContext().unregisterReceiver(br);
            }
        }
    }

    @Test
    public void insufficientPermissions_throwsException() throws Exception {
        dropPermissions();

        BugreportCallbackImpl callback = new BugreportCallbackImpl();
        try {
            mBrm.startBugreport(mBugreportFd, mScreenshotFd, wifi(), mExecutor, callback);
            fail("Expected startBugreport to throw SecurityException without DUMP permission");
        } catch (SecurityException expected) {
        }
        assertFdsAreClosed(mBugreportFd, mScreenshotFd);
    }

    @Test
    public void invalidBugreportMode_throwsException() throws Exception {
        BugreportCallbackImpl callback = new BugreportCallbackImpl();

        try {
            mBrm.startBugreport(mBugreportFd, mScreenshotFd,
                    new BugreportParams(25) /* unknown bugreport mode */, mExecutor, callback);
            fail("Expected to throw IllegalArgumentException with unknown bugreport mode");
        } catch (IllegalArgumentException expected) {
        }
        assertFdsAreClosed(mBugreportFd, mScreenshotFd);
    }

    private Handler createHandler() {
        HandlerThread handlerThread = new HandlerThread("BugreportManagerTest");
        handlerThread.start();
        return new Handler(handlerThread.getLooper());
    }

    /* Implementatiion of {@link BugreportCallback} that offers wrappers around execution result */
    private static final class BugreportCallbackImpl extends BugreportCallback {
        private int mErrorCode = -1;
        private boolean mSuccess = false;
        private boolean mReceivedProgress = false;
        private boolean mEarlyReportFinished = false;
        private final Object mLock = new Object();

        @Override
        public void onProgress(float progress) {
            synchronized (mLock) {
                mReceivedProgress = true;
            }
        }

        @Override
        public void onError(int errorCode) {
            synchronized (mLock) {
                mErrorCode = errorCode;
                Log.d(TAG, "bugreport errored.");
            }
        }

        @Override
        public void onFinished() {
            synchronized (mLock) {
                Log.d(TAG, "bugreport finished.");
                mSuccess =  true;
            }
        }

        @Override
        public void onEarlyReportFinished() {
            synchronized (mLock) {
                mEarlyReportFinished = true;
            }
        }

        /* Indicates completion; and ended up with a success or error. */
        public boolean isDone() {
            synchronized (mLock) {
                return (mErrorCode != -1) || mSuccess;
            }
        }

        public int getErrorCode() {
            synchronized (mLock) {
                return mErrorCode;
            }
        }

        public boolean isSuccess() {
            synchronized (mLock) {
                return mSuccess;
            }
        }

        public boolean hasReceivedProgress() {
            synchronized (mLock) {
                return mReceivedProgress;
            }
        }

        public boolean hasEarlyReportFinished() {
            synchronized (mLock) {
                return mEarlyReportFinished;
            }
        }
    }

    public static BugreportManager getBugreportManager() {
        Context context = InstrumentationRegistry.getContext();
        BugreportManager bm =
                (BugreportManager) context.getSystemService(Context.BUGREPORT_SERVICE);
        if (bm == null) {
            throw new AssertionError("Failed to get BugreportManager");
        }
        return bm;
    }
    private static File createTempFile(String prefix, String extension) throws Exception {
        final File f = File.createTempFile(prefix, extension);
        f.setReadable(true, true);
        f.setWritable(true, true);
        f.deleteOnExit();
        return f;
    }

    private static void startPreDumpedUiTraces() throws Exception {
        // Perfetto traces
        String perfettoConfig =
                "buffers: {\n"
                + "    size_kb: 2048\n"
                + "    fill_policy: RING_BUFFER\n"
                + "}\n"
                + "data_sources: {\n"
                + "    config {\n"
                + "        name: \"android.surfaceflinger.transactions\"\n"
                + "    }\n"
                + "}\n"
                + "bugreport_score: 10\n";
        File tmp = createTempFile("tmp", ".cfg");
        Files.write(perfettoConfig.getBytes(StandardCharsets.UTF_8), tmp);
        InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(
                "install -m 644 -o root -g root "
                + tmp.getAbsolutePath() + " /data/misc/perfetto-configs/bugreport-manager-test.cfg"
        );
        InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(
                "perfetto --background-wait"
                + " --config /data/misc/perfetto-configs/bugreport-manager-test.cfg --txt"
                + " --out /data/misc/perfetto-traces/not-used.perfetto-trace"
        );

        // Legacy traces
        InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(
                "cmd input_method tracing start"
        );
        InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(
                "cmd window tracing start"
        );
    }

    private static void stopPreDumpedUiTraces() {
        InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(
                "cmd input_method tracing stop"
        );
        InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(
                "cmd window tracing stop"
        );
        InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(
                "service call SurfaceFlinger 1025 i32 0"
        );
    }

    private void assertThatBugreportContainsFiles(Path[] paths)
            throws IOException {
        List<Path> entries = listZipArchiveEntries(mBugreportFile);
        for (Path pathInDevice : paths) {
            Path pathInArchive = Paths.get("FS" + pathInDevice.toString());
            assertThat(entries).contains(pathInArchive);
        }
    }

    private List<File> extractFilesFromBugreport(Path[] paths) throws Exception {
        List<File> files = new ArrayList<File>();
        for (Path pathInDevice : paths) {
            Path pathInArchive = Paths.get("FS" + pathInDevice.toString());
            files.add(extractZipArchiveEntry(mBugreportFile, pathInArchive));
        }
        return files;
    }

    private static List<Path> listZipArchiveEntries(File archive) throws IOException {
        ArrayList<Path> entries = new ArrayList<>();

        ZipInputStream stream = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(archive)));

        for (ZipEntry entry = stream.getNextEntry(); entry != null; entry = stream.getNextEntry()) {
            entries.add(Paths.get(entry.toString()));
        }

        return entries;
    }

    private static File extractZipArchiveEntry(File archive, Path entryToExtract)
            throws Exception {
        File extractedFile = createTempFile(entryToExtract.getFileName().toString(), ".extracted");

        ZipInputStream is = new ZipInputStream(new FileInputStream(archive));
        boolean hasFoundEntry = false;

        for (ZipEntry entry = is.getNextEntry(); entry != null; entry = is.getNextEntry()) {
            if (entry.toString().equals(entryToExtract.toString())) {
                BufferedOutputStream os =
                        new BufferedOutputStream(new FileOutputStream(extractedFile));
                ByteStreams.copy(is, os);
                os.close();
                hasFoundEntry = true;
                break;
            }

            ByteStreams.exhaust(is); // skip entry
        }

        is.closeEntry();
        is.close();

        assertThat(hasFoundEntry).isTrue();

        return extractedFile;
    }

    private static void createFakeTraceFiles(Path[] paths) throws Exception {
        File src = createTempFile("fake", ".data");
        Files.write("fake data".getBytes(StandardCharsets.UTF_8), src);

        for (Path path : paths) {
            InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(
                    "install -m 644 -o system -g system "
                    + src.getAbsolutePath() + " " + path.toString()
            );
        }

        // Dumpstate executes "perfetto --save-for-bugreport" as shell
        InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(
                "chown shell:shell /data/misc/perfetto-traces/bugreport/systrace.pftrace"
        );
    }

    private static List<File> copyFiles(Path[] paths) throws Exception {
        ArrayList<File> files = new ArrayList<File>();
        for (Path src : paths) {
            File dst = createTempFile(src.getFileName().toString(), ".copy");
            InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(
                    "cp " + src.toString() + " " + dst.getAbsolutePath()
            );
            files.add(dst);
        }
        return files;
    }

    private static void removeFilesIfNeeded(Path[] paths) throws Exception {
        for (Path path : paths) {
            InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(
                    "rm -f " + path.toString()
            );
        }
    }

    private static ParcelFileDescriptor parcelFd(File file) throws Exception {
        return ParcelFileDescriptor.open(file,
                ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_APPEND);
    }

    private static void assertThatAllFileContentsAreEqual(List<File> actual, List<File> expected)
            throws IOException {
        if (actual.size() != expected.size()) {
            fail("File lists have different size");
        }
        for (int i = 0; i < actual.size(); ++i) {
            if (!Files.equal(actual.get(i), expected.get(i))) {
                fail("Contents of " + actual.get(i).toString()
                        + " != " + expected.get(i).toString());
            }
        }
    }

    private static void assertThatAllFileContentsAreDifferent(List<File> a, List<File> b)
            throws IOException {
        if (a.size() != b.size()) {
            fail("File lists have different size");
        }
        for (int i = 0; i < a.size(); ++i) {
            if (Files.equal(a.get(i), b.get(i))) {
                fail("Contents of " + a.get(i).toString() + " == " + b.get(i).toString());
            }
        }
    }

    private static void dropPermissions() {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .dropShellPermissionIdentity();
    }

    private static void getPermissions() {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(Manifest.permission.DUMP);
    }

    private static boolean isDumpstateRunning() {
        String output;
        try {
            output = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                    .executeShellCommand("service list | grep dumpstate");
        } catch (IOException e) {
            Log.w(TAG, "Failed to check if dumpstate is running", e);
            return false;
        }
        for (String line : output.trim().split("\n")) {
            if (line.matches("^.*\\s+dumpstate:\\s+\\[.*\\]$")) {
                return true;
            }
        }
        return false;
    }

    private static void assertFdIsClosed(ParcelFileDescriptor pfd) {
        try {
            int fd = pfd.getFd();
            fail("Expected ParcelFileDescriptor argument to be closed, but got: " + fd);
        } catch (IllegalStateException expected) {
        }
    }

    private static void assertFdsAreClosed(ParcelFileDescriptor... pfds) {
        for (int i = 0; i <  pfds.length; i++) {
            assertFdIsClosed(pfds[i]);
        }
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static void waitTillDumpstateExitedOrTimeout() throws Exception {
        long startTimeMs = now();
        while (isDumpstateRunning()) {
            Thread.sleep(500 /* .5s */);
            if (now() - startTimeMs >= DUMPSTATE_TEARDOWN_TIMEOUT_MS) {
                break;
            }
            Log.d(TAG, "Waited " + (now() - startTimeMs) + "ms for dumpstate to exit");
        }
    }

    private static void waitTillDumpstateRunningOrTimeout() throws Exception {
        long startTimeMs = now();
        while (!isDumpstateRunning()) {
            Thread.sleep(500 /* .5s */);
            if (now() - startTimeMs >= DUMPSTATE_STARTUP_TIMEOUT_MS) {
                break;
            }
            Log.d(TAG, "Waited " + (now() - startTimeMs) + "ms for dumpstate to start");
        }
    }

    private static void waitTillDoneOrTimeout(BugreportCallbackImpl callback) throws Exception {
        long startTimeMs = now();
        while (!callback.isDone()) {
            Thread.sleep(1000 /* 1s */);
            if (now() - startTimeMs >= BUGREPORT_TIMEOUT_MS) {
                break;
            }
            Log.d(TAG, "Waited " + (now() - startTimeMs) + "ms for bugreport to finish");
        }
    }

    /*
     * Returns a {@link BugreportParams} for wifi only bugreport.
     *
     * <p>Wifi bugreports have minimal content and are fast to run. They also suppress progress
     * updates.
     */
    private static BugreportParams wifi() {
        return new BugreportParams(BugreportParams.BUGREPORT_MODE_WIFI);
    }

    /*
     * Returns a {@link BugreportParams} for interactive bugreport that offers progress updates.
     *
     * <p>This is the typical bugreport taken by users. This can take on the order of minutes to
     * finish.
     */
    private static BugreportParams interactive() {
        return new BugreportParams(BugreportParams.BUGREPORT_MODE_INTERACTIVE);
    }

    /*
     * Returns a {@link BugreportParams} for full bugreport that includes a screenshot.
     *
     * <p> This can take on the order of minutes to finish
     */
    private static BugreportParams full() {
        return new BugreportParams(BugreportParams.BUGREPORT_MODE_FULL);
    }

    /*
     * Returns a {@link BugreportParams} for full bugreport that reuses pre-dumped data.
     *
     * <p> This can take on the order of minutes to finish
     */
    private static BugreportParams fullWithUsePreDumpFlag() {
        return new BugreportParams(BugreportParams.BUGREPORT_MODE_FULL,
                BugreportParams.BUGREPORT_FLAG_USE_PREDUMPED_UI_DATA);
    }

    /* Allow/deny the consent dialog to sharing bugreport data or check existence only. */
    private enum ConsentReply {
        ALLOW,
        DENY,
        TIMEOUT
    }

    /*
     * Ensure the consent dialog is shown and take action according to <code>consentReply<code/>.
     * It will fail if the dialog is not shown when <code>ignoreNotFound<code/> is false.
     */
    private void shareConsentDialog(@NonNull ConsentReply consentReply) throws Exception {
        mTemporaryVmPolicy.permitIncorrectContextUse();
        final UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

        // Unlock before finding/clicking an object.
        device.wakeUp();
        device.executeShellCommand("wm dismiss-keyguard");

        final BySelector consentTitleObj = By.res("android", "alertTitle");
        if (!device.wait(Until.hasObject(consentTitleObj), UIAUTOMATOR_TIMEOUT_MS)) {
            fail("The consent dialog is not found");
        }
        if (consentReply.equals(ConsentReply.TIMEOUT)) {
            return;
        }
        final BySelector selector;
        if (consentReply.equals(ConsentReply.ALLOW)) {
            selector = By.res("android", "button1");
            Log.d(TAG, "Allow the consent dialog");
        } else { // ConsentReply.DENY
            selector = By.res("android", "button2");
            Log.d(TAG, "Deny the consent dialog");
        }
        final UiObject2 btnObj = device.findObject(selector);
        assertNotNull("The button of consent dialog is not found", btnObj);
        btnObj.click();

        Log.d(TAG, "Wait for the dialog to be dismissed");
        assertTrue(device.wait(Until.gone(consentTitleObj), UIAUTOMATOR_TIMEOUT_MS));
    }

    private class BugreportBroadcastReceiver extends BroadcastReceiver {
        Intent mBugreportFinishedIntent = null;
        final CountDownLatch mLatch;

        BugreportBroadcastReceiver() {
            mLatch = new CountDownLatch(1);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            setBugreportFinishedIntent(intent);
            mLatch.countDown();
        }

        private void setBugreportFinishedIntent(Intent intent) {
            mBugreportFinishedIntent = intent;
        }

        public Intent getBugreportFinishedIntent() {
            return mBugreportFinishedIntent;
        }

        public void waitForBugreportFinished() throws Exception {
            if (!mLatch.await(BUGREPORT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw new Exception("Failed to receive BUGREPORT_FINISHED in "
                        + BUGREPORT_TIMEOUT_MS + " ms.");
            }
        }
    }

    /**
     * A rule to change strict mode vm policy temporarily till test method finished.
     *
     * To permit the non-visual context usage in tests while taking bugreports need user consent,
     * or UiAutomator/BugreportManager.DumpstateListener would run into error.
     * UiDevice#findObject creates UiObject2, its Gesture object and ViewConfiguration and
     * UiObject2#click need to know bounds. Both of them access to WindowManager internally without
     * visual context comes from InstrumentationRegistry and violate the policy.
     * Also <code>DumpstateListener<code/> violate the policy when onScreenshotTaken is called.
     *
     * TODO(b/161201609) Remove this class once violations fixed.
     */
    static class ExtendedStrictModeVmPolicy extends ExternalResource {
        private boolean mWasVmPolicyChanged = false;
        private StrictMode.VmPolicy mOldVmPolicy;

        @Override
        protected void after() {
            restoreVmPolicyIfNeeded();
        }

        public void permitIncorrectContextUse() {
            // Allow to call multiple times without losing old policy.
            if (mOldVmPolicy == null) {
                mOldVmPolicy = StrictMode.getVmPolicy();
            }
            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .permitIncorrectContextUse()
                    .penaltyLog()
                    .build());
            mWasVmPolicyChanged = true;
        }

        private void restoreVmPolicyIfNeeded() {
            if (mWasVmPolicyChanged && mOldVmPolicy != null) {
                StrictMode.setVmPolicy(mOldVmPolicy);
                mOldVmPolicy = null;
            }
        }
    }
}
