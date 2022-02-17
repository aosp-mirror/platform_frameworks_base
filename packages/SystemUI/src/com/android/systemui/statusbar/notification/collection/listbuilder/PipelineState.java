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

package com.android.systemui.statusbar.notification.collection.listbuilder;

import android.annotation.IntDef;

import androidx.annotation.NonNull;

import com.android.systemui.statusbar.notification.collection.ShadeListBuilder;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Used by {@link ShadeListBuilder} to track its internal state machine.
 */
public class PipelineState {

    private @StateName int mState = STATE_IDLE;

    /** Returns true if the current state matches <code>state</code> */
    public boolean is(@StateName int state) {
        return state == mState;
    }

    /** Get the current state's integer representation */
    public @StateName int getState() {
        return mState;
    }

    public void setState(@StateName int state) {
        mState = state;
    }

    /**
     * Increments the state from <code>(to - 1)</code> to <code>to</code>. If the current state
     * isn't <code>(to - 1)</code>, throws an exception.
     */
    public void incrementTo(@StateName int to) {
        if (mState != to - 1) {
            throw new IllegalStateException(
                    "Cannot increment from state " + mState + " to state " + to);
        }
        mState = to;
    }

    /**
     * Throws an exception if the current state is not <code>state</code>.
     */
    public void requireState(@StateName int state) {
        if (state != mState) {
            throw new IllegalStateException(
                    "Required state is <" + state + " but actual state is " + mState);
        }
    }

    /**
     * Throws an exception if the current state is >= <code>state</code>.
     */
    public void requireIsBefore(@StateName int state) {
        if (mState >= state) {
            throw new IllegalStateException(
                    "Required state is <" + state + " but actual state is " + mState);
        }
    }

    /** Get the current state's string representation */
    @NonNull
    public String getStateName() {
        return getStateName(mState);
    }

    /** Get the given state's string representation */
    @NonNull
    public static String getStateName(@StateName int state) {
        switch (state) {
            case STATE_IDLE:
                return "STATE_IDLE";
            case STATE_BUILD_STARTED:
                return "STATE_BUILD_STARTED";
            case STATE_RESETTING:
                return "STATE_RESETTING";
            case STATE_PRE_GROUP_FILTERING:
                return "STATE_PRE_GROUP_FILTERING";
            case STATE_GROUPING:
                return "STATE_GROUPING";
            case STATE_TRANSFORMING:
                return "STATE_TRANSFORMING";
            case STATE_GROUP_STABILIZING:
                return "STATE_GROUP_STABILIZING";
            case STATE_SORTING:
                return "STATE_SORTING";
            case STATE_FINALIZE_FILTERING:
                return "STATE_FINALIZE_FILTERING";
            case STATE_FINALIZING:
                return "STATE_FINALIZING";
            default:
                return "STATE:" + state;
        }
    }

    public static final int STATE_IDLE = 0;
    public static final int STATE_BUILD_STARTED = 1;
    public static final int STATE_RESETTING = 2;
    public static final int STATE_PRE_GROUP_FILTERING = 3;
    public static final int STATE_GROUPING = 4;
    public static final int STATE_TRANSFORMING = 5;
    public static final int STATE_GROUP_STABILIZING = 6;
    public static final int STATE_SORTING = 7;
    public static final int STATE_FINALIZE_FILTERING = 8;
    public static final int STATE_FINALIZING = 9;

    @IntDef(prefix = { "STATE_" }, value = {
            STATE_IDLE,
            STATE_BUILD_STARTED,
            STATE_RESETTING,
            STATE_PRE_GROUP_FILTERING,
            STATE_GROUPING,
            STATE_TRANSFORMING,
            STATE_GROUP_STABILIZING,
            STATE_SORTING,
            STATE_FINALIZE_FILTERING,
            STATE_FINALIZING,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StateName {}
}
