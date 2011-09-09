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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.UEventObserver;
import android.util.Slog;
import android.media.AudioManager;
import android.util.Log;

import java.io.FileReader;
import java.io.FileNotFoundException;

/**
 * <p>WiredAccessoryObserver monitors for a wired headset on the main board or dock.
 */
class WiredAccessoryObserver extends UEventObserver {
    private static final String TAG = WiredAccessoryObserver.class.getSimpleName();
    private static final boolean LOG = true;
    private static final int MAX_AUDIO_PORTS = 3; /* h2w, USB Audio & hdmi */
    private static final String uEventInfo[][] = { {"DEVPATH=/devices/virtual/switch/h2w",
                                                    "/sys/class/switch/h2w/state",
                                                    "/sys/class/switch/h2w/name"},
                                                   {"DEVPATH=/devices/virtual/switch/usb_audio",
                                                    "/sys/class/switch/usb_audio/state",
                                                    "/sys/class/switch/usb_audio/name"},
                                                   {"DEVPATH=/devices/virtual/switch/hdmi",
                                                    "/sys/class/switch/hdmi/state",
                                                    "/sys/class/switch/hdmi/name"} };

    private static final int BIT_HEADSET = (1 << 0);
    private static final int BIT_HEADSET_NO_MIC = (1 << 1);
    private static final int BIT_USB_HEADSET_ANLG = (1 << 2);
    private static final int BIT_USB_HEADSET_DGTL = (1 << 3);
    private static final int BIT_HDMI_AUDIO = (1 << 4);
    private static final int SUPPORTED_HEADSETS = (BIT_HEADSET|BIT_HEADSET_NO_MIC|
                                                   BIT_USB_HEADSET_ANLG|BIT_USB_HEADSET_DGTL|
                                                   BIT_HDMI_AUDIO);
    private static final int HEADSETS_WITH_MIC = BIT_HEADSET;

    private int mHeadsetState;
    private int mPrevHeadsetState;
    private String mHeadsetName;
    private int switchState;

    private final Context mContext;
    private final WakeLock mWakeLock;  // held while there is a pending route change

    public WiredAccessoryObserver(Context context) {
        mContext = context;
        PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WiredAccessoryObserver");
        mWakeLock.setReferenceCounted(false);

        context.registerReceiver(new BootCompletedReceiver(),
            new IntentFilter(Intent.ACTION_BOOT_COMPLETED), null, null);
    }

    private final class BootCompletedReceiver extends BroadcastReceiver {
      @Override
      public void onReceive(Context context, Intent intent) {
        // At any given time accessories could be inserted
        // one on the board, one on the dock and one on HDMI:
        // observe three UEVENTs
        init();  // set initial status
        for (int i = 0; i < MAX_AUDIO_PORTS; i++) {
            startObserving(uEventInfo[i][0]);
        }
      }
  }

    @Override
    public void onUEvent(UEventObserver.UEvent event) {
        if (LOG) Slog.v(TAG, "Headset UEVENT: " + event.toString());

        try {
            String name = event.get("SWITCH_NAME");
            int state = Integer.parseInt(event.get("SWITCH_STATE"));
            updateState(name, state);
        } catch (NumberFormatException e) {
            Slog.e(TAG, "Could not parse switch state from event " + event);
        }
    }

    private synchronized final void updateState(String name, int state)
    {
        if (name.equals("usb_audio")) {
            switchState = ((mHeadsetState & (BIT_HEADSET|BIT_HEADSET_NO_MIC|BIT_HDMI_AUDIO)) |
                           ((state == 1) ? BIT_USB_HEADSET_ANLG :
                                         ((state == 2) ? BIT_USB_HEADSET_DGTL : 0)));
        } else if (name.equals("hdmi")) {
            switchState = ((mHeadsetState & (BIT_HEADSET|BIT_HEADSET_NO_MIC|
                                             BIT_USB_HEADSET_DGTL|BIT_USB_HEADSET_ANLG)) |
                           ((state == 1) ? BIT_HDMI_AUDIO : 0));
        } else {
            switchState = ((mHeadsetState & (BIT_HDMI_AUDIO|BIT_USB_HEADSET_ANLG|
                                             BIT_USB_HEADSET_DGTL)) |
                            ((state == 1) ? BIT_HEADSET :
                                          ((state == 2) ? BIT_HEADSET_NO_MIC : 0)));
        }
        update(name, switchState);
    }

    private synchronized final void init() {
        char[] buffer = new char[1024];

        String newName = mHeadsetName;
        int newState = mHeadsetState;
        mPrevHeadsetState = mHeadsetState;

        if (LOG) Slog.v(TAG, "init()");

        for (int i = 0; i < MAX_AUDIO_PORTS; i++) {
            try {
                FileReader file = new FileReader(uEventInfo[i][1]);
                int len = file.read(buffer, 0, 1024);
                file.close();
                newState = Integer.valueOf((new String(buffer, 0, len)).trim());

                file = new FileReader(uEventInfo[i][2]);
                len = file.read(buffer, 0, 1024);
                file.close();
                newName = new String(buffer, 0, len).trim();

                if (newState > 0) {
                    updateState(newName, newState);
                }

            } catch (FileNotFoundException e) {
                Slog.w(TAG, "This kernel does not have wired headset support");
            } catch (Exception e) {
                Slog.e(TAG, "" , e);
            }
        }
    }

    private synchronized final void update(String newName, int newState) {
        // Retain only relevant bits
        int headsetState = newState & SUPPORTED_HEADSETS;
        int newOrOld = headsetState | mHeadsetState;
        int delay = 0;
        int usb_headset_anlg = headsetState & BIT_USB_HEADSET_ANLG;
        int usb_headset_dgtl = headsetState & BIT_USB_HEADSET_DGTL;
        int h2w_headset = headsetState & (BIT_HEADSET | BIT_HEADSET_NO_MIC);
        boolean h2wStateChange = true;
        boolean usbStateChange = true;
        // reject all suspect transitions: only accept state changes from:
        // - a: 0 heaset to 1 headset
        // - b: 1 headset to 0 headset
        if (LOG) Slog.v(TAG, "newState = "+newState+", headsetState = "+headsetState+","
            + "mHeadsetState = "+mHeadsetState);
        if (mHeadsetState == headsetState || ((h2w_headset & (h2w_headset - 1)) != 0)) {
            Log.e(TAG, "unsetting h2w flag");
            h2wStateChange = false;
        }
        // - c: 0 usb headset to 1 usb headset
        // - d: 1 usb headset to 0 usb headset
        if ((usb_headset_anlg >> 2) == 1 && (usb_headset_dgtl >> 3) == 1) {
            Log.e(TAG, "unsetting usb flag");
            usbStateChange = false;
        }
        if (!h2wStateChange && !usbStateChange) {
            Log.e(TAG, "invalid transition, returning ...");
            return;
        }

        mHeadsetName = newName;
        mPrevHeadsetState = mHeadsetState;
        mHeadsetState = headsetState;

        if (headsetState == 0) {
            Intent intent = new Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
            mContext.sendBroadcast(intent);
            // It can take hundreds of ms flush the audio pipeline after
            // apps pause audio playback, but audio route changes are
            // immediate, so delay the route change by 1000ms.
            // This could be improved once the audio sub-system provides an
            // interface to clear the audio pipeline.
            delay = 1000;
        } else {
            // Insert the same delay for headset connection so that the connection event is not
            // broadcast before the disconnection event in case of fast removal/insertion
            if (mHandler.hasMessages(0)) {
                delay = 1000;
            }
        }
        mWakeLock.acquire();
        mHandler.sendMessageDelayed(mHandler.obtainMessage(0,
                                                           mHeadsetState,
                                                           mPrevHeadsetState,
                                                           mHeadsetName),
                                    delay);
    }

    private synchronized final void sendIntents(int headsetState, int prevHeadsetState, String headsetName) {
        int allHeadsets = SUPPORTED_HEADSETS;
        for (int curHeadset = 1; allHeadsets != 0; curHeadset <<= 1) {
            if ((curHeadset & allHeadsets) != 0) {
                sendIntent(curHeadset, headsetState, prevHeadsetState, headsetName);
                allHeadsets &= ~curHeadset;
            }
        }
    }

    private final void sendIntent(int headset, int headsetState, int prevHeadsetState, String headsetName) {
        if ((headsetState & headset) != (prevHeadsetState & headset)) {

            int state = 0;
            if ((headsetState & headset) != 0) {
                state = 1;
            }
            if((headset == BIT_USB_HEADSET_ANLG) || (headset == BIT_USB_HEADSET_DGTL) ||
               (headset == BIT_HDMI_AUDIO)) {
                Intent intent;

                //  Pack up the values and broadcast them to everyone
                if (headset == BIT_USB_HEADSET_ANLG) {
                    intent = new Intent(Intent.ACTION_USB_ANLG_HEADSET_PLUG);
                    intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                    intent.putExtra("state", state);
                    intent.putExtra("name", headsetName);
                    ActivityManagerNative.broadcastStickyIntent(intent, null);
                } else if (headset == BIT_USB_HEADSET_DGTL) {
                    intent = new Intent(Intent.ACTION_USB_DGTL_HEADSET_PLUG);
                    intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                    intent.putExtra("state", state);
                    intent.putExtra("name", headsetName);
                    ActivityManagerNative.broadcastStickyIntent(intent, null);
                } else if (headset == BIT_HDMI_AUDIO) {
                    intent = new Intent(Intent.ACTION_HDMI_AUDIO_PLUG);
                    intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                    intent.putExtra("state", state);
                    intent.putExtra("name", headsetName);
                    ActivityManagerNative.broadcastStickyIntent(intent, null);
                }

                if (LOG) Slog.v(TAG, "Intent.ACTION_USB_HEADSET_PLUG: state: "+state+" name: "+headsetName);
                // TODO: Should we require a permission?
            }
            if((headset == BIT_HEADSET) || (headset == BIT_HEADSET_NO_MIC)) {

                //  Pack up the values and broadcast them to everyone
                Intent intent = new Intent(Intent.ACTION_HEADSET_PLUG);
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                //int state = 0;
                int microphone = 0;

                if ((headset & HEADSETS_WITH_MIC) != 0) {
                    microphone = 1;
                }

                intent.putExtra("state", state);
                intent.putExtra("name", headsetName);
                intent.putExtra("microphone", microphone);

                if (LOG) Slog.v(TAG, "Intent.ACTION_HEADSET_PLUG: state: "+state+" name: "+headsetName+" mic: "+microphone);
                // TODO: Should we require a permission?
                ActivityManagerNative.broadcastStickyIntent(intent, null);
            }
        }
    }

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            sendIntents(msg.arg1, msg.arg2, (String)msg.obj);
            mWakeLock.release();
        }
    };
}
