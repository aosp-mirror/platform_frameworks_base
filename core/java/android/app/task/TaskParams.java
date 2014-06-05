/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package android.app.task;

import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;

/**
 * Contains the parameters used to configure/identify your task. You do not create this object
 * yourself, instead it is handed in to your application by the System.
 */
public class TaskParams implements Parcelable {

    private final int taskId;
    private final PersistableBundle extras;
    private final IBinder callback;

    /** @hide */
    public TaskParams(int taskId, PersistableBundle extras, IBinder callback) {
        this.taskId = taskId;
        this.extras = extras;
        this.callback = callback;
    }

    /**
     * @return The unique id of this task, specified at creation time.
     */
    public int getTaskId() {
        return taskId;
    }

    /**
     * @return The extras you passed in when constructing this task with
     * {@link android.app.task.Task.Builder#setExtras(android.os.PersistableBundle)}. This will
     * never be null. If you did not set any extras this will be an empty bundle.
     */
    public PersistableBundle getExtras() {
        return extras;
    }

    /** @hide */
    public ITaskCallback getCallback() {
        return ITaskCallback.Stub.asInterface(callback);
    }

    private TaskParams(Parcel in) {
        taskId = in.readInt();
        extras = in.readPersistableBundle();
        callback = in.readStrongBinder();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(taskId);
        dest.writePersistableBundle(extras);
        dest.writeStrongBinder(callback);
    }

    public static final Creator<TaskParams> CREATOR = new Creator<TaskParams>() {
        @Override
        public TaskParams createFromParcel(Parcel in) {
            return new TaskParams(in);
        }

        @Override
        public TaskParams[] newArray(int size) {
            return new TaskParams[size];
        }
    };
}
