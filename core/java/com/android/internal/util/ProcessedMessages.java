/**
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.internal.util;

import android.os.Message;

import java.util.Vector;

/**
 * {@hide}
 *
 * A list of messages recently processed by the state machine.
 *
 * The class maintains a list of messages that have been most
 * recently processed. The list is finite and may be set in the
 * constructor or by calling setSize. The public interface also
 * includes size which returns the number of recent messages,
 * count which is the number of message processed since the
 * the last setSize, get which returns a processed message and
 * add which adds a processed messaged.
 */
public class ProcessedMessages {

    public static final int DEFAULT_SIZE = 20;

    /**
     * The information maintained for a processed message.
     */
    public class Info {
        private int what;
        private HierarchicalState state;
        private HierarchicalState orgState;

        /**
         * Constructor
         * @param message
         * @param state that handled the message
         * @param orgState is the first state the received the message but
         * did not processes the message.
         */
        Info(Message message, HierarchicalState state, HierarchicalState orgState) {
            update(message, state, orgState);
        }

        /**
         * Update the information in the record.
         * @param state that handled the message
         * @param orgState is the first state the received the message but
         * did not processes the message.
         */
        public void update(Message message, HierarchicalState state, HierarchicalState orgState) {
            this.what = message.what;
            this.state = state;
            this.orgState = orgState;
        }

        /**
         * @return the command that was executing
         */
        public int getWhat() {
            return what;
        }

        /**
         * @return the state that handled this message
         */
        public HierarchicalState getState() {
            return state;
        }

        /**
         * @return the original state that received the message.
         */
        public HierarchicalState getOriginalState() {
            return orgState;
        }

        /**
         * @return as string
         */
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("what=");
            sb.append(what);
            sb.append(" state=");
            sb.append(cn(state));
            sb.append(" orgState=");
            sb.append(cn(orgState));
            return sb.toString();
        }

        /**
         * @return an objects class name
         */
        private String cn(Object n) {
            if (n == null) {
                return "null";
            } else {
                String name = n.getClass().getName();
                int lastDollar = name.lastIndexOf('$');
                return name.substring(lastDollar + 1);
            }
        }
    }

    private Vector<Info> mMessages = new Vector<Info>();
    private int mMaxSize = DEFAULT_SIZE;
    private int mOldestIndex = 0;
    private int mCount = 0;

    /**
     * Constructor
     */
    ProcessedMessages() {
    }

    ProcessedMessages(int maxSize) {
        setSize(maxSize);
    }

    /**
     * Set size of messages to maintain and clears all current messages.
     *
     * @param maxSize number of messages to maintain at anyone time.
    */
    void setSize(int maxSize) {
        mMaxSize = maxSize;
        mCount = 0;
        mMessages.clear();
    }

    /**
     * @return the number of recent messages.
     */
    int size() {
        return mMessages.size();
    }

    /**
     * @return the total number of messages processed since size was set.
     */
    int count() {
        return mCount;
    }

    /**
     * @return the information on a particular record. 0 is the oldest
     * record and size()-1 is the newest record. If the index is to
     * large null is returned.
     */
    Info get(int index) {
        int nextIndex = mOldestIndex + index;
        if (nextIndex >= mMaxSize) {
            nextIndex -= mMaxSize;
        }
        if (nextIndex >= size()) {
            return null;
        } else {
            return mMessages.get(nextIndex);
        }
    }

    /**
     * Add a processed message.
     *
     * @param message
     * @param state that handled the message
     * @param orgState is the first state the received the message but
     * did not processes the message.
     */
    void add(Message message, HierarchicalState state, HierarchicalState orgState) {
        mCount += 1;
        if (mMessages.size() < mMaxSize) {
            mMessages.add(new Info(message, state, orgState));
        } else {
            Info info = mMessages.get(mOldestIndex);
            mOldestIndex += 1;
            if (mOldestIndex >= mMaxSize) {
                mOldestIndex = 0;
            }
            info.update(message, state, orgState);
        }
    }
}
