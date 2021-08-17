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

import com.android.settingslib.SignalIcon.IconGroup;
import com.android.settingslib.SignalIcon.State;
import com.android.systemui.statusbar.policy.NetworkController.IconState;
import com.android.systemui.statusbar.policy.NetworkController.SignalCallback;

import java.io.PrintWriter;
import java.util.BitSet;


/**
 * Common base class for handling signal for both wifi and mobile data.
 *
 * @param <T> State of the SysUI controller.
 * @param <I> Icon groups of the SysUI controller for a given State.
 */
public abstract class SignalController<T extends State, I extends IconGroup> {
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
            return getIcons().qsIcons[mCurrentState.inetCondition][mCurrentState.level];
        } else if (mCurrentState.enabled) {
            return getIcons().qsDiscState;
        } else {
            return getIcons().qsNullState;
        }
    }

    /**
     * Gets the signal icon for SB based on current state of connected, enabled, and level.
     */
    public int getCurrentIconId() {
        if (mCurrentState.connected) {
            return getIcons().sbIcons[mCurrentState.inetCondition][mCurrentState.level];
        } else if (mCurrentState.enabled) {
            return getIcons().sbDiscState;
        } else {
            return getIcons().sbNullState;
        }
    }

    /**
     * Gets the content description id for the signal based on current state of connected and
     * level.
     */
    public int getContentDescription() {
        if (mCurrentState.connected) {
            return getIcons().contentDesc[mCurrentState.level];
        } else {
            return getIcons().discContentDesc;
        }
    }

    public void notifyListenersIfNecessary() {
        if (isDirty()) {
            saveLastState();
            notifyListeners();
        }
    }

    protected final void notifyCallStateChange(IconState statusIcon, int subId) {
        mCallbackHandler.setCallIndicator(statusIcon, subId);
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
        mHistory[mHistoryIndex].copyFrom(mLastState);
        mHistoryIndex = (mHistoryIndex + 1) % HISTORY_SIZE;
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
}
