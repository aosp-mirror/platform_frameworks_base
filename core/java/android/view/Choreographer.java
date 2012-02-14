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

import com.android.internal.util.ArrayUtils;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;

/**
 * Coordinates animations and drawing for UI on a particular thread.
 *
 * This object is thread-safe.  Other threads can add and remove listeners
 * or schedule work to occur at a later time on the UI thread.
 *
 * Ensuring thread-safety is a little tricky because the {@link DisplayEventReceiver}
 * can only be accessed from the UI thread so operations that touch the event receiver
 * are posted to the UI thread if needed.
 *
 * @hide
 */
public final class Choreographer extends Handler {
    private static final String TAG = "Choreographer";
    private static final boolean DEBUG = false;

    // Amount of time in ms to wait before actually disposing of the display event
    // receiver after all listeners have been removed.
    private static final long DISPOSE_RECEIVER_DELAY = 200;

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
            return new Choreographer(looper);
        }
    };

    // System property to enable/disable vsync for animations and drawing.
    // Enabled by default.
    private static final boolean USE_VSYNC = SystemProperties.getBoolean(
            "debug.choreographer.vsync", true);

    // System property to enable/disable the use of the vsync / animation timer
    // for drawing rather than drawing immediately.
    // Temporarily disabled by default because postponing performTraversals() violates
    // assumptions about traversals happening in-order relative to other posted messages.
    // Bug: 5721047
    private static final boolean USE_ANIMATION_TIMER_FOR_DRAW = SystemProperties.getBoolean(
            "debug.choreographer.animdraw", false);

    private static final int MSG_DO_ANIMATION = 0;
    private static final int MSG_DO_DRAW = 1;
    private static final int MSG_DO_SCHEDULE_VSYNC = 2;
    private static final int MSG_DO_DISPOSE_RECEIVER = 3;

    private final Object mLock = new Object();

    private final Looper mLooper;

    private OnAnimateListener[] mOnAnimateListeners;
    private OnDrawListener[] mOnDrawListeners;

    private boolean mAnimationScheduled;
    private boolean mDrawScheduled;
    private boolean mFrameDisplayEventReceiverNeeded;
    private FrameDisplayEventReceiver mFrameDisplayEventReceiver;
    private long mLastAnimationTime;
    private long mLastDrawTime;

    private Choreographer(Looper looper) {
        super(looper);
        mLooper = looper;
        mLastAnimationTime = Long.MIN_VALUE;
        mLastDrawTime = Long.MIN_VALUE;
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
     * The amount of time, in milliseconds, between each frame of the animation. This is a
     * requested time that the animation will attempt to honor, but the actual delay between
     * frames may be different, depending on system load and capabilities. This is a static
     * function because the same delay will be applied to all animations, since they are all
     * run off of a single timing loop.
     *
     * The frame delay may be ignored when the animation system uses an external timing
     * source, such as the display refresh rate (vsync), to govern animations.
     *
     * @return the requested time between frames, in milliseconds
     */
    public static long getFrameDelay() {
        return sFrameDelay;
    }

    /**
     * The amount of time, in milliseconds, between each frame of the animation. This is a
     * requested time that the animation will attempt to honor, but the actual delay between
     * frames may be different, depending on system load and capabilities. This is a static
     * function because the same delay will be applied to all animations, since they are all
     * run off of a single timing loop.
     *
     * The frame delay may be ignored when the animation system uses an external timing
     * source, such as the display refresh rate (vsync), to govern animations.
     *
     * @param frameDelay the requested time between frames, in milliseconds
     */
    public static void setFrameDelay(long frameDelay) {
        sFrameDelay = frameDelay;
    }

    /**
     * Schedules animation (and drawing) to occur on the next frame synchronization boundary.
     */
    public void scheduleAnimation() {
        synchronized (mLock) {
            scheduleAnimationLocked();
        }
    }

    private void scheduleAnimationLocked() {
        if (!mAnimationScheduled) {
            mAnimationScheduled = true;
            if (USE_VSYNC) {
                if (DEBUG) {
                    Log.d(TAG, "Scheduling vsync for animation.");
                }

                // If running on the Looper thread, then schedule the vsync immediately,
                // otherwise post a message to schedule the vsync from the UI thread
                // as soon as possible.
                if (!mFrameDisplayEventReceiverNeeded) {
                    mFrameDisplayEventReceiverNeeded = true;
                    if (mFrameDisplayEventReceiver != null) {
                        removeMessages(MSG_DO_DISPOSE_RECEIVER);
                    }
                }
                if (isRunningOnLooperThreadLocked()) {
                    doScheduleVsyncLocked();
                } else {
                    sendMessageAtFrontOfQueue(obtainMessage(MSG_DO_SCHEDULE_VSYNC));
                }
            } else {
                final long now = SystemClock.uptimeMillis();
                final long nextAnimationTime = Math.max(mLastAnimationTime + sFrameDelay, now);
                if (DEBUG) {
                    Log.d(TAG, "Scheduling animation in " + (nextAnimationTime - now) + " ms.");
                }
                sendEmptyMessageAtTime(MSG_DO_ANIMATION, nextAnimationTime);
            }
        }
    }

    /**
     * Returns true if {@link #scheduleAnimation()} has been called but
     * {@link OnAnimateListener#onAnimate() OnAnimateListener.onAnimate()} has
     * not yet been called.
     */
    public boolean isAnimationScheduled() {
        synchronized (mLock) {
            return mAnimationScheduled;
        }
    }

    /**
     * Schedules drawing to occur on the next frame synchronization boundary.
     * Must be called on the UI thread.
     */
    public void scheduleDraw() {
        synchronized (mLock) {
            if (!mDrawScheduled) {
                mDrawScheduled = true;
                if (USE_ANIMATION_TIMER_FOR_DRAW) {
                    scheduleAnimationLocked();
                } else {
                    if (DEBUG) {
                        Log.d(TAG, "Scheduling draw immediately.");
                    }
                    sendEmptyMessage(MSG_DO_DRAW);
                }
            }
        }
    }

    /**
     * Returns true if {@link #scheduleDraw()} has been called but
     * {@link OnDrawListener#onDraw() OnDrawListener.onDraw()} has
     * not yet been called.
     */
    public boolean isDrawScheduled() {
        synchronized (mLock) {
            return mDrawScheduled;
        }
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_DO_ANIMATION:
                doAnimation();
                break;
            case MSG_DO_DRAW:
                doDraw();
                break;
            case MSG_DO_SCHEDULE_VSYNC:
                doScheduleVsync();
                break;
            case MSG_DO_DISPOSE_RECEIVER:
                doDisposeReceiver();
                break;
        }
    }

    private void doAnimation() {
        doAnimationInner();

        if (USE_ANIMATION_TIMER_FOR_DRAW) {
            doDraw();
        }
    }

    private void doAnimationInner() {
        final long start;
        final OnAnimateListener[] listeners;
        synchronized (mLock) {
            if (!mAnimationScheduled) {
                return; // no work to do
            }
            mAnimationScheduled = false;

            start = SystemClock.uptimeMillis();
            if (DEBUG) {
                Log.d(TAG, "Performing animation: " + Math.max(0, start - mLastAnimationTime)
                        + " ms have elapsed since previous animation.");
            }
            mLastAnimationTime = start;

            listeners = mOnAnimateListeners;
        }

        if (listeners != null) {
            for (int i = 0; i < listeners.length; i++) {
                listeners[i].onAnimate();
            }
        }

        if (DEBUG) {
            Log.d(TAG, "Animation took " + (SystemClock.uptimeMillis() - start) + " ms.");
        }
    }

    private void doDraw() {
        final long start;
        final OnDrawListener[] listeners;
        synchronized (mLock) {
            if (!mDrawScheduled) {
                return; // no work to do
            }
            mDrawScheduled = false;

            start = SystemClock.uptimeMillis();
            if (DEBUG) {
                Log.d(TAG, "Performing draw: " + Math.max(0, start - mLastDrawTime)
                        + " ms have elapsed since previous draw.");
            }
            mLastDrawTime = start;

            listeners = mOnDrawListeners;
        }

        if (listeners != null) {
            for (int i = 0; i < listeners.length; i++) {
                listeners[i].onDraw();
            }
        }

        if (DEBUG) {
            Log.d(TAG, "Draw took " + (SystemClock.uptimeMillis() - start) + " ms.");
        }
    }

    private void doScheduleVsync() {
        synchronized (mLock) {
            doScheduleVsyncLocked();
        }
    }

    private void doScheduleVsyncLocked() {
        if (mFrameDisplayEventReceiverNeeded && mAnimationScheduled) {
            if (mFrameDisplayEventReceiver == null) {
                mFrameDisplayEventReceiver = new FrameDisplayEventReceiver(mLooper);
            }
            mFrameDisplayEventReceiver.scheduleVsync();
        }
    }

    private void doDisposeReceiver() {
        synchronized (mLock) {
            if (!mFrameDisplayEventReceiverNeeded && mFrameDisplayEventReceiver != null) {
                mFrameDisplayEventReceiver.dispose();
                mFrameDisplayEventReceiver = null;
            }
        }
    }

    /**
     * Adds an animation listener.
     *
     * @param listener The listener to add.
     */
    public void addOnAnimateListener(OnAnimateListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }

        if (DEBUG) {
            Log.d(TAG, "Adding onAnimate listener: " + listener);
        }

        synchronized (mLock) {
            mOnAnimateListeners = ArrayUtils.appendElement(OnAnimateListener.class,
                    mOnAnimateListeners, listener);
        }
    }

    /**
     * Removes an animation listener.
     *
     * @param listener The listener to remove.
     */
    public void removeOnAnimateListener(OnAnimateListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }

        if (DEBUG) {
            Log.d(TAG, "Removing onAnimate listener: " + listener);
        }

        synchronized (mLock) {
            mOnAnimateListeners = ArrayUtils.removeElement(OnAnimateListener.class,
                    mOnAnimateListeners, listener);
            stopTimingLoopIfNoListenersLocked();
        }
    }

    /**
     * Adds a draw listener.
     *
     * @param listener The listener to add.
     */
    public void addOnDrawListener(OnDrawListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }

        if (DEBUG) {
            Log.d(TAG, "Adding onDraw listener: " + listener);
        }

        synchronized (mLock) {
            mOnDrawListeners = ArrayUtils.appendElement(OnDrawListener.class,
                    mOnDrawListeners, listener);
        }
    }

    /**
     * Removes a draw listener.
     * Must be called on the UI thread.
     *
     * @param listener The listener to remove.
     */
    public void removeOnDrawListener(OnDrawListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }

        if (DEBUG) {
            Log.d(TAG, "Removing onDraw listener: " + listener);
        }

        synchronized (mLock) {
            mOnDrawListeners = ArrayUtils.removeElement(OnDrawListener.class,
                    mOnDrawListeners, listener);
            stopTimingLoopIfNoListenersLocked();
        }
    }

    private void stopTimingLoopIfNoListenersLocked() {
        if (mOnDrawListeners == null && mOnAnimateListeners == null) {
            if (DEBUG) {
                Log.d(TAG, "Stopping timing loop.");
            }

            if (mAnimationScheduled) {
                mAnimationScheduled = false;
                if (USE_VSYNC) {
                    removeMessages(MSG_DO_SCHEDULE_VSYNC);
                } else {
                    removeMessages(MSG_DO_ANIMATION);
                }
            }

            if (mDrawScheduled) {
                mDrawScheduled = false;
                if (!USE_ANIMATION_TIMER_FOR_DRAW) {
                    removeMessages(MSG_DO_DRAW);
                }
            }

            // Post a message to dispose the display event receiver if we haven't needed
            // it again after a certain amount of time has elapsed.  Another reason to
            // defer disposal is that it is possible for use to attempt to dispose the
            // receiver while handling a vsync event that it dispatched, which might
            // cause a few problems...
            if (mFrameDisplayEventReceiverNeeded) {
                mFrameDisplayEventReceiverNeeded = false;
                if (mFrameDisplayEventReceiver != null) {
                    sendEmptyMessageDelayed(MSG_DO_DISPOSE_RECEIVER, DISPOSE_RECEIVER_DELAY);
                }
            }
        }
    }

    private boolean isRunningOnLooperThreadLocked() {
        return Looper.myLooper() == mLooper;
    }

    /**
     * Listens for animation frame timing events.
     */
    public static interface OnAnimateListener {
        /**
         * Called to animate properties before drawing the frame.
         */
        public void onAnimate();
    }

    /**
     * Listens for draw frame timing events.
     */
    public static interface OnDrawListener {
        /**
         * Called to draw the frame.
         */
        public void onDraw();
    }

    private final class FrameDisplayEventReceiver extends DisplayEventReceiver {
        public FrameDisplayEventReceiver(Looper looper) {
            super(looper);
        }

        @Override
        public void onVsync(long timestampNanos, int frame) {
            doAnimation();
        }
    }
}
