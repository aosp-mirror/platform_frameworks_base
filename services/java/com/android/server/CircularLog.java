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

package com.android.server;

import android.text.format.DateFormat;

import java.io.PrintWriter;

public class CircularLog {
    private final String[] mLog;
    int mHead;

    public CircularLog(int size) {
        mLog = new String[size];
        mLog[mHead++] = "Log start, less than " + size + " log entries entered.";
    }

    public void add(String msg) {
        StringBuffer sb = new StringBuffer();
        long now = System.currentTimeMillis();
        sb.append(DateFormat.format("yyyy-MM-dd HH:mm:ss", now));
        sb.append(".");
        sb.append(String.format("%03d: ", now % 1000));
        sb.append(msg);

        mLog[(int)(mHead % mLog.length)] = sb.toString();
        ++mHead;
    }

    public String[] getLog() {
        final int length = Math.min(mHead, mLog.length);
        final String[] logCopy = new String[length];
        if (mHead > length) {
            final int start = mHead % length;
            System.arraycopy(mLog, start, logCopy, 0, length - start);
            System.arraycopy(mLog, 0, logCopy, length - start, start);
        } else {
            System.arraycopy(mLog, 0, logCopy, 0, length);
        }
        return logCopy;
    }

    public void dump(PrintWriter pw, String prefix) {
        final int length = Math.min(mHead, mLog.length);
        if (mHead > length) {
            final int start = mHead % length;
            for (int i = start; i < length; ++i) {
                pw.print(prefix); pw.println(mLog[i]);
            }
            for (int i = 0; i < start; ++i) {
                pw.print(prefix); pw.println(mLog[i]);
            }
        } else {
            for (int i = 0; i < length; ++i) {
                pw.print(prefix); pw.println(mLog[i]);
            }
        }
    }
}
