/*
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

package android.service.notification;

import android.annotation.SdkConstant;
import android.annotation.TestApi;
import android.app.ActivityManager;
import android.app.INotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

/**
 * A service that provides conditions about boolean state.
 * <p>To extend this class, you must declare the service in your manifest file with
 * the {@link android.Manifest.permission#BIND_CONDITION_PROVIDER_SERVICE} permission
 * and include an intent filter with the {@link #SERVICE_INTERFACE} action. If you want users to be
 * able to create and update conditions for this service to monitor, include the
 * {@link #META_DATA_RULE_TYPE} and {@link #META_DATA_CONFIGURATION_ACTIVITY} tags and request the
 * {@link android.Manifest.permission#ACCESS_NOTIFICATION_POLICY} permission. For example:</p>
 * <pre>
 * &lt;service android:name=".MyConditionProvider"
 *          android:label="&#64;string/service_name"
 *          android:permission="android.permission.BIND_CONDITION_PROVIDER_SERVICE">
 *     &lt;intent-filter>
 *         &lt;action android:name="android.service.notification.ConditionProviderService" />
 *     &lt;/intent-filter>
 *     &lt;meta-data
 *               android:name="android.service.zen.automatic.ruleType"
 *               android:value="@string/my_condition_rule">
 *           &lt;/meta-data>
 *           &lt;meta-data
 *               android:name="android.service.zen.automatic.configurationActivity"
 *               android:value="com.my.package/.MyConditionConfigurationActivity">
 *           &lt;/meta-data>
 * &lt;/service></pre>
 *
 *  <p> Condition providers cannot be bound by the system on
 * {@link ActivityManager#isLowRamDevice() low ram} devices running Android Q (and below)</p>
 *
 * @deprecated Instead of using an automatically bound service, use
 * {@link android.app.NotificationManager#setAutomaticZenRuleState(String, Condition)} to tell the
 * system about the state of your rule. In order to maintain a link from
 * Settings to your rule configuration screens, provide a configuration activity that handles
 * {@link android.app.NotificationManager#ACTION_AUTOMATIC_ZEN_RULE} on your
 * {@link android.app.AutomaticZenRule} via
 * {@link android.app.AutomaticZenRule#setConfigurationActivity(ComponentName)}.
 */
@Deprecated
public abstract class ConditionProviderService extends Service {
    private final String TAG = ConditionProviderService.class.getSimpleName()
            + "[" + getClass().getSimpleName() + "]";

    private final H mHandler = new H();

    private Provider mProvider;
    private INotificationManager mNoMan;
    boolean mIsConnected;

    /**
     * The {@link Intent} that must be declared as handled by the service.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE
            = "android.service.notification.ConditionProviderService";

    /**
     * The name of the {@code meta-data} tag containing a localized name of the type of zen rules
     * provided by this service.
     *
     * @deprecated see {@link android.app.NotificationManager#META_DATA_AUTOMATIC_RULE_TYPE}.
     */
    @Deprecated
    public static final String META_DATA_RULE_TYPE = "android.service.zen.automatic.ruleType";

    /**
     * The name of the {@code meta-data} tag containing the {@link ComponentName} of an activity
     * that allows users to configure the conditions provided by this service.
     *
     * @deprecated see {@link android.app.NotificationManager#ACTION_AUTOMATIC_ZEN_RULE}.
     */
    @Deprecated
    public static final String META_DATA_CONFIGURATION_ACTIVITY =
            "android.service.zen.automatic.configurationActivity";

    /**
     * The name of the {@code meta-data} tag containing the maximum number of rule instances that
     * can be created for this rule type. Omit or enter a value <= 0 to allow unlimited instances.
     *
     * @deprecated see {@link android.app.NotificationManager#META_DATA_RULE_INSTANCE_LIMIT}.
     */
    @Deprecated
    public static final String META_DATA_RULE_INSTANCE_LIMIT =
            "android.service.zen.automatic.ruleInstanceLimit";

    /**
     * A String rule id extra passed to {@link #META_DATA_CONFIGURATION_ACTIVITY}.
     *
     * @deprecated see {@link android.app.NotificationManager#EXTRA_AUTOMATIC_RULE_ID}.
     */
    @Deprecated
    public static final String EXTRA_RULE_ID = "android.service.notification.extra.RULE_ID";

    /**
     * Called when this service is connected.
     */
    abstract public void onConnected();

    public void onRequestConditions(int relevance) {}

    /**
     * Called by the system when there is a new {@link Condition} to be managed by this provider.
     * @param conditionId the Uri describing the criteria of the condition.
     */
    abstract public void onSubscribe(Uri conditionId);

    /**
     * Called by the system when a {@link Condition} has been deleted.
     * @param conditionId the Uri describing the criteria of the deleted condition.
     */
    abstract public void onUnsubscribe(Uri conditionId);

    private final INotificationManager getNotificationInterface() {
        if (mNoMan == null) {
            mNoMan = INotificationManager.Stub.asInterface(
                    ServiceManager.getService(Context.NOTIFICATION_SERVICE));
        }
        return mNoMan;
    }

    /**
     * Request that the provider be rebound, after a previous call to (@link #requestUnbind).
     *
     * <p>This method will fail for providers that have not been granted the permission by the user.
     */
    public static final void requestRebind(ComponentName componentName) {
        INotificationManager noMan = INotificationManager.Stub.asInterface(
                ServiceManager.getService(Context.NOTIFICATION_SERVICE));
        try {
            noMan.requestBindProvider(componentName);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Request that the provider service be unbound.
     *
     * <p>This will no longer receive subscription updates and will not be able to update the
     * state of conditions until {@link #requestRebind(ComponentName)} is called.
     * The service will likely be killed by the system after this call.
     *
     * <p>The service should wait for the {@link #onConnected()} event before performing this
     * operation.
     */
    public final void requestUnbind() {
        INotificationManager noMan = getNotificationInterface();
        try {
            noMan.requestUnbindProvider(mProvider);
            // Disable future messages.
            mIsConnected = false;
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Informs the notification manager that the state of a Condition has changed. Use this method
     * to put the system into Do Not Disturb mode or request that it exits Do Not Disturb mode. This
     * call will be ignored unless there is an enabled {@link android.app.AutomaticZenRule} owned by
     * service that has an {@link android.app.AutomaticZenRule#getConditionId()} equal to this
     * {@link Condition#id}.
     * @param condition the condition that has changed.
     *
     * @deprecated see
     * {@link android.app.NotificationManager#setAutomaticZenRuleState(String, Condition)}.
     */
    @Deprecated
    public final void notifyCondition(Condition condition) {
        if (condition == null) return;
        notifyConditions(new Condition[]{ condition });
    }

    /**
     * Informs the notification manager that the state of one or more Conditions has changed. See
     * {@link #notifyCondition(Condition)} for restrictions.
     * @param conditions the changed conditions.
     *
     * @deprecated see
     *       {@link android.app.NotificationManager#setAutomaticZenRuleState(String, Condition)}.
     */
    @Deprecated
    public final void notifyConditions(Condition... conditions) {
        if (!isBound() || conditions == null) return;
        try {
            getNotificationInterface().notifyConditions(getPackageName(), mProvider, conditions);
        } catch (android.os.RemoteException ex) {
            Log.v(TAG, "Unable to contact notification manager", ex);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (mProvider == null) {
            mProvider = new Provider();
        }
        return mProvider;
    }

    /**
     * @hide
     */
    @TestApi
    public boolean isBound() {
        if (!mIsConnected) {
            Log.w(TAG, "Condition provider service not yet bound.");
        }
        return mIsConnected;
    }

    private final class Provider extends IConditionProvider.Stub {
        @Override
        public void onConnected() {
            mIsConnected = true;
            mHandler.obtainMessage(H.ON_CONNECTED).sendToTarget();
        }

        @Override
        public void onSubscribe(Uri conditionId) {
            mHandler.obtainMessage(H.ON_SUBSCRIBE, conditionId).sendToTarget();
        }

        @Override
        public void onUnsubscribe(Uri conditionId) {
            mHandler.obtainMessage(H.ON_UNSUBSCRIBE, conditionId).sendToTarget();
        }
    }

    private final class H extends Handler {
        private static final int ON_CONNECTED = 1;
        private static final int ON_SUBSCRIBE = 3;
        private static final int ON_UNSUBSCRIBE = 4;

        @Override
        public void handleMessage(Message msg) {
            String name = null;
            if (!mIsConnected) {
                return;
            }
            try {
                switch(msg.what) {
                    case ON_CONNECTED:
                        name = "onConnected";
                        onConnected();
                        break;
                    case ON_SUBSCRIBE:
                        name = "onSubscribe";
                        onSubscribe((Uri)msg.obj);
                        break;
                    case ON_UNSUBSCRIBE:
                        name = "onUnsubscribe";
                        onUnsubscribe((Uri)msg.obj);
                        break;
                }
            } catch (Throwable t) {
                Log.w(TAG, "Error running " + name, t);
            }
        }
    }
}
