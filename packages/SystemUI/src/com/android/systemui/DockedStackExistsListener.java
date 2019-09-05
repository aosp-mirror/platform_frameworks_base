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

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * Utility wrapper to listen for whether or not a docked stack exists, to be
 * used for things like the different overview icon in that mode.
 */
public class DockedStackExistsListener {

    private static final String TAG = "DockedStackExistsListener";

    private static ArrayList<WeakReference<Consumer<Boolean>>> sCallbacks = new ArrayList<>();
    private static boolean mLastExists;

    static {
        try {
            WindowManagerGlobal.getWindowManagerService().registerDockedStackListener(
                    new IDockedStackListener.Stub() {
                        @Override
                        public void onDividerVisibilityChanged(boolean b) throws RemoteException {

                        }

                        @Override
                        public void onDockedStackExistsChanged(boolean exists)
                                throws RemoteException {
                            DockedStackExistsListener.onDockedStackExistsChanged(exists);
                        }

                        @Override
                        public void onDockedStackMinimizedChanged(boolean b, long l, boolean b1)
                                throws RemoteException {

                        }

                        @Override
                        public void onAdjustedForImeChanged(boolean b, long l)
                                throws RemoteException {

                        }

                        @Override
                        public void onDockSideChanged(int i) throws RemoteException {

                        }
                    });
        } catch (RemoteException e) {
            Log.e(TAG, "Failed registering docked stack exists listener", e);
        }
    }


    private static void onDockedStackExistsChanged(boolean exists) {
        mLastExists = exists;
        synchronized (sCallbacks) {
            sCallbacks.removeIf(wf -> {
                Consumer<Boolean> l = wf.get();
                if (l != null) l.accept(exists);
                return l == null;
            });
        }
    }

    public static void register(Consumer<Boolean> callback) {
        callback.accept(mLastExists);
        synchronized (sCallbacks) {
            sCallbacks.add(new WeakReference<>(callback));
        }
    }
}
