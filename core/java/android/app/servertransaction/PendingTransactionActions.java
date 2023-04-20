/*
 * Copyright 2017 The Android Open Source Project
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

package android.app.servertransaction;

import static android.app.ActivityThread.DEBUG_MEMORY_TRIM;

import android.app.ActivityClient;
import android.app.ActivityThread.ActivityClientRecord;
import android.os.Build;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.os.TransactionTooLargeException;
import android.util.Log;
import android.util.Slog;

import com.android.internal.util.IndentingPrintWriter;

import java.io.StringWriter;

/**
 * Container that has data pending to be used at later stages of
 * {@link android.app.servertransaction.ClientTransaction}.
 * An instance of this class is passed to each individual transaction item, so it can use some
 * information from previous steps or add some for the following steps.
 *
 * @hide
 */
public class PendingTransactionActions {
    private boolean mRestoreInstanceState;
    private boolean mCallOnPostCreate;
    private Bundle mOldState;
    private StopInfo mStopInfo;
    private boolean mReportRelaunchToWM;

    public PendingTransactionActions() {
        clear();
    }

    /** Reset the state of the instance to default, non-initialized values. */
    public void clear() {
        mRestoreInstanceState = false;
        mCallOnPostCreate = false;
        mOldState = null;
        mStopInfo = null;
    }

    /** Getter */
    public boolean shouldRestoreInstanceState() {
        return mRestoreInstanceState;
    }

    public void setRestoreInstanceState(boolean restoreInstanceState) {
        mRestoreInstanceState = restoreInstanceState;
    }

    /** Getter */
    public boolean shouldCallOnPostCreate() {
        return mCallOnPostCreate;
    }

    public void setCallOnPostCreate(boolean callOnPostCreate) {
        mCallOnPostCreate = callOnPostCreate;
    }

    public Bundle getOldState() {
        return mOldState;
    }

    public void setOldState(Bundle oldState) {
        mOldState = oldState;
    }

    public StopInfo getStopInfo() {
        return mStopInfo;
    }

    public void setStopInfo(StopInfo stopInfo) {
        mStopInfo = stopInfo;
    }

    /**
     * Check if we should report an activity relaunch to WindowManager. We report back for every
     * relaunch request to ActivityManager, but only for those that were actually finished to we
     * report to WindowManager.
     */
    public boolean shouldReportRelaunchToWindowManager() {
        return mReportRelaunchToWM;
    }

    /**
     * Set if we should report an activity relaunch to WindowManager. We report back for every
     * relaunch request to ActivityManager, but only for those that were actually finished we report
     * to WindowManager.
     */
    public void setReportRelaunchToWindowManager(boolean reportToWm) {
        mReportRelaunchToWM = reportToWm;
    }

    /** Reports to server about activity stop. */
    public static class StopInfo implements Runnable {
        private static final String TAG = "ActivityStopInfo";

        private ActivityClientRecord mActivity;
        private Bundle mState;
        private PersistableBundle mPersistentState;
        private CharSequence mDescription;

        public void setActivity(ActivityClientRecord activity) {
            mActivity = activity;
        }

        public void setState(Bundle state) {
            mState = state;
        }

        public void setPersistentState(PersistableBundle persistentState) {
            mPersistentState = persistentState;
        }

        public void setDescription(CharSequence description) {
            mDescription = description;
        }

        private String collectBundleStates() {
            final StringWriter writer = new StringWriter();
            final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");
            pw.println("Bundle stats:");
            Bundle.dumpStats(pw, mState);
            pw.println("PersistableBundle stats:");
            Bundle.dumpStats(pw, mPersistentState);
            return writer.toString().stripTrailing();
        }

        @Override
        public void run() {
            // Tell activity manager we have been stopped.
            try {
                if (DEBUG_MEMORY_TRIM) Slog.v(TAG, "Reporting activity stopped: " + mActivity);
                // TODO(lifecycler): Use interface callback instead of AMS.
                ActivityClient.getInstance().activityStopped(
                        mActivity.token, mState, mPersistentState, mDescription);
            } catch (RuntimeException runtimeException) {
                // Collect the statistics about bundle
                final String bundleStats = collectBundleStates();

                RuntimeException ex = runtimeException;
                if (ex.getCause() instanceof TransactionTooLargeException) {
                    // Embed the stats into exception message to help developers debug if the
                    // transaction size is too large.
                    final String message = ex.getMessage() + "\n" + bundleStats;
                    ex = new RuntimeException(message, ex.getCause());
                    if (mActivity.packageInfo.getTargetSdkVersion() < Build.VERSION_CODES.N) {
                        Log.e(TAG, "App sent too much data in instance state, so it was ignored",
                                ex);
                        return;
                    }
                } else {
                    // Otherwise, dump the stats anyway.
                    Log.w(TAG, bundleStats);
                }
                throw ex;
            }
        }
    }
}
