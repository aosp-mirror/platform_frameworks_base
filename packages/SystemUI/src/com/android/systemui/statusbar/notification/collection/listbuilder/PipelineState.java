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

    public static final int STATE_IDLE = 0;
    public static final int STATE_BUILD_STARTED = 1;
    public static final int STATE_RESETTING = 2;
    public static final int STATE_PRE_GROUP_FILTERING = 3;
    public static final int STATE_GROUPING = 4;
    public static final int STATE_TRANSFORMING = 5;
    public static final int STATE_SORTING = 6;
    public static final int STATE_FINALIZE_FILTERING = 7;
    public static final int STATE_FINALIZING = 8;

    @IntDef(prefix = { "STATE_" }, value = {
            STATE_IDLE,
            STATE_BUILD_STARTED,
            STATE_RESETTING,
            STATE_PRE_GROUP_FILTERING,
            STATE_GROUPING,
            STATE_TRANSFORMING,
            STATE_SORTING,
            STATE_FINALIZE_FILTERING,
            STATE_FINALIZING,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StateName {}
}
