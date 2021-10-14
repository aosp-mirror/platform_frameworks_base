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

package android.app.smartspace;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;

import java.util.Objects;

/**
 * The id for an Smartspace session. See {@link SmartspaceSession}.
 *
 * @hide
 */
@SystemApi
public final class SmartspaceSessionId implements Parcelable {

    @NonNull
    private final String mId;

    @NonNull
    private final UserHandle mUserHandle;

    /**
     * Creates a new id for a Smartspace session.
     *
     * @hide
     */
    public SmartspaceSessionId(@NonNull final String id, @NonNull final UserHandle userHandle) {
        mId = id;
        mUserHandle = userHandle;
    }

    private SmartspaceSessionId(Parcel p) {
        mId = p.readString();
        mUserHandle = p.readTypedObject(UserHandle.CREATOR);
    }

    /**
     * Returns a {@link String} Id of this sessionId.
     */
    @Nullable
    public String getId() {
        return mId;
    }

    /**
     * Returns the userId associated with this sessionId.
     */
    @NonNull
    public UserHandle getUserHandle() {
        return mUserHandle;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (!getClass().equals(o != null ? o.getClass() : null)) return false;

        SmartspaceSessionId other = (SmartspaceSessionId) o;
        return mId.equals(other.mId) && mUserHandle == other.mUserHandle;
    }

    @Override
    public String toString() {
        return "SmartspaceSessionId{"
                + "mId='" + mId + '\''
                + ", mUserId=" + mUserHandle.getIdentifier()
                + '}';
    }

    @Override
    public int hashCode() {
        return Objects.hash(mId, mUserHandle);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mId);
        dest.writeTypedObject(this.mUserHandle, flags);
    }

    public static final @NonNull Creator<SmartspaceSessionId> CREATOR =
            new Creator<SmartspaceSessionId>() {
                public SmartspaceSessionId createFromParcel(Parcel parcel) {
                    return new SmartspaceSessionId(parcel);
                }

                public SmartspaceSessionId[] newArray(int size) {
                    return new SmartspaceSessionId[size];
                }
            };
}
