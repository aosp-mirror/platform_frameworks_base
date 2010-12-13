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
 * <p>StrictMode is a developer tool which detects things you might be
 * doing by accident and brings them to your attention so you can fix
 * them.
 *
 * <p>StrictMode is most commonly used to catch accidental disk or
 * network access on the application's main thread, where UI
 * operations are received and animations take place.  Keeping disk
 * and network operations off the main thread makes for much smoother,
 * more responsive applications.  By keeping your application's main thread
 * responsive, you also prevent
 * <a href="{@docRoot}guide/practices/design/responsiveness.html">ANR dialogs</a>
 * from being shown to users.
 *
 * <p class="note">Note that even though an Android device's disk is
 * often on flash memory, many devices run a filesystem on top of that
 * memory with very limited concurrency.  It's often the case that
 * almost all disk accesses are fast, but may in individual cases be
 * dramatically slower when certain I/O is happening in the background
 * from other processes.  If possible, it's best to assume that such
 * things are not fast.</p>
 *
 * <p>Example code to enable from early in your
 * {@link android.app.Application}, {@link android.app.Activity}, or
 * other application component's
 * {@link android.app.Application#onCreate} method:
 *
 * <pre>
 * public void onCreate() {
 *     if (DEVELOPER_MODE) {
 *         StrictMode.setThreadPolicy(new {@link ThreadPolicy.Builder StrictMode.ThreadPolicy.Builder}()
 *                 .detectDiskReads()
 *                 .detectDiskWrites()
 *                 .detectNetwork()   // or .detectAll() for all detectable problems
 *                 .penaltyLog()
 *                 .build());
 *         StrictMode.setVmPolicy(new {@link VmPolicy.Builder StrictMode.VmPolicy.Builder}()
 *                 .detectLeakedSqlLiteObjects()
 *                 .penaltyLog()
 *                 .penaltyDeath()
 *                 .build());
 *     }
 *     super.onCreate();
 * }
 * </pre>
 *
 * <p>You can decide what should happen when a violation is detected.
 * For example, using {@link ThreadPolicy.Builder#penaltyLog} you can
 * watch the output of <code>adb logcat</code> while you use your
 * application to see the violations as they happen.
 *
 * <p>If you find violations that you feel are problematic, there are
 * a variety of tools to help solve them: threads, {@link android.os.Handler},
 * {@link android.os.AsyncTask}, {@link android.app.IntentService}, etc.
 * But don't feel compelled to fix everything that StrictMode finds.  In particular,
 * many cases of disk access are often necessary during the normal activity lifecycle.  Use
 * StrictMode to find things you did by accident.  Network requests on the UI thread
 * are almost always a problem, though.
 *
 * <p class="note">StrictMode is not a security mechanism and is not
 * guaranteed to find all disk or network accesses.  While it does
 * propagate its state across process boundaries when doing
 * {@link android.os.Binder} calls, it's still ultimately a best
 * effort mechanism.  Notably, disk or network access from JNI calls
 * won't necessarily trigger it.  Future versions of Android may catch
 * more (or fewer) operations, so you should never leave StrictMode
 * enabled in shipping applications on the Android Market.
 */
public final class StrictMode {
    private static final String TAG = "StrictMode";
    private static final boolean LOG_V = false;

    // Only log a duplicate stack trace to the logs every second.
    private static final long MIN_LOG_INTERVAL_MS = 1000;

    // Only show an annoying dialog at most every 30 seconds
    private static final long MIN_DIALOG_INTERVAL_MS = 30000;

    // How many offending stacks to keep track of (and time) per loop
    // of the Looper.
    private static final int MAX_OFFENSES_PER_LOOP = 10;

    // Thread-policy:

    /**
     * @hide
     */
    public static final int DETECT_DISK_WRITE = 0x01;  // for ThreadPolicy

    /**
      * @hide
     */
    public static final int DETECT_DISK_READ = 0x02;  // for ThreadPolicy

    /**
     * @hide
     */
    public static final int DETECT_NETWORK = 0x04;  // for ThreadPolicy

    // Process-policy:

    /**
     * Note, a "VM_" bit, not thread.
     * @hide
     */
    public static final int DETECT_VM_CURSOR_LEAKS = 0x200;  // for ProcessPolicy

    /**
     * @hide
     */
    public static final int PENALTY_LOG = 0x10;  // normal android.util.Log

    // Used for both process and thread policy:

    /**
     * @hide
     */
    public static final int PENALTY_DIALOG = 0x20;

    /**
     * @hide
     */
    public static final int PENALTY_DEATH = 0x40;

    /**
     * @hide
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

    /**
     * The current VmPolicy in effect.
     */
    private static volatile int sVmPolicyMask = 0;

    private StrictMode() {}

    /**
     * {@link StrictMode} policy applied to a certain thread.
     *
     * <p>The policy is enabled by {@link #setThreadPolicy}.  The current policy
     * can be retrieved with {@link #getThreadPolicy}.
     *
     * <p>Note that multiple penalties may be provided and they're run
     * in order from least to most severe (logging before process
     * death, for example).  There's currently no mechanism to choose
     * different penalties for different detected actions.
     */
    public static final class ThreadPolicy {
        /**
         * The default, lax policy which doesn't catch anything.
         */
        public static final ThreadPolicy LAX = new ThreadPolicy(0);

        final int mask;

        private ThreadPolicy(int mask) {
            this.mask = mask;
        }

        @Override
        public String toString() {
            return "[StrictMode.ThreadPolicy; mask=" + mask + "]";
        }

        /**
         * Creates {@link ThreadPolicy} instances.  Methods whose names start
         * with {@code detect} specify what problems we should look
         * for.  Methods whose names start with {@code penalty} specify what
         * we should do when we detect a problem.
         *
         * <p>You can call as many {@code detect} and {@code penalty}
         * methods as you like. Currently order is insignificant: all
         * penalties apply to all detected problems.
         *
         * <p>For example, detect everything and log anything that's found:
         * <pre>
         * StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder()
         *     .detectAll()
         *     .penaltyLog()
         *     .build();
         * StrictMode.setThreadPolicy(policy);
         * </pre>
         */
        public static final class Builder {
            private int mMask = 0;

            /**
             * Create a Builder that detects nothing and has no
             * violations.  (but note that {@link #build} will default
             * to enabling {@link #penaltyLog} if no other penalties
             * are specified)
             */
            public Builder() {
                mMask = 0;
            }

            /**
             * Initialize a Builder from an existing ThreadPolicy.
             */
            public Builder(ThreadPolicy policy) {
                mMask = policy.mask;
            }

            /**
             * Detect everything that's potentially suspect.
             *
             * <p>As of the Gingerbread release this includes network and
             * disk operations but will likely expand in future releases.
             */
            public Builder detectAll() {
                return enable(DETECT_DISK_WRITE | DETECT_DISK_READ | DETECT_NETWORK);
            }

            /**
             * Disable the detection of everything.
             */
            public Builder permitAll() {
                return disable(DETECT_DISK_WRITE | DETECT_DISK_READ | DETECT_NETWORK);
            }

            /**
             * Enable detection of network operations.
             */
            public Builder detectNetwork() {
                return enable(DETECT_NETWORK);
            }

            /**
             * Disable detection of network operations.
             */
            public Builder permitNetwork() {
                return disable(DETECT_NETWORK);
            }

            /**
             * Enable detection of disk reads.
             */
            public Builder detectDiskReads() {
                return enable(DETECT_DISK_READ);
            }

            /**
             * Disable detection of disk reads.
             */
            public Builder permitDiskReads() {
                return disable(DETECT_DISK_READ);
            }

            /**
             * Enable detection of disk writes.
             */
            public Builder detectDiskWrites() {
                return enable(DETECT_DISK_WRITE);
            }

            /**
             * Disable detection of disk writes.
             */
            public Builder permitDiskWrites() {
                return disable(DETECT_DISK_WRITE);
            }

            /**
             * Show an annoying dialog to the developer on detected
             * violations, rate-limited to be only a little annoying.
             */
            public Builder penaltyDialog() {
                return enable(PENALTY_DIALOG);
            }

            /**
             * Crash the whole process on violation.  This penalty runs at
             * the end of all enabled penalties so you'll still get
             * see logging or other violations before the process dies.
             */
            public Builder penaltyDeath() {
                return enable(PENALTY_DEATH);
            }

            /**
             * Log detected violations to the system log.
             */
            public Builder penaltyLog() {
                return enable(PENALTY_LOG);
            }

            /**
             * Enable detected violations log a stacktrace and timing data
             * to the {@link android.os.DropBoxManager DropBox} on policy
             * violation.  Intended mostly for platform integrators doing
             * beta user field data collection.
             */
            public Builder penaltyDropBox() {
                return enable(PENALTY_DROPBOX);
            }

            private Builder enable(int bit) {
                mMask |= bit;
                return this;
            }

            private Builder disable(int bit) {
                mMask &= ~bit;
                return this;
            }

            /**
             * Construct the ThreadPolicy instance.
             *
             * <p>Note: if no penalties are enabled before calling
             * <code>build</code>, {@link #penaltyLog} is implicitly
             * set.
             */
            public ThreadPolicy build() {
                // If there are detection bits set but no violation bits
                // set, enable simple logging.
                if (mMask != 0 &&
                    (mMask & (PENALTY_DEATH | PENALTY_LOG |
                              PENALTY_DROPBOX | PENALTY_DIALOG)) == 0) {
                    penaltyLog();
                }
                return new ThreadPolicy(mMask);
            }
        }
    }

    /**
     * {@link StrictMode} policy applied to all threads in the virtual machine's process.
     *
     * <p>The policy is enabled by {@link #setVmPolicy}.
     */
    public static final class VmPolicy {
        /**
         * The default, lax policy which doesn't catch anything.
         */
        public static final VmPolicy LAX = new VmPolicy(0);

        final int mask;

        private VmPolicy(int mask) {
            this.mask = mask;
        }

        @Override
        public String toString() {
            return "[StrictMode.VmPolicy; mask=" + mask + "]";
        }

        /**
         * Creates {@link VmPolicy} instances.  Methods whose names start
         * with {@code detect} specify what problems we should look
         * for.  Methods whose names start with {@code penalty} specify what
         * we should do when we detect a problem.
         *
         * <p>You can call as many {@code detect} and {@code penalty}
         * methods as you like. Currently order is insignificant: all
         * penalties apply to all detected problems.
         *
         * <p>For example, detect everything and log anything that's found:
         * <pre>
         * StrictMode.VmPolicy policy = new StrictMode.VmPolicy.Builder()
         *     .detectAll()
         *     .penaltyLog()
         *     .build();
         * StrictMode.setVmPolicy(policy);
         * </pre>
         */
        public static final class Builder {
            private int mMask;

            /**
             * Detect everything that's potentially suspect.
             *
             * <p>As of the Gingerbread release this only includes
             * SQLite cursor leaks but will likely expand in future
             * releases.
             */
            public Builder detectAll() {
                return enable(DETECT_VM_CURSOR_LEAKS);
            }

            /**
             * Detect when an
             * {@link android.database.sqlite.SQLiteCursor} or other
             * SQLite object is finalized without having been closed.
             *
             * <p>You always want to explicitly close your SQLite
             * cursors to avoid unnecessary database contention and
             * temporary memory leaks.
             */
            public Builder detectLeakedSqlLiteObjects() {
                return enable(DETECT_VM_CURSOR_LEAKS);
            }

            /**
             * Crashes the whole process on violation.  This penalty runs at
             * the end of all enabled penalties so yo you'll still get
             * your logging or other violations before the process dies.
             */
            public Builder penaltyDeath() {
                return enable(PENALTY_DEATH);
            }

            /**
             * Log detected violations to the system log.
             */
            public Builder penaltyLog() {
                return enable(PENALTY_LOG);
            }

            /**
             * Enable detected violations log a stacktrace and timing data
             * to the {@link android.os.DropBoxManager DropBox} on policy
             * violation.  Intended mostly for platform integrators doing
             * beta user field data collection.
             */
            public Builder penaltyDropBox() {
                return enable(PENALTY_DROPBOX);
            }

            private Builder enable(int bit) {
                mMask |= bit;
                return this;
            }

            /**
             * Construct the VmPolicy instance.
             *
             * <p>Note: if no penalties are enabled before calling
             * <code>build</code>, {@link #penaltyLog} is implicitly
             * set.
             */
            public VmPolicy build() {
                // If there are detection bits set but no violation bits
                // set, enable simple logging.
                if (mMask != 0 &&
                    (mMask & (PENALTY_DEATH | PENALTY_LOG |
                              PENALTY_DROPBOX | PENALTY_DIALOG)) == 0) {
                    penaltyLog();
                }
                return new VmPolicy(mMask);
            }
        }
    }

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
     * Sets the policy for what actions on the current thread should
     * be detected, as well as the penalty if such actions occur.
     *
     * <p>Internally this sets a thread-local variable which is
     * propagated across cross-process IPC calls, meaning you can
     * catch violations when a system service or another process
     * accesses the disk or network on your behalf.
     *
     * @param policy the policy to put into place
     */
    public static void setThreadPolicy(final ThreadPolicy policy) {
        setThreadPolicyMask(policy.mask);
    }

    private static void setThreadPolicyMask(final int policyMask) {
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
            super(policyMask, DETECT_NETWORK);
        }
    }

    private static class StrictModeDiskReadViolation extends BlockGuard.BlockGuardPolicyException {
        public StrictModeDiskReadViolation(int policyMask) {
            super(policyMask, DETECT_DISK_READ);
        }
    }

    private static class StrictModeDiskWriteViolation extends BlockGuard.BlockGuardPolicyException {
        public StrictModeDiskWriteViolation(int policyMask) {
            super(policyMask, DETECT_DISK_WRITE);
        }
    }

    /**
     * Returns the bitmask of the current thread's policy.
     *
     * @return the bitmask of all the DETECT_* and PENALTY_* bits currently enabled
     *
     * @hide
     */
    public static int getThreadPolicyMask() {
        return BlockGuard.getThreadPolicy().getPolicyMask();
    }

    /**
     * Returns the current thread's policy.
     */
    public static ThreadPolicy getThreadPolicy() {
        return new ThreadPolicy(getThreadPolicyMask());
    }

    /**
     * A convenience wrapper that takes the current
     * {@link ThreadPolicy} from {@link #getThreadPolicy}, modifies it
     * to permit both disk reads &amp; writes, and sets the new policy
     * with {@link #setThreadPolicy}, returning the old policy so you
     * can restore it at the end of a block.
     *
     * @return the old policy, to be passed to {@link #setThreadPolicy} to
     *         restore the policy at the end of a block
     */
    public static ThreadPolicy allowThreadDiskWrites() {
        int oldPolicyMask = getThreadPolicyMask();
        int newPolicyMask = oldPolicyMask & ~(DETECT_DISK_WRITE | DETECT_DISK_READ);
        if (newPolicyMask != oldPolicyMask) {
            setThreadPolicyMask(newPolicyMask);
        }
        return new ThreadPolicy(oldPolicyMask);
    }

    /**
     * A convenience wrapper that takes the current
     * {@link ThreadPolicy} from {@link #getThreadPolicy}, modifies it
     * to permit disk reads, and sets the new policy
     * with {@link #setThreadPolicy}, returning the old policy so you
     * can restore it at the end of a block.
     *
     * @return the old policy, to be passed to setThreadPolicy to
     *         restore the policy.
     */
    public static ThreadPolicy allowThreadDiskReads() {
        int oldPolicyMask = getThreadPolicyMask();
        int newPolicyMask = oldPolicyMask & ~(DETECT_DISK_READ);
        if (newPolicyMask != oldPolicyMask) {
            setThreadPolicyMask(newPolicyMask);
        }
        return new ThreadPolicy(oldPolicyMask);
    }

    /**
     * Enable DropBox logging for debug phone builds.
     *
     * @hide
     */
    public static boolean conditionallyEnableDebugLogging() {
        // For debug builds, log event loop stalls to dropbox for analysis.
        // Similar logic also appears in ActivityThread.java for system apps.
        if ("user".equals(Build.TYPE)) {
            return false;
        }
        StrictMode.setThreadPolicyMask(
            StrictMode.DETECT_DISK_WRITE |
            StrictMode.DETECT_DISK_READ |
            StrictMode.DETECT_NETWORK |
            StrictMode.PENALTY_DROPBOX);
        sVmPolicyMask = StrictMode.DETECT_VM_CURSOR_LEAKS |
                StrictMode.PENALTY_DROPBOX |
                StrictMode.PENALTY_LOG;
        return true;
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

    private static final ThreadLocal<ArrayList<ViolationInfo>> violationsBeingTimed =
            new ThreadLocal<ArrayList<ViolationInfo>>() {
        @Override protected ArrayList<ViolationInfo> initialValue() {
            return new ArrayList<ViolationInfo>();
        }
    };

    private static boolean tooManyViolationsThisLoop() {
        return violationsBeingTimed.get().size() >= MAX_OFFENSES_PER_LOOP;
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
            if ((mPolicyMask & DETECT_DISK_WRITE) == 0) {
                return;
            }
            if (tooManyViolationsThisLoop()) {
                return;
            }
            BlockGuard.BlockGuardPolicyException e = new StrictModeDiskWriteViolation(mPolicyMask);
            e.fillInStackTrace();
            startHandlingViolationException(e);
        }

        // Part of BlockGuard.Policy interface:
        public void onReadFromDisk() {
            if ((mPolicyMask & DETECT_DISK_READ) == 0) {
                return;
            }
            if (tooManyViolationsThisLoop()) {
                return;
            }
            BlockGuard.BlockGuardPolicyException e = new StrictModeDiskReadViolation(mPolicyMask);
            e.fillInStackTrace();
            startHandlingViolationException(e);
        }

        // Part of BlockGuard.Policy interface:
        public void onNetwork() {
            if ((mPolicyMask & DETECT_NETWORK) == 0) {
                return;
            }
            if (tooManyViolationsThisLoop()) {
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
            if (records.size() >= MAX_OFFENSES_PER_LOOP) {
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
                final int savedPolicyMask = getThreadPolicyMask();
                try {
                    // First, remove any policy before we call into the Activity Manager,
                    // otherwise we'll infinite recurse as we try to log policy violations
                    // to disk, thus violating policy, thus requiring logging, etc...
                    // We restore the current policy below, in the finally block.
                    setThreadPolicyMask(0);

                    ActivityManagerNative.getDefault().handleApplicationStrictModeViolation(
                        RuntimeInit.getApplicationObject(),
                        violationMaskSubset,
                        info);
                } catch (RemoteException e) {
                    Log.e(TAG, "RemoteException trying to handle StrictMode violation", e);
                } finally {
                    // Restore the policy.
                    setThreadPolicyMask(savedPolicyMask);
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
     * Sets the policy for what actions in the VM process (on any
     * thread) should be detected, as well as the penalty if such
     * actions occur.
     *
     * @param policy the policy to put into place
     */
    public static void setVmPolicy(final VmPolicy policy) {
        sVmPolicyMask = policy.mask;
    }

    /**
     * Gets the current VM policy.
     */
    public static VmPolicy getVmPolicy() {
        return new VmPolicy(sVmPolicyMask);
    }

    /**
     * Enable the recommended StrictMode defaults, with violations just being logged.
     *
     * <p>This catches disk and network access on the main thread, as
     * well as leaked SQLite cursors.  This is simply a wrapper around
     * {@link #setVmPolicy} and {@link #setThreadPolicy}.
     */
    public static void enableDefaults() {
        StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                                   .detectAll()
                                   .penaltyLog()
                                   .build());
        StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                               .detectLeakedSqlLiteObjects()
                               .penaltyLog()
                               .build());
    }

    /**
     * @hide
     */
    public static boolean vmSqliteObjectLeaksEnabled() {
        return (sVmPolicyMask & DETECT_VM_CURSOR_LEAKS) != 0;
    }

    /**
     * @hide
     */
    public static void onSqliteObjectLeaked(String message, Throwable originStack) {
        if ((sVmPolicyMask & PENALTY_LOG) != 0) {
            Log.e(TAG, message, originStack);
        }

        if ((sVmPolicyMask & PENALTY_DROPBOX) != 0) {
            final ViolationInfo info = new ViolationInfo(originStack, sVmPolicyMask);

            // The violationMask, passed to ActivityManager, is a
            // subset of the original StrictMode policy bitmask, with
            // only the bit violated and penalty bits to be executed
            // by the ActivityManagerService remaining set.
            int violationMaskSubset = PENALTY_DROPBOX | DETECT_VM_CURSOR_LEAKS;
            final int savedPolicyMask = getThreadPolicyMask();
            try {
                // First, remove any policy before we call into the Activity Manager,
                // otherwise we'll infinite recurse as we try to log policy violations
                // to disk, thus violating policy, thus requiring logging, etc...
                // We restore the current policy below, in the finally block.
                setThreadPolicyMask(0);

                ActivityManagerNative.getDefault().handleApplicationStrictModeViolation(
                    RuntimeInit.getApplicationObject(),
                    violationMaskSubset,
                    info);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException trying to handle StrictMode violation", e);
            } finally {
                // Restore the policy.
                setThreadPolicyMask(savedPolicyMask);
            }
        }

        if ((sVmPolicyMask & PENALTY_DEATH) != 0) {
            System.err.println("StrictMode VmPolicy violation with POLICY_DEATH; shutting down.");
            Process.killProcess(Process.myPid());
            System.exit(10);
        }
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

        int policyMask = getThreadPolicyMask();
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
