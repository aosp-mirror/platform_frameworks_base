/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui;

import android.os.RemoteException;
import android.util.Log;
import android.view.IDockedStackListener;
import android.view.WindowManagerGlobal;

import java.util.function.Consumer;

/**
 * Utility wrapper to listen for whether or not a docked stack exists, to be
 * used for things like the different overview icon in that mode.
 */
public class DockedStackExistsListener extends IDockedStackListener.Stub {

    private static final String TAG = "DockedStackExistsListener";

    private final Consumer<Boolean> mCallback;

    private DockedStackExistsListener(Consumer<Boolean> callback) {
        mCallback = callback;
    }

    @Override
    public void onDividerVisibilityChanged(boolean visible) throws RemoteException {
    }

    @Override
    public void onDockedStackExistsChanged(final boolean exists) throws RemoteException {
        mCallback.accept(exists);
    }

    @Override
    public void onDockedStackMinimizedChanged(boolean minimized, long animDuration,
                                              boolean isHomeStackResizable) throws RemoteException {
    }

    @Override
    public void onAdjustedForImeChanged(boolean adjustedForIme, long animDuration)
            throws RemoteException {
    }

    @Override
    public void onDockSideChanged(int newDockSide) throws RemoteException {
    }

    public static void register(Consumer<Boolean> callback) {
        try {
            WindowManagerGlobal.getWindowManagerService().registerDockedStackListener(
                    new DockedStackExistsListener(callback));
        } catch (RemoteException e) {
            Log.e(TAG, "Failed registering docked stack exists listener", e);
        }
    }
}
