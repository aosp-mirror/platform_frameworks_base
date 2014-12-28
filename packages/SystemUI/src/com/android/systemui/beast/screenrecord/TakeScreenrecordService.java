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
 *
 * Per article 5 of the Apache 2.0 License, some modifications to this code
 * were made by the OmniROM Project.
 *
 * Modifications Copyright (C) 2013 The OmniROM Project
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package com.android.systemui.beast.screenrecord;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;

public class TakeScreenrecordService extends Service {
    private static final String TAG = "TakeScreenrecordService";

    public static final String ACTION_START = "start";
    public static final String ACTION_STOP = "stop";
    public static final String ACTION_TOGGLE_POINTER = "toggle_pointer";

    private static GlobalScreenrecord mScreenrecord;

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 1:
                    final Messenger callback = msg.replyTo;
                    toggleScreenrecord();

                    Message reply = Message.obtain(null, 1);
                    try {
                        callback.send(reply);
                    } catch (RemoteException e) {
                    }
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return new Messenger(mHandler).getBinder();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (intent.getAction().equals(ACTION_START)) {
                startScreenrecord();
            } else if (intent.getAction().equals(ACTION_STOP)) {
                stopScreenrecord();
            } else if (intent.getAction().equals(ACTION_TOGGLE_POINTER)) {
                int currentStatus = Settings.System.getIntForUser(getContentResolver(),
                            Settings.System.SHOW_TOUCHES, 0, UserHandle.USER_CURRENT);
                Settings.System.putIntForUser(getContentResolver(), Settings.System.SHOW_TOUCHES,
                            1 - currentStatus, UserHandle.USER_CURRENT);
                mScreenrecord.updateNotification();
            }
        }

        return super.onStartCommand(intent, flags, startId);
    }

    private void startScreenrecord() {
        if (mScreenrecord == null) {
            mScreenrecord = new GlobalScreenrecord(TakeScreenrecordService.this);
        }
        mScreenrecord.takeScreenrecord();
    }

    private void stopScreenrecord() {
        if (mScreenrecord == null) {
            return;
        }
        mScreenrecord.stopScreenrecord();

        // Turn off pointer in all cases
        Settings.System.putIntForUser(getContentResolver(), Settings.System.SHOW_TOUCHES,
                0, UserHandle.USER_CURRENT);
    }

    private void toggleScreenrecord() {
        if (mScreenrecord == null || !mScreenrecord.isRecording()) {
            startScreenrecord();
        } else {
            stopScreenrecord();
        }
    }
}
