/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.wm;

import static android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_DOCUMENT;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertFalse;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Debug;
import android.os.StrictMode;
import android.os.strictmode.InstanceCountViolation;
import android.util.Log;

import com.android.server.wm.utils.CommonUtils;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for Activity leaks.
 *
 * Build/Install/Run:
 *     atest WmTests:ActivityLeakTests
 */
public class ActivityLeakTests {

    private final Instrumentation mInstrumentation = getInstrumentation();
    private final Context mContext = mInstrumentation.getTargetContext();
    private final List<Activity> mStartedActivityList = new ArrayList<>();

    @After
    public void tearDown() {
        mInstrumentation.runOnMainSync(() -> {
            // Reset strict mode.
            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().build());
        });
        for (Activity activity : mStartedActivityList) {
            if (!activity.isDestroyed()) {
                activity.finish();
            }
        }
        if (!mStartedActivityList.isEmpty()) {
            CommonUtils.waitUntilActivityRemoved(
                    mStartedActivityList.get(mStartedActivityList.size() - 1));
        }
        mStartedActivityList.clear();
    }

    @Test
    public void testActivityLeak() {
        final Bundle intentExtras = new Bundle();
        intentExtras.putBoolean(DetectLeakActivity.ENABLE_STRICT_MODE, true);
        final DetectLeakActivity activity = (DetectLeakActivity) startActivity(
                DetectLeakActivity.class, 0 /* flags */, intentExtras);
        mStartedActivityList.add(activity);

        activity.finish();

        assertFalse("Leak found on activity", activity.isLeakedAfterDestroy());
    }

    @Test
    public void testActivityLeakForTwoInstances() {
        final Bundle intentExtras = new Bundle();

        // Launch an activity, then enable strict mode
        intentExtras.putBoolean(DetectLeakActivity.ENABLE_STRICT_MODE, true);
        final DetectLeakActivity activity1 = (DetectLeakActivity) startActivity(
                DetectLeakActivity.class, 0 /* flags */, intentExtras);
        mStartedActivityList.add(activity1);

        // Launch second activity instance.
        intentExtras.putBoolean(DetectLeakActivity.ENABLE_STRICT_MODE, false);
        final DetectLeakActivity activity2 = (DetectLeakActivity) startActivity(
                DetectLeakActivity.class,
                FLAG_ACTIVITY_MULTIPLE_TASK | FLAG_ACTIVITY_NEW_DOCUMENT, intentExtras);
        mStartedActivityList.add(activity2);

        // Destroy the activity
        activity1.finish();
        assertFalse("Leak found on activity 1", activity1.isLeakedAfterDestroy());

        activity2.finish();
        assertFalse("Leak found on activity 2", activity2.isLeakedAfterDestroy());
    }

    private Activity startActivity(Class<?> cls, int flags, Bundle extras) {
        final Intent intent = new Intent(mContext, cls);
        intent.addFlags(flags | FLAG_ACTIVITY_NEW_TASK);
        if (extras != null) {
            intent.putExtras(extras);
        }
        return mInstrumentation.startActivitySync(intent);
    }

    public static class DetectLeakActivity extends Activity {

        private static final String TAG = "DetectLeakActivity";

        public static final String ENABLE_STRICT_MODE = "enable_strict_mode";

        private volatile boolean mWasDestroyed;
        private volatile boolean mIsLeaked;

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            if (getIntent().getBooleanExtra(ENABLE_STRICT_MODE, false)) {
                enableStrictMode();
            }
        }

        @Override
        protected void onDestroy() {
            super.onDestroy();
            getWindow().getDecorView().post(() -> {
                synchronized (this) {
                    mWasDestroyed = true;
                    notifyAll();
                }
            });
        }

        public boolean isLeakedAfterDestroy() {
            synchronized (this) {
                while (!mWasDestroyed && !mIsLeaked) {
                    try {
                        wait(5000 /* timeoutMs */);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
            return mIsLeaked;
        }

        private void enableStrictMode() {
            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                    .detectActivityLeaks()
                    .penaltyLog()
                    .penaltyListener(Runnable::run, violation -> {
                        if (!(violation instanceof InstanceCountViolation)) {
                            return;
                        }
                        synchronized (this) {
                            mIsLeaked = true;
                            notifyAll();
                        }
                        Log.w(TAG, violation.toString() + ", " + dumpHprofData());
                    })
                    .build());
        }

        private String dumpHprofData() {
            try {
                final String fileName = getDataDir().getPath() + "/ActivityLeakHeapDump.hprof";
                Debug.dumpHprofData(fileName);
                return "memory dump filename: " + fileName;
            } catch (Throwable e) {
                Log.e(TAG, "dumpHprofData failed", e);
                return "failed to save memory dump";
            }
        }
    }
}
