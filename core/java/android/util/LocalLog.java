/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.util;

import android.annotation.UnsupportedAppUsage;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/**
 * @hide
 */
public final class LocalLog {

    private final Deque<String> mLog;
    private final int mMaxLines;

    @UnsupportedAppUsage
    public LocalLog(int maxLines) {
        mMaxLines = Math.max(0, maxLines);
        mLog = new ArrayDeque<>(mMaxLines);
    }

    @UnsupportedAppUsage
    public void log(String msg) {
        if (mMaxLines <= 0) {
            return;
        }
        append(String.format("%s - %s", LocalDateTime.now(), msg));
    }

    private synchronized void append(String logLine) {
        while (mLog.size() >= mMaxLines) {
            mLog.remove();
        }
        mLog.add(logLine);
    }

    @UnsupportedAppUsage
    public synchronized void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        dump(pw);
    }

    public synchronized void dump(PrintWriter pw) {
        Iterator<String> itr = mLog.iterator();
        while (itr.hasNext()) {
            pw.println(itr.next());
        }
    }

    public synchronized void reverseDump(FileDescriptor fd, PrintWriter pw, String[] args) {
        reverseDump(pw);
    }

    public synchronized void reverseDump(PrintWriter pw) {
        Iterator<String> itr = mLog.descendingIterator();
        while (itr.hasNext()) {
            pw.println(itr.next());
        }
    }

    public static class ReadOnlyLocalLog {
        private final LocalLog mLog;
        ReadOnlyLocalLog(LocalLog log) {
            mLog = log;
        }
        @UnsupportedAppUsage
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            mLog.dump(pw);
        }
        public void dump(PrintWriter pw) {
            mLog.dump(pw);
        }
        public void reverseDump(FileDescriptor fd, PrintWriter pw, String[] args) {
            mLog.reverseDump(pw);
        }
        public void reverseDump(PrintWriter pw) {
            mLog.reverseDump(pw);
        }
    }

    @UnsupportedAppUsage
    public ReadOnlyLocalLog readOnlyLocalLog() {
        return new ReadOnlyLocalLog(this);
    }
}
