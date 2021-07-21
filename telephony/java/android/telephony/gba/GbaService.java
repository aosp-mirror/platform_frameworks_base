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

package android.telephony.gba;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.telephony.Annotation.UiccAppTypeExt;
import android.telephony.IBootstrapAuthenticationCallback;
import android.telephony.TelephonyManager;
import android.telephony.TelephonyManager.AuthenticationFailureReason;
import android.util.Log;
import android.util.SparseArray;

/**
  * Base class for GBA Service. Any implementation which wants to provide
  * GBA service must extend this class.
  *
  * <p>Note that the application to implement the service must declare to use
  * the permission {@link android.Manifest.permission#BIND_GBA_SERVICE},
  * and filter the intent of {@link #SERVICE_INTERFACE}.
  * The manifest of the service must follow the format below:
  *
  * <p>...
  * <service
  *     android:name=".EgGbaService"
  *     android:directBootAware="true"
  *     android:permission="android.permission.BIND_GBA_SERVICE" >
  *     ...
  *     <intent-filter>
  *         <action android:name="android.telephony.gba.GbaService"/>
  *     </intent-filter>
  * </service>
  * ...
  *
  * <p>The service should also be file-based encryption (FBE) aware.
  * {@hide}
  */
@SystemApi
public class GbaService extends Service  {
    private static final boolean DBG = Build.IS_DEBUGGABLE;
    private static final String TAG = "GbaService";

    /**
    * The intent must be defined as an intent-filter in the
    * AndroidManifest of the GbaService.
    */
    public static final String SERVICE_INTERFACE = "android.telephony.gba.GbaService";

    private static final int EVENT_GBA_AUTH_REQUEST = 1;

    private final HandlerThread mHandlerThread;
    private final GbaServiceHandler mHandler;

    private final SparseArray<IBootstrapAuthenticationCallback> mCallbacks = new SparseArray<>();
    private final IGbaServiceWrapper mBinder = new IGbaServiceWrapper();

    /**
     * Default constructor.
     */
    public GbaService() {
        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();

        mHandler = new GbaServiceHandler(mHandlerThread.getLooper());
        Log.d(TAG, "GBA service created");
    }

    private class GbaServiceHandler extends Handler {

        GbaServiceHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case EVENT_GBA_AUTH_REQUEST:
                    GbaAuthRequest req = (GbaAuthRequest) msg.obj;
                    synchronized (mCallbacks) {
                        mCallbacks.put(req.getToken(), req.getCallback());
                    }
                    onAuthenticationRequest(req.getSubId(), req.getToken(), req.getAppType(),
                            req.getNafUrl(), req.getSecurityProtocol(), req.isForceBootStrapping());
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * Called by the platform when a GBA authentication request is received from
     * {@link TelephonyManager#bootstrapAuthenticationRequest} to get the KsNAF for
     * a particular NAF.
     *
     * @param subscriptionId the ICC card to be used for the bootstrapping authentication.
     * @param token the identification of the authentication request.
     * @param appType icc application type, like {@link #APPTYPE_USIM} or {@link
     * #APPTYPE_ISIM} or {@link#APPTYPE_UNKNOWN}
     * @param nafUrl Network Application Function(NAF) fully qualified domain name and
     * the selected GBA mode. It shall contain two parts delimited by "@" sign. The first
     * part is the constant string "3GPP-bootstrapping" (GBA_ME),
     * "3GPP-bootstrapping-uicc" (GBA_ U), or "3GPP-bootstrapping-digest" (GBA_Digest),
     * and the latter part shall be the FQDN of the NAF (e.g.
     * "3GPP-bootstrapping@naf1.operator.com" or "3GPP-bootstrapping-uicc@naf1.operator.com",
     * or "3GPP-bootstrapping-digest@naf1.operator.com").
     * @param securityProtocol Security protocol identifier between UE and NAF.  See
     * 3GPP TS 33.220 Annex H. Application can use
     * {@link UaSecurityProtocolIdentifier#createDefaultUaSpId},
     * {@link UaSecurityProtocolIdentifier#create3GppUaSpId},
     * to create the ua security protocol identifier as needed
     * @param forceBootStrapping true=force bootstrapping, false=do not force
     * bootstrapping. Bootstrapping shouldn't be forced unless the application sees
     * authentication errors from the server.
     * Response is returned via {@link TelephonyManager#BootstrapAuthenticationCallback}
     * along with the token to identify the request.
     *
     * <p>Note that this is not called in the main thread.
     */
    public void onAuthenticationRequest(int subscriptionId, int token, @UiccAppTypeExt int appType,
            @NonNull Uri nafUrl, @NonNull byte[] securityProtocol, boolean forceBootStrapping) {
        //Default implementation should be overridden by vendor Gba Service. Vendor Gba Service
        //should handle the gba bootstrap authentication request, and call reportKeysAvailable or
        //reportAuthenticationFailure to notify the caller accordingly.
        reportAuthenticationFailure(
                token, TelephonyManager.GBA_FAILURE_REASON_FEATURE_NOT_SUPPORTED);
    }

    /**
     * Called by {@link GbaService} when the previously requested GBA keys are available
     * (@see onAuthenticationRequest())
     *
     * @param token unique identifier of the request.
     * @param gbaKey KsNaf Response.
     * @param transactionId Bootstrapping Transaction ID.
     * @throws RuntimeException when there is remote failure of callback.
     */
    public final void reportKeysAvailable(int token, @NonNull byte[] gbaKey,
            @NonNull String transactionId) throws RuntimeException {
        IBootstrapAuthenticationCallback cb = null;
        synchronized (mCallbacks) {
            cb = mCallbacks.get(token);
            mCallbacks.remove(token);
        }
        if (cb != null) {
            try {
                cb.onKeysAvailable(token, gbaKey, transactionId);
            } catch (RemoteException exception) {
                throw exception.rethrowAsRuntimeException();
            }
        }
    }

    /**
     * Invoked when the previously requested GBA key authentication failed
     * (@see onAuthenticationRequest())
     *
     * @param token unique identifier of the request.
     * @param reason The reason for the authentication failure.
     * @throws RuntimeException when there is remote failure of callback.
     */
    public final void reportAuthenticationFailure(int token,
            @AuthenticationFailureReason int reason) throws RuntimeException {
        IBootstrapAuthenticationCallback cb = null;
        synchronized (mCallbacks) {
            cb = mCallbacks.get(token);
            mCallbacks.remove(token);
        }
        if (cb != null) {
            try {
                cb.onAuthenticationFailure(token, reason);
            } catch (RemoteException exception) {
                throw exception.rethrowAsRuntimeException();
            }
        }
    }

    /** @hide */
    @Override
    public IBinder onBind(Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            Log.d(TAG, "GbaService Bound.");
            return mBinder;
        }
        return null;
    }

    /** @hide */
    @Override
    public void onDestroy() {
        mHandlerThread.quit();
        super.onDestroy();
    }

    private class IGbaServiceWrapper extends IGbaService.Stub {
        @Override
        public void authenticationRequest(GbaAuthRequest request) {
            if (DBG) Log.d(TAG, "receive request: " + request);
            mHandler.obtainMessage(EVENT_GBA_AUTH_REQUEST, request).sendToTarget();
        }
    }
}
