/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.log;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Stores information about an event that occurred in SystemUI to be used for debugging and triage.
 * Every event has a time stamp, log level and message.
 * Events are stored in {@link SysuiLog} and can be printed in a dumpsys.
 */
public class Event {
    public static final int UNINITIALIZED = -1;

    @IntDef({ERROR, WARN, INFO, DEBUG, VERBOSE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Level {}
    public static final int VERBOSE = 2;
    public static final int DEBUG = 3;
    public static final int INFO = 4;
    public static final int WARN = 5;
    public static final int ERROR = 6;
    public static final @Level int DEFAULT_LOG_LEVEL = DEBUG;

    private long mTimestamp;
    private @Level int mLogLevel = DEFAULT_LOG_LEVEL;
    private String mMessage = "";

    /**
     * initialize an event with a message
     */
    public Event init(String message) {
        init(DEFAULT_LOG_LEVEL, message);
        return this;
    }

    /**
     * initialize an event with a logLevel and message
     */
    public Event init(@Level int logLevel, String message) {
        mTimestamp = System.currentTimeMillis();
        mLogLevel = logLevel;
        mMessage = message;
        return this;
    }

    public String getMessage() {
        return mMessage;
    }

    public long getTimestamp() {
        return mTimestamp;
    }

    public @Level int getLogLevel() {
        return mLogLevel;
    }

    /**
     * Recycle this event
     */
    void recycle() {
        mTimestamp = -1;
        mLogLevel = DEFAULT_LOG_LEVEL;
        mMessage = "";
    }
}
