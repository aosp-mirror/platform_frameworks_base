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

/**
 * Stores information about an event that occurred in SystemUI to be used for debugging and triage.
 * Every rich event has a time stamp, event type, and log level, with the option to provide the
 * reason this event was triggered.
 * Events are stored in {@link SysuiLog} and can be printed in a dumpsys.
 */
public abstract class RichEvent extends Event {
    private final int mType;
    private final String mReason;

    /**
     * Create a rich event that includes an event type that matches with an index in the array
     * getEventLabels().
     */
    public RichEvent(@Event.Level int logLevel, int type, String reason) {
        super(logLevel, null);
        final int numEvents = getEventLabels().length;
        if (type < 0 || type >= numEvents) {
            throw new IllegalArgumentException("Unsupported event type. Events only supported"
                    + " from 0 to " + (numEvents - 1) + ", but given type=" + type);
        }
        mType = type;
        mReason = reason;
        mMessage = getEventLabels()[mType] + " " + mReason;
    }

    /**
     * Returns an array of the event labels.  The index represents the event type and the
     * corresponding String stored at that index is the user-readable representation of that event.
     * @return array of user readable events, where the index represents its event type constant
     */
    public abstract String[] getEventLabels();

    public int getType() {
        return mType;
    }

    public String getReason() {
        return mReason;
    }

    /**
     * Builder to build a RichEvent.
     * @param <B> Log specific builder that is extending this builder
     */
    public abstract static class Builder<B extends Builder<B>> {
        public static final int UNINITIALIZED = -1;

        private B mBuilder = getBuilder();
        protected int mType = UNINITIALIZED;
        protected String mReason;
        protected @Level int mLogLevel;

        /**
         * Get the log-specific builder.
         */
        public abstract B getBuilder();

        /**
         * Build the log-specific event.
         */
        public abstract RichEvent build();

        /**
         * Optional - set the log level. Defaults to DEBUG.
         */
        public B setLogLevel(@Level int logLevel) {
            mLogLevel = logLevel;
            return mBuilder;
        }

        /**
         * Required - set the event type.  These events must correspond with the events from
         * getEventLabels().
         */
        public B setType(int type) {
            mType = type;
            return mBuilder;
        }

        /**
         * Optional - set the reason why this event was triggered.
         */
        public B setReason(String reason) {
            mReason = reason;
            return mBuilder;
        }
    }
}
