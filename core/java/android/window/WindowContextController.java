/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.window;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.IWindowManager;
import android.view.WindowManager.LayoutParams.WindowType;
import android.view.WindowManagerGlobal;

import com.android.internal.annotations.VisibleForTesting;

/**
 * The controller to manage {@link WindowContext} listener, such as registering and unregistering
 * the listener.
 *
 * @hide
 */
public class WindowContextController {
    private final IWindowManager mWms;
    @VisibleForTesting
    public boolean mListenerRegistered;
    @NonNull
    private final IBinder mToken;

    /**
     * Window Context Controller constructor
     *
     * @param token The token to register to the window context listener. It is usually from
     *              {@link Context#getWindowContextToken()}.
     */
    public WindowContextController(@NonNull IBinder token) {
        mToken = token;
        mWms = WindowManagerGlobal.getWindowManagerService();
    }

    /** Used for test only. DO NOT USE it in production code. */
    @VisibleForTesting
    public WindowContextController(@NonNull IBinder token, IWindowManager mockWms) {
        mToken = token;
        mWms = mockWms;
    }

    /**
     * Registers the {@code mToken} to the window context listener.
     *
     * @param type The window type of the {@link WindowContext}
     * @param displayId The {@link Context#getDisplayId() ID of display} to associate with
     * @param options The window context launched option
     */
    public void registerListener(@WindowType int type, int displayId,  @Nullable Bundle options) {
        if (mListenerRegistered) {
            throw new UnsupportedOperationException("A Window Context can only register a listener"
                    + " once.");
        }
        try {
            mListenerRegistered = mWms.registerWindowContextListener(mToken, type, displayId,
                    options);
        }  catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Unregisters the window context listener associated with the {@code mToken} if it has been
     * registered.
     */
    public void unregisterListenerIfNeeded() {
        if (mListenerRegistered) {
            try {
                mWms.unregisterWindowContextListener(mToken);
                mListenerRegistered = false;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }
}
