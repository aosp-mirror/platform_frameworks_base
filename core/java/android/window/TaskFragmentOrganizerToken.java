/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.window;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Interface to communicate between window manager and {@link TaskFragmentOrganizer}.
 * <p>
 * Window manager will dispatch TaskFragment information updates via this interface.
 * It is necessary because {@link ITaskFragmentOrganizer} aidl interface can not be used as a
 * {@link TestApi}.
 * @hide
 */
@TestApi
public final class TaskFragmentOrganizerToken implements Parcelable {
    private final ITaskFragmentOrganizer mRealToken;

    TaskFragmentOrganizerToken(ITaskFragmentOrganizer realToken) {
        mRealToken = realToken;
    }

    /** @hide */
    public IBinder asBinder() {
        return mRealToken.asBinder();
    }

    /** @hide */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeStrongInterface(mRealToken);
    }

    /** @hide */
    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Creator<TaskFragmentOrganizerToken> CREATOR =
            new Creator<TaskFragmentOrganizerToken>() {
                @Override
                public TaskFragmentOrganizerToken createFromParcel(Parcel in) {
                    final ITaskFragmentOrganizer realToken =
                            ITaskFragmentOrganizer.Stub.asInterface(in.readStrongBinder());
                    // The TaskFragmentOrganizerToken may be null for TaskOrganizer or
                    // DisplayAreaOrganizer.
                    if (realToken == null) {
                        return null;
                    }
                    return new TaskFragmentOrganizerToken(realToken);
                }

                @Override
                public TaskFragmentOrganizerToken[] newArray(int size) {
                    return new TaskFragmentOrganizerToken[size];
                }
            };

    @Override
    public int hashCode() {
        return mRealToken.asBinder().hashCode();
    }

    @Override
    public String toString() {
        return "TaskFragmentOrganizerToken{" + mRealToken + "}";
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (!(obj instanceof TaskFragmentOrganizerToken)) {
            return false;
        }
        return mRealToken.asBinder() == ((TaskFragmentOrganizerToken) obj).asBinder();
    }
}
