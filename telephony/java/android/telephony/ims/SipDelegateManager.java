/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.telephony.ims;

import android.Manifest;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.telephony.CarrierConfigManager;
import android.telephony.TelephonyFrameworkInitializer;
import android.telephony.ims.aidl.IImsRcsController;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Manages the creation and destruction of SipDelegates, which allow an IMS application to forward
 * SIP messages for the purposes of providing a single IMS registration to the carrier's IMS network
 * from multiple sources.
 * @hide
 */
@SystemApi
public class SipDelegateManager {

    private final Context mContext;
    private final int mSubId;

    /**
     * Only visible for testing. To instantiate an instance of this class, please use
     * {@link ImsManager#getSipDelegateManager(int)}.
     * @hide
     */
    @VisibleForTesting
    public SipDelegateManager(Context context, int subId) {
        mContext = context;
        mSubId = subId;
    }

    /**
     * Determines if creating SIP delegates are supported for the subscription specified.
     * <p>
     * If SIP delegates are not supported on this device or the carrier associated with this
     * subscription, creating a SIP delegate will always fail, as this feature is not supported.
     * @return true if this device supports creating a SIP delegate and the carrier associated with
     * this subscription supports single registration, false if creating SIP delegates is not
     * supported.
     * @throws ImsException If the remote ImsService is not available for any reason or the
     * subscription associated with this instance is no longer active. See
     * {@link ImsException#getCode()} for more information.
     *
     * @see CarrierConfigManager.Ims#KEY_IMS_SINGLE_REGISTRATION_REQUIRED_BOOL
     */
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public boolean isSupported() throws ImsException {
        try {
            IImsRcsController controller = getIImsRcsController();
            if (controller == null) {
                throw new ImsException("Telephony server is down",
                        ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
            }
            return controller.isSipDelegateSupported(mSubId);
        } catch (ServiceSpecificException e) {
            throw new ImsException(e.getMessage(), e.errorCode);
        } catch (RemoteException e) {
            throw new ImsException(e.getMessage(),
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    private IImsRcsController getIImsRcsController() {
        IBinder binder = TelephonyFrameworkInitializer
                .getTelephonyServiceManager()
                .getTelephonyImsServiceRegisterer()
                .get();
        return IImsRcsController.Stub.asInterface(binder);
    }
}
