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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.IActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.strictmode.CleartextNetworkViolation;
import android.os.strictmode.ContentUriWithoutPermissionViolation;
import android.os.strictmode.CustomViolation;
import android.os.strictmode.DiskReadViolation;
import android.os.strictmode.DiskWriteViolation;
import android.os.strictmode.FileUriExposedViolation;
import android.os.strictmode.InstanceCountViolation;
import android.os.strictmode.IntentReceiverLeakedViolation;
import android.os.strictmode.LeakedClosableViolation;
import android.os.strictmode.NetworkViolation;
import android.os.strictmode.NonSdkApiUsedViolation;
import android.os.strictmode.ResourceMismatchViolation;
import android.os.strictmode.ServiceConnectionLeakedViolation;
import android.os.strictmode.SqliteObjectLeakedViolation;
import android.os.strictmode.UnbufferedIoViolation;
import android.os.strictmode.UntaggedSocketViolation;
import android.os.strictmode.Violation;
import android.os.strictmode.WebViewMethodCalledOnWrongThreadViolation;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Printer;
import android.util.Singleton;
import android.util.Slog;
import android.view.IWindowManager;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BackgroundThread;
import com.android.internal.os.RuntimeInit;
import com.android.internal.util.FastPrintWriter;
import com.android.internal.util.HexDump;

import dalvik.system.BlockGuard;
import dalvik.system.CloseGuard;
import dalvik.system.VMDebug;
import dalvik.system.VMRuntime;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * StrictMode is a developer tool which detects things you might be doing by accident and brings
 * them to your attention so you can fix them.
 *
 * <p>StrictMode is most commonly used to catch accidental disk or network access on the
 * application's main thread, where UI operations are received and animations take place. Keeping
 * disk and network operations off the main thread makes for much smoother, more responsive
 * applications. By keeping your application's main thread responsive, you also prevent <a
 * href="{@docRoot}guide/practices/design/responsiveness.html">ANR dialogs</a> from being shown to
 * users.
 *
 * <p class="note">Note that even though an Android device's disk is often on flash memory, many
 * devices run a filesystem on top of that memory with very limited concurrency. It's often the case
 * that almost all disk accesses are fast, but may in individual cases be dramatically slower when
 * certain I/O is happening in the background from other processes. If possible, it's best to assume
 * that such things are not fast.
 *
 * <p>Example code to enable from early in your {@link android.app.Application}, {@link
 * android.app.Activity}, or other application component's {@link android.app.Application#onCreate}
 * method:
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
 * <p>You can decide what should happen when a violation is detected. For example, using {@link
 * ThreadPolicy.Builder#penaltyLog} you can watch the output of <code>adb logcat</code> while you
 * use your application to see the violations as they happen.
 *
 * <p>If you find violations that you feel are problematic, there are a variety of tools to help
 * solve them: threads, {@link android.os.Handler}, {@link android.os.AsyncTask}, {@link
 * android.app.IntentService}, etc. But don't feel compelled to fix everything that StrictMode
 * finds. In particular, many cases of disk access are often necessary during the normal activity
 * lifecycle. Use StrictMode to find things you did by accident. Network requests on the UI thread
 * are almost always a problem, though.
 *
 * <p class="note">StrictMode is not a security mechanism and is not guaranteed to find all disk or
 * network accesses. While it does propagate its state across process boundaries when doing {@link
 * android.os.Binder} calls, it's still ultimately a best effort mechanism. Notably, disk or network
 * access from JNI calls won't necessarily trigger it. Future versions of Android may catch more (or
 * fewer) operations, so you should never leave StrictMode enabled in applications distributed on
 * Google Play.
 */
public final class StrictMode {
    private static final String TAG = "StrictMode";
    private static final boolean LOG_V = Log.isLoggable(TAG, Log.VERBOSE);

    /**
     * Boolean system property to disable strict mode checks outright. Set this to 'true' to force
     * disable; 'false' has no effect on other enable/disable policy.
     *
     * @hide
     */
    public static final String DISABLE_PROPERTY = "persist.sys.strictmode.disable";

    /**
     * The boolean system property to control screen flashes on violations.
     *
     * @hide
     */
    public static final String VISUAL_PROPERTY = "persist.sys.strictmode.visual";

    /**
     * Temporary property used to include {@link #DETECT_VM_CLEARTEXT_NETWORK} in {@link
     * VmPolicy.Builder#detectAll()}. Apps can still always opt-into detection using {@link
     * VmPolicy.Builder#detectCleartextNetwork()}.
     */
    private static final String CLEARTEXT_PROPERTY = "persist.sys.strictmode.clear";

    /**
     * Quick feature-flag that can be used to disable the defaults provided by {@link
     * #initThreadDefaults(ApplicationInfo)} and {@link #initVmDefaults(ApplicationInfo)}.
     */
    private static final boolean DISABLE = false;

    // Only apply VM penalties for the same violation at this interval.
    private static final long MIN_VM_INTERVAL_MS = 1000;

    // Only log a duplicate stack trace to the logs every second.
    private static final long MIN_LOG_INTERVAL_MS = 1000;

    // Only show an annoying dialog at most every 30 seconds
    private static final long MIN_DIALOG_INTERVAL_MS = 30000;

    // How many Span tags (e.g. animations) to report.
    private static final int MAX_SPAN_TAGS = 20;

    // How many offending stacks to keep track of (and time) per loop
    // of the Looper.
    private static final int MAX_OFFENSES_PER_LOOP = 10;

    // Byte 1: Thread-policy

    /** @hide */
    @TestApi public static final int DETECT_DISK_WRITE = 0x01; // for ThreadPolicy

    /** @hide */
    @TestApi public static final int DETECT_DISK_READ = 0x02; // for ThreadPolicy

    /** @hide */
    @TestApi public static final int DETECT_NETWORK = 0x04; // for ThreadPolicy

    /**
     * For StrictMode.noteSlowCall()
     *
     * @hide
     */
    @TestApi public static final int DETECT_CUSTOM = 0x08; // for ThreadPolicy

    /**
     * For StrictMode.noteResourceMismatch()
     *
     * @hide
     */
    @TestApi public static final int DETECT_RESOURCE_MISMATCH = 0x10; // for ThreadPolicy

    /** @hide */
    @TestApi public static final int DETECT_UNBUFFERED_IO = 0x20; // for ThreadPolicy

    private static final int ALL_THREAD_DETECT_BITS =
            DETECT_DISK_WRITE
                    | DETECT_DISK_READ
                    | DETECT_NETWORK
                    | DETECT_CUSTOM
                    | DETECT_RESOURCE_MISMATCH
                    | DETECT_UNBUFFERED_IO;

    // Byte 2: Process-policy

    /**
     * Note, a "VM_" bit, not thread.
     *
     * @hide
     */
    @TestApi public static final int DETECT_VM_CURSOR_LEAKS = 0x01 << 8; // for VmPolicy

    /**
     * Note, a "VM_" bit, not thread.
     *
     * @hide
     */
    @TestApi public static final int DETECT_VM_CLOSABLE_LEAKS = 0x02 << 8; // for VmPolicy

    /**
     * Note, a "VM_" bit, not thread.
     *
     * @hide
     */
    @TestApi public static final int DETECT_VM_ACTIVITY_LEAKS = 0x04 << 8; // for VmPolicy

    /** @hide */
    @TestApi public static final int DETECT_VM_INSTANCE_LEAKS = 0x08 << 8; // for VmPolicy

    /** @hide */
    @TestApi public static final int DETECT_VM_REGISTRATION_LEAKS = 0x10 << 8; // for VmPolicy

    /** @hide */
    @TestApi public static final int DETECT_VM_FILE_URI_EXPOSURE = 0x20 << 8; // for VmPolicy

    /** @hide */
    @TestApi public static final int DETECT_VM_CLEARTEXT_NETWORK = 0x40 << 8; // for VmPolicy

    /** @hide */
    @TestApi
    public static final int DETECT_VM_CONTENT_URI_WITHOUT_PERMISSION = 0x80 << 8; // for VmPolicy

    /** @hide */
    @TestApi public static final int DETECT_VM_UNTAGGED_SOCKET = 0x80 << 24; // for VmPolicy

    /** @hide */
    @TestApi public static final int DETECT_VM_NON_SDK_API_USAGE = 0x40 << 24; // for VmPolicy

    private static final int ALL_VM_DETECT_BITS =
            DETECT_VM_CURSOR_LEAKS
                    | DETECT_VM_CLOSABLE_LEAKS
                    | DETECT_VM_ACTIVITY_LEAKS
                    | DETECT_VM_INSTANCE_LEAKS
                    | DETECT_VM_REGISTRATION_LEAKS
                    | DETECT_VM_FILE_URI_EXPOSURE
                    | DETECT_VM_CLEARTEXT_NETWORK
                    | DETECT_VM_CONTENT_URI_WITHOUT_PERMISSION
                    | DETECT_VM_UNTAGGED_SOCKET
                    | DETECT_VM_NON_SDK_API_USAGE;


    // Byte 3: Penalty

    /** {@hide} */
    public static final int PENALTY_LOG = 0x01 << 16; // normal android.util.Log
    /** {@hide} */
    public static final int PENALTY_DIALOG = 0x02 << 16;
    /** {@hide} */
    public static final int PENALTY_DEATH = 0x04 << 16;
    /** {@hide} */
    public static final int PENALTY_FLASH = 0x10 << 16;
    /** {@hide} */
    public static final int PENALTY_DROPBOX = 0x20 << 16;

    /**
     * Non-public penalty mode which overrides all the other penalty bits and signals that we're in
     * a Binder call and we should ignore the other penalty bits and instead serialize back all our
     * offending stack traces to the caller to ultimately handle in the originating process.
     *
     * <p>This must be kept in sync with the constant in libs/binder/Parcel.cpp
     *
     * @hide
     */
    public static final int PENALTY_GATHER = 0x40 << 16;

    // Byte 4: Special cases

    /**
     * Death when network traffic is detected on main thread.
     *
     * @hide
     */
    public static final int PENALTY_DEATH_ON_NETWORK = 0x01 << 24;

    /**
     * Death when cleartext network traffic is detected.
     *
     * @hide
     */
    public static final int PENALTY_DEATH_ON_CLEARTEXT_NETWORK = 0x02 << 24;

    /**
     * Death when file exposure is detected.
     *
     * @hide
     */
    public static final int PENALTY_DEATH_ON_FILE_URI_EXPOSURE = 0x04 << 24;

    // CAUTION: we started stealing the top bits of Byte 4 for VM above

    /** Mask of all the penalty bits valid for thread policies. */
    private static final int THREAD_PENALTY_MASK =
            PENALTY_LOG
                    | PENALTY_DIALOG
                    | PENALTY_DEATH
                    | PENALTY_DROPBOX
                    | PENALTY_GATHER
                    | PENALTY_DEATH_ON_NETWORK
                    | PENALTY_FLASH;

    /** Mask of all the penalty bits valid for VM policies. */
    private static final int VM_PENALTY_MASK =
            PENALTY_LOG
                    | PENALTY_DEATH
                    | PENALTY_DROPBOX
                    | PENALTY_DEATH_ON_CLEARTEXT_NETWORK
                    | PENALTY_DEATH_ON_FILE_URI_EXPOSURE;

    /** {@hide} */
    public static final int NETWORK_POLICY_ACCEPT = 0;
    /** {@hide} */
    public static final int NETWORK_POLICY_LOG = 1;
    /** {@hide} */
    public static final int NETWORK_POLICY_REJECT = 2;

    // TODO: wrap in some ImmutableHashMap thing.
    // Note: must be before static initialization of sVmPolicy.
    private static final HashMap<Class, Integer> EMPTY_CLASS_LIMIT_MAP =
            new HashMap<Class, Integer>();

    /** The current VmPolicy in effect. */
    private static volatile VmPolicy sVmPolicy = VmPolicy.LAX;

    /** {@hide} */
    @TestApi
    public interface ViolationLogger {

        /** Called when penaltyLog is enabled and a violation needs logging. */
        void log(ViolationInfo info);
    }

    private static final ViolationLogger LOGCAT_LOGGER =
            info -> {
                String msg;
                if (info.durationMillis != -1) {
                    msg = "StrictMode policy violation; ~duration=" + info.durationMillis + " ms:";
                } else {
                    msg = "StrictMode policy violation:";
                }
                Log.d(TAG, msg + " " + info.getStackTrace());
            };

    private static volatile ViolationLogger sLogger = LOGCAT_LOGGER;

    private static final ThreadLocal<OnThreadViolationListener> sThreadViolationListener =
            new ThreadLocal<>();
    private static final ThreadLocal<Executor> sThreadViolationExecutor = new ThreadLocal<>();

    /**
     * When #{@link ThreadPolicy.Builder#penaltyListener} is enabled, the listener is called on the
     * provided executor when a Thread violation occurs.
     */
    public interface OnThreadViolationListener {
        /** Called on a thread policy violation. */
        void onThreadViolation(Violation v);
    }

    /**
     * When #{@link VmPolicy.Builder#penaltyListener} is enabled, the listener is called on the
     * provided executor when a VM violation occurs.
     */
    public interface OnVmViolationListener {
        /** Called on a VM policy violation. */
        void onVmViolation(Violation v);
    }

    /** {@hide} */
    @TestApi
    public static void setViolationLogger(ViolationLogger listener) {
        if (listener == null) {
            listener = LOGCAT_LOGGER;
        }
        sLogger = listener;
    }

    /**
     * The number of threads trying to do an async dropbox write. Just to limit ourselves out of
     * paranoia.
     */
    private static final AtomicInteger sDropboxCallsInFlight = new AtomicInteger(0);

    /**
     * Callback supplied to dalvik / libcore to get informed of usages of java API that are not
     * a part of the public SDK.
     */
    private static final Consumer<String> sNonSdkApiUsageConsumer =
            message -> onVmPolicyViolation(new NonSdkApiUsedViolation(message));

    private StrictMode() {}

    /**
     * {@link StrictMode} policy applied to a certain thread.
     *
     * <p>The policy is enabled by {@link #setThreadPolicy}. The current policy can be retrieved
     * with {@link #getThreadPolicy}.
     *
     * <p>Note that multiple penalties may be provided and they're run in order from least to most
     * severe (logging before process death, for example). There's currently no mechanism to choose
     * different penalties for different detected actions.
     */
    public static final class ThreadPolicy {
        /** The default, lax policy which doesn't catch anything. */
        public static final ThreadPolicy LAX = new ThreadPolicy(0, null, null);

        final int mask;
        final OnThreadViolationListener mListener;
        final Executor mCallbackExecutor;

        private ThreadPolicy(int mask, OnThreadViolationListener listener, Executor executor) {
            this.mask = mask;
            mListener = listener;
            mCallbackExecutor = executor;
        }

        @Override
        public String toString() {
            return "[StrictMode.ThreadPolicy; mask=" + mask + "]";
        }

        /**
         * Creates {@link ThreadPolicy} instances. Methods whose names start with {@code detect}
         * specify what problems we should look for. Methods whose names start with {@code penalty}
         * specify what we should do when we detect a problem.
         *
         * <p>You can call as many {@code detect} and {@code penalty} methods as you like. Currently
         * order is insignificant: all penalties apply to all detected problems.
         *
         * <p>For example, detect everything and log anything that's found:
         *
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
            private OnThreadViolationListener mListener;
            private Executor mExecutor;

            /**
             * Create a Builder that detects nothing and has no violations. (but note that {@link
             * #build} will default to enabling {@link #penaltyLog} if no other penalties are
             * specified)
             */
            public Builder() {
                mMask = 0;
            }

            /** Initialize a Builder from an existing ThreadPolicy. */
            public Builder(ThreadPolicy policy) {
                mMask = policy.mask;
                mListener = policy.mListener;
                mExecutor = policy.mCallbackExecutor;
            }

            /**
             * Detect everything that's potentially suspect.
             *
             * <p>As of the Gingerbread release this includes network and disk operations but will
             * likely expand in future releases.
             */
            public Builder detectAll() {
                detectDiskReads();
                detectDiskWrites();
                detectNetwork();

                final int targetSdk = VMRuntime.getRuntime().getTargetSdkVersion();
                if (targetSdk >= Build.VERSION_CODES.HONEYCOMB) {
                    detectCustomSlowCalls();
                }
                if (targetSdk >= Build.VERSION_CODES.M) {
                    detectResourceMismatches();
                }
                if (targetSdk >= Build.VERSION_CODES.O) {
                    detectUnbufferedIo();
                }
                return this;
            }

            /** Disable the detection of everything. */
            public Builder permitAll() {
                return disable(ALL_THREAD_DETECT_BITS);
            }

            /** Enable detection of network operations. */
            public Builder detectNetwork() {
                return enable(DETECT_NETWORK);
            }

            /** Disable detection of network operations. */
            public Builder permitNetwork() {
                return disable(DETECT_NETWORK);
            }

            /** Enable detection of disk reads. */
            public Builder detectDiskReads() {
                return enable(DETECT_DISK_READ);
            }

            /** Disable detection of disk reads. */
            public Builder permitDiskReads() {
                return disable(DETECT_DISK_READ);
            }

            /** Enable detection of slow calls. */
            public Builder detectCustomSlowCalls() {
                return enable(DETECT_CUSTOM);
            }

            /** Disable detection of slow calls. */
            public Builder permitCustomSlowCalls() {
                return disable(DETECT_CUSTOM);
            }

            /** Disable detection of mismatches between defined resource types and getter calls. */
            public Builder permitResourceMismatches() {
                return disable(DETECT_RESOURCE_MISMATCH);
            }

            /** Detect unbuffered input/output operations. */
            public Builder detectUnbufferedIo() {
                return enable(DETECT_UNBUFFERED_IO);
            }

            /** Disable detection of unbuffered input/output operations. */
            public Builder permitUnbufferedIo() {
                return disable(DETECT_UNBUFFERED_IO);
            }

            /**
             * Enables detection of mismatches between defined resource types and getter calls.
             *
             * <p>This helps detect accidental type mismatches and potentially expensive type
             * conversions when obtaining typed resources.
             *
             * <p>For example, a strict mode violation would be thrown when calling {@link
             * android.content.res.TypedArray#getInt(int, int)} on an index that contains a
             * String-type resource. If the string value can be parsed as an integer, this method
             * call will return a value without crashing; however, the developer should format the
             * resource as an integer to avoid unnecessary type conversion.
             */
            public Builder detectResourceMismatches() {
                return enable(DETECT_RESOURCE_MISMATCH);
            }

            /** Enable detection of disk writes. */
            public Builder detectDiskWrites() {
                return enable(DETECT_DISK_WRITE);
            }

            /** Disable detection of disk writes. */
            public Builder permitDiskWrites() {
                return disable(DETECT_DISK_WRITE);
            }

            /**
             * Show an annoying dialog to the developer on detected violations, rate-limited to be
             * only a little annoying.
             */
            public Builder penaltyDialog() {
                return enable(PENALTY_DIALOG);
            }

            /**
             * Crash the whole process on violation. This penalty runs at the end of all enabled
             * penalties so you'll still get see logging or other violations before the process
             * dies.
             *
             * <p>Unlike {@link #penaltyDeathOnNetwork}, this applies to disk reads, disk writes,
             * and network usage if their corresponding detect flags are set.
             */
            public Builder penaltyDeath() {
                return enable(PENALTY_DEATH);
            }

            /**
             * Crash the whole process on any network usage. Unlike {@link #penaltyDeath}, this
             * penalty runs <em>before</em> anything else. You must still have called {@link
             * #detectNetwork} to enable this.
             *
             * <p>In the Honeycomb or later SDKs, this is on by default.
             */
            public Builder penaltyDeathOnNetwork() {
                return enable(PENALTY_DEATH_ON_NETWORK);
            }

            /** Flash the screen during a violation. */
            public Builder penaltyFlashScreen() {
                return enable(PENALTY_FLASH);
            }

            /** Log detected violations to the system log. */
            public Builder penaltyLog() {
                return enable(PENALTY_LOG);
            }

            /**
             * Enable detected violations log a stacktrace and timing data to the {@link
             * android.os.DropBoxManager DropBox} on policy violation. Intended mostly for platform
             * integrators doing beta user field data collection.
             */
            public Builder penaltyDropBox() {
                return enable(PENALTY_DROPBOX);
            }

            /**
             * Call #{@link OnThreadViolationListener#onThreadViolation(Violation)} on specified
             * executor every violation.
             */
            public Builder penaltyListener(
                    @NonNull Executor executor, @NonNull OnThreadViolationListener listener) {
                if (executor == null) {
                    throw new NullPointerException("executor must not be null");
                }
                mListener = listener;
                mExecutor = executor;
                return this;
            }

            /** @removed */
            public Builder penaltyListener(
                    @NonNull OnThreadViolationListener listener, @NonNull Executor executor) {
                return penaltyListener(executor, listener);
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
             * <p>Note: if no penalties are enabled before calling <code>build</code>, {@link
             * #penaltyLog} is implicitly set.
             */
            public ThreadPolicy build() {
                // If there are detection bits set but no violation bits
                // set, enable simple logging.
                if (mListener == null
                        && mMask != 0
                        && (mMask
                                        & (PENALTY_DEATH
                                                | PENALTY_LOG
                                                | PENALTY_DROPBOX
                                                | PENALTY_DIALOG))
                                == 0) {
                    penaltyLog();
                }
                return new ThreadPolicy(mMask, mListener, mExecutor);
            }
        }
    }

    /**
     * {@link StrictMode} policy applied to all threads in the virtual machine's process.
     *
     * <p>The policy is enabled by {@link #setVmPolicy}.
     */
    public static final class VmPolicy {
        /** The default, lax policy which doesn't catch anything. */
        public static final VmPolicy LAX = new VmPolicy(0, EMPTY_CLASS_LIMIT_MAP, null, null);

        final int mask;
        final OnVmViolationListener mListener;
        final Executor mCallbackExecutor;

        // Map from class to max number of allowed instances in memory.
        final HashMap<Class, Integer> classInstanceLimit;

        private VmPolicy(
                int mask,
                HashMap<Class, Integer> classInstanceLimit,
                OnVmViolationListener listener,
                Executor executor) {
            if (classInstanceLimit == null) {
                throw new NullPointerException("classInstanceLimit == null");
            }
            this.mask = mask;
            this.classInstanceLimit = classInstanceLimit;
            mListener = listener;
            mCallbackExecutor = executor;
        }

        @Override
        public String toString() {
            return "[StrictMode.VmPolicy; mask=" + mask + "]";
        }

        /**
         * Creates {@link VmPolicy} instances. Methods whose names start with {@code detect} specify
         * what problems we should look for. Methods whose names start with {@code penalty} specify
         * what we should do when we detect a problem.
         *
         * <p>You can call as many {@code detect} and {@code penalty} methods as you like. Currently
         * order is insignificant: all penalties apply to all detected problems.
         *
         * <p>For example, detect everything and log anything that's found:
         *
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
            private OnVmViolationListener mListener;
            private Executor mExecutor;

            private HashMap<Class, Integer> mClassInstanceLimit; // null until needed
            private boolean mClassInstanceLimitNeedCow = false; // need copy-on-write

            public Builder() {
                mMask = 0;
            }

            /** Build upon an existing VmPolicy. */
            public Builder(VmPolicy base) {
                mMask = base.mask;
                mClassInstanceLimitNeedCow = true;
                mClassInstanceLimit = base.classInstanceLimit;
                mListener = base.mListener;
                mExecutor = base.mCallbackExecutor;
            }

            /**
             * Set an upper bound on how many instances of a class can be in memory at once. Helps
             * to prevent object leaks.
             */
            public Builder setClassInstanceLimit(Class klass, int instanceLimit) {
                if (klass == null) {
                    throw new NullPointerException("klass == null");
                }
                if (mClassInstanceLimitNeedCow) {
                    if (mClassInstanceLimit.containsKey(klass)
                            && mClassInstanceLimit.get(klass) == instanceLimit) {
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

            /** Detect leaks of {@link android.app.Activity} subclasses. */
            public Builder detectActivityLeaks() {
                return enable(DETECT_VM_ACTIVITY_LEAKS);
            }

            /** @hide */
            public Builder permitActivityLeaks() {
                return disable(DETECT_VM_ACTIVITY_LEAKS);
            }

            /**
             * Detect reflective usage of APIs that are not part of the public Android SDK.
             *
             * <p>Note that any non-SDK APIs that this processes accesses before this detection is
             * enabled may not be detected. To ensure that all such API accesses are detected,
             * you should apply this policy as early as possible after process creation.
             */
            public Builder detectNonSdkApiUsage() {
                return enable(DETECT_VM_NON_SDK_API_USAGE);
            }

            /**
             * Permit reflective usage of APIs that are not part of the public Android SDK. Note
             * that this <b>only</b> affects {@code StrictMode}, the underlying runtime may
             * continue to restrict or warn on access to methods that are not part of the
             * public SDK.
             */
            public Builder permitNonSdkApiUsage() {
                return disable(DETECT_VM_NON_SDK_API_USAGE);
            }

            /**
             * Detect everything that's potentially suspect.
             *
             * <p>In the Honeycomb release this includes leaks of SQLite cursors, Activities, and
             * other closable objects but will likely expand in future releases.
             */
            public Builder detectAll() {
                detectLeakedSqlLiteObjects();

                final int targetSdk = VMRuntime.getRuntime().getTargetSdkVersion();
                if (targetSdk >= Build.VERSION_CODES.HONEYCOMB) {
                    detectActivityLeaks();
                    detectLeakedClosableObjects();
                }
                if (targetSdk >= Build.VERSION_CODES.JELLY_BEAN) {
                    detectLeakedRegistrationObjects();
                }
                if (targetSdk >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                    detectFileUriExposure();
                }
                if (targetSdk >= Build.VERSION_CODES.M) {
                    // TODO: always add DETECT_VM_CLEARTEXT_NETWORK once we have
                    // facility for apps to mark sockets that should be ignored
                    if (SystemProperties.getBoolean(CLEARTEXT_PROPERTY, false)) {
                        detectCleartextNetwork();
                    }
                }
                if (targetSdk >= Build.VERSION_CODES.O) {
                    detectContentUriWithoutPermission();
                    detectUntaggedSockets();
                }

                // TODO: Decide whether to detect non SDK API usage beyond a certain API level.
                return this;
            }

            /**
             * Detect when an {@link android.database.sqlite.SQLiteCursor} or other SQLite object is
             * finalized without having been closed.
             *
             * <p>You always want to explicitly close your SQLite cursors to avoid unnecessary
             * database contention and temporary memory leaks.
             */
            public Builder detectLeakedSqlLiteObjects() {
                return enable(DETECT_VM_CURSOR_LEAKS);
            }

            /**
             * Detect when an {@link java.io.Closeable} or other object with an explicit termination
             * method is finalized without having been closed.
             *
             * <p>You always want to explicitly close such objects to avoid unnecessary resources
             * leaks.
             */
            public Builder detectLeakedClosableObjects() {
                return enable(DETECT_VM_CLOSABLE_LEAKS);
            }

            /**
             * Detect when a {@link BroadcastReceiver} or {@link ServiceConnection} is leaked during
             * {@link Context} teardown.
             */
            public Builder detectLeakedRegistrationObjects() {
                return enable(DETECT_VM_REGISTRATION_LEAKS);
            }

            /**
             * Detect when the calling application exposes a {@code file://} {@link android.net.Uri}
             * to another app.
             *
             * <p>This exposure is discouraged since the receiving app may not have access to the
             * shared path. For example, the receiving app may not have requested the {@link
             * android.Manifest.permission#READ_EXTERNAL_STORAGE} runtime permission, or the
             * platform may be sharing the {@link android.net.Uri} across user profile boundaries.
             *
             * <p>Instead, apps should use {@code content://} Uris so the platform can extend
             * temporary permission for the receiving app to access the resource.
             *
             * @see android.support.v4.content.FileProvider
             * @see Intent#FLAG_GRANT_READ_URI_PERMISSION
             */
            public Builder detectFileUriExposure() {
                return enable(DETECT_VM_FILE_URI_EXPOSURE);
            }

            /**
             * Detect any network traffic from the calling app which is not wrapped in SSL/TLS. This
             * can help you detect places that your app is inadvertently sending cleartext data
             * across the network.
             *
             * <p>Using {@link #penaltyDeath()} or {@link #penaltyDeathOnCleartextNetwork()} will
             * block further traffic on that socket to prevent accidental data leakage, in addition
             * to crashing your process.
             *
             * <p>Using {@link #penaltyDropBox()} will log the raw contents of the packet that
             * triggered the violation.
             *
             * <p>This inspects both IPv4/IPv6 and TCP/UDP network traffic, but it may be subject to
             * false positives, such as when STARTTLS protocols or HTTP proxies are used.
             */
            public Builder detectCleartextNetwork() {
                return enable(DETECT_VM_CLEARTEXT_NETWORK);
            }

            /**
             * Detect when the calling application sends a {@code content://} {@link
             * android.net.Uri} to another app without setting {@link
             * Intent#FLAG_GRANT_READ_URI_PERMISSION} or {@link
             * Intent#FLAG_GRANT_WRITE_URI_PERMISSION}.
             *
             * <p>Forgetting to include one or more of these flags when sending an intent is
             * typically an app bug.
             *
             * @see Intent#FLAG_GRANT_READ_URI_PERMISSION
             * @see Intent#FLAG_GRANT_WRITE_URI_PERMISSION
             */
            public Builder detectContentUriWithoutPermission() {
                return enable(DETECT_VM_CONTENT_URI_WITHOUT_PERMISSION);
            }

            /**
             * Detect any sockets in the calling app which have not been tagged using {@link
             * TrafficStats}. Tagging sockets can help you investigate network usage inside your
             * app, such as a narrowing down heavy usage to a specific library or component.
             *
             * <p>This currently does not detect sockets created in native code.
             *
             * @see TrafficStats#setThreadStatsTag(int)
             * @see TrafficStats#tagSocket(java.net.Socket)
             * @see TrafficStats#tagDatagramSocket(java.net.DatagramSocket)
             */
            public Builder detectUntaggedSockets() {
                return enable(DETECT_VM_UNTAGGED_SOCKET);
            }

            /** @hide */
            public Builder permitUntaggedSockets() {
                return disable(DETECT_VM_UNTAGGED_SOCKET);
            }

            /**
             * Crashes the whole process on violation. This penalty runs at the end of all enabled
             * penalties so you'll still get your logging or other violations before the process
             * dies.
             */
            public Builder penaltyDeath() {
                return enable(PENALTY_DEATH);
            }

            /**
             * Crashes the whole process when cleartext network traffic is detected.
             *
             * @see #detectCleartextNetwork()
             */
            public Builder penaltyDeathOnCleartextNetwork() {
                return enable(PENALTY_DEATH_ON_CLEARTEXT_NETWORK);
            }

            /**
             * Crashes the whole process when a {@code file://} {@link android.net.Uri} is exposed
             * beyond this app.
             *
             * @see #detectFileUriExposure()
             */
            public Builder penaltyDeathOnFileUriExposure() {
                return enable(PENALTY_DEATH_ON_FILE_URI_EXPOSURE);
            }

            /** Log detected violations to the system log. */
            public Builder penaltyLog() {
                return enable(PENALTY_LOG);
            }

            /**
             * Enable detected violations log a stacktrace and timing data to the {@link
             * android.os.DropBoxManager DropBox} on policy violation. Intended mostly for platform
             * integrators doing beta user field data collection.
             */
            public Builder penaltyDropBox() {
                return enable(PENALTY_DROPBOX);
            }

            /**
             * Call #{@link OnVmViolationListener#onVmViolation(Violation)} on every violation.
             */
            public Builder penaltyListener(
                    @NonNull Executor executor, @NonNull OnVmViolationListener listener) {
                if (executor == null) {
                    throw new NullPointerException("executor must not be null");
                }
                mListener = listener;
                mExecutor = executor;
                return this;
            }

            /** @removed */
            public Builder penaltyListener(
                    @NonNull OnVmViolationListener listener, @NonNull Executor executor) {
                return penaltyListener(executor, listener);
            }

            private Builder enable(int bit) {
                mMask |= bit;
                return this;
            }

            Builder disable(int bit) {
                mMask &= ~bit;
                return this;
            }

            /**
             * Construct the VmPolicy instance.
             *
             * <p>Note: if no penalties are enabled before calling <code>build</code>, {@link
             * #penaltyLog} is implicitly set.
             */
            public VmPolicy build() {
                // If there are detection bits set but no violation bits
                // set, enable simple logging.
                if (mListener == null
                        && mMask != 0
                        && (mMask
                                        & (PENALTY_DEATH
                                                | PENALTY_LOG
                                                | PENALTY_DROPBOX
                                                | PENALTY_DIALOG))
                                == 0) {
                    penaltyLog();
                }
                return new VmPolicy(
                        mMask,
                        mClassInstanceLimit != null ? mClassInstanceLimit : EMPTY_CLASS_LIMIT_MAP,
                        mListener,
                        mExecutor);
            }
        }
    }

    /**
     * Log of strict mode violation stack traces that have occurred during a Binder call, to be
     * serialized back later to the caller via Parcel.writeNoException() (amusingly) where the
     * caller can choose how to react.
     */
    private static final ThreadLocal<ArrayList<ViolationInfo>> gatheredViolations =
            new ThreadLocal<ArrayList<ViolationInfo>>() {
                @Override
                protected ArrayList<ViolationInfo> initialValue() {
                    // Starts null to avoid unnecessary allocations when
                    // checking whether there are any violations or not in
                    // hasGatheredViolations() below.
                    return null;
                }
            };

    /**
     * Sets the policy for what actions on the current thread should be detected, as well as the
     * penalty if such actions occur.
     *
     * <p>Internally this sets a thread-local variable which is propagated across cross-process IPC
     * calls, meaning you can catch violations when a system service or another process accesses the
     * disk or network on your behalf.
     *
     * @param policy the policy to put into place
     */
    public static void setThreadPolicy(final ThreadPolicy policy) {
        setThreadPolicyMask(policy.mask);
        sThreadViolationListener.set(policy.mListener);
        sThreadViolationExecutor.set(policy.mCallbackExecutor);
    }

    /** @hide */
    public static void setThreadPolicyMask(final int policyMask) {
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
        final BlockGuard.Policy policy = BlockGuard.getThreadPolicy();
        final AndroidBlockGuardPolicy androidPolicy;
        if (policy instanceof AndroidBlockGuardPolicy) {
            androidPolicy = (AndroidBlockGuardPolicy) policy;
        } else {
            androidPolicy = THREAD_ANDROID_POLICY.get();
            BlockGuard.setThreadPolicy(androidPolicy);
        }
        androidPolicy.setPolicyMask(policyMask);
    }

    // Sets up CloseGuard in Dalvik/libcore
    private static void setCloseGuardEnabled(boolean enabled) {
        if (!(CloseGuard.getReporter() instanceof AndroidCloseGuardReporter)) {
            CloseGuard.setReporter(new AndroidCloseGuardReporter());
        }
        CloseGuard.setEnabled(enabled);
    }

    /**
     * Returns the bitmask of the current thread's policy.
     *
     * @return the bitmask of all the DETECT_* and PENALTY_* bits currently enabled
     * @hide
     */
    public static int getThreadPolicyMask() {
        return BlockGuard.getThreadPolicy().getPolicyMask();
    }

    /** Returns the current thread's policy. */
    public static ThreadPolicy getThreadPolicy() {
        // TODO: this was a last minute Gingerbread API change (to
        // introduce VmPolicy cleanly) but this isn't particularly
        // optimal for users who might call this method often.  This
        // should be in a thread-local and not allocate on each call.
        return new ThreadPolicy(
                getThreadPolicyMask(),
                sThreadViolationListener.get(),
                sThreadViolationExecutor.get());
    }

    /**
     * A convenience wrapper that takes the current {@link ThreadPolicy} from {@link
     * #getThreadPolicy}, modifies it to permit both disk reads &amp; writes, and sets the new
     * policy with {@link #setThreadPolicy}, returning the old policy so you can restore it at the
     * end of a block.
     *
     * @return the old policy, to be passed to {@link #setThreadPolicy} to restore the policy at the
     *     end of a block
     */
    public static ThreadPolicy allowThreadDiskWrites() {
        return new ThreadPolicy(
                allowThreadDiskWritesMask(),
                sThreadViolationListener.get(),
                sThreadViolationExecutor.get());
    }

    /** @hide */
    public static int allowThreadDiskWritesMask() {
        int oldPolicyMask = getThreadPolicyMask();
        int newPolicyMask = oldPolicyMask & ~(DETECT_DISK_WRITE | DETECT_DISK_READ);
        if (newPolicyMask != oldPolicyMask) {
            setThreadPolicyMask(newPolicyMask);
        }
        return oldPolicyMask;
    }

    /**
     * A convenience wrapper that takes the current {@link ThreadPolicy} from {@link
     * #getThreadPolicy}, modifies it to permit disk reads, and sets the new policy with {@link
     * #setThreadPolicy}, returning the old policy so you can restore it at the end of a block.
     *
     * @return the old policy, to be passed to setThreadPolicy to restore the policy.
     */
    public static ThreadPolicy allowThreadDiskReads() {
        return new ThreadPolicy(
                allowThreadDiskReadsMask(),
                sThreadViolationListener.get(),
                sThreadViolationExecutor.get());
    }

    /** @hide */
    public static int allowThreadDiskReadsMask() {
        int oldPolicyMask = getThreadPolicyMask();
        int newPolicyMask = oldPolicyMask & ~(DETECT_DISK_READ);
        if (newPolicyMask != oldPolicyMask) {
            setThreadPolicyMask(newPolicyMask);
        }
        return oldPolicyMask;
    }

    private static ThreadPolicy allowThreadViolations() {
        ThreadPolicy oldPolicy = getThreadPolicy();
        setThreadPolicyMask(0);
        return oldPolicy;
    }

    private static VmPolicy allowVmViolations() {
        VmPolicy oldPolicy = getVmPolicy();
        sVmPolicy = VmPolicy.LAX;
        return oldPolicy;
    }

    /**
     * Determine if the given app is "bundled" as part of the system image. These bundled apps are
     * developed in lock-step with the OS, and they aren't updated outside of an OTA, so we want to
     * chase any {@link StrictMode} regressions by enabling detection when running on {@link
     * Build#IS_USERDEBUG} or {@link Build#IS_ENG} builds.
     *
     * <p>Unbundled apps included in the system image are expected to detect and triage their own
     * {@link StrictMode} issues separate from the OS release process, which is why we don't enable
     * them here.
     *
     * @hide
     */
    public static boolean isBundledSystemApp(ApplicationInfo ai) {
        if (ai == null || ai.packageName == null) {
            // Probably system server
            return true;
        } else if (ai.isSystemApp()) {
            // Ignore unbundled apps living in the wrong namespace
            if (ai.packageName.equals("com.android.vending")
                    || ai.packageName.equals("com.android.chrome")) {
                return false;
            }

            // Ignore bundled apps that are way too spammy
            // STOPSHIP: burn this list down to zero
            if (ai.packageName.equals("com.android.phone")) {
                return false;
            }

            if (ai.packageName.equals("android")
                    || ai.packageName.startsWith("android.")
                    || ai.packageName.startsWith("com.android.")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Initialize default {@link ThreadPolicy} for the current thread.
     *
     * @hide
     */
    public static void initThreadDefaults(ApplicationInfo ai) {
        final ThreadPolicy.Builder builder = new ThreadPolicy.Builder();
        final int targetSdkVersion =
                (ai != null) ? ai.targetSdkVersion : Build.VERSION_CODES.CUR_DEVELOPMENT;

        // Starting in HC, we don't allow network usage on the main thread
        if (targetSdkVersion >= Build.VERSION_CODES.HONEYCOMB) {
            builder.detectNetwork();
            builder.penaltyDeathOnNetwork();
        }

        if (Build.IS_USER || DISABLE || SystemProperties.getBoolean(DISABLE_PROPERTY, false)) {
            // Detect nothing extra
        } else if (Build.IS_USERDEBUG) {
            // Detect everything in bundled apps
            if (isBundledSystemApp(ai)) {
                builder.detectAll();
                builder.penaltyDropBox();
                if (SystemProperties.getBoolean(VISUAL_PROPERTY, false)) {
                    builder.penaltyFlashScreen();
                }
            }
        } else if (Build.IS_ENG) {
            // Detect everything in bundled apps
            if (isBundledSystemApp(ai)) {
                builder.detectAll();
                builder.penaltyDropBox();
                builder.penaltyLog();
                builder.penaltyFlashScreen();
            }
        }

        setThreadPolicy(builder.build());
    }

    /**
     * Initialize default {@link VmPolicy} for the current VM.
     *
     * @hide
     */
    public static void initVmDefaults(ApplicationInfo ai) {
        final VmPolicy.Builder builder = new VmPolicy.Builder();
        final int targetSdkVersion =
                (ai != null) ? ai.targetSdkVersion : Build.VERSION_CODES.CUR_DEVELOPMENT;

        // Starting in N, we don't allow file:// Uri exposure
        if (targetSdkVersion >= Build.VERSION_CODES.N) {
            builder.detectFileUriExposure();
            builder.penaltyDeathOnFileUriExposure();
        }

        if (Build.IS_USER || DISABLE || SystemProperties.getBoolean(DISABLE_PROPERTY, false)) {
            // Detect nothing extra
        } else if (Build.IS_USERDEBUG) {
            // Detect everything in bundled apps (except activity leaks, which
            // are expensive to track)
            if (isBundledSystemApp(ai)) {
                builder.detectAll();
                builder.permitActivityLeaks();
                builder.penaltyDropBox();
            }
        } else if (Build.IS_ENG) {
            // Detect everything in bundled apps
            if (isBundledSystemApp(ai)) {
                builder.detectAll();
                builder.penaltyDropBox();
                builder.penaltyLog();
            }
        }

        setVmPolicy(builder.build());
    }

    /**
     * Used by the framework to make file usage a fatal error.
     *
     * @hide
     */
    public static void enableDeathOnFileUriExposure() {
        sVmPolicy =
                new VmPolicy(
                        sVmPolicy.mask
                                | DETECT_VM_FILE_URI_EXPOSURE
                                | PENALTY_DEATH_ON_FILE_URI_EXPOSURE,
                        sVmPolicy.classInstanceLimit,
                        sVmPolicy.mListener,
                        sVmPolicy.mCallbackExecutor);
    }

    /**
     * Used by lame internal apps that haven't done the hard work to get themselves off file:// Uris
     * yet.
     *
     * @hide
     */
    public static void disableDeathOnFileUriExposure() {
        sVmPolicy =
                new VmPolicy(
                        sVmPolicy.mask
                                & ~(DETECT_VM_FILE_URI_EXPOSURE
                                        | PENALTY_DEATH_ON_FILE_URI_EXPOSURE),
                        sVmPolicy.classInstanceLimit,
                        sVmPolicy.mListener,
                        sVmPolicy.mCallbackExecutor);
    }

    /**
     * Parses the BlockGuard policy mask out from the Exception's getMessage() String value. Kinda
     * gross, but least invasive. :/
     *
     * <p>Input is of the following forms: "policy=137 violation=64" "policy=137 violation=64
     * msg=Arbitrary text"
     *
     * <p>Returns 0 on failure, which is a valid policy, but not a valid policy during a violation
     * (else there must've been some policy in effect to violate).
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
            return Integer.parseInt(policyString);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static final ThreadLocal<ArrayList<ViolationInfo>> violationsBeingTimed =
            new ThreadLocal<ArrayList<ViolationInfo>>() {
                @Override
                protected ArrayList<ViolationInfo> initialValue() {
                    return new ArrayList<ViolationInfo>();
                }
            };

    // Note: only access this once verifying the thread has a Looper.
    private static final ThreadLocal<Handler> THREAD_HANDLER =
            new ThreadLocal<Handler>() {
                @Override
                protected Handler initialValue() {
                    return new Handler();
                }
            };

    private static final ThreadLocal<AndroidBlockGuardPolicy> THREAD_ANDROID_POLICY =
            new ThreadLocal<AndroidBlockGuardPolicy>() {
                @Override
                protected AndroidBlockGuardPolicy initialValue() {
                    return new AndroidBlockGuardPolicy(0);
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
        private ArrayMap<Integer, Long> mLastViolationTime;

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
            startHandlingViolationException(new DiskWriteViolation());
        }

        // Not part of BlockGuard.Policy; just part of StrictMode:
        void onCustomSlowCall(String name) {
            if ((mPolicyMask & DETECT_CUSTOM) == 0) {
                return;
            }
            if (tooManyViolationsThisLoop()) {
                return;
            }
            startHandlingViolationException(new CustomViolation(name));
        }

        // Not part of BlockGuard.Policy; just part of StrictMode:
        void onResourceMismatch(Object tag) {
            if ((mPolicyMask & DETECT_RESOURCE_MISMATCH) == 0) {
                return;
            }
            if (tooManyViolationsThisLoop()) {
                return;
            }
            startHandlingViolationException(new ResourceMismatchViolation(tag));
        }

        // Not part of BlockGuard.Policy; just part of StrictMode:
        public void onUnbufferedIO() {
            if ((mPolicyMask & DETECT_UNBUFFERED_IO) == 0) {
                return;
            }
            if (tooManyViolationsThisLoop()) {
                return;
            }
            startHandlingViolationException(new UnbufferedIoViolation());
        }

        // Part of BlockGuard.Policy interface:
        public void onReadFromDisk() {
            if ((mPolicyMask & DETECT_DISK_READ) == 0) {
                return;
            }
            if (tooManyViolationsThisLoop()) {
                return;
            }
            startHandlingViolationException(new DiskReadViolation());
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
            startHandlingViolationException(new NetworkViolation());
        }

        public void setPolicyMask(int policyMask) {
            mPolicyMask = policyMask;
        }

        // Start handling a violation that just started and hasn't
        // actually run yet (e.g. no disk write or network operation
        // has yet occurred).  This sees if we're in an event loop
        // thread and, if so, uses it to roughly measure how long the
        // violation took.
        void startHandlingViolationException(Violation e) {
            final ViolationInfo info = new ViolationInfo(e, mPolicyMask);
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
            if (looper == null || (info.mPolicy & THREAD_PENALTY_MASK) == PENALTY_DEATH) {
                info.durationMillis = -1; // unknown (redundant, already set)
                onThreadPolicyViolation(info);
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

            final IWindowManager windowManager =
                    info.penaltyEnabled(PENALTY_FLASH) ? sWindowManager.get() : null;
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
            THREAD_HANDLER
                    .get()
                    .postAtFrontOfQueue(
                            () -> {
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
                                    onThreadPolicyViolation(v);
                                }
                                records.clear();
                            });
        }

        // Note: It's possible (even quite likely) that the
        // thread-local policy mask has changed from the time the
        // violation fired and now (after the violating code ran) due
        // to people who push/pop temporary policy in regions of code,
        // hence the policy being passed around.
        void onThreadPolicyViolation(final ViolationInfo info) {
            if (LOG_V) Log.d(TAG, "onThreadPolicyViolation; policy=" + info.mPolicy);

            if (info.penaltyEnabled(PENALTY_GATHER)) {
                ArrayList<ViolationInfo> violations = gatheredViolations.get();
                if (violations == null) {
                    violations = new ArrayList<>(1);
                    gatheredViolations.set(violations);
                }
                for (ViolationInfo previous : violations) {
                    if (info.getStackTrace().equals(previous.getStackTrace())) {
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
            if (mLastViolationTime != null) {
                Long vtime = mLastViolationTime.get(crashFingerprint);
                if (vtime != null) {
                    lastViolationTime = vtime;
                }
            } else {
                mLastViolationTime = new ArrayMap<>(1);
            }
            long now = SystemClock.uptimeMillis();
            mLastViolationTime.put(crashFingerprint, now);
            long timeSinceLastViolationMillis =
                    lastViolationTime == 0 ? Long.MAX_VALUE : (now - lastViolationTime);

            if (info.penaltyEnabled(PENALTY_LOG)
                    && timeSinceLastViolationMillis > MIN_LOG_INTERVAL_MS) {
                sLogger.log(info);
            }

            final Violation violation = info.mViolation;

            // The violationMaskSubset, passed to ActivityManager, is a
            // subset of the original StrictMode policy bitmask, with
            // only the bit violated and penalty bits to be executed
            // by the ActivityManagerService remaining set.
            int violationMaskSubset = 0;

            if (info.penaltyEnabled(PENALTY_DIALOG)
                    && timeSinceLastViolationMillis > MIN_DIALOG_INTERVAL_MS) {
                violationMaskSubset |= PENALTY_DIALOG;
            }

            if (info.penaltyEnabled(PENALTY_DROPBOX) && lastViolationTime == 0) {
                violationMaskSubset |= PENALTY_DROPBOX;
            }

            if (violationMaskSubset != 0) {
                violationMaskSubset |= info.getViolationBit();

                final boolean justDropBox = (info.mPolicy & THREAD_PENALTY_MASK) == PENALTY_DROPBOX;
                if (justDropBox) {
                    // If all we're going to ask the activity manager
                    // to do is dropbox it (the common case during
                    // platform development), we can avoid doing this
                    // call synchronously which Binder data suggests
                    // isn't always super fast, despite the implementation
                    // in the ActivityManager trying to be mostly async.
                    dropboxViolationAsync(violationMaskSubset, info);
                } else {
                    handleApplicationStrictModeViolation(violationMaskSubset, info);
                }
            }

            if ((info.getPolicyMask() & PENALTY_DEATH) != 0) {
                throw new RuntimeException("StrictMode ThreadPolicy violation", violation);
            }

            // penaltyDeath will cause penaltyCallback to no-op since we cannot guarantee the
            // executor finishes before crashing.
            final OnThreadViolationListener listener = sThreadViolationListener.get();
            final Executor executor = sThreadViolationExecutor.get();
            if (listener != null && executor != null) {
                try {
                    executor.execute(
                            () -> {
                                // Lift violated policy to prevent infinite recursion.
                                ThreadPolicy oldPolicy = allowThreadViolations();
                                try {
                                    listener.onThreadViolation(violation);
                                } finally {
                                    setThreadPolicy(oldPolicy);
                                }
                            });
                } catch (RejectedExecutionException e) {
                    Log.e(TAG, "ThreadPolicy penaltyCallback failed", e);
                }
            }
        }
    }

    /**
     * In the common case, as set by conditionallyEnableDebugLogging, we're just dropboxing any
     * violations but not showing a dialog, not loggging, and not killing the process. In these
     * cases we don't need to do a synchronous call to the ActivityManager. This is used by both
     * per-thread and vm-wide violations when applicable.
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

        BackgroundThread.getHandler().post(() -> {
            handleApplicationStrictModeViolation(violationMaskSubset, info);
            int outstandingInner = sDropboxCallsInFlight.decrementAndGet();
            if (LOG_V) Log.d(TAG, "Dropbox complete; in-flight=" + outstandingInner);
        });
    }

    private static void handleApplicationStrictModeViolation(int violationMaskSubset,
            ViolationInfo info) {
        final int oldMask = getThreadPolicyMask();
        try {
            // First, remove any policy before we call into the Activity Manager,
            // otherwise we'll infinite recurse as we try to log policy violations
            // to disk, thus violating policy, thus requiring logging, etc...
            // We restore the current policy below, in the finally block.
            setThreadPolicyMask(0);

            IActivityManager am = ActivityManager.getService();
            if (am == null) {
                Log.w(TAG, "No activity manager; failed to Dropbox violation.");
            } else {
                am.handleApplicationStrictModeViolation(
                        RuntimeInit.getApplicationObject(), violationMaskSubset, info);
            }
        } catch (RemoteException e) {
            if (e instanceof DeadObjectException) {
                // System process is dead; ignore
            } else {
                Log.e(TAG, "RemoteException handling StrictMode violation", e);
            }
        } finally {
            setThreadPolicyMask(oldMask);
        }
    }

    private static class AndroidCloseGuardReporter implements CloseGuard.Reporter {
        public void report(String message, Throwable allocationSite) {
            onVmPolicyViolation(new LeakedClosableViolation(message, allocationSite));
        }
    }

    /** Called from Parcel.writeNoException() */
    /* package */ static boolean hasGatheredViolations() {
        return gatheredViolations.get() != null;
    }

    /**
     * Called from Parcel.writeException(), so we drop this memory and don't incorrectly attribute
     * it to the wrong caller on the next Binder call on this thread.
     */
    /* package */ static void clearGatheredViolations() {
        gatheredViolations.set(null);
    }

    /** @hide */
    public static void conditionallyCheckInstanceCounts() {
        VmPolicy policy = getVmPolicy();
        int policySize = policy.classInstanceLimit.size();
        if (policySize == 0) {
            return;
        }

        System.gc();
        System.runFinalization();
        System.gc();

        // Note: classInstanceLimit is immutable, so this is lock-free
        // Create the classes array.
        Class[] classes = policy.classInstanceLimit.keySet().toArray(new Class[policySize]);
        long[] instanceCounts = VMDebug.countInstancesOfClasses(classes, false);
        for (int i = 0; i < classes.length; ++i) {
            Class klass = classes[i];
            int limit = policy.classInstanceLimit.get(klass);
            long instances = instanceCounts[i];
            if (instances > limit) {
                onVmPolicyViolation(new InstanceCountViolation(klass, instances, limit));
            }
        }
    }

    private static long sLastInstanceCountCheckMillis = 0;
    private static boolean sIsIdlerRegistered = false; // guarded by StrictMode.class
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
     * Sets the policy for what actions in the VM process (on any thread) should be detected, as
     * well as the penalty if such actions occur.
     *
     * @param policy the policy to put into place
     */
    public static void setVmPolicy(final VmPolicy policy) {
        synchronized (StrictMode.class) {
            sVmPolicy = policy;
            setCloseGuardEnabled(vmClosableObjectLeaksEnabled());

            Looper looper = Looper.getMainLooper();
            if (looper != null) {
                MessageQueue mq = looper.mQueue;
                if (policy.classInstanceLimit.size() == 0
                        || (sVmPolicy.mask & VM_PENALTY_MASK) == 0) {
                    mq.removeIdleHandler(sProcessIdleHandler);
                    sIsIdlerRegistered = false;
                } else if (!sIsIdlerRegistered) {
                    mq.addIdleHandler(sProcessIdleHandler);
                    sIsIdlerRegistered = true;
                }
            }

            int networkPolicy = NETWORK_POLICY_ACCEPT;
            if ((sVmPolicy.mask & DETECT_VM_CLEARTEXT_NETWORK) != 0) {
                if ((sVmPolicy.mask & PENALTY_DEATH) != 0
                        || (sVmPolicy.mask & PENALTY_DEATH_ON_CLEARTEXT_NETWORK) != 0) {
                    networkPolicy = NETWORK_POLICY_REJECT;
                } else {
                    networkPolicy = NETWORK_POLICY_LOG;
                }
            }

            final INetworkManagementService netd =
                    INetworkManagementService.Stub.asInterface(
                            ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE));
            if (netd != null) {
                try {
                    netd.setUidCleartextNetworkPolicy(android.os.Process.myUid(), networkPolicy);
                } catch (RemoteException ignored) {
                }
            } else if (networkPolicy != NETWORK_POLICY_ACCEPT) {
                Log.w(TAG, "Dropping requested network policy due to missing service!");
            }


            if ((sVmPolicy.mask & DETECT_VM_NON_SDK_API_USAGE) != 0) {
                VMRuntime.setNonSdkApiUsageConsumer(sNonSdkApiUsageConsumer);
                VMRuntime.setDedupeHiddenApiWarnings(false);
            } else {
                VMRuntime.setNonSdkApiUsageConsumer(null);
                VMRuntime.setDedupeHiddenApiWarnings(true);
            }
        }
    }

    /** Gets the current VM policy. */
    public static VmPolicy getVmPolicy() {
        synchronized (StrictMode.class) {
            return sVmPolicy;
        }
    }

    /**
     * Enable the recommended StrictMode defaults, with violations just being logged.
     *
     * <p>This catches disk and network access on the main thread, as well as leaked SQLite cursors
     * and unclosed resources. This is simply a wrapper around {@link #setVmPolicy} and {@link
     * #setThreadPolicy}.
     */
    public static void enableDefaults() {
        setThreadPolicy(new StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build());
        setVmPolicy(new StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build());
    }

    /** @hide */
    public static boolean vmSqliteObjectLeaksEnabled() {
        return (sVmPolicy.mask & DETECT_VM_CURSOR_LEAKS) != 0;
    }

    /** @hide */
    public static boolean vmClosableObjectLeaksEnabled() {
        return (sVmPolicy.mask & DETECT_VM_CLOSABLE_LEAKS) != 0;
    }

    /** @hide */
    public static boolean vmRegistrationLeaksEnabled() {
        return (sVmPolicy.mask & DETECT_VM_REGISTRATION_LEAKS) != 0;
    }

    /** @hide */
    public static boolean vmFileUriExposureEnabled() {
        return (sVmPolicy.mask & DETECT_VM_FILE_URI_EXPOSURE) != 0;
    }

    /** @hide */
    public static boolean vmCleartextNetworkEnabled() {
        return (sVmPolicy.mask & DETECT_VM_CLEARTEXT_NETWORK) != 0;
    }

    /** @hide */
    public static boolean vmContentUriWithoutPermissionEnabled() {
        return (sVmPolicy.mask & DETECT_VM_CONTENT_URI_WITHOUT_PERMISSION) != 0;
    }

    /** @hide */
    public static boolean vmUntaggedSocketEnabled() {
        return (sVmPolicy.mask & DETECT_VM_UNTAGGED_SOCKET) != 0;
    }

    /** @hide */
    public static void onSqliteObjectLeaked(String message, Throwable originStack) {
        onVmPolicyViolation(new SqliteObjectLeakedViolation(message, originStack));
    }

    /** @hide */
    public static void onWebViewMethodCalledOnWrongThread(Throwable originStack) {
        onVmPolicyViolation(new WebViewMethodCalledOnWrongThreadViolation(originStack));
    }

    /** @hide */
    public static void onIntentReceiverLeaked(Throwable originStack) {
        onVmPolicyViolation(new IntentReceiverLeakedViolation(originStack));
    }

    /** @hide */
    public static void onServiceConnectionLeaked(Throwable originStack) {
        onVmPolicyViolation(new ServiceConnectionLeakedViolation(originStack));
    }

    /** @hide */
    public static void onFileUriExposed(Uri uri, String location) {
        final String message = uri + " exposed beyond app through " + location;
        if ((sVmPolicy.mask & PENALTY_DEATH_ON_FILE_URI_EXPOSURE) != 0) {
            throw new FileUriExposedException(message);
        } else {
            onVmPolicyViolation(new FileUriExposedViolation(message));
        }
    }

    /** @hide */
    public static void onContentUriWithoutPermission(Uri uri, String location) {
        onVmPolicyViolation(new ContentUriWithoutPermissionViolation(uri, location));
    }

    /** @hide */
    public static final String CLEARTEXT_DETECTED_MSG =
            "Detected cleartext network traffic from UID ";

    /** @hide */
    public static void onCleartextNetworkDetected(byte[] firstPacket) {
        byte[] rawAddr = null;
        if (firstPacket != null) {
            if (firstPacket.length >= 20 && (firstPacket[0] & 0xf0) == 0x40) {
                // IPv4
                rawAddr = new byte[4];
                System.arraycopy(firstPacket, 16, rawAddr, 0, 4);
            } else if (firstPacket.length >= 40 && (firstPacket[0] & 0xf0) == 0x60) {
                // IPv6
                rawAddr = new byte[16];
                System.arraycopy(firstPacket, 24, rawAddr, 0, 16);
            }
        }

        final int uid = android.os.Process.myUid();
        String msg = CLEARTEXT_DETECTED_MSG + uid;
        if (rawAddr != null) {
            try {
                msg += " to " + InetAddress.getByAddress(rawAddr);
            } catch (UnknownHostException ignored) {
            }
        }
        msg += HexDump.dumpHexString(firstPacket).trim() + " ";
        final boolean forceDeath = (sVmPolicy.mask & PENALTY_DEATH_ON_CLEARTEXT_NETWORK) != 0;
        onVmPolicyViolation(new CleartextNetworkViolation(msg), forceDeath);
    }

    /** @hide */
    public static void onUntaggedSocket() {
        onVmPolicyViolation(new UntaggedSocketViolation());
    }

    // Map from VM violation fingerprint to uptime millis.
    private static final HashMap<Integer, Long> sLastVmViolationTime = new HashMap<>();

    /** @hide */
    public static void onVmPolicyViolation(Violation originStack) {
        onVmPolicyViolation(originStack, false);
    }

    /** @hide */
    public static void onVmPolicyViolation(Violation violation, boolean forceDeath) {
        final boolean penaltyDropbox = (sVmPolicy.mask & PENALTY_DROPBOX) != 0;
        final boolean penaltyDeath = ((sVmPolicy.mask & PENALTY_DEATH) != 0) || forceDeath;
        final boolean penaltyLog = (sVmPolicy.mask & PENALTY_LOG) != 0;
        final ViolationInfo info = new ViolationInfo(violation, sVmPolicy.mask);

        // Erase stuff not relevant for process-wide violations
        info.numAnimationsRunning = 0;
        info.tags = null;
        info.broadcastIntentAction = null;

        final Integer fingerprint = info.hashCode();
        final long now = SystemClock.uptimeMillis();
        long lastViolationTime;
        long timeSinceLastViolationMillis = Long.MAX_VALUE;
        synchronized (sLastVmViolationTime) {
            if (sLastVmViolationTime.containsKey(fingerprint)) {
                lastViolationTime = sLastVmViolationTime.get(fingerprint);
                timeSinceLastViolationMillis = now - lastViolationTime;
            }
            if (timeSinceLastViolationMillis > MIN_VM_INTERVAL_MS) {
                sLastVmViolationTime.put(fingerprint, now);
            }
        }
        if (timeSinceLastViolationMillis <= MIN_VM_INTERVAL_MS) {
            // Rate limit all penalties.
            return;
        }

        if (penaltyLog && sLogger != null && timeSinceLastViolationMillis > MIN_LOG_INTERVAL_MS) {
            sLogger.log(info);
        }

        int violationMaskSubset = PENALTY_DROPBOX | (ALL_VM_DETECT_BITS & sVmPolicy.mask);

        if (penaltyDropbox) {
            if (penaltyDeath) {
                handleApplicationStrictModeViolation(violationMaskSubset, info);
            } else {
                // Common case for userdebug/eng builds.  If no death and
                // just dropboxing, we can do the ActivityManager call
                // asynchronously.
                dropboxViolationAsync(violationMaskSubset, info);
            }
        }

        if (penaltyDeath) {
            System.err.println("StrictMode VmPolicy violation with POLICY_DEATH; shutting down.");
            Process.killProcess(Process.myPid());
            System.exit(10);
        }

        // If penaltyDeath, we can't guarantee this callback finishes before the process dies for
        // all executors. penaltyDeath supersedes penaltyCallback.
        if (sVmPolicy.mListener != null && sVmPolicy.mCallbackExecutor != null) {
            final OnVmViolationListener listener = sVmPolicy.mListener;
            try {
                sVmPolicy.mCallbackExecutor.execute(
                        () -> {
                            // Lift violated policy to prevent infinite recursion.
                            VmPolicy oldPolicy = allowVmViolations();
                            try {
                                listener.onVmViolation(violation);
                            } finally {
                                setVmPolicy(oldPolicy);
                            }
                        });
            } catch (RejectedExecutionException e) {
                Log.e(TAG, "VmPolicy penaltyCallback failed", e);
            }
        }
    }

    /** Called from Parcel.writeNoException() */
    /* package */ static void writeGatheredViolationsToParcel(Parcel p) {
        ArrayList<ViolationInfo> violations = gatheredViolations.get();
        if (violations == null) {
            p.writeInt(0);
        } else {
            // To avoid taking up too much transaction space, only include
            // details for the first 3 violations. Deep inside, CrashInfo
            // will truncate each stack trace to ~20kB.
            final int size = Math.min(violations.size(), 3);
            p.writeInt(size);
            for (int i = 0; i < size; i++) {
                violations.get(i).writeToParcel(p, 0);
            }
        }
        gatheredViolations.set(null);
    }

    /**
     * Called from Parcel.readException() when the exception is EX_STRICT_MODE_VIOLATIONS, we here
     * read back all the encoded violations.
     */
    /* package */ static void readAndHandleBinderCallViolations(Parcel p) {
        Throwable localCallSite = new Throwable();
        final int policyMask = getThreadPolicyMask();
        final boolean currentlyGathering = (policyMask & PENALTY_GATHER) != 0;

        final int size = p.readInt();
        for (int i = 0; i < size; i++) {
            final ViolationInfo info = new ViolationInfo(p, !currentlyGathering);
            info.addLocalStack(localCallSite);
            BlockGuard.Policy policy = BlockGuard.getThreadPolicy();
            if (policy instanceof AndroidBlockGuardPolicy) {
                ((AndroidBlockGuardPolicy) policy).handleViolationWithTimingAttempt(info);
            }
        }
    }

    /**
     * Called from android_util_Binder.cpp's android_os_Parcel_enforceInterface when an incoming
     * Binder call requires changing the StrictMode policy mask. The role of this function is to ask
     * Binder for its current (native) thread-local policy value and synchronize it to libcore's
     * (Java) thread-local policy value.
     */
    private static void onBinderStrictModePolicyChange(int newPolicy) {
        setBlockGuardPolicy(newPolicy);
    }

    /**
     * A tracked, critical time span. (e.g. during an animation.)
     *
     * <p>The object itself is a linked list node, to avoid any allocations during rapid span
     * entries and exits.
     *
     * @hide
     */
    public static class Span {
        private String mName;
        private long mCreateMillis;
        private Span mNext;
        private Span mPrev; // not used when in freeList, only active
        private final ThreadSpanState mContainerState;

        Span(ThreadSpanState threadState) {
            mContainerState = threadState;
        }

        // Empty constructor for the NO_OP_SPAN
        protected Span() {
            mContainerState = null;
        }

        /**
         * To be called when the critical span is complete (i.e. the animation is done animating).
         * This can be called on any thread (even a different one from where the animation was
         * taking place), but that's only a defensive implementation measure. It really makes no
         * sense for you to call this on thread other than that where you created it.
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
    private static final Span NO_OP_SPAN =
            new Span() {
                public void finish() {
                    // Do nothing.
                }
            };

    /**
     * Linked lists of active spans and a freelist.
     *
     * <p>Locking notes: there's one of these structures per thread and all members of this
     * structure (as well as the Span nodes under it) are guarded by the ThreadSpanState object
     * instance. While in theory there'd be no locking required because it's all local per-thread,
     * the finish() method above is defensive against people calling it on a different thread from
     * where they created the Span, hence the locking.
     */
    private static class ThreadSpanState {
        public Span mActiveHead; // doubly-linked list.
        public int mActiveSize;
        public Span mFreeListHead; // singly-linked list.  only changes at head.
        public int mFreeListSize;
    }

    private static final ThreadLocal<ThreadSpanState> sThisThreadSpanState =
            new ThreadLocal<ThreadSpanState>() {
                @Override
                protected ThreadSpanState initialValue() {
                    return new ThreadSpanState();
                }
            };

    private static Singleton<IWindowManager> sWindowManager =
            new Singleton<IWindowManager>() {
                protected IWindowManager create() {
                    return IWindowManager.Stub.asInterface(ServiceManager.getService("window"));
                }
            };

    /**
     * Enter a named critical span (e.g. an animation)
     *
     * <p>The name is an arbitary label (or tag) that will be applied to any strictmode violation
     * that happens while this span is active. You must call finish() on the span when done.
     *
     * <p>This will never return null, but on devices without debugging enabled, this may return a
     * dummy object on which the finish() method is a no-op.
     *
     * <p>TODO: add CloseGuard to this, verifying callers call finish.
     *
     * @hide
     */
    public static Span enterCriticalSpan(String name) {
        if (Build.IS_USER) {
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
     * For code to note that it's slow. This is a no-op unless the current thread's {@link
     * android.os.StrictMode.ThreadPolicy} has {@link
     * android.os.StrictMode.ThreadPolicy.Builder#detectCustomSlowCalls} enabled.
     *
     * @param name a short string for the exception stack trace that's built if when this fires.
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
     * For code to note that a resource was obtained using a type other than its defined type. This
     * is a no-op unless the current thread's {@link android.os.StrictMode.ThreadPolicy} has {@link
     * android.os.StrictMode.ThreadPolicy.Builder#detectResourceMismatches()} enabled.
     *
     * @param tag an object for the exception stack trace that's built if when this fires.
     * @hide
     */
    public static void noteResourceMismatch(Object tag) {
        BlockGuard.Policy policy = BlockGuard.getThreadPolicy();
        if (!(policy instanceof AndroidBlockGuardPolicy)) {
            // StrictMode not enabled.
            return;
        }
        ((AndroidBlockGuardPolicy) policy).onResourceMismatch(tag);
    }

    /** @hide */
    public static void noteUnbufferedIO() {
        BlockGuard.Policy policy = BlockGuard.getThreadPolicy();
        if (!(policy instanceof AndroidBlockGuardPolicy)) {
            // StrictMode not enabled.
            return;
        }
        policy.onUnbufferedIO();
    }

    /** @hide */
    public static void noteDiskRead() {
        BlockGuard.Policy policy = BlockGuard.getThreadPolicy();
        if (!(policy instanceof AndroidBlockGuardPolicy)) {
            // StrictMode not enabled.
            return;
        }
        policy.onReadFromDisk();
    }

    /** @hide */
    public static void noteDiskWrite() {
        BlockGuard.Policy policy = BlockGuard.getThreadPolicy();
        if (!(policy instanceof AndroidBlockGuardPolicy)) {
            // StrictMode not enabled.
            return;
        }
        policy.onWriteToDisk();
    }

    @GuardedBy("StrictMode.class")
    private static final HashMap<Class, Integer> sExpectedActivityInstanceCount = new HashMap<>();

    /**
     * Returns an object that is used to track instances of activites. The activity should store a
     * reference to the tracker object in one of its fields.
     *
     * @hide
     */
    public static Object trackActivity(Object instance) {
        return new InstanceTracker(instance);
    }

    /** @hide */
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

    /** @hide */
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

        System.gc();
        System.runFinalization();
        System.gc();

        long instances = VMDebug.countInstancesOfClass(klass, false);
        if (instances > limit) {
            onVmPolicyViolation(new InstanceCountViolation(klass, instances, limit));
        }
    }

    /**
     * Parcelable that gets sent in Binder call headers back to callers to report violations that
     * happened during a cross-process call.
     *
     * @hide
     */
    @TestApi
    public static final class ViolationInfo implements Parcelable {
        /** Stack and violation details. */
        private final Violation mViolation;

        /** Path leading to a violation that occurred across binder. */
        private final Deque<StackTraceElement[]> mBinderStack = new ArrayDeque<>();

        /** Memoized stack trace of full violation. */
        @Nullable private String mStackTrace;

        /** The strict mode policy mask at the time of violation. */
        private final int mPolicy;

        /** The wall time duration of the violation, when known. -1 when not known. */
        public int durationMillis = -1;

        /** The number of animations currently running. */
        public int numAnimationsRunning = 0;

        /** List of tags from active Span instances during this violation, or null for none. */
        public String[] tags;

        /**
         * Which violation number this was (1-based) since the last Looper loop, from the
         * perspective of the root caller (if it crossed any processes via Binder calls). The value
         * is 0 if the root caller wasn't on a Looper thread.
         */
        public int violationNumThisLoop;

        /** The time (in terms of SystemClock.uptimeMillis()) that the violation occurred. */
        public long violationUptimeMillis;

        /**
         * The action of the Intent being broadcast to somebody's onReceive on this thread right
         * now, or null.
         */
        public String broadcastIntentAction;

        /** If this is a instance count violation, the number of instances in memory, else -1. */
        public long numInstances = -1;

        /** Create an instance of ViolationInfo initialized from an exception. */
        ViolationInfo(Violation tr, int policy) {
            this.mViolation = tr;
            this.mPolicy = policy;
            violationUptimeMillis = SystemClock.uptimeMillis();
            this.numAnimationsRunning = ValueAnimator.getCurrentAnimationsCount();
            Intent broadcastIntent = ActivityThread.getIntentBeingBroadcast();
            if (broadcastIntent != null) {
                broadcastIntentAction = broadcastIntent.getAction();
            }
            ThreadSpanState state = sThisThreadSpanState.get();
            if (tr instanceof InstanceCountViolation) {
                this.numInstances = ((InstanceCountViolation) tr).getNumberOfInstances();
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

        /** Equivalent output to {@link ApplicationErrorReport.CrashInfo#stackTrace}. */
        public String getStackTrace() {
            if (mStackTrace == null) {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new FastPrintWriter(sw, false, 256);
                mViolation.printStackTrace(pw);
                for (StackTraceElement[] traces : mBinderStack) {
                    pw.append("# via Binder call with stack:\n");
                    for (StackTraceElement traceElement : traces) {
                        pw.append("\tat ");
                        pw.append(traceElement.toString());
                        pw.append('\n');
                    }
                }
                pw.flush();
                pw.close();
                mStackTrace = sw.toString();
            }
            return mStackTrace;
        }

        /**
         * Optional message describing this violation.
         *
         * @hide
         */
        @TestApi
        public String getViolationDetails() {
            return mViolation.getMessage();
        }

        /**
         * Policy mask at time of violation.
         *
         * @hide
         */
        @TestApi
        public int getPolicyMask() {
            return mPolicy;
        }

        boolean penaltyEnabled(int p) {
            return (mPolicy & p) != 0;
        }

        /**
         * Add a {@link Throwable} from the current process that caused the underlying violation. We
         * only preserve the stack trace elements.
         *
         * @hide
         */
        void addLocalStack(Throwable t) {
            mBinderStack.addFirst(t.getStackTrace());
        }

        /**
         * Retrieve the type of StrictMode violation.
         *
         * @hide
         */
        @TestApi
        public int getViolationBit() {
            if (mViolation instanceof DiskWriteViolation) {
                return DETECT_DISK_WRITE;
            } else if (mViolation instanceof DiskReadViolation) {
                return DETECT_DISK_READ;
            } else if (mViolation instanceof NetworkViolation) {
                return DETECT_NETWORK;
            } else if (mViolation instanceof CustomViolation) {
                return DETECT_CUSTOM;
            } else if (mViolation instanceof ResourceMismatchViolation) {
                return DETECT_RESOURCE_MISMATCH;
            } else if (mViolation instanceof UnbufferedIoViolation) {
                return DETECT_UNBUFFERED_IO;
            } else if (mViolation instanceof SqliteObjectLeakedViolation) {
                return DETECT_VM_CURSOR_LEAKS;
            } else if (mViolation instanceof LeakedClosableViolation) {
                return DETECT_VM_CLOSABLE_LEAKS;
            } else if (mViolation instanceof InstanceCountViolation) {
                return DETECT_VM_INSTANCE_LEAKS;
            } else if (mViolation instanceof IntentReceiverLeakedViolation) {
                return DETECT_VM_REGISTRATION_LEAKS;
            } else if (mViolation instanceof ServiceConnectionLeakedViolation) {
                return DETECT_VM_REGISTRATION_LEAKS;
            } else if (mViolation instanceof FileUriExposedViolation) {
                return DETECT_VM_FILE_URI_EXPOSURE;
            } else if (mViolation instanceof CleartextNetworkViolation) {
                return DETECT_VM_CLEARTEXT_NETWORK;
            } else if (mViolation instanceof ContentUriWithoutPermissionViolation) {
                return DETECT_VM_CONTENT_URI_WITHOUT_PERMISSION;
            } else if (mViolation instanceof UntaggedSocketViolation) {
                return DETECT_VM_UNTAGGED_SOCKET;
            } else if (mViolation instanceof NonSdkApiUsedViolation) {
                return DETECT_VM_NON_SDK_API_USAGE;
            }
            throw new IllegalStateException("missing violation bit");
        }

        @Override
        public int hashCode() {
            int result = 17;
            if (mViolation != null) {
                result = 37 * result + mViolation.hashCode();
            }
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

        /** Create an instance of ViolationInfo initialized from a Parcel. */
        public ViolationInfo(Parcel in) {
            this(in, false);
        }

        /**
         * Create an instance of ViolationInfo initialized from a Parcel.
         *
         * @param unsetGatheringBit if true, the caller is the root caller and the gathering penalty
         *     should be removed.
         */
        public ViolationInfo(Parcel in, boolean unsetGatheringBit) {
            mViolation = (Violation) in.readSerializable();
            int binderStackSize = in.readInt();
            for (int i = 0; i < binderStackSize; i++) {
                StackTraceElement[] traceElements = new StackTraceElement[in.readInt()];
                for (int j = 0; j < traceElements.length; j++) {
                    StackTraceElement element =
                            new StackTraceElement(
                                    in.readString(),
                                    in.readString(),
                                    in.readString(),
                                    in.readInt());
                    traceElements[j] = element;
                }
                mBinderStack.add(traceElements);
            }
            int rawPolicy = in.readInt();
            if (unsetGatheringBit) {
                mPolicy = rawPolicy & ~PENALTY_GATHER;
            } else {
                mPolicy = rawPolicy;
            }
            durationMillis = in.readInt();
            violationNumThisLoop = in.readInt();
            numAnimationsRunning = in.readInt();
            violationUptimeMillis = in.readLong();
            numInstances = in.readLong();
            broadcastIntentAction = in.readString();
            tags = in.readStringArray();
        }

        /** Save a ViolationInfo instance to a parcel. */
        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeSerializable(mViolation);
            dest.writeInt(mBinderStack.size());
            for (StackTraceElement[] traceElements : mBinderStack) {
                dest.writeInt(traceElements.length);
                for (StackTraceElement element : traceElements) {
                    dest.writeString(element.getClassName());
                    dest.writeString(element.getMethodName());
                    dest.writeString(element.getFileName());
                    dest.writeInt(element.getLineNumber());
                }
            }
            int start = dest.dataPosition();
            dest.writeInt(mPolicy);
            dest.writeInt(durationMillis);
            dest.writeInt(violationNumThisLoop);
            dest.writeInt(numAnimationsRunning);
            dest.writeLong(violationUptimeMillis);
            dest.writeLong(numInstances);
            dest.writeString(broadcastIntentAction);
            dest.writeStringArray(tags);
            int total = dest.dataPosition() - start;
            if (Binder.CHECK_PARCEL_SIZE && total > 10 * 1024) {
                Slog.d(
                        TAG,
                        "VIO: policy="
                                + mPolicy
                                + " dur="
                                + durationMillis
                                + " numLoop="
                                + violationNumThisLoop
                                + " anim="
                                + numAnimationsRunning
                                + " uptime="
                                + violationUptimeMillis
                                + " numInst="
                                + numInstances);
                Slog.d(TAG, "VIO: action=" + broadcastIntentAction);
                Slog.d(TAG, "VIO: tags=" + Arrays.toString(tags));
                Slog.d(TAG, "VIO: TOTAL BYTES WRITTEN: " + (dest.dataPosition() - start));
            }
        }

        /** Dump a ViolationInfo instance to a Printer. */
        public void dump(Printer pw, String prefix) {
            pw.println(prefix + "stackTrace: " + getStackTrace());
            pw.println(prefix + "policy: " + mPolicy);
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

        @Override
        public int describeContents() {
            return 0;
        }

        public static final Parcelable.Creator<ViolationInfo> CREATOR =
                new Parcelable.Creator<ViolationInfo>() {
                    @Override
                    public ViolationInfo createFromParcel(Parcel in) {
                        return new ViolationInfo(in);
                    }

                    @Override
                    public ViolationInfo[] newArray(int size) {
                        return new ViolationInfo[size];
                    }
                };
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
