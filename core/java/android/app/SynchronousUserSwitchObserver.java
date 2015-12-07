/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.app;

import android.os.Bundle;
import android.os.IRemoteCallback;
import android.os.RemoteException;

/**
 * Base class for synchronous implementations of {@link IUserSwitchObserver}
 *
 * @hide
 */
public abstract class SynchronousUserSwitchObserver extends IUserSwitchObserver.Stub {
    /**
     * Calls {@link #onUserSwitching(int)} and notifies {@code reply} by calling
     * {@link IRemoteCallback#sendResult(Bundle)}.
     */
    @Override
    public final void onUserSwitching(int newUserId, IRemoteCallback reply) throws RemoteException {
        try {
            onUserSwitching(newUserId);
        } finally {
            if (reply != null) {
                reply.sendResult(null);
            }
        }
    }

    /**
     * Synchronous version of {@link IUserSwitchObserver#onUserSwitching(int, IRemoteCallback)}
     */
    public abstract void onUserSwitching(int newUserId) throws RemoteException;
}
