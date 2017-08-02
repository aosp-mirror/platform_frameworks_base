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

package com.android.systemui.pip.phone;

import static android.view.WindowManager.INPUT_CONSUMER_PIP;

import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.view.BatchedInputEventReceiver;
import android.view.Choreographer;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.IWindowManager;
import android.view.MotionEvent;

import com.android.systemui.recents.misc.Utilities;

import java.io.PrintWriter;

/**
 * Manages the input consumer that allows the SystemUI to control the PiP.
 */
public class InputConsumerController {

    private static final String TAG = InputConsumerController.class.getSimpleName();

    /**
     * Listener interface for callers to subscribe to touch events.
     */
    public interface TouchListener {
        boolean onTouchEvent(MotionEvent ev);
    }

    /**
     * Listener interface for callers to learn when this class is registered or unregistered with
     * window manager
     */
    public interface RegistrationListener {
        void onRegistrationChanged(boolean isRegistered);
    }

    /**
     * Input handler used for the PiP input consumer. Input events are batched and consumed with the
     * SurfaceFlinger vsync.
     */
    private final class PipInputEventReceiver extends BatchedInputEventReceiver {

        public PipInputEventReceiver(InputChannel inputChannel, Looper looper) {
            super(inputChannel, looper, Choreographer.getSfInstance());
        }

        @Override
        public void onInputEvent(InputEvent event, int displayId) {
            boolean handled = true;
            try {
                // To be implemented for input handling over Pip windows
                if (mListener != null && event instanceof MotionEvent) {
                    MotionEvent ev = (MotionEvent) event;
                    handled = mListener.onTouchEvent(ev);
                }
            } finally {
                finishInputEvent(event, handled);
            }
        }
    }

    private IWindowManager mWindowManager;

    private PipInputEventReceiver mInputEventReceiver;
    private TouchListener mListener;
    private RegistrationListener mRegistrationListener;

    public InputConsumerController(IWindowManager windowManager) {
        mWindowManager = windowManager;
        registerInputConsumer();
    }

    /**
     * Sets the touch listener.
     */
    public void setTouchListener(TouchListener listener) {
        mListener = listener;
    }

    /**
     * Sets the registration listener.
     */
    public void setRegistrationListener(RegistrationListener listener) {
        mRegistrationListener = listener;
        if (mRegistrationListener != null) {
            mRegistrationListener.onRegistrationChanged(mInputEventReceiver != null);
        }
    }

    /**
     * Check if the InputConsumer is currently registered with WindowManager
     *
     * @return {@code true} if registered, {@code false} if not.
     */
    public boolean isRegistered() {
        return mInputEventReceiver != null;
    }

    /**
     * Registers the input consumer.
     */
    public void registerInputConsumer() {
        if (mInputEventReceiver == null) {
            final InputChannel inputChannel = new InputChannel();
            try {
                mWindowManager.destroyInputConsumer(INPUT_CONSUMER_PIP);
                mWindowManager.createInputConsumer(INPUT_CONSUMER_PIP, inputChannel);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to create PIP input consumer", e);
            }
            mInputEventReceiver = new PipInputEventReceiver(inputChannel, Looper.myLooper());
            if (mRegistrationListener != null) {
                mRegistrationListener.onRegistrationChanged(true /* isRegistered */);
            }
        }
    }

    /**
     * Unregisters the input consumer.
     */
    public void unregisterInputConsumer() {
        if (mInputEventReceiver != null) {
            try {
                mWindowManager.destroyInputConsumer(INPUT_CONSUMER_PIP);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to destroy PIP input consumer", e);
            }
            mInputEventReceiver.dispose();
            mInputEventReceiver = null;
            if (mRegistrationListener != null) {
                mRegistrationListener.onRegistrationChanged(false /* isRegistered */);
            }
        }
    }

    public void dump(PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.println(prefix + TAG);
        pw.println(innerPrefix + "registered=" + (mInputEventReceiver != null));
    }
}
