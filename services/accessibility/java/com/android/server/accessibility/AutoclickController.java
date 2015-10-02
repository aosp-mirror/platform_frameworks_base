/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.accessibility;

import android.annotation.NonNull;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.MotionEvent.PointerCoords;
import android.view.MotionEvent.PointerProperties;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;

/**
 * Implements "Automatically click on mouse stop" feature.
 *
 * If enabled, it will observe motion events from mouse source, and send click event sequence
 * shortly after mouse stops moving. The click will only be performed if mouse movement had been
 * actually detected.
 *
 * Movement detection has tolerance to jitter that may be caused by poor motor control to prevent:
 * <ul>
 *   <li>Initiating unwanted clicks with no mouse movement.</li>
 *   <li>Autoclick never occurring after mouse arriving at target.</li>
 * </ul>
 *
 * Non-mouse motion events, key events (excluding modifiers) and non-movement mouse events cancel
 * the automatic click.
 *
 * It is expected that each instance will receive mouse events from a single mouse device. User of
 * the class should handle cases where multiple mouse devices are present.
 *
 * Each instance is associated to a single user (and it does not handle user switch itself).
 */
public class AutoclickController implements EventStreamTransformation {

    private static final String LOG_TAG = AutoclickController.class.getSimpleName();

    private EventStreamTransformation mNext;
    private final Context mContext;
    private final int mUserId;

    // Lazily created on the first mouse motion event.
    private ClickScheduler mClickScheduler;
    private ClickDelayObserver mClickDelayObserver;

    public AutoclickController(Context context, int userId) {
        mContext = context;
        mUserId = userId;
    }

    @Override
    public void onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            if (mClickScheduler == null) {
                Handler handler = new Handler(mContext.getMainLooper());
                mClickScheduler =
                        new ClickScheduler(handler, AccessibilityManager.AUTOCLICK_DELAY_DEFAULT);
                mClickDelayObserver = new ClickDelayObserver(mUserId, handler);
                mClickDelayObserver.start(mContext.getContentResolver(), mClickScheduler);
            }

            handleMouseMotion(event, policyFlags);
        } else if (mClickScheduler != null) {
            mClickScheduler.cancel();
        }

        if (mNext != null) {
            mNext.onMotionEvent(event, rawEvent, policyFlags);
        }
    }

    @Override
    public void onKeyEvent(KeyEvent event, int policyFlags) {
        if (mClickScheduler != null) {
            if (KeyEvent.isModifierKey(event.getKeyCode())) {
                mClickScheduler.updateMetaState(event.getMetaState());
            } else {
                mClickScheduler.cancel();
            }
        }

        if (mNext != null) {
          mNext.onKeyEvent(event, policyFlags);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (mNext != null) {
            mNext.onAccessibilityEvent(event);
        }
    }

    @Override
    public void setNext(EventStreamTransformation next) {
        mNext = next;
    }

    @Override
    public void clearEvents(int inputSource) {
        if (inputSource == InputDevice.SOURCE_MOUSE && mClickScheduler != null) {
            mClickScheduler.cancel();
        }

        if (mNext != null) {
            mNext.clearEvents(inputSource);
        }
    }

    @Override
    public void onDestroy() {
        if (mClickDelayObserver != null) {
            mClickDelayObserver.stop();
            mClickDelayObserver = null;
        }
        if (mClickScheduler != null) {
            mClickScheduler.cancel();
            mClickScheduler = null;
        }
    }

    private void handleMouseMotion(MotionEvent event, int policyFlags) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_MOVE: {
                if (event.getPointerCount() == 1) {
                    mClickScheduler.update(event, policyFlags);
                } else {
                    mClickScheduler.cancel();
                }
            } break;
            // Ignore hover enter and exit.
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_EXIT:
                break;
            default:
                mClickScheduler.cancel();
        }
    }

    /**
     * Observes setting value for autoclick delay, and updates ClickScheduler delay whenever the
     * setting value changes.
     */
    final private static class ClickDelayObserver extends ContentObserver {
        /** URI used to identify the autoclick delay setting with content resolver. */
        private final Uri mAutoclickDelaySettingUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_AUTOCLICK_DELAY);

        private ContentResolver mContentResolver;
        private ClickScheduler mClickScheduler;
        private final int mUserId;

        public ClickDelayObserver(int userId, Handler handler) {
            super(handler);
            mUserId = userId;
        }

        /**
         * Starts the observer. And makes sure up-to-date autoclick delay is propagated to
         * |clickScheduler|.
         *
         * @param contentResolver Content resolver that should be observed for setting's value
         *     changes.
         * @param clickScheduler ClickScheduler that should be updated when click delay changes.
         * @throws IllegalStateException If internal state is already setup when the method is
         *         called.
         * @throws NullPointerException If any of the arguments is a null pointer.
         */
        public void start(@NonNull ContentResolver contentResolver,
                @NonNull ClickScheduler clickScheduler) {
            if (mContentResolver != null || mClickScheduler != null) {
                throw new IllegalStateException("Observer already started.");
            }
            if (contentResolver == null) {
                throw new NullPointerException("contentResolver not set.");
            }
            if (clickScheduler == null) {
                throw new NullPointerException("clickScheduler not set.");
            }

            mContentResolver = contentResolver;
            mClickScheduler = clickScheduler;
            mContentResolver.registerContentObserver(mAutoclickDelaySettingUri, false, this,
                    mUserId);

            // Initialize mClickScheduler's initial delay value.
            onChange(true, mAutoclickDelaySettingUri);
        }

        /**
         * Stops the the observer. Should only be called if the observer has been started.
         *
         * @throws IllegalStateException If internal state hasn't yet been initialized by calling
         *         {@link #start}.
         */
        public void stop() {
            if (mContentResolver == null || mClickScheduler == null) {
                throw new IllegalStateException("ClickDelayObserver not started.");
            }

            mContentResolver.unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (mAutoclickDelaySettingUri.equals(uri)) {
                int delay = Settings.Secure.getIntForUser(
                        mContentResolver, Settings.Secure.ACCESSIBILITY_AUTOCLICK_DELAY,
                        AccessibilityManager.AUTOCLICK_DELAY_DEFAULT, mUserId);
                mClickScheduler.updateDelay(delay);
            }
        }
    }

    /**
     * Schedules and performs click event sequence that should be initiated when mouse pointer stops
     * moving. The click is first scheduled when a mouse movement is detected, and then further
     * delayed on every sufficient mouse movement.
     */
    final private class ClickScheduler implements Runnable {
        /**
         * Minimal distance pointer has to move relative to anchor in order for movement not to be
         * discarded as noise. Anchor is the position of the last MOVE event that was not considered
         * noise.
         */
        private static final double MOVEMENT_SLOPE = 20f;

        /** Whether there is pending click. */
        private boolean mActive;
        /** If active, time at which pending click is scheduled. */
        private long mScheduledClickTime;

        /** Last observed motion event. null if no events have been observed yet. */
        private MotionEvent mLastMotionEvent;
        /** Last observed motion event's policy flags. */
        private int mEventPolicyFlags;
        /** Current meta state. This value will be used as meta state for click event sequence. */
        private int mMetaState;

        /**
         * The current anchor's coordinates. Should be ignored if #mLastMotionEvent is null.
         * Note that these are not necessary coords of #mLastMotionEvent (because last observed
         * motion event may have been labeled as noise).
         */
        private PointerCoords mAnchorCoords;

        /** Delay that should be used to schedule click. */
        private int mDelay;

        /** Handler for scheduling delayed operations. */
        private Handler mHandler;

        private PointerProperties mTempPointerProperties[];
        private PointerCoords mTempPointerCoords[];

        public ClickScheduler(Handler handler, int delay) {
            mHandler = handler;

            mLastMotionEvent = null;
            resetInternalState();
            mDelay = delay;
            mAnchorCoords = new PointerCoords();
        }

        @Override
        public void run() {
            long now = SystemClock.uptimeMillis();
            // Click was rescheduled after task was posted. Post new run task at updated time.
            if (now < mScheduledClickTime) {
                mHandler.postDelayed(this, mScheduledClickTime - now);
                return;
            }

            sendClick();
            resetInternalState();
        }

        /**
         * Updates properties that should be used for click event sequence initiated by this object,
         * as well as the time at which click will be scheduled.
         * Should be called whenever new motion event is observed.
         *
         * @param event Motion event whose properties should be used as a base for click event
         *     sequence.
         * @param policyFlags Policy flags that should be send with click event sequence.
         */
        public void update(MotionEvent event, int policyFlags) {
            mMetaState = event.getMetaState();

            boolean moved = detectMovement(event);
            cacheLastEvent(event, policyFlags, mLastMotionEvent == null || moved /* useAsAnchor */);

            if (moved) {
              rescheduleClick(mDelay);
            }
        }

        /** Cancels any pending clicks and resets the object state. */
        public void cancel() {
            if (!mActive) {
                return;
            }
            resetInternalState();
            mHandler.removeCallbacks(this);
        }

        /**
         * Updates the meta state that should be used for click sequence.
         */
        public void updateMetaState(int state) {
            mMetaState = state;
        }

        /**
         * Updates delay that should be used when scheduling clicks. The delay will be used only for
         * clicks scheduled after this point (pending click tasks are not affected).
         * @param delay New delay value.
         */
        public void updateDelay(int delay) {
            mDelay = delay;
        }

        /**
         * Updates the time at which click sequence should occur.
         *
         * @param delay Delay (from now) after which click should occur.
         */
        private void rescheduleClick(int delay) {
            long clickTime = SystemClock.uptimeMillis() + delay;
            // If there already is a scheduled click at time before the updated time, just update
            // scheduled time. The click will actually be rescheduled when pending callback is
            // run.
            if (mActive && clickTime > mScheduledClickTime) {
                mScheduledClickTime = clickTime;
                return;
            }

            if (mActive) {
                mHandler.removeCallbacks(this);
            }

            mActive = true;
            mScheduledClickTime = clickTime;

            mHandler.postDelayed(this, delay);
        }

        /**
         * Updates last observed motion event.
         *
         * @param event The last observed event.
         * @param policyFlags The policy flags used with the last observed event.
         * @param useAsAnchor Whether the event coords should be used as a new anchor.
         */
        private void cacheLastEvent(MotionEvent event, int policyFlags, boolean useAsAnchor) {
            if (mLastMotionEvent != null) {
                mLastMotionEvent.recycle();
            }
            mLastMotionEvent = MotionEvent.obtain(event);
            mEventPolicyFlags = policyFlags;

            if (useAsAnchor) {
                final int pointerIndex = mLastMotionEvent.getActionIndex();
                mLastMotionEvent.getPointerCoords(pointerIndex, mAnchorCoords);
            }
        }

        private void resetInternalState() {
            mActive = false;
            if (mLastMotionEvent != null) {
                mLastMotionEvent.recycle();
                mLastMotionEvent = null;
            }
            mScheduledClickTime = -1;
        }

        /**
         * @param event Observed motion event.
         * @return Whether the event coords are far enough from the anchor for the event not to be
         *     considered noise.
         */
        private boolean detectMovement(MotionEvent event) {
            if (mLastMotionEvent == null) {
                return false;
            }
            final int pointerIndex = event.getActionIndex();
            float deltaX = mAnchorCoords.x - event.getX(pointerIndex);
            float deltaY = mAnchorCoords.y - event.getY(pointerIndex);
            double delta = Math.hypot(deltaX, deltaY);
            return delta > MOVEMENT_SLOPE;
        }

        /**
         * Creates and forwards click event sequence.
         */
        private void sendClick() {
            if (mLastMotionEvent == null || mNext == null) {
                return;
            }

            final int pointerIndex = mLastMotionEvent.getActionIndex();

            if (mTempPointerProperties == null) {
                mTempPointerProperties = new PointerProperties[1];
                mTempPointerProperties[0] = new PointerProperties();
            }

            mLastMotionEvent.getPointerProperties(pointerIndex, mTempPointerProperties[0]);

            if (mTempPointerCoords == null) {
                mTempPointerCoords = new PointerCoords[1];
                mTempPointerCoords[0] = new PointerCoords();
            }
            mLastMotionEvent.getPointerCoords(pointerIndex, mTempPointerCoords[0]);

            final long now = SystemClock.uptimeMillis();

            MotionEvent downEvent = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 1,
                    mTempPointerProperties, mTempPointerCoords, mMetaState,
                    MotionEvent.BUTTON_PRIMARY, 1.0f, 1.0f, mLastMotionEvent.getDeviceId(), 0,
                    mLastMotionEvent.getSource(), mLastMotionEvent.getFlags());

            // The only real difference between these two events is the action flag.
            MotionEvent upEvent = MotionEvent.obtain(downEvent);
            upEvent.setAction(MotionEvent.ACTION_UP);

            mNext.onMotionEvent(downEvent, downEvent, mEventPolicyFlags);
            downEvent.recycle();

            mNext.onMotionEvent(upEvent, upEvent, mEventPolicyFlags);
            upEvent.recycle();
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append("ClickScheduler: { active=").append(mActive);
            builder.append(", delay=").append(mDelay);
            builder.append(", scheduledClickTime=").append(mScheduledClickTime);
            builder.append(", anchor={x:").append(mAnchorCoords.x);
            builder.append(", y:").append(mAnchorCoords.y).append("}");
            builder.append(", metastate=").append(mMetaState);
            builder.append(", policyFlags=").append(mEventPolicyFlags);
            builder.append(", lastMotionEvent=").append(mLastMotionEvent);
            builder.append(" }");
            return builder.toString();
        }
    }
}
