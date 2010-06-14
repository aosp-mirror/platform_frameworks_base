/*
 * Copyright (C) 2010 The Android Open Source Project
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
package android.os;

import android.app.ActivityManagerNative;
import android.app.ApplicationErrorReport;
import android.util.Log;

import com.android.internal.os.RuntimeInit;

import dalvik.system.BlockGuard;

import java.util.HashMap;

/**
 * <p>StrictMode lets you impose stricter rules under which your
 * application runs.</p>
 */
public final class StrictMode {
    private static final String TAG = "StrictMode";

    // Only log a duplicate stack trace to the logs every second.
    private static final long MIN_LOG_INTERVAL_MS = 1000;

    // Only show an annoying dialog at most every 30 seconds
    private static final long MIN_DIALOG_INTERVAL_MS = 30000;

    private StrictMode() {}

    public static final int DISALLOW_DISK_WRITE = 0x01;
    public static final int DISALLOW_DISK_READ = 0x02;
    public static final int DISALLOW_NETWORK = 0x04;

    /** @hide */
    public static final int DISALLOW_MASK =
            DISALLOW_DISK_WRITE | DISALLOW_DISK_READ | DISALLOW_NETWORK;

    /**
     * Flag to log to the system log.
     */
    public static final int PENALTY_LOG = 0x10;  // normal android.util.Log

    /**
     * Show an annoying dialog to the user.  Will be rate-limited to be only
     * a little annoying.
     */
    public static final int PENALTY_DIALOG = 0x20;

    /**
     * Crash hard if policy is violated.
     */
    public static final int PENALTY_DEATH = 0x40;

    /**
     * Log a stacktrace to the DropBox on policy violation.
     */
    public static final int PENALTY_DROPBOX = 0x80;

    /** @hide */
    public static final int PENALTY_MASK =
            PENALTY_LOG | PENALTY_DIALOG |
            PENALTY_DROPBOX | PENALTY_DEATH;

    /**
     * Sets the policy for what actions the current thread is denied,
     * as well as the penalty for violating the policy.
     *
     * @param policyMask a bitmask of DISALLOW_* and PENALTY_* values.
     */
    public static void setThreadBlockingPolicy(final int policyMask) {
        if (policyMask == 0) {
            BlockGuard.setThreadPolicy(BlockGuard.LAX_POLICY);
            return;
        }
        BlockGuard.Policy policy = BlockGuard.getThreadPolicy();
        if (!(policy instanceof AndroidBlockGuardPolicy)) {
            BlockGuard.setThreadPolicy(new AndroidBlockGuardPolicy(policyMask));
        } else {
            AndroidBlockGuardPolicy androidPolicy = (AndroidBlockGuardPolicy) policy;
            androidPolicy.setPolicyMask(policyMask);
        }
    }

    /**
     * Returns the bitmask of the current thread's blocking policy.
     *
     * @return the bitmask of all the DISALLOW_* and PENALTY_* bits currently enabled
     */
    public static int getThreadBlockingPolicy() {
        return BlockGuard.getThreadPolicy().getPolicyMask();
    }

    private static class AndroidBlockGuardPolicy implements BlockGuard.Policy {
        private int mPolicyMask;

        // Map from violation stacktrace hashcode -> uptimeMillis of
        // last violation.  No locking needed, as this is only
        // accessed by the same thread.
        private final HashMap<Integer, Long> mLastViolationTime = new HashMap<Integer, Long>();

        public AndroidBlockGuardPolicy(final int policyMask) {
            mPolicyMask = policyMask;
        }

        // Part of BlockGuard.Policy interface:
        public int getPolicyMask() {
            return mPolicyMask;
        }

        // Part of BlockGuard.Policy interface:
        public void onWriteToDisk() {
            if ((mPolicyMask & DISALLOW_DISK_WRITE) == 0) {
                return;
            }
            handleViolation(DISALLOW_DISK_WRITE);
        }

        // Part of BlockGuard.Policy interface:
        public void onReadFromDisk() {
            if ((mPolicyMask & DISALLOW_DISK_READ) == 0) {
                return;
            }
            handleViolation(DISALLOW_DISK_READ);
        }

        // Part of BlockGuard.Policy interface:
        public void onNetwork() {
            if ((mPolicyMask & DISALLOW_NETWORK) == 0) {
                return;
            }
            handleViolation(DISALLOW_NETWORK);
        }

        public void setPolicyMask(int policyMask) {
            mPolicyMask = policyMask;
        }

        private void handleViolation(int violationBit) {
            final BlockGuard.BlockGuardPolicyException violation =
                    new BlockGuard.BlockGuardPolicyException(mPolicyMask, violationBit);
            violation.fillInStackTrace();

            Looper looper = Looper.myLooper();
            if (looper == null) {
                // Without a Looper, we're unable to time how long the
                // violation takes place.  This case should be rare,
                // as most users will care about timing violations
                // that happen on their main UI thread.
                handleViolationWithTime(violation, -1L /* no time */);
            } else {
                MessageQueue queue = Looper.myQueue();
                final long violationTime = SystemClock.uptimeMillis();
                queue.addIdleHandler(new MessageQueue.IdleHandler() {
                        public boolean queueIdle() {
                            long afterViolationTime = SystemClock.uptimeMillis();
                            handleViolationWithTime(violation, afterViolationTime - violationTime);
                            return false;  // remove this idle handler from the array
                        }
                    });
            }
        }

        private void handleViolationWithTime(
            BlockGuard.BlockGuardPolicyException violation,
            long durationMillis) {

            // It's possible (even quite likely) that mPolicyMask has
            // changed from the time the violation fired and now
            // (after the violating code ran) due to people who
            // push/pop temporary policy in regions of code.  So use
            // the old policy here.
            int policy = violation.getPolicy();

            // Not _really_ a Crash, but we use the same data structure...
            ApplicationErrorReport.CrashInfo crashInfo =
                    new ApplicationErrorReport.CrashInfo(violation);
            crashInfo.durationMillis = durationMillis;

            // Not perfect, but fast and good enough for dup suppression.
            Integer crashFingerprint = crashInfo.stackTrace.hashCode();
            long lastViolationTime = 0;
            if (mLastViolationTime.containsKey(crashFingerprint)) {
                lastViolationTime = mLastViolationTime.get(crashFingerprint);
            }
            long now = SystemClock.uptimeMillis();
            mLastViolationTime.put(crashFingerprint, now);
            long timeSinceLastViolationMillis = lastViolationTime == 0 ?
                    Long.MAX_VALUE : (now - lastViolationTime);

            if ((policy & PENALTY_LOG) != 0 &&
                timeSinceLastViolationMillis > MIN_LOG_INTERVAL_MS) {
                if (durationMillis != -1) {
                    Log.d(TAG, "StrictMode policy violation; ~duration=" + durationMillis + " ms",
                          violation);
                } else {
                    Log.d(TAG, "StrictMode policy violation.", violation);
                }
            }

            // The violationMask, passed to ActivityManager, is a
            // subset of the original StrictMode policy bitmask, with
            // only the bit violated and penalty bits to be executed
            // by the ActivityManagerService remaining set.
            int violationMask = 0;

            if ((policy & PENALTY_DIALOG) != 0 &&
                timeSinceLastViolationMillis > MIN_DIALOG_INTERVAL_MS) {
                violationMask |= PENALTY_DIALOG;
            }

            if ((policy & PENALTY_DROPBOX) != 0 && lastViolationTime == 0) {
                violationMask |= PENALTY_DROPBOX;
            }

            if (violationMask != 0) {
                violationMask |= violation.getPolicyViolation();
                try {
                    ActivityManagerNative.getDefault().handleApplicationStrictModeViolation(
                        RuntimeInit.getApplicationObject(),
                        violationMask,
                        new ApplicationErrorReport.CrashInfo(violation));
                } catch (RemoteException e) {
                    Log.e(TAG, "RemoteException trying to handle StrictMode violation", e);
                }
            }

            if ((policy & PENALTY_DEATH) != 0) {
                System.err.println("StrictMode policy violation with POLICY_DEATH; shutting down.");
                Process.killProcess(Process.myPid());
                System.exit(10);
            }
        }
    }
}
