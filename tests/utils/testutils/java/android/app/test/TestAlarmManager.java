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

package android.app.test;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import android.app.AlarmManager;
import android.app.test.MockAnswerUtil.AnswerWithArguments;
import android.os.Handler;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * Creates an AlarmManager whose alarm dispatch can be controlled
 * Currently only supports alarm listeners
 *
 * Alarm listeners will be dispatched to the handler provided or will
 * be dispatched immediately if they would have been sent to the main
 * looper (handler was null).
 */
public class TestAlarmManager {
    private final AlarmManager mAlarmManager;
    private final List<PendingAlarm> mPendingAlarms;

    public TestAlarmManager() throws Exception {
        mPendingAlarms = new ArrayList<>();

        mAlarmManager = mock(AlarmManager.class);
        doAnswer(new SetListenerAnswer()).when(mAlarmManager).set(anyInt(), anyLong(), anyString(),
                any(AlarmManager.OnAlarmListener.class), any(Handler.class));
        doAnswer(new SetListenerAnswer()).when(mAlarmManager).setExact(anyInt(), anyLong(),
                anyString(), any(AlarmManager.OnAlarmListener.class), any(Handler.class));
        doAnswer(new CancelListenerAnswer())
                .when(mAlarmManager).cancel(any(AlarmManager.OnAlarmListener.class));
    }

    public AlarmManager getAlarmManager() {
        return mAlarmManager;
    }

    /**
     * Dispatch a pending alarm with the given tag
     * @return if any alarm was dispatched
     */
    public boolean dispatch(String tag) {
        for (int i = 0; i < mPendingAlarms.size(); ++i) {
            PendingAlarm alarm = mPendingAlarms.get(i);
            if (Objects.equals(tag, alarm.getTag())) {
                mPendingAlarms.remove(i);
                alarm.dispatch();
                return true;
            }
        }
        return false;
    }

    /**
     * @return if an alarm with the given tag is pending
     */
    public boolean isPending(String tag) {
        for (int i = 0; i < mPendingAlarms.size(); ++i) {
            PendingAlarm alarm = mPendingAlarms.get(i);
            if (Objects.equals(tag, alarm.getTag())) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return trigger time of an pending alarm with the given tag
     *         -1 if no pending alarm with the given tag
     */
    public long getTriggerTimeMillis(String tag) {
        for (int i = 0; i < mPendingAlarms.size(); ++i) {
            PendingAlarm alarm = mPendingAlarms.get(i);
            if (Objects.equals(tag, alarm.getTag())) {
                return alarm.getTriggerTimeMillis();
            }
        }
        return -1;
    }

    private static class PendingAlarm {
        private final int mType;
        private final long mTriggerAtMillis;
        private final String mTag;
        private final Runnable mCallback;

        public PendingAlarm(int type, long triggerAtMillis, String tag, Runnable callback) {
            mType = type;
            mTriggerAtMillis = triggerAtMillis;
            mTag = tag;
            mCallback = callback;
        }

        public void dispatch() {
            if (mCallback != null) {
                mCallback.run();
            }
        }

        public Runnable getCallback() {
            return mCallback;
        }

        public String getTag() {
            return mTag;
        }

        public long getTriggerTimeMillis() {
            return mTriggerAtMillis;
        }
    }

    private class SetListenerAnswer extends AnswerWithArguments {
        public void answer(int type, long triggerAtMillis, String tag,
                AlarmManager.OnAlarmListener listener, Handler handler) {
            mPendingAlarms.add(new PendingAlarm(type, triggerAtMillis, tag,
                            new AlarmListenerRunnable(listener, handler)));
        }
    }

    private class CancelListenerAnswer extends AnswerWithArguments {
        public void answer(AlarmManager.OnAlarmListener listener) {
            Iterator<PendingAlarm> alarmItr = mPendingAlarms.iterator();
            while (alarmItr.hasNext()) {
                PendingAlarm alarm = alarmItr.next();
                if (alarm.getCallback() instanceof AlarmListenerRunnable) {
                    AlarmListenerRunnable alarmCallback =
                            (AlarmListenerRunnable) alarm.getCallback();
                    if (alarmCallback.getListener() == listener) {
                        alarmItr.remove();
                    }
                }
            }
        }
    }

    private static class AlarmListenerRunnable implements Runnable {
        private final AlarmManager.OnAlarmListener mListener;
        private final Handler mHandler;
        public AlarmListenerRunnable(AlarmManager.OnAlarmListener listener, Handler handler) {
            mListener = listener;
            mHandler = handler;
        }

        public AlarmManager.OnAlarmListener getListener() {
            return mListener;
        }

        @Override
        public void run() {
            if (mHandler != null) {
                mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            mListener.onAlarm();
                        }
                    });
            } else { // normally gets dispatched in main looper
                mListener.onAlarm();
            }
        }
    }
}
