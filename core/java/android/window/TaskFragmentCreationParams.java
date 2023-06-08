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

import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.app.WindowConfiguration.WindowingMode;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Data object for options to create TaskFragment with.
 * @hide
 */
@TestApi
public final class TaskFragmentCreationParams implements Parcelable {

    /** The organizer that will organize this TaskFragment. */
    @NonNull
    private final TaskFragmentOrganizerToken mOrganizer;

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

    /** The initial bounds of the TaskFragment. Fills parent if empty. */
    @NonNull
    private final Rect mInitialBounds = new Rect();

    /** The initial windowing mode of the TaskFragment. Inherits from parent if not set. */
    @WindowingMode
    private final int mWindowingMode;

    /**
     * The fragment token of the paired primary TaskFragment.
     * When it is set, the new TaskFragment will be positioned right above the paired TaskFragment.
     * Otherwise, the new TaskFragment will be positioned on the top of the Task by default.
     *
     * This is different from {@link WindowContainerTransaction#setAdjacentTaskFragments} as we may
     * set this when the pair of TaskFragments are stacked, while adjacent is only set on the pair
     * of TaskFragments that are in split.
     *
     * This is needed in case we need to launch a placeholder Activity to split below a transparent
     * always-expand Activity.
     *
     * This should not be used with {@link #mPairedActivityToken}.
     */
    @Nullable
    private final IBinder mPairedPrimaryFragmentToken;

    /**
     * The Activity token to place the new TaskFragment on top of.
     * When it is set, the new TaskFragment will be positioned right above the target Activity.
     * Otherwise, the new TaskFragment will be positioned on the top of the Task by default.
     *
     * This is needed in case we need to place an Activity into TaskFragment to launch placeholder
     * below a transparent always-expand Activity, or when there is another Intent being started in
     * a TaskFragment above.
     *
     * This should not be used with {@link #mPairedPrimaryFragmentToken}.
     */
    @Nullable
    private final IBinder mPairedActivityToken;

    private TaskFragmentCreationParams(
            @NonNull TaskFragmentOrganizerToken organizer, @NonNull IBinder fragmentToken,
            @NonNull IBinder ownerToken, @NonNull Rect initialBounds,
            @WindowingMode int windowingMode, @Nullable IBinder pairedPrimaryFragmentToken,
            @Nullable IBinder pairedActivityToken) {
        if (pairedPrimaryFragmentToken != null && pairedActivityToken != null) {
            throw new IllegalArgumentException("pairedPrimaryFragmentToken and"
                    + " pairedActivityToken should not be set at the same time.");
        }
        mOrganizer = organizer;
        mFragmentToken = fragmentToken;
        mOwnerToken = ownerToken;
        mInitialBounds.set(initialBounds);
        mWindowingMode = windowingMode;
        mPairedPrimaryFragmentToken = pairedPrimaryFragmentToken;
        mPairedActivityToken = pairedActivityToken;
    }

    @NonNull
    public TaskFragmentOrganizerToken getOrganizer() {
        return mOrganizer;
    }

    @NonNull
    public IBinder getFragmentToken() {
        return mFragmentToken;
    }

    @NonNull
    public IBinder getOwnerToken() {
        return mOwnerToken;
    }

    @NonNull
    public Rect getInitialBounds() {
        return mInitialBounds;
    }

    @WindowingMode
    public int getWindowingMode() {
        return mWindowingMode;
    }

    /**
     * TODO(b/232476698): remove the hide with adding CTS for this in next release.
     * @hide
     */
    @Nullable
    public IBinder getPairedPrimaryFragmentToken() {
        return mPairedPrimaryFragmentToken;
    }

    /**
     * TODO(b/232476698): remove the hide with adding CTS for this in next release.
     * @hide
     */
    @Nullable
    public IBinder getPairedActivityToken() {
        return mPairedActivityToken;
    }

    private TaskFragmentCreationParams(Parcel in) {
        mOrganizer = TaskFragmentOrganizerToken.CREATOR.createFromParcel(in);
        mFragmentToken = in.readStrongBinder();
        mOwnerToken = in.readStrongBinder();
        mInitialBounds.readFromParcel(in);
        mWindowingMode = in.readInt();
        mPairedPrimaryFragmentToken = in.readStrongBinder();
        mPairedActivityToken = in.readStrongBinder();
    }

    /** @hide */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        mOrganizer.writeToParcel(dest, flags);
        dest.writeStrongBinder(mFragmentToken);
        dest.writeStrongBinder(mOwnerToken);
        mInitialBounds.writeToParcel(dest, flags);
        dest.writeInt(mWindowingMode);
        dest.writeStrongBinder(mPairedPrimaryFragmentToken);
        dest.writeStrongBinder(mPairedActivityToken);
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
                + " windowingMode=" + mWindowingMode
                + " pairedFragmentToken=" + mPairedPrimaryFragmentToken
                + " pairedActivityToken=" + mPairedActivityToken
                + "}";
    }

    /** @hide */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Builder to construct the options to create TaskFragment with. */
    public static final class Builder {

        @NonNull
        private final TaskFragmentOrganizerToken mOrganizer;

        @NonNull
        private final IBinder mFragmentToken;

        @NonNull
        private final IBinder mOwnerToken;

        @NonNull
        private final Rect mInitialBounds = new Rect();

        @WindowingMode
        private int mWindowingMode = WINDOWING_MODE_UNDEFINED;

        @Nullable
        private IBinder mPairedPrimaryFragmentToken;

        @Nullable
        private IBinder mPairedActivityToken;

        public Builder(@NonNull TaskFragmentOrganizerToken organizer,
                @NonNull IBinder fragmentToken, @NonNull IBinder ownerToken) {
            mOrganizer = organizer;
            mFragmentToken = fragmentToken;
            mOwnerToken = ownerToken;
        }

        /** Sets the initial bounds for the TaskFragment. */
        @NonNull
        public Builder setInitialBounds(@NonNull Rect bounds) {
            mInitialBounds.set(bounds);
            return this;
        }

        /** Sets the initial windowing mode for the TaskFragment. */
        @NonNull
        public Builder setWindowingMode(@WindowingMode int windowingMode) {
            mWindowingMode = windowingMode;
            return this;
        }

        /**
         * Sets the fragment token of the paired primary TaskFragment.
         * When it is set, the new TaskFragment will be positioned right above the paired
         * TaskFragment. Otherwise, the new TaskFragment will be positioned on the top of the Task
         * by default.
         *
         * This is needed in case we need to launch a placeholder Activity to split below a
         * transparent always-expand Activity.
         *
         * This should not be used with {@link #setPairedActivityToken}.
         *
         * TODO(b/232476698): remove the hide with adding CTS for this in next release.
         * @hide
         */
        @NonNull
        public Builder setPairedPrimaryFragmentToken(@Nullable IBinder fragmentToken) {
            mPairedPrimaryFragmentToken = fragmentToken;
            return this;
        }

        /**
         * Sets the Activity token to place the new TaskFragment on top of.
         * When it is set, the new TaskFragment will be positioned right above the target Activity.
         * Otherwise, the new TaskFragment will be positioned on the top of the Task by default.
         *
         * This is needed in case we need to place an Activity into TaskFragment to launch
         * placeholder below a transparent always-expand Activity, or when there is another Intent
         * being started in a TaskFragment above.
         *
         * This should not be used with {@link #setPairedPrimaryFragmentToken}.
         *
         * TODO(b/232476698): remove the hide with adding CTS for this in next release.
         * @hide
         */
        @NonNull
        public Builder setPairedActivityToken(@Nullable IBinder activityToken) {
            mPairedActivityToken = activityToken;
            return this;
        }

        /** Constructs the options to create TaskFragment with. */
        @NonNull
        public TaskFragmentCreationParams build() {
            return new TaskFragmentCreationParams(mOrganizer, mFragmentToken, mOwnerToken,
                    mInitialBounds, mWindowingMode, mPairedPrimaryFragmentToken,
                    mPairedActivityToken);
        }
    }
}
