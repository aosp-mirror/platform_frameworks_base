/*
 * Copyright (C) 2011 The Android Open Source Project
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

import com.google.android.collect.Lists;

import java.util.ArrayList;

/**
 * Parsed event from native side of {@link NativeDaemonConnector}.
 */
public class NativeDaemonEvent {

    // TODO: keep class ranges in sync with ResponseCode.h
    // TODO: swap client and server error ranges to roughly mirror HTTP spec

    private final int mCode;
    private final String mMessage;
    private final String mRawEvent;

    private NativeDaemonEvent(int code, String message, String rawEvent) {
        mCode = code;
        mMessage = message;
        mRawEvent = rawEvent;
    }

    public int getCode() {
        return mCode;
    }

    public String getMessage() {
        return mMessage;
    }

    @Deprecated
    public String getRawEvent() {
        return mRawEvent;
    }

    @Override
    public String toString() {
        return mRawEvent;
    }

    /**
     * Test if event represents a partial response which is continued in
     * additional subsequent events.
     */
    public boolean isClassContinue() {
        return mCode >= 100 && mCode < 200;
    }

    /**
     * Test if event represents a command success.
     */
    public boolean isClassOk() {
        return mCode >= 200 && mCode < 300;
    }

    /**
     * Test if event represents a remote native daemon error.
     */
    public boolean isClassServerError() {
        return mCode >= 400 && mCode < 500;
    }

    /**
     * Test if event represents a command syntax or argument error.
     */
    public boolean isClassClientError() {
        return mCode >= 500 && mCode < 600;
    }

    /**
     * Test if event represents an unsolicited event from native daemon.
     */
    public boolean isClassUnsolicited() {
        return mCode >= 600 && mCode < 700;
    }

    /**
     * Verify this event matches the given code.
     *
     * @throws IllegalStateException if {@link #getCode()} doesn't match.
     */
    public void checkCode(int code) {
        if (mCode != code) {
            throw new IllegalStateException("Expected " + code + " but was: " + this);
        }
    }

    /**
     * Parse the given raw event into {@link NativeDaemonEvent} instance.
     *
     * @throws IllegalArgumentException when line doesn't match format expected
     *             from native side.
     */
    public static NativeDaemonEvent parseRawEvent(String rawEvent) {
        final int splitIndex = rawEvent.indexOf(' ');
        if (splitIndex == -1) {
            throw new IllegalArgumentException("unable to find ' ' separator");
        }

        final int code;
        try {
            code = Integer.parseInt(rawEvent.substring(0, splitIndex));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("problem parsing code", e);
        }

        final String message = rawEvent.substring(splitIndex + 1);
        return new NativeDaemonEvent(code, message, rawEvent);
    }

    /**
     * Filter the given {@link NativeDaemonEvent} list, returning
     * {@link #getMessage()} for any events matching the requested code.
     */
    public static String[] filterMessageList(NativeDaemonEvent[] events, int matchCode) {
        final ArrayList<String> result = Lists.newArrayList();
        for (NativeDaemonEvent event : events) {
            if (event.getCode() == matchCode) {
                result.add(event.getMessage());
            }
        }
        return result.toArray(new String[result.size()]);
    }
}
