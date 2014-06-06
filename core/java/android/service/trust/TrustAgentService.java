/**
 * Copyright (C) 2014 The Android Open Source Project
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

package android.service.trust;

import android.Manifest;
import android.annotation.SystemApi;
import android.annotation.SdkConstant;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.util.Slog;

/**
 * A service that notifies the system about whether it believes the environment of the device
 * to be trusted.
 *
 * <p>Trust agents may only be provided by the platform.</p>
 *
 * <p>To extend this class, you must declare the service in your manifest file with
 * the {@link android.Manifest.permission#BIND_TRUST_AGENT} permission
 * and include an intent filter with the {@link #SERVICE_INTERFACE} action. For example:</p>
 * <pre>
 * &lt;service android:name=".TrustAgent"
 *          android:label="&#64;string/service_name"
 *          android:permission="android.permission.BIND_TRUST_AGENT">
 *     &lt;intent-filter>
 *         &lt;action android:name="android.service.trust.TrustAgentService" />
 *     &lt;/intent-filter>
 *     &lt;meta-data android:name="android.service.trust.trustagent"
 *          android:value="&#64;xml/trust_agent" />
 * &lt;/service></pre>
 *
 * <p>The associated meta-data file can specify an activity that is accessible through Settings
 * and should allow configuring the trust agent, as defined in
 * {@link android.R.styleable#TrustAgent}. For example:</p>
 *
 * <pre>
 * &lt;trust-agent xmlns:android="http://schemas.android.com/apk/res/android"
 *          android:settingsActivity=".TrustAgentSettings" /></pre>
 *
 * @hide
 */
@SystemApi
public class TrustAgentService extends Service {
    private final String TAG = TrustAgentService.class.getSimpleName() +
            "[" + getClass().getSimpleName() + "]";

    /**
     * The {@link Intent} that must be declared as handled by the service.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE
            = "android.service.trust.TrustAgentService";

    /**
     * The name of the {@code meta-data} tag pointing to additional configuration of the trust
     * agent.
     */
    public static final String TRUST_AGENT_META_DATA = "android.service.trust.trustagent";

    private static final int MSG_UNLOCK_ATTEMPT = 1;

    private static final boolean DEBUG = false;

    private ITrustAgentServiceCallback mCallback;

    private Handler mHandler = new Handler() {
        public void handleMessage(android.os.Message msg) {
            switch (msg.what) {
                case MSG_UNLOCK_ATTEMPT:
                    onUnlockAttempt(msg.arg1 != 0);
                    break;
            }
        };
    };

    @Override
    public void onCreate() {
        super.onCreate();
        ComponentName component = new ComponentName(this, getClass());
        try {
            ServiceInfo serviceInfo = getPackageManager().getServiceInfo(component, 0 /* flags */);
            if (!Manifest.permission.BIND_TRUST_AGENT.equals(serviceInfo.permission)) {
                throw new IllegalStateException(component.flattenToShortString()
                        + " is not declared with the permission "
                        + "\"" + Manifest.permission.BIND_TRUST_AGENT + "\"");
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Can't get ServiceInfo for " + component.toShortString());
        }
    }

    /**
     * Called when the user attempted to authenticate on the device.
     *
     * @param successful true if the attempt succeeded
     */
    public void onUnlockAttempt(boolean successful) {
    }

    private void onError(String msg) {
        Slog.v(TAG, "Remote exception while " + msg);
    }

    /**
     * Call to grant trust on the device.
     *
     * @param message describes why the device is trusted, e.g. "Trusted by location".
     * @param durationMs amount of time in milliseconds to keep the device in a trusted state. Trust
     *                   for this agent will automatically be revoked when the timeout expires.
     * @param initiatedByUser indicates that the user has explicitly initiated an action that proves
     *                        the user is about to use the device.
     */
    public final void grantTrust(CharSequence message, long durationMs, boolean initiatedByUser) {
        if (mCallback != null) {
            try {
                mCallback.grantTrust(message.toString(), durationMs, initiatedByUser);
            } catch (RemoteException e) {
                onError("calling enableTrust()");
            }
        }
    }

    /**
     * Call to revoke trust on the device.
     */
    public final void revokeTrust() {
        if (mCallback != null) {
            try {
                mCallback.revokeTrust();
            } catch (RemoteException e) {
                onError("calling revokeTrust()");
            }
        }
    }

    @Override
    public final IBinder onBind(Intent intent) {
        if (DEBUG) Slog.v(TAG, "onBind() intent = " + intent);
        return new TrustAgentServiceWrapper();
    }

    private final class TrustAgentServiceWrapper extends ITrustAgentService.Stub {
        @Override
        public void onUnlockAttempt(boolean successful) {
            mHandler.obtainMessage(MSG_UNLOCK_ATTEMPT, successful ? 1 : 0, 0)
                    .sendToTarget();
        }

        public void setCallback(ITrustAgentServiceCallback callback) {
            mCallback = callback;
        }
    }

}
