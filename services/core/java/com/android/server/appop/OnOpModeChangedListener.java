/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.appop;

import android.os.RemoteException;

/**
 * Listener for mode changes, encapsulates methods that should be triggered in the event of a mode
 * change.
 */
public interface OnOpModeChangedListener {

    /**
     * Method that should be triggered when the app-op's mode is changed.
     * @param op app-op whose mode-change is being listened to.
     * @param uid user-is associated with the app-op.
     * @param packageName package name associated with the app-op.
     */
    void onOpModeChanged(int op, int uid, String packageName) throws RemoteException;

    /**
     * Return human readable string representing the listener.
     */
    String toString();

}
