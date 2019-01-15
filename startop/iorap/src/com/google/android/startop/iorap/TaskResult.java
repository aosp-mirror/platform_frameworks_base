/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.google.android.startop.iorap;

import android.os.Parcelable;
import android.os.Parcel;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Result data accompanying a request for {@link com.google.android.startop.iorap.ITaskListener}
 * callbacks.<br /><br />
 *
 * Following {@link com.google.android.startop.iorap.IIorap} method invocation,
 * iorapd will issue in-order callbacks for that corresponding {@link RequestId}.<br /><br />
 *
 * State transitions are as follows: <br /><br />
 *
 * <pre>
 *          ┌─────────────────────────────┐
 *          │                             ▼
 *        ┌───────┐     ┌─────────┐     ╔═══════════╗
 *    ──▶ │ BEGAN │ ──▶ │ ONGOING │ ──▶ ║ COMPLETED ║
 *        └───────┘     └─────────┘     ╚═══════════╝
 *          │             │
 *          │             │
 *          ▼             │
 *        ╔═══════╗       │
 *    ──▶ ║ ERROR ║ ◀─────┘
 *        ╚═══════╝
 *
 * </pre> <!-- system/iorap/docs/binder/TaskResult.dot -->
 *
 * @hide
 */
public class TaskResult implements Parcelable {

    public static final int STATE_BEGAN = 0;
    public static final int STATE_ONGOING = 1;
    public static final int STATE_COMPLETED = 2;
    public static final int STATE_ERROR = 3;
    private static final int STATE_MAX = STATE_ERROR;

    /** @hide */
    @IntDef(flag = true, prefix = { "STATE_" }, value = {
            STATE_BEGAN,
            STATE_ONGOING,
            STATE_COMPLETED,
            STATE_ERROR,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface State {}

    @State public final int state;

    @Override
    public String toString() {
        return String.format("{state: %d}", state);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        } else if (other instanceof TaskResult) {
            return equals((TaskResult) other);
        }
        return false;
    }

    private boolean equals(TaskResult other) {
        return state == other.state;
    }

    public TaskResult(@State int state) {
        this.state = state;

        checkConstructorArguments();
    }

    private void checkConstructorArguments() {
        CheckHelpers.checkStateInRange(state, STATE_MAX);
    }

    //<editor-fold desc="Binder boilerplate">
    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(state);
    }

    private TaskResult(Parcel in) {
        state = in.readInt();

        checkConstructorArguments();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<TaskResult> CREATOR
            = new Parcelable.Creator<TaskResult>() {
        public TaskResult createFromParcel(Parcel in) {
            return new TaskResult(in);
        }

        public TaskResult[] newArray(int size) {
            return new TaskResult[size];
        }
    };
    //</editor-fold>
}
