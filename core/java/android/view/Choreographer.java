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
    private static final int MSG_DO_SCHEDULE_ANIMATION = 3;
    private static final int MSG_DO_SCHEDULE_DRAW = 4;

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
     * Subtracts typical frame delay time from a delay interval in milliseconds.
     *
     * This method can be used to compensate for animation delay times that have baked
     * in assumptions about the frame delay.  For example, it's quite common for code to
     * assume a 60Hz frame time and bake in a 16ms delay.  When we call
     * {@link #postAnimationCallbackDelayed} we want to know how long to wait before
     * posting the animation callback but let the animation timer take care of the remaining
     * frame delay time.
     *
     * This method is somewhat conservative about how much of the frame delay it
     * subtracts.  It uses the same value returned by {@link #getFrameDelay} which by
     * default is 10ms even though many parts of the system assume 16ms.  Consequently,
     * we might still wait 6ms before posting an animation callback that we want to run
     * on the next frame, but this is much better than waiting a whole 16ms and likely
     * missing the deadline.
     *
     * @param delayMillis The original delay time including an assumed frame delay.
     * @return The adjusted delay time with the assumed frame delay subtracted out.
     */
    public static long subtractFrameDelay(long delayMillis) {
        final long frameDelay = sFrameDelay;
        return delayMillis <= frameDelay ? 0 : delayMillis - frameDelay;
    }

    /**
     * Posts a callback to run on the next animation cycle.
     * The callback only runs once and then is automatically removed.
     *
     * @param action The callback action to run during the next animation cycle.
     * @param token The callback token, or null if none.
     *
     * @see #removeAnimationCallback
     */
    public void postAnimationCallback(Runnable action, Object token) {
        postAnimationCallbackDelayed(action, token, 0);
    }

    /**
     * Posts a callback to run on the next animation cycle following the specified delay.
     * The callback only runs once and then is automatically removed.
     *
     * @param action The callback action to run during the next animation cycle after
     * the specified delay.
     * @param token The callback token, or null if none.
     * @param delayMillis The delay time in milliseconds.
     *
     * @see #removeAnimationCallback
     */
    public void postAnimationCallbackDelayed(Runnable action, Object token, long delayMillis) {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }

        synchronized (mLock) {
            final long now = SystemClock.uptimeMillis();
            final long dueTime = now + delayMillis;
            mAnimationCallbacks = addCallbackLocked(mAnimationCallbacks, dueTime, action, token);

            if (dueTime <= now) {
                scheduleAnimationLocked(now);
            } else {
                Message msg = mHandler.obtainMessage(MSG_DO_SCHEDULE_ANIMATION, action);
                mHandler.sendMessageAtTime(msg, dueTime);
            }
        }
    }

    /**
     * Removes animation callbacks that have the specified action and token.
     *
     * @param action The action property of the callbacks to remove, or null to remove
     * callbacks with any action.
     * @param token The token property of the callbacks to remove, or null to remove
     * callbacks with any token.
     *
     * @see #postAnimationCallback
     * @see #postAnimationCallbackDelayed
     */
    public void removeAnimationCallbacks(Runnable action, Object token) {
        synchronized (mLock) {
            mAnimationCallbacks = removeCallbacksLocked(mAnimationCallbacks, action, token);
            if (action != null && token == null) {
                mHandler.removeMessages(MSG_DO_SCHEDULE_ANIMATION, action);
            }
        }
    }

    /**
     * Posts a callback to run on the next draw cycle.
     * The callback only runs once and then is automatically removed.
     *
     * @param action The callback action to run during the next draw cycle.
     * @param token The callback token, or null if none.
     *
     * @see #removeDrawCallback
     */
    public void postDrawCallback(Runnable action, Object token) {
        postDrawCallbackDelayed(action, token, 0);
    }

    /**
     * Posts a callback to run on the next draw cycle following the specified delay.
     * The callback only runs once and then is automatically removed.
     *
     * @param action The callback action to run during the next animation cycle after
     * the specified delay.
     * @param token The callback token, or null if none.
     * @param delayMillis The delay time in milliseconds.
     *
     * @see #removeDrawCallback
     */
    public void postDrawCallbackDelayed(Runnable action, Object token, long delayMillis) {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }

        synchronized (mLock) {
            final long now = SystemClock.uptimeMillis();
            final long dueTime = now + delayMillis;
            mDrawCallbacks = addCallbackLocked(mDrawCallbacks, dueTime, action, token);
            scheduleDrawLocked(now);

            if (dueTime <= now) {
                scheduleDrawLocked(now);
            } else {
                Message msg = mHandler.obtainMessage(MSG_DO_SCHEDULE_DRAW, action);
                mHandler.sendMessageAtTime(msg, dueTime);
            }
        }
    }

    /**
     * Removes draw callbacks that have the specified action and token.
     *
     * @param action The action property of the callbacks to remove, or null to remove
     * callbacks with any action.
     * @param token The token property of the callbacks to remove, or null to remove
     * callbacks with any token.
     *
     * @see #postDrawCallback
     * @see #postDrawCallbackDelayed
     */
    public void removeDrawCallbacks(Runnable action, Object token) {
        synchronized (mLock) {
            mDrawCallbacks = removeCallbacksLocked(mDrawCallbacks, action, token);
            if (action != null && token == null) {
                mHandler.removeMessages(MSG_DO_SCHEDULE_DRAW, action);
            }
        }
    }

    private void scheduleAnimationLocked(long now) {
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
                    scheduleVsyncLocked();
                } else {
                    Message msg = mHandler.obtainMessage(MSG_DO_SCHEDULE_VSYNC);
                    msg.setAsynchronous(true);
                    mHandler.sendMessageAtFrontOfQueue(msg);
                }
            } else {
                final long nextAnimationTime = Math.max(mLastAnimationTime + sFrameDelay, now);
                if (DEBUG) {
                    Log.d(TAG, "Scheduling animation in " + (nextAnimationTime - now) + " ms.");
                }
                Message msg = mHandler.obtainMessage(MSG_DO_ANIMATION);
                msg.setAsynchronous(true);
                mHandler.sendMessageAtTime(msg, nextAnimationTime);
            }
        }
    }

    private void scheduleDrawLocked(long now) {
        if (!mDrawScheduled) {
            mDrawScheduled = true;
            if (USE_ANIMATION_TIMER_FOR_DRAW) {
                scheduleAnimationLocked(now);
            } else {
                if (DEBUG) {
                    Log.d(TAG, "Scheduling draw immediately.");
                }
                Message msg = mHandler.obtainMessage(MSG_DO_DRAW);
                msg.setAsynchronous(true);
                mHandler.sendMessageAtTime(msg, now);
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
        Callback callbacks;
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
            if (callbacks != null) {
                if (callbacks.dueTime > start) {
                    callbacks = null;
                } else {
                    Callback predecessor = callbacks;
                    Callback successor = predecessor.next;
                    while (successor != null) {
                        if (successor.dueTime > start) {
                            predecessor.next = null;
                            break;
                        }
                        predecessor = successor;
                        successor = successor.next;
                    }
                    mAnimationCallbacks = successor;
                }
            }
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
        Callback callbacks;
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
            if (callbacks != null) {
                if (callbacks.dueTime > start) {
                    callbacks = null;
                } else {
                    Callback predecessor = callbacks;
                    Callback successor = predecessor.next;
                    while (successor != null) {
                        if (successor.dueTime > start) {
                            predecessor.next = null;
                            break;
                        }
                        predecessor = successor;
                        successor = successor.next;
                    }
                    mDrawCallbacks = successor;
                }
            }
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
            if (mAnimationScheduled) {
                scheduleVsyncLocked();
            }
        }
    }

    void doScheduleAnimation() {
        synchronized (mLock) {
            final long now = SystemClock.uptimeMillis();
            if (mAnimationCallbacks != null && mAnimationCallbacks.dueTime <= now) {
                scheduleAnimationLocked(now);
            }
        }
    }

    void doScheduleDraw() {
        synchronized (mLock) {
            final long now = SystemClock.uptimeMillis();
            if (mDrawCallbacks != null && mDrawCallbacks.dueTime <= now) {
                scheduleDrawLocked(now);
            }
        }
    }

    private void scheduleVsyncLocked() {
        mDisplayEventReceiver.scheduleVsync();
    }

    private boolean isRunningOnLooperThreadLocked() {
        return Looper.myLooper() == mLooper;
    }

    private Callback addCallbackLocked(Callback head,
            long dueTime, Runnable action, Object token) {
        Callback callback = obtainCallbackLocked(dueTime, action, token);
        if (head == null) {
            return callback;
        }
        Callback entry = head;
        if (dueTime < entry.dueTime) {
            callback.next = entry;
            return callback;
        }
        while (entry.next != null) {
            if (dueTime < entry.next.dueTime) {
                callback.next = entry.next;
                break;
            }
            entry = entry.next;
        }
        entry.next = callback;
        return head;
    }

    private Callback removeCallbacksLocked(Callback head, Runnable action, Object token) {
        Callback predecessor = null;
        for (Callback callback = head; callback != null;) {
            final Callback next = callback.next;
            if ((action == null || callback.action == action)
                    && (token == null || callback.token == token)) {
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
            head.action.run();
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

    private Callback obtainCallbackLocked(long dueTime, Runnable action, Object token) {
        Callback callback = mCallbackPool;
        if (callback == null) {
            callback = new Callback();
        } else {
            mCallbackPool = callback.next;
            callback.next = null;
        }
        callback.dueTime = dueTime;
        callback.action = action;
        callback.token = token;
        return callback;
    }

    private void recycleCallbackLocked(Callback callback) {
        callback.action = null;
        callback.token = null;
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
                case MSG_DO_SCHEDULE_ANIMATION:
                    doScheduleAnimation();
                    break;
                case MSG_DO_SCHEDULE_DRAW:
                    doScheduleDraw();
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
        public long dueTime;
        public Runnable action;
        public Object token;
    }
}
