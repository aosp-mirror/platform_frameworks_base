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
import android.graphics.Rect;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Data object for options to create TaskFragment with.
 * @hide
 */
public final class TaskFragmentCreationParams implements Parcelable {

    /** The organizer that will organize this TaskFragment. */
    @NonNull
    private final ITaskFragmentOrganizer mOrganizer;

    /**
     * Unique token assigned from the client organizer to identify the {@link TaskFragmentInfo} when
     * a new TaskFragment is created with this option.
     */
    @NonNull
    private final IBinder mFragmentToken;

    /**
     * Activity token used to identify the leaf Task to create the TaskFragment in. It has to belong
     * to the same app as the root Activity of the target Task.
     */
    @NonNull
    private final IBinder mOwnerToken;

    /** The initial bounds of the TaskFragment. Fill parent if empty. */
    @NonNull
    private final Rect mInitialBounds = new Rect();

    private TaskFragmentCreationParams(
            @NonNull ITaskFragmentOrganizer organizer, @NonNull IBinder fragmentToken,
            @NonNull IBinder ownerToken, @NonNull Rect initialBounds) {
        mOrganizer = organizer;
        mFragmentToken = fragmentToken;
        mOwnerToken = ownerToken;
        mInitialBounds.set(initialBounds);
    }

    public ITaskFragmentOrganizer getOrganizer() {
        return mOrganizer;
    }

    public IBinder getFragmentToken() {
        return mFragmentToken;
    }

    public IBinder getOwnerToken() {
        return mOwnerToken;
    }

    public Rect getInitialBounds() {
        return mInitialBounds;
    }

    private TaskFragmentCreationParams(Parcel in) {
        mOrganizer = ITaskFragmentOrganizer.Stub.asInterface(in.readStrongBinder());
        mFragmentToken = in.readStrongBinder();
        mOwnerToken = in.readStrongBinder();
        mInitialBounds.readFromParcel(in);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeStrongInterface(mOrganizer);
        dest.writeStrongBinder(mFragmentToken);
        dest.writeStrongBinder(mOwnerToken);
        mInitialBounds.writeToParcel(dest, flags);
    }

    @NonNull
    public static final Creator<TaskFragmentCreationParams> CREATOR =
            new Creator<TaskFragmentCreationParams>() {
                @Override
                public TaskFragmentCreationParams createFromParcel(Parcel in) {
                    return new TaskFragmentCreationParams(in);
                }

                @Override
                public TaskFragmentCreationParams[] newArray(int size) {
                    return new TaskFragmentCreationParams[size];
                }
            };

    @Override
    public String toString() {
        return "TaskFragmentCreationParams{"
                + " organizer=" + mOrganizer
                + " fragmentToken=" + mFragmentToken
                + " ownerToken=" + mOwnerToken
                + " initialBounds=" + mInitialBounds
                + "}";
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
