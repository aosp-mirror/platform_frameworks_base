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

package android.service.carrier;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.WorkerThread;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import com.android.internal.telephony.IApnSourceService;

import java.util.List;

/**
 * A service that the system can call to restore default APNs.
 * <p>
 * To extend this class, specify the full name of your implementation in the resource file
 * {@code packages/providers/TelephonyProvider/res/values/config.xml} as the
 * {@code apn_source_service}.
 * </p>
 *
 * @hide
 */
@SystemApi
public abstract class ApnService extends Service {

    private static final String LOG_TAG = "ApnService";

    private final IApnSourceService.Stub mBinder = new IApnSourceService.Stub() {
        /**
         * Retreive APNs for the default slot index.
         */
        @Override
        public ContentValues[] getApns(int subId) {
            try {
                List<ContentValues> apns = ApnService.this.onRestoreApns(subId);
                return apns.toArray(new ContentValues[apns.size()]);
            } catch (Exception e) {
                Log.e(LOG_TAG, "Error in getApns for subId=" + subId + ": " + e.getMessage(), e);
                return null;
            }
        }
    };

    @Override
    @NonNull
    public IBinder onBind(@Nullable Intent intent) {
        return mBinder;
    }

    /**
     * Override this method to restore default user APNs with a carrier service instead of the
     * built in platform xml APNs list.
     * <p>
     * This method is called by the TelephonyProvider when the user requests restoring the default
     * APNs. It should return a list of ContentValues representing the default APNs for the given
     * subId.
     */
    @WorkerThread
    @NonNull
    public abstract List<ContentValues> onRestoreApns(int subId);
}
