/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.testing.sleephelper;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Debug;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.testing.alarmservice.Alarm;

public class SetAlarm extends Instrumentation {

    private static final String COMMAND = "command";
    private static final String PARAM = "param";
    private static final String CMD_PREPARE = "prepare";
    private static final String CMD_SET = "set_wait";
    private static final String CMD_DONE = "done";
    private static final String SERVICE_ACTION = "com.android.testing.ALARM_SERVICE";
    private static final String SERVICE_PKG = "com.android.testing.alarmservice";
    private static final String LOG_TAG = SetAlarm.class.getSimpleName();

    private Alarm mAlarmService = null;
    private Bundle mArgs = null;
    private String mCommand = null;
    private Intent mServceIntent = new Intent(SERVICE_ACTION).setPackage(SERVICE_PKG);

    private ServiceConnection mConn = new ServiceConnection() {
        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(LOG_TAG, "Service disconnected.");
            mAlarmService = null;
            errorFinish("service disconnected");
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(LOG_TAG, "Service connected.");
            mAlarmService = Alarm.Stub.asInterface(service);
            handleCommands();
        }
    };


    private void handleCommands() {
        if (CMD_PREPARE.equals(mCommand)) {
            callPrepare();
        } else if (CMD_SET.equals(mCommand)) {
            String paramString = mArgs.getString(PARAM);
            if (paramString == null) {
                errorFinish("argument expected for alarm time");
            }
            long timeout = -1;
            try {
                timeout = Long.parseLong(paramString);
            } catch (NumberFormatException nfe) {
                errorFinish("a number argument is expected");
            }
            callSetAndWait(timeout);
        } else if (CMD_DONE.equals(mCommand)) {
            callDone();
        } else {
            errorFinish("Unrecognized command: " + mCommand);
        }
        finish(Activity.RESULT_OK, new Bundle());
    }

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        mCommand = arguments.getString(COMMAND);
        if ("true".equals(arguments.getString("debug"))) {
            Debug.waitForDebugger();
        }
        if (mCommand == null) {
            errorFinish("No command specified");
        }
        mArgs = arguments;
        connectToAlarmService();
    }

    private void errorFinish(String msg) {
        Bundle ret = new Bundle();
        ret.putString("error", msg);
        finish(Activity.RESULT_CANCELED, ret);
    }

    private void connectToAlarmService() {
        // start the service with an intent, this ensures the service keeps running after unbind
        ComponentName cn = getContext().startService(mServceIntent);
        if (cn == null) {
            errorFinish("failed to start service");
        }
        if (!getContext().bindService(mServceIntent, mConn, Context.BIND_AUTO_CREATE)) {
            errorFinish("failed to bind service");
        }
    }

    private void callPrepare() {
        try {
            mAlarmService.prepare();
        } catch (RemoteException e) {
            errorFinish("RemoteExeption in prepare()");
        } finally {
            getContext().unbindService(mConn);
        }
    }

    private void callDone() {
        try {
            mAlarmService.done();
        } catch (RemoteException e) {
            errorFinish("RemoteExeption in prepare()");
        } finally {
            getContext().unbindService(mConn);
        }
        // explicitly stop the service (started in prepare()) so that the service is now free
        // to be reclaimed
        getContext().stopService(mServceIntent);
    }

    private void callSetAndWait(long timeoutMills) {
        try {
            mAlarmService.setAlarmAndWait(timeoutMills);
        } catch (RemoteException e) {
            errorFinish("RemoteExeption in setAlarmAndWait()");
        } finally {
            getContext().unbindService(mConn);
        }
    }
}
