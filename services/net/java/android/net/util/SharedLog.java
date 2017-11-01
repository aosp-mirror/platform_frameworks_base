/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.net.util;

import android.text.TextUtils;
import android.util.LocalLog;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.StringJoiner;


/**
 * Class to centralize logging functionality for tethering.
 *
 * All access to class methods other than dump() must be on the same thread.
 *
 * @hide
 */
public class SharedLog {
    private final static int DEFAULT_MAX_RECORDS = 500;
    private final static String COMPONENT_DELIMITER = ".";

    private enum Category {
        NONE,
        ERROR,
        MARK,
        WARN,
    };

    private final LocalLog mLocalLog;
    // The tag to use for output to the system log. This is not output to the
    // LocalLog because that would be redundant.
    private final String mTag;
    // The component (or subcomponent) of a system that is sharing this log.
    // This can grow in depth if components call forSubComponent() to obtain
    // their SharedLog instance. The tag is not included in the component for
    // brevity.
    private final String mComponent;

    public SharedLog(String tag) {
        this(DEFAULT_MAX_RECORDS, tag);
    }

    public SharedLog(int maxRecords, String tag) {
        this(new LocalLog(maxRecords), tag, tag);
    }

    private SharedLog(LocalLog localLog, String tag, String component) {
        mLocalLog = localLog;
        mTag = tag;
        mComponent = component;
    }

    public SharedLog forSubComponent(String component) {
        if (!isRootLogInstance()) {
            component = mComponent + COMPONENT_DELIMITER + component;
        }
        return new SharedLog(mLocalLog, mTag, component);
    }

    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        mLocalLog.readOnlyLocalLog().dump(fd, writer, args);
    }

    //////
    // Methods that both log an entry and emit it to the system log.
    //////

    public void e(Exception e) {
        Log.e(mTag, record(Category.ERROR, e.toString()));
    }

    public void e(String msg) {
        Log.e(mTag, record(Category.ERROR, msg));
    }

    public void i(String msg) {
        Log.i(mTag, record(Category.NONE, msg));
    }

    public void w(String msg) {
        Log.w(mTag, record(Category.WARN, msg));
    }

    //////
    // Methods that only log an entry (and do NOT emit to the system log).
    //////

    public void log(String msg) {
        record(Category.NONE, msg);
    }

    public void logf(String fmt, Object... args) {
        log(String.format(fmt, args));
    }

    public void mark(String msg) {
        record(Category.MARK, msg);
    }

    private String record(Category category, String msg) {
        final String entry = logLine(category, msg);
        mLocalLog.log(entry);
        return entry;
    }

    private String logLine(Category category, String msg) {
        final StringJoiner sj = new StringJoiner(" ");
        if (!isRootLogInstance()) sj.add("[" + mComponent + "]");
        if (category != Category.NONE) sj.add(category.toString());
        return sj.add(msg).toString();
    }

    // Check whether this SharedLog instance is nominally the top level in
    // a potential hierarchy of shared logs (the root of a tree),
    // or is a subcomponent within the hierarchy.
    private boolean isRootLogInstance() {
        return TextUtils.isEmpty(mComponent) || mComponent.equals(mTag);
    }
}
