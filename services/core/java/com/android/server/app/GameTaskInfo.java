/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.app;

import android.content.ComponentName;

import java.util.Objects;

final class GameTaskInfo {
    final int mTaskId;
    final boolean mIsGameTask;
    final ComponentName mComponentName;

    GameTaskInfo(int taskId, boolean isGameTask, ComponentName componentName) {
        mTaskId = taskId;
        mIsGameTask = isGameTask;
        mComponentName = componentName;
    }

    @Override
    public String toString() {
        return "GameTaskInfo{"
                + "mTaskId="
                + mTaskId
                + ", mIsGameTask="
                + mIsGameTask
                + ", mComponentName="
                + mComponentName
                + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof GameTaskInfo)) {
            return false;
        }

        GameTaskInfo that = (GameTaskInfo) o;
        return mTaskId == that.mTaskId
                && mIsGameTask == that.mIsGameTask
                && mComponentName.equals(that.mComponentName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mTaskId, mIsGameTask, mComponentName);
    }
}
