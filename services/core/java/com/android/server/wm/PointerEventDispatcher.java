/*
 * Copyright (C) 2013 The Android Open Source Project
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

import static com.android.server.input.InputManagerService.ENABLE_PER_WINDOW_INPUT_ROTATION;

import android.graphics.Point;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.WindowManagerPolicyConstants.PointerEventListener;

import com.android.server.UiThread;

import java.util.ArrayList;

public class PointerEventDispatcher extends InputEventReceiver {
    private final ArrayList<PointerEventListener> mListeners = new ArrayList<>();
    private PointerEventListener[] mListenersArray = new PointerEventListener[0];

    private final DisplayContent mDisplayContent;
    private final Point mTmpSize = new Point();

    public PointerEventDispatcher(InputChannel inputChannel, DisplayContent dc) {
        super(inputChannel, UiThread.getHandler().getLooper());
        mDisplayContent = dc;
    }

    @Override
    public void onInputEvent(InputEvent event) {
        try {
            if (event instanceof MotionEvent
                    && (event.getSource() & InputDevice.SOURCE_CLASS_POINTER) != 0) {
                MotionEvent motionEvent = (MotionEvent) event;
                if (ENABLE_PER_WINDOW_INPUT_ROTATION) {
                    final int rotation = mDisplayContent.getRotation();
                    if (rotation != Surface.ROTATION_0) {
                        mDisplayContent.getDisplay().getRealSize(mTmpSize);
                        motionEvent = MotionEvent.obtain(motionEvent);
                        motionEvent.transform(MotionEvent.createRotateMatrix(
                                rotation, mTmpSize.x, mTmpSize.y));
                    }
                }
                PointerEventListener[] listeners;
                synchronized (mListeners) {
                    if (mListenersArray == null) {
                        mListenersArray = new PointerEventListener[mListeners.size()];
                        mListeners.toArray(mListenersArray);
                    }
                    listeners = mListenersArray;
                }
                for (int i = 0; i < listeners.length; ++i) {
                    listeners[i].onPointerEvent(motionEvent);
                }
            }
        } finally {
            finishInputEvent(event, false);
        }
    }

    /**
     * Add the specified listener to the list.
     * @param listener The listener to add.
     */
    public void registerInputEventListener(PointerEventListener listener) {
        synchronized (mListeners) {
            if (mListeners.contains(listener)) {
                throw new IllegalStateException("registerInputEventListener: trying to register" +
                        listener + " twice.");
            }
            mListeners.add(listener);
            mListenersArray = null;
        }
    }

    /**
     * Remove the specified listener from the list.
     * @param listener The listener to remove.
     */
    public void unregisterInputEventListener(PointerEventListener listener) {
        synchronized (mListeners) {
            if (!mListeners.contains(listener)) {
                throw new IllegalStateException("registerInputEventListener: " + listener +
                        " not registered.");
            }
            mListeners.remove(listener);
            mListenersArray = null;
        }
    }

    /** Dispose the associated input channel and clean up the listeners. */
    @Override
    public void dispose() {
        super.dispose();
        synchronized (mListeners) {
            mListeners.clear();
            mListenersArray = null;
        }
    }
}
