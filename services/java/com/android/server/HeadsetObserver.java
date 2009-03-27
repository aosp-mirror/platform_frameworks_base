/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.server;

import android.app.ActivityManagerNative;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Message;
import android.os.UEventObserver;
import android.util.Log;
import android.media.AudioManager;

import java.io.FileReader;
import java.io.FileNotFoundException;

/**
 * <p>HeadsetObserver monitors for a wired headset.
 */
class HeadsetObserver extends UEventObserver {
    private static final String TAG = HeadsetObserver.class.getSimpleName();

    private static final String HEADSET_UEVENT_MATCH = "DEVPATH=/devices/virtual/switch/h2w";
    private static final String HEADSET_STATE_PATH = "/sys/class/switch/h2w/state";
    private static final String HEADSET_NAME_PATH = "/sys/class/switch/h2w/name";

    private Context mContext;

    private int mHeadsetState;
    private String mHeadsetName;
    private boolean mAudioRouteNeedsUpdate;
    private AudioManager mAudioManager;

    public HeadsetObserver(Context context) {
        mContext = context;

        startObserving(HEADSET_UEVENT_MATCH);

        init();  // set initial status
    }

    @Override
    public void onUEvent(UEventObserver.UEvent event) {
        Log.v(TAG, "Headset UEVENT: " + event.toString());

        try {
            update(event.get("SWITCH_NAME"), Integer.parseInt(event.get("SWITCH_STATE")));
        } catch (NumberFormatException e) {
            Log.e(TAG, "Could not parse switch state from event " + event);
        }
    }

    private synchronized final void init() {
        char[] buffer = new char[1024];

        String newName = mHeadsetName;
        int newState = mHeadsetState;
        try {
            FileReader file = new FileReader(HEADSET_STATE_PATH);
            int len = file.read(buffer, 0, 1024);
            newState = Integer.valueOf((new String(buffer, 0, len)).trim());

            file = new FileReader(HEADSET_NAME_PATH);
            len = file.read(buffer, 0, 1024);
            newName = new String(buffer, 0, len).trim();

        } catch (FileNotFoundException e) {
            Log.w(TAG, "This kernel does not have wired headset support");
        } catch (Exception e) {
            Log.e(TAG, "" , e);
        }

        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        update(newName, newState);
    }

    private synchronized final void update(String newName, int newState) {
        if (newName != mHeadsetName || newState != mHeadsetState) {
            boolean isUnplug = (newState == 0 && mHeadsetState == 1);
            mHeadsetName = newName;
            mHeadsetState = newState;
            mAudioRouteNeedsUpdate = true;

            sendIntent(isUnplug);

            if (isUnplug) {
                // It can take hundreds of ms flush the audio pipeline after
                // apps pause audio playback, but audio route changes are
                // immediate, so delay the route change by 1000ms.
                // This could be improved once the audio sub-system provides an
                // interface to clear the audio pipeline.
                mHandler.sendEmptyMessageDelayed(0, 1000);
            } else {
                updateAudioRoute();
            }
        }
    }

    private synchronized final void sendIntent(boolean isUnplug) {
        //  Pack up the values and broadcast them to everyone
        Intent intent = new Intent(Intent.ACTION_HEADSET_PLUG);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);

        intent.putExtra("state", mHeadsetState);
        intent.putExtra("name", mHeadsetName);

        // TODO: Should we require a permission?
        ActivityManagerNative.broadcastStickyIntent(intent, null);

        if (isUnplug) {
            intent = new Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
            mContext.sendBroadcast(intent);
        }
    }

    private synchronized final void updateAudioRoute() {
        if (mAudioRouteNeedsUpdate) {
            mAudioManager.setWiredHeadsetOn(mHeadsetState == 1);
            mAudioRouteNeedsUpdate = false;
        }
    }

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            updateAudioRoute();
        }
    };        

}
