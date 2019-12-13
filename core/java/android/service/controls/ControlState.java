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

package android.service.controls;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.content.res.ColorStateList;
import android.graphics.drawable.Icon;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Current state for a {@link Control}.
 *
 * Collects information to render the current state of a {@link Control} as well as possible action
 * that can be performed on it.
 * <p>
 * Additionally, this object is used to modify elements from the {@link Control} such as icons,
 * colors, names and intents. This information will last until it is again modified by a
 * {@link ControlState}.
 * @hide
 */
public final class ControlState implements Parcelable {

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            STATUS_OK,
            STATUS_NOT_FOUND,
            STATUS_ERROR,
            STATUS_DISABLED,
    })
    public @interface Status {};

    /**
     * The device corresponding to the {@link Control} is responding correctly.
     */
    public static final int STATUS_OK = 0;

    /**
     * The device corresponding to the {@link Control} cannot be found or was removed.
     */
    public static final int STATUS_NOT_FOUND = 1;

    /**
     * The device corresponding to the {@link Control} is in an error state.
     */
    public static final int STATUS_ERROR = 2;

    /**
     * The {@link Control} is currently disabled.
     */
    public static final int STATUS_DISABLED = 3;

    private final @NonNull String mControlId;
    private final @Status int mStatus;
    private final @NonNull ControlTemplate mControlTemplate;
    private final @NonNull CharSequence mStatusText;
    private final @Nullable CharSequence mTitle;
    private final @Nullable PendingIntent mAppIntent;
    private final @Nullable Icon mIcon;
    private final @Nullable ColorStateList mTint;

    /**
     * @param controlId the identifier of the {@link Control} this object refers to.
     * @param status the current status of the {@link Control}.
     * @param controlTemplate the template to be used to render the {@link Control}. This can be
     *                        of a different
     *                        {@link android.service.controls.ControlTemplate.TemplateType} than the
     *                        one defined in {@link Control#getPrimaryType}
     * @param statusText the user facing text describing the current status.
     * @param title the title to replace the one set in the {@link Control} or set in the
     *              last {@link ControlState}. Pass {@code null} to use the last value set for this
     *              {@link Control}
     * @param appIntent the {@link PendingIntent} to replace the one set in the {@link Control} or
     *                  set in the last {@link ControlState}. Pass {@code null} to use the last
     *                  value set for this {@link Control}.
     * @param icon the icon to replace the one set in the {@link Control} or set in the last
     *             {@link ControlState}. Pass {@code null} to use the last value set for this
     *             {@link Control}.
     * @param tint the colors to replace those set in the {@link Control} or set in the last
     *             {@link ControlState}. Pass {@code null} to use the last value set for this
     *             {@link Control}.
     */
    public ControlState(@NonNull String controlId,
            int status,
            @NonNull ControlTemplate controlTemplate,
            @NonNull CharSequence statusText,
            @Nullable CharSequence title,
            @Nullable PendingIntent appIntent,
            @Nullable Icon icon,
            @Nullable ColorStateList tint) {
        Preconditions.checkNotNull(controlId);
        Preconditions.checkNotNull(controlTemplate);
        Preconditions.checkNotNull(statusText);
        mControlId = controlId;
        mStatus = status;
        mControlTemplate = controlTemplate;
        mStatusText = statusText;
        mTitle = title;
        mAppIntent = appIntent;
        mIcon = icon;
        mTint = tint;
    }

    ControlState(Parcel in) {
        mControlId = in.readString();
        mStatus = in.readInt();
        mControlTemplate = ControlTemplate.CREATOR.createFromParcel(in);
        mStatusText = in.readCharSequence();
        if (in.readByte() == 1) {
            mTitle = in.readCharSequence();
        } else {
            mTitle = null;
        }
        if (in.readByte() == 1) {
            mAppIntent = PendingIntent.CREATOR.createFromParcel(in);
        } else {
            mAppIntent = null;
        }
        if (in.readByte() == 1) {
            mIcon = Icon.CREATOR.createFromParcel(in);
        } else {
            mIcon = null;
        }
        if (in.readByte() == 1) {
            mTint = ColorStateList.CREATOR.createFromParcel(in);
        } else {
            mTint = null;
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public String getControlId() {
        return mControlId;
    }

    @Nullable
    public CharSequence getTitle() {
        return mTitle;
    }

    @Nullable
    public PendingIntent getAppIntent() {
        return mAppIntent;
    }

    @Status
    public int getStatus() {
        return mStatus;
    }

    @NonNull
    public ControlTemplate getControlTemplate() {
        return mControlTemplate;
    }

    @Nullable
    public Icon getIcon() {
        return mIcon;
    }

    @NonNull
    public CharSequence getStatusText() {
        return mStatusText;
    }

    @Nullable
    public ColorStateList getTint() {
        return mTint;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mControlId);
        dest.writeInt(mStatus);
        mControlTemplate.writeToParcel(dest, flags);
        dest.writeCharSequence(mStatusText);
        if (mTitle != null) {
            dest.writeByte((byte) 1);
            dest.writeCharSequence(mTitle);
        } else {
            dest.writeByte((byte) 0);
        }
        if (mAppIntent != null) {
            dest.writeByte((byte) 1);
            mAppIntent.writeToParcel(dest, flags);
        }
        if (mIcon != null) {
            dest.writeByte((byte) 1);
            mIcon.writeToParcel(dest, flags);
        } else {
            dest.writeByte((byte) 0);
        }
        if (mTint != null) {
            dest.writeByte((byte) 1);
            mTint.writeToParcel(dest, flags);
        } else {
            dest.writeByte((byte) 0);
        }
    }

    public static final Creator<ControlState> CREATOR = new Creator<ControlState>() {
        @Override
        public ControlState createFromParcel(Parcel source) {
            return new ControlState(source);
        }

        @Override
        public ControlState[] newArray(int size) {
            return new ControlState[size];
        }
    };

    /**
     * Builder class for {@link ControlState}.
     *
     * This class facilitates the creation of {@link ControlState}. It provides the following
     * defaults for non-optional parameters:
     * <ul>
     *     <li> Status: {@link ControlState#STATUS_OK}
     *     <li> Control template: {@link ControlTemplate#NO_TEMPLATE}
     *     <li> Status text: {@code ""}
     * </ul>
     */
    public static class Builder {
        private @NonNull String mControlId;
        private @Status int mStatus = STATUS_OK;
        private @NonNull ControlTemplate mControlTemplate = ControlTemplate.NO_TEMPLATE;
        private @NonNull CharSequence mStatusText = "";
        private @Nullable CharSequence mTitle;
        private @Nullable PendingIntent mAppIntent;
        private @Nullable Icon mIcon;
        private @Nullable ColorStateList mTint;

        /**
         * @param controlId the identifier of the {@link Control} that the resulting
         *                  {@link ControlState} refers to.
         */
        public Builder(@NonNull String controlId) {
            Preconditions.checkNotNull(controlId);
            mControlId = controlId;
        }

        /**
         * Creates a {@link Builder} using an existing {@link ControlState} as a base.
         * @param controlState base for the builder.
         */
        public Builder(@NonNull ControlState controlState) {
            Preconditions.checkNotNull(controlState);
            mControlId = controlState.mControlId;
            mStatus = controlState.mStatus;
            mControlTemplate = controlState.mControlTemplate;
            mStatusText = controlState.mStatusText;
            mTitle = controlState.mTitle;
            mAppIntent = controlState.mAppIntent;
            mIcon = controlState.mIcon;
            mTint = controlState.mTint;
        }


        /**
         * @param controlId the identifier of the {@link Control} for the resulting object.
         * @return {@code this}
         */
        @NonNull
        public Builder setControlId(@NonNull String controlId) {
            mControlId = controlId;
            return this;
        }

        /**
         * @param status the current status of the {@link Control}
         * @return {@code this}
         */
        @NonNull
        public Builder setStatus(@Status int status) {
            mStatus = status;
            return this;
        }

        /**
         * @param controlTemplate the template to use when rendering the {@code Control}.
         * @return {@code this}
         */
        @NonNull
        public Builder setControlTemplate(@NonNull ControlTemplate controlTemplate) {
            Preconditions.checkNotNull(controlTemplate);
            mControlTemplate = controlTemplate;
            return this;
        }

        /**
         * @param statusText the user-visible description of the status.
         * @return {@code this}
         */
        @NonNull
        public Builder setStatusText(@NonNull CharSequence statusText) {
            Preconditions.checkNotNull(statusText);
            mStatusText = statusText;
            return this;
        }

        /**
         * @param title the title to replace the one defined in the corresponding {@link Control} or
         *              set by the last {@link ControlState}. Pass {@code null} to keep the last
         *              value.
         * @return {@code this}
         */
        @NonNull
        public Builder setTitle(@Nullable CharSequence title) {
            mTitle = title;
            return this;
        }

        /**
         * @param appIntent the Pending Intent to replace the one defined in the corresponding
         *                  {@link Control} or set by the last {@link ControlState}. Pass
         *                  {@code null} to keep the last value.
         * @return {@code this}
         */
        @NonNull
        public Builder setAppIntent(@Nullable PendingIntent appIntent) {
            mAppIntent = appIntent;
            return this;
        }

        /**
         * @param icon the title to replace the one defined in the corresponding {@link Control} or
         *             set by the last {@link ControlState}. Pass {@code null} to keep the last
         *             value.
         * @return {@code this}
         */
        @NonNull
        public Builder setIcon(@Nullable Icon icon) {
            mIcon = icon;
            return this;
        }

        /**
         * @param tint the title to replace the one defined in the corresponding {@link Control} or
         *             set by the last {@link ControlState}. Pass {@code null} to keep the last
         *             value.
         * @return {@code this}
         */
        @NonNull
        public Builder setTint(@Nullable ColorStateList tint) {
            mTint = tint;
            return this;
        }

        /**
         * @return a new {@link ControlState}
         */
        public ControlState build() {
            return new ControlState(mControlId, mStatus, mControlTemplate, mStatusText,
                    mTitle, mAppIntent, mIcon, mTint);
        }
    }
}

