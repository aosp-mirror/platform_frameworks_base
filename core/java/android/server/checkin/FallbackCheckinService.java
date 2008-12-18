/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.server.checkin;

import android.os.ICheckinService;
import android.os.RemoteException;
import android.os.IParentalControlCallback;
import com.google.android.net.ParentalControlState;

/**
 * @hide
 */
public final class FallbackCheckinService extends ICheckinService.Stub {
    public FallbackCheckinService() {
    }

    public void reportCrashSync(byte[] crashData) throws RemoteException {
    }

    public void reportCrashAsync(byte[] crashData) throws RemoteException {
    }

    public void masterClear() throws RemoteException {
    }

    public void getParentalControlState(IParentalControlCallback p) throws RemoteException {
        ParentalControlState state = new ParentalControlState();
        state.isEnabled = false;
        p.onResult(state);
    }
    
    public void getParentalControlState(IParentalControlCallback p, String requestingApp)
            throws android.os.RemoteException {
    }
}
