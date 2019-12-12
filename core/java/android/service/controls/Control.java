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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.Icon;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

/**
 * Represents a physical object that can be represented by a {@link ControlTemplate} and whose
 * properties may be modified through a {@link ControlAction}.
 *
 * The information is provided by a {@link ControlProviderService} and represents static
 * information (not current status) about the device.
 * <p>
 * Each control needs a unique (per provider) identifier that is persistent across reboots of the
 * system.
 * <p>
 * Each {@link Control} will have a name and an icon. The name is usually set up by the user in the
 * {@link ControlProvider} while the icon is usually decided by the {@link ControlProvider} based
 * on the type of device.
 * <p>
 * The {@link ControlTemplate.TemplateType} provided will be used as a hint when displaying this in
 * non-interactive situations (for example when there's no state to display). This template is not
 * the one that will be shown with the current state and provide interactions. That template is set
 * using {@link ControlState}.
 * <p>
 * An {@link Intent} linking to the provider Activity that expands this {@link Control} should be
 * provided.
 * @hide
 */
public class Control implements Parcelable {

    private final @NonNull String mControlId;
    private final @NonNull Icon mIcon;
    private final @NonNull CharSequence mTitle;
    private final @Nullable ColorStateList mTintColor;
    private final @NonNull PendingIntent mAppIntent;
    private final @ControlTemplate.TemplateType int mPrimaryType;

    /**
     * @param controlId the unique persistent identifier for this object.
     * @param icon an icon to display identifying the control.
     * @param title the user facing name of this control (e.g. "Bedroom thermostat").
     * @param tintColor the color to tint parts of the element UI. If {@code null} is passed, the
     *                  system accent color will be used.
     * @param appIntent a {@link PendingIntent} linking to a page to interact with the
     *                  corresponding device.
     * @param primaryType the primary template for this type.
     */
    public Control(@NonNull String controlId,
            @NonNull Icon icon,
            @NonNull CharSequence title,
            @Nullable ColorStateList tintColor,
            @NonNull PendingIntent appIntent,
            int primaryType) {
        Preconditions.checkNotNull(controlId);
        Preconditions.checkNotNull(icon);
        Preconditions.checkNotNull(title);
        Preconditions.checkNotNull(appIntent);
        mControlId = controlId;
        mIcon = icon;
        mTitle = title;
        mTintColor = tintColor;
        mAppIntent = appIntent;
        mPrimaryType = primaryType;
    }

    public Control(Parcel in) {
        mControlId = in.readString();
        mIcon = Icon.CREATOR.createFromParcel(in);
        mTitle = in.readCharSequence();
        if (in.readByte() == 1) {
            mTintColor = ColorStateList.CREATOR.createFromParcel(in);
        } else {
            mTintColor = null;
        }
        mAppIntent = PendingIntent.CREATOR.createFromParcel(in);
        mPrimaryType = in.readInt();
    }

    @NonNull
    public String getControlId() {
        return mControlId;
    }

    @NonNull
    public Icon getIcon() {
        return mIcon;
    }

    @NonNull
    public CharSequence getTitle() {
        return mTitle;
    }

    @Nullable
    public ColorStateList getTint() {
        return mTintColor;
    }

    @NonNull
    public PendingIntent getAppIntent() {
        return mAppIntent;
    }

    @ControlTemplate.TemplateType
    public int getPrimaryType() {
        return mPrimaryType;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mControlId);
        mIcon.writeToParcel(dest, flags);
        dest.writeCharSequence(mTitle);
        if (mTintColor != null) {
            dest.writeByte((byte) 1);
            mTintColor.writeToParcel(dest, flags);
        } else {
            dest.writeByte((byte) 0);
        }
        mAppIntent.writeToParcel(dest, flags);
        dest.writeInt(mPrimaryType);
    }

    public static final Creator<Control> CREATOR = new Creator<Control>() {
        @Override
        public Control createFromParcel(Parcel source) {
            return new Control(source);
        }

        @Override
        public Control[] newArray(int size) {
            return new Control[size];
        }
    };

    /**
     * Builder class for {@link Control}.
     *
     * This class facilitates the creation of {@link Control}. It provides the following
     * defaults for non-optional parameters:
     * <ul>
     *     <li> Title: {@code ""}
     *     <li> Primary template: {@link ControlTemplate#TYPE_NONE}
     * </ul>
     */
    public static class Builder {
        private String mControlId;
        private Icon mIcon;
        private CharSequence mTitle = "";
        private ColorStateList mTintColor;
        private @Nullable PendingIntent mAppIntent;
        private @ControlTemplate.TemplateType int mPrimaryType = ControlTemplate.TYPE_NONE;

        /**
         * @param controlId the identifier for the {@link Control}.
         * @param icon the icon for the {@link Control}.
         * @param appIntent the pending intent linking to the device Activity.
         */
        public Builder(@NonNull String controlId,
                @NonNull Icon icon,
                @NonNull PendingIntent appIntent) {
            Preconditions.checkNotNull(controlId);
            Preconditions.checkNotNull(icon);
            Preconditions.checkNotNull(appIntent);
            mControlId = controlId;
            mIcon = icon;
            mAppIntent = appIntent;
        }

        /**
         * Creates a {@link Builder} using an existing {@link Control} as a base.
         * @param control base for the builder.
         */
        public Builder(@NonNull Control control) {
            Preconditions.checkNotNull(control);
            mControlId = control.mControlId;
            mIcon = control.mIcon;
            mTitle = control.mTitle;
            mTintColor = control.mTintColor;
            mAppIntent = control.mAppIntent;
            mPrimaryType = control.mPrimaryType;
        }

        /**
         * @param controlId the identifier for the {@link Control}.
         * @return {@code this}
         */
        public Builder setControlId(@NonNull String controlId) {
            Preconditions.checkNotNull(controlId);
            mControlId = controlId;
            return this;
        }

        /**
         * @param icon the icon for the {@link Control}
         * @return {@code this}
         */
        @NonNull
        public Builder setIcon(@NonNull Icon icon) {
            Preconditions.checkNotNull(icon);
            mIcon = icon;
            return this;
        }

        /**
         * @param title the user facing name of the {@link Control}
         * @return {@code this}
         */
        @NonNull
        public Builder setTitle(@NonNull CharSequence title) {
            Preconditions.checkNotNull(title);
            mTitle = title;
            return this;
        }

        /**
         * @param tint colors for tinting parts of the {@link Control} UI. Passing {@code null} will
         *             default to using the current color accent.
         * @return {@code this}
         */
        @NonNull
        public Builder setTint(@Nullable ColorStateList tint) {
            mTintColor = tint;
            return this;
        }

        /**
         * @param appIntent an {@link Intent} linking to an Activity for the {@link Control}
         * @return {@code this}
         */
        @NonNull
        public Builder setAppIntent(@NonNull PendingIntent appIntent) {
            Preconditions.checkNotNull(appIntent);
            mAppIntent = appIntent;
            return this;
        }

        /**
         * @param type type to use as default in the {@link Control}
         * @return {@code this}
         */
        @NonNull
        public Builder setPrimaryType(@ControlTemplate.TemplateType int type) {
            mPrimaryType = type;
            return this;
        }

        /**
         * Build a {@link Control}
         * @return a valid {@link Control}
         */
        @NonNull
        public Control build() {
            return new Control(mControlId, mIcon, mTitle, mTintColor, mAppIntent, mPrimaryType);
        }
    }
}
