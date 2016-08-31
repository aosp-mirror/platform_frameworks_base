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

import com.android.internal.annotations.VisibleForTesting;

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
 */
public class WakeupMessage implements AlarmManager.OnAlarmListener {
    private final AlarmManager mAlarmManager;

    @VisibleForTesting
    protected final Handler mHandler;
    @VisibleForTesting
    protected final String mCmdName;
    @VisibleForTesting
    protected final int mCmd, mArg1, mArg2;
    @VisibleForTesting
    protected final Object mObj;
    private boolean mScheduled;

    public WakeupMessage(Context context, Handler handler,
            String cmdName, int cmd, int arg1, int arg2, Object obj) {
        mAlarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        mHandler = handler;
        mCmdName = cmdName;
        mCmd = cmd;
        mArg1 = arg1;
        mArg2 = arg2;
        mObj = obj;
    }

    public WakeupMessage(Context context, Handler handler, String cmdName, int cmd, int arg1) {
        this(context, handler, cmdName, cmd, arg1, 0, null);
    }

    public WakeupMessage(Context context, Handler handler,
            String cmdName, int cmd, int arg1, int arg2) {
        this(context, handler, cmdName, cmd, arg1, arg2, null);
    }

    public WakeupMessage(Context context, Handler handler, String cmdName, int cmd) {
        this(context, handler, cmdName, cmd, 0, 0, null);
    }

    /**
     * Schedule the message to be delivered at the time in milliseconds of the
     * {@link android.os.SystemClock#elapsedRealtime SystemClock.elapsedRealtime()} clock and wakeup
     * the device when it goes off. If schedule is called multiple times without the message being
     * dispatched then the alarm is rescheduled to the new time.
     */
    public synchronized void schedule(long when) {
        mAlarmManager.setExact(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, when, mCmdName, this, mHandler);
        mScheduled = true;
    }

    /**
     * Cancel all pending messages. This includes alarms that may have been fired, but have not been
     * run on the handler yet.
     */
    public synchronized void cancel() {
        if (mScheduled) {
            mAlarmManager.cancel(this);
            mScheduled = false;
        }
    }

    @Override
    public void onAlarm() {
        // Once this method is called the alarm has already been fired and removed from
        // AlarmManager (it is still partially tracked, but only for statistics). The alarm can now
        // be marked as unscheduled so that it can be rescheduled in the message handler.
        final boolean stillScheduled;
        synchronized (this) {
            stillScheduled = mScheduled;
            mScheduled = false;
        }
        if (stillScheduled) {
            Message msg = mHandler.obtainMessage(mCmd, mArg1, mArg2, mObj);
            mHandler.dispatchMessage(msg);
            msg.recycle();
        }
    }
}
