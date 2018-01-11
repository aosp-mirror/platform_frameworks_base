/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.telephony.euicc;

import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.service.euicc.EuiccProfileInfo;
import android.util.Log;

import com.android.internal.telephony.euicc.IEuiccCardController;
import com.android.internal.telephony.euicc.IGetAllProfilesCallback;

/**
 * EuiccCardManager is the application interface to an eSIM card.
 *
 * @hide
 *
 * TODO(b/35851809): Make this a SystemApi.
 */
public class EuiccCardManager {
    private static final String TAG = "EuiccCardManager";

    /** Result code of execution with no error. */
    public static final int RESULT_OK = 0;

    /**
     * Callback to receive the result of an eUICC card API.
     *
     * @param <T> Type of the result.
     */
    public interface ResultCallback<T> {
        /**
         * This method will be called when an eUICC card API call is completed.
         *
         * @param resultCode This can be {@link #RESULT_OK} or other positive values returned by the
         *     eUICC.
         * @param result The result object. It can be null if the {@code resultCode} is not
         *     {@link #RESULT_OK}.
         */
        void onComplete(int resultCode, T result);
    }

    private final Context mContext;

    /** @hide */
    public EuiccCardManager(Context context) {
        mContext = context;
    }

    private IEuiccCardController getIEuiccCardController() {
        return IEuiccCardController.Stub.asInterface(
                ServiceManager.getService("euicc_card_controller"));
    }

    /**
     * Gets all the profiles on eUicc.
     *
     * @param callback the callback to get the result code and all the profiles.
     */
    public void getAllProfiles(ResultCallback<EuiccProfileInfo[]> callback) {
        try {
            getIEuiccCardController().getAllProfiles(mContext.getOpPackageName(),
                    new IGetAllProfilesCallback.Stub() {
                        @Override
                        public void onComplete(int resultCode, EuiccProfileInfo[] profiles) {
                            callback.onComplete(resultCode, profiles);
                        }
                    });
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling getAllProfiles", e);
            throw e.rethrowFromSystemServer();
        }
    }
}
