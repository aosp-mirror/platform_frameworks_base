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

package android.telephony.ims.feature;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;

import com.android.internal.telephony.flags.Flags;

/**
 * A callback class used to receive the result of {@link MmTelFeature#startImsTrafficSession}.
 * @hide
 */
@FlaggedApi(Flags.FLAG_SUPPORT_IMS_MMTEL_INTERFACE)
@SystemApi
public interface ImsTrafficSessionCallback {

    /** The modem is ready to process the IMS traffic. */
    void onReady();

    /**
     * Notifies that any IMS traffic can not be sent to the network due to the provided cellular
     * network failure. IMS service shall call {@link MmTelFeature#stopImsTrafficSession()}
     * when receiving this callback.
     *
     * @param info The information of the failure.
     */
    void onError(@NonNull ConnectionFailureInfo info);
}
