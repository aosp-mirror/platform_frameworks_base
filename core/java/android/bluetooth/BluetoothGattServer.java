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
 * Public API for the Bluetooth Gatt Profile server role.
 *
 * <p>This class provides Bluetooth Gatt server role functionality,
 * allowing applications to create and advertise Bluetooth Smart services
 * and characteristics.
 *
 * <p>BluetoothGattServer is a proxy object for controlling the Bluetooth Service
 * via IPC.  Use {@link BluetoothAdapter#getProfileProxy} to get the
 * BluetoothGatt proxy object.
 * @hide
 */
public final class BluetoothGattServer implements BluetoothProfile {
    private static final String TAG = "BluetoothGattServer";
    private static final boolean DBG = true;

    private Context mContext;
    private ServiceListener mServiceListener;
    private BluetoothAdapter mAdapter;
    private IBluetoothGatt mService;
    private BluetoothGattServerCallback mCallback;
    private int mServerIf;

    private List<BluetoothGattService> mServices;

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
                        try {
                            mService = null;
                            mContext.unbindService(mConnection);
                        } catch (Exception re) {
                            Log.e(TAG,"",re);
                        }
                    }
                } else {
                    synchronized (mConnection) {
                        try {
                            if (mService == null) {
                                if (DBG) Log.d(TAG,"Binding service...");
                                if (!mContext.bindService(new
                                        Intent(IBluetoothGatt.class.getName()),
                                        mConnection, 0)) {
                                    Log.e(TAG, "Could not bind to Bluetooth GATT Service");
                                }
                            }
                        } catch (Exception re) {
                            Log.e(TAG,"",re);
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
                ServiceListener serviceListner = mServiceListener;
                if (serviceListner != null) {
                    serviceListner.onServiceConnected(BluetoothProfile.GATT_SERVER,
                                                      BluetoothGattServer.this);
                }
            }
            public void onServiceDisconnected(ComponentName className) {
                if (DBG) Log.d(TAG, "Proxy object disconnected");
                mService = null;
                ServiceListener serviceListner = mServiceListener;
                if (serviceListner != null) {
                    serviceListner.onServiceDisconnected(BluetoothProfile.GATT_SERVER);
                }
            }
        };

    /**
     * Bluetooth GATT interface callbacks
     */
    private final IBluetoothGattServerCallback mBluetoothGattServerCallback =
        new IBluetoothGattServerCallback.Stub() {
            /**
             * Application interface registered - app is ready to go
             * @hide
             */
            public void onServerRegistered(int status, int serverIf) {
                if (DBG) Log.d(TAG, "onServerRegistered() - status=" + status
                    + " serverIf=" + serverIf);
                mServerIf = serverIf;
                try {
                    mCallback.onAppRegistered(status);
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
                    mCallback.onScanResult(mAdapter.getRemoteDevice(address),
                                           rssi, advData);
                } catch (Exception ex) {
                    Log.w(TAG, "Unhandled exception: " + ex);
                }
            }

            /**
             * Server connection state changed
             * @hide
             */
            public void onServerConnectionState(int status, int serverIf,
                                                boolean connected, String address) {
                if (DBG) Log.d(TAG, "onServerConnectionState() - status=" + status
                    + " serverIf=" + serverIf + " device=" + address);
                try {
                    mCallback.onConnectionStateChange(mAdapter.getRemoteDevice(address), status,
                                                      connected ? BluetoothProfile.STATE_CONNECTED :
                                                      BluetoothProfile.STATE_DISCONNECTED);
                } catch (Exception ex) {
                    Log.w(TAG, "Unhandled exception: " + ex);
                }
            }

            /**
             * Service has been added
             * @hide
             */
            public void onServiceAdded(int status, int srvcType,
                                       int srvcInstId, ParcelUuid srvcId) {
                UUID srvcUuid = srvcId.getUuid();
                if (DBG) Log.d(TAG, "onServiceAdded() - service=" + srvcUuid
                    + "status=" + status);

                BluetoothGattService service = getService(srvcUuid, srvcInstId, srvcType);
                if (service == null) return;

                try {
                    mCallback.onServiceAdded((int)status, service);
                } catch (Exception ex) {
                    Log.w(TAG, "Unhandled exception: " + ex);
                }
            }

            /**
             * Remote client characteristic read request.
             * @hide
             */
            public void onCharacteristicReadRequest(String address, int transId,
                            int offset, boolean isLong, int srvcType, int srvcInstId,
                            ParcelUuid srvcId, int charInstId, ParcelUuid charId) {
                UUID srvcUuid = srvcId.getUuid();
                UUID charUuid = charId.getUuid();
                if (DBG) Log.d(TAG, "onCharacteristicReadRequest() - "
                    + "service=" + srvcUuid + ", characteristic=" + charUuid);

                BluetoothDevice device = mAdapter.getRemoteDevice(address);
                BluetoothGattService service = getService(srvcUuid, srvcInstId, srvcType);
                if (service == null) return;

                BluetoothGattCharacteristic characteristic = service.getCharacteristic(
                    charUuid);
                if (characteristic == null) return;

                try {
                    mCallback.onCharacteristicReadRequest(device, transId, offset, characteristic);
                } catch (Exception ex) {
                    Log.w(TAG, "Unhandled exception: " + ex);
                }
            }

            /**
             * Remote client descriptor read request.
             * @hide
             */
            public void onDescriptorReadRequest(String address, int transId,
                            int offset, boolean isLong, int srvcType, int srvcInstId,
                            ParcelUuid srvcId, int charInstId, ParcelUuid charId,
                            ParcelUuid descrId) {
                UUID srvcUuid = srvcId.getUuid();
                UUID charUuid = charId.getUuid();
                UUID descrUuid = descrId.getUuid();
                if (DBG) Log.d(TAG, "onCharacteristicReadRequest() - "
                    + "service=" + srvcUuid + ", characteristic=" + charUuid
                    + "descriptor=" + descrUuid);

                BluetoothDevice device = mAdapter.getRemoteDevice(address);
                BluetoothGattService service = getService(srvcUuid, srvcInstId, srvcType);
                if (service == null) return;

                BluetoothGattCharacteristic characteristic = service.getCharacteristic(charUuid);
                if (characteristic == null) return;

                BluetoothGattDescriptor descriptor = characteristic.getDescriptor(descrUuid);
                if (descriptor == null) return;

                try {
                    mCallback.onDescriptorReadRequest(device, transId, offset, descriptor);
                } catch (Exception ex) {
                    Log.w(TAG, "Unhandled exception: " + ex);
                }
            }

            /**
             * Remote client characteristic write request.
             * @hide
             */
            public void onCharacteristicWriteRequest(String address, int transId,
                            int offset, int length, boolean isPrep, boolean needRsp,
                            int srvcType, int srvcInstId, ParcelUuid srvcId,
                            int charInstId, ParcelUuid charId, byte[] value) {
                UUID srvcUuid = srvcId.getUuid();
                UUID charUuid = charId.getUuid();
                if (DBG) Log.d(TAG, "onCharacteristicWriteRequest() - "
                    + "service=" + srvcUuid + ", characteristic=" + charUuid);

                BluetoothDevice device = mAdapter.getRemoteDevice(address);
                BluetoothGattService service = getService(srvcUuid, srvcInstId, srvcType);
                if (service == null) return;

                BluetoothGattCharacteristic characteristic = service.getCharacteristic(charUuid);
                if (characteristic == null) return;

                try {
                    mCallback.onCharacteristicWriteRequest(device, transId, characteristic,
                                                           isPrep, needRsp, offset, value);
                } catch (Exception ex) {
                    Log.w(TAG, "Unhandled exception: " + ex);
                }

            }

            /**
             * Remote client descriptor write request.
             * @hide
             */
            public void onDescriptorWriteRequest(String address, int transId,
                            int offset, int length, boolean isPrep, boolean needRsp,
                            int srvcType, int srvcInstId, ParcelUuid srvcId,
                            int charInstId, ParcelUuid charId, ParcelUuid descrId,
                            byte[] value) {
                UUID srvcUuid = srvcId.getUuid();
                UUID charUuid = charId.getUuid();
                UUID descrUuid = descrId.getUuid();
                if (DBG) Log.d(TAG, "onDescriptorWriteRequest() - "
                    + "service=" + srvcUuid + ", characteristic=" + charUuid
                    + "descriptor=" + descrUuid);

                BluetoothDevice device = mAdapter.getRemoteDevice(address);

                BluetoothGattService service = getService(srvcUuid, srvcInstId, srvcType);
                if (service == null) return;

                BluetoothGattCharacteristic characteristic = service.getCharacteristic(charUuid);
                if (characteristic == null) return;

                BluetoothGattDescriptor descriptor = characteristic.getDescriptor(descrUuid);
                if (descriptor == null) return;

                try {
                    mCallback.onDescriptorWriteRequest(device, transId, descriptor,
                                                       isPrep, needRsp, offset, value);
                } catch (Exception ex) {
                    Log.w(TAG, "Unhandled exception: " + ex);
                }
            }

            /**
             * Execute pending writes.
             * @hide
             */
            public void onExecuteWrite(String address, int transId,
                                       boolean execWrite) {
                if (DBG) Log.d(TAG, "onExecuteWrite() - "
                    + "device=" + address + ", transId=" + transId
                    + "execWrite=" + execWrite);

                BluetoothDevice device = mAdapter.getRemoteDevice(address);
                if (device == null) return;

                try {
                    mCallback.onExecuteWrite(device, transId, execWrite);
                } catch (Exception ex) {
                    Log.w(TAG, "Unhandled exception: " + ex);
                }
            }
        };

    /**
     * Create a BluetoothGattServer proxy object.
     */
    /*package*/ BluetoothGattServer(Context context, ServiceListener l) {
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
                try {
                    mService = null;
                    mContext.unbindService(mConnection);
                } catch (Exception re) {
                    Log.e(TAG,"",re);
                }
            }
        }
    }

    /**
     * Returns a service by UUID, instance and type.
     * @hide
     */
    /*package*/ BluetoothGattService getService(UUID uuid, int instanceId, int type) {
        for(BluetoothGattService svc : mServices) {
            if (svc.getType() == type &&
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
    public boolean registerApp(BluetoothGattServerCallback callback) {
        if (DBG) Log.d(TAG, "registerApp()");
        if (mService == null) return false;

        mCallback = callback;
        UUID uuid = UUID.randomUUID();
        if (DBG) Log.d(TAG, "registerApp() - UUID=" + uuid);

        try {
            mService.registerServer(new ParcelUuid(uuid), mBluetoothGattServerCallback);
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
        if (DBG) Log.d(TAG, "unregisterApp() - mServerIf=" + mServerIf);
        if (mService == null || mServerIf == 0) return;

        try {
            mCallback = null;
            mService.unregisterServer(mServerIf);
            mServerIf = 0;
        } catch (RemoteException e) {
            Log.e(TAG,"",e);
        }
    }

    /**
     * Starts a scan for Bluetooth LE devices.
     *
     * <p>Results of the scan are reported using the
     * {@link BluetoothGattServerCallback#onScanResult} callback.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @return true, if the scan was started successfully
     */
    public boolean startScan() {
        if (DBG) Log.d(TAG, "startScan()");
        if (mService == null || mServerIf == 0) return false;

        try {
            mService.startScan(mServerIf, true);
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
     * {@link BluetoothGattServerCallback#onScanResult} callback.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @param serviceUuids Array of services to look for
     * @return true, if the scan was started successfully
     */
    public boolean startScan(UUID[] serviceUuids) {
        if (DBG) Log.d(TAG, "startScan() - with UUIDs");
        if (mService == null || mServerIf == 0) return false;

        try {
            ParcelUuid[] uuids = new ParcelUuid[serviceUuids.length];
            for(int i = 0; i != uuids.length; ++i) {
                uuids[i] = new ParcelUuid(serviceUuids[i]);
            }
            mService.startScanWithUuids(mServerIf, true, uuids);
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
        if (mService == null || mServerIf == 0) return;

        try {
            mService.stopScan(mServerIf, true);
        } catch (RemoteException e) {
            Log.e(TAG,"",e);
        }
    }

    /**
     * Initiate a connection to a Bluetooth Gatt capable device.
     *
     * <p>The connection may not be established right away, but will be
     * completed when the remote device is available. A
     * {@link BluetoothGattServerCallback#onConnectionStateChange} callback will be
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
        if (mService == null || mServerIf == 0) return false;

        try {
            mService.serverConnect(mServerIf, device.getAddress(),
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
        if (DBG) Log.d(TAG, "cancelConnection() - device: " + device.getAddress());
        if (mService == null || mServerIf == 0) return;

        try {
            mService.serverDisconnect(mServerIf, device.getAddress());
        } catch (RemoteException e) {
            Log.e(TAG,"",e);
        }
    }

    /**
     * Send a response to a read or write request to a remote device.
     *
     * <p>This function must be invoked in when a remote read/write request
     * is received by one of these callback methots:
     *
     * <ul>
     *      <li>{@link BluetoothGattServerCallback#onCharacteristicReadRequest}
     *      <li>{@link BluetoothGattServerCallback#onCharacteristicWriteRequest}
     *      <li>{@link BluetoothGattServerCallback#onDescriptorReadRequest}
     *      <li>{@link BluetoothGattServerCallback#onDescriptorWriteRequest}
     * </ul>
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @param device The remote device to send this response to
     * @param requestId The ID of the request that was received with the callback
     * @param status The status of the request to be sent to the remote devices
     * @param offset Value offset for partial read/write response
     * @param value The value of the attribute that was read/written (optional)
     */
    public boolean sendResponse(BluetoothDevice device, int requestId,
                                int status, int offset, byte[] value) {
        if (DBG) Log.d(TAG, "sendResponse() - device: " + device.getAddress());
        if (mService == null || mServerIf == 0) return false;

        try {
            mService.sendResponse(mServerIf, device.getAddress(), requestId,
                                  status, offset, value);
        } catch (RemoteException e) {
            Log.e(TAG,"",e);
            return false;
        }
        return true;
    }

    /**
     * Send a notification or indication that a local characteristic has been
     * updated.
     *
     * <p>A notification or indication is sent to the remote device to signal
     * that the characteristic has been updated. This function should be invoked
     * for every client that requests notifications/indications by writing
     * to the "Client Configuration" descriptor for the given characteristic.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @param device The remote device to receive the notification/indication
     * @param characteristic The local characteristic that has been updated
     * @param confirm true to request confirmation from the client (indication),
     *                false to send a notification
     * @return true, if the notification has been triggered successfully
     */
    public boolean notifyCharacteristicChanged(BluetoothDevice device,
                    BluetoothGattCharacteristic characteristic, boolean confirm) {
        if (DBG) Log.d(TAG, "notifyCharacteristicChanged() - device: " + device.getAddress());
        if (mService == null || mServerIf == 0) return false;

        BluetoothGattService service = characteristic.getService();
        if (service == null) return false;

        try {
            mService.sendNotification(mServerIf, device.getAddress(),
                    service.getType(), service.getInstanceId(),
                    new ParcelUuid(service.getUuid()), characteristic.getInstanceId(),
                    new ParcelUuid(characteristic.getUuid()), confirm,
                    characteristic.getValue());
        } catch (RemoteException e) {
            Log.e(TAG,"",e);
            return false;
        }

        return true;
    }

    /**
     * Add a service to the list of services to be advertised.
     *
     * <p>Once a service has been addded to the the list, the service and it's
     * included characteristics will be advertised by the local device.
     *
     * <p>If the local device is already advertising services when this function
     * is called, a service update notification will be sent to all clients.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @param service Service to be added to the list of services advertised
     *                by this device.
     * @return true, if the service has been added successfully
     */
    public boolean addService(BluetoothGattService service) {
        if (DBG) Log.d(TAG, "addService() - service: " + service.getUuid());
        if (mService == null || mServerIf == 0) return false;

        mServices.add(service);

        try {
            mService.beginServiceDeclaration(mServerIf, service.getType(),
                service.getInstanceId(), service.getHandles(),
                new ParcelUuid(service.getUuid()));

            List<BluetoothGattService> includedServices = service.getIncludedServices();
            for (BluetoothGattService includedService : includedServices) {
                mService.addIncludedService(mServerIf,
                    includedService.getType(),
                    includedService.getInstanceId(),
                    new ParcelUuid(includedService.getUuid()));
            }

            List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
            for (BluetoothGattCharacteristic characteristic : characteristics) {
                int permission = ((characteristic.getKeySize() - 7) << 12)
                                    + characteristic.getPermissions();
                mService.addCharacteristic(mServerIf,
                    new ParcelUuid(characteristic.getUuid()),
                    characteristic.getProperties(), permission);

                List<BluetoothGattDescriptor> descriptors = characteristic.getDescriptors();
                for (BluetoothGattDescriptor descriptor: descriptors) {
                    mService.addDescriptor(mServerIf,
                        new ParcelUuid(descriptor.getUuid()),
                        descriptor.getPermissions());
                }
            }

            mService.endServiceDeclaration(mServerIf);
        } catch (RemoteException e) {
            Log.e(TAG,"",e);
            return false;
        }

        return true;
    }

    /**
     * Removes a service from the list of services to be advertised.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @param service Service to beremoved.
     * @return true, if the service has been removed
     */
    public boolean removeService(BluetoothGattService service) {
        if (DBG) Log.d(TAG, "removeService() - service: " + service.getUuid());
        if (mService == null || mServerIf == 0) return false;

        BluetoothGattService intService = getService(service.getUuid(),
                                service.getInstanceId(), service.getType());
        if (intService == null) return false;

        try {
            mService.removeService(mServerIf, service.getType(),
                service.getInstanceId(), new ParcelUuid(service.getUuid()));
            mServices.remove(intService);
        } catch (RemoteException e) {
            Log.e(TAG,"",e);
            return false;
        }

        return true;
    }

    /**
     * Remove all services from the list of advertised services.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     */
    public void clearServices() {
        if (DBG) Log.d(TAG, "clearServices()");
        if (mService == null || mServerIf == 0) return;

        try {
            mService.clearServices(mServerIf);
            mServices.clear();
        } catch (RemoteException e) {
            Log.e(TAG,"",e);
        }
    }

    /**
     * Returns a list of GATT services offered bu this device.
     *
     * <p>An application must call {@link #addService} to add a serice to the
     * list of services offered by this device.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @return List of services. Returns an empty list
     *         if no services have been added yet.
     */
    public List<BluetoothGattService> getServices() {
        return mServices;
    }

    /**
     * Returns a {@link BluetoothGattService} from the list of services offered
     * by this device.
     *
     * <p>If multiple instances of the same service (as identified by UUID)
     * exist, the first instance of the service is returned.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @param uuid UUID of the requested service
     * @return BluetoothGattService if supported, or null if the requested
     *         service is not offered by this device.
     */
    public BluetoothGattService getService(UUID uuid) {
        for (BluetoothGattService service : mServices) {
            if (service.getUuid().equals(uuid)) {
                return service;
            }
        }

        return null;
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
