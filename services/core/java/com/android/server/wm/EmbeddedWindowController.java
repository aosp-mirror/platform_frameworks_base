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


import static com.android.server.wm.IdentifierProto.HASH_CODE;
import static com.android.server.wm.IdentifierProto.TITLE;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowStateProto.IDENTIFIER;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.InputApplicationHandle;
import android.view.InputChannel;
import android.window.InputTransferToken;

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
    private ArrayMap<InputTransferToken /*input transfer token */, EmbeddedWindow>
            mWindowsByInputTransferToken = new ArrayMap<>();
    private ArrayMap<IBinder /*window token*/, EmbeddedWindow> mWindowsByWindowToken =
        new ArrayMap<>();
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
            final InputTransferToken inputTransferToken = window.getInputTransferToken();
            mWindowsByInputTransferToken.put(inputTransferToken, window);
            mWindowsByWindowToken.put(window.getWindowToken(), window);
            updateProcessController(window);
            window.mClient.linkToDeath(()-> {
                synchronized (mGlobalLock) {
                    mWindows.remove(inputToken);
                    mWindowsByInputTransferToken.remove(inputTransferToken);
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

    void remove(IBinder client) {
        for (int i = mWindows.size() - 1; i >= 0; i--) {
            EmbeddedWindow ew = mWindows.valueAt(i);
            if (ew.mClient == client) {
                mWindows.removeAt(i).onRemoved();
                mWindowsByInputTransferToken.remove(ew.getInputTransferToken());
                mWindowsByWindowToken.remove(ew.getWindowToken());
                return;
            }
        }
    }

    void onWindowRemoved(WindowState host) {
        for (int i = mWindows.size() - 1; i >= 0; i--) {
            EmbeddedWindow ew = mWindows.valueAt(i);
            if (ew.mHostWindowState == host) {
                mWindows.removeAt(i).onRemoved();
                mWindowsByInputTransferToken.remove(ew.getInputTransferToken());
                mWindowsByWindowToken.remove(ew.getWindowToken());
            }
        }
    }

    EmbeddedWindow get(IBinder inputToken) {
        return mWindows.get(inputToken);
    }

    EmbeddedWindow getByInputTransferToken(InputTransferToken inputTransferToken) {
        return mWindowsByInputTransferToken.get(inputTransferToken);
    }

    EmbeddedWindow getByWindowToken(IBinder windowToken) {
        return mWindowsByWindowToken.get(windowToken);
    }

    static class EmbeddedWindow implements InputTarget {
        final IBinder mClient;
        @Nullable final WindowState mHostWindowState;
        @Nullable final ActivityRecord mHostActivityRecord;
        final String mName;
        final int mOwnerUid;
        final int mOwnerPid;
        final WindowManagerService mWmService;
        final int mDisplayId;
        public Session mSession;
        InputChannel mInputChannel;
        final int mWindowType;

        /**
         * A unique token associated with the embedded window that can be used by the host window
         * to request focus transfer and gesture transfer to the embedded. This is not the input
         * token since we don't want to give clients access to each others input token.
         */
        private final InputTransferToken mInputTransferToken;

        private boolean mIsFocusable;

        /**
         * @param session  calling session to check ownership of the window
         * @param clientToken client token used to clean up the map if the embedding process dies
         * @param hostWindowState input channel token belonging to the host window. This is needed
         *                        to handle input callbacks to wm. It's used when raising ANR and
         *                        when the user taps out side of the focused region on screen. This
         *                        can be null if there is no host window.
         * @param ownerUid  calling uid
         * @param ownerPid  calling pid used for anr blaming
         * @param windowType to forward to input
         * @param displayId used for focus requests
         */
        EmbeddedWindow(Session session, WindowManagerService service, IBinder clientToken,
                       WindowState hostWindowState, int ownerUid, int ownerPid, int windowType,
                       int displayId, InputTransferToken inputTransferToken, String inputHandleName,
                       boolean isFocusable) {
            mSession = session;
            mWmService = service;
            mClient = clientToken;
            mHostWindowState = hostWindowState;
            mHostActivityRecord = (mHostWindowState != null) ? mHostWindowState.mActivityRecord
                    : null;
            mOwnerUid = ownerUid;
            mOwnerPid = ownerPid;
            mWindowType = windowType;
            mDisplayId = displayId;
            mInputTransferToken = inputTransferToken;
            final String hostWindowName =
                    (mHostWindowState != null) ? "-" + mHostWindowState.getWindowTag().toString()
                            : "";
            mIsFocusable = isFocusable;
            mName = "Embedded{" + inputHandleName + hostWindowName + "}";
        }

        @Override
        public String toString() {
            return mName;
        }

        InputApplicationHandle getApplicationHandle() {
            if (mHostWindowState == null
                    || mHostWindowState.mInputWindowHandle.getInputApplicationHandle() == null) {
                return null;
            }
            return new InputApplicationHandle(
                    mHostWindowState.mInputWindowHandle.getInputApplicationHandle());
        }

        void openInputChannel(@NonNull InputChannel outInputChannel) {
            final String name = toString();
            mInputChannel = mWmService.mInputManager.createInputChannel(name);
            mInputChannel.copyTo(outInputChannel);
        }

        void onRemoved() {
            if (mInputChannel != null) {
                mWmService.mInputManager.removeInputChannel(mInputChannel.getToken());
                mInputChannel.dispose();
                mInputChannel = null;
            }
            if (mHostActivityRecord != null) {
                final WindowProcessController wpc =
                        mWmService.mAtmService.getProcessController(mOwnerPid, mOwnerUid);
                if (wpc != null) {
                    wpc.removeHostActivity(mHostActivityRecord);
                }
            }
        }

        @Override
        public WindowState getWindowState() {
            return mHostWindowState;
        }

        @Override
        public int getDisplayId() {
            return mDisplayId;
        }

        @Override
        public DisplayContent getDisplayContent() {
            return mWmService.mRoot.getDisplayContent(getDisplayId());
        }

        public IBinder getWindowToken() {
            return mClient;
        }

        @Override
        public int getPid() {
            return mOwnerPid;
        }

        @Override
        public int getUid() {
            return mOwnerUid;
        }

        InputTransferToken getInputTransferToken() {
            return mInputTransferToken;
        }

        IBinder getInputChannelToken() {
            if (mInputChannel != null) {
                return mInputChannel.getToken();
            }
            return null;
        }

        void setIsFocusable(boolean isFocusable) {
            mIsFocusable = isFocusable;
        }

        /**
         * When an embedded window is touched when it's not currently focus, we need to switch
         * focus to that embedded window unless the embedded window was marked as not focusable.
         */
        @Override
        public boolean receiveFocusFromTapOutside() {
            return mIsFocusable;
        }

        private void handleTap(boolean grantFocus) {
            if (mInputChannel != null) {
                if (mHostWindowState != null) {
                    // Use null session since this is being granted by system server and doesn't
                    // require the host session to be passed in
                    mWmService.grantEmbeddedWindowFocus(null, mHostWindowState.mClient,
                            mInputTransferToken, grantFocus);
                    if (grantFocus) {
                        // If granting focus to the embedded when tapped, we need to ensure the host
                        // gains focus as well or the transfer won't take effect since it requires
                        // the host to transfer the focus to the embedded.
                        mHostWindowState.handleTapOutsideFocusInsideSelf();
                    }
                } else {
                    mWmService.grantEmbeddedWindowFocus(mSession, mInputTransferToken, grantFocus);
                }
            }
        }

        @Override
        public void handleTapOutsideFocusOutsideSelf() {
            handleTap(false);
        }

        @Override
        public void handleTapOutsideFocusInsideSelf() {
            handleTap(true);
        }

        @Override
        public boolean shouldControlIme() {
            return mHostWindowState != null;
        }

        @Override
        public boolean canScreenshotIme() {
            return true;
        }

        @Override
        public InsetsControlTarget getImeControlTarget() {
            if (mHostWindowState != null) {
                return mHostWindowState.getImeControlTarget();
            }
            return mWmService.getDefaultDisplayContentLocked().mRemoteInsetsControlTarget;
        }

        @Override
        public boolean isInputMethodClientFocus(int uid, int pid) {
            return uid == mOwnerUid && pid == mOwnerPid;
        }

        @Override
        public ActivityRecord getActivityRecord() {
            return mHostActivityRecord;
        }

        @Override
        public void dumpProto(ProtoOutputStream proto, long fieldId,
                              @WindowTraceLogLevel int logLevel) {
            final long token = proto.start(fieldId);

            final long token2 = proto.start(IDENTIFIER);
            proto.write(HASH_CODE, System.identityHashCode(this));
            proto.write(TITLE, "EmbeddedWindow");
            proto.end(token2);
            proto.end(token);
        }
    }
}
