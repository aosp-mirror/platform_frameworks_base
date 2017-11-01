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
 * limitations under the License
 */

package com.android.server.wm;

import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.SparseArray;
import android.view.IWallpaperVisibilityListener;

/**
 * Manages and trigger wallpaper visibility listeners.
 */
class WallpaperVisibilityListeners {

    /**
     * A map of displayIds and its listeners.
     */
    private final SparseArray<RemoteCallbackList<IWallpaperVisibilityListener>> mDisplayListeners =
            new SparseArray<>();

    void registerWallpaperVisibilityListener(IWallpaperVisibilityListener listener,
            int displayId) {
        RemoteCallbackList<IWallpaperVisibilityListener> listeners =
                mDisplayListeners.get(displayId);
        if (listeners == null) {
            listeners = new RemoteCallbackList<>();
            mDisplayListeners.append(displayId, listeners);
        }
        listeners.register(listener);
    }

    void unregisterWallpaperVisibilityListener(IWallpaperVisibilityListener listener,
            int displayId) {
        RemoteCallbackList<IWallpaperVisibilityListener> listeners =
                mDisplayListeners.get(displayId);
        if (listeners == null) {
            return;
        }
        listeners.unregister(listener);
    }

    void notifyWallpaperVisibilityChanged(DisplayContent displayContent) {
        final int displayId = displayContent.getDisplayId();
        final boolean visible = displayContent.mWallpaperController.isWallpaperVisible();
        RemoteCallbackList<IWallpaperVisibilityListener> displayListeners =
                mDisplayListeners.get(displayId);

        // No listeners for this display.
        if (displayListeners == null) {
            return;
        }

        int i = displayListeners.beginBroadcast();
        while (i > 0) {
            i--;
            IWallpaperVisibilityListener listener = displayListeners.getBroadcastItem(i);
            try {
                listener.onWallpaperVisibilityChanged(visible, displayId);
            } catch (RemoteException e) {
                // Nothing to do in here, RemoteCallbackListener will clean it up.
            }
        }
        displayListeners.finishBroadcast();
    }
}
