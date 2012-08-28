/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server.display;

import android.content.Context;
import android.os.Handler;

import java.io.PrintWriter;

/**
 * A display adapter makes zero or more display devices available to the system
 * and provides facilities for discovering when displays are connected or disconnected.
 * <p>
 * For now, all display adapters are registered in the system server but
 * in principle it could be done from other processes.
 * </p><p>
 * Display devices are not thread-safe and must only be accessed
 * on the display manager service's handler thread.
 * </p>
 */
public class DisplayAdapter {
    private final Context mContext;
    private final String mName;
    private final Handler mHandler;
    private Listener mListener;

    public static final int DISPLAY_DEVICE_EVENT_ADDED = 1;
    public static final int DISPLAY_DEVICE_EVENT_CHANGED = 2;
    public static final int DISPLAY_DEVICE_EVENT_REMOVED = 3;

    public DisplayAdapter(Context context, String name) {
        mContext = context;
        mName = name;
        mHandler = new Handler();
    }

    public final Context getContext() {
        return mContext;
    }

    public final Handler getHandler() {
        return mHandler;
    }

    /**
     * Gets the display adapter name for debugging purposes.
     *
     * @return The display adapter name.
     */
    public final String getName() {
        return mName;
    }

    /**
     * Registers the display adapter with the display manager.
     *
     * @param listener The listener for callbacks.  The listener will
     * be invoked on the display manager service's handler thread.
     */
    public final void register(Listener listener) {
        mListener = listener;
        onRegister();
    }

    /**
     * Dumps the local state of the display adapter.
     */
    public void dump(PrintWriter pw) {
    }

    /**
     * Called when the display adapter is registered.
     *
     * The display adapter should register any built-in display devices as soon as possible.
     * The boot process will wait for the default display to be registered.
     *
     * Other display devices can be registered dynamically later.
     */
    protected void onRegister() {
    }

    /**
     * Sends a display device event to the display adapter listener asynchronously.
     */
    protected void sendDisplayDeviceEvent(final DisplayDevice device, final int event) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mListener.onDisplayDeviceEvent(device, event);
            }
        });
    }

    public interface Listener {
        public void onDisplayDeviceEvent(DisplayDevice device, int event);
    }
}
