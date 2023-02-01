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


package android.companion;

import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.TestApi;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import java.util.Objects;

/**
 * A service that receives calls from the system when the associated companion device appears
 * nearby or is connected, as well as when the device is no longer "present" or connected.
 * See {@link #onDeviceAppeared(AssociationInfo)}/{@link #onDeviceDisappeared(AssociationInfo)}.
 *
 * <p>
 * Companion applications must create a service that {@code extends}
 * {@link CompanionDeviceService}, and declare it in their AndroidManifest.xml with the
 * "android.permission.BIND_COMPANION_DEVICE_SERVICE" permission
 * (see {@link android.Manifest.permission#BIND_COMPANION_DEVICE_SERVICE}),
 * as well as add an intent filter for the "android.companion.CompanionDeviceService" action
 * (see {@link #SERVICE_INTERFACE}).
 *
 * <p>
 * Following is an example of such declaration:
 * <pre>{@code
 * <service
 *        android:name=".CompanionService"
 *        android:label="@string/service_name"
 *        android:exported="true"
 *        android:permission="android.permission.BIND_COMPANION_DEVICE_SERVICE">
 *    <intent-filter>
 *        <action android:name="android.companion.CompanionDeviceService" />
 *    </intent-filter>
 * </service>
 * }</pre>
 *
 * <p>
 * If the companion application has requested observing device presence (see
 * {@link CompanionDeviceManager#startObservingDevicePresence(String)}) the system will
 * <a href="https://developer.android.com/guide/components/bound-services"> bind the service</a>
 * when it detects the device nearby (for BLE devices) or when the device is connected
 * (for Bluetooth devices).
 *
 * <p>
 * The system binding {@link CompanionDeviceService} elevates the priority of the process that
 * the service is running in, and thus may prevent
 * <a href="https://developer.android.com/topic/performance/memory-management#low-memory_killer">
 * the Low-memory killer</a> from killing the process at expense of other processes with lower
 * priority.
 *
 * <p>
 * It is possible for an application to declare multiple {@link CompanionDeviceService}-s.
 * In such case, the system will bind all declared services, but will deliver
 * {@link #onDeviceAppeared(AssociationInfo)} and {@link #onDeviceDisappeared(AssociationInfo)}
 * only to one "primary" services.
 * Applications that declare multiple {@link CompanionDeviceService}-s should indicate the "primary"
 * service using "android.companion.PROPERTY_PRIMARY_COMPANION_DEVICE_SERVICE" service level
 * property.
 * <pre>{@code
 * <property
 *       android:name="android.companion.PROPERTY_PRIMARY_COMPANION_DEVICE_SERVICE"
 *       android:value="true" />
 * }</pre>
 *
 * <p>
 * If the application declares multiple {@link CompanionDeviceService}-s, but does not indicate
 * the "primary" one, the system will pick one of the declared services to use as "primary".
 *
 * <p>
 * If the application declares multiple "primary" {@link CompanionDeviceService}-s, the system
 * will pick single one of them to use as "primary".
 */
public abstract class CompanionDeviceService extends Service {

    private static final String LOG_TAG = "CompanionDeviceService";

    /**
     * An intent action for a service to be bound whenever this app's companion device(s)
     * are nearby.
     *
     * <p>The app will be kept alive for as long as the device is nearby or companion app reports
     * appeared.
     * If the app is not running at the time device gets connected, the app will be woken up.</p>
     *
     * <p>Shortly after the device goes out of range or the companion app reports disappeared,
     * the service will be unbound, and the app will be eligible for cleanup, unless any other
     * user-visible components are running.</p>
     *
     * If running in background is not essential for the devices that this app can manage,
     * app should avoid declaring this service.</p>
     *
     * <p>The service must also require permission
     * {@link android.Manifest.permission#BIND_COMPANION_DEVICE_SERVICE}</p>
     */
    public static final String SERVICE_INTERFACE = "android.companion.CompanionDeviceService";

    private final Stub mRemote = new Stub();

    /**
     * Called by system whenever a device associated with this app is available.
     *
     * @param address the MAC address of the device
     * @deprecated please override {@link #onDeviceAppeared(AssociationInfo)} instead.
     */
    @Deprecated
    @MainThread
    public void onDeviceAppeared(@NonNull String address) {
        // Do nothing. Companion apps can override this function.
    }

    /**
     * Called by system whenever a device associated with this app stops being available.
     *
     * Usually this means the device goes out of range or is turned off.
     *
     * @param address the MAC address of the device
     * @deprecated please override {@link #onDeviceDisappeared(AssociationInfo)} instead.
     */
    @Deprecated
    @MainThread
    public void onDeviceDisappeared(@NonNull String address) {
        // Do nothing. Companion apps can override this function.
    }

    /**
     * Called by system whenever the system dispatches a message to the app to send it to
     * an associated device.
     *
     * @param messageId system assigned id of the message to be sent
     * @param associationId association id of the associated device
     * @param message message to be sent
     *
     * @hide
     */
    @MainThread
    public void onDispatchMessage(int messageId, int associationId, @NonNull byte[] message) {
        // do nothing. Companion apps can override this function for system to send messages.
    }

    /**
     * App calls this method when there's a message received from an associated device,
     * which needs to be dispatched to system for processing.
     *
     * <p>Calling app must declare uses-permission
     * {@link android.Manifest.permission#DELIVER_COMPANION_MESSAGES}</p>
     *
     * @param messageId id of the message
     * @param associationId id of the associated device
     * @param message messaged received from the associated device
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.DELIVER_COMPANION_MESSAGES)
    public final void dispatchMessage(int messageId, int associationId, @NonNull byte[] message) {
        CompanionDeviceManager companionDeviceManager =
                getSystemService(CompanionDeviceManager.class);
        companionDeviceManager.dispatchMessage(messageId, associationId, message);
    }

    /**
     * Called by system whenever a device associated with this app is connected.
     *
     * @param associationInfo A record for the companion device.
     */
    @MainThread
    public void onDeviceAppeared(@NonNull AssociationInfo associationInfo) {
        if (!associationInfo.isSelfManaged()) {
            onDeviceAppeared(associationInfo.getDeviceMacAddressAsString());
        }
    }

    /**
     * Called by system whenever a device associated with this app is disconnected.
     *
     * @param associationInfo A record for the companion device.
     */
    @MainThread
    public void onDeviceDisappeared(@NonNull AssociationInfo associationInfo) {
        if (!associationInfo.isSelfManaged()) {
            onDeviceDisappeared(associationInfo.getDeviceMacAddressAsString());
        }
    }

    @Nullable
    @Override
    public final IBinder onBind(@NonNull Intent intent) {
        if (Objects.equals(intent.getAction(), SERVICE_INTERFACE)) {
            onBindCompanionDeviceService(intent);
            return mRemote;
        }
        Log.w(LOG_TAG,
                "Tried to bind to wrong intent (should be " + SERVICE_INTERFACE + "): " + intent);
        return null;
    }

    /**
     * Used to track the state of Binder connection in CTS tests.
     * @hide
     */
    @TestApi
    public void onBindCompanionDeviceService(@NonNull Intent intent) {
    }

    private class Stub extends ICompanionDeviceService.Stub {
        final Handler mMainHandler = Handler.getMain();
        final CompanionDeviceService mService = CompanionDeviceService.this;

        @Override
        public void onDeviceAppeared(AssociationInfo associationInfo) {
            mMainHandler.postAtFrontOfQueue(() -> mService.onDeviceAppeared(associationInfo));
        }

        @Override
        public void onDeviceDisappeared(AssociationInfo associationInfo) {
            mMainHandler.postAtFrontOfQueue(() -> mService.onDeviceDisappeared(associationInfo));
        }

        @Override
        public void onDispatchMessage(int messageId, int associationId, @NonNull byte[] message) {
            mMainHandler.postAtFrontOfQueue(
                    () -> mService.onDispatchMessage(messageId, associationId, message));
        }
    }
}
