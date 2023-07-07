/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.media.projection;

import android.os.IBinder;
import android.os.RemoteException;

/**
 * The connection between MediaProjection and system server is represented by IMediaProjection;
 * outside the test it is implemented by the system server.
 */
public final class FakeIMediaProjection extends IMediaProjection.Stub {
    boolean mIsStarted = false;
    IBinder mLaunchCookie = null;
    IMediaProjectionCallback mIMediaProjectionCallback = null;

    @Override
    public void start(IMediaProjectionCallback callback) throws RemoteException {
        mIMediaProjectionCallback = callback;
        mIsStarted = true;
    }

    @Override
    public void stop() throws RemoteException {
        // Pass along to the client's callback wrapper.
        mIMediaProjectionCallback.onStop();
    }

    @Override
    public boolean canProjectAudio() throws RemoteException {
        return false;
    }

    @Override
    public boolean canProjectVideo() throws RemoteException {
        return false;
    }

    @Override
    public boolean canProjectSecureVideo() throws RemoteException {
        return false;
    }

    @Override
    public int applyVirtualDisplayFlags(int flags) throws RemoteException {
        return 0;
    }

    @Override
    public void registerCallback(IMediaProjectionCallback callback) throws RemoteException {
    }

    @Override
    public void unregisterCallback(IMediaProjectionCallback callback) throws RemoteException {
    }

    @Override
    public IBinder getLaunchCookie() throws RemoteException {
        return mLaunchCookie;
    }

    @Override
    public void setLaunchCookie(IBinder launchCookie) throws RemoteException {
        mLaunchCookie = launchCookie;
    }

    @Override
    public boolean isValid() throws RemoteException {
        return true;
    }


    @Override
    public void notifyVirtualDisplayCreated(int displayId) throws RemoteException {
    }
}
