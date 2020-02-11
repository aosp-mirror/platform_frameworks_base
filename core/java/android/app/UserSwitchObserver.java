/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.compat.annotation.UnsupportedAppUsage;
import android.os.IRemoteCallback;
import android.os.RemoteException;

/**
 * @hide
 */
public class UserSwitchObserver extends IUserSwitchObserver.Stub {

    @UnsupportedAppUsage
    public UserSwitchObserver() {
    }

    @Override
    public void onUserSwitching(int newUserId, IRemoteCallback reply) throws RemoteException {
        if (reply != null) {
            reply.sendResult(null);
        }
    }

    @Override
    public void onUserSwitchComplete(int newUserId) throws RemoteException {}

    @Override
    public void onForegroundProfileSwitch(int newProfileId) throws RemoteException {}

    @Override
    public void onLockedBootComplete(int newUserId) throws RemoteException {}
}