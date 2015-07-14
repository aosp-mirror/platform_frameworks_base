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

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Calendar;
import java.util.Iterator;
import java.util.LinkedList;

/**
 * @hide
 */
public final class LocalLog {

    private LinkedList<String> mLog;
    private int mMaxLines;
    private long mNow;

    public LocalLog(int maxLines) {
        mLog = new LinkedList<String>();
        mMaxLines = maxLines;
    }

    public synchronized void log(String msg) {
        if (mMaxLines > 0) {
            mNow = System.currentTimeMillis();
            StringBuilder sb = new StringBuilder();
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(mNow);
            sb.append(String.format("%tm-%td %tH:%tM:%tS.%tL", c, c, c, c, c, c));
            mLog.add(sb.toString() + " - " + msg);
            while (mLog.size() > mMaxLines) mLog.remove();
        }
    }

    public synchronized void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        Iterator<String> itr = mLog.listIterator(0);
        while (itr.hasNext()) {
            pw.println(itr.next());
        }
    }

    public synchronized void reverseDump(FileDescriptor fd, PrintWriter pw, String[] args) {
        for (int i = mLog.size() - 1; i >= 0; i--) {
            pw.println(mLog.get(i));
        }
    }

    public static class ReadOnlyLocalLog {
        private final LocalLog mLog;
        ReadOnlyLocalLog(LocalLog log) {
            mLog = log;
        }
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            mLog.dump(fd, pw, args);
        }
    }

    public ReadOnlyLocalLog readOnlyLocalLog() {
        return new ReadOnlyLocalLog(this);
    }
}
