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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;

import android.Manifest;
import android.content.Context;
import android.os.BugreportManager;
import android.os.BugreportManager.BugreportCallback;
import android.os.BugreportParams;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.test.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;


/**
 * Tests for BugreportManager API.
 */
@RunWith(JUnit4.class)
public class BugreportManagerTest {
    @Rule public TestName name = new TestName();

    private static final String TAG = "BugreportManagerTest";
    private static final long BUGREPORT_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(10);

    private Handler mHandler;
    private Executor mExecutor;
    private BugreportManager mBrm;
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
        mBugreportFd = parcelFd("bugreport_" + name.getMethodName(), ".zip");
        mScreenshotFd = parcelFd("screenshot_" + name.getMethodName(), ".png");

        getPermissions();
    }

    @After
    public void teardown() throws Exception {
        dropPermissions();
    }


    @Test
    public void normalFlow_wifi() throws Exception {
        BugreportCallbackImpl callback = new BugreportCallbackImpl();
        // wifi bugreport does not take screenshot
        mBrm.startBugreport(mBugreportFd, null /*screenshotFd = null*/, wifi(),
                mExecutor, callback);
        waitTillDoneOrTimeout(callback);

        assertThat(callback.isDone()).isTrue();
        // Wifi bugreports should not receive any progress.
        assertThat(callback.hasReceivedProgress()).isFalse();
        // TODO: Because of b/130234145, consent dialog is not shown; so we get a timeout error.
        // When the bug is fixed, accept consent via UIAutomator and verify contents
        // of mBugreportFd.
        assertThat(callback.getErrorCode()).isEqualTo(
                BugreportCallback.BUGREPORT_ERROR_USER_CONSENT_TIMED_OUT);
        assertFdsAreClosed(mBugreportFd);
    }

    @Test
    public void normalFlow_interactive() throws Exception {
        BugreportCallbackImpl callback = new BugreportCallbackImpl();
        // interactive bugreport does not take screenshot
        mBrm.startBugreport(mBugreportFd, null /*screenshotFd = null*/, interactive(),
                mExecutor, callback);

        waitTillDoneOrTimeout(callback);
        assertThat(callback.isDone()).isTrue();
        // Interactive bugreports show progress updates.
        assertThat(callback.hasReceivedProgress()).isTrue();
        assertThat(callback.getErrorCode()).isEqualTo(
                BugreportCallback.BUGREPORT_ERROR_USER_CONSENT_TIMED_OUT);
        assertFdsAreClosed(mBugreportFd);
    }

    @Test
    public void simultaneousBugreportsNotAllowed() throws Exception {
        // Start bugreport #1
        BugreportCallbackImpl callback = new BugreportCallbackImpl();
        mBrm.startBugreport(mBugreportFd, mScreenshotFd, wifi(), mExecutor, callback);

        // Before #1 is done, try to start #2.
        assertThat(callback.isDone()).isFalse();
        BugreportCallbackImpl callback2 = new BugreportCallbackImpl();
        ParcelFileDescriptor bugreportFd2 = parcelFd("bugreport_2_" + name.getMethodName(), ".zip");
        ParcelFileDescriptor screenshotFd2 =
                parcelFd("screenshot_2_" + name.getMethodName(), ".png");
        mBrm.startBugreport(bugreportFd2, screenshotFd2, wifi(), mExecutor, callback2);
        Thread.sleep(500 /* .5s */);

        // Verify #2 encounters an error.
        assertThat(callback2.getErrorCode()).isEqualTo(
                BugreportCallback.BUGREPORT_ERROR_ANOTHER_REPORT_IN_PROGRESS);
        assertFdsAreClosed(bugreportFd2, screenshotFd2);

        // Cancel #1 so we can move on to the next test.
        mBrm.cancelBugreport();
        Thread.sleep(500 /* .5s */);
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
        Thread.sleep(500 /* .5s */);
        assertThat(callback.isDone()).isTrue();
        assertFdsAreClosed(mBugreportFd, mScreenshotFd);
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

    private static ParcelFileDescriptor parcelFd(String prefix, String extension) throws Exception {
        File f = File.createTempFile(prefix, extension);
        f.setReadable(true, true);
        f.setWritable(true, true);

        return ParcelFileDescriptor.open(f,
                ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_APPEND);
    }

    private static void dropPermissions() {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .dropShellPermissionIdentity();
    }

    private static void getPermissions() {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(Manifest.permission.DUMP);
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

    private static boolean shouldTimeout(long startTimeMs) {
        return now() - startTimeMs >= BUGREPORT_TIMEOUT_MS;
    }

    private static void waitTillDoneOrTimeout(BugreportCallbackImpl callback) throws Exception {
        long startTimeMs = now();
        while (!callback.isDone()) {
            Thread.sleep(1000 /* 1s */);
            if (shouldTimeout(startTimeMs)) {
                break;
            }
            Log.d(TAG, "Waited " + (now() - startTimeMs) + "ms");
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
}
