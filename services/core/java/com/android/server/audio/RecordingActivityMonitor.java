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

import android.media.AudioManager;
import android.media.AudioRecordConfiguration;
import android.media.AudioSystem;
import android.media.IRecordingConfigDispatcher;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

/**
 * Class to receive and dispatch updates from AudioSystem about recording configurations.
 */
public final class RecordingActivityMonitor implements AudioSystem.AudioRecordingCallback {

    public final static String TAG = "AudioService.RecordingActivityMonitor";

    private ArrayList<RecMonitorClient> mClients = new ArrayList<RecMonitorClient>();

    private HashMap<Integer, AudioRecordConfiguration> mRecordConfigs =
            new HashMap<Integer, AudioRecordConfiguration>();

    RecordingActivityMonitor() {
        RecMonitorClient.sMonitor = this;
    }

    /**
     * Implementation of android.media.AudioSystem.AudioRecordingCallback
     */
    public void onRecordingConfigurationChanged(int event, int session, int source) {
        if (updateSnapshot(event, session, source)) {
            final Iterator<RecMonitorClient> clientIterator = mClients.iterator();
            synchronized(mClients) {
                while (clientIterator.hasNext()) {
                    try {
                        clientIterator.next().mDispatcherCb.dispatchRecordingConfigChange();
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

    AudioRecordConfiguration[] getActiveRecordConfigurations() {
        synchronized(mRecordConfigs) {
            return mRecordConfigs.values().toArray(new AudioRecordConfiguration[0]);
        }
    }

    /**
     * Update the internal "view" of the active recording sessions
     * @param event
     * @param session
     * @param source
     * @return true if the list of active recording sessions has been modified, false otherwise.
     */
    private boolean updateSnapshot(int event, int session, int source) {
        synchronized(mRecordConfigs) {
            switch (event) {
            case AudioManager.RECORD_CONFIG_EVENT_STOP:
                // return failure if an unknown recording session stopped
                return (mRecordConfigs.remove(new Integer(session)) != null);
            case AudioManager.RECORD_CONFIG_EVENT_START:
                if (mRecordConfigs.containsKey(new Integer(session))) {
                    // start of session that's already tracked, not worth an update
                    // TO DO in the future when tracking record format: there might be a record
                    //       format change during a recording that requires reporting
                    return false;
                } else {
                    mRecordConfigs.put(new Integer(session),
                            new AudioRecordConfiguration(session, source));
                    return true;
                }
            default:
                Log.e(TAG, String.format("Unknown event %d for session %d, source %d",
                        event, session, source));
                return false;
            }
        }
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
