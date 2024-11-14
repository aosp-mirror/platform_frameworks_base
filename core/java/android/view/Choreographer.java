/*
 * Copyright (C) 2011 The Android Open Source Project
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

import static android.view.flags.Flags.FLAG_EXPECTED_PRESENTATION_TIME_API;
import static android.view.DisplayEventReceiver.VSYNC_SOURCE_APP;
import static android.view.DisplayEventReceiver.VSYNC_SOURCE_SURFACE_FLINGER;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.TestApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.graphics.FrameInfo;
import android.graphics.Insets;
import android.hardware.display.DisplayManagerGlobal;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.util.Log;
import android.util.TimeUtils;
import android.view.animation.AnimationUtils;

import java.io.PrintWriter;
import java.util.Locale;

/**
 * Coordinates the timing of animations, input and drawing.
 * <p>
 * The choreographer receives timing pulses (such as vertical synchronization)
 * from the display subsystem then schedules work to occur as part of rendering
 * the next display frame.
 * </p><p>
 * Applications typically interact with the choreographer indirectly using
 * higher level abstractions in the animation framework or the view hierarchy.
 * Here are some examples of things you can do using the higher-level APIs.
 * </p>
 * <ul>
 * <li>To post an animation to be processed on a regular time basis synchronized with
 * display frame rendering, use {@link android.animation.ValueAnimator#start}.</li>
 * <li>To post a {@link Runnable} to be invoked once at the beginning of the next display
 * frame, use {@link View#postOnAnimation}.</li>
 * <li>To post a {@link Runnable} to be invoked once at the beginning of the next display
 * frame after a delay, use {@link View#postOnAnimationDelayed}.</li>
 * <li>To post a call to {@link View#invalidate()} to occur once at the beginning of the
 * next display frame, use {@link View#postInvalidateOnAnimation()} or
 * {@link View#postInvalidateOnAnimation(int, int, int, int)}.</li>
 * <li>To ensure that the contents of a {@link View} scroll smoothly and are drawn in
 * sync with display frame rendering, do nothing.  This already happens automatically.
 * {@link View#onDraw} will be called at the appropriate time.</li>
 * </ul>
 * <p>
 * However, there are a few cases where you might want to use the functions of the
 * choreographer directly in your application.  Here are some examples.
 * </p>
 * <ul>
 * <li>If your application does its rendering in a different thread, possibly using GL,
 * or does not use the animation framework or view hierarchy at all
 * and you want to ensure that it is appropriately synchronized with the display, then use
 * {@link Choreographer#postFrameCallback}.</li>
 * <li>... and that's about it.</li>
 * </ul>
 * <p>
 * Each {@link Looper} thread has its own choreographer.  Other threads can
 * post callbacks to run on the choreographer but they will run on the {@link Looper}
 * to which the choreographer belongs.
 * </p>
 */
public final class Choreographer {
    private static final String TAG = "Choreographer";

    // Prints debug messages about jank which was detected (low volume).
    private static final boolean DEBUG_JANK = false;

    // Prints debug messages about every frame and callback registered (high volume).
    private static final boolean DEBUG_FRAMES = false;

    // The default amount of time in ms between animation frames.
    // When vsync is not enabled, we want to have some idea of how long we should
    // wait before posting the next animation message.  It is important that the
    // default value be less than the true inter-frame delay on all devices to avoid
    // situations where we might skip frames by waiting too long (we must compensate
    // for jitter and hardware variations).  Regardless of this value, the animation
    // and display loop is ultimately rate-limited by how fast new graphics buffers can
    // be dequeued.
    private static final long DEFAULT_FRAME_DELAY = 10;

    // The number of milliseconds between animation frames.
    private static volatile long sFrameDelay = DEFAULT_FRAME_DELAY;

    // Thread local storage for the choreographer.
    private static final ThreadLocal<Choreographer> sThreadInstance =
            new ThreadLocal<Choreographer>() {
        @Override
        protected Choreographer initialValue() {
            Looper looper = Looper.myLooper();
            if (looper == null) {
                throw new IllegalStateException("The current thread must have a looper!");
            }
            Choreographer choreographer = new Choreographer(looper, VSYNC_SOURCE_APP);
            if (looper == Looper.getMainLooper()) {
                mMainInstance = choreographer;
            }
            return choreographer;
        }
    };

    private static volatile Choreographer mMainInstance;

    // Thread local storage for the SF choreographer.
    private static final ThreadLocal<Choreographer> sSfThreadInstance =
            new ThreadLocal<Choreographer>() {
                @Override
                protected Choreographer initialValue() {
                    Looper looper = Looper.myLooper();
                    if (looper == null) {
                        throw new IllegalStateException("The current thread must have a looper!");
                    }
                    return new Choreographer(looper, VSYNC_SOURCE_SURFACE_FLINGER);
                }
            };

    // Enable/disable vsync for animations and drawing.
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 123769497)
    private static final boolean USE_VSYNC = SystemProperties.getBoolean(
            "debug.choreographer.vsync", true);

    // Enable/disable using the frame time instead of returning now.
    private static final boolean USE_FRAME_TIME = SystemProperties.getBoolean(
            "debug.choreographer.frametime", true);

    // Set a limit to warn about skipped frames.
    // Skipped frames imply jank.
    private static final int SKIPPED_FRAME_WARNING_LIMIT = SystemProperties.getInt(
            "debug.choreographer.skipwarning", 30);

    private static final int MSG_DO_FRAME = 0;
    private static final int MSG_DO_SCHEDULE_VSYNC = 1;
    private static final int MSG_DO_SCHEDULE_CALLBACK = 2;

    // All frame callbacks posted by applications have this token or VSYNC_CALLBACK_TOKEN.
    private static final Object FRAME_CALLBACK_TOKEN = new Object() {
        public String toString() { return "FRAME_CALLBACK_TOKEN"; }
    };
    private static final Object VSYNC_CALLBACK_TOKEN = new Object() {
        public String toString() {
            return "VSYNC_CALLBACK_TOKEN";
        }
    };

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private final Object mLock = new Object();

    private final Looper mLooper;
    private final FrameHandler mHandler;

    // The display event receiver can only be accessed by the looper thread to which
    // it is attached.  We take care to ensure that we post message to the looper
    // if appropriate when interacting with the display event receiver.
    @UnsupportedAppUsage
    private final FrameDisplayEventReceiver mDisplayEventReceiver;

    private CallbackRecord mCallbackPool;

    @UnsupportedAppUsage
    private final CallbackQueue[] mCallbackQueues;

    private boolean mFrameScheduled;
    private boolean mCallbacksRunning;
    @UnsupportedAppUsage
    private long mLastFrameTimeNanos;

    // Keeps track of the last scheduled frame time without additional offsets
    // added from buffer stuffing recovery. Used to compare timing of vsyncs to
    // determine idle state.
    private long mLastNoOffsetFrameTimeNanos;

    /** DO NOT USE since this will not updated when screen refresh changes. */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R,
            publicAlternatives = "Use {@link android.view.Display#getRefreshRate} instead")
    @Deprecated
    private long mFrameIntervalNanos;
    private long mLastFrameIntervalNanos;

    private boolean mDebugPrintNextFrameTimeDelta;
    private int mFPSDivisor = 1;
    private final DisplayEventReceiver.VsyncEventData mLastVsyncEventData =
            new DisplayEventReceiver.VsyncEventData();
    private final FrameData mFrameData = new FrameData();
    private volatile boolean mInDoFrameCallback = false;

    private static class BufferStuffingData {
        enum RecoveryAction {
            // No recovery
            NONE,
            // Recovery has started, adds a negative offset
            OFFSET,
            // Recovery has started, delays a frame to return buffer count
            // back toward threshold.
            DELAY_FRAME
        }
        // The maximum number of times frames will be delayed per buffer stuffing event.
        // Since buffer stuffing can persist for several consecutive frames following the
        // initial missed frame, we want to adjust the timeline with enough frame delays and
        // offsets to return the queued buffer count back to threshold.
        public static final int MAX_FRAME_DELAYS = 3;

        // Whether buffer stuffing recovery has begun. Recovery can only end
        // when events are idle.
        public boolean isRecovering = false;

        // The number of frames delayed so far during recovery. Used to compare with
        // MAX_FRAME_DELAYS to safeguard against excessive frame delays during recovery.
        // Also used as unique cookie for tracing.
        public int numberFrameDelays = 0;

        // The number of additional frame delays scheduled during recovery to wait for the next
        // vsync. These are scheduled when frame times appear to go backward or frames are
        // being skipped due to FPSDivisor.
        public int numberWaitsForNextVsync = 0;

        /**
         * After buffer stuffing recovery has ended with a detected idle state, the
         * recovery data trackers can be reset in preparation for any future
         * stuffing events.
         */
        public void reset() {
            isRecovering = false;
            numberFrameDelays = 0;
            numberWaitsForNextVsync = 0;
        }
    }

    private final BufferStuffingData mBufferStuffingData = new BufferStuffingData();

    /**
     * Contains information about the current frame for jank-tracking,
     * mainly timings of key events along with a bit of metadata about
     * view tree state
     *
     * TODO: Is there a better home for this? Currently Choreographer
     * is the only one with CALLBACK_ANIMATION start time, hence why this
     * resides here.
     *
     * @hide
     */
    FrameInfo mFrameInfo = new FrameInfo();

    /**
     * Must be kept in sync with CALLBACK_* ints below, used to index into this array.
     * @hide
     */
    private static final String[] CALLBACK_TRACE_TITLES = {
            "input", "animation", "insets_animation", "traversal", "commit"
    };

    /**
     * Callback type: Input callback.  Runs first.
     * @hide
     */
    public static final int CALLBACK_INPUT = 0;

    /**
     * Callback type: Animation callback.  Runs before {@link #CALLBACK_INSETS_ANIMATION}.
     * @hide
     */
    @TestApi
    public static final int CALLBACK_ANIMATION = 1;

    /**
     * Callback type: Animation callback to handle inset updates. This is separate from
     * {@link #CALLBACK_ANIMATION} as we need to "gather" all inset animation updates via
     * {@link WindowInsetsAnimationController#setInsetsAndAlpha(Insets, float, float)} for multiple
     * ongoing animations but then update the whole view system with a single callback to
     * {@link View#dispatchWindowInsetsAnimationProgress} that contains all the combined updated
     * insets.
     * <p>
     * Both input and animation may change insets, so we need to run this after these callbacks, but
     * before traversals.
     * <p>
     * Runs before traversals.
     * @hide
     */
    public static final int CALLBACK_INSETS_ANIMATION = 2;

    /**
     * Callback type: Traversal callback.  Handles layout and draw.  Runs
     * after all other asynchronous messages have been handled.
     * @hide
     */
    public static final int CALLBACK_TRAVERSAL = 3;

    /**
     * Callback type: Commit callback.  Handles post-draw operations for the frame.
     * Runs after traversal completes.  The {@link #getFrameTime() frame time} reported
     * during this callback may be updated to reflect delays that occurred while
     * traversals were in progress in case heavy layout operations caused some frames
     * to be skipped.  The frame time reported during this callback provides a better
     * estimate of the start time of the frame in which animations (and other updates
     * to the view hierarchy state) actually took effect.
     * @hide
     */
    public static final int CALLBACK_COMMIT = 4;

    private static final int CALLBACK_LAST = CALLBACK_COMMIT;

    private Choreographer(Looper looper, int vsyncSource) {
        this(looper, vsyncSource, /* layerHandle */ 0L);
    }

    private Choreographer(Looper looper, int vsyncSource, long layerHandle) {
        mLooper = looper;
        mHandler = new FrameHandler(looper);
        mDisplayEventReceiver = USE_VSYNC
                ? new FrameDisplayEventReceiver(looper, vsyncSource, layerHandle)
                : null;
        mLastFrameTimeNanos = Long.MIN_VALUE;

        mFrameIntervalNanos = (long)(1000000000 / getRefreshRate());

        mCallbackQueues = new CallbackQueue[CALLBACK_LAST + 1];
        for (int i = 0; i <= CALLBACK_LAST; i++) {
            mCallbackQueues[i] = new CallbackQueue();
        }
        // b/68769804: For low FPS experiments.
        setFPSDivisor(SystemProperties.getInt(ThreadedRenderer.DEBUG_FPS_DIVISOR, 1));
    }

    private static float getRefreshRate() {
        DisplayInfo di = DisplayManagerGlobal.getInstance().getDisplayInfo(
                Display.DEFAULT_DISPLAY);
        return di.getRefreshRate();
    }

    /**
     * Gets the choreographer for the calling thread.  Must be called from
     * a thread that already has a {@link android.os.Looper} associated with it.
     *
     * @return The choreographer for this thread.
     * @throws IllegalStateException if the thread does not have a looper.
     */
    public static Choreographer getInstance() {
        return sThreadInstance.get();
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public static Choreographer getSfInstance() {
        return sSfThreadInstance.get();
    }

    /**
     * Gets the choreographer associated with the SurfaceControl.
     *
     * @param layerHandle to which the choreographer will be attached.
     * @param looper      the choreographer is attached on this looper.
     *
     * @return The choreographer for the looper which is attached
     * to the sourced SurfaceControl::mNativeHandle.
     * @throws IllegalStateException if the looper sourced is null.
     * @hide
     */
    @NonNull
    static Choreographer getInstanceForSurfaceControl(long layerHandle,
            @NonNull Looper looper) {
        if (looper == null) {
            throw new IllegalStateException("The current thread must have a looper!");
        }
        return new Choreographer(looper, VSYNC_SOURCE_APP, layerHandle);
    }

    /**
     * @return The Choreographer of the main thread, if it exists, or {@code null} otherwise.
     * @hide
     */
    public static Choreographer getMainThreadInstance() {
        return mMainInstance;
    }

    /** Destroys the calling thread's choreographer
     * @hide
     */
    public static void releaseInstance() {
        Choreographer old = sThreadInstance.get();
        sThreadInstance.remove();
        old.dispose();
    }

    private void dispose() {
        mDisplayEventReceiver.dispose();
    }

    /**
     * Dispose the DisplayEventReceiver on the Choreographer.
     * @hide
     */
    @UnsupportedAppUsage
    void invalidate() {
        dispose();
    }

    /**
     * Check if the sourced looper and the current looper are same.
     * @hide
     */
    boolean isTheLooperSame(Looper looper) {
        return mLooper == looper;
    }

    /**
     * @hide
     */
    public Looper getLooper() {
        return mLooper;
    }

    /**
     * The amount of time, in milliseconds, between each frame of the animation.
     * <p>
     * This is a requested time that the animation will attempt to honor, but the actual delay
     * between frames may be different, depending on system load and capabilities. This is a static
     * function because the same delay will be applied to all animations, since they are all
     * run off of a single timing loop.
     * </p><p>
     * The frame delay may be ignored when the animation system uses an external timing
     * source, such as the display refresh rate (vsync), to govern animations.
     * </p>
     *
     * @return the requested time between frames, in milliseconds
     * @hide
     */
    @UnsupportedAppUsage
    @TestApi
    public static long getFrameDelay() {
        return sFrameDelay;
    }

    /**
     * The amount of time, in milliseconds, between each frame of the animation.
     * <p>
     * This is a requested time that the animation will attempt to honor, but the actual delay
     * between frames may be different, depending on system load and capabilities. This is a static
     * function because the same delay will be applied to all animations, since they are all
     * run off of a single timing loop.
     * </p><p>
     * The frame delay may be ignored when the animation system uses an external timing
     * source, such as the display refresh rate (vsync), to govern animations.
     * </p>
     *
     * @param frameDelay the requested time between frames, in milliseconds
     * @hide
     */
    @TestApi
    public static void setFrameDelay(long frameDelay) {
        sFrameDelay = frameDelay;
    }

    /**
     * Subtracts typical frame delay time from a delay interval in milliseconds.
     * <p>
     * This method can be used to compensate for animation delay times that have baked
     * in assumptions about the frame delay.  For example, it's quite common for code to
     * assume a 60Hz frame time and bake in a 16ms delay.  When we call
     * {@link #postAnimationCallbackDelayed} we want to know how long to wait before
     * posting the animation callback but let the animation timer take care of the remaining
     * frame delay time.
     * </p><p>
     * This method is somewhat conservative about how much of the frame delay it
     * subtracts.  It uses the same value returned by {@link #getFrameDelay} which by
     * default is 10ms even though many parts of the system assume 16ms.  Consequently,
     * we might still wait 6ms before posting an animation callback that we want to run
     * on the next frame, but this is much better than waiting a whole 16ms and likely
     * missing the deadline.
     * </p>
     *
     * @param delayMillis The original delay time including an assumed frame delay.
     * @return The adjusted delay time with the assumed frame delay subtracted out.
     * @hide
     */
    public static long subtractFrameDelay(long delayMillis) {
        final long frameDelay = sFrameDelay;
        return delayMillis <= frameDelay ? 0 : delayMillis - frameDelay;
    }

    /**
     * @return The refresh rate as the nanoseconds between frames
     * @hide
     */
    public long getFrameIntervalNanos() {
        synchronized (mLock) {
            return mLastFrameIntervalNanos;
        }
    }

    void dump(String prefix, PrintWriter writer) {
        String innerPrefix = prefix + "  ";
        writer.print(prefix); writer.println("Choreographer:");
        writer.print(innerPrefix); writer.print("mFrameScheduled=");
                writer.println(mFrameScheduled);
        writer.print(innerPrefix); writer.print("mLastFrameTime=");
                writer.println(TimeUtils.formatUptime(mLastFrameTimeNanos / 1000000));
    }

    /**
     * Posts a callback to run on the next frame.
     * <p>
     * The callback runs once then is automatically removed.
     * </p>
     *
     * @param callbackType The callback type.
     * @param action The callback action to run during the next frame.
     * @param token The callback token, or null if none.
     *
     * @see #removeCallbacks
     * @hide
     */
    @UnsupportedAppUsage
    @TestApi
    public void postCallback(int callbackType, Runnable action, Object token) {
        postCallbackDelayed(callbackType, action, token, 0);
    }

    /**
     * Posts a callback to run on the next frame after the specified delay.
     * <p>
     * The callback runs once then is automatically removed.
     * </p>
     *
     * @param callbackType The callback type.
     * @param action The callback action to run during the next frame after the specified delay.
     * @param token The callback token, or null if none.
     * @param delayMillis The delay time in milliseconds.
     *
     * @see #removeCallback
     * @hide
     */
    @UnsupportedAppUsage
    @TestApi
    public void postCallbackDelayed(int callbackType,
            Runnable action, Object token, long delayMillis) {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        if (callbackType < 0 || callbackType > CALLBACK_LAST) {
            throw new IllegalArgumentException("callbackType is invalid");
        }

        postCallbackDelayedInternal(callbackType, action, token, delayMillis);
    }

    private void postCallbackDelayedInternal(int callbackType,
            Object action, Object token, long delayMillis) {
        if (DEBUG_FRAMES) {
            Log.d(TAG, "PostCallback: type=" + callbackType
                    + ", action=" + action + ", token=" + token
                    + ", delayMillis=" + delayMillis);
        }

        synchronized (mLock) {
            final long now = SystemClock.uptimeMillis();
            final long dueTime = now + delayMillis;
            mCallbackQueues[callbackType].addCallbackLocked(dueTime, action, token);

            if (dueTime <= now) {
                scheduleFrameLocked(now);
            } else {
                Message msg = mHandler.obtainMessage(MSG_DO_SCHEDULE_CALLBACK, action);
                msg.arg1 = callbackType;
                msg.setAsynchronous(true);
                mHandler.sendMessageAtTime(msg, dueTime);
            }
        }
    }

    /**
     * Posts a vsync callback to run on the next frame.
     * <p>
     * The callback runs once then is automatically removed.
     * </p>
     *
     * @param callback The vsync callback to run during the next frame.
     *
     * @see #removeVsyncCallback
     */
    public void postVsyncCallback(@NonNull VsyncCallback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }

        postCallbackDelayedInternal(CALLBACK_ANIMATION, callback, VSYNC_CALLBACK_TOKEN, 0);
    }

    /**
     * Removes callbacks that have the specified action and token.
     *
     * @param callbackType The callback type.
     * @param action The action property of the callbacks to remove, or null to remove
     * callbacks with any action.
     * @param token The token property of the callbacks to remove, or null to remove
     * callbacks with any token.
     *
     * @see #postCallback
     * @see #postCallbackDelayed
     * @hide
     */
    @UnsupportedAppUsage
    @TestApi
    public void removeCallbacks(int callbackType, Runnable action, Object token) {
        if (callbackType < 0 || callbackType > CALLBACK_LAST) {
            throw new IllegalArgumentException("callbackType is invalid");
        }

        removeCallbacksInternal(callbackType, action, token);
    }

    private void removeCallbacksInternal(int callbackType, Object action, Object token) {
        if (DEBUG_FRAMES) {
            Log.d(TAG, "RemoveCallbacks: type=" + callbackType
                    + ", action=" + action + ", token=" + token);
        }

        synchronized (mLock) {
            mCallbackQueues[callbackType].removeCallbacksLocked(action, token);
            if (action != null && token == null) {
                mHandler.removeMessages(MSG_DO_SCHEDULE_CALLBACK, action);
            }
        }
    }

    /**
     * Posts a frame callback to run on the next frame.
     * <p>
     * The callback runs once then is automatically removed.
     * </p>
     *
     * @param callback The frame callback to run during the next frame.
     *
     * @see #postFrameCallbackDelayed
     * @see #removeFrameCallback
     */
    public void postFrameCallback(FrameCallback callback) {
        postFrameCallbackDelayed(callback, 0);
    }

    /**
     * Posts a frame callback to run on the next frame after the specified delay.
     * <p>
     * The callback runs once then is automatically removed.
     * </p>
     *
     * @param callback The frame callback to run during the next frame.
     * @param delayMillis The delay time in milliseconds.
     *
     * @see #postFrameCallback
     * @see #removeFrameCallback
     */
    public void postFrameCallbackDelayed(FrameCallback callback, long delayMillis) {
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }

        postCallbackDelayedInternal(CALLBACK_ANIMATION,
                callback, FRAME_CALLBACK_TOKEN, delayMillis);
    }

    /**
     * Removes a previously posted frame callback.
     *
     * @param callback The frame callback to remove.
     *
     * @see #postFrameCallback
     * @see #postFrameCallbackDelayed
     */
    public void removeFrameCallback(FrameCallback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }

        removeCallbacksInternal(CALLBACK_ANIMATION, callback, FRAME_CALLBACK_TOKEN);
    }

    /**
     * Removes a previously posted vsync callback.
     *
     * @param callback The vsync callback to remove.
     *
     * @see #postVsyncCallback
     */
    public void removeVsyncCallback(@Nullable VsyncCallback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }

        removeCallbacksInternal(CALLBACK_ANIMATION, callback, VSYNC_CALLBACK_TOKEN);
    }

    /**
     * Gets the time when the current frame started.
     * <p>
     * This method provides the time in milliseconds when the frame started being rendered.
     * The frame time provides a stable time base for synchronizing animations
     * and drawing.  It should be used instead of {@link SystemClock#uptimeMillis()}
     * or {@link System#nanoTime()} for animations and drawing in the UI.  Using the frame
     * time helps to reduce inter-frame jitter because the frame time is fixed at the time
     * the frame was scheduled to start, regardless of when the animations or drawing
     * callback actually runs.  All callbacks that run as part of rendering a frame will
     * observe the same frame time so using the frame time also helps to synchronize effects
     * that are performed by different callbacks.
     * </p><p>
     * Please note that the framework already takes care to process animations and
     * drawing using the frame time as a stable time base.  Most applications should
     * not need to use the frame time information directly.
     * </p><p>
     * This method should only be called from within a callback.
     * </p>
     *
     * @return The frame start time, in the {@link SystemClock#uptimeMillis()} time base.
     *
     * @throws IllegalStateException if no frame is in progress.
     * @hide
     */
    @UnsupportedAppUsage
    public long getFrameTime() {
        return getFrameTimeNanos() / TimeUtils.NANOS_PER_MS;
    }

    /**
     * Same as {@link #getFrameTime()} but with nanosecond precision.
     *
     * @return The frame start time, in the {@link System#nanoTime()} time base.
     *
     * @throws IllegalStateException if no frame is in progress.
     * @hide
     */
    @TestApi
    @UnsupportedAppUsage
    @FlaggedApi(FLAG_EXPECTED_PRESENTATION_TIME_API)
    public long getFrameTimeNanos() {
        synchronized (mLock) {
            if (!mCallbacksRunning) {
                throw new IllegalStateException("This method must only be called as "
                        + "part of a callback while a frame is in progress.");
            }
            return USE_FRAME_TIME ? mLastFrameTimeNanos : System.nanoTime();
        }
    }

    /**
     * Like {@link #getLastFrameTimeNanos}, but always returns the last frame time, not matter
     * whether callbacks are currently running.
     * @return The frame start time of the last frame, in the {@link System#nanoTime()} time base.
     * @hide
     */
    public long getLastFrameTimeNanos() {
        synchronized (mLock) {
            return USE_FRAME_TIME ? mLastFrameTimeNanos : System.nanoTime();
        }
    }

    /**
     * Gets the time in {@link System#nanoTime()} timebase which the current frame
     * is expected to be presented.
     * <p>
     * This time should be used to advance any animation clocks.
     * Prefer using this method over {@link #getFrameTimeNanos()}.
     * </p><p>
     * This method should only be called from within a callback.
     * </p>
     *
     * @return The frame start time, in the {@link System#nanoTime()} time base.
     *
     * @throws IllegalStateException if no frame is in progress.
     * @hide
     */
    public long getExpectedPresentationTimeNanos() {
        return mFrameData.getPreferredFrameTimeline().getExpectedPresentationTimeNanos();
    }


    /**
     * Same as {@link #getExpectedPresentationTimeNanos()} but with millisecond precision.
     *
     * @return The frame start time, in the {@link SystemClock#uptimeMillis()} time base.
     *
     * @throws IllegalStateException if no frame is in progress.
     * @hide
     */
    public long getExpectedPresentationTimeMillis() {
        return getExpectedPresentationTimeNanos() / TimeUtils.NANOS_PER_MS;
    }

    /**
     * Same as {@link #getExpectedPresentationTimeNanos()},
     * Should always use {@link #getExpectedPresentationTimeNanos()} if it's possilbe.
     * This method involves a binder call to SF,
     * calling this method can potentially influence the performance.
     *
     * @return The frame start time, in the {@link System#nanoTime()} time base.
     *
     * @hide
     */
    public long getLatestExpectedPresentTimeNanos() {
        if (mDisplayEventReceiver == null) {
            return System.nanoTime();
        }

        return mDisplayEventReceiver.getLatestVsyncEventData()
                .preferredFrameTimeline().expectedPresentationTime;
    }

    private void scheduleFrameLocked(long now) {
        if (!mFrameScheduled) {
            mFrameScheduled = true;
            if (USE_VSYNC) {
                if (DEBUG_FRAMES) {
                    Log.d(TAG, "Scheduling next frame on vsync.");
                }

                // If running on the Looper thread, then schedule the vsync immediately,
                // otherwise post a message to schedule the vsync from the UI thread
                // as soon as possible.
                if (isRunningOnLooperThreadLocked()) {
                    scheduleVsyncLocked();
                } else {
                    Message msg = mHandler.obtainMessage(MSG_DO_SCHEDULE_VSYNC);
                    msg.setAsynchronous(true);
                    mHandler.sendMessageAtFrontOfQueue(msg);
                }
            } else {
                final long nextFrameTime = Math.max(
                        mLastFrameTimeNanos / TimeUtils.NANOS_PER_MS + sFrameDelay, now);
                if (DEBUG_FRAMES) {
                    Log.d(TAG, "Scheduling next frame in " + (nextFrameTime - now) + " ms.");
                }
                Message msg = mHandler.obtainMessage(MSG_DO_FRAME);
                msg.setAsynchronous(true);
                mHandler.sendMessageAtTime(msg, nextFrameTime);
            }
        }
    }

    /**
     * Returns the vsync id of the last frame callback. Client are expected to call
     * this function from their frame callback function to get the vsyncId and pass
     * it together with a buffer or transaction to the Surface Composer. Calling
     * this function from anywhere else will return an undefined value.
     *
     * @hide
     */
    public long getVsyncId() {
        if (!mInDoFrameCallback && Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
            String message = String.format(Locale.getDefault(), "unsync-vsync-id=%d isSfChoreo=%s",
                    mLastVsyncEventData.preferredFrameTimeline().vsyncId, this == getSfInstance());
            Trace.instant(Trace.TRACE_TAG_VIEW, message);
        }
        return mLastVsyncEventData.preferredFrameTimeline().vsyncId;
    }

    /**
     * Returns the frame deadline in {@link System#nanoTime()} timebase that it is allotted for the
     * frame to be completed. Client are expected to call this function from their frame callback
     * function. Calling this function from anywhere else will return an undefined value.
     *
     * @hide
     */
    public long getFrameDeadline() {
        return mLastVsyncEventData.preferredFrameTimeline().deadline;
    }

    void setFPSDivisor(int divisor) {
        if (divisor <= 0) divisor = 1;
        mFPSDivisor = divisor;
        ThreadedRenderer.setFPSDivisor(divisor);
    }

    private void traceMessage(String msg) {
        Trace.traceBegin(Trace.TRACE_TAG_VIEW, msg);
        Trace.traceEnd(Trace.TRACE_TAG_VIEW);
    }

    // Conducts logic for beginning or ending buffer stuffing recovery.
    // Returns an enum for the recovery action that should be taken in doFrame().
    BufferStuffingData.RecoveryAction checkBufferStuffingRecovery(long frameTimeNanos,
            DisplayEventReceiver.VsyncEventData vsyncEventData) {
        // Canned animations can recover from buffer stuffing whenever more
        // than 2 buffers are queued.
        if (vsyncEventData.numberQueuedBuffers > 2) {
            mBufferStuffingData.isRecovering = true;
            // Intentional frame delay that can happen at most MAX_FRAME_DELAYS times per
            // buffer stuffing event until the buffer count returns to threshold. The
            // delayed frames are compensated for by the negative offsets added to the
            // animation timestamps.
            if (mBufferStuffingData.numberFrameDelays < mBufferStuffingData.MAX_FRAME_DELAYS) {
                if (Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
                    Trace.asyncTraceForTrackBegin(
                            Trace.TRACE_TAG_VIEW, "Buffer stuffing recovery", "Thread "
                            + android.os.Process.myTid() + ", recover frame #"
                            + mBufferStuffingData.numberFrameDelays,
                            mBufferStuffingData.numberFrameDelays);
                }
                mBufferStuffingData.numberFrameDelays++;
                scheduleVsyncLocked();
                return BufferStuffingData.RecoveryAction.DELAY_FRAME;
            }
        }

        if (mBufferStuffingData.isRecovering) {
            // Includes an additional expected frame delay from the natural scheduling
            // of the next vsync event.
            int totalFrameDelays = mBufferStuffingData.numberFrameDelays
                    + mBufferStuffingData.numberWaitsForNextVsync + 1;
            long vsyncsSinceLastCallback =
                    (frameTimeNanos - mLastNoOffsetFrameTimeNanos) / mLastFrameIntervalNanos;

            // Detected idle state due to a longer inactive period since the last vsync callback
            // than the total expected number of vsync frame delays. End buffer stuffing recovery.
            // There are no frames to animate and offsets no longer need to be added
            // since the idle state gives the animation a chance to catch up.
            if (vsyncsSinceLastCallback > totalFrameDelays) {
                if (DEBUG_JANK) {
                    Log.d(TAG, "End buffer stuffing recovery");
                }
                if (Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
                    for (int i = 0; i < mBufferStuffingData.numberFrameDelays; i++) {
                        Trace.asyncTraceForTrackEnd(
                                Trace.TRACE_TAG_VIEW, "Buffer stuffing recovery", i);
                    }
                }
                mBufferStuffingData.reset();

            } else {
                if (DEBUG_JANK) {
                    Log.d(TAG, "Adjust animation timeline with a negative offset");
                }
                if (Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
                    Trace.instantForTrack(
                            Trace.TRACE_TAG_VIEW, "Buffer stuffing recovery",
                            "Negative offset added to animation");
                }
                return BufferStuffingData.RecoveryAction.OFFSET;
            }
        }
        return BufferStuffingData.RecoveryAction.NONE;
    }

    void doFrame(long frameTimeNanos, int frame,
            DisplayEventReceiver.VsyncEventData vsyncEventData) {
        final long startNanos;
        final long frameIntervalNanos = vsyncEventData.frameInterval;
        boolean resynced = false;
        long offsetFrameTimeNanos = frameTimeNanos;

        // Evaluate if buffer stuffing recovery needs to start or end, and
        // what actions need to be taken for recovery.
        switch (checkBufferStuffingRecovery(frameTimeNanos, vsyncEventData)) {
            case NONE:
                // Without buffer stuffing recovery, offsetFrameTimeNanos is
                // synonymous with frameTimeNanos.
                break;
            case OFFSET:
                // Add animation offset. Used to update frame timeline with
                // offset before jitter is calculated.
                offsetFrameTimeNanos = frameTimeNanos - frameIntervalNanos;
                break;
            case DELAY_FRAME:
                // Intentional frame delay to help restore queued buffer count to threshold.
                return;
            default:
                break;
        }

        try {
            FrameTimeline timeline = mFrameData.update(offsetFrameTimeNanos, vsyncEventData);
            if (Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
                Trace.traceBegin(
                        Trace.TRACE_TAG_VIEW, "Choreographer#doFrame " + timeline.mVsyncId);
                mInDoFrameCallback = true;
            }
            synchronized (mLock) {
                if (!mFrameScheduled) {
                    traceMessage("Frame not scheduled");
                    return; // no work to do
                }
                mLastNoOffsetFrameTimeNanos = frameTimeNanos;

                if (DEBUG_JANK && mDebugPrintNextFrameTimeDelta) {
                    mDebugPrintNextFrameTimeDelta = false;
                    Log.d(TAG, "Frame time delta: "
                            + ((offsetFrameTimeNanos - mLastFrameTimeNanos) * 0.000001f) + " ms");
                }

                long intendedFrameTimeNanos = offsetFrameTimeNanos;
                startNanos = System.nanoTime();
                // Calculating jitter involves using the original frame time without
                // adjustments from buffer stuffing
                final long jitterNanos = startNanos - frameTimeNanos;
                if (jitterNanos >= frameIntervalNanos) {
                    frameTimeNanos = startNanos;
                    if (frameIntervalNanos == 0) {
                        Log.i(TAG, "Vsync data empty due to timeout");
                    } else {
                        long lastFrameOffset = jitterNanos % frameIntervalNanos;
                        frameTimeNanos = frameTimeNanos - lastFrameOffset;
                        final long skippedFrames = jitterNanos / frameIntervalNanos;
                        if (skippedFrames >= SKIPPED_FRAME_WARNING_LIMIT) {
                            Log.i(TAG, "Skipped " + skippedFrames + " frames!  "
                                    + "The application may be doing too much work on its main "
                                    + "thread.");
                        }
                        if (DEBUG_JANK) {
                            Log.d(TAG, "Missed vsync by " + (jitterNanos * 0.000001f) + " ms "
                                    + "which is more than the frame interval of "
                                    + (frameIntervalNanos * 0.000001f) + " ms!  "
                                    + "Skipping " + skippedFrames + " frames and setting frame "
                                    + "time to " + (lastFrameOffset * 0.000001f)
                                    + " ms in the past.");
                        }
                    }
                    if (mBufferStuffingData.isRecovering) {
                        frameTimeNanos -= frameIntervalNanos;
                        if (DEBUG_JANK) {
                            Log.d(TAG, "Adjusted animation timeline with a negative offset after"
                                    + " jitter calculation");
                        }
                    }
                    timeline = mFrameData.update(
                            frameTimeNanos, mDisplayEventReceiver, jitterNanos);
                    resynced = true;
                }

                if (frameTimeNanos < mLastFrameTimeNanos) {
                    if (DEBUG_JANK) {
                        Log.d(TAG, "Frame time appears to be going backwards.  May be due to a "
                                + "previously skipped frame.  Waiting for next vsync.");
                    }
                    traceMessage("Frame time goes backward");
                    if (mBufferStuffingData.isRecovering) {
                        mBufferStuffingData.numberWaitsForNextVsync++;
                    }
                    scheduleVsyncLocked();
                    return;
                }

                if (mFPSDivisor > 1) {
                    long timeSinceVsync = frameTimeNanos - mLastFrameTimeNanos;
                    if (timeSinceVsync < (frameIntervalNanos * mFPSDivisor) && timeSinceVsync > 0) {
                        traceMessage("Frame skipped due to FPSDivisor");
                        if (mBufferStuffingData.isRecovering) {
                            mBufferStuffingData.numberWaitsForNextVsync++;
                        }
                        scheduleVsyncLocked();
                        return;
                    }
                }

                mFrameInfo.setVsync(intendedFrameTimeNanos, frameTimeNanos,
                        vsyncEventData.preferredFrameTimeline().vsyncId,
                        vsyncEventData.preferredFrameTimeline().deadline, startNanos,
                        vsyncEventData.frameInterval);
                mFrameScheduled = false;
                mLastFrameTimeNanos = frameTimeNanos;
                mLastFrameIntervalNanos = frameIntervalNanos;
                mLastVsyncEventData.copyFrom(vsyncEventData);
            }

            if (resynced && Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
                String message = String.format("Choreographer#doFrame - resynced to %d in %.1fms",
                        timeline.mVsyncId, (timeline.mDeadlineNanos - startNanos) * 0.000001f);
                Trace.traceBegin(Trace.TRACE_TAG_VIEW, message);
            }

            AnimationUtils.lockAnimationClock(frameTimeNanos / TimeUtils.NANOS_PER_MS,
                    timeline.mExpectedPresentationTimeNanos);

            mFrameInfo.markInputHandlingStart();
            doCallbacks(Choreographer.CALLBACK_INPUT, frameIntervalNanos);

            mFrameInfo.markAnimationsStart();
            doCallbacks(Choreographer.CALLBACK_ANIMATION, frameIntervalNanos);
            doCallbacks(Choreographer.CALLBACK_INSETS_ANIMATION, frameIntervalNanos);

            mFrameInfo.markPerformTraversalsStart();
            doCallbacks(Choreographer.CALLBACK_TRAVERSAL, frameIntervalNanos);

            doCallbacks(Choreographer.CALLBACK_COMMIT, frameIntervalNanos);
        } finally {
            AnimationUtils.unlockAnimationClock();
            mInDoFrameCallback = false;
            if (resynced) {
                Trace.traceEnd(Trace.TRACE_TAG_VIEW);
            }
            Trace.traceEnd(Trace.TRACE_TAG_VIEW);
        }

        if (DEBUG_FRAMES) {
            final long endNanos = System.nanoTime();
            Log.d(TAG, "Frame " + frame + ": Finished, took "
                    + (endNanos - startNanos) * 0.000001f + " ms, latency "
                    + (startNanos - frameTimeNanos) * 0.000001f + " ms.");
        }
    }

    void doCallbacks(int callbackType, long frameIntervalNanos) {
        CallbackRecord callbacks;
        long frameTimeNanos = mFrameData.mFrameTimeNanos;
        synchronized (mLock) {
            // We use "now" to determine when callbacks become due because it's possible
            // for earlier processing phases in a frame to post callbacks that should run
            // in a following phase, such as an input event that causes an animation to start.
            final long now = System.nanoTime();
            callbacks = mCallbackQueues[callbackType].extractDueCallbacksLocked(
                    now / TimeUtils.NANOS_PER_MS);
            if (callbacks == null) {
                return;
            }
            mCallbacksRunning = true;

            // Update the frame time if necessary when committing the frame.
            // We only update the frame time if we are more than 2 frames late reaching
            // the commit phase.  This ensures that the frame time which is observed by the
            // callbacks will always increase from one frame to the next and never repeat.
            // We never want the next frame's starting frame time to end up being less than
            // or equal to the previous frame's commit frame time.  Keep in mind that the
            // next frame has most likely already been scheduled by now so we play it
            // safe by ensuring the commit time is always at least one frame behind.
            if (callbackType == Choreographer.CALLBACK_COMMIT) {
                final long jitterNanos = now - frameTimeNanos;
                Trace.traceCounter(Trace.TRACE_TAG_VIEW, "jitterNanos", (int) jitterNanos);
                if (frameIntervalNanos > 0 && jitterNanos >= 2 * frameIntervalNanos) {
                    final long lastFrameOffset = jitterNanos % frameIntervalNanos
                            + frameIntervalNanos;
                    if (DEBUG_JANK) {
                        Log.d(TAG, "Commit callback delayed by " + (jitterNanos * 0.000001f)
                                + " ms which is more than twice the frame interval of "
                                + (frameIntervalNanos * 0.000001f) + " ms!  "
                                + "Setting frame time to " + (lastFrameOffset * 0.000001f)
                                + " ms in the past.");
                        mDebugPrintNextFrameTimeDelta = true;
                    }
                    frameTimeNanos = now - lastFrameOffset;
                    mLastFrameTimeNanos = frameTimeNanos;
                    mFrameData.update(frameTimeNanos, mDisplayEventReceiver, jitterNanos);
                }
            }
        }
        try {
            Trace.traceBegin(Trace.TRACE_TAG_VIEW, CALLBACK_TRACE_TITLES[callbackType]);
            for (CallbackRecord c = callbacks; c != null; c = c.next) {
                if (DEBUG_FRAMES) {
                    Log.d(TAG, "RunCallback: type=" + callbackType
                            + ", action=" + c.action + ", token=" + c.token
                            + ", latencyMillis=" + (SystemClock.uptimeMillis() - c.dueTime));
                }
                c.run(mFrameData);
            }
        } finally {
            synchronized (mLock) {
                mCallbacksRunning = false;
                do {
                    final CallbackRecord next = callbacks.next;
                    recycleCallbackLocked(callbacks);
                    callbacks = next;
                } while (callbacks != null);
            }
            Trace.traceEnd(Trace.TRACE_TAG_VIEW);
        }
    }

    void doScheduleVsync() {
        synchronized (mLock) {
            if (mFrameScheduled) {
                scheduleVsyncLocked();
            }
        }
    }

    void doScheduleCallback(int callbackType) {
        synchronized (mLock) {
            if (!mFrameScheduled) {
                final long now = SystemClock.uptimeMillis();
                if (mCallbackQueues[callbackType].hasDueCallbacksLocked(now)) {
                    scheduleFrameLocked(now);
                }
            }
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private void scheduleVsyncLocked() {
        try {
            Trace.traceBegin(Trace.TRACE_TAG_VIEW, "Choreographer#scheduleVsyncLocked");
            mDisplayEventReceiver.scheduleVsync();
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIEW);
        }
    }

    private boolean isRunningOnLooperThreadLocked() {
        return Looper.myLooper() == mLooper;
    }

    private CallbackRecord obtainCallbackLocked(long dueTime, Object action, Object token) {
        CallbackRecord callback = mCallbackPool;
        if (callback == null) {
            callback = new CallbackRecord();
        } else {
            mCallbackPool = callback.next;
            callback.next = null;
        }
        callback.dueTime = dueTime;
        callback.action = action;
        callback.token = token;
        return callback;
    }

    private void recycleCallbackLocked(CallbackRecord callback) {
        callback.action = null;
        callback.token = null;
        callback.next = mCallbackPool;
        mCallbackPool = callback;
    }

    /**
     * Implement this interface to receive a callback when a new display frame is
     * being rendered.  The callback is invoked on the {@link Looper} thread to
     * which the {@link Choreographer} is attached.
     */
    public interface FrameCallback {
        /**
         * Called when a new display frame is being rendered.
         * <p>
         * This method provides the time in nanoseconds when the frame started being rendered.
         * The frame time provides a stable time base for synchronizing animations
         * and drawing.  It should be used instead of {@link SystemClock#uptimeMillis()}
         * or {@link System#nanoTime()} for animations and drawing in the UI.  Using the frame
         * time helps to reduce inter-frame jitter because the frame time is fixed at the time
         * the frame was scheduled to start, regardless of when the animations or drawing
         * callback actually runs.  All callbacks that run as part of rendering a frame will
         * observe the same frame time so using the frame time also helps to synchronize effects
         * that are performed by different callbacks.
         * </p><p>
         * Please note that the framework already takes care to process animations and
         * drawing using the frame time as a stable time base.  Most applications should
         * not need to use the frame time information directly.
         * </p>
         *
         * @param frameTimeNanos The time in nanoseconds when the frame started being rendered,
         * in the {@link System#nanoTime()} timebase.  Divide this value by {@code 1000000}
         * to convert it to the {@link SystemClock#uptimeMillis()} time base.
         */
        public void doFrame(long frameTimeNanos);
    }

    /** Holds data that describes one possible VSync frame event to render at. */
    public static class FrameTimeline {
        private long mVsyncId = FrameInfo.INVALID_VSYNC_ID;
        private long mExpectedPresentationTimeNanos = -1;
        private long mDeadlineNanos = -1;
        private boolean mInCallback = false;

        FrameTimeline() {
            // Intentionally empty; defined so that it is not API/public by default.
        }

        void setInCallback(boolean inCallback) {
            mInCallback = inCallback;
        }

        private void checkInCallback() {
            if (!mInCallback) {
                throw new IllegalStateException(
                        "FrameTimeline is not valid outside of the vsync callback");
            }
        }

        void update(long vsyncId, long expectedPresentationTimeNanos, long deadlineNanos) {
            mVsyncId = vsyncId;
            mExpectedPresentationTimeNanos = expectedPresentationTimeNanos;
            mDeadlineNanos = deadlineNanos;
        }

        /**
         * The id that corresponds to this frame timeline, used to correlate a frame
         * produced by HWUI with the timeline data stored in Surface Flinger.
         */
        public long getVsyncId() {
            checkInCallback();
            return mVsyncId;
        }

        /**
         * The time in {@link System#nanoTime()} timebase which this frame is expected to be
         * presented.
         */
        public long getExpectedPresentationTimeNanos() {
            checkInCallback();
            return mExpectedPresentationTimeNanos;
        }

        /**
         * The time in  {@link System#nanoTime()} timebase which this frame needs to be ready by.
         */
        public long getDeadlineNanos() {
            checkInCallback();
            return mDeadlineNanos;
        }
    }

    /**
     * The payload for {@link VsyncCallback} which includes frame information such as when
     * the frame started being rendered, and multiple possible frame timelines and their
     * information including deadline and expected present time.
     */
    public static class FrameData {
        private long mFrameTimeNanos;
        private FrameTimeline[] mFrameTimelines;
        private int mPreferredFrameTimelineIndex;
        private boolean mInCallback = false;

        FrameData() {
            allocateFrameTimelines(DisplayEventReceiver.VsyncEventData.FRAME_TIMELINES_CAPACITY);
        }

        /** The time in nanoseconds when the frame started being rendered. */
        public long getFrameTimeNanos() {
            checkInCallback();
            return mFrameTimeNanos;
        }

        /** The possible frame timelines, sorted chronologically. */
        @NonNull
        @SuppressLint("ArrayReturn") // For API consistency and speed.
        public FrameTimeline[] getFrameTimelines() {
            checkInCallback();
            return mFrameTimelines;
        }

        /** The platform-preferred frame timeline. */
        @NonNull
        public FrameTimeline getPreferredFrameTimeline() {
            checkInCallback();
            return mFrameTimelines[mPreferredFrameTimelineIndex];
        }

        void setInCallback(boolean inCallback) {
            mInCallback = inCallback;
            for (int i = 0; i < mFrameTimelines.length; i++) {
                mFrameTimelines[i].setInCallback(inCallback);
            }
        }

        private void checkInCallback() {
            if (!mInCallback) {
                throw new IllegalStateException(
                        "FrameData is not valid outside of the vsync callback");
            }
        }

        private void allocateFrameTimelines(int length) {
            // Maintain one default frame timeline for API (such as getFrameTimelines and
            // getPreferredFrameTimeline) consistency. It should have default data when accessed.
            length = Math.max(1, length);

            if (mFrameTimelines == null || mFrameTimelines.length != length) {
                mFrameTimelines = new FrameTimeline[length];
                for (int i = 0; i < mFrameTimelines.length; i++) {
                    mFrameTimelines[i] = new FrameTimeline();
                }
            }
        }

        /**
         * Update the frame data with a {@code DisplayEventReceiver.VsyncEventData} received from
         * native.
         */
        FrameTimeline update(
                long frameTimeNanos, DisplayEventReceiver.VsyncEventData vsyncEventData) {
            allocateFrameTimelines(vsyncEventData.frameTimelinesLength);
            mFrameTimeNanos = frameTimeNanos;
            mPreferredFrameTimelineIndex = vsyncEventData.preferredFrameTimelineIndex;
            for (int i = 0; i < mFrameTimelines.length; i++) {
                DisplayEventReceiver.VsyncEventData.FrameTimeline frameTimeline =
                        vsyncEventData.frameTimelines[i];
                mFrameTimelines[i].update(frameTimeline.vsyncId,
                        frameTimeline.expectedPresentationTime, frameTimeline.deadline);
            }
            return mFrameTimelines[mPreferredFrameTimelineIndex];
        }

        /**
         * Update the frame data when the frame is late.
         *
         * @param jitterNanos currentTime - frameTime
         */
        FrameTimeline update(
                long frameTimeNanos, DisplayEventReceiver displayEventReceiver, long jitterNanos) {
            int newPreferredIndex = 0;
            final long minimumDeadline =
                    mFrameTimelines[mPreferredFrameTimelineIndex].mDeadlineNanos + jitterNanos;
            // Look for a non-past deadline timestamp in the existing frame data. Otherwise, binder
            // query for new frame data. Note that binder is relatively slow, O(ms), so it is
            // only called when the existing frame data does not hold a valid frame.
            while (newPreferredIndex < mFrameTimelines.length - 1
                    && mFrameTimelines[newPreferredIndex].mDeadlineNanos < minimumDeadline) {
                newPreferredIndex++;
            }

            long newPreferredDeadline = mFrameTimelines[newPreferredIndex].mDeadlineNanos;
            if (newPreferredDeadline < minimumDeadline) {
                DisplayEventReceiver.VsyncEventData latestVsyncEventData =
                        displayEventReceiver.getLatestVsyncEventData();
                if (latestVsyncEventData == null) {
                    Log.w(TAG, "Could not get latest VsyncEventData. Did SurfaceFlinger crash?");
                } else {
                    update(frameTimeNanos, latestVsyncEventData);
                }
            } else {
                update(frameTimeNanos, newPreferredIndex);
            }
            return mFrameTimelines[mPreferredFrameTimelineIndex];
        }

        void update(long frameTimeNanos, int newPreferredFrameTimelineIndex) {
            mFrameTimeNanos = frameTimeNanos;
            mPreferredFrameTimelineIndex = newPreferredFrameTimelineIndex;
        }
    }

    /**
     * Implement this interface to receive a callback to start the next frame. The callback is
     * invoked on the {@link Looper} thread to which the {@link Choreographer} is attached. The
     * callback payload contains information about multiple possible frames, allowing choice of
     * the appropriate frame based on latency requirements.
     *
     * @see FrameCallback
     */
    public interface VsyncCallback {
        /**
         * Called when a new display frame is being rendered.
         *
         * @param data The payload which includes frame information. Divide nanosecond values by
         *             {@code 1000000} to convert it to the {@link SystemClock#uptimeMillis()}
         *             time base. {@code data} is not valid outside of {@code onVsync} and should
         *             not be accessed outside the callback.
         * @see FrameCallback#doFrame
         **/
        void onVsync(@NonNull FrameData data);
    }

    private final class FrameHandler extends Handler {
        public FrameHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_DO_FRAME:
                    doFrame(System.nanoTime(), 0, new DisplayEventReceiver.VsyncEventData());
                    break;
                case MSG_DO_SCHEDULE_VSYNC:
                    doScheduleVsync();
                    break;
                case MSG_DO_SCHEDULE_CALLBACK:
                    doScheduleCallback(msg.arg1);
                    break;
            }
        }
    }

    private final class FrameDisplayEventReceiver extends DisplayEventReceiver
            implements Runnable {
        private boolean mHavePendingVsync;
        private long mTimestampNanos;
        private int mFrame;
        private final VsyncEventData mLastVsyncEventData = new VsyncEventData();

        FrameDisplayEventReceiver(Looper looper, int vsyncSource, long layerHandle) {
            super(looper, vsyncSource, /* eventRegistration */ 0, layerHandle);
        }

        // TODO(b/116025192): physicalDisplayId is ignored because SF only emits VSYNC events for
        // the internal display and DisplayEventReceiver#scheduleVsync only allows requesting VSYNC
        // for the internal display implicitly.
        @Override
        public void onVsync(long timestampNanos, long physicalDisplayId, int frame,
                VsyncEventData vsyncEventData) {
            try {
                if (Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
                    Trace.traceBegin(Trace.TRACE_TAG_VIEW,
                            "Choreographer#onVsync "
                                    + vsyncEventData.preferredFrameTimeline().vsyncId);
                }
                // Post the vsync event to the Handler.
                // The idea is to prevent incoming vsync events from completely starving
                // the message queue.  If there are no messages in the queue with timestamps
                // earlier than the frame time, then the vsync event will be processed immediately.
                // Otherwise, messages that predate the vsync event will be handled first.
                long now = System.nanoTime();
                if (timestampNanos > now) {
                    Log.w(TAG, "Frame time is " + ((timestampNanos - now) * 0.000001f)
                            + " ms in the future!  Check that graphics HAL is generating vsync "
                            + "timestamps using the correct timebase.");
                    timestampNanos = now;
                }

                if (mHavePendingVsync) {
                    Log.w(TAG, "Already have a pending vsync event.  There should only be "
                            + "one at a time.");
                } else {
                    mHavePendingVsync = true;
                }

                mTimestampNanos = timestampNanos;
                mFrame = frame;
                mLastVsyncEventData.copyFrom(vsyncEventData);
                Message msg = Message.obtain(mHandler, this);
                msg.setAsynchronous(true);
                mHandler.sendMessageAtTime(msg, timestampNanos / TimeUtils.NANOS_PER_MS);
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_VIEW);
            }
        }

        @Override
        public void run() {
            mHavePendingVsync = false;
            doFrame(mTimestampNanos, mFrame, mLastVsyncEventData);
        }
    }

    private static final class CallbackRecord {
        public CallbackRecord next;
        public long dueTime;
        /** Runnable or FrameCallback or VsyncCallback object. */
        public Object action;
        /** Denotes the action type. */
        public Object token;

        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public void run(long frameTimeNanos) {
            if (token == FRAME_CALLBACK_TOKEN) {
                ((FrameCallback)action).doFrame(frameTimeNanos);
            } else {
                ((Runnable)action).run();
            }
        }

        void run(FrameData frameData) {
            frameData.setInCallback(true);
            if (token == VSYNC_CALLBACK_TOKEN) {
                ((VsyncCallback) action).onVsync(frameData);
            } else {
                run(frameData.getFrameTimeNanos());
            }
            frameData.setInCallback(false);
        }
    }

    private final class CallbackQueue {
        private CallbackRecord mHead;

        public boolean hasDueCallbacksLocked(long now) {
            return mHead != null && mHead.dueTime <= now;
        }

        public CallbackRecord extractDueCallbacksLocked(long now) {
            CallbackRecord callbacks = mHead;
            if (callbacks == null || callbacks.dueTime > now) {
                return null;
            }

            CallbackRecord last = callbacks;
            CallbackRecord next = last.next;
            while (next != null) {
                if (next.dueTime > now) {
                    last.next = null;
                    break;
                }
                last = next;
                next = next.next;
            }
            mHead = next;
            return callbacks;
        }

        @UnsupportedAppUsage
        public void addCallbackLocked(long dueTime, Object action, Object token) {
            CallbackRecord callback = obtainCallbackLocked(dueTime, action, token);
            CallbackRecord entry = mHead;
            if (entry == null) {
                mHead = callback;
                return;
            }
            if (dueTime < entry.dueTime) {
                callback.next = entry;
                mHead = callback;
                return;
            }
            while (entry.next != null) {
                if (dueTime < entry.next.dueTime) {
                    callback.next = entry.next;
                    break;
                }
                entry = entry.next;
            }
            entry.next = callback;
        }

        public void removeCallbacksLocked(Object action, Object token) {
            CallbackRecord predecessor = null;
            for (CallbackRecord callback = mHead; callback != null;) {
                final CallbackRecord next = callback.next;
                if ((action == null || callback.action == action)
                        && (token == null || callback.token == token)) {
                    if (predecessor != null) {
                        predecessor.next = next;
                    } else {
                        mHead = next;
                    }
                    recycleCallbackLocked(callback);
                } else {
                    predecessor = callback;
                }
                callback = next;
            }
        }
    }
}
