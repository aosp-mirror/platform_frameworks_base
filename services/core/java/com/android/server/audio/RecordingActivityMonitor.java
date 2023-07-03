/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.audio;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.media.AudioSystem;
import android.media.IRecordingConfigDispatcher;
import android.media.MediaRecorder;
import android.media.audiofx.AudioEffect;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.server.utils.EventLogger;

import java.io.PrintWriter;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Class to receive and dispatch updates from AudioSystem about recording configurations.
 */
public final class RecordingActivityMonitor implements AudioSystem.AudioRecordingCallback {

    public final static String TAG = "AudioService.RecordingActivityMonitor";

    private ArrayList<RecMonitorClient> mClients = new ArrayList<RecMonitorClient>();
    // a public client is one that needs an anonymized version of the playback configurations, we
    // keep track of whether there is at least one to know when we need to create the list of
    // playback configurations that do not contain uid/package name information.
    private boolean mHasPublicClients = false;


    // When legacy remote submix device is active, remote submix device should not be fixed and
    // full volume device. When legacy remote submix device is active, there will be a recording
    // activity using device with type as {@link AudioSystem.DEVICE_OUT_REMOTE_SUBMIX} and address
    // as {@link AudioSystem.LEGACY_REMOTE_SUBMIX_ADDRESS}. Cache riid of legacy remote submix
    // since remote submix state is not cached in mRecordStates.
    private AtomicInteger mLegacyRemoteSubmixRiid =
            new AtomicInteger(AudioManager.RECORD_RIID_INVALID);
    private AtomicBoolean mLegacyRemoteSubmixActive = new AtomicBoolean(false);

    static final class RecordingState {
        private final int mRiid;
        private final RecorderDeathHandler mDeathHandler;
        private boolean mIsActive;
        private AudioRecordingConfiguration mConfig;

        RecordingState(int riid, RecorderDeathHandler handler) {
            mRiid = riid;
            mDeathHandler = handler;
        }

        RecordingState(AudioRecordingConfiguration config) {
            mRiid = AudioManager.RECORD_RIID_INVALID;
            mDeathHandler = null;
            mConfig = config;
        }

        int getRiid() {
            return mRiid;
        }

        int getPortId() {
            return mConfig != null ? mConfig.getClientPortId() : -1;
        }

        AudioRecordingConfiguration getConfig() {
            return mConfig;
        }

        boolean hasDeathHandler() {
            return mDeathHandler != null;
        }

        boolean isActiveConfiguration() {
            return mIsActive && mConfig != null;
        }

        void release() {
            if (mDeathHandler != null) {
                mDeathHandler.release();
            }
        }

        // returns true if status of an active recording has changed
        boolean setActive(boolean active) {
            if (mIsActive == active) return false;
            mIsActive = active;
            return mConfig != null;
        }

        // returns true if an active recording has been updated
        boolean setConfig(AudioRecordingConfiguration config) {
            if (config.equals(mConfig)) return false;
            mConfig = config;
            return mIsActive;
        }

        void dump(PrintWriter pw) {
            pw.println("riid " + mRiid + "; active? " + mIsActive);
            if (mConfig != null) {
                mConfig.dump(pw);
            } else {
                pw.println("  no config");
            }
        }
    }
    private List<RecordingState> mRecordStates = new ArrayList<RecordingState>();

    private final PackageManager mPackMan;

    RecordingActivityMonitor(Context ctxt) {
        RecMonitorClient.sMonitor = this;
        RecorderDeathHandler.sMonitor = this;
        mPackMan = ctxt.getPackageManager();
    }

    /**
     * Implementation of android.media.AudioSystem.AudioRecordingCallback
     */
    public void onRecordingConfigurationChanged(int event, int riid, int uid, int session,
                                                int source, int portId, boolean silenced,
                                                int[] recordingInfo,
                                                AudioEffect.Descriptor[] clientEffects,
                                                AudioEffect.Descriptor[] effects,
                                                int activeSource, String packName) {
        final AudioRecordingConfiguration config = createRecordingConfiguration(
                uid, session, source, recordingInfo,
                portId, silenced, activeSource, clientEffects, effects);
        if (source == MediaRecorder.AudioSource.REMOTE_SUBMIX
                && (event == AudioManager.RECORD_CONFIG_EVENT_START
                        || event == AudioManager.RECORD_CONFIG_EVENT_UPDATE)) {
            final AudioDeviceInfo device = config.getAudioDevice();
            if (device != null
                    && AudioSystem.LEGACY_REMOTE_SUBMIX_ADDRESS.equals(device.getAddress())) {
                mLegacyRemoteSubmixRiid.set(riid);
                mLegacyRemoteSubmixActive.set(true);
            }
        }
        if (MediaRecorder.isSystemOnlyAudioSource(source)) {
            // still want to log event, it just won't appear in recording configurations;
            sEventLogger.enqueue(new RecordingEvent(event, riid, config).printLog(TAG));
            return;
        }
        dispatchCallbacks(updateSnapshot(event, riid, config));
    }

    /**
     * Track a recorder provided by the client
     */
    public int trackRecorder(IBinder recorder) {
        if (recorder == null) {
            Log.e(TAG, "trackRecorder called with null token");
            return AudioManager.RECORD_RIID_INVALID;
        }
        final int newRiid = AudioSystem.newAudioRecorderId();
        RecorderDeathHandler handler = new RecorderDeathHandler(newRiid, recorder);
        if (!handler.init()) {
            // probably means that the AudioRecord has already died
            return AudioManager.RECORD_RIID_INVALID;
        }
        synchronized (mRecordStates) {
            mRecordStates.add(new RecordingState(newRiid, handler));
        }
        // a newly added record is inactive, no change in active configs is possible.
        return newRiid;
    }

    /**
     * Receive an event from the client about a tracked recorder
     */
    public void recorderEvent(int riid, int event) {
        if (mLegacyRemoteSubmixRiid.get() == riid) {
            mLegacyRemoteSubmixActive.set(event == AudioManager.RECORDER_STATE_STARTED);
        }
        int configEvent = event == AudioManager.RECORDER_STATE_STARTED
                ? AudioManager.RECORD_CONFIG_EVENT_START :
                event == AudioManager.RECORDER_STATE_STOPPED
                ? AudioManager.RECORD_CONFIG_EVENT_STOP : AudioManager.RECORD_CONFIG_EVENT_NONE;
        if (riid == AudioManager.RECORD_RIID_INVALID
                || configEvent == AudioManager.RECORD_CONFIG_EVENT_NONE) {
            sEventLogger.enqueue(new RecordingEvent(event, riid, null).printLog(TAG));
            return;
        }
        dispatchCallbacks(updateSnapshot(configEvent, riid, null));
    }

    /**
     * Stop tracking the recorder
     */
    public void releaseRecorder(int riid) {
        dispatchCallbacks(updateSnapshot(AudioManager.RECORD_CONFIG_EVENT_RELEASE, riid, null));
    }

    /**
     * Returns true if a recorder belonging to the app with given uid is active.
     *
     * @param uid the app uid
     * @return true if a recorder is active, false otherwise
     */
    public boolean isRecordingActiveForUid(int uid) {
        synchronized (mRecordStates) {
            for (RecordingState state : mRecordStates) {
                // Note: isActiveConfiguration() == true => state.getConfig() != null
                if (state.isActiveConfiguration()
                        && state.getConfig().getClientUid() == uid) {
                    return true;
                }
            }
        }
        return false;
    }

    private void dispatchCallbacks(List<AudioRecordingConfiguration> configs) {
        if (configs == null) { // null means "no changes"
            return;
        }
        synchronized (mClients) {
            // list of recording configurations for "public consumption". It is only computed if
            // there are non-system recording activity listeners.
            final List<AudioRecordingConfiguration> configsPublic = mHasPublicClients
                    ? anonymizeForPublicConsumption(configs) :
                      new ArrayList<AudioRecordingConfiguration>();
            for (RecMonitorClient rmc : mClients) {
                try {
                    if (rmc.mIsPrivileged) {
                        rmc.mDispatcherCb.dispatchRecordingConfigChange(configs);
                    } else {
                        rmc.mDispatcherCb.dispatchRecordingConfigChange(configsPublic);
                    }
                } catch (RemoteException e) {
                    Log.w(TAG, "Could not call dispatchRecordingConfigChange() on client", e);
                }
            }
        }
    }

    protected void dump(PrintWriter pw) {
        // recorders
        pw.println("\nRecordActivityMonitor dump time: "
                + DateFormat.getTimeInstance().format(new Date()));
        synchronized (mRecordStates) {
            for (RecordingState state : mRecordStates) {
                state.dump(pw);
            }
        }
        pw.println("\n");
        // log
        sEventLogger.dump(pw);
    }

    private static ArrayList<AudioRecordingConfiguration> anonymizeForPublicConsumption(
            List<AudioRecordingConfiguration> sysConfigs) {
        ArrayList<AudioRecordingConfiguration> publicConfigs =
                new ArrayList<AudioRecordingConfiguration>();
        // only add active anonymized configurations,
        for (AudioRecordingConfiguration config : sysConfigs) {
            publicConfigs.add(AudioRecordingConfiguration.anonymizedCopy(config));
        }
        return publicConfigs;
    }

    void initMonitor() {
        AudioSystem.setRecordingCallback(this);
    }

    void onAudioServerDied() {
        // Remove all RecordingState entries that do not have a death handler (that means
        // they are tracked by the Audio Server). If there were active entries among removed,
        // dispatch active configuration changes.
        List<AudioRecordingConfiguration> configs = null;
        synchronized (mRecordStates) {
            boolean configChanged = false;
            for (Iterator<RecordingState> it = mRecordStates.iterator(); it.hasNext(); ) {
                RecordingState state = it.next();
                if (!state.hasDeathHandler()) {
                    if (state.isActiveConfiguration()) {
                        configChanged = true;
                        sEventLogger.enqueue(new RecordingEvent(
                                        AudioManager.RECORD_CONFIG_EVENT_RELEASE,
                                        state.getRiid(), state.getConfig()));
                    }
                    it.remove();
                }
            }
            if (configChanged) {
                configs = getActiveRecordingConfigurations(true /*isPrivileged*/);
            }
        }
        dispatchCallbacks(configs);
    }

    void registerRecordingCallback(IRecordingConfigDispatcher rcdb, boolean isPrivileged) {
        if (rcdb == null) {
            return;
        }
        synchronized (mClients) {
            final RecMonitorClient rmc = new RecMonitorClient(rcdb, isPrivileged);
            if (rmc.init()) {
                if (!isPrivileged) {
                    mHasPublicClients = true;
                }
                mClients.add(rmc);
            }
        }
    }

    void unregisterRecordingCallback(IRecordingConfigDispatcher rcdb) {
        if (rcdb == null) {
            return;
        }
        synchronized (mClients) {
            final Iterator<RecMonitorClient> clientIterator = mClients.iterator();
            boolean hasPublicClients = false;
            while (clientIterator.hasNext()) {
                RecMonitorClient rmc = clientIterator.next();
                if (rcdb.asBinder().equals(rmc.mDispatcherCb.asBinder())) {
                    rmc.release();
                    clientIterator.remove();
                } else {
                    if (!rmc.mIsPrivileged) {
                        hasPublicClients = true;
                    }
                }
            }
            mHasPublicClients = hasPublicClients;
        }
    }

    List<AudioRecordingConfiguration> getActiveRecordingConfigurations(boolean isPrivileged) {
        List<AudioRecordingConfiguration> configs = new ArrayList<AudioRecordingConfiguration>();
        synchronized (mRecordStates) {
            for (RecordingState state : mRecordStates) {
                if (state.isActiveConfiguration()) {
                    configs.add(state.getConfig());
                }
            }
        }
        // AudioRecordingConfiguration objects never get updated. If config changes,
        // the reference to the config is set in RecordingState.
        if (!isPrivileged) {
            configs = anonymizeForPublicConsumption(configs);
        }
        return configs;
    }

    /**
     * Return true if legacy remote submix device is active. Otherwise, return false.
     */
    boolean isLegacyRemoteSubmixActive() {
        return mLegacyRemoteSubmixActive.get();
    }

    /**
     * Create a recording configuration from the provided parameters
     * @param uid
     * @param session
     * @param source
     * @param recordingFormat see
     *     {@link AudioSystem.AudioRecordingCallback#onRecordingConfigurationChanged(int, int, int,\
     int, int, boolean, int[], AudioEffect.Descriptor[], AudioEffect.Descriptor[], int, String)}
     *     for the definition of the contents of the array
     * @param portId
     * @param silenced
     * @param activeSource
     * @param clientEffects
     * @param effects
     * @return null a configuration object.
     */
    private AudioRecordingConfiguration createRecordingConfiguration(int uid,
            int session, int source, int[] recordingInfo, int portId, boolean silenced,
            int activeSource, AudioEffect.Descriptor[] clientEffects,
            AudioEffect.Descriptor[] effects) {
        final AudioFormat clientFormat = new AudioFormat.Builder()
                .setEncoding(recordingInfo[0])
                // FIXME this doesn't support index-based masks
                .setChannelMask(recordingInfo[1])
                .setSampleRate(recordingInfo[2])
                .build();
        final AudioFormat deviceFormat = new AudioFormat.Builder()
                .setEncoding(recordingInfo[3])
                // FIXME this doesn't support index-based masks
                .setChannelMask(recordingInfo[4])
                .setSampleRate(recordingInfo[5])
                .build();
        final int patchHandle = recordingInfo[6];
        final String[] packages = mPackMan.getPackagesForUid(uid);
        final String packageName;
        if (packages != null && packages.length > 0) {
            packageName = packages[0];
        } else {
            packageName = "";
        }
        return new AudioRecordingConfiguration(uid, session, source,
                clientFormat, deviceFormat, patchHandle, packageName,
                portId, silenced, activeSource, clientEffects, effects);
    }

    /**
     * Update the internal "view" of the active recording sessions
     * @param event RECORD_CONFIG_EVENT_...
     * @param riid
     * @param config
     * @return null if the list of active recording sessions has not been modified, a list
     *     with the current active configurations otherwise.
     */
    private List<AudioRecordingConfiguration> updateSnapshot(
            int event, int riid, AudioRecordingConfiguration config) {
        List<AudioRecordingConfiguration> configs = null;
        synchronized (mRecordStates) {
            int stateIndex = -1;
            if (riid != AudioManager.RECORD_RIID_INVALID) {
                stateIndex = findStateByRiid(riid);
            } else if (config != null) {
                stateIndex = findStateByPortId(config.getClientPortId());
            }
            if (stateIndex == -1) {
                if (event == AudioManager.RECORD_CONFIG_EVENT_START && config != null) {
                    // First time registration for a recorder tracked by AudioServer.
                    mRecordStates.add(new RecordingState(config));
                    stateIndex = mRecordStates.size() - 1;
                } else {
                    if (config == null) {
                        // Records tracked by clients must be registered first via trackRecorder.
                        Log.e(TAG, String.format(
                                        "Unexpected event %d for riid %d", event, riid));
                    }
                    return configs;
                }
            }
            final RecordingState state = mRecordStates.get(stateIndex);

            boolean configChanged;
            switch (event) {
                case AudioManager.RECORD_CONFIG_EVENT_START:
                    configChanged = state.setActive(true);
                    if (config != null) {
                        configChanged = state.setConfig(config) || configChanged;
                    }
                    break;
                case AudioManager.RECORD_CONFIG_EVENT_UPDATE:
                    // For this event config != null
                    configChanged = state.setConfig(config);
                    break;
                case AudioManager.RECORD_CONFIG_EVENT_STOP:
                    configChanged = state.setActive(false);
                    if (!state.hasDeathHandler()) {
                        // A recorder tracked by AudioServer has to be removed now so it
                        // does not leak. It will be re-registered if recording starts again.
                        mRecordStates.remove(stateIndex);
                    }
                    break;
                case AudioManager.RECORD_CONFIG_EVENT_RELEASE:
                    configChanged = state.isActiveConfiguration();
                    state.release();
                    mRecordStates.remove(stateIndex);
                    break;
                default:
                    Log.e(TAG, String.format("Unknown event %d for riid %d / portid %d",
                                    event, riid, state.getPortId()));
                    configChanged = false;
            }
            if (configChanged) {
                sEventLogger.enqueue(new RecordingEvent(event, riid, state.getConfig()));
                configs = getActiveRecordingConfigurations(true /*isPrivileged*/);
            }
        }
        return configs;
    }

    // riid is assumed to be valid
    private int findStateByRiid(int riid) {
        synchronized (mRecordStates) {
            for (int i = 0; i < mRecordStates.size(); i++) {
                if (mRecordStates.get(i).getRiid() == riid) {
                    return i;
                }
            }
        }
        return -1;
    }

    private int findStateByPortId(int portId) {
        // Lookup by portId is unambiguous only for recordings managed by the Audio Server.
        synchronized (mRecordStates) {
            for (int i = 0; i < mRecordStates.size(); i++) {
                if (!mRecordStates.get(i).hasDeathHandler()
                        && mRecordStates.get(i).getPortId() == portId) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Inner class to track clients that want to be notified of recording updates
     */
    private final static class RecMonitorClient implements IBinder.DeathRecipient {

        // can afford to be static because only one RecordingActivityMonitor ever instantiated
        static RecordingActivityMonitor sMonitor;

        final IRecordingConfigDispatcher mDispatcherCb;
        final boolean mIsPrivileged;

        RecMonitorClient(IRecordingConfigDispatcher rcdb, boolean isPrivileged) {
            mDispatcherCb = rcdb;
            mIsPrivileged = isPrivileged;
        }

        public void binderDied() {
            Log.w(TAG, "client died");
            sMonitor.unregisterRecordingCallback(mDispatcherCb);
        }

        boolean init() {
            try {
                mDispatcherCb.asBinder().linkToDeath(this, 0);
                return true;
            } catch (RemoteException e) {
                Log.w(TAG, "Could not link to client death", e);
                return false;
            }
        }

        void release() {
            mDispatcherCb.asBinder().unlinkToDeath(this, 0);
        }
    }

    private static final class RecorderDeathHandler implements IBinder.DeathRecipient {

        // can afford to be static because only one RecordingActivityMonitor ever instantiated
        static RecordingActivityMonitor sMonitor;

        final int mRiid;
        private final IBinder mRecorderToken;

        RecorderDeathHandler(int riid, IBinder recorderToken) {
            mRiid = riid;
            mRecorderToken = recorderToken;
        }

        public void binderDied() {
            sMonitor.releaseRecorder(mRiid);
        }

        boolean init() {
            try {
                mRecorderToken.linkToDeath(this, 0);
                return true;
            } catch (RemoteException e) {
                Log.w(TAG, "Could not link to recorder death", e);
                return false;
            }
        }

        void release() {
            mRecorderToken.unlinkToDeath(this, 0);
        }
    }

    /**
     * Inner class for recording event logging
     */
    private static final class RecordingEvent extends EventLogger.Event {
        private final int mRecEvent;
        private final int mRIId;
        private final int mClientUid;
        private final int mSession;
        private final int mSource;
        private final String mPackName;
        private final boolean mSilenced;

        RecordingEvent(int event, int riid, AudioRecordingConfiguration config) {
            mRecEvent = event;
            mRIId = riid;
            if (config != null) {
                mClientUid = config.getClientUid();
                mSession = config.getClientAudioSessionId();
                mSource = config.getClientAudioSource();
                mPackName = config.getClientPackageName();
                mSilenced = config.isClientSilenced();
            } else {
                mClientUid = -1;
                mSession = -1;
                mSource = -1;
                mPackName = null;
                mSilenced = false;
            }
        }

        private static String recordEventToString(int recEvent) {
            switch (recEvent) {
                case AudioManager.RECORD_CONFIG_EVENT_START:
                    return "start";
                case AudioManager.RECORD_CONFIG_EVENT_UPDATE:
                    return "update";
                case AudioManager.RECORD_CONFIG_EVENT_STOP:
                    return "stop";
                case AudioManager.RECORD_CONFIG_EVENT_RELEASE:
                    return "release";
                default:
                    return "unknown (" + recEvent + ")";
            }
        }

        @Override
        public String eventToString() {
            return new StringBuilder("rec ").append(recordEventToString(mRecEvent))
                    .append(" riid:").append(mRIId)
                    .append(" uid:").append(mClientUid)
                    .append(" session:").append(mSession)
                    .append(" src:").append(MediaRecorder.toLogFriendlyAudioSource(mSource))
                    .append(mSilenced ? " silenced" : " not silenced")
                    .append(mPackName == null ? "" : " pack:" + mPackName).toString();
        }
    }

    private static final EventLogger
            sEventLogger = new EventLogger(50,
            "recording activity received by AudioService");
}
