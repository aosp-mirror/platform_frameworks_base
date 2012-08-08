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

import android.animation.ValueAnimator;
import android.app.ActivityManagerNative;
import android.app.ActivityThread;
import android.app.ApplicationErrorReport;
import android.app.IActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.util.Log;
import android.util.Printer;
import android.util.Singleton;
import android.view.IWindowManager;

import com.android.internal.os.RuntimeInit;

import dalvik.system.BlockGuard;
import dalvik.system.CloseGuard;
import dalvik.system.VMDebug;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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
 *                 .detectLeakedClosableObjects()
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
 * enabled in applications distributed on Google Play.
 */
public final class StrictMode {
    private static final String TAG = "StrictMode";
    private static final boolean LOG_V = Log.isLoggable(TAG, Log.VERBOSE);

    private static final boolean IS_USER_BUILD = "user".equals(Build.TYPE);
    private static final boolean IS_ENG_BUILD = "eng".equals(Build.TYPE);

    /**
     * Boolean system property to disable strict mode checks outright.
     * Set this to 'true' to force disable; 'false' has no effect on other
     * enable/disable policy.
     * @hide
     */
    public static final String DISABLE_PROPERTY = "persist.sys.strictmode.disable";

    /**
     * The boolean system property to control screen flashes on violations.
     *
     * @hide
     */
    public static final String VISUAL_PROPERTY = "persist.sys.strictmode.visual";

    // Only log a duplicate stack trace to the logs every second.
    private static final long MIN_LOG_INTERVAL_MS = 1000;

    // Only show an annoying dialog at most every 30 seconds
    private static final long MIN_DIALOG_INTERVAL_MS = 30000;

    // How many Span tags (e.g. animations) to report.
    private static final int MAX_SPAN_TAGS = 20;

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

    /**
     * For StrictMode.noteSlowCall()
     *
     * @hide
     */
    public static final int DETECT_CUSTOM = 0x08;  // for ThreadPolicy

    private static final int ALL_THREAD_DETECT_BITS =
            DETECT_DISK_WRITE | DETECT_DISK_READ | DETECT_NETWORK | DETECT_CUSTOM;

    // Process-policy:

    /**
     * Note, a "VM_" bit, not thread.
     * @hide
     */
    public static final int DETECT_VM_CURSOR_LEAKS = 0x200;  // for VmPolicy

    /**
     * Note, a "VM_" bit, not thread.
     * @hide
     */
    public static final int DETECT_VM_CLOSABLE_LEAKS = 0x400;  // for VmPolicy

    /**
     * Note, a "VM_" bit, not thread.
     * @hide
     */
    public static final int DETECT_VM_ACTIVITY_LEAKS = 0x800;  // for VmPolicy

    /**
     * @hide
     */
    private static final int DETECT_VM_INSTANCE_LEAKS = 0x1000;  // for VmPolicy

    /**
     * @hide
     */
    public static final int DETECT_VM_REGISTRATION_LEAKS = 0x2000;  // for VmPolicy

    private static final int ALL_VM_DETECT_BITS =
            DETECT_VM_CURSOR_LEAKS | DETECT_VM_CLOSABLE_LEAKS |
            DETECT_VM_ACTIVITY_LEAKS | DETECT_VM_INSTANCE_LEAKS |
            DETECT_VM_REGISTRATION_LEAKS;

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
     * Death on any detected violation.
     *
     * @hide
     */
    public static final int PENALTY_DEATH = 0x40;

    /**
     * Death just for detected network usage.
     *
     * @hide
     */
    public static final int PENALTY_DEATH_ON_NETWORK = 0x200;

    /**
     * Flash the screen during violations.
     *
     * @hide
     */
    public static final int PENALTY_FLASH = 0x800;

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
     * Mask of all the penalty bits valid for thread policies.
     */
    private static final int THREAD_PENALTY_MASK =
            PENALTY_LOG | PENALTY_DIALOG | PENALTY_DEATH | PENALTY_DROPBOX | PENALTY_GATHER |
            PENALTY_DEATH_ON_NETWORK | PENALTY_FLASH;


    /**
     * Mask of all the penalty bits valid for VM policies.
     */
    private static final int VM_PENALTY_MASK =
            PENALTY_LOG | PENALTY_DEATH | PENALTY_DROPBOX;


    // TODO: wrap in some ImmutableHashMap thing.
    // Note: must be before static initialization of sVmPolicy.
    private static final HashMap<Class, Integer> EMPTY_CLASS_LIMIT_MAP = new HashMap<Class, Integer>();

    /**
     * The current VmPolicy in effect.
     *
     * TODO: these are redundant (mask is in VmPolicy).  Should remove sVmPolicyMask.
     */
    private static volatile int sVmPolicyMask = 0;
    private static volatile VmPolicy sVmPolicy = VmPolicy.LAX;

    /**
     * The number of threads trying to do an async dropbox write.
     * Just to limit ourselves out of paranoia.
     */
    private static final AtomicInteger sDropboxCallsInFlight = new AtomicInteger(0);

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
                return enable(ALL_THREAD_DETECT_BITS);
            }

            /**
             * Disable the detection of everything.
             */
            public Builder permitAll() {
                return disable(ALL_THREAD_DETECT_BITS);
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
             * Enable detection of slow calls.
             */
            public Builder detectCustomSlowCalls() {
                return enable(DETECT_CUSTOM);
            }

            /**
             * Disable detection of slow calls.
             */
            public Builder permitCustomSlowCalls() {
                return disable(DETECT_CUSTOM);
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
             *
             * <p>Unlike {@link #penaltyDeathOnNetwork}, this applies
             * to disk reads, disk writes, and network usage if their
             * corresponding detect flags are set.
             */
            public Builder penaltyDeath() {
                return enable(PENALTY_DEATH);
            }

            /**
             * Crash the whole process on any network usage.  Unlike
             * {@link #penaltyDeath}, this penalty runs
             * <em>before</em> anything else.  You must still have
             * called {@link #detectNetwork} to enable this.
             *
             * <p>In the Honeycomb or later SDKs, this is on by default.
             */
            public Builder penaltyDeathOnNetwork() {
                return enable(PENALTY_DEATH_ON_NETWORK);
            }

            /**
             * Flash the screen during a violation.
             */
            public Builder penaltyFlashScreen() {
                return enable(PENALTY_FLASH);
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
        public static final VmPolicy LAX = new VmPolicy(0, EMPTY_CLASS_LIMIT_MAP);

        final int mask;

        // Map from class to max number of allowed instances in memory.
        final HashMap<Class, Integer> classInstanceLimit;

        private VmPolicy(int mask, HashMap<Class, Integer> classInstanceLimit) {
            if (classInstanceLimit == null) {
                throw new NullPointerException("classInstanceLimit == null");
            }
            this.mask = mask;
            this.classInstanceLimit = classInstanceLimit;
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

            private HashMap<Class, Integer> mClassInstanceLimit;  // null until needed
            private boolean mClassInstanceLimitNeedCow = false;  // need copy-on-write

            public Builder() {
                mMask = 0;
            }

            /**
             * Build upon an existing VmPolicy.
             */
            public Builder(VmPolicy base) {
                mMask = base.mask;
                mClassInstanceLimitNeedCow = true;
                mClassInstanceLimit = base.classInstanceLimit;
            }

            /**
             * Set an upper bound on how many instances of a class can be in memory
             * at once.  Helps to prevent object leaks.
             */
            public Builder setClassInstanceLimit(Class klass, int instanceLimit) {
                if (klass == null) {
                    throw new NullPointerException("klass == null");
                }
                if (mClassInstanceLimitNeedCow) {
                    if (mClassInstanceLimit.containsKey(klass) &&
                        mClassInstanceLimit.get(klass) == instanceLimit) {
                        // no-op; don't break COW
                        return this;
                    }
                    mClassInstanceLimitNeedCow = false;
                    mClassInstanceLimit = (HashMap<Class, Integer>) mClassInstanceLimit.clone();
                } else if (mClassInstanceLimit == null) {
                    mClassInstanceLimit = new HashMap<Class, Integer>();
                }
                mMask |= DETECT_VM_INSTANCE_LEAKS;
                mClassInstanceLimit.put(klass, instanceLimit);
                return this;
            }

            /**
             * Detect leaks of {@link android.app.Activity} subclasses.
             */
            public Builder detectActivityLeaks() {
                return enable(DETECT_VM_ACTIVITY_LEAKS);
            }

            /**
             * Detect everything that's potentially suspect.
             *
             * <p>In the Honeycomb release this includes leaks of
             * SQLite cursors, Activities, and other closable objects
             * but will likely expand in future releases.
             */
            public Builder detectAll() {
                return enable(DETECT_VM_ACTIVITY_LEAKS | DETECT_VM_CURSOR_LEAKS
                        | DETECT_VM_CLOSABLE_LEAKS | DETECT_VM_REGISTRATION_LEAKS);
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
             * Detect when an {@link java.io.Closeable} or other
             * object with a explict termination method is finalized
             * without having been closed.
             *
             * <p>You always want to explicitly close such objects to
             * avoid unnecessary resources leaks.
             */
            public Builder detectLeakedClosableObjects() {
                return enable(DETECT_VM_CLOSABLE_LEAKS);
            }

            /**
             * Detect when a {@link BroadcastReceiver} or
             * {@link ServiceConnection} is leaked during {@link Context}
             * teardown.
             */
            public Builder detectLeakedRegistrationObjects() {
                return enable(DETECT_VM_REGISTRATION_LEAKS);
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
                return new VmPolicy(mMask,
                        mClassInstanceLimit != null ? mClassInstanceLimit : EMPTY_CLASS_LIMIT_MAP);
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

    // Sets up CloseGuard in Dalvik/libcore
    private static void setCloseGuardEnabled(boolean enabled) {
        if (!(CloseGuard.getReporter() instanceof AndroidCloseGuardReporter)) {
            CloseGuard.setReporter(new AndroidCloseGuardReporter());
        }
        CloseGuard.setEnabled(enabled);
    }

    /**
     * @hide
     */
    public static class StrictModeViolation extends BlockGuard.BlockGuardPolicyException {
        public StrictModeViolation(int policyState, int policyViolated, String message) {
            super(policyState, policyViolated, message);
        }
    }

    /**
     * @hide
     */
    public static class StrictModeNetworkViolation extends StrictModeViolation {
        public StrictModeNetworkViolation(int policyMask) {
            super(policyMask, DETECT_NETWORK, null);
        }
    }

    /**
     * @hide
     */
    private static class StrictModeDiskReadViolation extends StrictModeViolation {
        public StrictModeDiskReadViolation(int policyMask) {
            super(policyMask, DETECT_DISK_READ, null);
        }
    }

     /**
     * @hide
     */
   private static class StrictModeDiskWriteViolation extends StrictModeViolation {
        public StrictModeDiskWriteViolation(int policyMask) {
            super(policyMask, DETECT_DISK_WRITE, null);
        }
    }

    /**
     * @hide
     */
    private static class StrictModeCustomViolation extends StrictModeViolation {
        public StrictModeCustomViolation(int policyMask, String name) {
            super(policyMask, DETECT_CUSTOM, name);
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
        // TODO: this was a last minute Gingerbread API change (to
        // introduce VmPolicy cleanly) but this isn't particularly
        // optimal for users who might call this method often.  This
        // should be in a thread-local and not allocate on each call.
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

    // We don't want to flash the screen red in the system server
    // process, nor do we want to modify all the call sites of
    // conditionallyEnableDebugLogging() in the system server,
    // so instead we use this to determine if we are the system server.
    private static boolean amTheSystemServerProcess() {
        // Fast path.  Most apps don't have the system server's UID.
        if (Process.myUid() != Process.SYSTEM_UID) {
            return false;
        }

        // The settings app, though, has the system server's UID so
        // look up our stack to see if we came from the system server.
        Throwable stack = new Throwable();
        stack.fillInStackTrace();
        for (StackTraceElement ste : stack.getStackTrace()) {
            String clsName = ste.getClassName();
            if (clsName != null && clsName.startsWith("com.android.server.")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Enable DropBox logging for debug phone builds.
     *
     * @hide
     */
    public static boolean conditionallyEnableDebugLogging() {
        boolean doFlashes = SystemProperties.getBoolean(VISUAL_PROPERTY, false)
                && !amTheSystemServerProcess();
        final boolean suppress = SystemProperties.getBoolean(DISABLE_PROPERTY, false);

        // For debug builds, log event loop stalls to dropbox for analysis.
        // Similar logic also appears in ActivityThread.java for system apps.
        if (!doFlashes && (IS_USER_BUILD || suppress)) {
            setCloseGuardEnabled(false);
            return false;
        }

        // Eng builds have flashes on all the time.  The suppression property
        // overrides this, so we force the behavior only after the short-circuit
        // check above.
        if (IS_ENG_BUILD) {
            doFlashes = true;
        }

        // Thread policy controls BlockGuard.
        int threadPolicyMask = StrictMode.DETECT_DISK_WRITE |
                StrictMode.DETECT_DISK_READ |
                StrictMode.DETECT_NETWORK;

        if (!IS_USER_BUILD) {
            threadPolicyMask |= StrictMode.PENALTY_DROPBOX;
        }
        if (doFlashes) {
            threadPolicyMask |= StrictMode.PENALTY_FLASH;
        }

        StrictMode.setThreadPolicyMask(threadPolicyMask);

        // VM Policy controls CloseGuard, detection of Activity leaks,
        // and instance counting.
        if (IS_USER_BUILD) {
            setCloseGuardEnabled(false);
        } else {
            VmPolicy.Builder policyBuilder = new VmPolicy.Builder().detectAll().penaltyDropBox();
            if (IS_ENG_BUILD) {
                policyBuilder.penaltyLog();
            }
            setVmPolicy(policyBuilder.build());
            setCloseGuardEnabled(vmClosableObjectLeaksEnabled());
        }
        return true;
    }

    /**
     * Used by the framework to make network usage on the main
     * thread a fatal error.
     *
     * @hide
     */
    public static void enableDeathOnNetwork() {
        int oldPolicy = getThreadPolicyMask();
        int newPolicy = oldPolicy | DETECT_NETWORK | PENALTY_DEATH_ON_NETWORK;
        setThreadPolicyMask(newPolicy);
    }

    /**
     * Parses the BlockGuard policy mask out from the Exception's
     * getMessage() String value.  Kinda gross, but least
     * invasive.  :/
     *
     * Input is of the following forms:
     *     "policy=137 violation=64"
     *     "policy=137 violation=64 msg=Arbitrary text"
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
        int numberStartIndex = violationIndex + "violation=".length();
        int numberEndIndex = message.indexOf(' ', numberStartIndex);
        if (numberEndIndex == -1) {
            numberEndIndex = message.length();
        }
        String violationString = message.substring(numberStartIndex, numberEndIndex);
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

    // Note: only access this once verifying the thread has a Looper.
    private static final ThreadLocal<Handler> threadHandler = new ThreadLocal<Handler>() {
        @Override protected Handler initialValue() {
            return new Handler();
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

        // Not part of BlockGuard.Policy; just part of StrictMode:
        void onCustomSlowCall(String name) {
            if ((mPolicyMask & DETECT_CUSTOM) == 0) {
                return;
            }
            if (tooManyViolationsThisLoop()) {
                return;
            }
            BlockGuard.BlockGuardPolicyException e = new StrictModeCustomViolation(mPolicyMask, name);
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
            if ((mPolicyMask & PENALTY_DEATH_ON_NETWORK) != 0) {
                throw new NetworkOnMainThreadException();
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
            //
            // Also, as a special short-cut case when the only penalty
            // bit is death, we die immediately, rather than timing
            // the violation's duration.  This makes it convenient to
            // use in unit tests too, rather than waiting on a Looper.
            //
            // TODO: if in gather mode, ignore Looper.myLooper() and always
            //       go into this immediate mode?
            if (looper == null ||
                (info.policy & THREAD_PENALTY_MASK) == PENALTY_DEATH) {
                info.durationMillis = -1;  // unknown (redundant, already set)
                handleViolation(info);
                return;
            }

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

            final IWindowManager windowManager = (info.policy & PENALTY_FLASH) != 0 ?
                    sWindowManager.get() : null;
            if (windowManager != null) {
                try {
                    windowManager.showStrictModeViolation(true);
                } catch (RemoteException unused) {
                }
            }

            // We post a runnable to a Handler (== delay 0 ms) for
            // measuring the end time of a violation instead of using
            // an IdleHandler (as was previously used) because an
            // IdleHandler may not run for quite a long period of time
            // if an ongoing animation is happening and continually
            // posting ASAP (0 ms) animation steps.  Animations are
            // throttled back to 60fps via SurfaceFlinger/View
            // invalidates, _not_ by posting frame updates every 16
            // milliseconds.
            threadHandler.get().postAtFrontOfQueue(new Runnable() {
                    public void run() {
                        long loopFinishTime = SystemClock.uptimeMillis();

                        // Note: we do this early, before handling the
                        // violation below, as handling the violation
                        // may include PENALTY_DEATH and we don't want
                        // to keep the red border on.
                        if (windowManager != null) {
                            try {
                                windowManager.showStrictModeViolation(false);
                            } catch (RemoteException unused) {
                            }
                        }

                        for (int n = 0; n < records.size(); ++n) {
                            ViolationInfo v = records.get(n);
                            v.violationNumThisLoop = n + 1;
                            v.durationMillis =
                                    (int) (loopFinishTime - v.violationUptimeMillis);
                            handleViolation(v);
                        }
                        records.clear();
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
            Integer crashFingerprint = info.hashCode();
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

            // The violationMaskSubset, passed to ActivityManager, is a
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

                final boolean justDropBox = (info.policy & THREAD_PENALTY_MASK) == PENALTY_DROPBOX;
                if (justDropBox) {
                    // If all we're going to ask the activity manager
                    // to do is dropbox it (the common case during
                    // platform development), we can avoid doing this
                    // call synchronously which Binder data suggests
                    // isn't always super fast, despite the implementation
                    // in the ActivityManager trying to be mostly async.
                    dropboxViolationAsync(violationMaskSubset, info);
                    return;
                }

                // Normal synchronous call to the ActivityManager.
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
                executeDeathPenalty(info);
            }
        }
    }

    private static void executeDeathPenalty(ViolationInfo info) {
        int violationBit = parseViolationFromMessage(info.crashInfo.exceptionMessage);
        throw new StrictModeViolation(info.policy, violationBit, null);
    }

    /**
     * In the common case, as set by conditionallyEnableDebugLogging,
     * we're just dropboxing any violations but not showing a dialog,
     * not loggging, and not killing the process.  In these cases we
     * don't need to do a synchronous call to the ActivityManager.
     * This is used by both per-thread and vm-wide violations when
     * applicable.
     */
    private static void dropboxViolationAsync(
            final int violationMaskSubset, final ViolationInfo info) {
        int outstanding = sDropboxCallsInFlight.incrementAndGet();
        if (outstanding > 20) {
            // What's going on?  Let's not make make the situation
            // worse and just not log.
            sDropboxCallsInFlight.decrementAndGet();
            return;
        }

        if (LOG_V) Log.d(TAG, "Dropboxing async; in-flight=" + outstanding);

        new Thread("callActivityManagerForStrictModeDropbox") {
            public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                try {
                    IActivityManager am = ActivityManagerNative.getDefault();
                    if (am == null) {
                        Log.d(TAG, "No activity manager; failed to Dropbox violation.");
                    } else {
                        am.handleApplicationStrictModeViolation(
                            RuntimeInit.getApplicationObject(),
                            violationMaskSubset,
                            info);
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "RemoteException handling StrictMode violation", e);
                }
                int outstanding = sDropboxCallsInFlight.decrementAndGet();
                if (LOG_V) Log.d(TAG, "Dropbox complete; in-flight=" + outstanding);
            }
        }.start();
    }

    private static class AndroidCloseGuardReporter implements CloseGuard.Reporter {
        public void report (String message, Throwable allocationSite) {
            onVmPolicyViolation(message, allocationSite);
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
     * @hide
     */
    public static void conditionallyCheckInstanceCounts() {
        VmPolicy policy = getVmPolicy();
        if (policy.classInstanceLimit.size() == 0) {
            return;
        }
        Runtime.getRuntime().gc();
        // Note: classInstanceLimit is immutable, so this is lock-free
        for (Map.Entry<Class, Integer> entry : policy.classInstanceLimit.entrySet()) {
            Class klass = entry.getKey();
            int limit = entry.getValue();
            long instances = VMDebug.countInstancesOfClass(klass, false);
            if (instances <= limit) {
                continue;
            }
            Throwable tr = new InstanceCountViolation(klass, instances, limit);
            onVmPolicyViolation(tr.getMessage(), tr);
        }
    }

    private static long sLastInstanceCountCheckMillis = 0;
    private static boolean sIsIdlerRegistered = false;  // guarded by StrictMode.class
    private static final MessageQueue.IdleHandler sProcessIdleHandler =
            new MessageQueue.IdleHandler() {
                public boolean queueIdle() {
                    long now = SystemClock.uptimeMillis();
                    if (now - sLastInstanceCountCheckMillis > 30 * 1000) {
                        sLastInstanceCountCheckMillis = now;
                        conditionallyCheckInstanceCounts();
                    }
                    return true;
                }
            };

    /**
     * Sets the policy for what actions in the VM process (on any
     * thread) should be detected, as well as the penalty if such
     * actions occur.
     *
     * @param policy the policy to put into place
     */
    public static void setVmPolicy(final VmPolicy policy) {
        synchronized (StrictMode.class) {
            sVmPolicy = policy;
            sVmPolicyMask = policy.mask;
            setCloseGuardEnabled(vmClosableObjectLeaksEnabled());

            Looper looper = Looper.getMainLooper();
            if (looper != null) {
                MessageQueue mq = looper.mQueue;
                if (policy.classInstanceLimit.size() == 0 ||
                    (sVmPolicyMask & VM_PENALTY_MASK) == 0) {
                    mq.removeIdleHandler(sProcessIdleHandler);
                    sIsIdlerRegistered = false;
                } else if (!sIsIdlerRegistered) {
                    mq.addIdleHandler(sProcessIdleHandler);
                    sIsIdlerRegistered = true;
                }
            }
        }
    }

    /**
     * Gets the current VM policy.
     */
    public static VmPolicy getVmPolicy() {
        synchronized (StrictMode.class) {
            return sVmPolicy;
        }
    }

    /**
     * Enable the recommended StrictMode defaults, with violations just being logged.
     *
     * <p>This catches disk and network access on the main thread, as
     * well as leaked SQLite cursors and unclosed resources.  This is
     * simply a wrapper around {@link #setVmPolicy} and {@link
     * #setThreadPolicy}.
     */
    public static void enableDefaults() {
        StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                                   .detectAll()
                                   .penaltyLog()
                                   .build());
        StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                               .detectAll()
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
    public static boolean vmClosableObjectLeaksEnabled() {
        return (sVmPolicyMask & DETECT_VM_CLOSABLE_LEAKS) != 0;
    }

    /**
     * @hide
     */
    public static boolean vmRegistrationLeaksEnabled() {
        return (sVmPolicyMask & DETECT_VM_REGISTRATION_LEAKS) != 0;
    }

    /**
     * @hide
     */
    public static void onSqliteObjectLeaked(String message, Throwable originStack) {
        onVmPolicyViolation(message, originStack);
    }

    /**
     * @hide
     */
    public static void onWebViewMethodCalledOnWrongThread(Throwable originStack) {
        onVmPolicyViolation(null, originStack);
    }

    /**
     * @hide
     */
    public static void onIntentReceiverLeaked(Throwable originStack) {
        onVmPolicyViolation(null, originStack);
    }

    /**
     * @hide
     */
    public static void onServiceConnectionLeaked(Throwable originStack) {
        onVmPolicyViolation(null, originStack);
    }

    // Map from VM violation fingerprint to uptime millis.
    private static final HashMap<Integer, Long> sLastVmViolationTime = new HashMap<Integer, Long>();

    /**
     * @hide
     */
    public static void onVmPolicyViolation(String message, Throwable originStack) {
        final boolean penaltyDropbox = (sVmPolicyMask & PENALTY_DROPBOX) != 0;
        final boolean penaltyDeath = (sVmPolicyMask & PENALTY_DEATH) != 0;
        final boolean penaltyLog = (sVmPolicyMask & PENALTY_LOG) != 0;
        final ViolationInfo info = new ViolationInfo(originStack, sVmPolicyMask);

        // Erase stuff not relevant for process-wide violations
        info.numAnimationsRunning = 0;
        info.tags = null;
        info.broadcastIntentAction = null;

        final Integer fingerprint = info.hashCode();
        final long now = SystemClock.uptimeMillis();
        long lastViolationTime = 0;
        long timeSinceLastViolationMillis = Long.MAX_VALUE;
        synchronized (sLastVmViolationTime) {
            if (sLastVmViolationTime.containsKey(fingerprint)) {
                lastViolationTime = sLastVmViolationTime.get(fingerprint);
                timeSinceLastViolationMillis = now - lastViolationTime;
            }
            if (timeSinceLastViolationMillis > MIN_LOG_INTERVAL_MS) {
                sLastVmViolationTime.put(fingerprint, now);
            }
        }

        if (penaltyLog && timeSinceLastViolationMillis > MIN_LOG_INTERVAL_MS) {
            Log.e(TAG, message, originStack);
        }

        int violationMaskSubset = PENALTY_DROPBOX | (ALL_VM_DETECT_BITS & sVmPolicyMask);

        if (penaltyDropbox && !penaltyDeath) {
            // Common case for userdebug/eng builds.  If no death and
            // just dropboxing, we can do the ActivityManager call
            // asynchronously.
            dropboxViolationAsync(violationMaskSubset, info);
            return;
        }

        if (penaltyDropbox && lastViolationTime == 0) {
            // The violationMask, passed to ActivityManager, is a
            // subset of the original StrictMode policy bitmask, with
            // only the bit violated and penalty bits to be executed
            // by the ActivityManagerService remaining set.
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

        if (penaltyDeath) {
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
     * A tracked, critical time span.  (e.g. during an animation.)
     *
     * The object itself is a linked list node, to avoid any allocations
     * during rapid span entries and exits.
     *
     * @hide
     */
    public static class Span {
        private String mName;
        private long mCreateMillis;
        private Span mNext;
        private Span mPrev;  // not used when in freeList, only active
        private final ThreadSpanState mContainerState;

        Span(ThreadSpanState threadState) {
            mContainerState = threadState;
        }

        // Empty constructor for the NO_OP_SPAN
        protected Span() {
            mContainerState = null;
        }

        /**
         * To be called when the critical span is complete (i.e. the
         * animation is done animating).  This can be called on any
         * thread (even a different one from where the animation was
         * taking place), but that's only a defensive implementation
         * measure.  It really makes no sense for you to call this on
         * thread other than that where you created it.
         *
         * @hide
         */
        public void finish() {
            ThreadSpanState state = mContainerState;
            synchronized (state) {
                if (mName == null) {
                    // Duplicate finish call.  Ignore.
                    return;
                }

                // Remove ourselves from the active list.
                if (mPrev != null) {
                    mPrev.mNext = mNext;
                }
                if (mNext != null) {
                    mNext.mPrev = mPrev;
                }
                if (state.mActiveHead == this) {
                    state.mActiveHead = mNext;
                }

                state.mActiveSize--;

                if (LOG_V) Log.d(TAG, "Span finished=" + mName + "; size=" + state.mActiveSize);

                this.mCreateMillis = -1;
                this.mName = null;
                this.mPrev = null;
                this.mNext = null;

                // Add ourselves to the freeList, if it's not already
                // too big.
                if (state.mFreeListSize < 5) {
                    this.mNext = state.mFreeListHead;
                    state.mFreeListHead = this;
                    state.mFreeListSize++;
                }
            }
        }
    }

    // The no-op span that's used in user builds.
    private static final Span NO_OP_SPAN = new Span() {
            public void finish() {
                // Do nothing.
            }
        };

    /**
     * Linked lists of active spans and a freelist.
     *
     * Locking notes: there's one of these structures per thread and
     * all members of this structure (as well as the Span nodes under
     * it) are guarded by the ThreadSpanState object instance.  While
     * in theory there'd be no locking required because it's all local
     * per-thread, the finish() method above is defensive against
     * people calling it on a different thread from where they created
     * the Span, hence the locking.
     */
    private static class ThreadSpanState {
        public Span mActiveHead;    // doubly-linked list.
        public int mActiveSize;
        public Span mFreeListHead;  // singly-linked list.  only changes at head.
        public int mFreeListSize;
    }

    private static final ThreadLocal<ThreadSpanState> sThisThreadSpanState =
            new ThreadLocal<ThreadSpanState>() {
        @Override protected ThreadSpanState initialValue() {
            return new ThreadSpanState();
        }
    };

    private static Singleton<IWindowManager> sWindowManager = new Singleton<IWindowManager>() {
        protected IWindowManager create() {
            return IWindowManager.Stub.asInterface(ServiceManager.getService("window"));
        }
    };

    /**
     * Enter a named critical span (e.g. an animation)
     *
     * <p>The name is an arbitary label (or tag) that will be applied
     * to any strictmode violation that happens while this span is
     * active.  You must call finish() on the span when done.
     *
     * <p>This will never return null, but on devices without debugging
     * enabled, this may return a dummy object on which the finish()
     * method is a no-op.
     *
     * <p>TODO: add CloseGuard to this, verifying callers call finish.
     *
     * @hide
     */
    public static Span enterCriticalSpan(String name) {
        if (IS_USER_BUILD) {
            return NO_OP_SPAN;
        }
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name must be non-null and non-empty");
        }
        ThreadSpanState state = sThisThreadSpanState.get();
        Span span = null;
        synchronized (state) {
            if (state.mFreeListHead != null) {
                span = state.mFreeListHead;
                state.mFreeListHead = span.mNext;
                state.mFreeListSize--;
            } else {
                // Shouldn't have to do this often.
                span = new Span(state);
            }
            span.mName = name;
            span.mCreateMillis = SystemClock.uptimeMillis();
            span.mNext = state.mActiveHead;
            span.mPrev = null;
            state.mActiveHead = span;
            state.mActiveSize++;
            if (span.mNext != null) {
                span.mNext.mPrev = span;
            }
            if (LOG_V) Log.d(TAG, "Span enter=" + name + "; size=" + state.mActiveSize);
        }
        return span;
    }

    /**
     * For code to note that it's slow.  This is a no-op unless the
     * current thread's {@link android.os.StrictMode.ThreadPolicy} has
     * {@link android.os.StrictMode.ThreadPolicy.Builder#detectCustomSlowCalls}
     * enabled.
     *
     * @param name a short string for the exception stack trace that's
     *             built if when this fires.
     */
    public static void noteSlowCall(String name) {
        BlockGuard.Policy policy = BlockGuard.getThreadPolicy();
        if (!(policy instanceof AndroidBlockGuardPolicy)) {
            // StrictMode not enabled.
            return;
        }
        ((AndroidBlockGuardPolicy) policy).onCustomSlowCall(name);
    }

    /**
     * @hide
     */
    public static void noteDiskRead() {
        BlockGuard.Policy policy = BlockGuard.getThreadPolicy();
        if (!(policy instanceof AndroidBlockGuardPolicy)) {
            // StrictMode not enabled.
            return;
        }
        ((AndroidBlockGuardPolicy) policy).onReadFromDisk();
    }

    /**
     * @hide
     */
    public static void noteDiskWrite() {
        BlockGuard.Policy policy = BlockGuard.getThreadPolicy();
        if (!(policy instanceof AndroidBlockGuardPolicy)) {
            // StrictMode not enabled.
            return;
        }
        ((AndroidBlockGuardPolicy) policy).onWriteToDisk();
    }

    // Guarded by StrictMode.class
    private static final HashMap<Class, Integer> sExpectedActivityInstanceCount =
            new HashMap<Class, Integer>();

    /**
     * Returns an object that is used to track instances of activites.
     * The activity should store a reference to the tracker object in one of its fields.
     * @hide
     */
    public static Object trackActivity(Object instance) {
        return new InstanceTracker(instance);
    }

    /**
     * @hide
     */
    public static void incrementExpectedActivityCount(Class klass) {
        if (klass == null) {
            return;
        }

        synchronized (StrictMode.class) {
            if ((sVmPolicy.mask & DETECT_VM_ACTIVITY_LEAKS) == 0) {
                return;
            }

            Integer expected = sExpectedActivityInstanceCount.get(klass);
            Integer newExpected = expected == null ? 1 : expected + 1;
            sExpectedActivityInstanceCount.put(klass, newExpected);
        }
    }

    /**
     * @hide
     */
    public static void decrementExpectedActivityCount(Class klass) {
        if (klass == null) {
            return;
        }

        final int limit;
        synchronized (StrictMode.class) {
            if ((sVmPolicy.mask & DETECT_VM_ACTIVITY_LEAKS) == 0) {
                return;
            }

            Integer expected = sExpectedActivityInstanceCount.get(klass);
            int newExpected = (expected == null || expected == 0) ? 0 : expected - 1;
            if (newExpected == 0) {
                sExpectedActivityInstanceCount.remove(klass);
            } else {
                sExpectedActivityInstanceCount.put(klass, newExpected);
            }

            // Note: adding 1 here to give some breathing room during
            // orientation changes.  (shouldn't be necessary, though?)
            limit = newExpected + 1;
        }

        // Quick check.
        int actual = InstanceTracker.getInstanceCount(klass);
        if (actual <= limit) {
            return;
        }

        // Do a GC and explicit count to double-check.
        // This is the work that we are trying to avoid by tracking the object instances
        // explicity.  Running an explicit GC can be expensive (80ms) and so can walking
        // the heap to count instance (30ms).  This extra work can make the system feel
        // noticeably less responsive during orientation changes when activities are
        // being restarted.  Granted, it is only a problem when StrictMode is enabled
        // but it is annoying.
        Runtime.getRuntime().gc();

        long instances = VMDebug.countInstancesOfClass(klass, false);
        if (instances > limit) {
            Throwable tr = new InstanceCountViolation(klass, instances, limit);
            onVmPolicyViolation(tr.getMessage(), tr);
        }
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
         * The number of animations currently running.
         */
        public int numAnimationsRunning = 0;

        /**
         * List of tags from active Span instances during this
         * violation, or null for none.
         */
        public String[] tags;

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
         * The action of the Intent being broadcast to somebody's onReceive
         * on this thread right now, or null.
         */
        public String broadcastIntentAction;

        /**
         * If this is a instance count violation, the number of instances in memory,
         * else -1.
         */
        public long numInstances = -1;

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
            this.numAnimationsRunning = ValueAnimator.getCurrentAnimationsCount();
            Intent broadcastIntent = ActivityThread.getIntentBeingBroadcast();
            if (broadcastIntent != null) {
                broadcastIntentAction = broadcastIntent.getAction();
            }
            ThreadSpanState state = sThisThreadSpanState.get();
            if (tr instanceof InstanceCountViolation) {
                this.numInstances = ((InstanceCountViolation) tr).mInstances;
            }
            synchronized (state) {
                int spanActiveCount = state.mActiveSize;
                if (spanActiveCount > MAX_SPAN_TAGS) {
                    spanActiveCount = MAX_SPAN_TAGS;
                }
                if (spanActiveCount != 0) {
                    this.tags = new String[spanActiveCount];
                    Span iter = state.mActiveHead;
                    int index = 0;
                    while (iter != null && index < spanActiveCount) {
                        this.tags[index] = iter.mName;
                        index++;
                        iter = iter.mNext;
                    }
                }
            }
        }

        @Override
        public int hashCode() {
            int result = 17;
            result = 37 * result + crashInfo.stackTrace.hashCode();
            if (numAnimationsRunning != 0) {
                result *= 37;
            }
            if (broadcastIntentAction != null) {
                result = 37 * result + broadcastIntentAction.hashCode();
            }
            if (tags != null) {
                for (String tag : tags) {
                    result = 37 * result + tag.hashCode();
                }
            }
            return result;
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
            numAnimationsRunning = in.readInt();
            violationUptimeMillis = in.readLong();
            numInstances = in.readLong();
            broadcastIntentAction = in.readString();
            tags = in.readStringArray();
        }

        /**
         * Save a ViolationInfo instance to a parcel.
         */
        public void writeToParcel(Parcel dest, int flags) {
            crashInfo.writeToParcel(dest, flags);
            dest.writeInt(policy);
            dest.writeInt(durationMillis);
            dest.writeInt(violationNumThisLoop);
            dest.writeInt(numAnimationsRunning);
            dest.writeLong(violationUptimeMillis);
            dest.writeLong(numInstances);
            dest.writeString(broadcastIntentAction);
            dest.writeStringArray(tags);
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
            if (numInstances != -1) {
                pw.println(prefix + "numInstances: " + numInstances);
            }
            if (violationNumThisLoop != 0) {
                pw.println(prefix + "violationNumThisLoop: " + violationNumThisLoop);
            }
            if (numAnimationsRunning != 0) {
                pw.println(prefix + "numAnimationsRunning: " + numAnimationsRunning);
            }
            pw.println(prefix + "violationUptimeMillis: " + violationUptimeMillis);
            if (broadcastIntentAction != null) {
                pw.println(prefix + "broadcastIntentAction: " + broadcastIntentAction);
            }
            if (tags != null) {
                int index = 0;
                for (String tag : tags) {
                    pw.println(prefix + "tag[" + (index++) + "]: " + tag);
                }
            }
        }

    }

    // Dummy throwable, for now, since we don't know when or where the
    // leaked instances came from.  We might in the future, but for
    // now we suppress the stack trace because it's useless and/or
    // misleading.
    private static class InstanceCountViolation extends Throwable {
        final Class mClass;
        final long mInstances;
        final int mLimit;

        private static final StackTraceElement[] FAKE_STACK = {
            new StackTraceElement("android.os.StrictMode", "setClassInstanceLimit",
                                  "StrictMode.java", 1)
        };

        public InstanceCountViolation(Class klass, long instances, int limit) {
            super(klass.toString() + "; instances=" + instances + "; limit=" + limit);
            setStackTrace(FAKE_STACK);
            mClass = klass;
            mInstances = instances;
            mLimit = limit;
        }
    }

    private static final class InstanceTracker {
        private static final HashMap<Class<?>, Integer> sInstanceCounts =
                new HashMap<Class<?>, Integer>();

        private final Class<?> mKlass;

        public InstanceTracker(Object instance) {
            mKlass = instance.getClass();

            synchronized (sInstanceCounts) {
                final Integer value = sInstanceCounts.get(mKlass);
                final int newValue = value != null ? value + 1 : 1;
                sInstanceCounts.put(mKlass, newValue);
            }
        }

        @Override
        protected void finalize() throws Throwable {
            try {
                synchronized (sInstanceCounts) {
                    final Integer value = sInstanceCounts.get(mKlass);
                    if (value != null) {
                        final int newValue = value - 1;
                        if (newValue > 0) {
                            sInstanceCounts.put(mKlass, newValue);
                        } else {
                            sInstanceCounts.remove(mKlass);
                        }
                    }
                }
            } finally {
                super.finalize();
            }
        }

        public static int getInstanceCount(Class<?> klass) {
            synchronized (sInstanceCounts) {
                final Integer value = sInstanceCounts.get(klass);
                return value != null ? value : 0;
            }
        }
    }
}
