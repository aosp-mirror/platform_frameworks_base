/*
 * Copyright (C) 2017 The Android Open Source Project
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
package android.service.euicc;

import android.annotation.CallSuper;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.telephony.euicc.DownloadableSubscription;

/**
 * Service interface linking the system with an eUICC local profile assistant (LPA) application.
 *
 * <p>An LPA consists of two separate components (which may both be implemented in the same APK):
 * the LPA backend, and the LPA UI or LUI.
 *
 * <p>To implement the LPA backend, you must extend this class and declare this service in your
 * manifest file. The service must require the
 * {@link android.Manifest.permission#BIND_EUICC_SERVICE} permission and include an intent filter
 * with the {@link #EUICC_SERVICE_INTERFACE} action. The priority of the intent filter must be set
 * to a non-zero value in case multiple implementations are present on the device. For example:
 *
 * <pre>{@code
 * <service android:name=".MyEuiccService"
 *          android:permission="android.permission.BIND_EUICC_SERVICE">
 *     <intent-filter android:priority="100">
 *         <action android:name="android.service.euicc.EuiccService" />
 *     </intent-filter>
 * </service>
 * }</pre>
 *
 * <p>To implement the LUI, you must provide an activity for the following actions:
 *
 * <ul>
 * <li>{@link #ACTION_MANAGE_EMBEDDED_SUBSCRIPTIONS}
 * <li>{@link #ACTION_PROVISION_EMBEDDED_SUBSCRIPTION}
 * </ul>
 *
 * <p>As with the service, each activity must require the
 * {@link android.Manifest.permission#BIND_EUICC_SERVICE} permission. Each should have an intent
 * filter with the appropriate action, the {@link #CATEGORY_EUICC_UI} category, and a non-zero
 * priority.
 *
 * TODO(b/35851809): Make this a SystemApi.
 * @hide
 */
public abstract class EuiccService extends Service {
    /** Action which must be included in this service's intent filter. */
    public static final String EUICC_SERVICE_INTERFACE = "android.service.euicc.EuiccService";

    /** Category which must be defined to all UI actions, for efficient lookup. */
    public static final String CATEGORY_EUICC_UI = "android.service.euicc.category.EUICC_UI";

    // LUI actions. These are passthroughs of the corresponding EuiccManager actions.

    /** @see android.telephony.euicc.EuiccManager#ACTION_MANAGE_EMBEDDED_SUBSCRIPTIONS */
    public static final String ACTION_MANAGE_EMBEDDED_SUBSCRIPTIONS =
            "android.service.euicc.action.MANAGE_EMBEDDED_SUBSCRIPTIONS";
    /** @see android.telephony.euicc.EuiccManager#ACTION_PROVISION_EMBEDDED_SUBSCRIPTION */
    public static final String ACTION_PROVISION_EMBEDDED_SUBSCRIPTION =
            "android.service.euicc.action.PROVISION_EMBEDDED_SUBSCRIPTION";

    private final IEuiccService.Stub mStubWrapper;

    public EuiccService() {
        mStubWrapper = new IEuiccServiceWrapper();
    }

    /**
     * If overriding this method, call through to the super method for any unknown actions.
     * {@inheritDoc}
     */
    @Override
    @CallSuper
    public IBinder onBind(Intent intent) {
        return mStubWrapper;
    }

    /**
     * Return the EID of the eUICC.
     *
     * @param slotId ID of the SIM slot being queried. This is currently not populated but is here
     *     to future-proof the APIs.
     * @return the EID.
     * @see android.telephony.euicc.EuiccManager#getEid
     */
    // TODO(b/36260308): Update doc when we have multi-SIM support.
    public abstract String onGetEid(int slotId);

    /**
     * Populate {@link DownloadableSubscription} metadata for the given downloadable subscription.
     *
     * @param slotId ID of the SIM slot to use when starting the download. This is currently not
     *     populated but is here to future-proof the APIs.
     * @param subscription A subscription whose metadata needs to be populated.
     * @return The result of the operation.
     * @see android.telephony.euicc.EuiccManager#getDownloadableSubscriptionMetadata
     */
    public abstract GetDownloadableSubscriptionMetadataResult getDownloadableSubscriptionMetadata(
            int slotId, DownloadableSubscription subscription);

    /**
     * Download the given subscription.
     *
     * @param slotId ID of the SIM slot onto which the subscription should be downloaded. This is
     *     currently not populated but is here to future-proof the APIs.
     * @param subscription The subscription to download.
     * @param switchAfterDownload If true, the subscription should be enabled upon successful
     *     download.
     * @return the result of the download operation.
     * @see android.telephony.euicc.EuiccManager#downloadSubscription
     */
    public abstract DownloadResult downloadSubscription(int slotId,
            DownloadableSubscription subscription, boolean switchAfterDownload);

    /**
     * Wrapper around IEuiccService that forwards calls to implementations of {@link EuiccService}.
     */
    private class IEuiccServiceWrapper extends IEuiccService.Stub {
        @Override
        public void downloadSubscription(int slotId, DownloadableSubscription subscription,
                boolean switchAfterDownload, IDownloadSubscriptionCallback callback) {
            DownloadResult result = EuiccService.this.downloadSubscription(
                    slotId, subscription, switchAfterDownload);
            try {
                callback.onComplete(result);
            } catch (RemoteException e) {
                // Can't communicate with the phone process; ignore.
            }
        }

        @Override
        public void getEid(int slotId, IGetEidCallback callback) {
            String eid = EuiccService.this.onGetEid(slotId);
            try {
                callback.onSuccess(eid);
            } catch (RemoteException e) {
                // Can't communicate with the phone process; ignore.
            }
        }

        @Override
        public void getDownloadableSubscriptionMetadata(int slotId,
                DownloadableSubscription subscription,
                IGetDownloadableSubscriptionMetadataCallback callback) {
            GetDownloadableSubscriptionMetadataResult result =
                    EuiccService.this.getDownloadableSubscriptionMetadata(slotId, subscription);
            try {
                callback.onComplete(result);
            } catch (RemoteException e) {
                // Can't communicate with the phone process; ignore.
            }
        }
    }
}
