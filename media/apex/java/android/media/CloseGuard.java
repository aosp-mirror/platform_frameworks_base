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

package android.media;

import android.util.Log;

/**
 * Note: This file is copied from dalvik.system package with the following modifications:
 *       - Remove @CorePlatformApi, @IntraCoreApi and @UnsupportedAppUsage annotations.
 *       - Replace System.logW() with android.util.Log.w().
 *       This file should be used only within media mainline module.
 * TODO: Remove this file and use dalvik.system.CloseGuard once
 *       @CorePlatformApi becomes stable or we have a replacement in SDK API.
 *       b/120419300
 *
 * CloseGuard is a mechanism for flagging implicit finalizer cleanup of
 * resources that should have been cleaned up by explicit close
 * methods (aka "explicit termination methods" in Effective Java).
 * <p>
 * A simple example: <pre>   {@code
 *   class Foo {
 *
 *       {@literal @}ReachabilitySensitive
 *       private final CloseGuard guard = CloseGuard.get();
 *
 *       ...
 *
 *       public Foo() {
 *           ...;
 *           guard.open("cleanup");
 *       }
 *
 *       public void cleanup() {
 *          guard.close();
 *          ...;
 *       }
 *
 *       protected void finalize() throws Throwable {
 *           try {
 *               // Note that guard could be null if the constructor threw.
 *               if (guard != null) {
 *                   guard.warnIfOpen();
 *               }
 *               cleanup();
 *           } finally {
 *               super.finalize();
 *           }
 *       }
 *   }
 * }</pre>
 *
 * In usage where the resource to be explicitly cleaned up is
 * allocated after object construction, CloseGuard protection can
 * be deferred. For example: <pre>   {@code
 *   class Bar {
 *
 *       {@literal @}ReachabilitySensitive
 *       private final CloseGuard guard = CloseGuard.get();
 *
 *       ...
 *
 *       public Bar() {
 *           ...;
 *       }
 *
 *       public void connect() {
 *          ...;
 *          guard.open("cleanup");
 *       }
 *
 *       public void cleanup() {
 *          guard.close();
 *          ...;
 *       }
 *
 *       protected void finalize() throws Throwable {
 *           try {
 *               // Note that guard could be null if the constructor threw.
 *               if (guard != null) {
 *                   guard.warnIfOpen();
 *               }
 *               cleanup();
 *           } finally {
 *               super.finalize();
 *           }
 *       }
 *   }
 * }</pre>
 *
 * When used in a constructor, calls to {@code open} should occur at
 * the end of the constructor since an exception that would cause
 * abrupt termination of the constructor will mean that the user will
 * not have a reference to the object to cleanup explicitly. When used
 * in a method, the call to {@code open} should occur just after
 * resource acquisition.
 *
 * The @ReachabilitySensitive annotation ensures that finalize() cannot be
 * called during the explicit call to cleanup(), prior to the guard.close call.
 * There is an extremely small chance that, for code that neglects to call
 * cleanup(), finalize() and thus cleanup() will be called while a method on
 * the object is still active, but the "this" reference is no longer required.
 * If missing cleanup() calls are expected, additional @ReachabilitySensitive
 * annotations or reachabilityFence() calls may be required.
 *
 * @hide
 */
final class CloseGuard {

    /**
     * True if collection of call-site information (the expensive operation
     * here)  and tracking via a Tracker (see below) are enabled.
     * Enabled by default so we can diagnose issues early in VM startup.
     * Note, however, that Android disables this early in its startup,
     * but enables it with DropBoxing for system apps on debug builds.
     */
    private static volatile boolean stackAndTrackingEnabled = true;

    /**
     * Hook for customizing how CloseGuard issues are reported.
     * Bypassed if stackAndTrackingEnabled was false when open was called.
     */
    private static volatile Reporter reporter = new DefaultReporter();

    /**
     * Hook for customizing how CloseGuard issues are tracked.
     */
    private static volatile Tracker currentTracker = null; // Disabled by default.

    /**
     * Returns a CloseGuard instance. {@code #open(String)} can be used to set
     * up the instance to warn on failure to close.
     */
    public static CloseGuard get() {
        return new CloseGuard();
    }

    /**
     * Enables/disables stack capture and tracking. A call stack is captured
     * during open(), and open/close events are reported to the Tracker, only
     * if enabled is true. If a stack trace was captured, the {@link
     * #getReporter() reporter} is informed of unclosed resources; otherwise a
     * one-line warning is logged.
     */
    public static void setEnabled(boolean enabled) {
        CloseGuard.stackAndTrackingEnabled = enabled;
    }

    /**
     * True if CloseGuard stack capture and tracking are enabled.
     */
    public static boolean isEnabled() {
        return stackAndTrackingEnabled;
    }

    /**
     * Used to replace default Reporter used to warn of CloseGuard
     * violations when stack tracking is enabled. Must be non-null.
     */
    public static void setReporter(Reporter rep) {
        if (rep == null) {
            throw new NullPointerException("reporter == null");
        }
        CloseGuard.reporter = rep;
    }

    /**
     * Returns non-null CloseGuard.Reporter.
     */
    public static Reporter getReporter() {
        return reporter;
    }

    /**
     * Sets the {@link Tracker} that is notified when resources are allocated and released.
     * The Tracker is invoked only if CloseGuard {@link #isEnabled()} held when {@link #open()}
     * was called. A null argument disables tracking.
     *
     * <p>This is only intended for use by {@code dalvik.system.CloseGuardSupport} class and so
     * MUST NOT be used for any other purposes.
     */
    public static void setTracker(Tracker tracker) {
        currentTracker = tracker;
    }

    /**
     * Returns {@link #setTracker(Tracker) last Tracker that was set}, or null to indicate
     * there is none.
     *
     * <p>This is only intended for use by {@code dalvik.system.CloseGuardSupport} class and so
     * MUST NOT be used for any other purposes.
     */
    public static Tracker getTracker() {
        return currentTracker;
    }

    private CloseGuard() {}

    /**
     * {@code open} initializes the instance with a warning that the caller
     * should have explicitly called the {@code closer} method instead of
     * relying on finalization.
     *
     * @param closer non-null name of explicit termination method. Printed by warnIfOpen.
     * @throws NullPointerException if closer is null.
     */
    public void open(String closer) {
        // always perform the check for valid API usage...
        if (closer == null) {
            throw new NullPointerException("closer == null");
        }
        // ...but avoid allocating an allocation stack if "disabled"
        if (!stackAndTrackingEnabled) {
            closerNameOrAllocationInfo = closer;
            return;
        }
        String message = "Explicit termination method '" + closer + "' not called";
        Throwable stack = new Throwable(message);
        closerNameOrAllocationInfo = stack;
        Tracker tracker = currentTracker;
        if (tracker != null) {
            tracker.open(stack);
        }
    }

    // We keep either an allocation stack containing the closer String or, when
    // in disabled state, just the closer String.
    // We keep them in a single field only to minimize overhead.
    private Object /* String or Throwable */ closerNameOrAllocationInfo;

    /**
     * Marks this CloseGuard instance as closed to avoid warnings on
     * finalization.
     */
    public void close() {
        Tracker tracker = currentTracker;
        if (tracker != null && closerNameOrAllocationInfo instanceof Throwable) {
            // Invoke tracker on close only if we invoked it on open. Tracker may have changed.
            tracker.close((Throwable) closerNameOrAllocationInfo);
        }
        closerNameOrAllocationInfo = null;
    }

    /**
     * Logs a warning if the caller did not properly cleanup by calling an
     * explicit close method before finalization. If CloseGuard was enabled
     * when the CloseGuard was created, passes the stacktrace associated with
     * the allocation to the current reporter. If it was not enabled, it just
     * directly logs a brief message.
     */
    public void warnIfOpen() {
        if (closerNameOrAllocationInfo != null) {
            if (closerNameOrAllocationInfo instanceof String) {
                Log.w("CloseGuard", "A resource failed to call "
                        + (String) closerNameOrAllocationInfo + ". ");
            } else {
                String message =
                        "A resource was acquired at attached stack trace but never released. ";
                message += "See java.io.Closeable for information on avoiding resource leaks.";
                Throwable stack = (Throwable) closerNameOrAllocationInfo;
                reporter.report(message, stack);
            }
        }
    }

    /**
     * Interface to allow customization of tracking behaviour.
     *
     * <p>This is only intended for use by {@code dalvik.system.CloseGuardSupport} class and so
     * MUST NOT be used for any other purposes.
     */
    public interface Tracker {
        void open(Throwable allocationSite);
        void close(Throwable allocationSite);
    }

    /**
     * Interface to allow customization of reporting behavior.
     * @hide
     */
    public interface Reporter {
        void report(String message, Throwable allocationSite);
    }

    /**
     * Default Reporter which reports CloseGuard violations to the log.
     */
    private static final class DefaultReporter implements Reporter {
        private DefaultReporter() {}

        @Override public void report (String message, Throwable allocationSite) {
            Log.w("CloseGuard", message, allocationSite);
        }
    }
}
