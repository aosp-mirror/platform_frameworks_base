/*
 * Copyright (C) 2006 The Android Open Source Project
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


/**
 * Core timekeeping facilities.
 *
 * <p> Three different clocks are available, and they should not be confused:
 *
 * <ul>
 *     <li> <p> {@link System#currentTimeMillis System.currentTimeMillis()}
 *     is the standard "wall" clock (time and date) expressing milliseconds
 *     since the epoch.  The wall clock can be set by the user or the phone
 *     network (see {@link #setCurrentTimeMillis}), so the time may jump
 *     backwards or forwards unpredictably.  This clock should only be used
 *     when correspondence with real-world dates and times is important, such
 *     as in a calendar or alarm clock application.  Interval or elapsed
 *     time measurements should use a different clock.  If you are using
 *     System.currentTimeMillis(), consider listening to the
 *     {@link android.content.Intent#ACTION_TIME_TICK ACTION_TIME_TICK},
 *     {@link android.content.Intent#ACTION_TIME_CHANGED ACTION_TIME_CHANGED}
 *     and {@link android.content.Intent#ACTION_TIMEZONE_CHANGED
 *     ACTION_TIMEZONE_CHANGED} {@link android.content.Intent Intent}
 *     broadcasts to find out when the time changes.
 *
 *     <li> <p> {@link #uptimeMillis} is counted in milliseconds since the
 *     system was booted.  This clock stops when the system enters deep
 *     sleep (CPU off, display dark, device waiting for external input),
 *     but is not affected by clock scaling, idle, or other power saving
 *     mechanisms.  This is the basis for most interval timing
 *     such as {@link Thread#sleep(long) Thread.sleep(millls)},
 *     {@link Object#wait(long) Object.wait(millis)}, and
 *     {@link System#nanoTime System.nanoTime()}.  This clock is guaranteed
 *     to be monotonic, and is suitable for interval timing when the
 *     interval does not span device sleep.  Most methods that accept a
 *     timestamp value currently expect the {@link #uptimeMillis} clock.
 *
 *     <li> <p> {@link #elapsedRealtime} and {@link #elapsedRealtimeNanos}
 *     return the time since the system was booted, and include deep sleep.
 *     This clock is guaranteed to be monotonic, and continues to tick even
 *     when the CPU is in power saving modes, so is the recommend basis
 *     for general purpose interval timing.
 *
 * </ul>
 *
 * There are several mechanisms for controlling the timing of events:
 *
 * <ul>
 *     <li> <p> Standard functions like {@link Thread#sleep(long)
 *     Thread.sleep(millis)} and {@link Object#wait(long) Object.wait(millis)}
 *     are always available.  These functions use the {@link #uptimeMillis}
 *     clock; if the device enters sleep, the remainder of the time will be
 *     postponed until the device wakes up.  These synchronous functions may
 *     be interrupted with {@link Thread#interrupt Thread.interrupt()}, and
 *     you must handle {@link InterruptedException}.
 *
 *     <li> <p> {@link #sleep SystemClock.sleep(millis)} is a utility function
 *     very similar to {@link Thread#sleep(long) Thread.sleep(millis)}, but it
 *     ignores {@link InterruptedException}.  Use this function for delays if
 *     you do not use {@link Thread#interrupt Thread.interrupt()}, as it will
 *     preserve the interrupted state of the thread.
 *
 *     <li> <p> The {@link android.os.Handler} class can schedule asynchronous
 *     callbacks at an absolute or relative time.  Handler objects also use the
 *     {@link #uptimeMillis} clock, and require an {@link android.os.Looper
 *     event loop} (normally present in any GUI application).
 *
 *     <li> <p> The {@link android.app.AlarmManager} can trigger one-time or
 *     recurring events which occur even when the device is in deep sleep
 *     or your application is not running.  Events may be scheduled with your
 *     choice of {@link java.lang.System#currentTimeMillis} (RTC) or
 *     {@link #elapsedRealtime} (ELAPSED_REALTIME), and cause an
 *     {@link android.content.Intent} broadcast when they occur.
 * </ul>
 */
public final class SystemClock {
    /**
     * This class is uninstantiable.
     */
    private SystemClock() {
        // This space intentionally left blank.
    }

    /**
     * Waits a given number of milliseconds (of uptimeMillis) before returning.
     * Similar to {@link java.lang.Thread#sleep(long)}, but does not throw
     * {@link InterruptedException}; {@link Thread#interrupt()} events are
     * deferred until the next interruptible operation.  Does not return until
     * at least the specified number of milliseconds has elapsed.
     *
     * @param ms to sleep before returning, in milliseconds of uptime.
     */
    public static void sleep(long ms)
    {
        long start = uptimeMillis();
        long duration = ms;
        boolean interrupted = false;
        do {
            try {
                Thread.sleep(duration);
            }
            catch (InterruptedException e) {
                interrupted = true;
            }
            duration = start + ms - uptimeMillis();
        } while (duration > 0);
        
        if (interrupted) {
            // Important: we don't want to quietly eat an interrupt() event,
            // so we make sure to re-interrupt the thread so that the next
            // call to Thread.sleep() or Object.wait() will be interrupted.
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Sets the current wall time, in milliseconds.  Requires the calling
     * process to have appropriate permissions.
     *
     * @return if the clock was successfully set to the specified time.
     */
    native public static boolean setCurrentTimeMillis(long millis);

    /**
     * Returns milliseconds since boot, not counting time spent in deep sleep.
     * <b>Note:</b> This value may get reset occasionally (before it would
     * otherwise wrap around).
     *
     * @return milliseconds of non-sleep uptime since boot.
     */
    native public static long uptimeMillis();

    /**
     * Returns milliseconds since boot, including time spent in sleep.
     *
     * @return elapsed milliseconds since boot.
     */
    native public static long elapsedRealtime();

    /**
     * Returns nanoseconds since boot, including time spent in sleep.
     *
     * @return elapsed nanoseconds since boot.
     */
    public static native long elapsedRealtimeNanos();

    /**
     * Returns milliseconds running in the current thread.
     * 
     * @return elapsed milliseconds in the thread
     */
    public static native long currentThreadTimeMillis();

    /**
     * Returns microseconds running in the current thread.
     * 
     * @return elapsed microseconds in the thread
     * 
     * @hide
     */
    public static native long currentThreadTimeMicro();

    /**
     * Returns current wall time in  microseconds.
     * 
     * @return elapsed microseconds in wall time
     * 
     * @hide
     */
    public static native long currentTimeMicro();
}
