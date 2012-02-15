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

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;

/**
 * Coordinates animations and drawing for UI on a particular thread.
 *
 * This object is thread-safe.  Other threads can post callbacks to run at a later time
 * on the UI thread.
 *
 * Ensuring thread-safety is a little tricky because the {@link DisplayEventReceiver}
 * can only be accessed from the UI thread so operations that touch the event receiver
 * are posted to the UI thread if needed.
 *
 * @hide
 */
public final class Choreographer {
    private static final String TAG = "Choreographer";
    private static final boolean DEBUG = false;

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

    private final Object mLock = new Object();

    private final Looper mLooper;
    private final FrameHandler mHandler;
    private final FrameDisplayEventReceiver mDisplayEventReceiver;

    private Callback mCallbackPool;

    private Callback mAnimationCallbacks;
    private Callback mDrawCallbacks;

    private boolean mAnimationScheduled;
    private boolean mDrawScheduled;
    private long mLastAnimationTime;
    private long mLastDrawTime;

    private Choreographer(Looper looper) {
        mLooper = looper;
        mHandler = new FrameHandler(looper);
        mDisplayEventReceiver = USE_VSYNC ? new FrameDisplayEventReceiver(looper) : null;
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
     * Posts a callback to run on the next animation cycle and schedules an animation cycle.
     * The callback only runs once and then is automatically removed.
     *
     * @param runnable The callback to run during the next animation cycle.
     *
     * @see #removeAnimationCallback
     */
    public void postAnimationCallback(Runnable runnable) {
        if (runnable == null) {
            throw new IllegalArgumentException("runnable must not be null");
        }
        synchronized (mLock) {
            mAnimationCallbacks = addCallbackLocked(mAnimationCallbacks, runnable);
            scheduleAnimationLocked();
        }
    }

    /**
     * Removes an animation callback.
     * Does nothing if the specified animation callback has not been posted or has already
     * been removed.
     *
     * @param runnable The animation callback to remove.
     *
     * @see #postAnimationCallback
     */
    public void removeAnimationCallback(Runnable runnable) {
        if (runnable == null) {
            throw new IllegalArgumentException("runnable must not be null");
        }
        synchronized (mLock) {
            mAnimationCallbacks = removeCallbackLocked(mAnimationCallbacks, runnable);
        }
    }

    /**
     * Posts a callback to run on the next draw cycle and schedules a draw cycle.
     * The callback only runs once and then is automatically removed.
     *
     * @param runnable The callback to run during the next draw cycle.
     *
     * @see #removeDrawCallback
     */
    public void postDrawCallback(Runnable runnable) {
        if (runnable == null) {
            throw new IllegalArgumentException("runnable must not be null");
        }
        synchronized (mLock) {
            mDrawCallbacks = addCallbackLocked(mDrawCallbacks, runnable);
            scheduleDrawLocked();
        }
    }

    /**
     * Removes a draw callback.
     * Does nothing if the specified draw callback has not been posted or has already
     * been removed.
     *
     * @param runnable The draw callback to remove.
     *
     * @see #postDrawCallback
     */
    public void removeDrawCallback(Runnable runnable) {
        if (runnable == null) {
            throw new IllegalArgumentException("runnable must not be null");
        }
        synchronized (mLock) {
            mDrawCallbacks = removeCallbackLocked(mDrawCallbacks, runnable);
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
                if (isRunningOnLooperThreadLocked()) {
                    doScheduleVsyncLocked();
                } else {
                    mHandler.sendMessageAtFrontOfQueue(
                            mHandler.obtainMessage(MSG_DO_SCHEDULE_VSYNC));
                }
            } else {
                final long now = SystemClock.uptimeMillis();
                final long nextAnimationTime = Math.max(mLastAnimationTime + sFrameDelay, now);
                if (DEBUG) {
                    Log.d(TAG, "Scheduling animation in " + (nextAnimationTime - now) + " ms.");
                }
                mHandler.sendEmptyMessageAtTime(MSG_DO_ANIMATION, nextAnimationTime);
            }
        }
    }

    private void scheduleDrawLocked() {
        if (!mDrawScheduled) {
            mDrawScheduled = true;
            if (USE_ANIMATION_TIMER_FOR_DRAW) {
                scheduleAnimationLocked();
            } else {
                if (DEBUG) {
                    Log.d(TAG, "Scheduling draw immediately.");
                }
                mHandler.sendEmptyMessage(MSG_DO_DRAW);
            }
        }
    }

    void doAnimation() {
        doAnimationInner();

        if (USE_ANIMATION_TIMER_FOR_DRAW) {
            doDraw();
        }
    }

    void doAnimationInner() {
        final long start;
        final Callback callbacks;
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

            callbacks = mAnimationCallbacks;
            mAnimationCallbacks = null;
        }

        if (callbacks != null) {
            runCallbacks(callbacks);
            synchronized (mLock) {
                recycleCallbacksLocked(callbacks);
            }
        }

        if (DEBUG) {
            Log.d(TAG, "Animation took " + (SystemClock.uptimeMillis() - start) + " ms.");
        }
    }

    void doDraw() {
        final long start;
        final Callback callbacks;
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

            callbacks = mDrawCallbacks;
            mDrawCallbacks = null;
        }

        if (callbacks != null) {
            runCallbacks(callbacks);
            synchronized (mLock) {
                recycleCallbacksLocked(callbacks);
            }
        }

        if (DEBUG) {
            Log.d(TAG, "Draw took " + (SystemClock.uptimeMillis() - start) + " ms.");
        }
    }

    void doScheduleVsync() {
        synchronized (mLock) {
            doScheduleVsyncLocked();
        }
    }

    private void doScheduleVsyncLocked() {
        if (mAnimationScheduled) {
            mDisplayEventReceiver.scheduleVsync();
        }
    }

    private boolean isRunningOnLooperThreadLocked() {
        return Looper.myLooper() == mLooper;
    }

    private Callback addCallbackLocked(Callback head, Runnable runnable) {
        Callback callback = obtainCallbackLocked(runnable);
        if (head == null) {
            return callback;
        }
        Callback tail = head;
        while (tail.next != null) {
            tail = tail.next;
        }
        tail.next = callback;
        return head;
    }

    private Callback removeCallbackLocked(Callback head, Runnable runnable) {
        Callback predecessor = null;
        for (Callback callback = head; callback != null;) {
            final Callback next = callback.next;
            if (callback.runnable == runnable) {
                if (predecessor != null) {
                    predecessor.next = next;
                } else {
                    head = next;
                }
                recycleCallbackLocked(callback);
            } else {
                predecessor = callback;
            }
            callback = next;
        }
        return head;
    }

    private void runCallbacks(Callback head) {
        while (head != null) {
            head.runnable.run();
            head = head.next;
        }
    }

    private void recycleCallbacksLocked(Callback head) {
        while (head != null) {
            final Callback next = head.next;
            recycleCallbackLocked(head);
            head = next;
        }
    }

    private Callback obtainCallbackLocked(Runnable runnable) {
        Callback callback = mCallbackPool;
        if (callback == null) {
            callback = new Callback();
        } else {
            mCallbackPool = callback.next;
            callback.next = null;
        }
        callback.runnable = runnable;
        return callback;
    }

    private void recycleCallbackLocked(Callback callback) {
        callback.runnable = null;
        callback.next = mCallbackPool;
        mCallbackPool = callback;
    }

    private final class FrameHandler extends Handler {
        public FrameHandler(Looper looper) {
            super(looper);
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
            }
        }
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

    private static final class Callback {
        public Callback next;
        public Runnable runnable;
    }
}
