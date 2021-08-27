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

package com.android.systemui.model;

import static android.view.Display.DEFAULT_DISPLAY;

import android.annotation.NonNull;
import android.util.Log;

import com.android.systemui.Dumpable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.shared.system.QuickStepContract;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Contains sysUi state flags and notifies registered
 * listeners whenever changes happen.
 */
@SysUISingleton
public class SysUiState implements Dumpable {

    private static final String TAG = SysUiState.class.getSimpleName();
    public static final boolean DEBUG = false;

    private @QuickStepContract.SystemUiStateFlags int mFlags;
    private final List<SysUiStateCallback> mCallbacks = new ArrayList<>();
    private int mFlagsToSet = 0;
    private int mFlagsToClear = 0;

    /**
     * Add listener to be notified of changes made to SysUI state.
     * The callback will also be called as part of this function.
     */
    public void addCallback(@NonNull SysUiStateCallback callback) {
        mCallbacks.add(callback);
        callback.onSystemUiStateChanged(mFlags);
    }

    /** Callback will no longer receive events on state change */
    public void removeCallback(@NonNull SysUiStateCallback callback) {
        mCallbacks.remove(callback);
    }

    /** Returns the current sysui state flags. */
    public int getFlags() {
        return mFlags;
    }

    /** Methods to this call can be chained together before calling {@link #commitUpdate(int)}. */
    public SysUiState setFlag(int flag, boolean enabled) {
        if (enabled) {
            mFlagsToSet |= flag;
        } else {
            mFlagsToClear |= flag;
        }
        return this;
    }

    /** Call to save all the flags updated from {@link #setFlag(int, boolean)}. */
    public void commitUpdate(int displayId) {
        updateFlags(displayId);
        mFlagsToSet = 0;
        mFlagsToClear = 0;
    }

    private void updateFlags(int displayId) {
        if (displayId != DEFAULT_DISPLAY) {
            // Ignore non-default displays for now
            Log.w(TAG, "Ignoring flag update for display: " + displayId, new Throwable());
            return;
        }

        int newState = mFlags;
        newState |= mFlagsToSet;
        newState &= ~mFlagsToClear;
        notifyAndSetSystemUiStateChanged(newState, mFlags);
    }

    /** Notify all those who are registered that the state has changed. */
    private void notifyAndSetSystemUiStateChanged(int newFlags, int oldFlags) {
        if (DEBUG) {
            Log.d(TAG, "SysUiState changed: old=" + oldFlags + " new=" + newFlags);
        }
        if (newFlags != oldFlags) {
            mCallbacks.forEach(callback -> callback.onSystemUiStateChanged(newFlags));
            mFlags = newFlags;
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("SysUiState state:");
        pw.print("  mSysUiStateFlags="); pw.println(mFlags);
        pw.println("    " + QuickStepContract.getSystemUiStateString(mFlags));
        pw.print("    backGestureDisabled=");
        pw.println(QuickStepContract.isBackGestureDisabled(mFlags));
        pw.print("    assistantGestureDisabled=");
        pw.println(QuickStepContract.isAssistantGestureDisabled(mFlags));
    }

    /** Callback to be notified whenever system UI state flags are changed. */
    public interface SysUiStateCallback{
        /** To be called when any SysUiStateFlag gets updated */
        void onSystemUiStateChanged(@QuickStepContract.SystemUiStateFlags int sysUiFlags);
    }
}


