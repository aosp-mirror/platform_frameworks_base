/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.view;

import static android.Manifest.permission.READ_FRAME_BUFFER;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.GcUtils;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * A thread-safe registry used to track surface controls that are active (not yet released) within a
 * process, to help debug and identify leaks.
 * @hide
 */
public class SurfaceControlRegistry {
    private static final String TAG = "SurfaceControlRegistry";
    // Special constant for identifying the Transaction#apply() calls
    static final String APPLY = "apply";

    /**
     * An interface for processing the registered SurfaceControls when the threshold is exceeded.
     */
    public interface Reporter {
        /**
         * Called when the set of layers exceeds the max threshold.  This can be called on any
         * thread, and must be handled synchronously.
         */
        void onMaxLayersExceeded(WeakHashMap<SurfaceControl, Long> surfaceControls, int limit,
                PrintWriter pw);
    }

    /**
     * The default implementation of the reporter which logs the existing registered surfaces to
     * logcat.
     */
    private static class DefaultReporter implements Reporter {
        public void onMaxLayersExceeded(WeakHashMap<SurfaceControl, Long> surfaceControls,
                int limit, PrintWriter pw) {
            final long now = SystemClock.elapsedRealtime();
            final ArrayList<Map.Entry<SurfaceControl, Long>> entries = new ArrayList<>();
            for (Map.Entry<SurfaceControl, Long> entry : surfaceControls.entrySet()) {
                entries.add(entry);
            }
            // Sort entries by time registered when dumping
            // TODO: Or should it sort by name?
            entries.sort((o1, o2) -> Long.compare(o1.getValue(), o2.getValue()));
            final int size = Math.min(entries.size(), limit);

            pw.println("SurfaceControlRegistry");
            pw.println("----------------------");
            pw.println("Listing oldest " + size + " of " + surfaceControls.size());
            for (int i = 0; i < size; i++) {
                final Map.Entry<SurfaceControl, Long> entry = entries.get(i);
                final SurfaceControl sc = entry.getKey();
                if (sc == null) {
                    // Just skip if the key has since been removed from the weak hash map
                    continue;
                }

                final long timeRegistered = entry.getValue();
                pw.print("  ");
                pw.print(sc.getName());
                pw.print(" (" + sc.getCallsite() + ")");
                pw.println(" [" + ((now - timeRegistered) / 1000) + "s ago]");
            }
        }
    }

    // The threshold at which to dump information about all the known active SurfaceControls in the
    // process when the number of layers exceeds a certain count.  This should be significantly
    // smaller than the MAX_LAYERS (currently 4096) defined in SurfaceFlinger.h
    private static final int MAX_LAYERS_REPORTING_THRESHOLD = 1024;

    // The threshold at which to reset the dump state.  Needs to be smaller than
    // MAX_LAYERS_REPORTING_THRESHOLD
    private static final int RESET_REPORTING_THRESHOLD = 256;

    // Number of surface controls to dump when the max threshold is exceeded
    private static final int DUMP_LIMIT = 256;

    // An instance of a registry that is a no-op
    private static final SurfaceControlRegistry NO_OP_REGISTRY = new NoOpRegistry();

    // Static lock, must be held for all registry operations
    private static final Object sLock = new Object();

    // The default reporter for printing out the registered surfaces
    private static final DefaultReporter sDefaultReporter = new DefaultReporter();

    // The registry for a given process
    private static volatile SurfaceControlRegistry sProcessRegistry;

    // Whether call stack debugging has been initialized. This is evaluated only once per process
    // instance when the first SurfaceControl.Transaction object is created
    static boolean sCallStackDebuggingInitialized;

    // Whether call stack debugging is currently enabled, ie. whether there is a valid match string
    // for either a specific surface control name or surface control transaction method
    static boolean sCallStackDebuggingEnabled;

    // The name of the surface control to log stack traces for.  Always non-null if
    // sCallStackDebuggingEnabled is true.  Can be combined with the match call.
    private static String sCallStackDebuggingMatchName;

    // The surface control transaction method name to log stack traces for.  Always non-null if
    // sCallStackDebuggingEnabled is true.  Can be combined with the match name.
    private static String sCallStackDebuggingMatchCall;

    // When set, all calls on a SurfaceControl.Transaction will be stored and logged when the
    // transaction is applied.
    static boolean sLogAllTxCallsOnApply;

    // Mapping of the active SurfaceControls to the elapsed time when they were registered
    @GuardedBy("sLock")
    private final WeakHashMap<SurfaceControl, Long> mSurfaceControls;

    // The threshold at which we dump information about the current set of registered surfaces.
    // Once this threshold is reached, we no longer report until the number of layers drops below
    // mResetReportingThreshold to ensure that we don't spam logcat.
    private int mMaxLayersReportingThreshold = MAX_LAYERS_REPORTING_THRESHOLD;
    private int mResetReportingThreshold = RESET_REPORTING_THRESHOLD;

    // Whether the current set of layers has exceeded mMaxLayersReportingThreshold, and we have
    // already reported the set of registered surfaces.
    private boolean mHasReportedExceedingMaxThreshold = false;

    // The handler for when the registry exceeds the max threshold
    private Reporter mReporter = sDefaultReporter;

    private SurfaceControlRegistry() {
        mSurfaceControls = new WeakHashMap<>(256);
    }

    /**
     * Sets the thresholds at which the registry reports errors.
     * @param maxLayersReportingThreshold The max threshold (inclusive)
     * @param resetReportingThreshold The reset threshold (inclusive)
     * @hide
     */
    @VisibleForTesting
    public void setReportingThresholds(int maxLayersReportingThreshold, int resetReportingThreshold,
            Reporter reporter) {
        synchronized (sLock) {
            if (maxLayersReportingThreshold <= 0
                    || resetReportingThreshold >= maxLayersReportingThreshold) {
                throw new IllegalArgumentException("Expected maxLayersReportingThreshold ("
                        + maxLayersReportingThreshold + ") to be > 0 and resetReportingThreshold ("
                        + resetReportingThreshold + ") to be < maxLayersReportingThreshold");
            }
            if (reporter == null) {
                throw new IllegalArgumentException("Expected non-null reporter");
            }
            mMaxLayersReportingThreshold = maxLayersReportingThreshold;
            mResetReportingThreshold = resetReportingThreshold;
            mHasReportedExceedingMaxThreshold = false;
            mReporter = reporter;
        }
    }

    @VisibleForTesting
    public void setCallStackDebuggingParams(String matchName, String matchCall) {
        sCallStackDebuggingMatchName = matchName.toLowerCase();
        sCallStackDebuggingMatchCall = matchCall.toLowerCase();
        sLogAllTxCallsOnApply = sCallStackDebuggingMatchCall.contains("apply");
    }

    /**
     * Creates and initializes the registry for all SurfaceControls in this process. The caller must
     * hold the READ_FRAME_BUFFER permission.
     * @hide
     */
    @RequiresPermission(READ_FRAME_BUFFER)
    @NonNull
    public static void createProcessInstance(Context context) {
        if (context.checkSelfPermission(READ_FRAME_BUFFER) != PERMISSION_GRANTED) {
            throw new SecurityException("Expected caller to hold READ_FRAME_BUFFER");
        }
        synchronized (sLock) {
            if (sProcessRegistry == null) {
                sProcessRegistry = new SurfaceControlRegistry();
            }
        }
    }

    /**
     * Destroys the previously created registry this process.
     * @hide
     */
    public static void destroyProcessInstance() {
        synchronized (sLock) {
            if (sProcessRegistry == null) {
                return;
            }
            sProcessRegistry = null;
        }
    }

    /**
     * Returns the instance of the registry for this process, only non-null if
     * createProcessInstance(Context) was previously called from a valid caller.
     * @hide
     */
    public static SurfaceControlRegistry getProcessInstance() {
        synchronized (sLock) {
            return sProcessRegistry != null ? sProcessRegistry : NO_OP_REGISTRY;
        }
    }

    /**
     * Adds a SurfaceControl to the registry.
     */
    void add(SurfaceControl sc) {
        synchronized (sLock) {
            mSurfaceControls.put(sc, SystemClock.elapsedRealtime());
            if (!mHasReportedExceedingMaxThreshold
                    && mSurfaceControls.size() >= mMaxLayersReportingThreshold) {
                // Dump existing info to logcat for debugging purposes (but don't close the
                // System.out output stream otherwise we can't print to it after this call)
                PrintWriter pw = new PrintWriter(System.out, true /* autoFlush */);
                mReporter.onMaxLayersExceeded(mSurfaceControls, DUMP_LIMIT, pw);
                mHasReportedExceedingMaxThreshold = true;
            }
        }
    }

    /**
     * Removes a SurfaceControl from the registry.
     */
    void remove(SurfaceControl sc) {
        synchronized (sLock) {
            mSurfaceControls.remove(sc);
            if (mHasReportedExceedingMaxThreshold
                    && mSurfaceControls.size() <= mResetReportingThreshold) {
                mHasReportedExceedingMaxThreshold = false;
            }
        }
    }

    /**
     * Returns a hash of this registry and is a function of all the active surface controls. This
     * is useful for testing to determine whether the registry has changed between creating and
     * destroying new SurfaceControls.
     */
    @Override
    public int hashCode() {
        synchronized (sLock) {
            // Return a hash of the surface controls
            return mSurfaceControls.keySet().hashCode();
        }
    }

    /**
     * Initializes global call stack debugging if this is a debug build and a filter is specified.
     * This is a no-op if
     *
     * Usage:
     *   adb shell setprop persist.wm.debug.sc.tx.log_match_call <call or \"\" to unset>
     *   adb shell setprop persist.wm.debug.sc.tx.log_match_name <name or \"\" to unset>
     *   adb reboot
     */
    final static void initializeCallStackDebugging() {
        if (sCallStackDebuggingInitialized || !Build.IS_DEBUGGABLE) {
            // Return early if already initialized or this is not a debug build
            return;
        }

        sCallStackDebuggingInitialized = true;
        updateCallStackDebuggingParams();
        if (sCallStackDebuggingEnabled) {
            Log.d(TAG, "Enabling transaction call stack debugging:"
                    + " matchCall=" + sCallStackDebuggingMatchCall
                    + " matchName=" + sCallStackDebuggingMatchName
                    + " logCallsWithApply=" + sLogAllTxCallsOnApply);
        }
    }

    /**
     * Dumps the callstack if it matches the global debug properties. Caller should first verify
     * {@link #sCallStackDebuggingEnabled} is true.
     *
     * @param call the name of the call
     * @param tx (optional) the transaction associated with this call
     * @param sc the affected surface
     * @param details additional details to print with the stack track
     */
    final void checkCallStackDebugging(@NonNull String call,
            @Nullable SurfaceControl.Transaction tx, @Nullable SurfaceControl sc,
            @Nullable String details) {

        if (sCallStackDebuggingInitialized && sCallStackDebuggingEnabled) {
            updateCallStackDebuggingParams();
        }

        if (!sCallStackDebuggingEnabled) {
            return;
        }

        final String txMsg = tx != null ? "tx=" + tx.getId() + " " : "";
        final String scMsg = sc != null ? " sc=" + sc.getName() + "" : "";
        final String msg = details != null
                ? call + " (" + txMsg + scMsg + ") " + details
                : call + " (" + txMsg + scMsg + ")";
        if (sLogAllTxCallsOnApply && tx != null) {
            if (call == APPLY) {
                // Log the apply and dump the calls on that transaction
                Log.e(TAG, msg, new Throwable());
                for (int i = 0; i < tx.mCalls.size(); i++) {
                    Log.d(TAG, "        " + tx.mCalls.get(i));
                }
            } else if (matchesForCallStackDebugging(sc != null ? sc.getName() : null, call)) {
                // Otherwise log this call to the transaction if it matches the tracked calls
                Log.e(TAG, msg, new Throwable());
                tx.mCalls.add(msg);
            }
        } else {
            // Log this call if it matches the tracked calls
            if (!matchesForCallStackDebugging(sc != null ? sc.getName() : null, call)) {
                return;
            }
            Log.e(TAG, msg, new Throwable());
        }
    }

    /**
     * Updates the call stack debugging params from the system properties.
     */
    private static void updateCallStackDebuggingParams() {
        sCallStackDebuggingMatchCall =
                SystemProperties.get("persist.wm.debug.sc.tx.log_match_call", null)
                        .toLowerCase();
        sCallStackDebuggingMatchName =
                SystemProperties.get("persist.wm.debug.sc.tx.log_match_name", null)
                        .toLowerCase();
        sLogAllTxCallsOnApply = sCallStackDebuggingMatchCall.contains("apply");
        // Only enable stack debugging if any of the match filters are set
        sCallStackDebuggingEnabled = !sCallStackDebuggingMatchCall.isEmpty()
                || !sCallStackDebuggingMatchName.isEmpty();
    }

    /**
     * Tests whether the given surface control name/method call matches the filters set for the
     * call stack debugging.
     */
    @VisibleForTesting
    public final boolean matchesForCallStackDebugging(@Nullable String name, @NonNull String call) {
        final boolean matchCall = !sCallStackDebuggingMatchCall.isEmpty();
        if (matchCall && !sCallStackDebuggingMatchCall.contains(call.toLowerCase())) {
            // Skip if target call doesn't match requested caller
            return false;
        }
        final boolean matchName = !sCallStackDebuggingMatchName.isEmpty();
        if (!matchName) {
            return true;
        }
        if (name == null) {
            return false;
        }
        return sCallStackDebuggingMatchName.contains(name.toLowerCase()) ||
                        name.toLowerCase().contains(sCallStackDebuggingMatchName);
    }

    /**
     * Returns whether call stack debugging is enabled for this process.
     */
    final static boolean isCallStackDebuggingEnabled() {
        return sCallStackDebuggingEnabled;
    }

    /**
     * Forces the gc and finalizers to run, used prior to dumping to ensure we only dump strongly
     * referenced surface controls.
     */
    private static void runGcAndFinalizers() {
        long t = SystemClock.elapsedRealtime();
        GcUtils.runGcAndFinalizersSync();
        Log.i(TAG, "Ran gc and finalizers (" + (SystemClock.elapsedRealtime() - t) + "ms)");
    }

    /**
     * Dumps information about the set of SurfaceControls in the registry.
     *
     * @param limit the number of layers to report
     * @param runGc whether to run the GC and finalizers before dumping
     * @hide
     */
    public static void dump(int limit, boolean runGc, PrintWriter pw) {
        if (runGc) {
            // This needs to run outside the lock since finalization isn't synchronous
            runGcAndFinalizers();
        }
        synchronized (sLock) {
            if (sProcessRegistry != null) {
                sDefaultReporter.onMaxLayersExceeded(sProcessRegistry.mSurfaceControls, limit, pw);
                pw.println("sCallStackDebuggingInitialized=" + sCallStackDebuggingInitialized);
                pw.println("sCallStackDebuggingEnabled=" + sCallStackDebuggingEnabled);
                pw.println("sCallStackDebuggingMatchName=" + sCallStackDebuggingMatchName);
                pw.println("sCallStackDebuggingMatchCall=" + sCallStackDebuggingMatchCall);
                pw.println("sLogAllTxCallsOnApply=" + sLogAllTxCallsOnApply);
            }
        }
    }

    /**
     * A no-op implementation of the registry.
     */
    private static class NoOpRegistry extends SurfaceControlRegistry {

        @Override
        public void setReportingThresholds(int maxLayersReportingThreshold,
                int resetReportingThreshold, Reporter reporter) {}

        @Override
        void add(SurfaceControl sc) {}

        @Override
        void remove(SurfaceControl sc) {}
    }
}
