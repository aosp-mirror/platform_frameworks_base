/*
 * Copyright (C) 2019 The Android Open Source Project
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


import android.annotation.Nullable;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.view.IWindow;

/**
 * Keeps track of embedded windows.
 *
 * If the embedded window does not receive input then Window Manager does not keep track of it.
 * But if they do receive input, we keep track of the calling PID to blame the right app and
 * the host window to send pointerDownOutsideFocus.
 */
class EmbeddedWindowController {
    /* maps input token to an embedded window */
    private ArrayMap<IBinder /*input token */, EmbeddedWindow> mWindows = new ArrayMap<>();
    private final Object mWmLock;

    EmbeddedWindowController(Object wmLock) {
        mWmLock = wmLock;
    }

    /**
     * Adds a new embedded window.
     *
     * @param inputToken input channel token passed in by the embedding process when it requests
     *                   the server to add an input channel to the embedded surface.
     * @param window     client token used to clean up the map if the embedding process dies
     * @param hostWindowState input channel token belonging to the host window. This is needed to
     *                        handle input callbacks to wm. It's used when raising ANR and when
     *                        the user taps out side of the focused region on screen. This can be
     *                        null if there is no host window.
     * @param ownerUid  calling uid
     * @param ownerPid  calling pid used for anr blaming
     */
    void add(IBinder inputToken, IWindow window, @Nullable WindowState hostWindowState,
            int ownerUid, int ownerPid) {
        EmbeddedWindow embeddedWindow = new EmbeddedWindow(window, hostWindowState, ownerUid,
                ownerPid);
        try {
            mWindows.put(inputToken, embeddedWindow);
            window.asBinder().linkToDeath(()-> {
                synchronized (mWmLock) {
                    mWindows.remove(inputToken);
                }
            }, 0);
        } catch (RemoteException e) {
            // The caller has died, remove from the map
            mWindows.remove(inputToken);
        }
    }

    WindowState getHostWindow(IBinder inputToken) {
        EmbeddedWindow embeddedWindow = mWindows.get(inputToken);
        return embeddedWindow != null ? embeddedWindow.mHostWindowState : null;
    }

    void remove(IWindow client) {
        for (int i = mWindows.size() - 1; i >= 0; i--) {
            if (mWindows.valueAt(i).mClient.asBinder() == client.asBinder()) {
                mWindows.removeAt(i);
                return;
            }
        }
    }

    void removeWindowsWithHost(WindowState host) {
        for (int i = mWindows.size() - 1; i >= 0; i--) {
            if (mWindows.valueAt(i).mHostWindowState == host) {
                mWindows.removeAt(i);
            }
        }
    }

    EmbeddedWindow get(IBinder inputToken) {
        return mWindows.get(inputToken);
    }

    static class EmbeddedWindow {
        final IWindow mClient;
        @Nullable final WindowState mHostWindowState;
        final int mOwnerUid;
        final int mOwnerPid;

        EmbeddedWindow(IWindow clientToken, WindowState hostWindowState, int ownerUid,
                int ownerPid) {
            mClient = clientToken;
            mHostWindowState = hostWindowState;
            mOwnerUid = ownerUid;
            mOwnerPid = ownerPid;
        }
    }
}
