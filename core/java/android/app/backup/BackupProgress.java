/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.app.backup;

import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Information about current progress of full data backup
 * Used in {@link BackupObserver#onUpdate(String, BackupProgress)}
 *
 * @hide
 */
@SystemApi
public class BackupProgress implements Parcelable {

    /**
     * Expected size of data in full backup.
     */
    public final long bytesExpected;
    /**
     * Amount of backup data that is already saved in backup.
     */
    public final long bytesTransferred;

    public BackupProgress(long _bytesExpected, long _bytesTransferred) {
        bytesExpected = _bytesExpected;
        bytesTransferred = _bytesTransferred;
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeLong(bytesExpected);
        out.writeLong(bytesTransferred);
    }

    public static final @android.annotation.NonNull Creator<BackupProgress> CREATOR = new Creator<BackupProgress>() {
        public BackupProgress createFromParcel(Parcel in) {
            return new BackupProgress(in);
        }

        public BackupProgress[] newArray(int size) {
            return new BackupProgress[size];
        }
    };

    private BackupProgress(Parcel in) {
        bytesExpected = in.readLong();
        bytesTransferred = in.readLong();
    }
}
