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
package android.app;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.LocusId;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

/**
 * Represents a abstract action that can be perform on this app. This are requested from
 * outside the app's UI (eg by SystemUI or assistant).
 */
public final class DirectAction implements Parcelable {

    /**
     * @hide
     */
    public static final String KEY_ACTIONS_LIST = "actions_list";

    private int mTaskId;
    private IBinder mActivityId;

    @NonNull
    private final String mID;
    @Nullable
    private final Bundle mExtras;
    @Nullable
    private final LocusId mLocusId;

    /** @hide */
    public DirectAction(@NonNull String id, @Nullable Bundle extras,
            @Nullable LocusId locusId) {
        mID = Preconditions.checkStringNotEmpty(id);
        mExtras = extras;
        mLocusId = locusId;
    }

    /** @hide */
    public void setSource(int taskId, IBinder activityId) {
        mTaskId = taskId;
        mActivityId = activityId;
    }

    /**
     * @hide
     */
    public DirectAction(@NonNull DirectAction original) {
        mTaskId = original.mTaskId;
        mActivityId = original.mActivityId;
        mID = original.mID;
        mExtras = original.mExtras;
        mLocusId = original.mLocusId;
    }

    private DirectAction(Parcel in) {
        mTaskId = in.readInt();
        mActivityId = in.readStrongBinder();
        mID = in.readString();
        mExtras = in.readBundle();
        final String idString = in.readString();
        mLocusId = (idString != null) ? new LocusId(idString) : null;
    }

    /** @hide */
    public int getTaskId() {
        return mTaskId;
    }

    /** @hide */
    public IBinder getActivityId() {
        return mActivityId;
    }

    /**
     * Returns the ID for this action.
     */
    @NonNull
    public String getId() {
        return mID;
    }

    /**
     * Returns any extras associated with this action.
     */
    @Nullable
    public Bundle getExtras() {
        return mExtras;
    }

    /**
     * Returns the LocusId for the current state for the app
     */
    @Nullable
    public LocusId getLocusId() {
        return mLocusId;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mTaskId);
        dest.writeStrongBinder(mActivityId);
        dest.writeString(mID);
        dest.writeBundle(mExtras);
        dest.writeString(mLocusId.getId());
    }

    /**
     * Builder for construction of DirectAction.
     */
    public static final class Builder {
        private @NonNull String mId;
        private @Nullable Bundle mExtras;
        private @Nullable LocusId mLocusId;

        /**
         * Creates a new instance.
         *
         * @param id The mandatory action id.
         */
        public Builder(@NonNull String id) {
            Preconditions.checkNotNull(id);
            mId = id;
        }

        /**
         * Sets the optional action extras.
         *
         * @param extras The extras.
         * @return This builder.
         */
        public @NonNull Builder setExtras(@Nullable Bundle extras) {
            mExtras = extras;
            return this;
        }

        /**
         * Sets the optional locus id.
         *
         * @param locusId The locus id.
         * @return This builder.
         */
        public @NonNull Builder setLocusId(@Nullable LocusId locusId) {
            mLocusId = locusId;
            return this;
        }

        /**
         * @return A newly constructed instance.
         */
        public @NonNull DirectAction build() {
            return new DirectAction(mId, mExtras, mLocusId);
        }
    }

    public static final @NonNull Parcelable.Creator<DirectAction> CREATOR =
            new Parcelable.Creator<DirectAction>() {
                public DirectAction createFromParcel(Parcel in) {
                    return new DirectAction(in);
                }
                public DirectAction[] newArray(int size) {
                    return new DirectAction[size];
                }
            };
}
