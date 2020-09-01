/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.systemui.statusbar.policy;

import static com.android.systemui.statusbar.policy.NetworkControllerImpl.TAG;

import android.annotation.NonNull;
import android.content.Context;
import android.util.Log;

import com.android.systemui.statusbar.policy.NetworkController.SignalCallback;

import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.BitSet;


/**
 * Common base class for handling signal for both wifi and mobile data.
 */
public abstract class SignalController<T extends SignalController.State,
        I extends SignalController.IconGroup> {
    // Save the previous SignalController.States of all SignalControllers for dumps.
    static final boolean RECORD_HISTORY = true;
    // If RECORD_HISTORY how many to save, must be a power of 2.
    static final int HISTORY_SIZE = 64;

    protected static final boolean DEBUG = NetworkControllerImpl.DEBUG;
    protected static final boolean CHATTY = NetworkControllerImpl.CHATTY;

    protected final String mTag;
    protected final T mCurrentState;
    protected final T mLastState;
    protected final int mTransportType;
    protected final Context mContext;
    // The owner of the SignalController (i.e. NetworkController will maintain the following
    // lists and call notifyListeners whenever the list has changed to ensure everyone
    // is aware of current state.
    protected final NetworkControllerImpl mNetworkController;

    private final CallbackHandler mCallbackHandler;

    // Save the previous HISTORY_SIZE states for logging.
    private final State[] mHistory;
    // Where to copy the next state into.
    private int mHistoryIndex;

    public SignalController(String tag, Context context, int type, CallbackHandler callbackHandler,
            NetworkControllerImpl networkController) {
        mTag = TAG + "." + tag;
        mNetworkController = networkController;
        mTransportType = type;
        mContext = context;
        mCallbackHandler = callbackHandler;
        mCurrentState = cleanState();
        mLastState = cleanState();
        if (RECORD_HISTORY) {
            mHistory = new State[HISTORY_SIZE];
            for (int i = 0; i < HISTORY_SIZE; i++) {
                mHistory[i] = cleanState();
            }
        }
    }

    public T getState() {
        return mCurrentState;
    }

    public void updateConnectivity(BitSet connectedTransports, BitSet validatedTransports) {
        mCurrentState.inetCondition = validatedTransports.get(mTransportType) ? 1 : 0;
        notifyListenersIfNecessary();
    }

    /**
     * Used at the end of demo mode to clear out any ugly state that it has created.
     * Since we haven't had any callbacks, then isDirty will not have been triggered,
     * so we can just take the last good state directly from there.
     *
     * Used for demo mode.
     */
    public void resetLastState() {
        mCurrentState.copyFrom(mLastState);
    }

    /**
     * Determines if the state of this signal controller has changed and
     * needs to trigger callbacks related to it.
     */
    public boolean isDirty() {
        if (!mLastState.equals(mCurrentState)) {
            if (DEBUG) {
                Log.d(mTag, "Change in state from: " + mLastState + "\n"
                        + "\tto: " + mCurrentState);
            }
            return true;
        }
        return false;
    }

    public void saveLastState() {
        if (RECORD_HISTORY) {
            recordLastState();
        }
        // Updates the current time.
        mCurrentState.time = System.currentTimeMillis();
        mLastState.copyFrom(mCurrentState);
    }

    /**
     * Gets the signal icon for QS based on current state of connected, enabled, and level.
     */
    public int getQsCurrentIconId() {
        if (mCurrentState.connected) {
            return getIcons().mQsIcons[mCurrentState.inetCondition][mCurrentState.level];
        } else if (mCurrentState.enabled) {
            return getIcons().mQsDiscState;
        } else {
            return getIcons().mQsNullState;
        }
    }

    /**
     * Gets the signal icon for SB based on current state of connected, enabled, and level.
     */
    public int getCurrentIconId() {
        if (mCurrentState.connected) {
            return getIcons().mSbIcons[mCurrentState.inetCondition][mCurrentState.level];
        } else if (mCurrentState.enabled) {
            return getIcons().mSbDiscState;
        } else {
            return getIcons().mSbNullState;
        }
    }

    /**
     * Gets the content description id for the signal based on current state of connected and
     * level.
     */
    public int getContentDescription() {
        if (mCurrentState.connected) {
            return getIcons().mContentDesc[mCurrentState.level];
        } else {
            return getIcons().mDiscContentDesc;
        }
    }

    public void notifyListenersIfNecessary() {
        if (isDirty()) {
            saveLastState();
            notifyListeners();
        }
    }

    /**
     * Returns the resource if resId is not 0, and an empty string otherwise.
     */
    @NonNull CharSequence getTextIfExists(int resId) {
        return resId != 0 ? mContext.getText(resId) : "";
    }

    protected I getIcons() {
        return (I) mCurrentState.iconGroup;
    }

    /**
     * Saves the last state of any changes, so we can log the current
     * and last value of any state data.
     */
    protected void recordLastState() {
        mHistory[mHistoryIndex++ & (HISTORY_SIZE - 1)].copyFrom(mLastState);
    }

    public void dump(PrintWriter pw) {
        pw.println("  - " + mTag + " -----");
        pw.println("  Current State: " + mCurrentState);
        if (RECORD_HISTORY) {
            // Count up the states that actually contain time stamps, and only display those.
            int size = 0;
            for (int i = 0; i < HISTORY_SIZE; i++) {
                if (mHistory[i].time != 0) size++;
            }
            // Print out the previous states in ordered number.
            for (int i = mHistoryIndex + HISTORY_SIZE - 1;
                    i >= mHistoryIndex + HISTORY_SIZE - size; i--) {
                pw.println("  Previous State(" + (mHistoryIndex + HISTORY_SIZE - i) + "): "
                        + mHistory[i & (HISTORY_SIZE - 1)]);
            }
        }
    }

    public final void notifyListeners() {
        notifyListeners(mCallbackHandler);
    }

    /**
     * Trigger callbacks based on current state.  The callbacks should be completely
     * based on current state, and only need to be called in the scenario where
     * mCurrentState != mLastState.
     */
    public abstract void notifyListeners(SignalCallback callback);

    /**
     * Generate a blank T.
     */
    protected abstract T cleanState();

    /*
     * Holds icons for a given state. Arrays are generally indexed as inet
     * state (full connectivity or not) first, and second dimension as
     * signal strength.
     */
    static class IconGroup {
        final int[][] mSbIcons;
        final int[][] mQsIcons;
        final int[] mContentDesc;
        final int mSbNullState;
        final int mQsNullState;
        final int mSbDiscState;
        final int mQsDiscState;
        final int mDiscContentDesc;
        // For logging.
        final String mName;

        public IconGroup(String name, int[][] sbIcons, int[][] qsIcons, int[] contentDesc,
                int sbNullState, int qsNullState, int sbDiscState, int qsDiscState,
                int discContentDesc) {
            mName = name;
            mSbIcons = sbIcons;
            mQsIcons = qsIcons;
            mContentDesc = contentDesc;
            mSbNullState = sbNullState;
            mQsNullState = qsNullState;
            mSbDiscState = sbDiscState;
            mQsDiscState = qsDiscState;
            mDiscContentDesc = discContentDesc;
        }

        @Override
        public String toString() {
            return "IconGroup(" + mName + ")";
        }
    }

    static class State {
        // No locale as it's only used for logging purposes
        private static SimpleDateFormat sSDF = new SimpleDateFormat("MM-dd HH:mm:ss.SSS");
        boolean connected;
        boolean enabled;
        boolean activityIn;
        boolean activityOut;
        int level;
        IconGroup iconGroup;
        int inetCondition;
        int rssi; // Only for logging.

        // Not used for comparison, just used for logging.
        long time;

        public void copyFrom(State state) {
            connected = state.connected;
            enabled = state.enabled;
            level = state.level;
            iconGroup = state.iconGroup;
            inetCondition = state.inetCondition;
            activityIn = state.activityIn;
            activityOut = state.activityOut;
            rssi = state.rssi;
            time = state.time;
        }

        @Override
        public String toString() {
            if (time != 0) {
                StringBuilder builder = new StringBuilder();
                toString(builder);
                return builder.toString();
            } else {
                return "Empty " + getClass().getSimpleName();
            }
        }

        protected void toString(StringBuilder builder) {
            builder.append("connected=").append(connected).append(',')
                    .append("enabled=").append(enabled).append(',')
                    .append("level=").append(level).append(',')
                    .append("inetCondition=").append(inetCondition).append(',')
                    .append("iconGroup=").append(iconGroup).append(',')
                    .append("activityIn=").append(activityIn).append(',')
                    .append("activityOut=").append(activityOut).append(',')
                    .append("rssi=").append(rssi).append(',')
                    .append("lastModified=").append(sSDF.format(time));
        }

        @Override
        public boolean equals(Object o) {
            if (!o.getClass().equals(getClass())) {
                return false;
            }
            State other = (State) o;
            return other.connected == connected
                    && other.enabled == enabled
                    && other.level == level
                    && other.inetCondition == inetCondition
                    && other.iconGroup == iconGroup
                    && other.activityIn == activityIn
                    && other.activityOut == activityOut
                    && other.rssi == rssi;
        }
    }
}
