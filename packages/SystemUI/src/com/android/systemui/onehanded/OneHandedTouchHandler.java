/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.onehanded;

import static android.view.Display.DEFAULT_DISPLAY;

import android.graphics.Rect;
import android.hardware.input.InputManager;
import android.os.Looper;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.systemui.Dumpable;

import java.io.FileDescriptor;
import java.io.PrintWriter;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Manages all the touch handling for One Handed on the Phone, including user tap outside region
 * to exit, reset timer when user is in one-handed mode.
 * Refer {@link OneHandedGestureHandler} to see start and stop one handed gesture
 */
@Singleton
public class OneHandedTouchHandler implements OneHandedTransitionCallback, Dumpable {
    private static final String TAG = "OneHandedTouchHandler";
    private final Rect mLastUpdatedBounds = new Rect();

    private OneHandedTimeoutHandler mTimeoutHandler;

    @VisibleForTesting
    InputMonitor mInputMonitor;
    @VisibleForTesting
    InputEventReceiver mInputEventReceiver;
    @VisibleForTesting
    OneHandedTouchEventCallback mTouchEventCallback;

    private boolean mIsEnabled;
    private boolean mIsInOutsideRegion;

    @Inject
    public OneHandedTouchHandler() {
        mTimeoutHandler = OneHandedTimeoutHandler.get();
        updateIsEnabled();
    }

    /**
     * Notified by {@link OneHandedManagerImpl}, when user update settings of Enabled or Disabled
     *
     * @param isEnabled is one handed settings enabled or not
     */
    public void onOneHandedEnabled(boolean isEnabled) {
        mIsEnabled = isEnabled;
        updateIsEnabled();
    }

    /**
     * Register {@link OneHandedTouchEventCallback} to receive onEnter(), onExit() callback
     */
    public void registerTouchEventListener(OneHandedTouchEventCallback callback) {
        mTouchEventCallback = callback;
    }

    private boolean onMotionEvent(MotionEvent ev) {
        mIsInOutsideRegion = isWithinTouchOutsideRegion(ev.getX(), ev.getY());
        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE: {
                if (!mIsInOutsideRegion) {
                    mTimeoutHandler.resetTimer();
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                mTimeoutHandler.resetTimer();
                if (mIsInOutsideRegion) {
                    mTouchEventCallback.onStop();
                }
                // Reset flag for next operation
                mIsInOutsideRegion = false;
                break;
            }
        }
        return true;
    }

    private void disposeInputChannel() {
        if (mInputEventReceiver != null) {
            mInputEventReceiver.dispose();
            mInputEventReceiver = null;
        }
        if (mInputMonitor != null) {
            mInputMonitor.dispose();
            mInputMonitor = null;
        }
    }

    private boolean isWithinTouchOutsideRegion(float x, float y) {
        return Math.round(y) < mLastUpdatedBounds.top;
    }

    private void onInputEvent(InputEvent ev) {
        if (ev instanceof MotionEvent) {
            onMotionEvent((MotionEvent) ev);
        }
    }

    private void updateIsEnabled() {
        disposeInputChannel();
        if (mIsEnabled) {
            mInputMonitor = InputManager.getInstance().monitorGestureInput(
                    "onehanded-touch", DEFAULT_DISPLAY);
            mInputEventReceiver = new SysUiInputEventReceiver(
                    mInputMonitor.getInputChannel(), Looper.getMainLooper());
        }
    }

    @Override
    public void onStartFinished(Rect bounds) {
        mLastUpdatedBounds.set(bounds);
    }

    @Override
    public void onStopFinished(Rect bounds) {
        mLastUpdatedBounds.set(bounds);
    }

    @Override
    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw, @NonNull String[] args) {
        final String innerPrefix = "  ";
        pw.println(TAG + "states: ");
        pw.print(innerPrefix + "mLastUpdatedBounds=");
        pw.println(mLastUpdatedBounds);
    }

    private class SysUiInputEventReceiver extends InputEventReceiver {
        SysUiInputEventReceiver(InputChannel channel, Looper looper) {
            super(channel, looper);
        }

        public void onInputEvent(InputEvent event) {
            OneHandedTouchHandler.this.onInputEvent(event);
            finishInputEvent(event, true);
        }
    }

    /**
     * The touch(gesture) events to notify {@link OneHandedManager} start or stop one handed
     */
    public interface OneHandedTouchEventCallback {
        /**
         * Handle the start event event, and return whether the event was consumed.
         */
        boolean onStart();

        /**
         * Handle the exit event event, and return whether the event was consumed.
         */
        boolean onStop();
    }
}
