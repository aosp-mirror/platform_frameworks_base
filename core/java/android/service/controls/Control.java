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
import android.os.Parcel;
import android.os.Parcelable;
import android.service.controls.actions.ControlAction;
import android.service.controls.templates.ControlTemplate;
import android.util.Log;

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
 * Each {@link Control} will have a name, a subtitle and will optionally belong to a structure
 * and zone. Some of these values are defined by the user and/or the {@link ControlProviderService}
 * and will be used to display the control as well as group them for management.
 * <p>
 * Each object will have an associated {@link DeviceTypes.DeviceType}. This will determine the icons and colors
 * used to display it.
 * <p>
 * The {@link ControlTemplate.TemplateType} provided will be used as a hint when displaying this in
 * non-interactive situations (for example when there's no state to display). This template is not
 * the one that will be shown with the current state and provide interactions. That template is set
 * using {@link ControlState}.
 * <p>
 * An {@link Intent} linking to the provider Activity that expands on this {@link Control} and
 * allows for further actions should be provided.
 * @hide
 */
public class Control implements Parcelable {
    private static final String TAG = "Control";

    ;

    private final @NonNull String mControlId;
    private final @DeviceTypes.DeviceType
    int mDeviceType;
    private final @NonNull CharSequence mTitle;
    private final @NonNull CharSequence mSubtitle;
    private final @Nullable CharSequence mStructure;
    private final @Nullable CharSequence mZone;
    private final @NonNull PendingIntent mAppIntent;
    private final @ControlTemplate.TemplateType int mPrimaryType;

    /**
     * @param controlId the unique persistent identifier for this object.
     * @param deviceType the type of device for this control. This will determine icons and colors.
     * @param title the user facing name of this control (e.g. "Bedroom thermostat").
     * @param subtitle a user facing subtitle with extra information about this control
     * @param structure a user facing name for the structure containing the device associated with
     *                  this control.
     * @param zone
     * @param appIntent a {@link PendingIntent} linking to a page to interact with the
     *                  corresponding device.
     * @param primaryType the primary template for this type.
     */
    public Control(@NonNull String controlId,
            @DeviceTypes.DeviceType int deviceType,
            @NonNull CharSequence title,
            @NonNull CharSequence subtitle,
            @Nullable CharSequence structure,
            @Nullable CharSequence zone,
            @NonNull PendingIntent appIntent,
            int primaryType) {
        Preconditions.checkNotNull(controlId);
        Preconditions.checkNotNull(title);
        Preconditions.checkNotNull(subtitle);
        Preconditions.checkNotNull(appIntent);
        mControlId = controlId;
        if (!DeviceTypes.validDeviceType(deviceType)) {
            Log.e(TAG, "Invalid device type:" + deviceType);
            mDeviceType = DeviceTypes.TYPE_UNKNOWN;
        } else {
            mDeviceType = deviceType;
        }
        mTitle = title;
        mSubtitle = subtitle;
        mStructure = structure;
        mZone = zone;
        mAppIntent = appIntent;
        mPrimaryType = primaryType;
    }

    public Control(Parcel in) {
        mControlId = in.readString();
        mDeviceType = in.readInt();
        mTitle = in.readCharSequence();
        mSubtitle = in.readCharSequence();
        if (in.readByte() == (byte) 1) {
            mStructure = in.readCharSequence();
        } else {
            mStructure = null;
        }
        if (in.readByte() == (byte) 1) {
            mZone = in.readCharSequence();
        } else {
            mZone = null;
        }
        mAppIntent = PendingIntent.CREATOR.createFromParcel(in);
        mPrimaryType = in.readInt();
    }

    @NonNull
    public String getControlId() {
        return mControlId;
    }

    @DeviceTypes.DeviceType
    public int getDeviceType() {
        return mDeviceType;
    }

    @NonNull
    public CharSequence getTitle() {
        return mTitle;
    }

    @NonNull
    public CharSequence getSubtitle() {
        return mSubtitle;
    }

    @Nullable
    public CharSequence getStructure() {
        return mStructure;
    }

    @Nullable
    public CharSequence getZone() {
        return mZone;
    }

    @NonNull
    public PendingIntent getAppIntent() {
        return mAppIntent;
    }

    @android.service.controls.templates.ControlTemplate.TemplateType
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
        dest.writeInt(mDeviceType);
        dest.writeCharSequence(mTitle);
        dest.writeCharSequence(mSubtitle);
        if (mStructure != null) {
            dest.writeByte((byte) 1);
            dest.writeCharSequence(mStructure);
        } else {
            dest.writeByte((byte) 0);
        }
        if (mZone != null) {
            dest.writeByte((byte) 1);
            dest.writeCharSequence(mZone);
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
     *     <li> Device type: {@link DeviceTypes#TYPE_UNKNOWN}
     *     <li> Title: {@code ""}
     *     <li> Subtitle: {@code ""}
     *     <li> Primary template: {@link ControlTemplate#TYPE_NONE}
     * </ul>
     */
    public static class Builder {
        private static final String TAG = "Control.Builder";
        private @NonNull String mControlId;
        private @DeviceTypes.DeviceType
        int mDeviceType = DeviceTypes.TYPE_UNKNOWN;
        private @NonNull CharSequence mTitle = "";
        private @NonNull CharSequence mSubtitle = "";
        private @Nullable CharSequence mStructure;
        private @Nullable CharSequence mZone;
        private @NonNull PendingIntent mAppIntent;
        private @ControlTemplate.TemplateType int mPrimaryType = ControlTemplate.TYPE_NONE;

        /**
         * @param controlId the identifier for the {@link Control}.
         * @param appIntent the pending intent linking to the device Activity.
         */
        public Builder(@NonNull String controlId,
                @NonNull PendingIntent appIntent) {
            Preconditions.checkNotNull(controlId);
            Preconditions.checkNotNull(appIntent);
            mControlId = controlId;
            mAppIntent = appIntent;
        }

        /**
         * Creates a {@link Builder} using an existing {@link Control} as a base.
         * @param control base for the builder.
         */
        public Builder(@NonNull Control control) {
            Preconditions.checkNotNull(control);
            mControlId = control.mControlId;
            mDeviceType = control.mDeviceType;
            mTitle = control.mTitle;
            mSubtitle = control.mSubtitle;
            mStructure = control.mStructure;
            mZone = control.mZone;
            mAppIntent = control.mAppIntent;
            mPrimaryType = control.mPrimaryType;
        }

        /**
         * @param controlId the identifier for the {@link Control}.
         * @return {@code this}
         */
        @NonNull
        public Builder setControlId(@NonNull String controlId) {
            Preconditions.checkNotNull(controlId);
            mControlId = controlId;
            return this;
        }

        @NonNull
        public Builder setDeviceType(@DeviceTypes.DeviceType int deviceType) {
            if (!DeviceTypes.validDeviceType(deviceType)) {
                Log.e(TAG, "Invalid device type:" + deviceType);
                mDeviceType = DeviceTypes.TYPE_UNKNOWN;
            } else {
                mDeviceType = deviceType;
            }
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

        @NonNull
        public Builder setSubtitle(@NonNull CharSequence subtitle) {
            Preconditions.checkNotNull(subtitle);
            mSubtitle = subtitle;
            return this;
        }

        @NonNull
        public Builder setStructure(@Nullable CharSequence structure) {
            mStructure = structure;
            return this;
        }

        @NonNull
        public Builder setZone(@Nullable CharSequence zone) {
            mZone = zone;
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
            return new Control(mControlId,
                    mDeviceType,
                    mTitle,
                    mSubtitle,
                    mStructure,
                    mZone,
                    mAppIntent,
                    mPrimaryType);
        }
    }
}
