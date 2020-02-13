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
import android.view.Display;

import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A display adapter makes zero or more display devices available to the system
 * and provides facilities for discovering when displays are connected or disconnected.
 * <p>
 * For now, all display adapters are registered in the system server but
 * in principle it could be done from other processes.
 * </p><p>
 * Display adapters are guarded by the {@link DisplayManagerService.SyncRoot} lock.
 * </p>
 */
abstract class DisplayAdapter {
    private final DisplayManagerService.SyncRoot mSyncRoot;
    private final Context mContext;
    private final Handler mHandler;
    private final Listener mListener;
    private final String mName;

    public static final int DISPLAY_DEVICE_EVENT_ADDED = 1;
    public static final int DISPLAY_DEVICE_EVENT_CHANGED = 2;
    public static final int DISPLAY_DEVICE_EVENT_REMOVED = 3;

    /**
     * Used to generate globally unique display mode ids.
     */
    private static final AtomicInteger NEXT_DISPLAY_MODE_ID = new AtomicInteger(1);  // 0 = no mode.

    // Called with SyncRoot lock held.
    public DisplayAdapter(DisplayManagerService.SyncRoot syncRoot,
            Context context, Handler handler, Listener listener, String name) {
        mSyncRoot = syncRoot;
        mContext = context;
        mHandler = handler;
        mListener = listener;
        mName = name;
    }

    /**
     * Gets the object that the display adapter should synchronize on when handling
     * calls that come in from outside of the display manager service.
     */
    public final DisplayManagerService.SyncRoot getSyncRoot() {
        return mSyncRoot;
    }

    /**
     * Gets the display adapter's context.
     */
    public final Context getContext() {
        return mContext;
    }

    /**
     * Gets a handler that the display adapter may use to post asynchronous messages.
     */
    public final Handler getHandler() {
        return mHandler;
    }

    /**
     * Gets the display adapter name for debugging purposes.
     */
    public final String getName() {
        return mName;
    }

    /**
     * Registers the display adapter with the display manager.
     *
     * The display adapter should register any built-in display devices as soon as possible.
     * The boot process will wait for the default display to be registered.
     * Other display devices can be registered dynamically later.
     */
    public void registerLocked() {
    }

    /**
     * Dumps the local state of the display adapter.
     */
    public void dumpLocked(PrintWriter pw) {
    }

    /**
     * Sends a display device event to the display adapter listener asynchronously.
     */
    protected final void sendDisplayDeviceEventLocked(
            final DisplayDevice device, final int event) {
        mHandler.post(() -> mListener.onDisplayDeviceEvent(device, event));
    }

    /**
     * Sends a request to perform traversals.
     */
    protected final void sendTraversalRequestLocked() {
        mHandler.post(() -> mListener.onTraversalRequested());
    }

    public static Display.Mode createMode(int width, int height, float refreshRate) {
        return new Display.Mode(
                NEXT_DISPLAY_MODE_ID.getAndIncrement(), width, height, refreshRate);
    }

    public interface Listener {
        void onDisplayDeviceEvent(DisplayDevice device, int event);
        void onTraversalRequested();
    }
}
