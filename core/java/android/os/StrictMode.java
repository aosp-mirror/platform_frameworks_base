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
import android.util.Printer;

import com.android.internal.os.RuntimeInit;

import dalvik.system.BlockGuard;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * <p>StrictMode lets you impose stricter rules under which your
 * application runs.</p>
 */
public final class StrictMode {
    private static final String TAG = "StrictMode";
    private static final boolean LOG_V = false;

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

    /**
     * Non-public penalty mode which overrides all the other penalty
     * bits and signals that we're in a Binder call and we should
     * ignore the other penalty bits and instead serialize back all
     * our offending stack traces to the caller to ultimately handle
     * in the originating process.
     *
     * This must be kept in sync with the constant in libs/binder/Parcel.cpp
     *
     * @hide
     */
    public static final int PENALTY_GATHER = 0x100;

    /** @hide */
    public static final int PENALTY_MASK =
            PENALTY_LOG | PENALTY_DIALOG |
            PENALTY_DROPBOX | PENALTY_DEATH;

    /**
     * Log of strict mode violation stack traces that have occurred
     * during a Binder call, to be serialized back later to the caller
     * via Parcel.writeNoException() (amusingly) where the caller can
     * choose how to react.
     */
    private static final ThreadLocal<ArrayList<ViolationInfo>> gatheredViolations =
            new ThreadLocal<ArrayList<ViolationInfo>>() {
        @Override protected ArrayList<ViolationInfo> initialValue() {
            // Starts null to avoid unnecessary allocations when
            // checking whether there are any violations or not in
            // hasGatheredViolations() below.
            return null;
        }
    };

    /**
     * Sets the policy for what actions the current thread is denied,
     * as well as the penalty for violating the policy.
     *
     * @param policyMask a bitmask of DISALLOW_* and PENALTY_* values.
     */
    public static void setThreadBlockingPolicy(final int policyMask) {
        // In addition to the Java-level thread-local in Dalvik's
        // BlockGuard, we also need to keep a native thread-local in
        // Binder in order to propagate the value across Binder calls,
        // even across native-only processes.  The two are kept in
        // sync via the callback to onStrictModePolicyChange, below.
        setBlockGuardPolicy(policyMask);

        // And set the Android native version...
        Binder.setThreadStrictModePolicy(policyMask);
    }

    // Sets the policy in Dalvik/libcore (BlockGuard)
    private static void setBlockGuardPolicy(final int policyMask) {
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

    private static class StrictModeNetworkViolation extends BlockGuard.BlockGuardPolicyException {
        public StrictModeNetworkViolation(int policyMask) {
            super(policyMask, DISALLOW_NETWORK);
        }
    }

    private static class StrictModeDiskReadViolation extends BlockGuard.BlockGuardPolicyException {
        public StrictModeDiskReadViolation(int policyMask) {
            super(policyMask, DISALLOW_DISK_READ);
        }
    }

    private static class StrictModeDiskWriteViolation extends BlockGuard.BlockGuardPolicyException {
        public StrictModeDiskWriteViolation(int policyMask) {
            super(policyMask, DISALLOW_DISK_WRITE);
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

    /**
     * Parses the BlockGuard policy mask out from the Exception's
     * getMessage() String value.  Kinda gross, but least
     * invasive.  :/
     *
     * Input is of form "policy=137 violation=64"
     *
     * Returns 0 on failure, which is a valid policy, but not a
     * valid policy during a violation (else there must've been
     * some policy in effect to violate).
     */
    private static int parsePolicyFromMessage(String message) {
        if (message == null || !message.startsWith("policy=")) {
            return 0;
        }
        int spaceIndex = message.indexOf(' ');
        if (spaceIndex == -1) {
            return 0;
        }
        String policyString = message.substring(7, spaceIndex);
        try {
            return Integer.valueOf(policyString).intValue();
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Like parsePolicyFromMessage(), but returns the violation.
     */
    private static int parseViolationFromMessage(String message) {
        if (message == null) {
            return 0;
        }
        int violationIndex = message.indexOf("violation=");
        if (violationIndex == -1) {
            return 0;
        }
        String violationString = message.substring(violationIndex + 10);
        try {
            return Integer.valueOf(violationString).intValue();
        } catch (NumberFormatException e) {
            return 0;
        }
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

        @Override
        public String toString() {
            return "AndroidBlockGuardPolicy; mPolicyMask=" + mPolicyMask;
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
            BlockGuard.BlockGuardPolicyException e = new StrictModeDiskWriteViolation(mPolicyMask);
            e.fillInStackTrace();
            startHandlingViolationException(e);
        }

        // Part of BlockGuard.Policy interface:
        public void onReadFromDisk() {
            if ((mPolicyMask & DISALLOW_DISK_READ) == 0) {
                return;
            }
            BlockGuard.BlockGuardPolicyException e = new StrictModeDiskReadViolation(mPolicyMask);
            e.fillInStackTrace();
            startHandlingViolationException(e);
        }

        // Part of BlockGuard.Policy interface:
        public void onNetwork() {
            if ((mPolicyMask & DISALLOW_NETWORK) == 0) {
                return;
            }
            BlockGuard.BlockGuardPolicyException e = new StrictModeNetworkViolation(mPolicyMask);
            e.fillInStackTrace();
            startHandlingViolationException(e);
        }

        public void setPolicyMask(int policyMask) {
            mPolicyMask = policyMask;
        }

        // Start handling a violation that just started and hasn't
        // actually run yet (e.g. no disk write or network operation
        // has yet occurred).  This sees if we're in an event loop
        // thread and, if so, uses it to roughly measure how long the
        // violation took.
        void startHandlingViolationException(BlockGuard.BlockGuardPolicyException e) {
            final ViolationInfo info = new ViolationInfo(e, e.getPolicy());
            info.violationUptimeMillis = SystemClock.uptimeMillis();
            handleViolationWithTimingAttempt(info);
        }

        private static final ThreadLocal<ArrayList<ViolationInfo>> violationsBeingTimed =
                new ThreadLocal<ArrayList<ViolationInfo>>() {
            @Override protected ArrayList<ViolationInfo> initialValue() {
                return new ArrayList<ViolationInfo>();
            }
        };

        // Attempts to fill in the provided ViolationInfo's
        // durationMillis field if this thread has a Looper we can use
        // to measure with.  We measure from the time of violation
        // until the time the looper is idle again (right before
        // the next epoll_wait)
        void handleViolationWithTimingAttempt(final ViolationInfo info) {
            Looper looper = Looper.myLooper();

            // Without a Looper, we're unable to time how long the
            // violation takes place.  This case should be rare, as
            // most users will care about timing violations that
            // happen on their main UI thread.  Note that this case is
            // also hit when a violation takes place in a Binder
            // thread, in "gather" mode.  In this case, the duration
            // of the violation is computed by the ultimate caller and
            // its Looper, if any.
            // TODO: if in gather mode, ignore Looper.myLooper() and always
            //       go into this immediate mode?
            if (looper == null) {
                info.durationMillis = -1;  // unknown (redundant, already set)
                handleViolation(info);
                return;
            }

            MessageQueue queue = Looper.myQueue();
            final ArrayList<ViolationInfo> records = violationsBeingTimed.get();
            if (records.size() >= 10) {
                // Not worth measuring.  Too many offenses in one loop.
                return;
            }
            records.add(info);
            if (records.size() > 1) {
                // There's already been a violation this loop, so we've already
                // registered an idle handler to process the list of violations
                // at the end of this Looper's loop.
                return;
            }

            queue.addIdleHandler(new MessageQueue.IdleHandler() {
                    public boolean queueIdle() {
                        long loopFinishTime = SystemClock.uptimeMillis();
                        for (int n = 0; n < records.size(); ++n) {
                            ViolationInfo v = records.get(n);
                            v.violationNumThisLoop = n + 1;
                            v.durationMillis =
                                    (int) (loopFinishTime - v.violationUptimeMillis);
                            handleViolation(v);
                        }
                        records.clear();
                        return false;  // remove this idle handler from the array
                    }
                });
        }

        // Note: It's possible (even quite likely) that the
        // thread-local policy mask has changed from the time the
        // violation fired and now (after the violating code ran) due
        // to people who push/pop temporary policy in regions of code,
        // hence the policy being passed around.
        void handleViolation(final ViolationInfo info) {
            if (info == null || info.crashInfo == null || info.crashInfo.stackTrace == null) {
                Log.wtf(TAG, "unexpected null stacktrace");
                return;
            }

            if (LOG_V) Log.d(TAG, "handleViolation; policy=" + info.policy);

            if ((info.policy & PENALTY_GATHER) != 0) {
                ArrayList<ViolationInfo> violations = gatheredViolations.get();
                if (violations == null) {
                    violations = new ArrayList<ViolationInfo>(1);
                    gatheredViolations.set(violations);
                } else if (violations.size() >= 5) {
                    // Too many.  In a loop or something?  Don't gather them all.
                    return;
                }
                for (ViolationInfo previous : violations) {
                    if (info.crashInfo.stackTrace.equals(previous.crashInfo.stackTrace)) {
                        // Duplicate. Don't log.
                        return;
                    }
                }
                violations.add(info);
                return;
            }

            // Not perfect, but fast and good enough for dup suppression.
            Integer crashFingerprint = info.crashInfo.stackTrace.hashCode();
            long lastViolationTime = 0;
            if (mLastViolationTime.containsKey(crashFingerprint)) {
                lastViolationTime = mLastViolationTime.get(crashFingerprint);
            }
            long now = SystemClock.uptimeMillis();
            mLastViolationTime.put(crashFingerprint, now);
            long timeSinceLastViolationMillis = lastViolationTime == 0 ?
                    Long.MAX_VALUE : (now - lastViolationTime);

            if ((info.policy & PENALTY_LOG) != 0 &&
                timeSinceLastViolationMillis > MIN_LOG_INTERVAL_MS) {
                if (info.durationMillis != -1) {
                    Log.d(TAG, "StrictMode policy violation; ~duration=" +
                          info.durationMillis + " ms: " + info.crashInfo.stackTrace);
                } else {
                    Log.d(TAG, "StrictMode policy violation: " + info.crashInfo.stackTrace);
                }
            }

            // The violationMask, passed to ActivityManager, is a
            // subset of the original StrictMode policy bitmask, with
            // only the bit violated and penalty bits to be executed
            // by the ActivityManagerService remaining set.
            int violationMaskSubset = 0;

            if ((info.policy & PENALTY_DIALOG) != 0 &&
                timeSinceLastViolationMillis > MIN_DIALOG_INTERVAL_MS) {
                violationMaskSubset |= PENALTY_DIALOG;
            }

            if ((info.policy & PENALTY_DROPBOX) != 0 && lastViolationTime == 0) {
                violationMaskSubset |= PENALTY_DROPBOX;
            }

            if (violationMaskSubset != 0) {
                int violationBit = parseViolationFromMessage(info.crashInfo.exceptionMessage);
                violationMaskSubset |= violationBit;
                final int savedPolicy = getThreadBlockingPolicy();
                try {
                    // First, remove any policy before we call into the Activity Manager,
                    // otherwise we'll infinite recurse as we try to log policy violations
                    // to disk, thus violating policy, thus requiring logging, etc...
                    // We restore the current policy below, in the finally block.
                    setThreadBlockingPolicy(0);

                    ActivityManagerNative.getDefault().handleApplicationStrictModeViolation(
                        RuntimeInit.getApplicationObject(),
                        violationMaskSubset,
                        info);
                } catch (RemoteException e) {
                    Log.e(TAG, "RemoteException trying to handle StrictMode violation", e);
                } finally {
                    // Restore the policy.
                    setThreadBlockingPolicy(savedPolicy);
                }
            }

            if ((info.policy & PENALTY_DEATH) != 0) {
                System.err.println("StrictMode policy violation with POLICY_DEATH; shutting down.");
                Process.killProcess(Process.myPid());
                System.exit(10);
            }
        }
    }

    /**
     * Called from Parcel.writeNoException()
     */
    /* package */ static boolean hasGatheredViolations() {
        return gatheredViolations.get() != null;
    }

    /**
     * Called from Parcel.writeException(), so we drop this memory and
     * don't incorrectly attribute it to the wrong caller on the next
     * Binder call on this thread.
     */
    /* package */ static void clearGatheredViolations() {
        gatheredViolations.set(null);
    }

    /**
     * Called from Parcel.writeNoException()
     */
    /* package */ static void writeGatheredViolationsToParcel(Parcel p) {
        ArrayList<ViolationInfo> violations = gatheredViolations.get();
        if (violations == null) {
            p.writeInt(0);
        } else {
            p.writeInt(violations.size());
            for (int i = 0; i < violations.size(); ++i) {
                violations.get(i).writeToParcel(p, 0 /* unused flags? */);
            }
            if (LOG_V) Log.d(TAG, "wrote violations to response parcel; num=" + violations.size());
            violations.clear(); // somewhat redundant, as we're about to null the threadlocal
        }
        gatheredViolations.set(null);
    }

    private static class LogStackTrace extends Exception {}

    /**
     * Called from Parcel.readException() when the exception is EX_STRICT_MODE_VIOLATIONS,
     * we here read back all the encoded violations.
     */
    /* package */ static void readAndHandleBinderCallViolations(Parcel p) {
        // Our own stack trace to append
        StringWriter sw = new StringWriter();
        new LogStackTrace().printStackTrace(new PrintWriter(sw));
        String ourStack = sw.toString();

        int policyMask = getThreadBlockingPolicy();
        boolean currentlyGathering = (policyMask & PENALTY_GATHER) != 0;

        int numViolations = p.readInt();
        for (int i = 0; i < numViolations; ++i) {
            if (LOG_V) Log.d(TAG, "strict mode violation stacks read from binder call.  i=" + i);
            ViolationInfo info = new ViolationInfo(p, !currentlyGathering);
            info.crashInfo.stackTrace += "# via Binder call with stack:\n" + ourStack;
            BlockGuard.Policy policy = BlockGuard.getThreadPolicy();
            if (policy instanceof AndroidBlockGuardPolicy) {
                ((AndroidBlockGuardPolicy) policy).handleViolationWithTimingAttempt(info);
            }
        }
    }

    /**
     * Called from android_util_Binder.cpp's
     * android_os_Parcel_enforceInterface when an incoming Binder call
     * requires changing the StrictMode policy mask.  The role of this
     * function is to ask Binder for its current (native) thread-local
     * policy value and synchronize it to libcore's (Java)
     * thread-local policy value.
     */
    private static void onBinderStrictModePolicyChange(int newPolicy) {
        setBlockGuardPolicy(newPolicy);
    }

    /**
     * Parcelable that gets sent in Binder call headers back to callers
     * to report violations that happened during a cross-process call.
     *
     * @hide
     */
    public static class ViolationInfo {
        /**
         * Stack and other stuff info.
         */
        public final ApplicationErrorReport.CrashInfo crashInfo;

        /**
         * The strict mode policy mask at the time of violation.
         */
        public final int policy;

        /**
         * The wall time duration of the violation, when known.  -1 when
         * not known.
         */
        public int durationMillis = -1;

        /**
         * Which violation number this was (1-based) since the last Looper loop,
         * from the perspective of the root caller (if it crossed any processes
         * via Binder calls).  The value is 0 if the root caller wasn't on a Looper
         * thread.
         */
        public int violationNumThisLoop;

        /**
         * The time (in terms of SystemClock.uptimeMillis()) that the
         * violation occurred.
         */
        public long violationUptimeMillis;

        /**
         * Create an uninitialized instance of ViolationInfo
         */
        public ViolationInfo() {
            crashInfo = null;
            policy = 0;
        }

        /**
         * Create an instance of ViolationInfo initialized from an exception.
         */
        public ViolationInfo(Throwable tr, int policy) {
            crashInfo = new ApplicationErrorReport.CrashInfo(tr);
            violationUptimeMillis = SystemClock.uptimeMillis();
            this.policy = policy;
        }

        /**
         * Create an instance of ViolationInfo initialized from a Parcel.
         */
        public ViolationInfo(Parcel in) {
            this(in, false);
        }

        /**
         * Create an instance of ViolationInfo initialized from a Parcel.
         *
         * @param unsetGatheringBit if true, the caller is the root caller
         *   and the gathering penalty should be removed.
         */
        public ViolationInfo(Parcel in, boolean unsetGatheringBit) {
            crashInfo = new ApplicationErrorReport.CrashInfo(in);
            int rawPolicy = in.readInt();
            if (unsetGatheringBit) {
                policy = rawPolicy & ~PENALTY_GATHER;
            } else {
                policy = rawPolicy;
            }
            durationMillis = in.readInt();
            violationNumThisLoop = in.readInt();
            violationUptimeMillis = in.readLong();
        }

        /**
         * Save a ViolationInfo instance to a parcel.
         */
        public void writeToParcel(Parcel dest, int flags) {
            crashInfo.writeToParcel(dest, flags);
            dest.writeInt(policy);
            dest.writeInt(durationMillis);
            dest.writeInt(violationNumThisLoop);
            dest.writeLong(violationUptimeMillis);
        }


        /**
         * Dump a ViolationInfo instance to a Printer.
         */
        public void dump(Printer pw, String prefix) {
            crashInfo.dump(pw, prefix);
            pw.println(prefix + "policy: " + policy);
            if (durationMillis != -1) {
                pw.println(prefix + "durationMillis: " + durationMillis);
            }
            if (violationNumThisLoop != 0) {
                pw.println(prefix + "violationNumThisLoop: " + violationNumThisLoop);
            }
            pw.println(prefix + "violationUptimeMillis: " + violationUptimeMillis);
        }

    }
}
