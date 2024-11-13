/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.display;

import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
import static android.util.DisplayMetrics.DENSITY_HIGH;
import static android.util.DisplayMetrics.DENSITY_MEDIUM;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import android.app.ActivityManager;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.platform.test.annotations.AppModeSdkSandbox;
import android.util.Log;
import android.util.SparseArray;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.SystemUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Tests that applications can receive display events correctly.
 */
@RunWith(Parameterized.class)
@AppModeSdkSandbox(reason = "Allow test in the SDK sandbox (does not prevent other modes).")
public class DisplayEventDeliveryTest {
    private static final String TAG = "DisplayEventDeliveryTest";

    private static final String NAME = TAG;
    private static final int WIDTH = 720;
    private static final int HEIGHT = 480;

    private static final int MESSAGE_LAUNCHED = 1;
    private static final int MESSAGE_CALLBACK = 2;

    private static final int DISPLAY_ADDED = 1;
    private static final int DISPLAY_CHANGED = 2;
    private static final int DISPLAY_REMOVED = 3;

    private static final long DISPLAY_EVENT_TIMEOUT_MSEC = 100;
    private static final long TEST_FAILURE_TIMEOUT_MSEC = 10000;

    private static final String TEST_PACKAGE =
            "com.android.servicestests.apps.displaymanagertestapp";
    private static final String TEST_ACTIVITY = TEST_PACKAGE + ".DisplayEventActivity";
    private static final String TEST_DISPLAYS = "DISPLAYS";
    private static final String TEST_MESSENGER = "MESSENGER";

    private final Object mLock = new Object();

    private Instrumentation mInstrumentation;
    private Context mContext;
    private DisplayManager mDisplayManager;
    private ActivityManager mActivityManager;
    private ActivityManager.OnUidImportanceListener mUidImportanceListener;
    private CountDownLatch mLatchActivityLaunch;
    private CountDownLatch mLatchActivityCached;
    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private Messenger mMessenger;
    private int mPid;
    private int mUid;

    /**
     * Array of DisplayBundle. The test handler uses it to check if certain display events have
     * been sent to DisplayEventActivity.
     * Key: displayId of each new VirtualDisplay created by this test
     * Value: DisplayBundle, storing the VirtualDisplay and its expected display events
     *
     * NOTE: The lock is required when adding and removing virtual displays. Otherwise it's not
     * necessary to lock mDisplayBundles when accessing it from the test function.
     */
    @GuardedBy("mLock")
    private SparseArray<DisplayBundle> mDisplayBundles;

    /**
     * Helper class to store VirtualDisplay and its corresponding display events expected to be
     * sent to DisplayEventActivity.
     */
    private static final class DisplayBundle {
        private VirtualDisplay mVirtualDisplay;
        private final int mDisplayId;

        // Display events we expect to receive before timeout
        private final LinkedBlockingQueue<Integer> mExpectations;

        DisplayBundle(VirtualDisplay display) {
            mVirtualDisplay = display;
            mDisplayId = display.getDisplay().getDisplayId();
            mExpectations = new LinkedBlockingQueue<>();
        }

        public void releaseDisplay() {
            if (mVirtualDisplay != null) {
                mVirtualDisplay.release();
            }
            mVirtualDisplay = null;
        }

        /**
         * Add the received display event from the test activity to the queue
         *
         * @param event The corresponding display event
         */
        public void addDisplayEvent(int event) {
            Log.d(TAG, "Received " + mDisplayId + " " + event);
            mExpectations.offer(event);
        }


        /**
         * Assert that there isn't any unexpected display event from the test activity
         */
        public void assertNoDisplayEvents() {
            try {
                assertNull(mExpectations.poll(DISPLAY_EVENT_TIMEOUT_MSEC, TimeUnit.MILLISECONDS));
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        /**
         * Wait for the expected display event from the test activity
         *
         * @param expect The expected display event
         */
        public void waitDisplayEvent(int expect) {
            while (true) {
                try {
                    final Integer event;
                    event = mExpectations.poll(TEST_FAILURE_TIMEOUT_MSEC, TimeUnit.MILLISECONDS);
                    assertNotNull(event);
                    if (expect == event) {
                        Log.d(TAG, "Found    " + mDisplayId + " " + event);
                        return;
                    }
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    /**
     * How many virtual displays to create during the test
     */
    @Parameter(0)
    public int mDisplayCount;

    /**
     * True if running the test activity in cached mode
     * False if running it in non-cached mode
     */
    @Parameter(1)
    public boolean mCached;

    @Parameters(name = "#{index}: {0} {1}")
    public static Iterable<? extends Object> data() {
        return Arrays.asList(new Object[][]{
                {1, false}, {2, false}, {3, false}, {10, false},
                {1, true}, {2, true}, {3, true}, {10, true}
        });
    }

    private class TestHandler extends Handler {
        TestHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            switch (msg.what) {
                case MESSAGE_LAUNCHED:
                    mPid = msg.arg1;
                    mUid = msg.arg2;
                    Log.d(TAG, "Launched " + mPid + " " + mUid);
                    mLatchActivityLaunch.countDown();
                    break;
                case MESSAGE_CALLBACK:
                    Log.d(TAG, "Callback " + msg.arg1 + " " + msg.arg2);
                    synchronized (mLock) {
                        // arg1: displayId
                        DisplayBundle bundle = mDisplayBundles.get(msg.arg1);
                        if (bundle != null) {
                            // arg2: display event
                            bundle.addDisplayEvent(msg.arg2);
                        }
                    }
                    break;
                default:
                    fail("Unexpected value: " + msg.what);
                    break;
            }
        }
    }

    @Before
    public void setUp() throws Exception {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mContext = mInstrumentation.getContext();
        mDisplayManager = mContext.getSystemService(DisplayManager.class);
        mLatchActivityLaunch = new CountDownLatch(1);
        mLatchActivityCached = new CountDownLatch(1);
        mActivityManager = mContext.getSystemService(ActivityManager.class);
        mUidImportanceListener = (uid, importance) -> {
            if (uid == mUid && importance == IMPORTANCE_CACHED) {
                Log.d(TAG, "Listener " + uid + " becomes " + importance);
                mLatchActivityCached.countDown();
            }
        };
        SystemUtil.runWithShellPermissionIdentity(() ->
                mActivityManager.addOnUidImportanceListener(mUidImportanceListener,
                        IMPORTANCE_CACHED));
        // The lock is not functionally necessary but eliminates lint error messages.
        synchronized (mLock) {
            mDisplayBundles = new SparseArray<>();
        }
        mHandlerThread = new HandlerThread("handler");
        mHandlerThread.start();
        mHandler = new TestHandler(mHandlerThread.getLooper());
        mMessenger = new Messenger(mHandler);
        mPid = 0;
    }

    @After
    public void tearDown() throws Exception {
        mActivityManager.removeOnUidImportanceListener(mUidImportanceListener);
        mHandlerThread.quitSafely();
        synchronized (mLock) {
            for (int i = 0; i < mDisplayBundles.size(); i++) {
                DisplayBundle bundle = mDisplayBundles.valueAt(i);
                // Clean up unreleased virtual display
                bundle.releaseDisplay();
            }
            mDisplayBundles.clear();
        }
        SystemUtil.runShellCommand(mInstrumentation, "am force-stop " + TEST_PACKAGE);
    }

    /**
     * Return a display bundle at the stated index.  The bundle is retrieved under lock.
     */
    private DisplayBundle displayBundleAt(int i) {
        synchronized (mLock) {
            return mDisplayBundles.valueAt(i);
        }
    }

    /**
     * Create virtual displays, change their configurations and release them
     * mDisplays: the amount of virtual displays to be created
     * mCached: true to run the test activity in cached mode; false in non-cached mode
     */
    @Test
    public void testDisplayEvents() {
        Log.d(TAG, "Start test testDisplayEvents " + mDisplayCount + " " + mCached);
        // Launch DisplayEventActivity and start listening to display events
        launchTestActivity();

        if (mCached) {
            // The test activity in cached mode won't receive the pending display events
            makeTestActivityCached();
        }

        // Create new virtual displays
        for (int i = 0; i < mDisplayCount; i++) {
            // Lock is needed here to ensure the handler can query the displays
            synchronized (mLock) {
                VirtualDisplay display = createVirtualDisplay(NAME + i);
                DisplayBundle bundle = new DisplayBundle(display);
                mDisplayBundles.put(bundle.mDisplayId, bundle);
            }
        }

        for (int i = 0; i < mDisplayCount; i++) {
            if (mCached) {
                // DISPLAY_ADDED should be deferred for cached process
                displayBundleAt(i).assertNoDisplayEvents();
            } else {
                // DISPLAY_ADDED should arrive immediately for non-cached process
                displayBundleAt(i).waitDisplayEvent(DISPLAY_ADDED);
            }
        }

        // Change the virtual displays
        for (int i = 0; i < mDisplayCount; i++) {
            DisplayBundle bundle = displayBundleAt(i);
            bundle.mVirtualDisplay.resize(WIDTH, HEIGHT, DENSITY_HIGH);
        }

        for (int i = 0; i < mDisplayCount; i++) {
            if (mCached) {
                // DISPLAY_CHANGED should be deferred for cached process
                displayBundleAt(i).assertNoDisplayEvents();
            } else {
                // DISPLAY_CHANGED should arrive immediately for non-cached process
                displayBundleAt(i).waitDisplayEvent(DISPLAY_CHANGED);
            }
        }

        if (mCached) {
            // The test activity becomes non-cached and should receive the pending display events
            bringTestActivityTop();

            for (int i = 0; i < mDisplayCount; i++) {
                // The pending DISPLAY_ADDED & DISPLAY_CHANGED should arrive now
                displayBundleAt(i).waitDisplayEvent(DISPLAY_ADDED);
                displayBundleAt(i).waitDisplayEvent(DISPLAY_CHANGED);
            }
        }

        // Release the virtual displays
        for (int i = 0; i < mDisplayCount; i++) {
            displayBundleAt(i).releaseDisplay();
        }

        // DISPLAY_REMOVED should arrive now
        for (int i = 0; i < mDisplayCount; i++) {
            displayBundleAt(i).waitDisplayEvent(DISPLAY_REMOVED);
        }
    }

    /**
     * Launch the test activity that would listen to display events
     */
    private void launchTestActivity() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setClassName(TEST_PACKAGE, TEST_ACTIVITY);
        intent.putExtra(TEST_MESSENGER, mMessenger);
        intent.putExtra(TEST_DISPLAYS, mDisplayCount);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        SystemUtil.runWithShellPermissionIdentity(
                () -> {
                    mContext.startActivity(intent);
                },
                android.Manifest.permission.START_ACTIVITIES_FROM_SDK_SANDBOX);
        waitLatch(mLatchActivityLaunch);
    }

    /**
     * Bring the test activity back to top
     */
    private void bringTestActivityTop() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setClassName(TEST_PACKAGE, TEST_ACTIVITY);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        SystemUtil.runWithShellPermissionIdentity(
                () -> {
                    mContext.startActivity(intent);
                },
                android.Manifest.permission.START_ACTIVITIES_FROM_SDK_SANDBOX);
    }

    /**
     * Bring the test activity into cached mode by launching another 2 apps
     */
    private void makeTestActivityCached() {
        // Launch another activity to bring the test activity into background
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setClass(mContext, SimpleActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);

        // Launch another activity to bring the test activity into cached mode
        Intent intent2 = new Intent(Intent.ACTION_MAIN);
        intent2.setClass(mContext, SimpleActivity2.class);
        intent2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        SystemUtil.runWithShellPermissionIdentity(
                () -> {
                    mInstrumentation.startActivitySync(intent);
                    mInstrumentation.startActivitySync(intent2);
                },
                android.Manifest.permission.START_ACTIVITIES_FROM_SDK_SANDBOX);
        waitLatch(mLatchActivityCached);
    }

    /**
     * Create a virtual display
     *
     * @param name The name of the new virtual display
     * @return The new virtual display
     */
    private VirtualDisplay createVirtualDisplay(String name) {
        return mDisplayManager.createVirtualDisplay(name, WIDTH, HEIGHT, DENSITY_MEDIUM,
                null /* surface: as we don't actually draw anything, null is enough */,
                VIRTUAL_DISPLAY_FLAG_PUBLIC | VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                /* flags: a public virtual display that another app can access */);
    }

    /**
     * Wait for CountDownLatch with timeout
     */
    private void waitLatch(CountDownLatch latch) {
        try {
            latch.await(TEST_FAILURE_TIMEOUT_MSEC, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
