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

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.media.AudioSystem;
import android.media.IRecordingConfigDispatcher;
import android.media.MediaRecorder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

/**
 * Class to receive and dispatch updates from AudioSystem about recording configurations.
 */
public final class RecordingActivityMonitor implements AudioSystem.AudioRecordingCallback {

    public final static String TAG = "AudioService.RecordingActivityMonitor";

    private ArrayList<RecMonitorClient> mClients = new ArrayList<RecMonitorClient>();

    private HashMap<Integer, AudioRecordingConfiguration> mRecordConfigs =
            new HashMap<Integer, AudioRecordingConfiguration>();

    RecordingActivityMonitor() {
        RecMonitorClient.sMonitor = this;
    }

    /**
     * Implementation of android.media.AudioSystem.AudioRecordingCallback
     */
    public void onRecordingConfigurationChanged(int event, int session, int source,
            int[] recordingInfo) {
        if (MediaRecorder.isSystemOnlyAudioSource(source)) {
            return;
        }
        final List<AudioRecordingConfiguration> configs =
                updateSnapshot(event, session, source, recordingInfo);
        if (configs != null){
            synchronized(mClients) {
                final Iterator<RecMonitorClient> clientIterator = mClients.iterator();
                while (clientIterator.hasNext()) {
                    try {
                        clientIterator.next().mDispatcherCb.dispatchRecordingConfigChange(
                                configs);
                    } catch (RemoteException e) {
                        Log.w(TAG, "Could not call dispatchRecordingConfigChange() on client", e);
                    }
                }
            }
        }
    }

    void initMonitor() {
        AudioSystem.setRecordingCallback(this);
    }

    void registerRecordingCallback(IRecordingConfigDispatcher rcdb) {
        if (rcdb == null) {
            return;
        }
        synchronized(mClients) {
            final RecMonitorClient rmc = new RecMonitorClient(rcdb);
            if (rmc.init()) {
                mClients.add(rmc);
            }
        }
    }

    void unregisterRecordingCallback(IRecordingConfigDispatcher rcdb) {
        if (rcdb == null) {
            return;
        }
        synchronized(mClients) {
            final Iterator<RecMonitorClient> clientIterator = mClients.iterator();
            while (clientIterator.hasNext()) {
                RecMonitorClient rmc = clientIterator.next();
                if (rcdb.equals(rmc.mDispatcherCb)) {
                    rmc.release();
                    clientIterator.remove();
                    break;
                }
            }
        }
    }

    List<AudioRecordingConfiguration> getActiveRecordingConfigurations() {
        synchronized(mRecordConfigs) {
            return new ArrayList<AudioRecordingConfiguration>(mRecordConfigs.values());
        }
    }

    /**
     * Update the internal "view" of the active recording sessions
     * @param event
     * @param session
     * @param source
     * @param recordingFormat see
     *     {@link AudioSystem.AudioRecordingCallback#onRecordingConfigurationChanged(int, int, int, int[])}
     *     for the definition of the contents of the array
     * @return null if the list of active recording sessions has not been modified, a list
     *     with the current active configurations otherwise.
     */
    private List<AudioRecordingConfiguration> updateSnapshot(int event, int session, int source,
            int[] recordingInfo) {
        final boolean configChanged;
        final ArrayList<AudioRecordingConfiguration> configs;
        synchronized(mRecordConfigs) {
            switch (event) {
            case AudioManager.RECORD_CONFIG_EVENT_STOP:
                // return failure if an unknown recording session stopped
                configChanged = (mRecordConfigs.remove(new Integer(session)) != null);
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
                final Integer sessionKey = new Integer(session);
                if (mRecordConfigs.containsKey(sessionKey)) {
                    final AudioRecordingConfiguration updatedConfig =
                            new AudioRecordingConfiguration(session, source,
                                    clientFormat, deviceFormat, patchHandle);
                    if (updatedConfig.equals(mRecordConfigs.get(sessionKey))) {
                        configChanged = false;
                    } else {
                        // config exists but has been modified
                        mRecordConfigs.remove(sessionKey);
                        mRecordConfigs.put(sessionKey, updatedConfig);
                        configChanged = true;
                    }
                } else {
                    mRecordConfigs.put(sessionKey,
                            new AudioRecordingConfiguration(session, source,
                                    clientFormat, deviceFormat, patchHandle));
                    configChanged = true;
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

        RecMonitorClient(IRecordingConfigDispatcher rcdb) {
            mDispatcherCb = rcdb;
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
}
