/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.wm;

import static android.view.InputDevice.SOURCE_CLASS_POINTER;
import static android.view.MotionEvent.ACTION_CANCEL;
import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_MOVE;
import static android.view.MotionEvent.ACTION_UP;
import static android.view.MotionEvent.BUTTON_STYLUS_PRIMARY;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_DRAG;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.os.Looper;
import android.util.Slog;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.MotionEvent;

/**
 * Input receiver for drag and drop
 */
class DragInputEventReceiver extends InputEventReceiver {
    private final WindowManagerService mService;
    private final DragDropController mDragDropController;

    // Set, if stylus button was down at the start of the drag.
    private boolean mStylusButtonDownAtStart;
    // Indicates the first event to check for button state.
    private boolean mIsStartEvent = true;
    // Set to true to ignore input events after the drag gesture is complete but the drag events
    // are still being dispatched.
    private boolean mMuteInput = false;

    public DragInputEventReceiver(InputChannel inputChannel, Looper looper,
            DragDropController controller, WindowManagerService service) {
        super(inputChannel, looper);
        mDragDropController = controller;
        mService = service;
    }

    @Override
    public void onInputEvent(InputEvent event, int displayId) {
        boolean handled = false;
        try {
            synchronized (mService.mWindowMap) {
                if (!mDragDropController.dragDropActiveLocked()) {
                    // The drag has ended but the clean-up message has not been processed by
                    // window manager. Drop events that occur after this until window manager
                    // has a chance to clean-up the input handle.
                    handled = true;
                    return;
                }
                if (!(event instanceof MotionEvent)
                        || (event.getSource() & SOURCE_CLASS_POINTER) == 0
                        || mMuteInput) {
                    return;
                }
                final MotionEvent motionEvent = (MotionEvent) event;
                boolean endDrag = false;
                final float newX = motionEvent.getRawX();
                final float newY = motionEvent.getRawY();
                final boolean isStylusButtonDown =
                        (motionEvent.getButtonState() & BUTTON_STYLUS_PRIMARY) != 0;

                if (mIsStartEvent) {
                    if (isStylusButtonDown) {
                        // First event and the button was down, check for the button being
                        // lifted in the future, if that happens we'll drop the item.
                        mStylusButtonDownAtStart = true;
                    }
                    mIsStartEvent = false;
                }

                switch (motionEvent.getAction()) {
                    case ACTION_DOWN: {
                        if (DEBUG_DRAG) Slog.w(TAG_WM, "Unexpected ACTION_DOWN in drag layer");
                    }
                    break;

                    case ACTION_MOVE: {
                        if (mStylusButtonDownAtStart && !isStylusButtonDown) {
                            if (DEBUG_DRAG) {
                                Slog.d(TAG_WM, "Button no longer pressed; dropping at "
                                        + newX + "," + newY);
                            }
                            mMuteInput = true;
                            endDrag = mDragDropController.mDragState
                                    .notifyDropLocked(newX, newY);
                        } else {
                            // move the surface and tell the involved window(s) where we are
                            mDragDropController.mDragState.notifyMoveLocked(newX, newY);
                        }
                    }
                    break;

                    case ACTION_UP: {
                        if (DEBUG_DRAG) {
                            Slog.d(TAG_WM, "Got UP on move channel; dropping at "
                                    + newX + "," + newY);
                        }
                        mMuteInput = true;
                        endDrag = mDragDropController.mDragState
                                .notifyDropLocked(newX, newY);
                    }
                    break;

                    case ACTION_CANCEL: {
                        if (DEBUG_DRAG) Slog.d(TAG_WM, "Drag cancelled!");
                        mMuteInput = true;
                        endDrag = true;
                    }
                    break;
                }

                if (endDrag) {
                    if (DEBUG_DRAG)
                        Slog.d(TAG_WM, "Drag ended; tearing down state");
                    // tell all the windows that the drag has ended
                    // endDragLocked will post back to looper to dispose the receiver
                    // since we still need the receiver for the last finishInputEvent.
                    mDragDropController.mDragState.endDragLocked();
                    mStylusButtonDownAtStart = false;
                    mIsStartEvent = true;
                }

                handled = true;
            }
        } catch (Exception e) {
            Slog.e(TAG_WM, "Exception caught by drag handleMotion", e);
        } finally {
            finishInputEvent(event, handled);
        }
    }
}
