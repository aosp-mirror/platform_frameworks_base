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
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;


import com.android.internal.util.function.pooled.PooledLambda;

import java.util.Objects;

/**
 * Service to be implemented by apps that manage a companion device.
 *
 * System will keep this service bound whenever an associated device is nearby for Bluetooth
 * devices or companion app manages the connectivity and reports disappeared, ensuring app stays
 * alive
 *
 * An app must be {@link CompanionDeviceManager#associate associated} with at leas one device,
 * before it can take advantage of this service.
 *
 * You must declare this service in your manifest with an
 * intent-filter action of {@link #SERVICE_INTERFACE} and
 * permission of {@link android.Manifest.permission#BIND_COMPANION_DEVICE_SERVICE}
 *
 * <p>If you want to declare more than one of these services, you must declare the meta-data in the
 * service of your manifest with the corresponding name and value to true to indicate the
 * primary service.
 * Only the primary one will get the callback from
 * {@link #onDeviceAppeared(AssociationInfo associationInfo)}.</p>
 *
 * Example:
 * <meta-data
 *   android:name="primary"
 *   android:value="true" />
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
            return mRemote;
        }
        Log.w(LOG_TAG,
                "Tried to bind to wrong intent (should be " + SERVICE_INTERFACE + "): " + intent);
        return null;
    }

    class Stub extends ICompanionDeviceService.Stub {

        @Override
        public void onDeviceAppeared(AssociationInfo associationInfo) {
            Handler.getMain().post(
                    () -> CompanionDeviceService.this.onDeviceAppeared(associationInfo));
        }

        @Override
        public void onDeviceDisappeared(AssociationInfo associationInfo) {
            Handler.getMain().post(
                    () -> CompanionDeviceService.this.onDeviceDisappeared(associationInfo));
        }

        public void onDispatchMessage(int messageId, int associationId, @NonNull byte[] message) {
            Handler.getMain().sendMessage(PooledLambda.obtainMessage(
                    CompanionDeviceService::onDispatchMessage,
                    CompanionDeviceService.this, messageId, associationId, message));
        }

        public final void dispatchMessage(int messageId, int associationId,
                @NonNull byte[] message) {
            Handler.getMain().sendMessage(PooledLambda.obtainMessage(
                    CompanionDeviceService::dispatchMessage,
                    CompanionDeviceService.this, messageId, associationId, message));
        }
    }
}
