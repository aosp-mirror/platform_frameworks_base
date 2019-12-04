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
    private int mType;

    /**
     * Initializes a rich event that includes an event type that matches with an index in the array
     * getEventLabels().
     */
    public RichEvent init(@Event.Level int logLevel, int type, String reason) {
        final int numEvents = getEventLabels().length;
        if (type < 0 || type >= numEvents) {
            throw new IllegalArgumentException("Unsupported event type. Events only supported"
                    + " from 0 to " + (numEvents - 1) + ", but given type=" + type);
        }
        mType = type;
        super.init(logLevel, getEventLabels()[mType] + " " + reason);
        return this;
    }

    /**
     * Returns an array of the event labels.  The index represents the event type and the
     * corresponding String stored at that index is the user-readable representation of that event.
     * @return array of user readable events, where the index represents its event type constant
     */
    public abstract String[] getEventLabels();

    @Override
    public void recycle() {
        super.recycle();
        mType = -1;
    }

    public int getType() {
        return mType;
    }

    /**
     * Builder to build a RichEvent.
     * @param <B> Log specific builder that is extending this builder
     * @param <E> Type of event we'll be building
     */
    public abstract static class Builder<B extends Builder<B, E>, E extends RichEvent> {
        public static final int UNINITIALIZED = -1;

        public final SysuiLog mLog;
        private B mBuilder = getBuilder();
        protected int mType;
        protected String mReason;
        protected @Level int mLogLevel;

        public Builder(SysuiLog sysuiLog) {
            mLog = sysuiLog;
            reset();
        }

        /**
         * Reset this builder's parameters so it can be reused to build another RichEvent.
         */
        public void reset() {
            mType = UNINITIALIZED;
            mReason = null;
            mLogLevel = VERBOSE;
        }

        /**
         * Get the log-specific builder.
         */
        public abstract B getBuilder();

        /**
         * Build the log-specific event given an event to populate.
         */
        public abstract E build(E e);

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
