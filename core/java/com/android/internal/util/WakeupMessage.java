/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.internal.util;

import android.app.AlarmManager;
import android.content.Context;
import android.os.Handler;
import android.os.Message;

 /**
 * An AlarmListener that sends the specified message to a Handler and keeps the system awake until
 * the message is processed.
 *
 * This is useful when using the AlarmManager direct callback interface to wake up the system and
 * request that an object whose API consists of messages (such as a StateMachine) perform some
 * action.
 *
 * In this situation, using AlarmManager.onAlarmListener by itself will wake up the system to send
 * the message, but does not guarantee that the system will be awake until the target object has
 * processed it. This is because as soon as the onAlarmListener sends the message and returns, the
 * AlarmManager releases its wakelock and the system is free to go to sleep again.
 *
 */
public class WakeupMessage implements AlarmManager.OnAlarmListener {
    private static AlarmManager sAlarmManager;
    private final Handler mHandler;
    private final String mCmdName;
    private final int mCmd, mArg1, mArg2;

    public WakeupMessage(Context context, Handler handler,
            String cmdName, int cmd, int arg1, int arg2) {
        if (sAlarmManager == null) {
            sAlarmManager = context.getSystemService(AlarmManager.class);
        }
        mHandler = handler;
        mCmdName = cmdName;
        mCmd = cmd;
        mArg1 = arg1;
        mArg2 = arg2;
    }

    public WakeupMessage(Context context, Handler handler, String cmdName, int cmd, int arg1) {
        this(context, handler, cmdName, cmd, arg1, 0);
    }

    public WakeupMessage(Context context, Handler handler, String cmdName, int cmd) {
        this(context, handler, cmdName, cmd, 0, 0);
    }

    public void schedule(long when) {
        sAlarmManager.setExact(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, when, mCmdName, this, mHandler);
    }

    public void cancel() {
        sAlarmManager.cancel(this);
    }

    @Override
    public void onAlarm() {
        Message msg = mHandler.obtainMessage(mCmd, mArg1, mArg2);
        mHandler.handleMessage(msg);
        msg.recycle();
    }
}
