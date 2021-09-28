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

package android.companion;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.app.Activity;
import android.app.Application;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.net.MacAddress;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService;
import android.util.ExceptionUtils;
import android.util.Log;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * System level service for managing companion devices
 *
 * See <a href="{@docRoot}guide/topics/connectivity/companion-device-pairing">this guide</a>
 * for a usage example.
 *
 * <p>To obtain an instance call {@link Context#getSystemService}({@link
 * Context#COMPANION_DEVICE_SERVICE}) Then, call {@link #associate(AssociationRequest,
 * Callback, Handler)} to initiate the flow of associating current package with a
 * device selected by user.</p>
 *
 * @see CompanionDeviceManager#associate
 * @see AssociationRequest
 */
@SystemService(Context.COMPANION_DEVICE_SERVICE)
public final class CompanionDeviceManager {

    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "CompanionDeviceManager";

    /**
     * A device, returned in the activity result of the {@link IntentSender} received in
     * {@link Callback#onDeviceFound}
     *
     * Type is:
     * <ul>
     *     <li>for classic Bluetooth - {@link android.bluetooth.BluetoothDevice}</li>
     *     <li>for Bluetooth LE - {@link android.bluetooth.le.ScanResult}</li>
     *     <li>for WiFi - {@link android.net.wifi.ScanResult}</li>
     * </ul>
     */
    public static final String EXTRA_DEVICE = "android.companion.extra.DEVICE";

    /**
     * The package name of the companion device discovery component.
     *
     * @hide
     */
    public static final String COMPANION_DEVICE_DISCOVERY_PACKAGE_NAME =
            "com.android.companiondevicemanager";

    /**
     * A callback to receive once at least one suitable device is found, or the search failed
     * (e.g. timed out)
     */
    public abstract static class Callback {

        /**
         * Called once at least one suitable device is found
         *
         * @param chooserLauncher a {@link IntentSender} to launch the UI for user to select a
         *                        device
         */
        public abstract void onDeviceFound(IntentSender chooserLauncher);

        /**
         * Called if there was an error looking for device(s)
         *
         * @param error the cause of the error
         */
        public abstract void onFailure(CharSequence error);
    }

    private final ICompanionDeviceManager mService;
    private final Context mContext;

    /** @hide */
    public CompanionDeviceManager(
            @Nullable ICompanionDeviceManager service, @NonNull Context context) {
        mService = service;
        mContext = context;
    }

    /**
     * Associate this app with a companion device, selected by user
     *
     * <p>Once at least one appropriate device is found, {@code callback} will be called with a
     * {@link PendingIntent} that can be used to show the list of available devices for the user
     * to select.
     * It should be started for result (i.e. using
     * {@link android.app.Activity#startIntentSenderForResult}), as the resulting
     * {@link android.content.Intent} will contain extra {@link #EXTRA_DEVICE}, with the selected
     * device. (e.g. {@link android.bluetooth.BluetoothDevice})</p>
     *
     * <p>If your app needs to be excluded from battery optimizations (run in the background)
     * or to have unrestricted data access (use data in the background) you can declare that
     * you use the {@link android.Manifest.permission#REQUEST_COMPANION_RUN_IN_BACKGROUND} and {@link
     * android.Manifest.permission#REQUEST_COMPANION_USE_DATA_IN_BACKGROUND} respectively. Note that these
     * special capabilities have a negative effect on the device's battery and user's data
     * usage, therefore you should request them when absolutely necessary.</p>
     *
     * <p>You can call {@link #getAssociations} to get the list of currently associated
     * devices, and {@link #disassociate} to remove an association. Consider doing so when the
     * association is no longer relevant to avoid unnecessary battery and/or data drain resulting
     * from special privileges that the association provides</p>
     *
     * <p>Calling this API requires a uses-feature
     * {@link PackageManager#FEATURE_COMPANION_DEVICE_SETUP} declaration in the manifest</p>
     *
     * <p>When using {@link AssociationRequest#DEVICE_PROFILE_WATCH watch}
     * {@link AssociationRequest.Builder#setDeviceProfile profile}, caller must also hold
     * {@link Manifest.permission#REQUEST_COMPANION_PROFILE_WATCH}</p>
     *
     * @param request specific details about this request
     * @param callback will be called once there's at least one device found for user to choose from
     * @param handler A handler to control which thread the callback will be delivered on, or null,
     *                to deliver it on main thread
     *
     * @see AssociationRequest
     */
    @RequiresPermission(
            value = Manifest.permission.REQUEST_COMPANION_PROFILE_WATCH,
            conditional = true)
    public void associate(
            @NonNull AssociationRequest request,
            @NonNull Callback callback,
            @Nullable Handler handler) {
        if (!checkFeaturePresent()) {
            return;
        }
        Objects.requireNonNull(request, "Request cannot be null");
        Objects.requireNonNull(callback, "Callback cannot be null");
        try {
            mService.associate(
                    request,
                    new CallbackProxy(request, callback, Handler.mainIfNull(handler)),
                    getCallingPackage());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * <p>Calling this API requires a uses-feature
     * {@link PackageManager#FEATURE_COMPANION_DEVICE_SETUP} declaration in the manifest</p>
     *
     * @return a list of MAC addresses of devices that have been previously associated with the
     * current app. You can use these with {@link #disassociate}
     */
    @NonNull
    public List<String> getAssociations() {
        if (!checkFeaturePresent()) {
            return Collections.emptyList();
        }
        try {
            return mService.getAssociations(getCallingPackage(), mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Remove the association between this app and the device with the given mac address.
     *
     * <p>Any privileges provided via being associated with a given device will be revoked</p>
     *
     * <p>Consider doing so when the
     * association is no longer relevant to avoid unnecessary battery and/or data drain resulting
     * from special privileges that the association provides</p>
     *
     * <p>Calling this API requires a uses-feature
     * {@link PackageManager#FEATURE_COMPANION_DEVICE_SETUP} declaration in the manifest</p>
     *
     * @param deviceMacAddress the MAC address of device to disassociate from this app
     */
    public void disassociate(@NonNull String deviceMacAddress) {
        if (!checkFeaturePresent()) {
            return;
        }
        try {
            mService.disassociate(deviceMacAddress, getCallingPackage());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Request notification access for the given component.
     *
     * The given component must follow the protocol specified in {@link NotificationListenerService}
     *
     * Only components from the same {@link ComponentName#getPackageName package} as the calling app
     * are allowed.
     *
     * Your app must have an association with a device before calling this API
     *
     * <p>Calling this API requires a uses-feature
     * {@link PackageManager#FEATURE_COMPANION_DEVICE_SETUP} declaration in the manifest</p>
     */
    public void requestNotificationAccess(ComponentName component) {
        if (!checkFeaturePresent()) {
            return;
        }
        try {
            IntentSender intentSender = mService.requestNotificationAccess(component)
                    .getIntentSender();
            mContext.startIntentSender(intentSender, null, 0, 0, 0);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (IntentSender.SendIntentException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Check whether the given component can access the notifications via a
     * {@link NotificationListenerService}
     *
     * Your app must have an association with a device before calling this API
     *
     * <p>Calling this API requires a uses-feature
     * {@link PackageManager#FEATURE_COMPANION_DEVICE_SETUP} declaration in the manifest</p>
     *
     * @param component the name of the component
     * @return whether the given component has the notification listener permission
     *
     * @deprecated Use
     * {@link NotificationManager#isNotificationListenerAccessGranted(ComponentName)} instead.
     */
    @Deprecated
    public boolean hasNotificationAccess(ComponentName component) {
        if (!checkFeaturePresent()) {
            return false;
        }
        try {
            return mService.hasNotificationAccess(component);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Check if a given package was {@link #associate associated} with a device with given
     * Wi-Fi MAC address for a given user.
     *
     * <p>This is a system API protected by the
     * {@link andrioid.Manifest.permission#MANAGE_COMPANION_DEVICES} permission, that’s currently
     * called by the Android Wi-Fi stack to determine whether user consent is required to connect
     * to a Wi-Fi network. Devices that have been pre-registered as companion devices will not
     * require user consent to connect.</p>
     *
     * <p>Note if the caller has the
     * {@link android.Manifest.permission#COMPANION_APPROVE_WIFI_CONNECTIONS} permission, this
     * method will return true by default.</p>
     *
     * @param packageName the name of the package that has the association with the companion device
     * @param macAddress the Wi-Fi MAC address or BSSID of the companion device to check for
     * @param user the user handle that currently hosts the package being queried for a companion
     *             device association
     * @return whether a corresponding association record exists
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_COMPANION_DEVICES)
    public boolean isDeviceAssociatedForWifiConnection(
            @NonNull String packageName,
            @NonNull MacAddress macAddress,
            @NonNull UserHandle user) {
        if (!checkFeaturePresent()) {
            return false;
        }
        Objects.requireNonNull(packageName, "package name cannot be null");
        Objects.requireNonNull(macAddress, "mac address cannot be null");
        Objects.requireNonNull(user, "user cannot be null");
        try {
            return mService.isDeviceAssociatedForWifiConnection(
                    packageName, macAddress.toString(), user.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets all package-device {@link Association}s for the current user.
     *
     * @return the associations list
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_COMPANION_DEVICES)
    public @NonNull List<Association> getAllAssociations() {
        if (!checkFeaturePresent()) {
            return Collections.emptyList();
        }
        try {
            return mService.getAssociationsForUser(mContext.getUser().getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Checks whether the bluetooth device represented by the mac address was recently associated
     * with the companion app. This allows these devices to skip the Bluetooth pairing dialog if
     * their pairing variant is {@link BluetoothDevice#PAIRING_VARIANT_CONSENT}.
     *
     * @param packageName the package name of the calling app
     * @param deviceMacAddress the bluetooth device's mac address
     * @param user the user handle that currently hosts the package being queried for a companion
     *             device association
     * @return true if it was recently associated and we can bypass the dialog, false otherwise
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_COMPANION_DEVICES)
    public boolean canPairWithoutPrompt(@NonNull String packageName,
            @NonNull String deviceMacAddress, @NonNull UserHandle user) {
        if (!checkFeaturePresent()) {
            return false;
        }
        Objects.requireNonNull(packageName, "package name cannot be null");
        Objects.requireNonNull(deviceMacAddress, "device mac address cannot be null");
        Objects.requireNonNull(user, "user handle cannot be null");
        try {
            return mService.canPairWithoutPrompt(packageName, deviceMacAddress,
                    user.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Register to receive callbacks whenever the associated device comes in and out of range.
     *
     * The provided device must be {@link #associate associated} with the calling app before
     * calling this method.
     *
     * Caller must implement a single {@link CompanionDeviceService} which will be bound to and
     * receive callbacks to {@link CompanionDeviceService#onDeviceAppeared} and
     * {@link CompanionDeviceService#onDeviceDisappeared}.
     * The app doesn't need to remain running in order to receive its callbacks.
     *
     * Calling app must declare uses-permission
     * {@link android.Manifest.permission#REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE}.
     *
     * Calling app must check for feature presence of
     * {@link PackageManager#FEATURE_COMPANION_DEVICE_SETUP} before calling this API.
     *
     * For Bluetooth LE devices this is based on scanning for device with the given address.
     * For Bluetooth classic devices this is triggered when the device connects/disconnects.
     * WiFi devices are not supported.
     *
     * If a Bluetooth LE device wants to use a rotating mac address, it is recommended to use
     * Resolvable Private Address, and ensure the device is bonded to the phone so that android OS
     * is able to resolve the address.
     *
     * @param deviceAddress a previously-associated companion device's address
     *
     * @throws DeviceNotAssociatedException if the given device was not previously associated
     * with this app.
     */
    @RequiresPermission(android.Manifest.permission.REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE)
    public void startObservingDevicePresence(@NonNull String deviceAddress)
            throws DeviceNotAssociatedException {
        if (!checkFeaturePresent()) {
            return;
        }
        Objects.requireNonNull(deviceAddress, "address cannot be null");
        try {
            mService.registerDevicePresenceListenerService(
                    mContext.getPackageName(), deviceAddress);
        } catch (RemoteException e) {
            ExceptionUtils.propagateIfInstanceOf(e.getCause(), DeviceNotAssociatedException.class);
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Unregister for receiving callbacks whenever the associated device comes in and out of range.
     *
     * The provided device must be {@link #associate associated} with the calling app before
     * calling this method.
     *
     * Calling app must declare uses-permission
     * {@link android.Manifest.permission#REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE}.
     *
     * Calling app must check for feature presence of
     * {@link PackageManager#FEATURE_COMPANION_DEVICE_SETUP} before calling this API.
     *
     * @param deviceAddress a previously-associated companion device's address
     *
     * @throws DeviceNotAssociatedException if the given device was not previously associated
     * with this app.
     */
    @RequiresPermission(android.Manifest.permission.REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE)
    public void stopObservingDevicePresence(@NonNull String deviceAddress)
            throws DeviceNotAssociatedException {
        if (!checkFeaturePresent()) {
            return;
        }
        Objects.requireNonNull(deviceAddress, "address cannot be null");
        try {
            mService.unregisterDevicePresenceListenerService(
                    mContext.getPackageName(), deviceAddress);
        } catch (RemoteException e) {
            ExceptionUtils.propagateIfInstanceOf(e.getCause(), DeviceNotAssociatedException.class);
        }
    }

    /**
     * Dispatch a message to system for processing.
     *
     * <p>Calling app must declare uses-permission
     * {@link android.Manifest.permission#DELIVER_COMPANION_MESSAGES}</p>
     *
     * @param messageId id of the message
     * @param associationId association id of the associated device where data is coming from
     * @param message message received from the associated device
     *
     * @throws DeviceNotAssociatedException if the given device was not previously associated with
     * this app
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.DELIVER_COMPANION_MESSAGES)
    public void dispatchMessage(int messageId, int associationId, @NonNull byte[] message)
            throws DeviceNotAssociatedException {
        try {
            mService.dispatchMessage(messageId, associationId, message);
        } catch (RemoteException e) {
            ExceptionUtils.propagateIfInstanceOf(e.getCause(), DeviceNotAssociatedException.class);
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Associates given device with given app for the given user directly, without UI prompt.
     *
     * @param packageName package name of the companion app
     * @param macAddress mac address of the device to associate
     * @param certificate The SHA256 digest of the companion app's signing certificate
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.ASSOCIATE_COMPANION_DEVICES)
    public void associate(
            @NonNull String packageName,
            @NonNull MacAddress macAddress,
            @NonNull byte[] certificate) {
        if (!checkFeaturePresent()) {
            return;
        }
        Objects.requireNonNull(packageName, "package name cannot be null");
        Objects.requireNonNull(macAddress, "mac address cannot be null");

        UserHandle user = android.os.Process.myUserHandle();
        try {
            mService.createAssociation(
                    packageName, macAddress.toString(), user.getIdentifier(), certificate);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private boolean checkFeaturePresent() {
        boolean featurePresent = mService != null;
        if (!featurePresent && DEBUG) {
            Log.d(LOG_TAG, "Feature " + PackageManager.FEATURE_COMPANION_DEVICE_SETUP
                    + " not available");
        }
        return featurePresent;
    }

    private Activity getActivity() {
        return (Activity) mContext;
    }

    private String getCallingPackage() {
        return mContext.getPackageName();
    }

    private class CallbackProxy extends IFindDeviceCallback.Stub
            implements Application.ActivityLifecycleCallbacks {

        private Callback mCallback;
        private Handler mHandler;
        private AssociationRequest mRequest;

        final Object mLock = new Object();

        private CallbackProxy(AssociationRequest request, Callback callback, Handler handler) {
            mCallback = callback;
            mHandler = handler;
            mRequest = request;
            getActivity().getApplication().registerActivityLifecycleCallbacks(this);
        }

        @Override
        public void onSuccess(PendingIntent launcher) {
            lockAndPost(Callback::onDeviceFound, launcher.getIntentSender());
        }

        @Override
        public void onFailure(CharSequence reason) {
            lockAndPost(Callback::onFailure, reason);
        }

        <T> void lockAndPost(BiConsumer<Callback, T> action, T payload) {
            synchronized (mLock) {
                if (mHandler != null) {
                    mHandler.post(() -> {
                        Callback callback = null;
                        synchronized (mLock) {
                            callback = mCallback;
                        }
                        if (callback != null) {
                            action.accept(callback, payload);
                        }
                    });
                }
            }
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
            synchronized (mLock) {
                if (activity != getActivity()) return;
                try {
                    mService.stopScan(mRequest, this, getCallingPackage());
                } catch (RemoteException e) {
                    e.rethrowFromSystemServer();
                }
                getActivity().getApplication().unregisterActivityLifecycleCallbacks(this);
                mCallback = null;
                mHandler = null;
                mRequest = null;
            }
        }

        @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}
        @Override public void onActivityStarted(Activity activity) {}
        @Override public void onActivityResumed(Activity activity) {}
        @Override public void onActivityPaused(Activity activity) {}
        @Override public void onActivityStopped(Activity activity) {}
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
    }
}
