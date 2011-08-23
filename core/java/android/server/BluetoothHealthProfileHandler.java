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
import android.bluetooth.BluetoothHealth;
import android.bluetooth.BluetoothHealthAppConfiguration;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.IBluetoothHealthCallback;
import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;

/**
 * This handles all the operations on the Bluetooth Health profile.
 * All functions are called by BluetoothService, as Bluetooth Service
 * is the Service handler for the HDP profile.
 *
 * @hide
 */
final class BluetoothHealthProfileHandler {
    private static final String TAG = "BluetoothHealthProfileHandler";
    /*STOPSHIP*/
    private static final boolean DBG = true;

    private static BluetoothHealthProfileHandler sInstance;
    private Context mContext;
    private BluetoothService mBluetoothService;
    private ArrayList<HealthChannel> mHealthChannels;
    private HashMap <BluetoothHealthAppConfiguration, String> mHealthAppConfigs;
    private HashMap <BluetoothDevice, Integer> mHealthDevices;
    private HashMap <BluetoothHealthAppConfiguration, IBluetoothHealthCallback> mCallbacks;

    private static final int MESSAGE_REGISTER_APPLICATION = 0;
    private static final int MESSAGE_UNREGISTER_APPLICATION = 1;
    private static final int MESSAGE_CONNECT_CHANNEL = 2;

    class HealthChannel {
        private ParcelFileDescriptor mChannelFd;
        private boolean mMainChannel;
        private String mChannelPath;
        private BluetoothDevice mDevice;
        private BluetoothHealthAppConfiguration mConfig;
        private int mState;
        private int mChannelType;

        HealthChannel(BluetoothDevice device, BluetoothHealthAppConfiguration config,
                ParcelFileDescriptor fd, boolean mainChannel, String channelPath) {
             mChannelFd = fd;
             mMainChannel = mainChannel;
             mChannelPath = channelPath;
             mDevice = device;
             mConfig = config;
             mState = BluetoothHealth.STATE_CHANNEL_DISCONNECTED;
        }
    }

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MESSAGE_REGISTER_APPLICATION:
                BluetoothHealthAppConfiguration registerApp =
                    (BluetoothHealthAppConfiguration) msg.obj;
                int role = registerApp.getRole();
                String path = null;

                if (role == BluetoothHealth.SINK_ROLE) {
                    path = mBluetoothService.registerHealthApplicationNative(
                            registerApp.getDataType(), getStringRole(role), registerApp.getName());
                } else {
                    path = mBluetoothService.registerHealthApplicationNative(
                            registerApp.getDataType(), getStringRole(role), registerApp.getName(),
                            getStringChannelType(registerApp.getChannelType()));
                }

                if (path == null) {
                    mCallbacks.remove(registerApp);
                    callHealthApplicationStatusCallback(registerApp,
                            BluetoothHealth.APPLICATION_REGISTRATION_FAILURE);
                } else {
                    mHealthAppConfigs.put(registerApp, path);
                    callHealthApplicationStatusCallback(registerApp,
                            BluetoothHealth.APPLICATION_REGISTRATION_SUCCESS);
                }

                break;
            case MESSAGE_UNREGISTER_APPLICATION:
                BluetoothHealthAppConfiguration unregisterApp =
                    (BluetoothHealthAppConfiguration) msg.obj;
                boolean result = mBluetoothService.unregisterHealthApplicationNative(
                        mHealthAppConfigs.get(unregisterApp));
                if (result) {
                    mCallbacks.remove(unregisterApp);
                    callHealthApplicationStatusCallback(unregisterApp,
                            BluetoothHealth.APPLICATION_UNREGISTRATION_SUCCESS);
                } else {
                    callHealthApplicationStatusCallback(unregisterApp,
                            BluetoothHealth.APPLICATION_UNREGISTRATION_FAILURE);
                }
                break;
            case MESSAGE_CONNECT_CHANNEL:
                HealthChannel chan = (HealthChannel)msg.obj;
                String deviceObjectPath =
                    mBluetoothService.getObjectPathFromAddress(chan.mDevice.getAddress());
                String configPath = mHealthAppConfigs.get(chan.mConfig);
                String channelType = getStringChannelType(chan.mChannelType);

                if (!mBluetoothService.createChannelNative(deviceObjectPath, configPath,
                          channelType)) {
                    int prevState = chan.mState;
                    int state = BluetoothHealth.STATE_CHANNEL_DISCONNECTED;
                    callHealthChannelCallback(chan.mConfig, chan.mDevice, prevState, state, null);
                    mHealthChannels.remove(chan);
                }
            }
        }
    };

    private BluetoothHealthProfileHandler(Context context, BluetoothService service) {
        mContext = context;
        mBluetoothService = service;
        mHealthAppConfigs = new HashMap<BluetoothHealthAppConfiguration, String>();
        mHealthChannels = new ArrayList<HealthChannel>();
        mHealthDevices = new HashMap<BluetoothDevice, Integer>();
        mCallbacks = new HashMap<BluetoothHealthAppConfiguration, IBluetoothHealthCallback>();
    }

    static synchronized BluetoothHealthProfileHandler getInstance(Context context,
            BluetoothService service) {
        if (sInstance == null) sInstance = new BluetoothHealthProfileHandler(context, service);
        return sInstance;
    }

    boolean registerAppConfiguration(BluetoothHealthAppConfiguration config,
                                     IBluetoothHealthCallback callback) {
        Message msg = mHandler.obtainMessage(MESSAGE_REGISTER_APPLICATION);
        msg.obj = config;
        mHandler.sendMessage(msg);
        mCallbacks.put(config, callback);
        return true;
    }

    boolean unregisterAppConfiguration(BluetoothHealthAppConfiguration config) {
        String path = mHealthAppConfigs.get(config);
        if (path == null) return false;

        Message msg = mHandler.obtainMessage(MESSAGE_UNREGISTER_APPLICATION);
        msg.obj = config;
        mHandler.sendMessage(msg);
        return true;
    }

    boolean connectChannelToSource(BluetoothDevice device,
            BluetoothHealthAppConfiguration config) {
        return connectChannel(device, config, BluetoothHealth.CHANNEL_TYPE_ANY);
    }

    private HealthChannel getMainChannel(BluetoothDevice device,
            BluetoothHealthAppConfiguration config) {
        for (HealthChannel chan: mHealthChannels) {
            if (chan.mDevice.equals(device) && chan.mConfig.equals(config)) {
                if (chan.mMainChannel) return chan;
            }
        }
        return null;
    }

    boolean connectChannel(BluetoothDevice device,
            BluetoothHealthAppConfiguration config, int channelType) {
        String deviceObjectPath =
            mBluetoothService.getObjectPathFromAddress(device.getAddress());
        if (deviceObjectPath == null) return false;

        String configPath = mHealthAppConfigs.get(config);
        if (configPath == null) return false;

        HealthChannel chan = new HealthChannel(device, config, null, false, null);
        chan.mState = BluetoothHealth.STATE_CHANNEL_CONNECTING;
        chan.mChannelType = channelType;
        mHealthChannels.add(chan);

        int prevState = BluetoothHealth.STATE_CHANNEL_DISCONNECTED;
        int state = BluetoothHealth.STATE_CHANNEL_CONNECTING;
        callHealthChannelCallback(config, device, prevState, state, null);

        Message msg = mHandler.obtainMessage(MESSAGE_CONNECT_CHANNEL);
        msg.obj = chan;
        mHandler.sendMessage(msg);

        return true;
    }

    private String getStringChannelType(int type) {
        if (type == BluetoothHealth.CHANNEL_TYPE_RELIABLE) {
            return "Reliable";
        } else if (type == BluetoothHealth.CHANNEL_TYPE_STREAMING) {
            return "Streaming";
        } else {
            return "Any";
        }
    }

    private String getStringRole(int role) {
        if (role == BluetoothHealth.SINK_ROLE) {
            return "Sink";
        } else if (role == BluetoothHealth.SOURCE_ROLE) {
            return "Streaming";
        } else {
            return null;
        }
    }

    boolean disconnectChannel(BluetoothDevice device,
            BluetoothHealthAppConfiguration config, ParcelFileDescriptor fd) {
        HealthChannel chan = findChannelByFd(device, config, fd);
        if (chan == null) return false;

        String deviceObjectPath =
                mBluetoothService.getObjectPathFromAddress(device.getAddress());
        if (mBluetoothService.destroyChannelNative(deviceObjectPath, chan.mChannelPath)) {
            int prevState = chan.mState;
            chan.mState = BluetoothHealth.STATE_CHANNEL_DISCONNECTING;
            callHealthChannelCallback(config, device, prevState, chan.mState,
                    chan.mChannelFd);
            return true;
        } else {
            return false;
        }
    }

    private HealthChannel findChannelByFd(BluetoothDevice device,
            BluetoothHealthAppConfiguration config, ParcelFileDescriptor fd) {
        for (HealthChannel chan : mHealthChannels) {
            if (chan.mChannelFd.equals(fd) && chan.mDevice.equals(device) &&
                    chan.mConfig.equals(config)) return chan;
        }
        return null;
    }

    private HealthChannel findChannelByPath(BluetoothDevice device,
            BluetoothHealthAppConfiguration config, String path) {
        for (HealthChannel chan : mHealthChannels) {
            if (chan.mChannelPath.equals(path) && chan.mDevice.equals(device) &&
                    chan.mConfig.equals(config)) return chan;
        }
        return null;
    }

    private List<HealthChannel> findChannelByStates(BluetoothDevice device, int[] states) {
        List<HealthChannel> channels = new ArrayList<HealthChannel>();
        for (HealthChannel chan: mHealthChannels) {
            if (chan.mDevice.equals(device)) {
                for (int state : states) {
                    if (chan.mState == state) {
                        channels.add(chan);
                    }
                }
            }
        }
        return channels;
    }

    private HealthChannel findConnectingChannel(BluetoothDevice device,
            BluetoothHealthAppConfiguration config) {
        for (HealthChannel chan : mHealthChannels) {
            if (chan.mDevice.equals(device) && chan.mConfig.equals(config) &&
                chan.mState == BluetoothHealth.STATE_CHANNEL_CONNECTING) return chan;
        }
        return null;
    }

    ParcelFileDescriptor getMainChannelFd(BluetoothDevice device,
            BluetoothHealthAppConfiguration config) {
        HealthChannel chan = getMainChannel(device, config);
        if (chan != null) return chan.mChannelFd;

        String objectPath =
                mBluetoothService.getObjectPathFromAddress(device.getAddress());
        if (objectPath == null) return null;

        String mainChannelPath = mBluetoothService.getMainChannelNative(objectPath);
        if (mainChannelPath == null) return null;

        // We had no record of the main channel but querying Bluez we got a
        // main channel. We might not have received the PropertyChanged yet for
        // the main channel creation so update our data structure here.
        chan = findChannelByPath(device, config, mainChannelPath);
        if (chan == null) {
            errorLog("Main Channel present but we don't have any account of it:" +
                    device +":" + config);
            return null;
        }
        chan.mMainChannel = true;
        return chan.mChannelFd;
    }

    /*package*/ void onHealthDevicePropertyChanged(String devicePath,
            String channelPath) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        String address = mBluetoothService.getAddressFromObjectPath(devicePath);
        if (address == null) return;

        //TODO: Fix this in Bluez
        if (channelPath.equals("/")) {
            // This means that the main channel is being destroyed.
            return;
        }

        BluetoothDevice device = adapter.getRemoteDevice(address);
        BluetoothHealthAppConfiguration config = findHealthApplication(device,
                channelPath);
        if (config != null) {
            HealthChannel chan = findChannelByPath(device, config, channelPath);
            if (chan == null) {
                errorLog("Health Channel is not present:" + channelPath);
            } else {
                chan.mMainChannel = true;
            }
        }
    }

    private BluetoothHealthAppConfiguration findHealthApplication(
            BluetoothDevice device, String channelPath) {
        BluetoothHealthAppConfiguration config = null;
        String configPath = mBluetoothService.getChannelApplicationNative(channelPath);

        if (configPath == null) {
            errorLog("No associated application for Health Channel:" + channelPath);
            return null;
        } else {
            for (Entry<BluetoothHealthAppConfiguration, String> e :
                    mHealthAppConfigs.entrySet()) {
                if (e.getValue().equals(configPath)) {
                    config = e.getKey();
                }
            }
            if (config == null) {
                errorLog("No associated application for application path:" + configPath);
                return null;
            }
        }
        return config;
    }

    /*package*/ void onHealthDeviceChannelChanged(String devicePath,
            String channelPath, boolean exists) {
        debugLog("onHealthDeviceChannelChanged: devicePath: " + devicePath +
                "ChannelPath: " + channelPath + "Exists: " + exists);
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        String address = mBluetoothService.getAddressFromObjectPath(devicePath);
        if (address == null) return;

        BluetoothDevice device = adapter.getRemoteDevice(address);

        BluetoothHealthAppConfiguration config = findHealthApplication(device,
                channelPath);
        int state, prevState = BluetoothHealth.STATE_CHANNEL_DISCONNECTED;
        ParcelFileDescriptor fd;
        HealthChannel channel;

        if (config != null) {
             if (exists) {
                 fd = mBluetoothService.getChannelFdNative(channelPath);

                 if (fd == null) {
                     errorLog("Error obtaining fd for channel:" + channelPath);
                     return;
                 }

                 boolean mainChannel =
                         getMainChannel(device, config) == null ? false : true;
                 if (!mainChannel) {
                     String mainChannelPath =
                             mBluetoothService.getMainChannelNative(devicePath);
                     if (mainChannelPath == null) {
                         errorLog("Main Channel Path is null for devicePath:" + devicePath);
                         return;
                     }
                     if (mainChannelPath.equals(channelPath)) mainChannel = true;
                 }

                 channel = findConnectingChannel(device, config);
                 if (channel != null) {
                    channel.mChannelFd = fd;
                    channel.mMainChannel = mainChannel;
                    channel.mChannelPath = channelPath;
                    prevState = channel.mState;
                 } else {
                    channel = new HealthChannel(device, config, fd, mainChannel,
                            channelPath);
                    mHealthChannels.add(channel);
                    prevState = BluetoothHealth.STATE_CHANNEL_DISCONNECTED;
                 }
                 state = BluetoothHealth.STATE_CHANNEL_CONNECTED;
             } else {
                 channel = findChannelByPath(device, config, channelPath);
                 if (channel == null) {
                     errorLog("Channel not found:" + config + ":" + channelPath);
                     return;
                 }

                 fd = channel.mChannelFd;
                 // CLOSE FD
                 mBluetoothService.releaseChannelFdNative(channel.mChannelPath);
                 mHealthChannels.remove(channel);

                 prevState = channel.mState;
                 state = BluetoothHealth.STATE_CHANNEL_DISCONNECTED;
             }
             channel.mState = state;
             callHealthChannelCallback(config, device, prevState, state, fd);
        }
    }

    private void callHealthChannelCallback(BluetoothHealthAppConfiguration config,
            BluetoothDevice device, int prevState, int state, ParcelFileDescriptor fd) {
        broadcastHealthDeviceStateChange(device, prevState, state);

        debugLog("Health Device Callback: " + device + " State Change: "
                + prevState + "->" + state);
        IBluetoothHealthCallback callback = mCallbacks.get(config);
        if (callback != null) {
            try {
                callback.onHealthChannelStateChange(config, device, prevState, state, fd);
            } catch (RemoteException e) {}
        }
    }

    private void callHealthApplicationStatusCallback(
            BluetoothHealthAppConfiguration config, int status) {
        debugLog("Health Device Application: " + config + " State Change: status:"
                + status);
        IBluetoothHealthCallback callback = mCallbacks.get(config);
        if (callback != null) {
            try {
                callback.onHealthAppConfigurationStatusChange(config, status);
            } catch (RemoteException e) {}
        }
    }

    int getHealthDeviceConnectionState(BluetoothDevice device) {
        if (mHealthDevices.get(device) == null) {
            return BluetoothHealth.STATE_DISCONNECTED;
        }
        return mHealthDevices.get(device);
    }

    List<BluetoothDevice> getConnectedHealthDevices() {
        List<BluetoothDevice> devices = lookupHealthDevicesMatchingStates(
                    new int[] {BluetoothHealth.STATE_CONNECTED});
        return devices;
    }

    List<BluetoothDevice> getHealthDevicesMatchingConnectionStates(int[] states) {
        List<BluetoothDevice> devices = lookupHealthDevicesMatchingStates(states);
        return devices;
    }

    List<BluetoothDevice> lookupHealthDevicesMatchingStates(int[] states) {
        List<BluetoothDevice> healthDevices = new ArrayList<BluetoothDevice>();

        for (BluetoothDevice device: mHealthDevices.keySet()) {
            int healthDeviceState = getHealthDeviceConnectionState(device);
            for (int state : states) {
                if (state == healthDeviceState) {
                    healthDevices.add(device);
                    break;
                }
            }
        }
        return healthDevices;
    }

    /**
     * This function sends the intent for the updates on the connection status to the remote device.
     * Note that multiple channels can be connected to the remote device by multiple applications.
     * This sends an intent for the update to the device connection status and not the channel
     * connection status. Only the following state transitions are possible:
     *
     * {@link BluetoothHealth#STATE_DISCONNECTED} to {@link BluetoothHealth#STATE_CONNECTING}
     * {@link BluetoothHealth#STATE_CONNECTING} to {@link BluetoothHealth#STATE_CONNECTED}
     * {@link BluetoothHealth#STATE_CONNECTED} to {@link BluetoothHealth#STATE_DISCONNECTING}
     * {@link BluetoothHealth#STATE_DISCONNECTING} to {@link BluetoothHealth#STATE_DISCONNECTED}
     * {@link BluetoothHealth#STATE_DISCONNECTED} to {@link BluetoothHealth#STATE_CONNECTED}
     * {@link BluetoothHealth#STATE_CONNECTED} to {@link BluetoothHealth#STATE_DISCONNECTED}
     * {@link BluetoothHealth#STATE_CONNECTING} to {{@link BluetoothHealth#STATE_DISCONNECTED}
     *
     * @param device
     * @param prevChannelState
     * @param newChannelState
     * @hide
     */
    private void broadcastHealthDeviceStateChange(BluetoothDevice device, int prevChannelState,
            int newChannelState) {
        if (mHealthDevices.get(device) == null) {
            mHealthDevices.put(device, BluetoothHealth.STATE_DISCONNECTED);
        }

        int currDeviceState = mHealthDevices.get(device);
        int newDeviceState = convertState(newChannelState);

        if (currDeviceState != newDeviceState) {
            List<HealthChannel> chan;
            switch (currDeviceState) {
                case BluetoothHealth.STATE_DISCONNECTED:
                    updateAndSendIntent(device, currDeviceState, newDeviceState);
                    break;
                case BluetoothHealth.STATE_CONNECTING:
                    // Channel got connected.
                    if (newDeviceState == BluetoothHealth.STATE_CONNECTED) {
                        updateAndSendIntent(device, currDeviceState, newDeviceState);
                    } else {
                        // Channel got disconnected
                        chan = findChannelByStates(device, new int [] {
                                    BluetoothHealth.STATE_CHANNEL_CONNECTING,
                                    BluetoothHealth.STATE_CHANNEL_DISCONNECTING});
                        if (chan.isEmpty()) {
                            updateAndSendIntent(device, currDeviceState, newDeviceState);
                        }
                    }
                    break;
                case BluetoothHealth.STATE_CONNECTED:
                    // Channel got disconnected or is in disconnecting state.
                    chan = findChannelByStates(device, new int [] {
                                BluetoothHealth.STATE_CHANNEL_CONNECTING,
                                BluetoothHealth.STATE_CHANNEL_CONNECTED});
                    if (chan.isEmpty()) {
                        updateAndSendIntent(device, currDeviceState, newDeviceState);
                    }
                    break;
                case BluetoothHealth.STATE_DISCONNECTING:
                    // Channel got disconnected.
                    chan = findChannelByStates(device, new int [] {
                                BluetoothHealth.STATE_CHANNEL_CONNECTING,
                                BluetoothHealth.STATE_CHANNEL_DISCONNECTING});
                    if (chan.isEmpty()) {
                        updateAndSendIntent(device, currDeviceState, newDeviceState);
                    }
                    break;
            }
        }
    }

    private void updateAndSendIntent(BluetoothDevice device, int prevDeviceState,
            int newDeviceState) {
        mHealthDevices.put(device, newDeviceState);
        mBluetoothService.sendConnectionStateChange(device, BluetoothProfile.HEALTH,
                                                    newDeviceState, prevDeviceState);
    }

    /**
     * This function converts the channel connection state to device connection state.
     *
     * @param state
     * @return
     */
    private int convertState(int state) {
        switch (state) {
            case BluetoothHealth.STATE_CHANNEL_CONNECTED:
                return BluetoothHealth.STATE_CONNECTED;
            case BluetoothHealth.STATE_CHANNEL_CONNECTING:
                return BluetoothHealth.STATE_CONNECTING;
            case BluetoothHealth.STATE_CHANNEL_DISCONNECTING:
                return BluetoothHealth.STATE_DISCONNECTING;
            case BluetoothHealth.STATE_CHANNEL_DISCONNECTED:
                return BluetoothHealth.STATE_DISCONNECTED;
        }
        errorLog("Mismatch in Channel and Health Device State");
        return -1;
    }

    private static void debugLog(String msg) {
        if (DBG) Log.d(TAG, msg);
    }

    private static void errorLog(String msg) {
        Log.e(TAG, msg);
    }
}
