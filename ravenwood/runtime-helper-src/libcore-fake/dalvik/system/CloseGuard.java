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

package dalvik.system;

/**
 * A no-op copy of libcore/dalvik/src/main/java/dalvik/system/CloseGuard.java
 */
public final class CloseGuard {

    /**
     * Returns a CloseGuard instance. {@code #open(String)} can be used to set
     * up the instance to warn on failure to close.
     *
     * @return {@link CloseGuard} instance.
     *
     * @hide
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
     *
     * @param enabled whether stack capture and tracking is enabled.
     *
     * @hide
     */
    public static void setEnabled(boolean enabled) {
    }

    /**
     * True if CloseGuard stack capture and tracking are enabled.
     *
     * @hide
     */
    public static boolean isEnabled() {
        return false;
    }

    /**
     * Used to replace default Reporter used to warn of CloseGuard
     * violations when stack tracking is enabled. Must be non-null.
     *
     * @param rep replacement for default Reporter.
     *
     * @hide
     */
    public static void setReporter(Reporter rep) {
        if (rep == null) {
            throw new NullPointerException("reporter == null");
        }
    }

    /**
     * Returns non-null CloseGuard.Reporter.
     *
     * @return CloseGuard's Reporter.
     *
     * @hide
     */
    public static Reporter getReporter() {
        return null;
    }

    /**
     * Sets the {@link Tracker} that is notified when resources are allocated and released.
     * The Tracker is invoked only if CloseGuard {@link #isEnabled()} held when {@link #open()}
     * was called. A null argument disables tracking.
     *
     * <p>This is only intended for use by {@code dalvik.system.CloseGuardSupport} class and so
     * MUST NOT be used for any other purposes.
     *
     * @hide
     */
    public static void setTracker(Tracker tracker) {
    }

    /**
     * Returns {@link #setTracker(Tracker) last Tracker that was set}, or null to indicate
     * there is none.
     *
     * <p>This is only intended for use by {@code dalvik.system.CloseGuardSupport} class and so
     * MUST NOT be used for any other purposes.
     *
     * @hide
     */
    public static Tracker getTracker() {
        return null;
    }

    private CloseGuard() {}

    /**
     * {@code open} initializes the instance with a warning that the caller
     * should have explicitly called the {@code closer} method instead of
     * relying on finalization.
     *
     * @param closer non-null name of explicit termination method. Printed by warnIfOpen.
     * @throws NullPointerException if closer is null.
     *
     * @hide
     */
    public void open(String closer) {
        openWithCallSite(closer, null /* callsite */);
    }

    /**
     * Like {@link #open(String)}, but with explicit callsite string being passed in for better
     * performance.
     * <p>
     * This only has better performance than {@link #open(String)} if {@link #isEnabled()} returns {@code true}, which
     * usually shouldn't happen on release builds.
     *
     * @param closer Non-null name of explicit termination method. Printed by warnIfOpen.
     * @param callsite Non-null string uniquely identifying the callsite.
     *
     * @hide
     */
    public void openWithCallSite(String closer, String callsite) {
    }

    // We keep either an allocation stack containing the closer String or, when
    // in disabled state, just the closer String.
    // We keep them in a single field only to minimize overhead.
    private Object /* String or Throwable */ closerNameOrAllocationInfo;

    /**
     * Marks this CloseGuard instance as closed to avoid warnings on
     * finalization.
     *
     * @hide
     */
    public void close() {
    }

    /**
     * Logs a warning if the caller did not properly cleanup by calling an
     * explicit close method before finalization. If CloseGuard was enabled
     * when the CloseGuard was created, passes the stacktrace associated with
     * the allocation to the current reporter. If it was not enabled, it just
     * directly logs a brief message.
     *
     * @hide
     */
    public void warnIfOpen() {
    }


    /**
     * Interface to allow customization of tracking behaviour.
     *
     * <p>This is only intended for use by {@code dalvik.system.CloseGuardSupport} class and so
     * MUST NOT be used for any other purposes.
     *
     * @hide
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
        /**
         *
         * @hide
         */
        void report(String message, Throwable allocationSite);

        /**
         *
         * @hide
         */
        default void report(String message) {}
    }
}
