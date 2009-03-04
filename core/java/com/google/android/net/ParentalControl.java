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

package com.google.android.net;

import android.os.ICheckinService;
import android.os.IParentalControlCallback;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

public class ParentalControl {
    /**
     * Strings to identify your app. To enable parental control checking for
     * new apps, please add it here, and configure GServices accordingly.
     */
    public static final String VENDING = "vending";
    public static final String YOUTUBE = "youtube";

    /**
     * This interface is supplied to getParentalControlState and is callback upon with
     * the state of parental control.
     */
    public interface Callback {
        /**
         * This method will be called when the state of parental control is known. If state is
         * null, then the state of parental control is unknown.
         * @param state The state of parental control.
         */
        void onResult(ParentalControlState state);
    }

    private static class RemoteCallback extends IParentalControlCallback.Stub {
        private Callback mCallback;

        public RemoteCallback(Callback callback) {
            mCallback = callback;
        }

        public void onResult(ParentalControlState state) {
            if (mCallback != null) {
                mCallback.onResult(state);
            }
        }
    };

    public static void getParentalControlState(Callback callback,
                                               String requestingApp) {
        ICheckinService service =
          ICheckinService.Stub.asInterface(ServiceManager.getService("checkin"));

        RemoteCallback remoteCallback = new RemoteCallback(callback);
        try {
            service.getParentalControlState(remoteCallback, requestingApp);
        } catch (RemoteException e) {
            // This should never happen.
            Log.e("ParentalControl", "Failed to talk to the checkin service.");
        }
    }
}
