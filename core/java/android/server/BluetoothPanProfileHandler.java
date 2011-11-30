/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.server;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothPan;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothTetheringDataTracker;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources.NotFoundException;
import android.net.ConnectivityManager;
import android.net.InterfaceConfiguration;
import android.net.LinkAddress;
import android.net.NetworkUtils;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.ServiceManager;
import android.util.Log;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * This handles the PAN profile. All calls into this are made
 * from Bluetooth Service.
 */
final class BluetoothPanProfileHandler {
    private static final String TAG = "BluetoothPanProfileHandler";
    private static final boolean DBG = true;

    private ArrayList<String> mBluetoothIfaceAddresses;
    private int mMaxPanDevices;

    private static final String BLUETOOTH_IFACE_ADDR_START= "192.168.44.1";
    private static final int BLUETOOTH_MAX_PAN_CONNECTIONS = 5;
    private static final int BLUETOOTH_PREFIX_LENGTH        = 24;
    public static BluetoothPanProfileHandler sInstance;
    private final HashMap<BluetoothDevice, BluetoothPanDevice> mPanDevices;
    private boolean mTetheringOn;
    private Context mContext;
    private BluetoothService mBluetoothService;

    static final String NAP_ROLE = "nap";
    static final String NAP_BRIDGE = "pan1";

    private BluetoothPanProfileHandler(Context context, BluetoothService service) {
        mContext = context;
        mPanDevices = new HashMap<BluetoothDevice, BluetoothPanDevice>();
        mBluetoothService = service;
        mTetheringOn = false;
        mBluetoothIfaceAddresses = new ArrayList<String>();
        try {
            mMaxPanDevices = context.getResources().getInteger(
                            com.android.internal.R.integer.config_max_pan_devices);
        } catch (NotFoundException e) {
            mMaxPanDevices = BLUETOOTH_MAX_PAN_CONNECTIONS;
        }
    }

    static BluetoothPanProfileHandler getInstance(Context context,
            BluetoothService service) {
        if (sInstance == null) sInstance = new BluetoothPanProfileHandler(context, service);
        return sInstance;
    }

    boolean isTetheringOn() {
        return mTetheringOn;
    }

    boolean allowIncomingTethering() {
        if (isTetheringOn() && getConnectedPanDevices().size() < mMaxPanDevices)
            return true;
        return false;
    }

    private BroadcastReceiver mTetheringReceiver = null;

    void setBluetoothTethering(boolean value) {
        if (!value) {
            disconnectPanServerDevices();
        }

        if (mBluetoothService.getBluetoothState() != BluetoothAdapter.STATE_ON && value) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            mTetheringReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF)
                            == BluetoothAdapter.STATE_ON) {
                        mTetheringOn = true;
                        mContext.unregisterReceiver(mTetheringReceiver);
                    }
                }
            };
            mContext.registerReceiver(mTetheringReceiver, filter);
        } else {
            mTetheringOn = value;
        }
    }

    int getPanDeviceConnectionState(BluetoothDevice device) {
        BluetoothPanDevice panDevice = mPanDevices.get(device);
        if (panDevice == null) {
            return BluetoothPan.STATE_DISCONNECTED;
        }
        return panDevice.mState;
    }

    boolean connectPanDevice(BluetoothDevice device) {
        String objectPath = mBluetoothService.getObjectPathFromAddress(device.getAddress());
        if (DBG) Log.d(TAG, "connect PAN(" + objectPath + ")");
        if (getPanDeviceConnectionState(device) != BluetoothPan.STATE_DISCONNECTED) {
            errorLog(device + " already connected to PAN");
        }

        int connectedCount = 0;
        for (BluetoothDevice panDevice: mPanDevices.keySet()) {
            if (getPanDeviceConnectionState(panDevice) == BluetoothPan.STATE_CONNECTED) {
                connectedCount ++;
            }
        }
        if (connectedCount > 8) {
            debugLog(device + " could not connect to PAN because 8 other devices are"
                    + "already connected");
            return false;
        }

        // Send interface as null as it is not known
        handlePanDeviceStateChange(device, null, BluetoothPan.STATE_CONNECTING,
                                           BluetoothPan.LOCAL_PANU_ROLE);
        if (mBluetoothService.connectPanDeviceNative(objectPath, "nap")) {
            debugLog("connecting to PAN");
            return true;
        } else {
            handlePanDeviceStateChange(device, null, BluetoothPan.STATE_DISCONNECTED,
                                                BluetoothPan.LOCAL_PANU_ROLE);
            errorLog("could not connect to PAN");
            return false;
        }
    }

    private boolean disconnectPanServerDevices() {
        debugLog("disconnect all PAN devices");

        for (BluetoothDevice device: mPanDevices.keySet()) {
            BluetoothPanDevice panDevice = mPanDevices.get(device);
            int state = panDevice.mState;
            if (state == BluetoothPan.STATE_CONNECTED &&
                    panDevice.mLocalRole == BluetoothPan.LOCAL_NAP_ROLE) {
                String objectPath = mBluetoothService.getObjectPathFromAddress(device.getAddress());

                handlePanDeviceStateChange(device, panDevice.mIface,
                        BluetoothPan.STATE_DISCONNECTING, panDevice.mLocalRole);

                if (!mBluetoothService.disconnectPanServerDeviceNative(objectPath,
                        device.getAddress(),
                        panDevice.mIface)) {
                    errorLog("could not disconnect Pan Server Device "+device.getAddress());

                    // Restore prev state
                    handlePanDeviceStateChange(device, panDevice.mIface, state,
                            panDevice.mLocalRole);

                    return false;
                }
            }
        }
        return true;
    }

    List<BluetoothDevice> getConnectedPanDevices() {
        List<BluetoothDevice> devices = new ArrayList<BluetoothDevice>();

        for (BluetoothDevice device: mPanDevices.keySet()) {
            if (getPanDeviceConnectionState(device) == BluetoothPan.STATE_CONNECTED) {
                devices.add(device);
            }
        }
        return devices;
    }

    List<BluetoothDevice> getPanDevicesMatchingConnectionStates(int[] states) {
        List<BluetoothDevice> devices = new ArrayList<BluetoothDevice>();

        for (BluetoothDevice device: mPanDevices.keySet()) {
            int panDeviceState = getPanDeviceConnectionState(device);
            for (int state : states) {
                if (state == panDeviceState) {
                    devices.add(device);
                    break;
                }
            }
        }
        return devices;
    }

    boolean disconnectPanDevice(BluetoothDevice device) {
        String objectPath = mBluetoothService.getObjectPathFromAddress(device.getAddress());
        debugLog("disconnect PAN(" + objectPath + ")");

        int state = getPanDeviceConnectionState(device);
        if (state != BluetoothPan.STATE_CONNECTED) {
            debugLog(device + " already disconnected from PAN");
            return false;
        }

        BluetoothPanDevice panDevice = mPanDevices.get(device);

        if (panDevice == null) {
            errorLog("No record for this Pan device:" + device);
            return false;
        }

        handlePanDeviceStateChange(device, panDevice.mIface, BluetoothPan.STATE_DISCONNECTING,
                                    panDevice.mLocalRole);
        if (panDevice.mLocalRole == BluetoothPan.LOCAL_NAP_ROLE) {
            if (!mBluetoothService.disconnectPanServerDeviceNative(objectPath, device.getAddress(),
                    panDevice.mIface)) {
                // Restore prev state, this shouldn't happen
                handlePanDeviceStateChange(device, panDevice.mIface, state, panDevice.mLocalRole);
                return false;
            }
        } else {
            if (!mBluetoothService.disconnectPanDeviceNative(objectPath)) {
                // Restore prev state, this shouldn't happen
                handlePanDeviceStateChange(device, panDevice.mIface, state, panDevice.mLocalRole);
                return false;
            }
        }
        return true;
    }

    void handlePanDeviceStateChange(BluetoothDevice device,
                                                 String iface, int state, int role) {
        int prevState;
        String ifaceAddr = null;
        BluetoothPanDevice panDevice = mPanDevices.get(device);

        if (panDevice == null) {
            prevState = BluetoothPan.STATE_DISCONNECTED;
        } else {
            prevState = panDevice.mState;
            ifaceAddr = panDevice.mIfaceAddr;
        }
        if (prevState == state) return;

        if (role == BluetoothPan.LOCAL_NAP_ROLE) {
            if (state == BluetoothPan.STATE_CONNECTED) {
                ifaceAddr = enableTethering(iface);
                if (ifaceAddr == null) Log.e(TAG, "Error seting up tether interface");
            } else if (state == BluetoothPan.STATE_DISCONNECTED) {
                if (ifaceAddr != null) {
                    mBluetoothIfaceAddresses.remove(ifaceAddr);
                    ifaceAddr = null;
                }
            }
        } else {
            // PANU Role = reverse Tether
            if (state == BluetoothPan.STATE_CONNECTED) {
                BluetoothTetheringDataTracker.getInstance().startReverseTether(iface, device);
            } else if (state == BluetoothPan.STATE_DISCONNECTED &&
                  (prevState == BluetoothPan.STATE_CONNECTED ||
                  prevState == BluetoothPan.STATE_DISCONNECTING)) {
                BluetoothTetheringDataTracker.getInstance().stopReverseTether(panDevice.mIface);
            }
        }

        if (panDevice == null) {
            panDevice = new BluetoothPanDevice(state, ifaceAddr, iface, role);
            mPanDevices.put(device, panDevice);
        } else {
            panDevice.mState = state;
            panDevice.mIfaceAddr = ifaceAddr;
            panDevice.mLocalRole = role;
            panDevice.mIface = iface;
        }

        Intent intent = new Intent(BluetoothPan.ACTION_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothPan.EXTRA_PREVIOUS_STATE, prevState);
        intent.putExtra(BluetoothPan.EXTRA_STATE, state);
        intent.putExtra(BluetoothPan.EXTRA_LOCAL_ROLE, role);
        mContext.sendBroadcast(intent, BluetoothService.BLUETOOTH_PERM);

        debugLog("Pan Device state : device: " + device + " State:" + prevState + "->" + state);
        mBluetoothService.sendConnectionStateChange(device, BluetoothProfile.PAN, state,
                                                    prevState);
    }

    private class BluetoothPanDevice {
        private int mState;
        private String mIfaceAddr;
        private String mIface;
        private int mLocalRole; // Which local role is this PAN device bound to

        BluetoothPanDevice(int state, String ifaceAddr, String iface, int localRole) {
            mState = state;
            mIfaceAddr = ifaceAddr;
            mIface = iface;
            mLocalRole = localRole;
        }
    }

    private String createNewTetheringAddressLocked() {
        if (getConnectedPanDevices().size() == mMaxPanDevices) {
            debugLog ("Max PAN device connections reached");
            return null;
        }
        String address = BLUETOOTH_IFACE_ADDR_START;
        while (true) {
            if (mBluetoothIfaceAddresses.contains(address)) {
                String[] addr = address.split("\\.");
                Integer newIp = Integer.parseInt(addr[2]) + 1;
                address = address.replace(addr[2], newIp.toString());
            } else {
                break;
            }
        }
        mBluetoothIfaceAddresses.add(address);
        return address;
    }

    // configured when we start tethering
    private String enableTethering(String iface) {
        debugLog("updateTetherState:" + iface);

        IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
        INetworkManagementService service = INetworkManagementService.Stub.asInterface(b);
        ConnectivityManager cm =
            (ConnectivityManager)mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        String[] bluetoothRegexs = cm.getTetherableBluetoothRegexs();

        // bring toggle the interfaces
        String[] currentIfaces = new String[0];
        try {
            currentIfaces = service.listInterfaces();
        } catch (Exception e) {
            Log.e(TAG, "Error listing Interfaces :" + e);
            return null;
        }

        boolean found = false;
        for (String currIface: currentIfaces) {
            if (currIface.equals(iface)) {
                found = true;
                break;
            }
        }

        if (!found) return null;

        String address = createNewTetheringAddressLocked();
        if (address == null) return null;

        InterfaceConfiguration ifcg = null;
        try {
            ifcg = service.getInterfaceConfig(iface);
            if (ifcg != null) {
                final LinkAddress linkAddr = ifcg.getLinkAddress();
                InetAddress addr = null;
                if (linkAddr == null || (addr = linkAddr.getAddress()) == null ||
                        addr.equals(NetworkUtils.numericToInetAddress("0.0.0.0")) ||
                        addr.equals(NetworkUtils.numericToInetAddress("::0"))) {
                    addr = NetworkUtils.numericToInetAddress(address);
                }
                ifcg.setInterfaceUp();
                ifcg.clearFlag("running");
                ifcg.setLinkAddress(new LinkAddress(addr, BLUETOOTH_PREFIX_LENGTH));
                service.setInterfaceConfig(iface, ifcg);
                if (cm.tether(iface) != ConnectivityManager.TETHER_ERROR_NO_ERROR) {
                    Log.e(TAG, "Error tethering "+iface);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error configuring interface " + iface + ", :" + e);
            return null;
        }
        return address;
    }

    private static void debugLog(String msg) {
        if (DBG) Log.d(TAG, msg);
    }

    private static void errorLog(String msg) {
        Log.e(TAG, msg);
    }
}
