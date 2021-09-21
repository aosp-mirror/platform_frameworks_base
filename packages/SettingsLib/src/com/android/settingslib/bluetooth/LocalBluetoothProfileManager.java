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
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothCsipSetCoordinator;
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
import android.bluetooth.BluetoothSap;
import android.bluetooth.BluetoothUuid;
import android.content.Context;
import android.content.Intent;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;


/**
 * LocalBluetoothProfileManager provides access to the LocalBluetoothProfile
 * objects for the available Bluetooth profiles.
 */
public class LocalBluetoothProfileManager {
    private static final String TAG = "LocalBluetoothProfileManager";
    private static final boolean DEBUG = BluetoothUtils.D;

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
    private final CachedBluetoothDeviceManager mDeviceManager;
    private final BluetoothEventManager mEventManager;

    private A2dpProfile mA2dpProfile;
    private A2dpSinkProfile mA2dpSinkProfile;
    private HeadsetProfile mHeadsetProfile;
    private HfpClientProfile mHfpClientProfile;
    private MapProfile mMapProfile;
    private MapClientProfile mMapClientProfile;
    private HidProfile mHidProfile;
    private HidDeviceProfile mHidDeviceProfile;
    private OppProfile mOppProfile;
    private PanProfile mPanProfile;
    private PbapClientProfile mPbapClientProfile;
    private PbapServerProfile mPbapProfile;
    private HearingAidProfile mHearingAidProfile;
    private CsipSetCoordinatorProfile mCsipSetCoordinatorProfile;
    private SapProfile mSapProfile;
    private VolumeControlProfile mVolumeControlProfile;

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

        mDeviceManager = deviceManager;
        mEventManager = eventManager;
        // pass this reference to adapter and event manager (circular dependency)
        adapter.setProfileManager(this);

        if (DEBUG) Log.d(TAG, "LocalBluetoothProfileManager construction complete");
    }

    /**
     * create profile instance according to bluetooth supported profile list
     */
    void updateLocalProfiles() {
        List<Integer> supportedList = BluetoothAdapter.getDefaultAdapter().getSupportedProfiles();
        if (CollectionUtils.isEmpty(supportedList)) {
            if (DEBUG) Log.d(TAG, "supportedList is null");
            return;
        }
        if (mA2dpProfile == null && supportedList.contains(BluetoothProfile.A2DP)) {
            if (DEBUG) Log.d(TAG, "Adding local A2DP profile");
            mA2dpProfile = new A2dpProfile(mContext, mDeviceManager, this);
            addProfile(mA2dpProfile, A2dpProfile.NAME,
                    BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        }
        if (mA2dpSinkProfile == null && supportedList.contains(BluetoothProfile.A2DP_SINK)) {
            if (DEBUG) Log.d(TAG, "Adding local A2DP SINK profile");
            mA2dpSinkProfile = new A2dpSinkProfile(mContext, mDeviceManager, this);
            addProfile(mA2dpSinkProfile, A2dpSinkProfile.NAME,
                    BluetoothA2dpSink.ACTION_CONNECTION_STATE_CHANGED);
        }
        if (mHeadsetProfile == null && supportedList.contains(BluetoothProfile.HEADSET)) {
            if (DEBUG) Log.d(TAG, "Adding local HEADSET profile");
            mHeadsetProfile = new HeadsetProfile(mContext, mDeviceManager, this);
            addHeadsetProfile(mHeadsetProfile, HeadsetProfile.NAME,
                    BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED,
                    BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED,
                    BluetoothHeadset.STATE_AUDIO_DISCONNECTED);
        }
        if (mHfpClientProfile == null && supportedList.contains(BluetoothProfile.HEADSET_CLIENT)) {
            if (DEBUG) Log.d(TAG, "Adding local HfpClient profile");
            mHfpClientProfile = new HfpClientProfile(mContext, mDeviceManager, this);
            addHeadsetProfile(mHfpClientProfile, HfpClientProfile.NAME,
                    BluetoothHeadsetClient.ACTION_CONNECTION_STATE_CHANGED,
                    BluetoothHeadsetClient.ACTION_AUDIO_STATE_CHANGED,
                    BluetoothHeadsetClient.STATE_AUDIO_DISCONNECTED);
        }
        if (mMapClientProfile == null && supportedList.contains(BluetoothProfile.MAP_CLIENT)) {
            if (DEBUG) Log.d(TAG, "Adding local MAP CLIENT profile");
            mMapClientProfile = new MapClientProfile(mContext, mDeviceManager,this);
            addProfile(mMapClientProfile, MapClientProfile.NAME,
                    BluetoothMapClient.ACTION_CONNECTION_STATE_CHANGED);
        }
        if (mMapProfile == null && supportedList.contains(BluetoothProfile.MAP)) {
            if (DEBUG) Log.d(TAG, "Adding local MAP profile");
            mMapProfile = new MapProfile(mContext, mDeviceManager, this);
            addProfile(mMapProfile, MapProfile.NAME, BluetoothMap.ACTION_CONNECTION_STATE_CHANGED);
        }
        if (mOppProfile == null && supportedList.contains(BluetoothProfile.OPP)) {
            if (DEBUG) Log.d(TAG, "Adding local OPP profile");
            mOppProfile = new OppProfile();
            // Note: no event handler for OPP, only name map.
            mProfileNameMap.put(OppProfile.NAME, mOppProfile);
        }
        if (mHearingAidProfile == null && supportedList.contains(BluetoothProfile.HEARING_AID)) {
            if (DEBUG) Log.d(TAG, "Adding local Hearing Aid profile");
            mHearingAidProfile = new HearingAidProfile(mContext, mDeviceManager,
                    this);
            addProfile(mHearingAidProfile, HearingAidProfile.NAME,
                    BluetoothHearingAid.ACTION_CONNECTION_STATE_CHANGED);
        }
        if (mHidProfile == null && supportedList.contains(BluetoothProfile.HID_HOST)) {
            if (DEBUG) Log.d(TAG, "Adding local HID_HOST profile");
            mHidProfile = new HidProfile(mContext, mDeviceManager, this);
            addProfile(mHidProfile, HidProfile.NAME,
                    BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED);
        }
        if (mHidDeviceProfile == null && supportedList.contains(BluetoothProfile.HID_DEVICE)) {
            if (DEBUG) Log.d(TAG, "Adding local HID_DEVICE profile");
            mHidDeviceProfile = new HidDeviceProfile(mContext, mDeviceManager, this);
            addProfile(mHidDeviceProfile, HidDeviceProfile.NAME,
                    BluetoothHidDevice.ACTION_CONNECTION_STATE_CHANGED);
        }
        if (mPanProfile == null && supportedList.contains(BluetoothProfile.PAN)) {
            if (DEBUG) Log.d(TAG, "Adding local PAN profile");
            mPanProfile = new PanProfile(mContext);
            addPanProfile(mPanProfile, PanProfile.NAME,
                    BluetoothPan.ACTION_CONNECTION_STATE_CHANGED);
        }
        if (mPbapProfile == null && supportedList.contains(BluetoothProfile.PBAP)) {
            if (DEBUG) Log.d(TAG, "Adding local PBAP profile");
            mPbapProfile = new PbapServerProfile(mContext);
            addProfile(mPbapProfile, PbapServerProfile.NAME,
                    BluetoothPbap.ACTION_CONNECTION_STATE_CHANGED);
        }
        if (mPbapClientProfile == null && supportedList.contains(BluetoothProfile.PBAP_CLIENT)) {
            if (DEBUG) Log.d(TAG, "Adding local PBAP Client profile");
            mPbapClientProfile = new PbapClientProfile(mContext, mDeviceManager,this);
            addProfile(mPbapClientProfile, PbapClientProfile.NAME,
                    BluetoothPbapClient.ACTION_CONNECTION_STATE_CHANGED);
        }
        if (mSapProfile == null && supportedList.contains(BluetoothProfile.SAP)) {
            if (DEBUG) {
                Log.d(TAG, "Adding local SAP profile");
            }
            mSapProfile = new SapProfile(mContext, mDeviceManager, this);
            addProfile(mSapProfile, SapProfile.NAME, BluetoothSap.ACTION_CONNECTION_STATE_CHANGED);
        }
        if (mVolumeControlProfile == null
                && supportedList.contains(BluetoothProfile.VOLUME_CONTROL)) {
            if (DEBUG) {
                Log.d(TAG, "Adding local Volume Control profile");
            }
            mVolumeControlProfile = new VolumeControlProfile();
            // Note: no event handler for VCP, only for being connectable.
            mProfileNameMap.put(VolumeControlProfile.NAME, mVolumeControlProfile);
        }
        if (mCsipSetCoordinatorProfile == null
                && supportedList.contains(BluetoothProfile.CSIP_SET_COORDINATOR)) {
            if (DEBUG) {
                Log.d(TAG, "Adding local CSIP set coordinator profile");
            }
            mCsipSetCoordinatorProfile =
                    new CsipSetCoordinatorProfile(mContext, mDeviceManager, this);
            addProfile(mCsipSetCoordinatorProfile, mCsipSetCoordinatorProfile.NAME,
                    BluetoothCsipSetCoordinator.ACTION_CSIS_CONNECTION_STATE_CHANGED);
        }
        mEventManager.registerProfileIntentReceiver();
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
            new CopyOnWriteArrayList<ServiceListener>();

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
        updateLocalProfiles();
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
                cachedDevice = mDeviceManager.addDevice(device);
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

            if (getHearingAidProfile() != null &&
                mProfile instanceof HearingAidProfile &&
                (newState == BluetoothProfile.STATE_CONNECTED)) {
                // Check if the HiSyncID has being initialized
                if (cachedDevice.getHiSyncId() == BluetoothHearingAid.HI_SYNC_ID_INVALID) {
                    long newHiSyncId = getHearingAidProfile().getHiSyncId(cachedDevice.getDevice());
                    if (newHiSyncId != BluetoothHearingAid.HI_SYNC_ID_INVALID) {
                        cachedDevice.setHiSyncId(newHiSyncId);
                    }
                }
            }

            if (getCsipSetCoordinatorProfile() != null
                    && mProfile instanceof CsipSetCoordinatorProfile
                    && newState == BluetoothProfile.STATE_CONNECTED) {
                // Check if the GroupID has being initialized
                if (cachedDevice.getGroupId() == BluetoothCsipSetCoordinator.GROUP_ID_INVALID) {
                    final Map<Integer, ParcelUuid> groupIdMap = getCsipSetCoordinatorProfile()
                            .getGroupUuidMapByDevice(cachedDevice.getDevice());
                    if (groupIdMap != null) {
                        for (Map.Entry<Integer, ParcelUuid> entry: groupIdMap.entrySet()) {
                            if (entry.getValue().equals(BluetoothUuid.BASE_UUID)) {
                                cachedDevice.setGroupId(entry.getKey());
                                break;
                            }
                        }
                    }
                }
            }

            cachedDevice.onProfileStateChanged(mProfile, newState);
            // Dispatch profile changed after device update
            boolean needDispatchProfileConnectionState = true;
            if (cachedDevice.getHiSyncId() != BluetoothHearingAid.HI_SYNC_ID_INVALID
                    || cachedDevice.getGroupId() != BluetoothCsipSetCoordinator.GROUP_ID_INVALID) {
                needDispatchProfileConnectionState = !mDeviceManager
                        .onProfileConnectionStateChangedIfProcessed(cachedDevice, newState,
                        mProfile.getProfileId());
            }
            if (needDispatchProfileConnectionState) {
                cachedDevice.refresh();
                mEventManager.dispatchProfileConnectionStateChanged(cachedDevice, newState,
                        mProfile.getProfileId());
            }
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
        final Collection<ServiceListener> listeners = new ArrayList<>(mServiceListeners);

        for (ServiceListener l : listeners) {
            l.onServiceConnected();
        }
    }

    // not synchronized: use only from UI thread! (TODO: verify)
    void callServiceDisconnectedListeners() {
        final Collection<ServiceListener> listeners = new ArrayList<>(mServiceListeners);

        for (ServiceListener listener : listeners) {
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

    SapProfile getSapProfile() {
        return mSapProfile;
    }

    @VisibleForTesting
    HidProfile getHidProfile() {
        return mHidProfile;
    }

    @VisibleForTesting
    HidDeviceProfile getHidDeviceProfile() {
        return mHidDeviceProfile;
    }

    public CsipSetCoordinatorProfile getCsipSetCoordinatorProfile() {
        return mCsipSetCoordinatorProfile;
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
            if ((ArrayUtils.contains(localUuids, BluetoothUuid.HSP_AG)
                    && ArrayUtils.contains(uuids, BluetoothUuid.HSP))
                    || (ArrayUtils.contains(localUuids, BluetoothUuid.HFP_AG)
                    && ArrayUtils.contains(uuids, BluetoothUuid.HFP))) {
                profiles.add(mHeadsetProfile);
                removedProfiles.remove(mHeadsetProfile);
            }
        }

        if ((mHfpClientProfile != null) &&
                ArrayUtils.contains(uuids, BluetoothUuid.HFP_AG)
                && ArrayUtils.contains(localUuids, BluetoothUuid.HFP)) {
            profiles.add(mHfpClientProfile);
            removedProfiles.remove(mHfpClientProfile);
        }

        if (BluetoothUuid.containsAnyUuid(uuids, A2dpProfile.SINK_UUIDS) && mA2dpProfile != null) {
            profiles.add(mA2dpProfile);
            removedProfiles.remove(mA2dpProfile);
        }

        if (BluetoothUuid.containsAnyUuid(uuids, A2dpSinkProfile.SRC_UUIDS)
                && mA2dpSinkProfile != null) {
                profiles.add(mA2dpSinkProfile);
                removedProfiles.remove(mA2dpSinkProfile);
        }

        if (ArrayUtils.contains(uuids, BluetoothUuid.OBEX_OBJECT_PUSH) && mOppProfile != null) {
            profiles.add(mOppProfile);
            removedProfiles.remove(mOppProfile);
        }

        if ((ArrayUtils.contains(uuids, BluetoothUuid.HID)
                || ArrayUtils.contains(uuids, BluetoothUuid.HOGP)) && mHidProfile != null) {
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
        if ((ArrayUtils.contains(uuids, BluetoothUuid.NAP) && mPanProfile != null)
                || isPanNapConnected) {
            profiles.add(mPanProfile);
            removedProfiles.remove(mPanProfile);
        }

        if ((mMapProfile != null) &&
            (mMapProfile.getConnectionStatus(device) == BluetoothProfile.STATE_CONNECTED)) {
            profiles.add(mMapProfile);
            removedProfiles.remove(mMapProfile);
            mMapProfile.setEnabled(device, true);
        }

        if ((mPbapProfile != null) &&
            (mPbapProfile.getConnectionStatus(device) == BluetoothProfile.STATE_CONNECTED)) {
            profiles.add(mPbapProfile);
            removedProfiles.remove(mPbapProfile);
            mPbapProfile.setEnabled(device, true);
        }

        if ((mMapClientProfile != null)
                && BluetoothUuid.containsAnyUuid(uuids, MapClientProfile.UUIDS)) {
            profiles.add(mMapClientProfile);
            removedProfiles.remove(mMapClientProfile);
        }

        if ((mPbapClientProfile != null)
                && BluetoothUuid.containsAnyUuid(uuids, PbapClientProfile.SRC_UUIDS)) {
            profiles.add(mPbapClientProfile);
            removedProfiles.remove(mPbapClientProfile);
        }

        if (ArrayUtils.contains(uuids, BluetoothUuid.HEARING_AID) && mHearingAidProfile != null) {
            profiles.add(mHearingAidProfile);
            removedProfiles.remove(mHearingAidProfile);
        }

        if (mSapProfile != null && ArrayUtils.contains(uuids, BluetoothUuid.SAP)) {
            profiles.add(mSapProfile);
            removedProfiles.remove(mSapProfile);
        }

        if (mVolumeControlProfile != null
                && ArrayUtils.contains(uuids, BluetoothUuid.VOLUME_CONTROL)) {
            profiles.add(mVolumeControlProfile);
            removedProfiles.remove(mVolumeControlProfile);
        }

        if (mCsipSetCoordinatorProfile != null
                && ArrayUtils.contains(uuids, BluetoothUuid.COORDINATED_SET)) {
            profiles.add(mCsipSetCoordinatorProfile);
            removedProfiles.remove(mCsipSetCoordinatorProfile);
        }

        if (DEBUG) {
            Log.d(TAG,"New Profiles" + profiles.toString());
        }
    }
}
