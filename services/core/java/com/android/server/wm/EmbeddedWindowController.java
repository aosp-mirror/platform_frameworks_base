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


import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.annotation.Nullable;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Slog;
import android.view.IWindow;
import android.view.InputApplicationHandle;

/**
 * Keeps track of embedded windows.
 *
 * If the embedded window does not receive input then Window Manager does not keep track of it.
 * But if they do receive input, we keep track of the calling PID to blame the right app and
 * the host window to send pointerDownOutsideFocus.
 */
class EmbeddedWindowController {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "EmbeddedWindowController" : TAG_WM;
    /* maps input token to an embedded window */
    private ArrayMap<IBinder /*input token */, EmbeddedWindow> mWindows = new ArrayMap<>();
    private final Object mGlobalLock;
    private final ActivityTaskManagerService mAtmService;

    EmbeddedWindowController(ActivityTaskManagerService atmService) {
        mAtmService = atmService;
        mGlobalLock = atmService.getGlobalLock();
    }

    /**
     * Adds a new embedded window.
     *
     * @param inputToken input channel token passed in by the embedding process when it requests
     *                   the server to add an input channel to the embedded surface.
     * @param window An {@link EmbeddedWindow} object to add to this controller.
     */
    void add(IBinder inputToken, EmbeddedWindow window) {
        try {
            mWindows.put(inputToken, window);
            updateProcessController(window);
            window.mClient.asBinder().linkToDeath(()-> {
                synchronized (mGlobalLock) {
                    mWindows.remove(inputToken);
                }
            }, 0);
        } catch (RemoteException e) {
            // The caller has died, remove from the map
            mWindows.remove(inputToken);
        }
    }

    /**
     * Track the host activity in the embedding process so we can determine if the
     * process is currently showing any UI to the user.
     */
    private void updateProcessController(EmbeddedWindow window) {
        if (window.mHostActivityRecord == null) {
            return;
        }
        final WindowProcessController processController =
                mAtmService.getProcessController(window.mOwnerPid, window.mOwnerUid);
        if (processController == null) {
            Slog.w(TAG, "Could not find the embedding process.");
        } else {
            processController.addHostActivity(window.mHostActivityRecord);
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

    void onWindowRemoved(WindowState host) {
        for (int i = mWindows.size() - 1; i >= 0; i--) {
            if (mWindows.valueAt(i).mHostWindowState == host) {
                mWindows.removeAt(i);
            }
        }
    }

    EmbeddedWindow get(IBinder inputToken) {
        return mWindows.get(inputToken);
    }

    void onActivityRemoved(ActivityRecord activityRecord) {
        for (int i = mWindows.size() - 1; i >= 0; i--) {
            final EmbeddedWindow window = mWindows.valueAt(i);
            if (window.mHostActivityRecord == activityRecord) {
                final WindowProcessController processController =
                        mAtmService.getProcessController(window.mOwnerPid, window.mOwnerUid);
                if (processController != null) {
                    processController.removeHostActivity(activityRecord);
                }
            }
        }
    }

    static class EmbeddedWindow {
        final IWindow mClient;
        @Nullable final WindowState mHostWindowState;
        @Nullable final ActivityRecord mHostActivityRecord;
        final int mOwnerUid;
        final int mOwnerPid;

        /**
         * @param clientToken client token used to clean up the map if the embedding process dies
         * @param hostWindowState input channel token belonging to the host window. This is needed
         *                        to handle input callbacks to wm. It's used when raising ANR and
         *                        when the user taps out side of the focused region on screen. This
         *                        can be null if there is no host window.
         * @param ownerUid  calling uid
         * @param ownerPid  calling pid used for anr blaming
         */
        EmbeddedWindow(IWindow clientToken, WindowState hostWindowState, int ownerUid,
                int ownerPid) {
            mClient = clientToken;
            mHostWindowState = hostWindowState;
            mHostActivityRecord = (mHostWindowState != null) ? mHostWindowState.mActivityRecord
                    : null;
            mOwnerUid = ownerUid;
            mOwnerPid = ownerPid;
        }

        String getName() {
            final String hostWindowName = (mHostWindowState != null)
                    ? mHostWindowState.getWindowTag().toString() : "Internal";
            return "EmbeddedWindow{ u" + UserHandle.getUserId(mOwnerUid) + " " + hostWindowName
                    + "}";
        }

        InputApplicationHandle getApplicationHandle() {
            if (mHostWindowState == null
                    || mHostWindowState.mInputWindowHandle.inputApplicationHandle == null) {
                return null;
            }
            return new InputApplicationHandle(
                    mHostWindowState.mInputWindowHandle.inputApplicationHandle);
        }
    }
}
