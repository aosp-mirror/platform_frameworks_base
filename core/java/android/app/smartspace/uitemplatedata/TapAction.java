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

package android.app.smartspace.uitemplatedata;


import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.app.PendingIntent;
import android.app.smartspace.SmartspaceUtils;
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.text.TextUtils;

import java.util.Objects;

/**
 * A {@link TapAction} represents an action which can be taken by a user by tapping on
 * either the title, the subtitle or on the icon. Supported instances are Intents and
 * PendingIntents. These actions can be called from another process or within the client process.
 *
 * Clients can also receive ShorcutInfos in the extras bundle.
 *
 * @hide
 */
@SystemApi
public final class TapAction implements Parcelable {

    /** A unique Id of this {@link TapAction}. */
    @Nullable
    private final CharSequence mId;

    @Nullable
    private final Intent mIntent;

    @Nullable
    private final PendingIntent mPendingIntent;

    @Nullable
    private final UserHandle mUserHandle;

    @Nullable
    private final Bundle mExtras;

    /**
     * Whether the tap action's result should be shown on the lockscreen (e.g. turn off the
     * flashlight can be done on LS bypassing the keyguard). Default value is false.
     */
    private final boolean mShouldShowOnLockscreen;

    TapAction(@NonNull Parcel in) {
        mId = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
        mIntent = in.readTypedObject(Intent.CREATOR);
        mPendingIntent = in.readTypedObject(PendingIntent.CREATOR);
        mUserHandle = in.readTypedObject(UserHandle.CREATOR);
        mExtras = in.readBundle();
        mShouldShowOnLockscreen = in.readBoolean();
    }

    private TapAction(@Nullable CharSequence id, @Nullable Intent intent,
            @Nullable PendingIntent pendingIntent, @Nullable UserHandle userHandle,
            @Nullable Bundle extras, boolean shouldShowOnLockscreen) {
        mId = id;
        mIntent = intent;
        mPendingIntent = pendingIntent;
        mUserHandle = userHandle;
        mExtras = extras;
        mShouldShowOnLockscreen = shouldShowOnLockscreen;
    }

    /** Returns the unique id of the tap action. */
    @Nullable
    public CharSequence getId() {
        return mId;
    }

    /** Returns the intent of the tap action. */
    @SuppressLint("IntentBuilderName")
    @Nullable
    public Intent getIntent() {
        return mIntent;
    }

    /** Returns the pending intent of the tap action. */
    @Nullable
    public PendingIntent getPendingIntent() {
        return mPendingIntent;
    }

    /** Returns the user handle of the tap action. */
    @Nullable
    public UserHandle getUserHandle() {
        return mUserHandle;
    }

    /** Returns the extras bundle of the tap action. */
    @Nullable
    @SuppressLint("NullableCollection")
    public Bundle getExtras() {
        return mExtras;
    }

    /**
     * Whether the tap action's result should be shown on the lockscreen. If true, the tap action's
     * handling should bypass the keyguard. Default value is false.
     */
    public boolean shouldShowOnLockscreen() {
        return mShouldShowOnLockscreen;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        TextUtils.writeToParcel(mId, out, flags);
        out.writeTypedObject(mIntent, flags);
        out.writeTypedObject(mPendingIntent, flags);
        out.writeTypedObject(mUserHandle, flags);
        out.writeBundle(mExtras);
        out.writeBoolean(mShouldShowOnLockscreen);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Creator<TapAction> CREATOR = new Creator<TapAction>() {
        @Override
        public TapAction createFromParcel(Parcel in) {
            return new TapAction(in);
        }

        @Override
        public TapAction[] newArray(int size) {
            return new TapAction[size];
        }
    };

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TapAction)) return false;
        TapAction that = (TapAction) o;
        return SmartspaceUtils.isEqual(mId, that.mId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mId);
    }

    @Override
    public String toString() {
        return "SmartspaceTapAction{"
                + "mId=" + mId
                + "mIntent=" + mIntent
                + ", mPendingIntent=" + mPendingIntent
                + ", mUserHandle=" + mUserHandle
                + ", mExtras=" + mExtras
                + ", mShouldShowOnLockscreen=" + mShouldShowOnLockscreen
                + '}';
    }

    /**
     * A builder for {@link TapAction} object.
     *
     * @hide
     */
    @SystemApi
    public static final class Builder {

        private CharSequence mId;
        private Intent mIntent;
        private PendingIntent mPendingIntent;
        private UserHandle mUserHandle;
        private Bundle mExtras;
        private boolean mShouldShowOnLockScreen;

        /**
         * A builder for {@link TapAction}. By default sets should_show_on_lockscreen to false.
         *
         * @param id A unique Id of this {@link TapAction}.
         */
        public Builder(@NonNull CharSequence id) {
            mId = Objects.requireNonNull(id);
            mShouldShowOnLockScreen = false;
        }

        /**
         * Sets the action intent.
         */
        @NonNull
        public Builder setIntent(@NonNull Intent intent) {
            mIntent = intent;
            return this;
        }

        /**
         * Sets the pending intent.
         */
        @NonNull
        public Builder setPendingIntent(@NonNull PendingIntent pendingIntent) {
            mPendingIntent = pendingIntent;
            return this;
        }

        /**
         * Sets the user handle.
         */
        @NonNull
        @SuppressLint("UserHandleName")
        public Builder setUserHandle(@Nullable UserHandle userHandle) {
            mUserHandle = userHandle;
            return this;
        }

        /**
         * Sets the extras.
         */
        @NonNull
        public Builder setExtras(@NonNull Bundle extras) {
            mExtras = extras;
            return this;
        }

        /**
         * Sets whether the tap action's result should be shown on the lockscreen, to bypass the
         * keyguard when the tap action is triggered.
         */
        @NonNull
        public Builder setShouldShowOnLockscreen(@NonNull boolean shouldShowOnLockScreen) {
            mShouldShowOnLockScreen = shouldShowOnLockScreen;
            return this;
        }

        /**
         * Builds a new SmartspaceTapAction instance.
         *
         * @throws IllegalStateException if the tap action is empty.
         */
        @NonNull
        public TapAction build() {
            if (mIntent == null && mPendingIntent == null && mExtras == null) {
                throw new IllegalStateException("Please assign at least 1 valid tap field");
            }
            return new TapAction(mId, mIntent, mPendingIntent, mUserHandle, mExtras,
                    mShouldShowOnLockScreen);
        }
    }
}
