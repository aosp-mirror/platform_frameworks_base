/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.app;

import android.content.Intent;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Describes a Service.onStartCommand() request from the system.
 * @hide
 */
public class ServiceStartArgs implements Parcelable {
    final public boolean taskRemoved;
    final public int startId;
    final public int flags;
    final public Intent args;

    public ServiceStartArgs(boolean _taskRemoved, int _startId, int _flags, Intent _args) {
        taskRemoved = _taskRemoved;
        startId = _startId;
        flags = _flags;
        args = _args;
    }

    public String toString() {
        return "ServiceStartArgs{taskRemoved=" + taskRemoved + ", startId=" + startId
                + ", flags=0x" + Integer.toHexString(flags) + ", args=" + args + "}";
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(taskRemoved ? 1 : 0);
        out.writeInt(startId);
        out.writeInt(flags);
        if (args != null) {
            out.writeInt(1);
            args.writeToParcel(out, 0);
        } else {
            out.writeInt(0);
        }
    }

    public static final @android.annotation.NonNull Parcelable.Creator<ServiceStartArgs> CREATOR
            = new Parcelable.Creator<ServiceStartArgs>() {
        public ServiceStartArgs createFromParcel(Parcel in) {
            return new ServiceStartArgs(in);
        }

        public ServiceStartArgs[] newArray(int size) {
            return new ServiceStartArgs[size];
        }
    };

    public ServiceStartArgs(Parcel in) {
        taskRemoved = in.readInt() != 0;
        startId = in.readInt();
        flags = in.readInt();
        if (in.readInt() != 0) {
            args = Intent.CREATOR.createFromParcel(in);
        } else {
            args = null;
        }
    }
}
