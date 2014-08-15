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
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.util.Slog;

/**
 * A service that notifies the system about whether it believes the environment of the device
 * to be trusted.
 *
 * <p>Trust agents may only be provided by the platform. It is expected that there is only
 * one trust agent installed on the platform. In the event there is more than one,
 * either trust agent can enable trust.
 * </p>
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
    private static final boolean DEBUG = false;

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

    /**
     * A white list of features that the given trust agent should support when otherwise disabled
     * by device policy.
     */
    public static final String KEY_FEATURES = "trust_agent_features";

    private static final int MSG_UNLOCK_ATTEMPT = 1;
    private static final int MSG_SET_TRUST_AGENT_FEATURES_ENABLED = 2;
    private static final int MSG_TRUST_TIMEOUT = 3;

    private ITrustAgentServiceCallback mCallback;

    private Runnable mPendingGrantTrustTask;

    private boolean mManagingTrust;

    // Lock used to access mPendingGrantTrustTask and mCallback.
    private final Object mLock = new Object();

    private Handler mHandler = new Handler() {
        public void handleMessage(android.os.Message msg) {
            switch (msg.what) {
                case MSG_UNLOCK_ATTEMPT:
                    onUnlockAttempt(msg.arg1 != 0);
                    break;
                case MSG_SET_TRUST_AGENT_FEATURES_ENABLED:
                    Bundle features = msg.peekData();
                    IBinder token = (IBinder) msg.obj;
                    boolean result = onSetTrustAgentFeaturesEnabled(features);
                    try {
                        synchronized (mLock) {
                            mCallback.onSetTrustAgentFeaturesEnabledCompleted(result, token);
                        }
                    } catch (RemoteException e) {
                        onError("calling onSetTrustAgentFeaturesEnabledCompleted()");
                    }
                    break;
                case MSG_TRUST_TIMEOUT:
                    onTrustTimeout();
                    break;
            }
        }
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
     * Called after the user attempts to authenticate in keyguard with their device credentials,
     * such as pin, pattern or password.
     *
     * @param successful true if the user successfully completed the challenge.
     */
    public void onUnlockAttempt(boolean successful) {
    }

    /**
     * Called when the timeout provided by the agent expires.  Note that this may be called earlier
     * than requested by the agent if the trust timeout is adjusted by the system or
     * {@link DevicePolicyManager}.  The agent is expected to re-evaluate the trust state and only
     * call {@link #grantTrust(CharSequence, long, boolean)} if the trust state should be
     * continued.
     */
    public void onTrustTimeout() {
    }

    private void onError(String msg) {
        Slog.v(TAG, "Remote exception while " + msg);
    }

    /**
     * Called when device policy wants to restrict features in the agent in response to
     * {@link DevicePolicyManager#setTrustAgentFeaturesEnabled(ComponentName, ComponentName, java.util.List) }.
     * Agents that support this feature should overload this method and return 'true'.
     *
     * The list of options can be obtained by calling
     * options.getStringArrayList({@link #KEY_FEATURES}). Presence of a feature string in the list
     * means it should be enabled ("white-listed"). Absence of the feature means it should be
     * disabled. An empty list means all features should be disabled.
     *
     * This function is only called if {@link DevicePolicyManager#KEYGUARD_DISABLE_TRUST_AGENTS} is
     * set.
     *
     * @param options Option feature bundle.
     * @return true if the {@link TrustAgentService} supports this feature.
     */
    public boolean onSetTrustAgentFeaturesEnabled(Bundle options) {
        return false;
    }

    /**
     * Call to grant trust on the device.
     *
     * @param message describes why the device is trusted, e.g. "Trusted by location".
     * @param durationMs amount of time in milliseconds to keep the device in a trusted state.
     *    Trust for this agent will automatically be revoked when the timeout expires unless
     *    extended by a subsequent call to this function. The timeout is measured from the
     *    invocation of this function as dictated by {@link SystemClock#elapsedRealtime())}.
     *    For security reasons, the value should be no larger than necessary.
     *    The value may be adjusted by the system as necessary to comply with a policy controlled
     *    by the system or {@link DevicePolicyManager} restrictions. See {@link #onTrustTimeout()}
     *    for determining when trust expires.
     * @param initiatedByUser this is a hint to the system that trust is being granted as the
     *    direct result of user action - such as solving a security challenge. The hint is used
     *    by the system to optimize the experience. Behavior may vary by device and release, so
     *    one should only set this parameter if it meets the above criteria rather than relying on
     *    the behavior of any particular device or release.
     * @throws IllegalStateException if the agent is not currently managing trust.
     */
    public final void grantTrust(
            final CharSequence message, final long durationMs, final boolean initiatedByUser) {
        synchronized (mLock) {
            if (!mManagingTrust) {
                throw new IllegalStateException("Cannot grant trust if agent is not managing trust."
                        + " Call setManagingTrust(true) first.");
            }
            if (mCallback != null) {
                try {
                    mCallback.grantTrust(message.toString(), durationMs, initiatedByUser);
                } catch (RemoteException e) {
                    onError("calling enableTrust()");
                }
            } else {
                // Remember trust has been granted so we can effectively grant it once the service
                // is bound.
                mPendingGrantTrustTask = new Runnable() {
                    @Override
                    public void run() {
                        grantTrust(message, durationMs, initiatedByUser);
                    }
                };
            }
        }
    }

    /**
     * Call to revoke trust on the device.
     */
    public final void revokeTrust() {
        synchronized (mLock) {
            if (mPendingGrantTrustTask != null) {
                mPendingGrantTrustTask = null;
            }
            if (mCallback != null) {
                try {
                    mCallback.revokeTrust();
                } catch (RemoteException e) {
                    onError("calling revokeTrust()");
                }
            }
        }
    }

    /**
     * Call to notify the system if the agent is ready to manage trust.
     *
     * This property is not persistent across recreating the service and defaults to false.
     * Therefore this method is typically called when initializing the agent in {@link #onCreate}.
     *
     * @param managingTrust indicates if the agent would like to manage trust.
     */
    public final void setManagingTrust(boolean managingTrust) {
        synchronized (mLock) {
            if (mManagingTrust != managingTrust) {
                mManagingTrust = managingTrust;
                if (mCallback != null) {
                    try {
                        mCallback.setManagingTrust(managingTrust);
                    } catch (RemoteException e) {
                        onError("calling setManagingTrust()");
                    }
                }
            }
        }
    }

    @Override
    public final IBinder onBind(Intent intent) {
        if (DEBUG) Slog.v(TAG, "onBind() intent = " + intent);
        return new TrustAgentServiceWrapper();
    }

    private final class TrustAgentServiceWrapper extends ITrustAgentService.Stub {
        @Override /* Binder API */
        public void onUnlockAttempt(boolean successful) {
            mHandler.obtainMessage(MSG_UNLOCK_ATTEMPT, successful ? 1 : 0, 0).sendToTarget();
        }

        @Override /* Binder API */
        public void onTrustTimeout() {
            mHandler.sendEmptyMessage(MSG_TRUST_TIMEOUT);
        }

        @Override /* Binder API */
        public void setCallback(ITrustAgentServiceCallback callback) {
            synchronized (mLock) {
                mCallback = callback;
                // The managingTrust property is false implicitly on the server-side, so we only
                // need to set it here if the agent has decided to manage trust.
                if (mManagingTrust) {
                    try {
                        mCallback.setManagingTrust(mManagingTrust);
                    } catch (RemoteException e ) {
                        onError("calling setManagingTrust()");
                    }
                }
                if (mPendingGrantTrustTask != null) {
                    mPendingGrantTrustTask.run();
                    mPendingGrantTrustTask = null;
                }
            }
        }

        @Override /* Binder API */
        public void setTrustAgentFeaturesEnabled(Bundle features, IBinder token) {
            Message msg = mHandler.obtainMessage(MSG_SET_TRUST_AGENT_FEATURES_ENABLED, token);
            msg.setData(features);
            msg.sendToTarget();
        }
    }

}
