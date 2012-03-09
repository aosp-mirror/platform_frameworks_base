/*
 * Copyright (C) 2010 The Android Open Source Project
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
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothHeadset;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Local cache of bonding state.
 * We keep our own state to track the intermediate state BONDING, which
 * bluez does not track.
 * All addresses must be passed in upper case.
 */
class BluetoothBondState {
    private static final String TAG = "BluetoothBondState";
    private static final boolean DBG =  true;

    private final HashMap<String, Integer> mState = new HashMap<String, Integer>();
    private final HashMap<String, Integer> mPinAttempt = new HashMap<String, Integer>();

    private static final String AUTO_PAIRING_BLACKLIST =
        "/etc/bluetooth/auto_pairing.conf";
    private static final String DYNAMIC_AUTO_PAIRING_BLACKLIST =
        "/data/misc/bluetooth/dynamic_auto_pairing.conf";
    private ArrayList<String> mAutoPairingAddressBlacklist;
    private ArrayList<String> mAutoPairingExactNameBlacklist;
    private ArrayList<String> mAutoPairingPartialNameBlacklist;
    private ArrayList<String> mAutoPairingFixedPinZerosKeyboardList;
    // Addresses added to blacklist dynamically based on usage.
    private ArrayList<String> mAutoPairingDynamicAddressBlacklist;

    // If this is an outgoing connection, store the address.
    // There can be only 1 pending outgoing connection at a time,
    private String mPendingOutgoingBonding;

    private final Context mContext;
    private final BluetoothService mService;
    private final BluetoothInputProfileHandler mBluetoothInputProfileHandler;
    private BluetoothA2dp mA2dpProxy;
    private BluetoothHeadset mHeadsetProxy;

    private ArrayList<String> mPairingRequestRcvd = new ArrayList<String>();

    BluetoothBondState(Context context, BluetoothService service) {
        mContext = context;
        mService = service;
        mBluetoothInputProfileHandler =
            BluetoothInputProfileHandler.getInstance(mContext, mService);

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
        mContext.registerReceiver(mReceiver, filter);
        readAutoPairingData();
    }

    synchronized void setPendingOutgoingBonding(String address) {
        mPendingOutgoingBonding = address;
    }

    public synchronized String getPendingOutgoingBonding() {
        return mPendingOutgoingBonding;
    }

    public synchronized void initBondState() {
        getProfileProxy();
        loadBondState();
    }

    private void loadBondState() {
        if (mService.getBluetoothStateInternal() !=
                BluetoothAdapter.STATE_TURNING_ON) {
            return;
        }
        String val = mService.getAdapterProperties().getProperty("Devices");
        if (val == null) {
            return;
        }
        String[] bonds = val.split(",");
        if (bonds == null) {
            return;
        }
        mState.clear();
        if (DBG) Log.d(TAG, "found " + bonds.length + " bonded devices");
        for (String device : bonds) {
            mState.put(mService.getAddressFromObjectPath(device).toUpperCase(),
                    BluetoothDevice.BOND_BONDED);
        }
    }

    public synchronized void setBondState(String address, int state) {
        setBondState(address, state, 0);
    }

    /** reason is ignored unless state == BOND_NOT_BONDED */
    public synchronized void setBondState(String address, int state, int reason) {
        if (DBG) Log.d(TAG, "setBondState " + "address" + " " + state + "reason: " + reason);

        int oldState = getBondState(address);
        if (oldState == state) {
            return;
        }

        // Check if this was a pending outgoing bonding.
        // If yes, reset the state.
        if (oldState == BluetoothDevice.BOND_BONDING) {
            if (address.equals(mPendingOutgoingBonding)) {
                mPendingOutgoingBonding = null;
            }
        }

        if (state == BluetoothDevice.BOND_BONDED) {
            boolean setTrust = false;
            if (mPairingRequestRcvd.contains(address)) setTrust = true;

            mService.addProfileState(address, setTrust);
            mPairingRequestRcvd.remove(address);

        } else if (state == BluetoothDevice.BOND_BONDING) {
            if (mA2dpProxy == null || mHeadsetProxy == null) {
                getProfileProxy();
            }
        } else if (state == BluetoothDevice.BOND_NONE) {
            mPairingRequestRcvd.remove(address);
        }

        setProfilePriorities(address, state);

        if (DBG) {
            Log.d(TAG, address + " bond state " + oldState + " -> " + state
                + " (" + reason + ")");
        }
        Intent intent = new Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, mService.getRemoteDevice(address));
        intent.putExtra(BluetoothDevice.EXTRA_BOND_STATE, state);
        intent.putExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, oldState);
        if (state == BluetoothDevice.BOND_NONE) {
            if (reason <= 0) {
                Log.w(TAG, "setBondState() called to unbond device, but reason code is " +
                      "invalid. Overriding reason code with BOND_RESULT_REMOVED");
                reason = BluetoothDevice.UNBOND_REASON_REMOVED;
            }
            intent.putExtra(BluetoothDevice.EXTRA_REASON, reason);
            mState.remove(address);
        } else {
            mState.put(address, state);
        }

        mContext.sendBroadcast(intent, BluetoothService.BLUETOOTH_PERM);
    }

    public boolean isAutoPairingBlacklisted(String address) {
        if (mAutoPairingAddressBlacklist != null) {
            for (String blacklistAddress : mAutoPairingAddressBlacklist) {
                if (address.startsWith(blacklistAddress)) return true;
            }
        }

        if (mAutoPairingDynamicAddressBlacklist != null) {
            for (String blacklistAddress: mAutoPairingDynamicAddressBlacklist) {
                if (address.equals(blacklistAddress)) return true;
            }
        }

        String name = mService.getRemoteName(address);
        if (name != null) {
            if (mAutoPairingExactNameBlacklist != null) {
                for (String blacklistName : mAutoPairingExactNameBlacklist) {
                    if (name.equals(blacklistName)) return true;
                }
            }

            if (mAutoPairingPartialNameBlacklist != null) {
                for (String blacklistName : mAutoPairingPartialNameBlacklist) {
                    if (name.startsWith(blacklistName)) return true;
                }
            }
        }
        return false;
    }

    public boolean isFixedPinZerosAutoPairKeyboard(String address) {
        // Note: the meaning of blacklist is reversed in this case.
        // If its in the list, we can go ahead and auto pair since
        // by default keyboard should have a variable PIN that we don't
        // auto pair using 0000.
        if (mAutoPairingFixedPinZerosKeyboardList != null) {
            for (String blacklistAddress : mAutoPairingFixedPinZerosKeyboardList) {
                if (address.startsWith(blacklistAddress)) return true;
            }
        }
        return false;
    }

    public synchronized int getBondState(String address) {
        Integer state = mState.get(address);
        if (state == null) {
            return BluetoothDevice.BOND_NONE;
        }
        return state.intValue();
    }

    /*package*/ synchronized String[] listInState(int state) {
        ArrayList<String> result = new ArrayList<String>(mState.size());
        for (Map.Entry<String, Integer> e : mState.entrySet()) {
            if (e.getValue().intValue() == state) {
                result.add(e.getKey());
            }
        }
        return result.toArray(new String[result.size()]);
    }

    public synchronized void addAutoPairingFailure(String address) {
        if (mAutoPairingDynamicAddressBlacklist == null) {
            mAutoPairingDynamicAddressBlacklist = new ArrayList<String>();
        }

        updateAutoPairingData(address);
        mAutoPairingDynamicAddressBlacklist.add(address);
    }

    public synchronized boolean isAutoPairingAttemptsInProgress(String address) {
        return getAttempt(address) != 0;
    }

    public synchronized void clearPinAttempts(String address) {
        if (DBG) Log.d(TAG, "clearPinAttempts: " + address);

        mPinAttempt.remove(address);
    }

    public synchronized boolean hasAutoPairingFailed(String address) {
        if (mAutoPairingDynamicAddressBlacklist == null) return false;

        return mAutoPairingDynamicAddressBlacklist.contains(address);
    }

    public synchronized int getAttempt(String address) {
        Integer attempt = mPinAttempt.get(address);
        if (attempt == null) {
            return 0;
        }
        return attempt.intValue();
    }

    public synchronized void attempt(String address) {
        Integer attempt = mPinAttempt.get(address);
        int newAttempt;
        if (attempt == null) {
            newAttempt = 1;
        } else {
            newAttempt = attempt.intValue() + 1;
        }
        if (DBG) Log.d(TAG, "attemp newAttempt: " + newAttempt);

        mPinAttempt.put(address, new Integer(newAttempt));
    }

    private void getProfileProxy() {
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (mA2dpProxy == null) {
            bluetoothAdapter.getProfileProxy(mContext, mProfileServiceListener,
                                             BluetoothProfile.A2DP);
        }

        if (mHeadsetProxy == null) {
            bluetoothAdapter.getProfileProxy(mContext, mProfileServiceListener,
                                             BluetoothProfile.HEADSET);
        }
    }

    private void closeProfileProxy() {
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (mA2dpProxy != null) {
            bluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, mA2dpProxy);
        }

        if (mHeadsetProxy != null) {
            bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, mHeadsetProxy);
        }
    }

    private BluetoothProfile.ServiceListener mProfileServiceListener =
        new BluetoothProfile.ServiceListener() {

        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == BluetoothProfile.A2DP) {
                mA2dpProxy = (BluetoothA2dp) proxy;
            } else if (profile == BluetoothProfile.HEADSET) {
                mHeadsetProxy = (BluetoothHeadset) proxy;
            }
        }

        public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.A2DP) {
                mA2dpProxy = null;
            } else if (profile == BluetoothProfile.HEADSET) {
                mHeadsetProxy = null;
            }
        }
    };

    private void copyAutoPairingData() {
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            File file = new File(DYNAMIC_AUTO_PAIRING_BLACKLIST);
            if (file.exists()) return;

            in = new FileInputStream(AUTO_PAIRING_BLACKLIST);
            out= new FileOutputStream(DYNAMIC_AUTO_PAIRING_BLACKLIST);

            byte[] buf = new byte[1024];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        } catch (FileNotFoundException e) {
            Log.e(TAG, "FileNotFoundException: copyAutoPairingData " + e);
        } catch (IOException e) {
            Log.e(TAG, "IOException: copyAutoPairingData " + e);
        } finally {
             try {
                 if (in != null) in.close();
                 if (out != null) out.close();
             } catch (IOException e) {}
        }
    }

    synchronized public void readAutoPairingData() {
        if (mAutoPairingAddressBlacklist != null) return;
        copyAutoPairingData();
        FileInputStream fstream = null;
        try {
            fstream = new FileInputStream(DYNAMIC_AUTO_PAIRING_BLACKLIST);
            DataInputStream in = new DataInputStream(fstream);
            BufferedReader file = new BufferedReader(new InputStreamReader(in));
            String line;
            while((line = file.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0 || line.startsWith("//")) continue;
                String[] value = line.split("=");
                if (value != null && value.length == 2) {
                    String[] val = value[1].split(",");
                    if (value[0].equalsIgnoreCase("AddressBlacklist")) {
                        mAutoPairingAddressBlacklist =
                            new ArrayList<String>(Arrays.asList(val));
                    } else if (value[0].equalsIgnoreCase("ExactNameBlacklist")) {
                        mAutoPairingExactNameBlacklist =
                            new ArrayList<String>(Arrays.asList(val));
                    } else if (value[0].equalsIgnoreCase("PartialNameBlacklist")) {
                        mAutoPairingPartialNameBlacklist =
                            new ArrayList<String>(Arrays.asList(val));
                    } else if (value[0].equalsIgnoreCase("FixedPinZerosKeyboardBlacklist")) {
                        mAutoPairingFixedPinZerosKeyboardList =
                            new ArrayList<String>(Arrays.asList(val));
                    } else if (value[0].equalsIgnoreCase("DynamicAddressBlacklist")) {
                        mAutoPairingDynamicAddressBlacklist =
                            new ArrayList<String>(Arrays.asList(val));
                    } else {
                        Log.e(TAG, "Error parsing Auto pairing blacklist file");
                    }
                }
            }
        } catch (FileNotFoundException e) {
            Log.e(TAG, "FileNotFoundException: readAutoPairingData " + e);
        } catch (IOException e) {
            Log.e(TAG, "IOException: readAutoPairingData " + e);
        } finally {
            if (fstream != null) {
                try {
                    fstream.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
    }

    // This function adds a bluetooth address to the auto pairing blacklist
    // file. These addresses are added to DynamicAddressBlacklistSection
    private void updateAutoPairingData(String address) {
        BufferedWriter out = null;
        try {
            out = new BufferedWriter(new FileWriter(DYNAMIC_AUTO_PAIRING_BLACKLIST, true));
            StringBuilder str = new StringBuilder();
            if (mAutoPairingDynamicAddressBlacklist.size() == 0) {
                str.append("DynamicAddressBlacklist=");
            }
            str.append(address);
            str.append(",");
            out.write(str.toString());
        } catch (FileNotFoundException e) {
            Log.e(TAG, "FileNotFoundException: updateAutoPairingData " + e);
        } catch (IOException e) {
            Log.e(TAG, "IOException: updateAutoPairingData " + e);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
    }

    // Set service priority of Hid, A2DP and Headset profiles depending on
    // the bond state change
    private void setProfilePriorities(String address, int state) {
        BluetoothDevice remoteDevice = mService.getRemoteDevice(address);
        // HID is handled by BluetoothService
        mBluetoothInputProfileHandler.setInitialInputDevicePriority(remoteDevice, state);

        // Set service priority of A2DP and Headset
        // We used to do the priority change in the 2 services after the broadcast
        //   intent reach them. But that left a small time gap that could reject
        //   incoming connection due to undefined priorities.
        if (state == BluetoothDevice.BOND_BONDED) {
            if (mA2dpProxy != null &&
                  mA2dpProxy.getPriority(remoteDevice) == BluetoothProfile.PRIORITY_UNDEFINED) {
                mA2dpProxy.setPriority(remoteDevice, BluetoothProfile.PRIORITY_ON);
            }

            if (mHeadsetProxy != null &&
                  mHeadsetProxy.getPriority(remoteDevice) == BluetoothProfile.PRIORITY_UNDEFINED) {
                mHeadsetProxy.setPriority(remoteDevice, BluetoothProfile.PRIORITY_ON);
            }
        } else if (state == BluetoothDevice.BOND_NONE) {
            if (mA2dpProxy != null) {
                mA2dpProxy.setPriority(remoteDevice, BluetoothProfile.PRIORITY_UNDEFINED);
            }
            if (mHeadsetProxy != null) {
                mHeadsetProxy.setPriority(remoteDevice, BluetoothProfile.PRIORITY_UNDEFINED);
            }
        }

        if (mA2dpProxy == null || mHeadsetProxy == null) {
            Log.e(TAG, "Proxy is null:" + mA2dpProxy + ":" + mHeadsetProxy);
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;

            String action = intent.getAction();
            if (action.equals(BluetoothDevice.ACTION_PAIRING_REQUEST)) {
                BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                String address = dev.getAddress();
                mPairingRequestRcvd.add(address);
            }
        }
    };
}
