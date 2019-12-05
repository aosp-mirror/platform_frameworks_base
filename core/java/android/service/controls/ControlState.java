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
 * that can be performed on it. Some of the information may temporarily override the defaults
 * provided by the corresponding {@link Control}, while this state is being displayed.
 *
 * Additionally, this can be used to modify information related to the corresponding
 * {@link Control}.
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

    private final @NonNull Control mControl;
    private final @Status int mStatus;
    private final @NonNull ControlTemplate mControlTemplate;
    private final @NonNull CharSequence mStatusText;
    private final @Nullable Icon mOverrideIcon;
    private final @Nullable ColorStateList mOverrideTint;

    /**
     * @param control the {@link Control} this state should be applied to. Can be used to
     *                       update information about the {@link Control}
     * @param status the current status of the {@link Control}.
     * @param controlTemplate the template to be used to render the {@link Control}.
     * @param statusText the text describing the current status.
     * @param overrideIcon the icon to temporarily override the one provided in
     *                     {@link Control#getIcon()}. Pass {@code null} to use the icon in
     *                     {@link Control#getIcon()}.
     * @param overrideTint the colors to temporarily override those provided in
     *                            {@link Control#getTint()}. Pass {@code null} to use the colors in
     *                            {@link Control#getTint()}.
     */
    public ControlState(@NonNull Control control,
            int status,
            @NonNull ControlTemplate controlTemplate,
            @NonNull CharSequence statusText,
            @Nullable Icon overrideIcon,
            @Nullable ColorStateList overrideTint) {
        Preconditions.checkNotNull(control);
        Preconditions.checkNotNull(controlTemplate);
        Preconditions.checkNotNull(statusText);

        mControl = control;
        mStatus = status;
        mControlTemplate = controlTemplate;
        mOverrideIcon = overrideIcon;
        mStatusText = statusText;
        mOverrideTint = overrideTint;
    }

    ControlState(Parcel in) {
        mControl = Control.CREATOR.createFromParcel(in);
        mStatus = in.readInt();
        mControlTemplate = ControlTemplate.CREATOR.createFromParcel(in);
        mStatusText = in.readCharSequence();
        if (in.readByte() == 1) {
            mOverrideIcon = Icon.CREATOR.createFromParcel(in);
        } else {
            mOverrideIcon = null;
        }
        if (in.readByte() == 1) {
            mOverrideTint = ColorStateList.CREATOR.createFromParcel(in);
        } else {
            mOverrideTint = null;
        }
    }

    @Override
    public int describeContents() {
        return 0;
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
    public Icon getOverrideIcon() {
        return mOverrideIcon;
    }

    @NonNull
    public CharSequence getStatusText() {
        return mStatusText;
    }

    @Nullable
    public ColorStateList getOverrideTint() {
        return mOverrideTint;
    }

    @NonNull
    public Control getControl() {
        return mControl;
    }

    @NonNull
    public String getControlId() {
        return mControl.getControlId();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        mControl.writeToParcel(dest, flags);
        dest.writeInt(mStatus);
        mControlTemplate.writeToParcel(dest, flags);
        dest.writeCharSequence(mStatusText);
        if (mOverrideIcon != null) {
            dest.writeByte((byte) 1);
            mOverrideIcon.writeToParcel(dest, flags);
        } else {
            dest.writeByte((byte) 0);
        }
        if (mOverrideTint != null) {
            dest.writeByte((byte) 1);
            mOverrideTint.writeToParcel(dest, flags);
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
        private @NonNull Control mControl;
        private @Status int mStatus = STATUS_OK;
        private @NonNull ControlTemplate mControlTemplate = ControlTemplate.NO_TEMPLATE;
        private @NonNull CharSequence mStatusText = "";
        private @Nullable Icon mOverrideIcon;
        private @Nullable ColorStateList mOverrideTint;

        /**
         * @param control the {@link Control} that the resulting {@link ControlState} refers to.
         */
        public Builder(@NonNull Control control) {
            Preconditions.checkNotNull(control);
            mControl = control;
        }

        /**
         * Creates a {@link Builder} using an existing {@link ControlState} as a base.
         * @param controlState base for the builder.
         */
        public Builder(@NonNull ControlState controlState) {
            Preconditions.checkNotNull(controlState);
            mControl = controlState.mControl;
            mControlTemplate = controlState.mControlTemplate;
            mOverrideIcon = controlState.mOverrideIcon;
            mStatusText = controlState.mStatusText;
            mOverrideTint = controlState.mOverrideTint;
        }


        /**
         * @param control the updated {@link Control} information.
         * @return {@code this}
         */
        @NonNull
        public Builder setControl(@NonNull Control control) {
            mControl = control;
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
         * @param overrideIcon the icon to override the one defined in the corresponding
         *                            {@code Control}. Pass {@code null} to remove the override.
         * @return {@code this}
         */
        @NonNull
        public Builder setOverrideIcon(@Nullable Icon overrideIcon) {
            mOverrideIcon = overrideIcon;
            return this;
        }

        /**
         * @param overrideTint the colors to override the ones defined in the corresponding
         *                            {@code Control}. Pass {@code null} to remove the override.
         * @return {@code this}
         */
        @NonNull
        public Builder setOverrideTint(@Nullable ColorStateList overrideTint) {
            mOverrideTint = overrideTint;
            return this;
        }

        /**
         * @return a new {@link ControlState}
         */
        public ControlState build() {
            return new ControlState(mControl, mStatus, mControlTemplate, mStatusText,
                    mOverrideIcon, mOverrideTint);
        }
    }
}

