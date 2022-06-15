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

import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.util.Pools;
import android.util.Slog;
import android.view.KeyEvent;

import com.android.server.policy.WindowManagerPolicy;

/**
 * Intercepts key events and forwards them to accessibility manager service.
 */
public class KeyboardInterceptor extends BaseEventStreamTransformation implements Handler.Callback {
    private static final int MESSAGE_PROCESS_QUEUED_EVENTS = 1;
    private static final String LOG_TAG = "KeyboardInterceptor";

    private final AccessibilityManagerService mAms;
    private final WindowManagerPolicy mPolicy;
    private final Handler mHandler;

    private KeyEventHolder mEventQueueStart;
    private KeyEventHolder mEventQueueEnd;

    /**
     * @param service The service to notify of key events
     * @param policy The policy to check for keys that may affect a11y
     */
    public KeyboardInterceptor(AccessibilityManagerService service, WindowManagerPolicy policy) {
        mAms = service;
        mPolicy = policy;
        mHandler = new Handler(this);
    }

    /**
     * @param service The service to notify of key events
     * @param policy The policy to check for keys that may affect a11y
     * @param handler The handler to use. Only used for testing.
     */
    public KeyboardInterceptor(AccessibilityManagerService service, WindowManagerPolicy policy,
            Handler handler) {
        // Can't combine the constructors without making at least mHandler non-final.
        mAms = service;
        mPolicy = policy;
        mHandler = handler;
    }

    @Override
    public void onKeyEvent(KeyEvent event, int policyFlags) {
        /*
         * Certain keys have system-level behavior that affects accessibility services.
         * Let that behavior settle before handling the keys
         */
        long eventDelay = getEventDelay(event, policyFlags);
        if (eventDelay < 0) {
            return;
        }
        if ((eventDelay > 0) || (mEventQueueStart != null))  {
            addEventToQueue(event, policyFlags, eventDelay);
            return;
        }

        mAms.notifyKeyEvent(event, policyFlags);
    }

    @Override
    public boolean handleMessage(Message msg) {
        if (msg.what != MESSAGE_PROCESS_QUEUED_EVENTS) {
            Slog.e(LOG_TAG, "Unexpected message type");
            return false;
        }
        processQueuedEvents();
        if (mEventQueueStart != null) {
            scheduleProcessQueuedEvents();
        }
        return true;
    }

    private void addEventToQueue(KeyEvent event, int policyFlags, long delay) {
        long dispatchTime = SystemClock.uptimeMillis() + delay;
        if (mEventQueueStart == null) {
            mEventQueueEnd = mEventQueueStart =
                    KeyEventHolder.obtain(event, policyFlags, dispatchTime);
            scheduleProcessQueuedEvents();
            return;
        }
        final KeyEventHolder holder = KeyEventHolder.obtain(event, policyFlags, dispatchTime);
        holder.next = mEventQueueStart;
        mEventQueueStart.previous = holder;
        mEventQueueStart = holder;
    }

    private void scheduleProcessQueuedEvents() {
        if (!mHandler.sendEmptyMessageAtTime(
                MESSAGE_PROCESS_QUEUED_EVENTS, mEventQueueEnd.dispatchTime)) {
            Slog.e(LOG_TAG, "Failed to schedule key event");
        };
    }

    private void processQueuedEvents() {
        final long currentTime = SystemClock.uptimeMillis();
        while ((mEventQueueEnd != null) && (mEventQueueEnd.dispatchTime <= currentTime)) {
            final long eventDelay = getEventDelay(mEventQueueEnd.event, mEventQueueEnd.policyFlags);
            if (eventDelay > 0) {
                // Reschedule the event
                mEventQueueEnd.dispatchTime = currentTime + eventDelay;
                return;
            }
            // We'll either send or drop the event
            if (eventDelay == 0) {
                mAms.notifyKeyEvent(mEventQueueEnd.event, mEventQueueEnd.policyFlags);
            }
            final KeyEventHolder eventToBeRecycled = mEventQueueEnd;
            mEventQueueEnd = mEventQueueEnd.previous;
            if (mEventQueueEnd != null) {
                mEventQueueEnd.next = null;
            }
            eventToBeRecycled.recycle();
            if (mEventQueueEnd == null) {
                mEventQueueStart = null;
            }
        }
    }

    private long getEventDelay(KeyEvent event, int policyFlags) {
        int keyCode = event.getKeyCode();
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) || (keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            return mPolicy.interceptKeyBeforeDispatching(null, event, policyFlags);
        }
        return 0;
    }

    private static class KeyEventHolder {
        private static final int MAX_POOL_SIZE = 32;
        private static final Pools.SimplePool<KeyEventHolder> sPool =
                new Pools.SimplePool<>(MAX_POOL_SIZE);

        public int policyFlags;
        public long dispatchTime;
        public KeyEvent event;
        public KeyEventHolder next;
        public KeyEventHolder previous;

        public static KeyEventHolder obtain(KeyEvent event, int policyFlags, long dispatchTime) {
            KeyEventHolder holder = sPool.acquire();
            if (holder == null) {
                holder = new KeyEventHolder();
            }
            holder.event = KeyEvent.obtain(event);
            holder.policyFlags = policyFlags;
            holder.dispatchTime = dispatchTime;
            return holder;
        }

        public void recycle() {
            event.recycle();
            event = null;
            policyFlags = 0;
            dispatchTime = 0;
            next = null;
            previous = null;
            sPool.release(this);
        }
    }
}
