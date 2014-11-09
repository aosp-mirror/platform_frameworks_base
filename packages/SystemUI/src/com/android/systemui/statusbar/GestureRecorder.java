/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.systemui.statusbar;

import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;

import java.io.BufferedWriter;
import java.io.FileDescriptor;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.LinkedList;

/**
 * Convenience class for capturing gestures for later analysis.
 */
public class GestureRecorder {
    public static final boolean DEBUG = false; // for now
    public static final String TAG = GestureRecorder.class.getSimpleName();

    public class Gesture {
        public abstract class Record {
            long time;
            public abstract String toJson();
        }
        public class MotionEventRecord extends Record {
            public MotionEvent event;
            public MotionEventRecord(long when, MotionEvent event) {
                this.time = when;
                this.event = MotionEvent.obtain(event);
            }
            String actionName(int action) {
                switch (action) {
                    case MotionEvent.ACTION_DOWN:
                        return "down";
                    case MotionEvent.ACTION_UP:
                        return "up";
                    case MotionEvent.ACTION_MOVE:
                        return "move";
                    case MotionEvent.ACTION_CANCEL:
                        return "cancel";
                    default:
                        return String.valueOf(action);
                }
            }
            public String toJson() {
                return String.format(
                        ("{\"type\":\"motion\", \"time\":%d, \"action\":\"%s\", "
                            + "\"x\":%.2f, \"y\":%.2f, \"s\":%.2f, \"p\":%.2f}"),
                        this.time,
                        actionName(this.event.getAction()),
                        this.event.getRawX(),
                        this.event.getRawY(),
                        this.event.getSize(),
                        this.event.getPressure()
                        );
            }
        }
        public class TagRecord extends Record {
            public String tag, info;
            public TagRecord(long when, String tag, String info) {
                this.time = when;
                this.tag = tag;
                this.info = info;
            }
            public String toJson() {
                return String.format("{\"type\":\"tag\", \"time\":%d, \"tag\":\"%s\", \"info\":\"%s\"}",
                        this.time,
                        this.tag,
                        this.info
                        );
            }
        }
        private LinkedList<Record> mRecords = new LinkedList<Record>();
        private HashSet<String> mTags = new HashSet<String>();
        long mDownTime = -1;
        boolean mComplete = false;

        public void add(MotionEvent ev) {
            mRecords.add(new MotionEventRecord(ev.getEventTime(), ev));
            if (mDownTime < 0) {
                mDownTime = ev.getDownTime();
            } else {
                if (mDownTime != ev.getDownTime()) {
                    Log.w(TAG, "Assertion failure in GestureRecorder: event downTime ("
                            +ev.getDownTime()+") does not match gesture downTime ("+mDownTime+")");
                }
            }
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mComplete = true;
            }
        }
        public void tag(long when, String tag, String info) {
            mRecords.add(new TagRecord(when, tag, info));
            mTags.add(tag);
        }
        public boolean isComplete() {
            return mComplete;
        }
        public String toJson() {
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            sb.append("[");
            for (Record r : mRecords) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(r.toJson());
            }
            sb.append("]");
            return sb.toString();
        }
    }

    // -=-=-=-=-=-=-=-=-=-=-=-

    static final long SAVE_DELAY = 5000; // ms
    static final int SAVE_MESSAGE = 6351;

    private LinkedList<Gesture> mGestures;
    private Gesture mCurrentGesture;
    private int mLastSaveLen = -1;
    private String mLogfile;

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == SAVE_MESSAGE) {
                save();
            }
        }
    };

    public GestureRecorder(String filename) {
        mLogfile = filename;
        mGestures = new LinkedList<Gesture>();
        mCurrentGesture = null;
    }

    public void add(MotionEvent ev) {
        synchronized (mGestures) {
            if (mCurrentGesture == null || mCurrentGesture.isComplete()) {
                mCurrentGesture = new Gesture();
                mGestures.add(mCurrentGesture);
            }
            mCurrentGesture.add(ev);
        }
        saveLater();
    }

    public void tag(long when, String tag, String info) {
        synchronized (mGestures) {
            if (mCurrentGesture == null) {
                mCurrentGesture = new Gesture();
                mGestures.add(mCurrentGesture);
            }
            mCurrentGesture.tag(when, tag, info);
        }
        saveLater();
    }

    public void tag(long when, String tag) {
        tag(when, tag, null);
    }

    public void tag(String tag) {
        tag(SystemClock.uptimeMillis(), tag, null);
    }

    public void tag(String tag, String info) {
        tag(SystemClock.uptimeMillis(), tag, info);
    }

    /**
     * Generates a JSON string capturing all completed gestures.
     * Not threadsafe; call with a lock.
     */
    public String toJsonLocked() {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        sb.append("[");
        int count = 0;
        for (Gesture g : mGestures) {
            if (!g.isComplete()) continue;
            if (!first) sb.append("," );
            first = false;
            sb.append(g.toJson());
            count++;
        }
        mLastSaveLen = count;
        sb.append("]");
        return sb.toString();
    }

    public String toJson() {
        String s;
        synchronized (mGestures) {
            s = toJsonLocked();
        }
        return s;
    }

    public void saveLater() {
        mHandler.removeMessages(SAVE_MESSAGE);
        mHandler.sendEmptyMessageDelayed(SAVE_MESSAGE, SAVE_DELAY);
    }

    public void save() {
        synchronized (mGestures) {
            try {
                BufferedWriter w = new BufferedWriter(new FileWriter(mLogfile, /*append=*/ true));
                w.append(toJsonLocked() + "\n");
                w.close();
                mGestures.clear();
                // If we have a pending gesture, push it back
                if (mCurrentGesture != null && !mCurrentGesture.isComplete()) {
                    mGestures.add(mCurrentGesture);
                }
                if (DEBUG) {
                    Log.v(TAG, String.format("Wrote %d complete gestures to %s", mLastSaveLen, mLogfile));
                }
            } catch (IOException e) {
                Log.e(TAG, String.format("Couldn't write gestures to %s", mLogfile), e);
                mLastSaveLen = -1;
            }
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        save();
        if (mLastSaveLen >= 0) {
            pw.println(String.valueOf(mLastSaveLen) + " gestures written to " + mLogfile);
        } else {
            pw.println("error writing gestures");
        }
    }
}
