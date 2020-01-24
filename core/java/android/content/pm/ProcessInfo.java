/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.content.pm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.ArraySet;

/**
 * Information about a process an app may run.  This corresponds to information collected from the
 * AndroidManifest.xml's &lt;permission-group&gt; tags.
 * @hide
 */
public class ProcessInfo implements Parcelable {
    /**
     * The name of the process, fully-qualified based on the app's package name.
     */
    public String name;

    /**
     * If non-null, these are permissions that are not allowed in this process.
     */
    @Nullable
    public ArraySet<String> deniedPermissions;

    public ProcessInfo(String name, ArraySet<String> deniedPermissions) {
        this.name = name;
        this.deniedPermissions = deniedPermissions;
    }

    @Deprecated
    public ProcessInfo(@NonNull ProcessInfo orig) {
        this.name = orig.name;
        this.deniedPermissions = orig.deniedPermissions;
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int parcelableFlags) {
        dest.writeString(this.name);
        final int numDenied = this.deniedPermissions != null
                ? this.deniedPermissions.size() : 0;
        dest.writeInt(numDenied);
        for (int i = 0; i < numDenied; i++) {
            dest.writeString(this.deniedPermissions.valueAt(i));
        }
    }

    public static final @NonNull Creator<ProcessInfo> CREATOR =
            new Creator<ProcessInfo>() {
                public ProcessInfo createFromParcel(Parcel source) {
                    return new ProcessInfo(source);
                }
                public ProcessInfo[] newArray(int size) {
                    return new ProcessInfo[size];
                }
            };

    private ProcessInfo(Parcel source) {
        this.name = source.readString();
        final int numDenied = source.readInt();
        if (numDenied > 0) {
            this.deniedPermissions = new ArraySet<>(numDenied);
            for (int i = numDenied - 1; i >= 0; i--) {
                this.deniedPermissions.add(TextUtils.safeIntern(source.readString()));
            }
        }
    }
}
