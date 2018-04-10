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

package com.android.settingslib.bluetooth;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothA2dpSink;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothHeadsetClient;
import android.bluetooth.BluetoothHearingAid;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidHost;
import android.bluetooth.BluetoothMap;
import android.bluetooth.BluetoothMapClient;
import android.bluetooth.BluetoothPan;
import android.bluetooth.BluetoothPbap;
import android.bluetooth.BluetoothPbapClient;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.content.Context;
import android.content.Intent;
import android.os.ParcelUuid;
import android.support.annotation.VisibleForTesting;
import android.util.Log;
import com.android.internal.R;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;


/**
 * LocalBluetoothProfileManager provides access to the LocalBluetoothProfile
 * objects for the available Bluetooth profiles.
 */
public class LocalBluetoothProfileManager {
    private static final String TAG = "LocalBluetoothProfileManager";
    private static final boolean DEBUG = Utils.D;
    /** Singleton instance. */
    private static LocalBluetoothProfileManager sInstance;

    /**
     * An interface for notifying BluetoothHeadset IPC clients when they have
     * been connected to the BluetoothHeadset service.
     * Only used by com.android.settings.bluetooth.DockService.
     */
    public interface ServiceListener {
        /**
         * Called to notify the client when this proxy object has been
         * connected to the BluetoothHeadset service. Clients must wait for
         * this callback before making IPC calls on the BluetoothHeadset
         * service.
         */
        void onServiceConnected();

        /**
         * Called to notify the client that this proxy object has been
         * disconnected from the BluetoothHeadset service. Clients must not
         * make IPC calls on the BluetoothHeadset service after this callback.
         * This callback will currently only occur if the application hosting
         * the BluetoothHeadset service, but may be called more often in future.
         */
        void onServiceDisconnected();
    }

    private final Context mContext;
    private final LocalBluetoothAdapter mLocalAdapter;
    private final CachedBluetoothDeviceManager mDeviceManager;
    private final BluetoothEventManager mEventManager;

    private A2dpProfile mA2dpProfile;
    private A2dpSinkProfile mA2dpSinkProfile;
    private HeadsetProfile mHeadsetProfile;
    private HfpClientProfile mHfpClientProfile;
    private MapProfile mMapProfile;
    private MapClientProfile mMapClientProfile;
    private final HidProfile mHidProfile;
    private HidDeviceProfile mHidDeviceProfile;
    private OppProfile mOppProfile;
    private final PanProfile mPanProfile;
    private PbapClientProfile mPbapClientProfile;
    private final PbapServerProfile mPbapProfile;
    private final boolean mUsePbapPce;
    private final boolean mUseMapClient;
    private HearingAidProfile mHearingAidProfile;

    /**
     * Mapping from profile name, e.g. "HEADSET" to profile object.
     */
    private final Map<String, LocalBluetoothProfile>
            mProfileNameMap = new HashMap<String, LocalBluetoothProfile>();

    LocalBluetoothProfileManager(Context context,
            LocalBluetoothAdapter adapter,
            CachedBluetoothDeviceManager deviceManager,
            BluetoothEventManager eventManager) {
        mContext = context;

        mLocalAdapter = adapter;
        mDeviceManager = deviceManager;
        mEventManager = eventManager;
        mUsePbapPce = mContext.getResources().getBoolean(R.bool.enable_pbap_pce_profile);
        // MAP Client is typically used in the same situations as PBAP Client
        mUseMapClient = mContext.getResources().getBoolean(R.bool.enable_pbap_pce_profile);
        // pass this reference to adapter and event manager (circular dependency)
        mLocalAdapter.setProfileManager(this);
        mEventManager.setProfileManager(this);

        ParcelUuid[] uuids = adapter.getUuids();

        // uuids may be null if Bluetooth is turned off
        if (uuids != null) {
            updateLocalProfiles(uuids);
        }

        // Always add HID host, HID device, and PAN profiles
        mHidProfile = new HidProfile(context, mLocalAdapter, mDeviceManager, this);
        addProfile(mHidProfile, HidProfile.NAME,
                BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED);

        mPanProfile = new PanProfile(context, mLocalAdapter);
        addPanProfile(mPanProfile, PanProfile.NAME,
                BluetoothPan.ACTION_CONNECTION_STATE_CHANGED);

        mHidDeviceProfile = new HidDeviceProfile(context, mLocalAdapter, mDeviceManager, this);
        addProfile(mHidDeviceProfile, HidDeviceProfile.NAME,
                BluetoothHidDevice.ACTION_CONNECTION_STATE_CHANGED);

        if(DEBUG) Log.d(TAG, "Adding local MAP profile");
        if (mUseMapClient) {
            mMapClientProfile = new MapClientProfile(mContext, mLocalAdapter, mDeviceManager, this);
            addProfile(mMapClientProfile, MapClientProfile.NAME,
                BluetoothMapClient.ACTION_CONNECTION_STATE_CHANGED);
        } else {
            mMapProfile = new MapProfile(mContext, mLocalAdapter, mDeviceManager, this);
            addProfile(mMapProfile, MapProfile.NAME,
                    BluetoothMap.ACTION_CONNECTION_STATE_CHANGED);
        }

        //Create PBAP server profile
        if(DEBUG) Log.d(TAG, "Adding local PBAP profile");

        mPbapProfile = new PbapServerProfile(context);
        addProfile(mPbapProfile, PbapServerProfile.NAME,
             BluetoothPbap.ACTION_CONNECTION_STATE_CHANGED);

        mHearingAidProfile = new HearingAidProfile(mContext, mLocalAdapter, mDeviceManager, this);
        addProfile(mHearingAidProfile, HearingAidProfile.NAME,
                   BluetoothHearingAid.ACTION_CONNECTION_STATE_CHANGED);
        if (DEBUG) Log.d(TAG, "LocalBluetoothProfileManager construction complete");
    }

    /**
     * Initialize or update the local profile objects. If a UUID was previously
     * present but has been removed, we print a warning but don't remove the
     * profile object as it might be referenced elsewhere, or the UUID might
     * come back and we don't want multiple copies of the profile objects.
     * @param uuids
     */
    void updateLocalProfiles(ParcelUuid[] uuids) {
        // A2DP SRC
        if (BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.AudioSource)) {
            if (mA2dpProfile == null) {
                if(DEBUG) Log.d(TAG, "Adding local A2DP SRC profile");
                mA2dpProfile = new A2dpProfile(mContext, mLocalAdapter, mDeviceManager, this);
                addProfile(mA2dpProfile, A2dpProfile.NAME,
                        BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
            }
        } else if (mA2dpProfile != null) {
            Log.w(TAG, "Warning: A2DP profile was previously added but the UUID is now missing.");
        }

        // A2DP SINK
        if (BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.AudioSink)) {
            if (mA2dpSinkProfile == null) {
                if(DEBUG) Log.d(TAG, "Adding local A2DP Sink profile");
                mA2dpSinkProfile = new A2dpSinkProfile(mContext, mLocalAdapter, mDeviceManager, this);
                addProfile(mA2dpSinkProfile, A2dpSinkProfile.NAME,
                        BluetoothA2dpSink.ACTION_CONNECTION_STATE_CHANGED);
            }
        } else if (mA2dpSinkProfile != null) {
            Log.w(TAG, "Warning: A2DP Sink profile was previously added but the UUID is now missing.");
        }

        // Headset / Handsfree
        if (BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.Handsfree_AG) ||
            BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.HSP_AG)) {
            if (mHeadsetProfile == null) {
                if (DEBUG) Log.d(TAG, "Adding local HEADSET profile");
                mHeadsetProfile = new HeadsetProfile(mContext, mLocalAdapter,
                        mDeviceManager, this);
                addHeadsetProfile(mHeadsetProfile, HeadsetProfile.NAME,
                        BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED,
                        BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED,
                        BluetoothHeadset.STATE_AUDIO_DISCONNECTED);
            }
        } else if (mHeadsetProfile != null) {
            Log.w(TAG, "Warning: HEADSET profile was previously added but the UUID is now missing.");
        }

        // Headset HF
        if (BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.Handsfree)) {
            if (mHfpClientProfile == null) {
                if(DEBUG) Log.d(TAG, "Adding local HfpClient profile");
                mHfpClientProfile =
                    new HfpClientProfile(mContext, mLocalAdapter, mDeviceManager, this);
                addHeadsetProfile(mHfpClientProfile, HfpClientProfile.NAME,
                        BluetoothHeadsetClient.ACTION_CONNECTION_STATE_CHANGED,
                        BluetoothHeadsetClient.ACTION_AUDIO_STATE_CHANGED,
                        BluetoothHeadsetClient.STATE_AUDIO_DISCONNECTED);
            }
        } else if (mHfpClientProfile != null) {
            Log.w(TAG,
                "Warning: Hfp Client profile was previously added but the UUID is now missing.");
        } else {
            Log.d(TAG, "Handsfree Uuid not found.");
        }

        // Message Access Profile Client
        if (BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.MNS)) {
            if (mMapClientProfile == null) {
                if(DEBUG) Log.d(TAG, "Adding local Map Client profile");
                mMapClientProfile =
                        new MapClientProfile(mContext, mLocalAdapter, mDeviceManager, this);
                addProfile(mMapClientProfile, MapClientProfile.NAME,
                        BluetoothMapClient.ACTION_CONNECTION_STATE_CHANGED);
            }
        } else if (mMapClientProfile != null) {
            Log.w(TAG,
                    "Warning: MAP Client profile was previously added but the UUID is now missing.");
        } else {
            Log.d(TAG, "MAP Client Uuid not found.");
        }

        // OPP
        if (BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.ObexObjectPush)) {
            if (mOppProfile == null) {
                if(DEBUG) Log.d(TAG, "Adding local OPP profile");
                mOppProfile = new OppProfile();
                // Note: no event handler for OPP, only name map.
                mProfileNameMap.put(OppProfile.NAME, mOppProfile);
            }
        } else if (mOppProfile != null) {
            Log.w(TAG, "Warning: OPP profile was previously added but the UUID is now missing.");
        }

        //PBAP Client
        if (mUsePbapPce) {
            if (mPbapClientProfile == null) {
                if(DEBUG) Log.d(TAG, "Adding local PBAP Client profile");
                mPbapClientProfile = new PbapClientProfile(mContext, mLocalAdapter, mDeviceManager,
                        this);
                addProfile(mPbapClientProfile, PbapClientProfile.NAME,
                        BluetoothPbapClient.ACTION_CONNECTION_STATE_CHANGED);
            }
        } else if (mPbapClientProfile != null) {
            Log.w(TAG,
                "Warning: PBAP Client profile was previously added but the UUID is now missing.");
        }

        //Hearing Aid Client
        if (BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.HearingAid)) {
            if (mHearingAidProfile == null) {
                if(DEBUG) Log.d(TAG, "Adding local Hearing Aid profile");
                mHearingAidProfile = new HearingAidProfile(mContext, mLocalAdapter, mDeviceManager, this);
                addProfile(mHearingAidProfile, HearingAidProfile.NAME,
                        BluetoothHearingAid.ACTION_CONNECTION_STATE_CHANGED);
            }
        } else if (mHearingAidProfile != null) {
            Log.w(TAG, "Warning: Hearing Aid profile was previously added but the UUID is now missing.");
        }

        mEventManager.registerProfileIntentReceiver();

        // There is no local SDP record for HID and Settings app doesn't control PBAP Server.
    }

    private void addHeadsetProfile(LocalBluetoothProfile profile, String profileName,
            String stateChangedAction, String audioStateChangedAction, int audioDisconnectedState) {
        BluetoothEventManager.Handler handler = new HeadsetStateChangeHandler(
                profile, audioStateChangedAction, audioDisconnectedState);
        mEventManager.addProfileHandler(stateChangedAction, handler);
        mEventManager.addProfileHandler(audioStateChangedAction, handler);
        mProfileNameMap.put(profileName, profile);
    }

    private final Collection<ServiceListener> mServiceListeners =
            new ArrayList<ServiceListener>();

    private void addProfile(LocalBluetoothProfile profile,
            String profileName, String stateChangedAction) {
        mEventManager.addProfileHandler(stateChangedAction, new StateChangedHandler(profile));
        mProfileNameMap.put(profileName, profile);
    }

    private void addPanProfile(LocalBluetoothProfile profile,
            String profileName, String stateChangedAction) {
        mEventManager.addProfileHandler(stateChangedAction,
                new PanStateChangedHandler(profile));
        mProfileNameMap.put(profileName, profile);
    }

    public LocalBluetoothProfile getProfileByName(String name) {
        return mProfileNameMap.get(name);
    }

    // Called from LocalBluetoothAdapter when state changes to ON
    void setBluetoothStateOn() {
        ParcelUuid[] uuids = mLocalAdapter.getUuids();
        if (uuids != null) {
            updateLocalProfiles(uuids);
        }
        mEventManager.readPairedDevices();
    }

    /**
     * Generic handler for connection state change events for the specified profile.
     */
    private class StateChangedHandler implements BluetoothEventManager.Handler {
        final LocalBluetoothProfile mProfile;

        StateChangedHandler(LocalBluetoothProfile profile) {
            mProfile = profile;
        }

        public void onReceive(Context context, Intent intent, BluetoothDevice device) {
            CachedBluetoothDevice cachedDevice = mDeviceManager.findDevice(device);
            if (cachedDevice == null) {
                Log.w(TAG, "StateChangedHandler found new device: " + device);
                cachedDevice = mDeviceManager.addDevice(mLocalAdapter,
                        LocalBluetoothProfileManager.this, device);
            }
            onReceiveInternal(intent, cachedDevice);
        }

        protected void onReceiveInternal(Intent intent, CachedBluetoothDevice cachedDevice) {
            int newState = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, 0);
            int oldState = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, 0);
            if (newState == BluetoothProfile.STATE_DISCONNECTED &&
                    oldState == BluetoothProfile.STATE_CONNECTING) {
                Log.i(TAG, "Failed to connect " + mProfile + " device");
            }
            cachedDevice.onProfileStateChanged(mProfile, newState);
            cachedDevice.refresh();
        }
    }

    /** Connectivity and audio state change handler for headset profiles. */
    private class HeadsetStateChangeHandler extends StateChangedHandler {
        private final String mAudioChangeAction;
        private final int mAudioDisconnectedState;

        HeadsetStateChangeHandler(LocalBluetoothProfile profile, String audioChangeAction,
                int audioDisconnectedState) {
            super(profile);
            mAudioChangeAction = audioChangeAction;
            mAudioDisconnectedState = audioDisconnectedState;
        }

        @Override
        public void onReceiveInternal(Intent intent, CachedBluetoothDevice cachedDevice) {
            if (mAudioChangeAction.equals(intent.getAction())) {
                int newState = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, 0);
                if (newState != mAudioDisconnectedState) {
                    cachedDevice.onProfileStateChanged(mProfile, BluetoothProfile.STATE_CONNECTED);
                }
                cachedDevice.refresh();
            } else {
                super.onReceiveInternal(intent, cachedDevice);
            }
        }
    }

    /** State change handler for NAP and PANU profiles. */
    private class PanStateChangedHandler extends StateChangedHandler {

        PanStateChangedHandler(LocalBluetoothProfile profile) {
            super(profile);
        }

        @Override
        public void onReceive(Context context, Intent intent, BluetoothDevice device) {
            PanProfile panProfile = (PanProfile) mProfile;
            int role = intent.getIntExtra(BluetoothPan.EXTRA_LOCAL_ROLE, 0);
            panProfile.setLocalRole(device, role);
            super.onReceive(context, intent, device);
        }
    }

    // called from DockService
    public void addServiceListener(ServiceListener l) {
        mServiceListeners.add(l);
    }

    // called from DockService
    public void removeServiceListener(ServiceListener l) {
        mServiceListeners.remove(l);
    }

    // not synchronized: use only from UI thread! (TODO: verify)
    void callServiceConnectedListeners() {
        for (ServiceListener l : mServiceListeners) {
            l.onServiceConnected();
        }
    }

    // not synchronized: use only from UI thread! (TODO: verify)
    void callServiceDisconnectedListeners() {
        for (ServiceListener listener : mServiceListeners) {
            listener.onServiceDisconnected();
        }
    }

    // This is called by DockService, so check Headset and A2DP.
    public synchronized boolean isManagerReady() {
        // Getting just the headset profile is fine for now. Will need to deal with A2DP
        // and others if they aren't always in a ready state.
        LocalBluetoothProfile profile = mHeadsetProfile;
        if (profile != null) {
            return profile.isProfileReady();
        }
        profile = mA2dpProfile;
        if (profile != null) {
            return profile.isProfileReady();
        }
        profile = mA2dpSinkProfile;
        if (profile != null) {
            return profile.isProfileReady();
        }
        return false;
    }

    public A2dpProfile getA2dpProfile() {
        return mA2dpProfile;
    }

    public A2dpSinkProfile getA2dpSinkProfile() {
        if ((mA2dpSinkProfile != null) && (mA2dpSinkProfile.isProfileReady())) {
            return mA2dpSinkProfile;
        } else {
            return null;
        }
    }

    public HeadsetProfile getHeadsetProfile() {
        return mHeadsetProfile;
    }

    public HfpClientProfile getHfpClientProfile() {
        if ((mHfpClientProfile != null) && (mHfpClientProfile.isProfileReady())) {
            return mHfpClientProfile;
        } else {
          return null;
        }
    }

    public PbapClientProfile getPbapClientProfile() {
        return mPbapClientProfile;
    }

    public PbapServerProfile getPbapProfile(){
        return mPbapProfile;
    }

    public MapProfile getMapProfile(){
        return mMapProfile;
    }

    public MapClientProfile getMapClientProfile() {
        return mMapClientProfile;
    }

    public HearingAidProfile getHearingAidProfile() {
        return mHearingAidProfile;
    }

    @VisibleForTesting
    HidProfile getHidProfile() {
        return mHidProfile;
    }

    @VisibleForTesting
    HidDeviceProfile getHidDeviceProfile() {
        return mHidDeviceProfile;
    }

    /**
     * Fill in a list of LocalBluetoothProfile objects that are supported by
     * the local device and the remote device.
     *
     * @param uuids of the remote device
     * @param localUuids UUIDs of the local device
     * @param profiles The list of profiles to fill
     * @param removedProfiles list of profiles that were removed
     */
    synchronized void updateProfiles(ParcelUuid[] uuids, ParcelUuid[] localUuids,
            Collection<LocalBluetoothProfile> profiles,
            Collection<LocalBluetoothProfile> removedProfiles,
            boolean isPanNapConnected, BluetoothDevice device) {
        // Copy previous profile list into removedProfiles
        removedProfiles.clear();
        removedProfiles.addAll(profiles);
        if (DEBUG) {
            Log.d(TAG,"Current Profiles" + profiles.toString());
        }
        profiles.clear();

        if (uuids == null) {
            return;
        }

        if (mHeadsetProfile != null) {
            if ((BluetoothUuid.isUuidPresent(localUuids, BluetoothUuid.HSP_AG) &&
                    BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.HSP)) ||
                    (BluetoothUuid.isUuidPresent(localUuids, BluetoothUuid.Handsfree_AG) &&
                            BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.Handsfree))) {
                profiles.add(mHeadsetProfile);
                removedProfiles.remove(mHeadsetProfile);
            }
        }

        if ((mHfpClientProfile != null) &&
                BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.Handsfree_AG) &&
                BluetoothUuid.isUuidPresent(localUuids, BluetoothUuid.Handsfree)) {
            profiles.add(mHfpClientProfile);
            removedProfiles.remove(mHfpClientProfile);
        }

        if (BluetoothUuid.containsAnyUuid(uuids, A2dpProfile.SINK_UUIDS) &&
            mA2dpProfile != null) {
            profiles.add(mA2dpProfile);
            removedProfiles.remove(mA2dpProfile);
        }

        if (BluetoothUuid.containsAnyUuid(uuids, A2dpSinkProfile.SRC_UUIDS) &&
                mA2dpSinkProfile != null) {
                profiles.add(mA2dpSinkProfile);
                removedProfiles.remove(mA2dpSinkProfile);
        }

        if (BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.ObexObjectPush) &&
            mOppProfile != null) {
            profiles.add(mOppProfile);
            removedProfiles.remove(mOppProfile);
        }

        if ((BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.Hid) ||
             BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.Hogp)) &&
            mHidProfile != null) {
            profiles.add(mHidProfile);
            removedProfiles.remove(mHidProfile);
        }

        if (mHidDeviceProfile != null && mHidDeviceProfile.getConnectionStatus(device)
                != BluetoothProfile.STATE_DISCONNECTED) {
            profiles.add(mHidDeviceProfile);
            removedProfiles.remove(mHidDeviceProfile);
        }

        if(isPanNapConnected)
            if(DEBUG) Log.d(TAG, "Valid PAN-NAP connection exists.");
        if ((BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.NAP) &&
            mPanProfile != null) || isPanNapConnected) {
            profiles.add(mPanProfile);
            removedProfiles.remove(mPanProfile);
        }

        if ((mMapProfile != null) &&
            (mMapProfile.getConnectionStatus(device) == BluetoothProfile.STATE_CONNECTED)) {
            profiles.add(mMapProfile);
            removedProfiles.remove(mMapProfile);
            mMapProfile.setPreferred(device, true);
        }

        if ((mPbapProfile != null) &&
            (mPbapProfile.getConnectionStatus(device) == BluetoothProfile.STATE_CONNECTED)) {
            profiles.add(mPbapProfile);
            removedProfiles.remove(mPbapProfile);
            mPbapProfile.setPreferred(device, true);
        }

        if (mMapClientProfile != null) {
            profiles.add(mMapClientProfile);
            removedProfiles.remove(mMapClientProfile);
        }

        if (mUsePbapPce) {
            profiles.add(mPbapClientProfile);
            removedProfiles.remove(mPbapClientProfile);
        }

        if (BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.HearingAid) &&
            mHearingAidProfile != null) {
            profiles.add(mHearingAidProfile);
            removedProfiles.remove(mHearingAidProfile);
        }

        if (DEBUG) {
            Log.d(TAG,"New Profiles" + profiles.toString());
        }
    }
}
