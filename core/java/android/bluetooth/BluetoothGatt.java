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


import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothProfile.ServiceListener;
import android.bluetooth.IBluetoothManager;
import android.bluetooth.IBluetoothStateChangeCallback;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Public API for the Bluetooth Gatt Profile.
 *
 * <p>This class provides Bluetooth Gatt functionality to enable communication
 * with Bluetooth Smart or Smart Ready devices.
 *
 * <p>BluetoothGatt is a proxy object for controlling the Bluetooth Service
 * via IPC.  Use {@link BluetoothAdapter#getProfileProxy} to get the
 * BluetoothGatt proxy object.
 *
 * <p>To connect to a remote peripheral device, create a {@link BluetoothGattCallback}
 * and call {@link #registerApp} to register your application. Gatt capable
 * devices can be discovered using the {@link #startScan} function or the
 * regular Bluetooth device discovery process.
 * @hide
 */
public final class BluetoothGatt implements BluetoothProfile {
    private static final String TAG = "BluetoothGatt";
    private static final boolean DBG = true;

    private Context mContext;
    private ServiceListener mServiceListener;
    private BluetoothAdapter mAdapter;
    private IBluetoothGatt mService;
    private BluetoothGattCallback mCallback;
    private int mClientIf;
    private boolean mAuthRetry = false;

    private List<BluetoothGattService> mServices;

    /** A Gatt operation completed successfully */
    public static final int GATT_SUCCESS = 0;

    /** Gatt read operation is not permitted */
    public static final int GATT_READ_NOT_PERMITTED = 0x2;

    /** Gatt write operation is not permitted */
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

    /**
     * No authentication required.
     * @hide
     */
    /*package*/ static final int AUTHENTICATION_NONE = 0;

    /**
     * Authentication requested; no man-in-the-middle protection required.
     * @hide
     */
    /*package*/ static final int AUTHENTICATION_NO_MITM = 1;

    /**
     * Authentication with man-in-the-middle protection requested.
     * @hide
     */
    /*package*/ static final int AUTHENTICATION_MITM = 2;

    /**
     * Bluetooth state change handlers
     */
    private final IBluetoothStateChangeCallback mBluetoothStateChangeCallback =
        new IBluetoothStateChangeCallback.Stub() {
            public void onBluetoothStateChange(boolean up) {
                if (DBG) Log.d(TAG, "onBluetoothStateChange: up=" + up);
                if (!up) {
                    if (DBG) Log.d(TAG,"Unbinding service...");
                    synchronized (mConnection) {
                        mService = null;
                        mContext.unbindService(mConnection);
                    }
                } else {
                    synchronized (mConnection) {
                        if (mService == null) {
                            if (DBG) Log.d(TAG,"Binding service...");
                            if (!mContext.bindService(new Intent(IBluetoothGatt.class.getName()),
                                                      mConnection, 0)) {
                                Log.e(TAG, "Could not bind to Bluetooth GATT Service");
                            }
                        }
                    }
                }
            }
        };

    /**
     * Service binder handling
     */
    private ServiceConnection mConnection = new ServiceConnection() {
            public void onServiceConnected(ComponentName className, IBinder service) {
                if (DBG) Log.d(TAG, "Proxy object connected");
                mService = IBluetoothGatt.Stub.asInterface(service);
                ServiceListener serviceListener = mServiceListener;
                if (serviceListener != null) {
                    serviceListener.onServiceConnected(BluetoothProfile.GATT, BluetoothGatt.this);
                }
            }
            public void onServiceDisconnected(ComponentName className) {
                if (DBG) Log.d(TAG, "Proxy object disconnected");
                mService = null;
                ServiceListener serviceListener = mServiceListener;
                if (serviceListener != null) {
                    serviceListener.onServiceDisconnected(BluetoothProfile.GATT);
                }
            }
        };

    /**
     * Bluetooth GATT interface callbacks
     */
    private final IBluetoothGattCallback mBluetoothGattCallback =
        new IBluetoothGattCallback.Stub() {
            /**
             * Application interface registered - app is ready to go
             * @hide
             */
            public void onClientRegistered(int status, int clientIf) {
                if (DBG) Log.d(TAG, "onClientRegistered() - status=" + status
                    + " clientIf=" + clientIf);
                mClientIf = clientIf;
                try {
                    mCallback.onAppRegistered(status);
                } catch (Exception ex) {
                    Log.w(TAG, "Unhandled exception: " + ex);
                }
            }

            /**
             * Client connection state changed
             * @hide
             */
            public void onClientConnectionState(int status, int clientIf,
                                                boolean connected, String address) {
                if (DBG) Log.d(TAG, "onClientConnectionState() - status=" + status
                                 + " clientIf=" + clientIf + " device=" + address);
                try {
                    mCallback.onConnectionStateChange(mAdapter.getRemoteDevice(address), status,
                                                      connected ? BluetoothProfile.STATE_CONNECTED
                                                      : BluetoothProfile.STATE_DISCONNECTED);
                } catch (Exception ex) {
                    Log.w(TAG, "Unhandled exception: " + ex);
                }
            }

            /**
             * Callback reporting an LE scan result.
             * @hide
             */
            public void onScanResult(String address, int rssi, byte[] advData) {
                if (DBG) Log.d(TAG, "onScanResult() - Device=" + address + " RSSI=" +rssi);

                try {
                    mCallback.onScanResult(mAdapter.getRemoteDevice(address), rssi, advData);
                } catch (Exception ex) {
                    Log.w(TAG, "Unhandled exception: " + ex);
                }
            }

            /**
             * A new GATT service has been discovered.
             * The service is added to the internal list and the search
             * continues.
             * @hide
             */
            public void onGetService(String address, int srvcType,
                                     int srvcInstId, ParcelUuid srvcUuid) {
                if (DBG) Log.d(TAG, "onGetService() - Device=" + address + " UUID=" + srvcUuid);
                BluetoothDevice device = mAdapter.getRemoteDevice(address);
                mServices.add(new BluetoothGattService(device, srvcUuid.getUuid(),
                                                       srvcInstId, srvcType));
            }

            /**
             * An included service has been found durig GATT discovery.
             * The included service is added to the respective parent.
             * @hide
             */
            public void onGetIncludedService(String address, int srvcType,
                                             int srvcInstId, ParcelUuid srvcUuid,
                                             int inclSrvcType, int inclSrvcInstId,
                                             ParcelUuid inclSrvcUuid) {
                if (DBG) Log.d(TAG, "onGetIncludedService() - Device=" + address
                    + " UUID=" + srvcUuid + " Included=" + inclSrvcUuid);

                BluetoothDevice device = mAdapter.getRemoteDevice(address);
                BluetoothGattService service = getService(device,
                        srvcUuid.getUuid(), srvcInstId, srvcType);
                BluetoothGattService includedService = getService(device,
                        inclSrvcUuid.getUuid(), inclSrvcInstId, inclSrvcType);

                if (service != null && includedService != null) {
                    service.addIncludedService(includedService);
                }
            }

            /**
             * A new GATT characteristic has been discovered.
             * Add the new characteristic to the relevant service and continue
             * the remote device inspection.
             * @hide
             */
            public void onGetCharacteristic(String address, int srvcType,
                             int srvcInstId, ParcelUuid srvcUuid,
                             int charInstId, ParcelUuid charUuid,
                             int charProps) {
                if (DBG) Log.d(TAG, "onGetCharacteristic() - Device=" + address + " UUID=" +
                               charUuid);

                BluetoothDevice device = mAdapter.getRemoteDevice(address);
                BluetoothGattService service = getService(device, srvcUuid.getUuid(),
                                                          srvcInstId, srvcType);
                if (service != null) {
                    service.addCharacteristic(new BluetoothGattCharacteristic(
                           service, charUuid.getUuid(), charInstId, charProps, 0));
                }
            }

            /**
             * A new GATT descriptor has been discovered.
             * Finally, add the descriptor to the related characteristic.
             * This should conclude the remote device update.
             * @hide
             */
            public void onGetDescriptor(String address, int srvcType,
                             int srvcInstId, ParcelUuid srvcUuid,
                             int charInstId, ParcelUuid charUuid,
                             ParcelUuid descUuid) {
                if (DBG) Log.d(TAG, "onGetDescriptor() - Device=" + address + " UUID=" + descUuid);

                BluetoothDevice device = mAdapter.getRemoteDevice(address);
                BluetoothGattService service = getService(device, srvcUuid.getUuid(),
                                                          srvcInstId, srvcType);
                if (service == null) return;

                BluetoothGattCharacteristic characteristic = service.getCharacteristic(
                    charUuid.getUuid());
                if (characteristic == null) return;

                characteristic.addDescriptor(new BluetoothGattDescriptor(
                    characteristic, descUuid.getUuid(), 0));
            }

            /**
             * Remote search has been completed.
             * The internal object structure should now reflect the state
             * of the remote device database. Let the application know that
             * we are done at this point.
             * @hide
             */
            public void onSearchComplete(String address, int status) {
                if (DBG) Log.d(TAG, "onSearchComplete() = Device=" + address + " Status=" + status);
                BluetoothDevice device = mAdapter.getRemoteDevice(address);
                try {
                    mCallback.onServicesDiscovered(device, status);
                } catch (Exception ex) {
                    Log.w(TAG, "Unhandled exception: " + ex);
                }
            }

            /**
             * Remote characteristic has been read.
             * Updates the internal value.
             * @hide
             */
            public void onCharacteristicRead(String address, int status, int srvcType,
                             int srvcInstId, ParcelUuid srvcUuid,
                             int charInstId, ParcelUuid charUuid, byte[] value) {
                if (DBG) Log.d(TAG, "onCharacteristicRead() - Device=" + address
                            + " UUID=" + charUuid + " Status=" + status);

                if ((status == GATT_INSUFFICIENT_AUTHENTICATION
                  || status == GATT_INSUFFICIENT_ENCRYPTION)
                  && mAuthRetry == false) {
                    try {
                        mAuthRetry = true;
                        mService.readCharacteristic(mClientIf, address,
                            srvcType, srvcInstId, srvcUuid,
                            charInstId, charUuid, AUTHENTICATION_MITM);
                        return;
                    } catch (RemoteException e) {
                        Log.e(TAG,"",e);
                    }
                }

                mAuthRetry = false;

                BluetoothDevice device = mAdapter.getRemoteDevice(address);
                BluetoothGattService service = getService(device, srvcUuid.getUuid(),
                                                          srvcInstId, srvcType);
                if (service == null) return;

                BluetoothGattCharacteristic characteristic = service.getCharacteristic(
                        charUuid.getUuid(), charInstId);
                if (characteristic == null) return;

                if (status == 0) characteristic.setValue(value);

                try {
                    mCallback.onCharacteristicRead(characteristic, status);
                } catch (Exception ex) {
                    Log.w(TAG, "Unhandled exception: " + ex);
                }
            }

            /**
             * Characteristic has been written to the remote device.
             * Let the app know how we did...
             * @hide
             */
            public void onCharacteristicWrite(String address, int status, int srvcType,
                             int srvcInstId, ParcelUuid srvcUuid,
                             int charInstId, ParcelUuid charUuid) {
                if (DBG) Log.d(TAG, "onCharacteristicWrite() - Device=" + address
                            + " UUID=" + charUuid + " Status=" + status);

                BluetoothDevice device = mAdapter.getRemoteDevice(address);
                BluetoothGattService service = getService(device, srvcUuid.getUuid(),
                                                          srvcInstId, srvcType);
                if (service == null) return;

                BluetoothGattCharacteristic characteristic = service.getCharacteristic(
                        charUuid.getUuid(), charInstId);
                if (characteristic == null) return;

                if ((status == GATT_INSUFFICIENT_AUTHENTICATION
                  || status == GATT_INSUFFICIENT_ENCRYPTION)
                  && mAuthRetry == false) {
                    try {
                        mAuthRetry = true;
                        mService.writeCharacteristic(mClientIf, address,
                            srvcType, srvcInstId, srvcUuid, charInstId, charUuid,
                            characteristic.getWriteType(), AUTHENTICATION_MITM,
                            characteristic.getValue());
                        return;
                    } catch (RemoteException e) {
                        Log.e(TAG,"",e);
                    }
                }

                mAuthRetry = false;

                try {
                    mCallback.onCharacteristicWrite(characteristic, status);
                } catch (Exception ex) {
                    Log.w(TAG, "Unhandled exception: " + ex);
                }
            }

            /**
             * Remote characteristic has been updated.
             * Updates the internal value.
             * @hide
             */
            public void onNotify(String address, int srvcType,
                             int srvcInstId, ParcelUuid srvcUuid,
                             int charInstId, ParcelUuid charUuid,
                             byte[] value) {
                if (DBG) Log.d(TAG, "onNotify() - Device=" + address + " UUID=" + charUuid);

                BluetoothDevice device = mAdapter.getRemoteDevice(address);
                BluetoothGattService service = getService(device, srvcUuid.getUuid(),
                                                          srvcInstId, srvcType);
                if (service == null) return;

                BluetoothGattCharacteristic characteristic = service.getCharacteristic(
                        charUuid.getUuid(), charInstId);
                if (characteristic == null) return;

                characteristic.setValue(value);

                try {
                    mCallback.onCharacteristicChanged(characteristic);
                } catch (Exception ex) {
                    Log.w(TAG, "Unhandled exception: " + ex);
                }
            }

            /**
             * Descriptor has been read.
             * @hide
             */
            public void onDescriptorRead(String address, int status, int srvcType,
                             int srvcInstId, ParcelUuid srvcUuid,
                             int charInstId, ParcelUuid charUuid,
                             ParcelUuid descrUuid, byte[] value) {
                if (DBG) Log.d(TAG, "onDescriptorRead() - Device=" + address + " UUID=" + charUuid);

                BluetoothDevice device = mAdapter.getRemoteDevice(address);
                BluetoothGattService service = getService(device, srvcUuid.getUuid(),
                                                          srvcInstId, srvcType);
                if (service == null) return;

                BluetoothGattCharacteristic characteristic = service.getCharacteristic(
                        charUuid.getUuid(), charInstId);
                if (characteristic == null) return;

                BluetoothGattDescriptor descriptor = characteristic.getDescriptor(
                        descrUuid.getUuid());
                if (descriptor == null) return;

                if (status == 0) descriptor.setValue(value);

                if ((status == GATT_INSUFFICIENT_AUTHENTICATION
                  || status == GATT_INSUFFICIENT_ENCRYPTION)
                  && mAuthRetry == false) {
                    try {
                        mAuthRetry = true;
                        mService.readDescriptor(mClientIf, address,
                            srvcType, srvcInstId, srvcUuid, charInstId, charUuid,
                            descrUuid, AUTHENTICATION_MITM);
                    } catch (RemoteException e) {
                        Log.e(TAG,"",e);
                    }
                }

                mAuthRetry = true;

                try {
                    mCallback.onDescriptorRead(descriptor, status);
                } catch (Exception ex) {
                    Log.w(TAG, "Unhandled exception: " + ex);
                }
            }

            /**
             * Descriptor write operation complete.
             * @hide
             */
            public void onDescriptorWrite(String address, int status, int srvcType,
                             int srvcInstId, ParcelUuid srvcUuid,
                             int charInstId, ParcelUuid charUuid,
                             ParcelUuid descrUuid) {
                if (DBG) Log.d(TAG, "onDescriptorWrite() - Device=" + address + " UUID=" + charUuid);

                BluetoothDevice device = mAdapter.getRemoteDevice(address);
                BluetoothGattService service = getService(device, srvcUuid.getUuid(),
                                                          srvcInstId, srvcType);
                if (service == null) return;

                BluetoothGattCharacteristic characteristic = service.getCharacteristic(
                        charUuid.getUuid(), charInstId);
                if (characteristic == null) return;

                BluetoothGattDescriptor descriptor = characteristic.getDescriptor(
                        descrUuid.getUuid());
                if (descriptor == null) return;

                if ((status == GATT_INSUFFICIENT_AUTHENTICATION
                  || status == GATT_INSUFFICIENT_ENCRYPTION)
                  && mAuthRetry == false) {
                    try {
                        mAuthRetry = true;
                        mService.writeDescriptor(mClientIf, address,
                            srvcType, srvcInstId, srvcUuid, charInstId, charUuid,
                            descrUuid, characteristic.getWriteType(),
                            AUTHENTICATION_MITM, descriptor.getValue());
                    } catch (RemoteException e) {
                        Log.e(TAG,"",e);
                    }
                }

                mAuthRetry = false;

                try {
                    mCallback.onDescriptorWrite(descriptor, status);
                } catch (Exception ex) {
                    Log.w(TAG, "Unhandled exception: " + ex);
                }
            }

            /**
             * Prepared write transaction completed (or aborted)
             * @hide
             */
            public void onExecuteWrite(String address, int status) {
                if (DBG) Log.d(TAG, "onExecuteWrite() - Device=" + address
                    + " status=" + status);
                BluetoothDevice device = mAdapter.getRemoteDevice(address);
                try {
                    mCallback.onReliableWriteCompleted(device, status);
                } catch (Exception ex) {
                    Log.w(TAG, "Unhandled exception: " + ex);
                }
            }

            /**
             * Remote device RSSI has been read
             * @hide
             */
            public void onReadRemoteRssi(String address, int rssi, int status) {
                if (DBG) Log.d(TAG, "onReadRemoteRssi() - Device=" + address +
                            " rssi=" + rssi + " status=" + status);
                BluetoothDevice device = mAdapter.getRemoteDevice(address);
                try {
                    mCallback.onReadRemoteRssi(device, rssi, status);
                } catch (Exception ex) {
                    Log.w(TAG, "Unhandled exception: " + ex);
                }
            }
        };

    /**
     * Create a BluetoothGatt proxy object.
     */
    /*package*/ BluetoothGatt(Context context, ServiceListener l) {
        mContext = context;
        mServiceListener = l;
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mServices = new ArrayList<BluetoothGattService>();

        IBinder b = ServiceManager.getService(BluetoothAdapter.BLUETOOTH_MANAGER_SERVICE);
        if (b != null) {
            IBluetoothManager mgr = IBluetoothManager.Stub.asInterface(b);
            try {
                mgr.registerStateChangeCallback(mBluetoothStateChangeCallback);
            } catch (RemoteException re) {
                Log.e(TAG, "Unable to register BluetoothStateChangeCallback", re);
            }
        } else {
            Log.e(TAG, "Unable to get BluetoothManager interface.");
            throw new RuntimeException("BluetoothManager inactive");
        }

        //Bind to the service only if the Bluetooth is ON
        if(mAdapter.isEnabled()){
            if (!context.bindService(new Intent(IBluetoothGatt.class.getName()), mConnection, 0)) {
                Log.e(TAG, "Could not bind to Bluetooth Gatt Service");
            }
        }
    }

    /**
     * Close the connection to the gatt service.
     */
    /*package*/ void close() {
        if (DBG) Log.d(TAG, "close()");

        unregisterApp();
        mServiceListener = null;

        IBinder b = ServiceManager.getService(BluetoothAdapter.BLUETOOTH_MANAGER_SERVICE);
        if (b != null) {
            IBluetoothManager mgr = IBluetoothManager.Stub.asInterface(b);
            try {
                mgr.unregisterStateChangeCallback(mBluetoothStateChangeCallback);
            } catch (RemoteException re) {
                Log.e(TAG, "Unable to unregister BluetoothStateChangeCallback", re);
            }
        }

        synchronized (mConnection) {
            if (mService != null) {
                mService = null;
                mContext.unbindService(mConnection);
            }
        }
    }

    /**
     * Returns a service by UUID, instance and type.
     * @hide
     */
    /*package*/ BluetoothGattService getService(BluetoothDevice device, UUID uuid,
                                                int instanceId, int type) {
        for(BluetoothGattService svc : mServices) {
            if (svc.getDevice().equals(device) &&
                svc.getType() == type &&
                svc.getInstanceId() == instanceId &&
                svc.getUuid().equals(uuid)) {
                return svc;
            }
        }
        return null;
    }


    /**
     * Register an application callback to start using Gatt.
     *
     * <p>This is an asynchronous call. The callback is used to notify
     * success or failure if the function returns true.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @param callback Gatt callback handler that will receive asynchronous
     *          callbacks.
     * @return true, if application was successfully registered.
     */
    public boolean registerApp(BluetoothGattCallback callback) {
        if (DBG) Log.d(TAG, "registerApp()");
        if (mService == null) return false;

        mCallback = callback;
        UUID uuid = UUID.randomUUID();
        if (DBG) Log.d(TAG, "registerApp() - UUID=" + uuid);

        try {
            mService.registerClient(new ParcelUuid(uuid), mBluetoothGattCallback);
        } catch (RemoteException e) {
            Log.e(TAG,"",e);
            return false;
        }

        return true;
    }

    /**
     * Unregister the current application and callbacks.
     */
    public void unregisterApp() {
        if (DBG) Log.d(TAG, "unregisterApp() - mClientIf=" + mClientIf);
        if (mService == null || mClientIf == 0) return;

        try {
            mCallback = null;
            mService.unregisterClient(mClientIf);
            mClientIf = 0;
        } catch (RemoteException e) {
            Log.e(TAG,"",e);
        }
    }

    /**
     * Starts a scan for Bluetooth LE devices.
     *
     * <p>Results of the scan are reported using the
     * {@link BluetoothGattCallback#onScanResult} callback.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @return true, if the scan was started successfully
     */
    public boolean startScan() {
        if (DBG) Log.d(TAG, "startScan()");
        if (mService == null || mClientIf == 0) return false;

        try {
            mService.startScan(mClientIf, false);
        } catch (RemoteException e) {
            Log.e(TAG,"",e);
            return false;
        }

        return true;
    }

    /**
     * Starts a scan for Bluetooth LE devices, looking for devices that
     * advertise given services.
     *
     * <p>Devices which advertise all specified services are reported using the
     * {@link BluetoothGattCallback#onScanResult} callback.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @param serviceUuids Array of services to look for
     * @return true, if the scan was started successfully
     */
    public boolean startScan(UUID[] serviceUuids) {
        if (DBG) Log.d(TAG, "startScan() - with UUIDs");
        if (mService == null || mClientIf == 0) return false;

        try {
            ParcelUuid[] uuids = new ParcelUuid[serviceUuids.length];
            for(int i = 0; i != uuids.length; ++i) {
                uuids[i] = new ParcelUuid(serviceUuids[i]);
            }
            mService.startScanWithUuids(mClientIf, false, uuids);
        } catch (RemoteException e) {
            Log.e(TAG,"",e);
            return false;
        }

        return true;
    }

    /**
     * Stops an ongoing Bluetooth LE device scan.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     */
    public void stopScan() {
        if (DBG) Log.d(TAG, "stopScan()");
        if (mService == null || mClientIf == 0) return;

        try {
            mService.stopScan(mClientIf, false);
        } catch (RemoteException e) {
            Log.e(TAG,"",e);
        }
    }

    /**
     * Initiate a connection to a Bluetooth Gatt capable device.
     *
     * <p>The connection may not be established right away, but will be
     * completed when the remote device is available. A
     * {@link BluetoothGattCallback#onConnectionStateChange} callback will be
     * invoked when the connection state changes as a result of this function.
     *
     * <p>The autoConnect paramter determines whether to actively connect to
     * the remote device, or rather passively scan and finalize the connection
     * when the remote device is in range/available. Generally, the first ever
     * connection to a device should be direct (autoConnect set to false) and
     * subsequent connections to known devices should be invoked with the
     * autoConnect parameter set to false.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @param device Remote device to connect to
     * @param autoConnect Whether to directly connect to the remote device (false)
     *                    or to automatically connect as soon as the remote
     *                    device becomes available (true).
     * @return true, if the connection attempt was initiated successfully
     */
    public boolean connect(BluetoothDevice device, boolean autoConnect) {
        if (DBG) Log.d(TAG, "connect() - device: " + device.getAddress() + ", auto: " + autoConnect);
        if (mService == null || mClientIf == 0) return false;

        try {
            mService.clientConnect(mClientIf, device.getAddress(),
                                   autoConnect ? false : true); // autoConnect is inverse of "isDirect"
        } catch (RemoteException e) {
            Log.e(TAG,"",e);
            return false;
        }

        return true;
    }

    /**
     * Disconnects an established connection, or cancels a connection attempt
     * currently in progress.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @param device Remote device
     */
    public void cancelConnection(BluetoothDevice device) {
        if (DBG) Log.d(TAG, "cancelOpen() - device: " + device.getAddress());
        if (mService == null || mClientIf == 0) return;

        try {
            mService.clientDisconnect(mClientIf, device.getAddress());
        } catch (RemoteException e) {
            Log.e(TAG,"",e);
        }
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
     * @param device Remote device to explore
     * @return true, if the remote service discovery has been started
     */
    public boolean discoverServices(BluetoothDevice device) {
        if (DBG) Log.d(TAG, "discoverServices() - device: " + device.getAddress());
        if (mService == null || mClientIf == 0) return false;

        mServices.clear();

        try {
            mService.discoverServices(mClientIf, device.getAddress());
        } catch (RemoteException e) {
            Log.e(TAG,"",e);
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
     * @param device Remote device
     * @return List of services on the remote device. Returns an empty list
     *         if service discovery has not yet been performed.
     */
    public List<BluetoothGattService> getServices(BluetoothDevice device) {
        List<BluetoothGattService> result =
                new ArrayList<BluetoothGattService>();

        for (BluetoothGattService service : mServices) {
            if (service.getDevice().equals(device)) {
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
     * @param device Remote device
     * @param uuid UUID of the requested service
     * @return BluetoothGattService if supported, or null if the requested
     *         service is not offered by the remote device.
     */
    public BluetoothGattService getService(BluetoothDevice device, UUID uuid) {
        for (BluetoothGattService service : mServices) {
            if (service.getDevice().equals(device) &&
                service.getUuid().equals(uuid)) {
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
        if ((characteristic.getProperties() &
                BluetoothGattCharacteristic.PROPERTY_READ) == 0) return false;

        if (DBG) Log.d(TAG, "readCharacteristic() - uuid: " + characteristic.getUuid());
        if (mService == null || mClientIf == 0) return false;

        BluetoothGattService service = characteristic.getService();
        if (service == null) return false;

        BluetoothDevice device = service.getDevice();
        if (device == null) return false;

        try {
            mService.readCharacteristic(mClientIf, device.getAddress(),
                service.getType(), service.getInstanceId(),
                new ParcelUuid(service.getUuid()), characteristic.getInstanceId(),
                new ParcelUuid(characteristic.getUuid()), AUTHENTICATION_NONE);
        } catch (RemoteException e) {
            Log.e(TAG,"",e);
            return false;
        }

        return true;
    }

    /**
     * Writes a given characteristic and it's values to the associated remote
     * device.
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
            && (characteristic.getProperties() &
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) == 0) return false;

        if (DBG) Log.d(TAG, "writeCharacteristic() - uuid: " + characteristic.getUuid());
        if (mService == null || mClientIf == 0) return false;

        BluetoothGattService service = characteristic.getService();
        if (service == null) return false;

        BluetoothDevice device = service.getDevice();
        if (device == null) return false;

        try {
            mService.writeCharacteristic(mClientIf, device.getAddress(),
                service.getType(), service.getInstanceId(),
                new ParcelUuid(service.getUuid()), characteristic.getInstanceId(),
                new ParcelUuid(characteristic.getUuid()),
                characteristic.getWriteType(), AUTHENTICATION_NONE,
                characteristic.getValue());
        } catch (RemoteException e) {
            Log.e(TAG,"",e);
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
        if (DBG) Log.d(TAG, "readDescriptor() - uuid: " + descriptor.getUuid());
        if (mService == null || mClientIf == 0) return false;

        BluetoothGattCharacteristic characteristic = descriptor.getCharacteristic();
        if (characteristic == null) return false;

        BluetoothGattService service = characteristic.getService();
        if (service == null) return false;

        BluetoothDevice device = service.getDevice();
        if (device == null) return false;

        try {
            mService.readDescriptor(mClientIf, device.getAddress(),
                service.getType(), service.getInstanceId(),
                new ParcelUuid(service.getUuid()), characteristic.getInstanceId(),
                new ParcelUuid(characteristic.getUuid()),
                new ParcelUuid(descriptor.getUuid()), AUTHENTICATION_NONE);
        } catch (RemoteException e) {
            Log.e(TAG,"",e);
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
        if (DBG) Log.d(TAG, "writeDescriptor() - uuid: " + descriptor.getUuid());
        if (mService == null || mClientIf == 0) return false;

        BluetoothGattCharacteristic characteristic = descriptor.getCharacteristic();
        if (characteristic == null) return false;

        BluetoothGattService service = characteristic.getService();
        if (service == null) return false;

        BluetoothDevice device = service.getDevice();
        if (device == null) return false;

        try {
            mService.writeDescriptor(mClientIf, device.getAddress(),
                service.getType(), service.getInstanceId(),
                new ParcelUuid(service.getUuid()), characteristic.getInstanceId(),
                new ParcelUuid(characteristic.getUuid()),
                new ParcelUuid(descriptor.getUuid()),
                characteristic.getWriteType(), AUTHENTICATION_NONE,
                descriptor.getValue());
        } catch (RemoteException e) {
            Log.e(TAG,"",e);
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
     * cancel the current transaction without commiting any values on the
     * remote device.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @param device Remote device
     * @return true, if the reliable write transaction has been initiated
     */
    public boolean beginReliableWrite(BluetoothDevice device) {
        if (DBG) Log.d(TAG, "beginReliableWrite() - device: " + device.getAddress());
        if (mService == null || mClientIf == 0) return false;

        try {
            mService.beginReliableWrite(mClientIf, device.getAddress());
        } catch (RemoteException e) {
            Log.e(TAG,"",e);
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
     * @param device Remote device
     * @return true, if the request to execute the transaction has been sent
     */
    public boolean executeReliableWrite(BluetoothDevice device) {
        if (DBG) Log.d(TAG, "executeReliableWrite() - device: " + device.getAddress());
        if (mService == null || mClientIf == 0) return false;

        try {
            mService.endReliableWrite(mClientIf, device.getAddress(), true);
        } catch (RemoteException e) {
            Log.e(TAG,"",e);
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
     *
     * @param device Remote device
     */
    public void abortReliableWrite(BluetoothDevice device) {
        if (DBG) Log.d(TAG, "abortReliableWrite() - device: " + device.getAddress());
        if (mService == null || mClientIf == 0) return;

        try {
            mService.endReliableWrite(mClientIf, device.getAddress(), false);
        } catch (RemoteException e) {
            Log.e(TAG,"",e);
        }
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
        if (DBG) Log.d(TAG, "setCharacteristicNotification() - uuid: " + characteristic.getUuid()
                         + " enable: " + enable);
        if (mService == null || mClientIf == 0) return false;

        BluetoothGattService service = characteristic.getService();
        if (service == null) return false;

        BluetoothDevice device = service.getDevice();
        if (device == null) return false;

        try {
            mService.registerForNotification(mClientIf, device.getAddress(),
                service.getType(), service.getInstanceId(),
                new ParcelUuid(service.getUuid()), characteristic.getInstanceId(),
                new ParcelUuid(characteristic.getUuid()),
                enable);
        } catch (RemoteException e) {
            Log.e(TAG,"",e);
            return false;
        }

        return true;
    }

    /**
     * Clears the internal cache and forces a refresh of the services from the
     * remote device.
     * @hide
     */
    public boolean refresh(BluetoothDevice device) {
        if (DBG) Log.d(TAG, "refresh() - device: " + device.getAddress());
        if (mService == null || mClientIf == 0) return false;

        try {
            mService.refreshDevice(mClientIf, device.getAddress());
        } catch (RemoteException e) {
            Log.e(TAG,"",e);
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
     * @param device Remote device
     * @return true, if the RSSI value has been requested successfully
     */
    public boolean readRemoteRssi(BluetoothDevice device) {
        if (DBG) Log.d(TAG, "readRssi() - device: " + device.getAddress());
        if (mService == null || mClientIf == 0) return false;

        try {
            mService.readRemoteRssi(mClientIf, device.getAddress());
        } catch (RemoteException e) {
            Log.e(TAG,"",e);
            return false;
        }

        return true;
    }

    /**
     * Get the current connection state of the profile.
     *
     * <p>This is not specific to any application configuration but represents
     * the connection state of the local Bluetooth adapter for this profile.
     * This can be used by applications like status bar which would just like
     * to know the state of the local adapter.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @param device Remote bluetooth device.
     * @return State of the profile connection. One of
     *               {@link #STATE_CONNECTED}, {@link #STATE_CONNECTING},
     *               {@link #STATE_DISCONNECTED}, {@link #STATE_DISCONNECTING}
     */
    @Override
    public int getConnectionState(BluetoothDevice device) {
        if (DBG) Log.d(TAG,"getConnectionState()");
        if (mService == null) return STATE_DISCONNECTED;

        List<BluetoothDevice> connectedDevices = getConnectedDevices();
        for(BluetoothDevice connectedDevice : connectedDevices) {
            if (device.equals(connectedDevice)) {
                return STATE_CONNECTED;
            }
        }

        return STATE_DISCONNECTED;
    }

    /**
     * Get connected devices for the Gatt profile.
     *
     * <p> Return the set of devices which are in state {@link #STATE_CONNECTED}
     *
     * <p>This is not specific to any application configuration but represents
     * the connection state of the local Bluetooth adapter for this profile.
     * This can be used by applications like status bar which would just like
     * to know the state of the local adapter.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @return List of devices. The list will be empty on error.
     */
    @Override
    public List<BluetoothDevice> getConnectedDevices() {
        if (DBG) Log.d(TAG,"getConnectedDevices");

        List<BluetoothDevice> connectedDevices = new ArrayList<BluetoothDevice>();
        if (mService == null) return connectedDevices;

        try {
            connectedDevices = mService.getDevicesMatchingConnectionStates(
                new int[] { BluetoothProfile.STATE_CONNECTED });
        } catch (RemoteException e) {
            Log.e(TAG,"",e);
        }

        return connectedDevices;
    }

    /**
     * Get a list of devices that match any of the given connection
     * states.
     *
     * <p> If none of the devices match any of the given states,
     * an empty list will be returned.
     *
     * <p>This is not specific to any application configuration but represents
     * the connection state of the local Bluetooth adapter for this profile.
     * This can be used by applications like status bar which would just like
     * to know the state of the local adapter.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @param states Array of states. States can be one of
     *              {@link #STATE_CONNECTED}, {@link #STATE_CONNECTING},
     *              {@link #STATE_DISCONNECTED}, {@link #STATE_DISCONNECTING},
     * @return List of devices. The list will be empty on error.
     */
    @Override
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        if (DBG) Log.d(TAG,"getDevicesMatchingConnectionStates");

        List<BluetoothDevice> devices = new ArrayList<BluetoothDevice>();
        if (mService == null) return devices;

        try {
            devices = mService.getDevicesMatchingConnectionStates(states);
        } catch (RemoteException e) {
            Log.e(TAG,"",e);
        }

        return devices;
    }
}
