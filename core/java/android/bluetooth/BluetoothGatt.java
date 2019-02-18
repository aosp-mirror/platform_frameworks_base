/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.bluetooth;

import android.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Handler;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Public API for the Bluetooth GATT Profile.
 *
 * <p>This class provides Bluetooth GATT functionality to enable communication
 * with Bluetooth Smart or Smart Ready devices.
 *
 * <p>To connect to a remote peripheral device, create a {@link BluetoothGattCallback}
 * and call {@link BluetoothDevice#connectGatt} to get a instance of this class.
 * GATT capable devices can be discovered using the Bluetooth device discovery or BLE
 * scan process.
 */
public final class BluetoothGatt implements BluetoothProfile {
    private static final String TAG = "BluetoothGatt";
    private static final boolean DBG = true;
    private static final boolean VDBG = false;

    @UnsupportedAppUsage
    private IBluetoothGatt mService;
    @UnsupportedAppUsage
    private volatile BluetoothGattCallback mCallback;
    private Handler mHandler;
    @UnsupportedAppUsage
    private int mClientIf;
    private BluetoothDevice mDevice;
    @UnsupportedAppUsage
    private boolean mAutoConnect;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private int mAuthRetryState;
    private int mConnState;
    private final Object mStateLock = new Object();
    private final Object mDeviceBusyLock = new Object();
    @UnsupportedAppUsage
    private Boolean mDeviceBusy = false;
    @UnsupportedAppUsage
    private int mTransport;
    private int mPhy;
    private boolean mOpportunistic;

    private static final int AUTH_RETRY_STATE_IDLE = 0;
    private static final int AUTH_RETRY_STATE_NO_MITM = 1;
    private static final int AUTH_RETRY_STATE_MITM = 2;

    private static final int CONN_STATE_IDLE = 0;
    private static final int CONN_STATE_CONNECTING = 1;
    private static final int CONN_STATE_CONNECTED = 2;
    private static final int CONN_STATE_DISCONNECTING = 3;
    private static final int CONN_STATE_CLOSED = 4;

    private List<BluetoothGattService> mServices;

    /** A GATT operation completed successfully */
    public static final int GATT_SUCCESS = 0;

    /** GATT read operation is not permitted */
    public static final int GATT_READ_NOT_PERMITTED = 0x2;

    /** GATT write operation is not permitted */
    public static final int GATT_WRITE_NOT_PERMITTED = 0x3;

    /** Insufficient authentication for a given operation */
    public static final int GATT_INSUFFICIENT_AUTHENTICATION = 0x5;

    /** The given request is not supported */
    public static final int GATT_REQUEST_NOT_SUPPORTED = 0x6;

    /** Insufficient encryption for a given operation */
    public static final int GATT_INSUFFICIENT_ENCRYPTION = 0xf;

    /** A read or write operation was requested with an invalid offset */
    public static final int GATT_INVALID_OFFSET = 0x7;

    /** A write operation exceeds the maximum length of the attribute */
    public static final int GATT_INVALID_ATTRIBUTE_LENGTH = 0xd;

    /** A remote device connection is congested. */
    public static final int GATT_CONNECTION_CONGESTED = 0x8f;

    /** A GATT operation failed, errors other than the above */
    public static final int GATT_FAILURE = 0x101;

    /**
     * Connection parameter update - Use the connection parameters recommended by the
     * Bluetooth SIG. This is the default value if no connection parameter update
     * is requested.
     */
    public static final int CONNECTION_PRIORITY_BALANCED = 0;

    /**
     * Connection parameter update - Request a high priority, low latency connection.
     * An application should only request high priority connection parameters to transfer large
     * amounts of data over LE quickly. Once the transfer is complete, the application should
     * request {@link BluetoothGatt#CONNECTION_PRIORITY_BALANCED} connection parameters to reduce
     * energy use.
     */
    public static final int CONNECTION_PRIORITY_HIGH = 1;

    /** Connection parameter update - Request low power, reduced data rate connection parameters. */
    public static final int CONNECTION_PRIORITY_LOW_POWER = 2;

    /**
     * No authentication required.
     *
     * @hide
     */
    /*package*/ static final int AUTHENTICATION_NONE = 0;

    /**
     * Authentication requested; no man-in-the-middle protection required.
     *
     * @hide
     */
    /*package*/ static final int AUTHENTICATION_NO_MITM = 1;

    /**
     * Authentication with man-in-the-middle protection requested.
     *
     * @hide
     */
    /*package*/ static final int AUTHENTICATION_MITM = 2;

    /**
     * Bluetooth GATT callbacks. Overrides the default BluetoothGattCallback implementation.
     */
    private final IBluetoothGattCallback mBluetoothGattCallback =
            new IBluetoothGattCallback.Stub() {
                /**
                 * Application interface registered - app is ready to go
                 * @hide
                 */
                @Override
                public void onClientRegistered(int status, int clientIf) {
                    if (DBG) {
                        Log.d(TAG, "onClientRegistered() - status=" + status
                                + " clientIf=" + clientIf);
                    }
                    if (VDBG) {
                        synchronized (mStateLock) {
                            if (mConnState != CONN_STATE_CONNECTING) {
                                Log.e(TAG, "Bad connection state: " + mConnState);
                            }
                        }
                    }
                    mClientIf = clientIf;
                    if (status != GATT_SUCCESS) {
                        runOrQueueCallback(new Runnable() {
                            @Override
                            public void run() {
                                final BluetoothGattCallback callback = mCallback;
                                if (callback != null) {
                                    callback.onConnectionStateChange(BluetoothGatt.this,
                                            GATT_FAILURE,
                                            BluetoothProfile.STATE_DISCONNECTED);
                                }
                            }
                        });

                        synchronized (mStateLock) {
                            mConnState = CONN_STATE_IDLE;
                        }
                        return;
                    }
                    try {
                        mService.clientConnect(mClientIf, mDevice.getAddress(),
                                !mAutoConnect, mTransport, mOpportunistic,
                                mPhy); // autoConnect is inverse of "isDirect"
                    } catch (RemoteException e) {
                        Log.e(TAG, "", e);
                    }
                }

                /**
                 * Phy update callback
                 * @hide
                 */
                @Override
                public void onPhyUpdate(String address, int txPhy, int rxPhy, int status) {
                    if (DBG) {
                        Log.d(TAG, "onPhyUpdate() - status=" + status
                                + " address=" + address + " txPhy=" + txPhy + " rxPhy=" + rxPhy);
                    }
                    if (!address.equals(mDevice.getAddress())) {
                        return;
                    }

                    runOrQueueCallback(new Runnable() {
                        @Override
                        public void run() {
                            final BluetoothGattCallback callback = mCallback;
                            if (callback != null) {
                                callback.onPhyUpdate(BluetoothGatt.this, txPhy, rxPhy, status);
                            }
                        }
                    });
                }

                /**
                 * Phy read callback
                 * @hide
                 */
                @Override
                public void onPhyRead(String address, int txPhy, int rxPhy, int status) {
                    if (DBG) {
                        Log.d(TAG, "onPhyRead() - status=" + status
                                + " address=" + address + " txPhy=" + txPhy + " rxPhy=" + rxPhy);
                    }
                    if (!address.equals(mDevice.getAddress())) {
                        return;
                    }

                    runOrQueueCallback(new Runnable() {
                        @Override
                        public void run() {
                            final BluetoothGattCallback callback = mCallback;
                            if (callback != null) {
                                callback.onPhyRead(BluetoothGatt.this, txPhy, rxPhy, status);
                            }
                        }
                    });
                }

                /**
                 * Client connection state changed
                 * @hide
                 */
                @Override
                public void onClientConnectionState(int status, int clientIf,
                        boolean connected, String address) {
                    if (DBG) {
                        Log.d(TAG, "onClientConnectionState() - status=" + status
                                + " clientIf=" + clientIf + " device=" + address);
                    }
                    if (!address.equals(mDevice.getAddress())) {
                        return;
                    }
                    int profileState = connected ? BluetoothProfile.STATE_CONNECTED :
                            BluetoothProfile.STATE_DISCONNECTED;

                    runOrQueueCallback(new Runnable() {
                        @Override
                        public void run() {
                            final BluetoothGattCallback callback = mCallback;
                            if (callback != null) {
                                callback.onConnectionStateChange(BluetoothGatt.this, status,
                                        profileState);
                            }
                        }
                    });

                    synchronized (mStateLock) {
                        if (connected) {
                            mConnState = CONN_STATE_CONNECTED;
                        } else {
                            mConnState = CONN_STATE_IDLE;
                        }
                    }

                    synchronized (mDeviceBusyLock) {
                        mDeviceBusy = false;
                    }
                }

                /**
                 * Remote search has been completed.
                 * The internal object structure should now reflect the state
                 * of the remote device database. Let the application know that
                 * we are done at this point.
                 * @hide
                 */
                @Override
                public void onSearchComplete(String address, List<BluetoothGattService> services,
                        int status) {
                    if (DBG) {
                        Log.d(TAG,
                                "onSearchComplete() = Device=" + address + " Status=" + status);
                    }
                    if (!address.equals(mDevice.getAddress())) {
                        return;
                    }

                    for (BluetoothGattService s : services) {
                        //services we receive don't have device set properly.
                        s.setDevice(mDevice);
                    }

                    mServices.addAll(services);

                    // Fix references to included services, as they doesn't point to right objects.
                    for (BluetoothGattService fixedService : mServices) {
                        ArrayList<BluetoothGattService> includedServices =
                                new ArrayList(fixedService.getIncludedServices());
                        fixedService.getIncludedServices().clear();

                        for (BluetoothGattService brokenRef : includedServices) {
                            BluetoothGattService includedService = getService(mDevice,
                                    brokenRef.getUuid(), brokenRef.getInstanceId());
                            if (includedService != null) {
                                fixedService.addIncludedService(includedService);
                            } else {
                                Log.e(TAG, "Broken GATT database: can't find included service.");
                            }
                        }
                    }

                    runOrQueueCallback(new Runnable() {
                        @Override
                        public void run() {
                            final BluetoothGattCallback callback = mCallback;
                            if (callback != null) {
                                callback.onServicesDiscovered(BluetoothGatt.this, status);
                            }
                        }
                    });
                }

                /**
                 * Remote characteristic has been read.
                 * Updates the internal value.
                 * @hide
                 */
                @Override
                public void onCharacteristicRead(String address, int status, int handle,
                        byte[] value) {
                    if (VDBG) {
                        Log.d(TAG, "onCharacteristicRead() - Device=" + address
                                + " handle=" + handle + " Status=" + status);
                    }

                    if (!address.equals(mDevice.getAddress())) {
                        return;
                    }

                    synchronized (mDeviceBusyLock) {
                        mDeviceBusy = false;
                    }

                    if ((status == GATT_INSUFFICIENT_AUTHENTICATION
                            || status == GATT_INSUFFICIENT_ENCRYPTION)
                            && (mAuthRetryState != AUTH_RETRY_STATE_MITM)) {
                        try {
                            final int authReq = (mAuthRetryState == AUTH_RETRY_STATE_IDLE)
                                    ? AUTHENTICATION_NO_MITM : AUTHENTICATION_MITM;
                            mService.readCharacteristic(mClientIf, address, handle, authReq);
                            mAuthRetryState++;
                            return;
                        } catch (RemoteException e) {
                            Log.e(TAG, "", e);
                        }
                    }

                    mAuthRetryState = AUTH_RETRY_STATE_IDLE;

                    BluetoothGattCharacteristic characteristic = getCharacteristicById(mDevice,
                            handle);
                    if (characteristic == null) {
                        Log.w(TAG, "onCharacteristicRead() failed to find characteristic!");
                        return;
                    }

                    runOrQueueCallback(new Runnable() {
                        @Override
                        public void run() {
                            final BluetoothGattCallback callback = mCallback;
                            if (callback != null) {
                                if (status == 0) characteristic.setValue(value);
                                callback.onCharacteristicRead(BluetoothGatt.this, characteristic,
                                        status);
                            }
                        }
                    });
                }

                /**
                 * Characteristic has been written to the remote device.
                 * Let the app know how we did...
                 * @hide
                 */
                @Override
                public void onCharacteristicWrite(String address, int status, int handle) {
                    if (VDBG) {
                        Log.d(TAG, "onCharacteristicWrite() - Device=" + address
                                + " handle=" + handle + " Status=" + status);
                    }

                    if (!address.equals(mDevice.getAddress())) {
                        return;
                    }

                    synchronized (mDeviceBusyLock) {
                        mDeviceBusy = false;
                    }

                    BluetoothGattCharacteristic characteristic = getCharacteristicById(mDevice,
                            handle);
                    if (characteristic == null) return;

                    if ((status == GATT_INSUFFICIENT_AUTHENTICATION
                            || status == GATT_INSUFFICIENT_ENCRYPTION)
                            && (mAuthRetryState != AUTH_RETRY_STATE_MITM)) {
                        try {
                            final int authReq = (mAuthRetryState == AUTH_RETRY_STATE_IDLE)
                                    ? AUTHENTICATION_NO_MITM : AUTHENTICATION_MITM;
                            mService.writeCharacteristic(mClientIf, address, handle,
                                    characteristic.getWriteType(), authReq,
                                    characteristic.getValue());
                            mAuthRetryState++;
                            return;
                        } catch (RemoteException e) {
                            Log.e(TAG, "", e);
                        }
                    }

                    mAuthRetryState = AUTH_RETRY_STATE_IDLE;

                    runOrQueueCallback(new Runnable() {
                        @Override
                        public void run() {
                            final BluetoothGattCallback callback = mCallback;
                            if (callback != null) {
                                callback.onCharacteristicWrite(BluetoothGatt.this, characteristic,
                                        status);
                            }
                        }
                    });
                }

                /**
                 * Remote characteristic has been updated.
                 * Updates the internal value.
                 * @hide
                 */
                @Override
                public void onNotify(String address, int handle, byte[] value) {
                    if (VDBG) Log.d(TAG, "onNotify() - Device=" + address + " handle=" + handle);

                    if (!address.equals(mDevice.getAddress())) {
                        return;
                    }

                    BluetoothGattCharacteristic characteristic = getCharacteristicById(mDevice,
                            handle);
                    if (characteristic == null) return;

                    runOrQueueCallback(new Runnable() {
                        @Override
                        public void run() {
                            final BluetoothGattCallback callback = mCallback;
                            if (callback != null) {
                                characteristic.setValue(value);
                                callback.onCharacteristicChanged(BluetoothGatt.this,
                                        characteristic);
                            }
                        }
                    });
                }

                /**
                 * Descriptor has been read.
                 * @hide
                 */
                @Override
                public void onDescriptorRead(String address, int status, int handle, byte[] value) {
                    if (VDBG) {
                        Log.d(TAG,
                                "onDescriptorRead() - Device=" + address + " handle=" + handle);
                    }

                    if (!address.equals(mDevice.getAddress())) {
                        return;
                    }

                    synchronized (mDeviceBusyLock) {
                        mDeviceBusy = false;
                    }

                    BluetoothGattDescriptor descriptor = getDescriptorById(mDevice, handle);
                    if (descriptor == null) return;


                    if ((status == GATT_INSUFFICIENT_AUTHENTICATION
                            || status == GATT_INSUFFICIENT_ENCRYPTION)
                            && (mAuthRetryState != AUTH_RETRY_STATE_MITM)) {
                        try {
                            final int authReq = (mAuthRetryState == AUTH_RETRY_STATE_IDLE)
                                    ? AUTHENTICATION_NO_MITM : AUTHENTICATION_MITM;
                            mService.readDescriptor(mClientIf, address, handle, authReq);
                            mAuthRetryState++;
                            return;
                        } catch (RemoteException e) {
                            Log.e(TAG, "", e);
                        }
                    }

                    mAuthRetryState = AUTH_RETRY_STATE_IDLE;

                    runOrQueueCallback(new Runnable() {
                        @Override
                        public void run() {
                            final BluetoothGattCallback callback = mCallback;
                            if (callback != null) {
                                if (status == 0) descriptor.setValue(value);
                                callback.onDescriptorRead(BluetoothGatt.this, descriptor, status);
                            }
                        }
                    });
                }

                /**
                 * Descriptor write operation complete.
                 * @hide
                 */
                @Override
                public void onDescriptorWrite(String address, int status, int handle) {
                    if (VDBG) {
                        Log.d(TAG,
                                "onDescriptorWrite() - Device=" + address + " handle=" + handle);
                    }

                    if (!address.equals(mDevice.getAddress())) {
                        return;
                    }

                    synchronized (mDeviceBusyLock) {
                        mDeviceBusy = false;
                    }

                    BluetoothGattDescriptor descriptor = getDescriptorById(mDevice, handle);
                    if (descriptor == null) return;

                    if ((status == GATT_INSUFFICIENT_AUTHENTICATION
                            || status == GATT_INSUFFICIENT_ENCRYPTION)
                            && (mAuthRetryState != AUTH_RETRY_STATE_MITM)) {
                        try {
                            final int authReq = (mAuthRetryState == AUTH_RETRY_STATE_IDLE)
                                    ? AUTHENTICATION_NO_MITM : AUTHENTICATION_MITM;
                            mService.writeDescriptor(mClientIf, address, handle,
                                    authReq, descriptor.getValue());
                            mAuthRetryState++;
                            return;
                        } catch (RemoteException e) {
                            Log.e(TAG, "", e);
                        }
                    }

                    mAuthRetryState = AUTH_RETRY_STATE_IDLE;

                    runOrQueueCallback(new Runnable() {
                        @Override
                        public void run() {
                            final BluetoothGattCallback callback = mCallback;
                            if (callback != null) {
                                callback.onDescriptorWrite(BluetoothGatt.this, descriptor, status);
                            }
                        }
                    });
                }

                /**
                 * Prepared write transaction completed (or aborted)
                 * @hide
                 */
                @Override
                public void onExecuteWrite(String address, int status) {
                    if (VDBG) {
                        Log.d(TAG, "onExecuteWrite() - Device=" + address
                                + " status=" + status);
                    }
                    if (!address.equals(mDevice.getAddress())) {
                        return;
                    }

                    synchronized (mDeviceBusyLock) {
                        mDeviceBusy = false;
                    }

                    runOrQueueCallback(new Runnable() {
                        @Override
                        public void run() {
                            final BluetoothGattCallback callback = mCallback;
                            if (callback != null) {
                                callback.onReliableWriteCompleted(BluetoothGatt.this, status);
                            }
                        }
                    });
                }

                /**
                 * Remote device RSSI has been read
                 * @hide
                 */
                @Override
                public void onReadRemoteRssi(String address, int rssi, int status) {
                    if (VDBG) {
                        Log.d(TAG, "onReadRemoteRssi() - Device=" + address
                                + " rssi=" + rssi + " status=" + status);
                    }
                    if (!address.equals(mDevice.getAddress())) {
                        return;
                    }
                    runOrQueueCallback(new Runnable() {
                        @Override
                        public void run() {
                            final BluetoothGattCallback callback = mCallback;
                            if (callback != null) {
                                callback.onReadRemoteRssi(BluetoothGatt.this, rssi, status);
                            }
                        }
                    });
                }

                /**
                 * Callback invoked when the MTU for a given connection changes
                 * @hide
                 */
                @Override
                public void onConfigureMTU(String address, int mtu, int status) {
                    if (DBG) {
                        Log.d(TAG, "onConfigureMTU() - Device=" + address
                                + " mtu=" + mtu + " status=" + status);
                    }
                    if (!address.equals(mDevice.getAddress())) {
                        return;
                    }

                    runOrQueueCallback(new Runnable() {
                        @Override
                        public void run() {
                            final BluetoothGattCallback callback = mCallback;
                            if (callback != null) {
                                callback.onMtuChanged(BluetoothGatt.this, mtu, status);
                            }
                        }
                    });
                }

                /**
                 * Callback invoked when the given connection is updated
                 * @hide
                 */
                @Override
                public void onConnectionUpdated(String address, int interval, int latency,
                        int timeout, int status) {
                    if (DBG) {
                        Log.d(TAG, "onConnectionUpdated() - Device=" + address
                                + " interval=" + interval + " latency=" + latency
                                + " timeout=" + timeout + " status=" + status);
                    }
                    if (!address.equals(mDevice.getAddress())) {
                        return;
                    }

                    runOrQueueCallback(new Runnable() {
                        @Override
                        public void run() {
                            final BluetoothGattCallback callback = mCallback;
                            if (callback != null) {
                                callback.onConnectionUpdated(BluetoothGatt.this, interval, latency,
                                        timeout, status);
                            }
                        }
                    });
                }
            };

    /*package*/ BluetoothGatt(IBluetoothGatt iGatt, BluetoothDevice device,
            int transport, boolean opportunistic, int phy) {
        mService = iGatt;
        mDevice = device;
        mTransport = transport;
        mPhy = phy;
        mOpportunistic = opportunistic;
        mServices = new ArrayList<BluetoothGattService>();

        mConnState = CONN_STATE_IDLE;
        mAuthRetryState = AUTH_RETRY_STATE_IDLE;
    }

    /**
     * Close this Bluetooth GATT client.
     *
     * Application should call this method as early as possible after it is done with
     * this GATT client.
     */
    public void close() {
        if (DBG) Log.d(TAG, "close()");

        unregisterApp();
        mConnState = CONN_STATE_CLOSED;
        mAuthRetryState = AUTH_RETRY_STATE_IDLE;
    }

    /**
     * Returns a service by UUID, instance and type.
     *
     * @hide
     */
    /*package*/ BluetoothGattService getService(BluetoothDevice device, UUID uuid,
            int instanceId) {
        for (BluetoothGattService svc : mServices) {
            if (svc.getDevice().equals(device)
                    && svc.getInstanceId() == instanceId
                    && svc.getUuid().equals(uuid)) {
                return svc;
            }
        }
        return null;
    }


    /**
     * Returns a characteristic with id equal to instanceId.
     *
     * @hide
     */
    /*package*/ BluetoothGattCharacteristic getCharacteristicById(BluetoothDevice device,
            int instanceId) {
        for (BluetoothGattService svc : mServices) {
            for (BluetoothGattCharacteristic charac : svc.getCharacteristics()) {
                if (charac.getInstanceId() == instanceId) {
                    return charac;
                }
            }
        }
        return null;
    }

    /**
     * Returns a descriptor with id equal to instanceId.
     *
     * @hide
     */
    /*package*/ BluetoothGattDescriptor getDescriptorById(BluetoothDevice device, int instanceId) {
        for (BluetoothGattService svc : mServices) {
            for (BluetoothGattCharacteristic charac : svc.getCharacteristics()) {
                for (BluetoothGattDescriptor desc : charac.getDescriptors()) {
                    if (desc.getInstanceId() == instanceId) {
                        return desc;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Queue the runnable on a {@link Handler} provided by the user, or execute the runnable
     * immediately if no Handler was provided.
     */
    private void runOrQueueCallback(final Runnable cb) {
        if (mHandler == null) {
            try {
                cb.run();
            } catch (Exception ex) {
                Log.w(TAG, "Unhandled exception in callback", ex);
            }
        } else {
            mHandler.post(cb);
        }
    }

    /**
     * Register an application callback to start using GATT.
     *
     * <p>This is an asynchronous call. The callback {@link BluetoothGattCallback#onAppRegistered}
     * is used to notify success or failure if the function returns true.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @param callback GATT callback handler that will receive asynchronous callbacks.
     * @return If true, the callback will be called to notify success or failure, false on immediate
     * error
     */
    private boolean registerApp(BluetoothGattCallback callback, Handler handler) {
        if (DBG) Log.d(TAG, "registerApp()");
        if (mService == null) return false;

        mCallback = callback;
        mHandler = handler;
        UUID uuid = UUID.randomUUID();
        if (DBG) Log.d(TAG, "registerApp() - UUID=" + uuid);

        try {
            mService.registerClient(new ParcelUuid(uuid), mBluetoothGattCallback);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return false;
        }

        return true;
    }

    /**
     * Unregister the current application and callbacks.
     */
    @UnsupportedAppUsage
    private void unregisterApp() {
        if (DBG) Log.d(TAG, "unregisterApp() - mClientIf=" + mClientIf);
        if (mService == null || mClientIf == 0) return;

        try {
            mCallback = null;
            mService.unregisterClient(mClientIf);
            mClientIf = 0;
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
    }

    /**
     * Initiate a connection to a Bluetooth GATT capable device.
     *
     * <p>The connection may not be established right away, but will be
     * completed when the remote device is available. A
     * {@link BluetoothGattCallback#onConnectionStateChange} callback will be
     * invoked when the connection state changes as a result of this function.
     *
     * <p>The autoConnect parameter determines whether to actively connect to
     * the remote device, or rather passively scan and finalize the connection
     * when the remote device is in range/available. Generally, the first ever
     * connection to a device should be direct (autoConnect set to false) and
     * subsequent connections to known devices should be invoked with the
     * autoConnect parameter set to true.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @param device Remote device to connect to
     * @param autoConnect Whether to directly connect to the remote device (false) or to
     * automatically connect as soon as the remote device becomes available (true).
     * @return true, if the connection attempt was initiated successfully
     */
    @UnsupportedAppUsage
    /*package*/ boolean connect(Boolean autoConnect, BluetoothGattCallback callback,
            Handler handler) {
        if (DBG) {
            Log.d(TAG,
                    "connect() - device: " + mDevice.getAddress() + ", auto: " + autoConnect);
        }
        synchronized (mStateLock) {
            if (mConnState != CONN_STATE_IDLE) {
                throw new IllegalStateException("Not idle");
            }
            mConnState = CONN_STATE_CONNECTING;
        }

        mAutoConnect = autoConnect;

        if (!registerApp(callback, handler)) {
            synchronized (mStateLock) {
                mConnState = CONN_STATE_IDLE;
            }
            Log.e(TAG, "Failed to register callback");
            return false;
        }

        // The connection will continue in the onClientRegistered callback
        return true;
    }

    /**
     * Disconnects an established connection, or cancels a connection attempt
     * currently in progress.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     */
    public void disconnect() {
        if (DBG) Log.d(TAG, "cancelOpen() - device: " + mDevice.getAddress());
        if (mService == null || mClientIf == 0) return;

        try {
            mService.clientDisconnect(mClientIf, mDevice.getAddress());
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
    }

    /**
     * Connect back to remote device.
     *
     * <p>This method is used to re-connect to a remote device after the
     * connection has been dropped. If the device is not in range, the
     * re-connection will be triggered once the device is back in range.
     *
     * @return true, if the connection attempt was initiated successfully
     */
    public boolean connect() {
        try {
            mService.clientConnect(mClientIf, mDevice.getAddress(), false, mTransport,
                    mOpportunistic, mPhy); // autoConnect is inverse of "isDirect"
            return true;
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return false;
        }
    }

    /**
     * Set the preferred connection PHY for this app. Please note that this is just a
     * recommendation, whether the PHY change will happen depends on other applications preferences,
     * local and remote controller capabilities. Controller can override these settings.
     * <p>
     * {@link BluetoothGattCallback#onPhyUpdate} will be triggered as a result of this call, even
     * if no PHY change happens. It is also triggered when remote device updates the PHY.
     *
     * @param txPhy preferred transmitter PHY. Bitwise OR of any of {@link
     * BluetoothDevice#PHY_LE_1M_MASK}, {@link BluetoothDevice#PHY_LE_2M_MASK}, and {@link
     * BluetoothDevice#PHY_LE_CODED_MASK}.
     * @param rxPhy preferred receiver PHY. Bitwise OR of any of {@link
     * BluetoothDevice#PHY_LE_1M_MASK}, {@link BluetoothDevice#PHY_LE_2M_MASK}, and {@link
     * BluetoothDevice#PHY_LE_CODED_MASK}.
     * @param phyOptions preferred coding to use when transmitting on the LE Coded PHY. Can be one
     * of {@link BluetoothDevice#PHY_OPTION_NO_PREFERRED}, {@link BluetoothDevice#PHY_OPTION_S2} or
     * {@link BluetoothDevice#PHY_OPTION_S8}
     */
    public void setPreferredPhy(int txPhy, int rxPhy, int phyOptions) {
        try {
            mService.clientSetPreferredPhy(mClientIf, mDevice.getAddress(), txPhy, rxPhy,
                    phyOptions);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
    }

    /**
     * Read the current transmitter PHY and receiver PHY of the connection. The values are returned
     * in {@link BluetoothGattCallback#onPhyRead}
     */
    public void readPhy() {
        try {
            mService.clientReadPhy(mClientIf, mDevice.getAddress());
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
    }

    /**
     * Return the remote bluetooth device this GATT client targets to
     *
     * @return remote bluetooth device
     */
    public BluetoothDevice getDevice() {
        return mDevice;
    }

    /**
     * Discovers services offered by a remote device as well as their
     * characteristics and descriptors.
     *
     * <p>This is an asynchronous operation. Once service discovery is completed,
     * the {@link BluetoothGattCallback#onServicesDiscovered} callback is
     * triggered. If the discovery was successful, the remote services can be
     * retrieved using the {@link #getServices} function.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @return true, if the remote service discovery has been started
     */
    public boolean discoverServices() {
        if (DBG) Log.d(TAG, "discoverServices() - device: " + mDevice.getAddress());
        if (mService == null || mClientIf == 0) return false;

        mServices.clear();

        try {
            mService.discoverServices(mClientIf, mDevice.getAddress());
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return false;
        }

        return true;
    }

    /**
     * Discovers a service by UUID. This is exposed only for passing PTS tests.
     * It should never be used by real applications. The service is not searched
     * for characteristics and descriptors, or returned in any callback.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @return true, if the remote service discovery has been started
     * @hide
     */
    public boolean discoverServiceByUuid(UUID uuid) {
        if (DBG) Log.d(TAG, "discoverServiceByUuid() - device: " + mDevice.getAddress());
        if (mService == null || mClientIf == 0) return false;

        mServices.clear();

        try {
            mService.discoverServiceByUuid(mClientIf, mDevice.getAddress(), new ParcelUuid(uuid));
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return false;
        }
        return true;
    }

    /**
     * Returns a list of GATT services offered by the remote device.
     *
     * <p>This function requires that service discovery has been completed
     * for the given device.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @return List of services on the remote device. Returns an empty list if service discovery has
     * not yet been performed.
     */
    public List<BluetoothGattService> getServices() {
        List<BluetoothGattService> result =
                new ArrayList<BluetoothGattService>();

        for (BluetoothGattService service : mServices) {
            if (service.getDevice().equals(mDevice)) {
                result.add(service);
            }
        }

        return result;
    }

    /**
     * Returns a {@link BluetoothGattService}, if the requested UUID is
     * supported by the remote device.
     *
     * <p>This function requires that service discovery has been completed
     * for the given device.
     *
     * <p>If multiple instances of the same service (as identified by UUID)
     * exist, the first instance of the service is returned.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @param uuid UUID of the requested service
     * @return BluetoothGattService if supported, or null if the requested service is not offered by
     * the remote device.
     */
    public BluetoothGattService getService(UUID uuid) {
        for (BluetoothGattService service : mServices) {
            if (service.getDevice().equals(mDevice) && service.getUuid().equals(uuid)) {
                return service;
            }
        }

        return null;
    }

    /**
     * Reads the requested characteristic from the associated remote device.
     *
     * <p>This is an asynchronous operation. The result of the read operation
     * is reported by the {@link BluetoothGattCallback#onCharacteristicRead}
     * callback.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @param characteristic Characteristic to read from the remote device
     * @return true, if the read operation was initiated successfully
     */
    public boolean readCharacteristic(BluetoothGattCharacteristic characteristic) {
        if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) == 0) {
            return false;
        }

        if (VDBG) Log.d(TAG, "readCharacteristic() - uuid: " + characteristic.getUuid());
        if (mService == null || mClientIf == 0) return false;

        BluetoothGattService service = characteristic.getService();
        if (service == null) return false;

        BluetoothDevice device = service.getDevice();
        if (device == null) return false;

        synchronized (mDeviceBusyLock) {
            if (mDeviceBusy) return false;
            mDeviceBusy = true;
        }

        try {
            mService.readCharacteristic(mClientIf, device.getAddress(),
                    characteristic.getInstanceId(), AUTHENTICATION_NONE);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            mDeviceBusy = false;
            return false;
        }

        return true;
    }

    /**
     * Reads the characteristic using its UUID from the associated remote device.
     *
     * <p>This is an asynchronous operation. The result of the read operation
     * is reported by the {@link BluetoothGattCallback#onCharacteristicRead}
     * callback.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @param uuid UUID of characteristic to read from the remote device
     * @return true, if the read operation was initiated successfully
     * @hide
     */
    public boolean readUsingCharacteristicUuid(UUID uuid, int startHandle, int endHandle) {
        if (VDBG) Log.d(TAG, "readUsingCharacteristicUuid() - uuid: " + uuid);
        if (mService == null || mClientIf == 0) return false;

        synchronized (mDeviceBusyLock) {
            if (mDeviceBusy) return false;
            mDeviceBusy = true;
        }

        try {
            mService.readUsingCharacteristicUuid(mClientIf, mDevice.getAddress(),
                    new ParcelUuid(uuid), startHandle, endHandle, AUTHENTICATION_NONE);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            mDeviceBusy = false;
            return false;
        }

        return true;
    }


    /**
     * Writes a given characteristic and its values to the associated remote device.
     *
     * <p>Once the write operation has been completed, the
     * {@link BluetoothGattCallback#onCharacteristicWrite} callback is invoked,
     * reporting the result of the operation.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @param characteristic Characteristic to write on the remote device
     * @return true, if the write operation was initiated successfully
     */
    public boolean writeCharacteristic(BluetoothGattCharacteristic characteristic) {
        if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0
                && (characteristic.getProperties()
                & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) == 0) {
            return false;
        }

        if (VDBG) Log.d(TAG, "writeCharacteristic() - uuid: " + characteristic.getUuid());
        if (mService == null || mClientIf == 0 || characteristic.getValue() == null) return false;

        BluetoothGattService service = characteristic.getService();
        if (service == null) return false;

        BluetoothDevice device = service.getDevice();
        if (device == null) return false;

        synchronized (mDeviceBusyLock) {
            if (mDeviceBusy) return false;
            mDeviceBusy = true;
        }

        try {
            mService.writeCharacteristic(mClientIf, device.getAddress(),
                    characteristic.getInstanceId(), characteristic.getWriteType(),
                    AUTHENTICATION_NONE, characteristic.getValue());
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            mDeviceBusy = false;
            return false;
        }

        return true;
    }

    /**
     * Reads the value for a given descriptor from the associated remote device.
     *
     * <p>Once the read operation has been completed, the
     * {@link BluetoothGattCallback#onDescriptorRead} callback is
     * triggered, signaling the result of the operation.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @param descriptor Descriptor value to read from the remote device
     * @return true, if the read operation was initiated successfully
     */
    public boolean readDescriptor(BluetoothGattDescriptor descriptor) {
        if (VDBG) Log.d(TAG, "readDescriptor() - uuid: " + descriptor.getUuid());
        if (mService == null || mClientIf == 0) return false;

        BluetoothGattCharacteristic characteristic = descriptor.getCharacteristic();
        if (characteristic == null) return false;

        BluetoothGattService service = characteristic.getService();
        if (service == null) return false;

        BluetoothDevice device = service.getDevice();
        if (device == null) return false;

        synchronized (mDeviceBusyLock) {
            if (mDeviceBusy) return false;
            mDeviceBusy = true;
        }

        try {
            mService.readDescriptor(mClientIf, device.getAddress(),
                    descriptor.getInstanceId(), AUTHENTICATION_NONE);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            mDeviceBusy = false;
            return false;
        }

        return true;
    }

    /**
     * Write the value of a given descriptor to the associated remote device.
     *
     * <p>A {@link BluetoothGattCallback#onDescriptorWrite} callback is
     * triggered to report the result of the write operation.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @param descriptor Descriptor to write to the associated remote device
     * @return true, if the write operation was initiated successfully
     */
    public boolean writeDescriptor(BluetoothGattDescriptor descriptor) {
        if (VDBG) Log.d(TAG, "writeDescriptor() - uuid: " + descriptor.getUuid());
        if (mService == null || mClientIf == 0 || descriptor.getValue() == null) return false;

        BluetoothGattCharacteristic characteristic = descriptor.getCharacteristic();
        if (characteristic == null) return false;

        BluetoothGattService service = characteristic.getService();
        if (service == null) return false;

        BluetoothDevice device = service.getDevice();
        if (device == null) return false;

        synchronized (mDeviceBusyLock) {
            if (mDeviceBusy) return false;
            mDeviceBusy = true;
        }

        try {
            mService.writeDescriptor(mClientIf, device.getAddress(), descriptor.getInstanceId(),
                    AUTHENTICATION_NONE, descriptor.getValue());
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            mDeviceBusy = false;
            return false;
        }

        return true;
    }

    /**
     * Initiates a reliable write transaction for a given remote device.
     *
     * <p>Once a reliable write transaction has been initiated, all calls
     * to {@link #writeCharacteristic} are sent to the remote device for
     * verification and queued up for atomic execution. The application will
     * receive an {@link BluetoothGattCallback#onCharacteristicWrite} callback
     * in response to every {@link #writeCharacteristic} call and is responsible
     * for verifying if the value has been transmitted accurately.
     *
     * <p>After all characteristics have been queued up and verified,
     * {@link #executeReliableWrite} will execute all writes. If a characteristic
     * was not written correctly, calling {@link #abortReliableWrite} will
     * cancel the current transaction without committing any values on the
     * remote device.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @return true, if the reliable write transaction has been initiated
     */
    public boolean beginReliableWrite() {
        if (VDBG) Log.d(TAG, "beginReliableWrite() - device: " + mDevice.getAddress());
        if (mService == null || mClientIf == 0) return false;

        try {
            mService.beginReliableWrite(mClientIf, mDevice.getAddress());
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return false;
        }

        return true;
    }

    /**
     * Executes a reliable write transaction for a given remote device.
     *
     * <p>This function will commit all queued up characteristic write
     * operations for a given remote device.
     *
     * <p>A {@link BluetoothGattCallback#onReliableWriteCompleted} callback is
     * invoked to indicate whether the transaction has been executed correctly.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @return true, if the request to execute the transaction has been sent
     */
    public boolean executeReliableWrite() {
        if (VDBG) Log.d(TAG, "executeReliableWrite() - device: " + mDevice.getAddress());
        if (mService == null || mClientIf == 0) return false;

        synchronized (mDeviceBusyLock) {
            if (mDeviceBusy) return false;
            mDeviceBusy = true;
        }

        try {
            mService.endReliableWrite(mClientIf, mDevice.getAddress(), true);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            mDeviceBusy = false;
            return false;
        }

        return true;
    }

    /**
     * Cancels a reliable write transaction for a given device.
     *
     * <p>Calling this function will discard all queued characteristic write
     * operations for a given remote device.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     */
    public void abortReliableWrite() {
        if (VDBG) Log.d(TAG, "abortReliableWrite() - device: " + mDevice.getAddress());
        if (mService == null || mClientIf == 0) return;

        try {
            mService.endReliableWrite(mClientIf, mDevice.getAddress(), false);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
    }

    /**
     * @deprecated Use {@link #abortReliableWrite()}
     */
    @Deprecated
    public void abortReliableWrite(BluetoothDevice mDevice) {
        abortReliableWrite();
    }

    /**
     * Enable or disable notifications/indications for a given characteristic.
     *
     * <p>Once notifications are enabled for a characteristic, a
     * {@link BluetoothGattCallback#onCharacteristicChanged} callback will be
     * triggered if the remote device indicates that the given characteristic
     * has changed.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @param characteristic The characteristic for which to enable notifications
     * @param enable Set to true to enable notifications/indications
     * @return true, if the requested notification status was set successfully
     */
    public boolean setCharacteristicNotification(BluetoothGattCharacteristic characteristic,
            boolean enable) {
        if (DBG) {
            Log.d(TAG, "setCharacteristicNotification() - uuid: " + characteristic.getUuid()
                    + " enable: " + enable);
        }
        if (mService == null || mClientIf == 0) return false;

        BluetoothGattService service = characteristic.getService();
        if (service == null) return false;

        BluetoothDevice device = service.getDevice();
        if (device == null) return false;

        try {
            mService.registerForNotification(mClientIf, device.getAddress(),
                    characteristic.getInstanceId(), enable);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return false;
        }

        return true;
    }

    /**
     * Clears the internal cache and forces a refresh of the services from the
     * remote device.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public boolean refresh() {
        if (DBG) Log.d(TAG, "refresh() - device: " + mDevice.getAddress());
        if (mService == null || mClientIf == 0) return false;

        try {
            mService.refreshDevice(mClientIf, mDevice.getAddress());
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return false;
        }

        return true;
    }

    /**
     * Read the RSSI for a connected remote device.
     *
     * <p>The {@link BluetoothGattCallback#onReadRemoteRssi} callback will be
     * invoked when the RSSI value has been read.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @return true, if the RSSI value has been requested successfully
     */
    public boolean readRemoteRssi() {
        if (DBG) Log.d(TAG, "readRssi() - device: " + mDevice.getAddress());
        if (mService == null || mClientIf == 0) return false;

        try {
            mService.readRemoteRssi(mClientIf, mDevice.getAddress());
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return false;
        }

        return true;
    }

    /**
     * Request an MTU size used for a given connection.
     *
     * <p>When performing a write request operation (write without response),
     * the data sent is truncated to the MTU size. This function may be used
     * to request a larger MTU size to be able to send more data at once.
     *
     * <p>A {@link BluetoothGattCallback#onMtuChanged} callback will indicate
     * whether this operation was successful.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @return true, if the new MTU value has been requested successfully
     */
    public boolean requestMtu(int mtu) {
        if (DBG) {
            Log.d(TAG, "configureMTU() - device: " + mDevice.getAddress()
                    + " mtu: " + mtu);
        }
        if (mService == null || mClientIf == 0) return false;

        try {
            mService.configureMTU(mClientIf, mDevice.getAddress(), mtu);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return false;
        }

        return true;
    }

    /**
     * Request a connection parameter update.
     *
     * <p>This function will send a connection parameter update request to the
     * remote device.
     *
     * @param connectionPriority Request a specific connection priority. Must be one of {@link
     * BluetoothGatt#CONNECTION_PRIORITY_BALANCED}, {@link BluetoothGatt#CONNECTION_PRIORITY_HIGH}
     * or {@link BluetoothGatt#CONNECTION_PRIORITY_LOW_POWER}.
     * @throws IllegalArgumentException If the parameters are outside of their specified range.
     */
    public boolean requestConnectionPriority(int connectionPriority) {
        if (connectionPriority < CONNECTION_PRIORITY_BALANCED
                || connectionPriority > CONNECTION_PRIORITY_LOW_POWER) {
            throw new IllegalArgumentException("connectionPriority not within valid range");
        }

        if (DBG) Log.d(TAG, "requestConnectionPriority() - params: " + connectionPriority);
        if (mService == null || mClientIf == 0) return false;

        try {
            mService.connectionParameterUpdate(mClientIf, mDevice.getAddress(), connectionPriority);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return false;
        }

        return true;
    }

    /**
     * Request an LE connection parameter update.
     *
     * <p>This function will send an LE connection parameters update request to the remote device.
     *
     * @return true, if the request is send to the Bluetooth stack.
     * @hide
     */
    public boolean requestLeConnectionUpdate(int minConnectionInterval, int maxConnectionInterval,
                                             int slaveLatency, int supervisionTimeout,
                                             int minConnectionEventLen, int maxConnectionEventLen) {
        if (DBG) {
            Log.d(TAG, "requestLeConnectionUpdate() - min=(" + minConnectionInterval
                        + ")" + (1.25 * minConnectionInterval)
                        + "msec, max=(" + maxConnectionInterval + ")"
                        + (1.25 * maxConnectionInterval) + "msec, latency=" + slaveLatency
                        + ", timeout=" + supervisionTimeout + "msec" + ", min_ce="
                        + minConnectionEventLen + ", max_ce=" + maxConnectionEventLen);
        }
        if (mService == null || mClientIf == 0) return false;

        try {
            mService.leConnectionUpdate(mClientIf, mDevice.getAddress(),
                                        minConnectionInterval, maxConnectionInterval,
                                        slaveLatency, supervisionTimeout,
                                        minConnectionEventLen, maxConnectionEventLen);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return false;
        }

        return true;
    }

    /**
     * Not supported - please use {@link BluetoothManager#getConnectedDevices(int)}
     * with {@link BluetoothProfile#GATT} as argument
     *
     * @throws UnsupportedOperationException
     */
    @Override
    public int getConnectionState(BluetoothDevice device) {
        throw new UnsupportedOperationException("Use BluetoothManager#getConnectionState instead.");
    }

    /**
     * Not supported - please use {@link BluetoothManager#getConnectedDevices(int)}
     * with {@link BluetoothProfile#GATT} as argument
     *
     * @throws UnsupportedOperationException
     */
    @Override
    public List<BluetoothDevice> getConnectedDevices() {
        throw new UnsupportedOperationException(
                "Use BluetoothManager#getConnectedDevices instead.");
    }

    /**
     * Not supported - please use
     * {@link BluetoothManager#getDevicesMatchingConnectionStates(int, int[])}
     * with {@link BluetoothProfile#GATT} as first argument
     *
     * @throws UnsupportedOperationException
     */
    @Override
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        throw new UnsupportedOperationException(
                "Use BluetoothManager#getDevicesMatchingConnectionStates instead.");
    }
}
