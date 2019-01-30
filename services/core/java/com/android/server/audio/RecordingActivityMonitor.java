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

import java.io.PrintWriter;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

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

    private HashMap<Integer, AudioRecordingConfiguration> mRecordConfigs =
            new HashMap<Integer, AudioRecordingConfiguration>();

    private final PackageManager mPackMan;

    RecordingActivityMonitor(Context ctxt) {
        RecMonitorClient.sMonitor = this;
        mPackMan = ctxt.getPackageManager();
    }

    /**
     * Implementation of android.media.AudioSystem.AudioRecordingCallback
     */
    public void onRecordingConfigurationChanged(int event, int uid, int session, int source,
                                                int portId, boolean silenced, int[] recordingInfo,
                                                AudioEffect.Descriptor[] clientEffects,
                                                AudioEffect.Descriptor[] effects,
                                                int activeSource, String packName) {
        if (MediaRecorder.isSystemOnlyAudioSource(source)) {
            return;
        }
        String clientEffectName =  clientEffects.length == 0 ? "None" : clientEffects[0].name;
        String effectName =  effects.length == 0 ? "None" : effects[0].name;

        final List<AudioRecordingConfiguration> configsSystem =
                updateSnapshot(event, uid, session, source, recordingInfo,
                portId, silenced, activeSource, clientEffects, effects);
        if (configsSystem != null){
            dispatchCallbacks(configsSystem);
        }
    }
    private void dispatchCallbacks(List<AudioRecordingConfiguration> configs) {
        synchronized (mClients) {
            // list of recording configurations for "public consumption". It is only computed if
            // there are non-system recording activity listeners.
            final List<AudioRecordingConfiguration> configsPublic = mHasPublicClients
                    ? anonymizeForPublicConsumption(configs) :
                      new ArrayList<AudioRecordingConfiguration>();
            final Iterator<RecMonitorClient> clientIterator = mClients.iterator();
            while (clientIterator.hasNext()) {
                final RecMonitorClient rmc = clientIterator.next();
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
        // players
        pw.println("\nRecordActivityMonitor dump time: "
                + DateFormat.getTimeInstance().format(new Date()));
        synchronized(mRecordConfigs) {
            for (AudioRecordingConfiguration conf : mRecordConfigs.values()) {
                conf.dump(pw);
            }
        }
        pw.println("\n");
        // log
        sEventLogger.dump(pw);
    }

    private ArrayList<AudioRecordingConfiguration> anonymizeForPublicConsumption(
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

    void clear() {
        synchronized (mRecordConfigs) {
            mRecordConfigs.clear();
        }
        dispatchCallbacks(new ArrayList<AudioRecordingConfiguration>());
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
                if (rcdb.equals(rmc.mDispatcherCb)) {
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
        synchronized(mRecordConfigs) {
            if (isPrivileged) {
                return new ArrayList<AudioRecordingConfiguration>(mRecordConfigs.values());
            } else {
                final List<AudioRecordingConfiguration> configsPublic =
                        anonymizeForPublicConsumption(
                            new ArrayList<AudioRecordingConfiguration>(mRecordConfigs.values()));
                return configsPublic;
            }
        }
    }

    /**
     * Update the internal "view" of the active recording sessions
     * @param event
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
     * @return null if the list of active recording sessions has not been modified, a list
     *     with the current active configurations otherwise.
     */
    private List<AudioRecordingConfiguration> updateSnapshot(int event, int uid, int session,
            int source, int[] recordingInfo, int portId, boolean silenced, int activeSource,
            AudioEffect.Descriptor[] clientEffects, AudioEffect.Descriptor[] effects) {
        final boolean configChanged;
        final ArrayList<AudioRecordingConfiguration> configs;
        synchronized(mRecordConfigs) {
            switch (event) {
            case AudioManager.RECORD_CONFIG_EVENT_STOP:
                // return failure if an unknown recording session stopped
                configChanged = (mRecordConfigs.remove(new Integer(portId)) != null);
                if (configChanged) {
                    sEventLogger.log(new RecordingEvent(event, uid, session, source, null));
                }
                break;
            case AudioManager.RECORD_CONFIG_EVENT_START:
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
                final Integer portIdKey = new Integer(portId);

                final String[] packages = mPackMan.getPackagesForUid(uid);
                final String packageName;
                if (packages != null && packages.length > 0) {
                    packageName = packages[0];
                } else {
                    packageName = "";
                }
                final AudioRecordingConfiguration updatedConfig =
                        new AudioRecordingConfiguration(uid, session, source,
                                clientFormat, deviceFormat, patchHandle, packageName,
                                portId, silenced, activeSource, clientEffects, effects);

                if (mRecordConfigs.containsKey(portIdKey)) {
                    if (updatedConfig.equals(mRecordConfigs.get(portIdKey))) {
                        configChanged = false;
                    } else {
                        // config exists but has been modified
                        mRecordConfigs.remove(portIdKey);
                        mRecordConfigs.put(portIdKey, updatedConfig);
                        configChanged = true;
                    }
                } else {
                    mRecordConfigs.put(portIdKey, updatedConfig);
                    configChanged = true;
                }
                if (configChanged) {
                    sEventLogger.log(new RecordingEvent(event, uid, session, source, packageName));
                }
                break;
            default:
                Log.e(TAG, String.format("Unknown event %d for session %d, source %d",
                        event, session, source));
                configChanged = false;
            }
            if (configChanged) {
                configs = new ArrayList<AudioRecordingConfiguration>(mRecordConfigs.values());
            } else {
                configs = null;
            }
        }
        return configs;
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

    /**
     * Inner class for recording event logging
     */
    private static final class RecordingEvent extends AudioEventLogger.Event {
        private final int mRecEvent;
        private final int mClientUid;
        private final int mSession;
        private final int mSource;
        private final String mPackName;

        RecordingEvent(int event, int uid, int session, int source, String packName) {
            mRecEvent = event;
            mClientUid = uid;
            mSession = session;
            mSource = source;
            mPackName = packName;
        }

        @Override
        public String eventToString() {
            return new StringBuilder("rec ").append(
                        mRecEvent == AudioManager.RECORD_CONFIG_EVENT_START ? "start" : "stop ")
                    .append(" uid:").append(mClientUid)
                    .append(" session:").append(mSession)
                    .append(" src:").append(MediaRecorder.toLogFriendlyAudioSource(mSource))
                    .append(mPackName == null ? "" : " pack:" + mPackName).toString();
        }
    }

    private static final AudioEventLogger sEventLogger = new AudioEventLogger(50,
            "recording activity as reported through AudioSystem.AudioRecordingCallback");
}
