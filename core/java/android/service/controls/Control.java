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
import android.content.Intent;
import android.os.Parcel;
import android.os.Parcelable;
import android.service.controls.actions.ControlAction;
import android.service.controls.templates.ControlTemplate;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Represents a physical object that can be represented by a {@link ControlTemplate} and whose
 * properties may be modified through a {@link ControlAction}.
 *
 * The information is provided by a {@link ControlsProviderService} and represents static
 * information (not current status) about the device.
 * <p>
 * Each control needs a unique (per provider) identifier that is persistent across reboots of the
 * system.
 * <p>
 * Each {@link Control} will have a name, a subtitle and will optionally belong to a structure
 * and zone. Some of these values are defined by the user and/or the {@link ControlsProviderService}
 * and will be used to display the control as well as group them for management.
 * <p>
 * Each object will have an associated {@link DeviceTypes.DeviceType}. This will determine the icons and colors
 * used to display it.
 * <p>
 * An {@link Intent} linking to the provider Activity that expands on this {@link Control} and
 * allows for further actions should be provided.
 * @hide
 */
public class Control implements Parcelable {
    private static final String TAG = "Control";

    private static final int NUM_STATUS = 5;
    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            STATUS_UNKNOWN,
            STATUS_OK,
            STATUS_NOT_FOUND,
            STATUS_ERROR,
            STATUS_DISABLED,
    })
    public @interface Status {};

    public static final int STATUS_UNKNOWN = 0;

    /**
     * The device corresponding to the {@link Control} is responding correctly.
     */
    public static final int STATUS_OK = 1;

    /**
     * The device corresponding to the {@link Control} cannot be found or was removed.
     */
    public static final int STATUS_NOT_FOUND = 2;

    /**
     * The device corresponding to the {@link Control} is in an error state.
     */
    public static final int STATUS_ERROR = 3;

    /**
     * The {@link Control} is currently disabled.
     */
    public static final int STATUS_DISABLED = 4;

    private final @NonNull String mControlId;
    private final @DeviceTypes.DeviceType int mDeviceType;
    private final @NonNull CharSequence mTitle;
    private final @NonNull CharSequence mSubtitle;
    private final @Nullable CharSequence mStructure;
    private final @Nullable CharSequence mZone;
    private final @NonNull PendingIntent mAppIntent;
    private final @Status int mStatus;
    private final @NonNull ControlTemplate mControlTemplate;
    private final @NonNull CharSequence mStatusText;

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
     */
    public Control(@NonNull String controlId,
            @DeviceTypes.DeviceType int deviceType,
            @NonNull CharSequence title,
            @NonNull CharSequence subtitle,
            @Nullable CharSequence structure,
            @Nullable CharSequence zone,
            @NonNull PendingIntent appIntent,
            @Status int status,
            @NonNull ControlTemplate controlTemplate,
            @NonNull CharSequence statusText) {
        Preconditions.checkNotNull(controlId);
        Preconditions.checkNotNull(title);
        Preconditions.checkNotNull(subtitle);
        Preconditions.checkNotNull(appIntent);
        Preconditions.checkNotNull(controlTemplate);
        Preconditions.checkNotNull(statusText);
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
        if (status < 0 || status >= NUM_STATUS) {
            mStatus = STATUS_UNKNOWN;
            Log.e(TAG, "Status unknown:" + status);
        } else {
            mStatus = status;
        }
        mControlTemplate = controlTemplate;
        mStatusText = statusText;
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
        mStatus = in.readInt();
        mControlTemplate = ControlTemplate.CREATOR.createFromParcel(in);
        mStatusText = in.readCharSequence();
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

    @Status
    public int getStatus() {
        return mStatus;
    }

    @NonNull
    public ControlTemplate getControlTemplate() {
        return mControlTemplate;
    }

    @NonNull
    public CharSequence getStatusText() {
        return mStatusText;
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
        dest.writeInt(mStatus);
        mControlTemplate.writeToParcel(dest, flags);
        dest.writeCharSequence(mStatusText);
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
     * This class facilitates the creation of {@link Control} with no state.
     * It provides the following defaults for non-optional parameters:
     * <ul>
     *     <li> Device type: {@link DeviceTypes#TYPE_UNKNOWN}
     *     <li> Title: {@code ""}
     *     <li> Subtitle: {@code ""}
     * </ul>
     * This fixes the values relating to state of the {@link Control} as required by
     * {@link ControlsProviderService#onLoad}:
     * <ul>
     *     <li> Status: {@link Status#STATUS_UNKNOWN}
     *     <li> Control template: {@link ControlTemplate#NO_TEMPLATE}
     *     <li> Status text: {@code ""}
     * </ul>
     */
    public static class StatelessBuilder {
        private static final String TAG = "StatelessBuilder";
        protected @NonNull String mControlId;
        protected @DeviceTypes.DeviceType int mDeviceType = DeviceTypes.TYPE_UNKNOWN;
        protected @NonNull CharSequence mTitle = "";
        protected @NonNull CharSequence mSubtitle = "";
        protected @Nullable CharSequence mStructure;
        protected @Nullable CharSequence mZone;
        protected @NonNull PendingIntent mAppIntent;
        protected @Status int mStatus = STATUS_UNKNOWN;
        protected @NonNull ControlTemplate mControlTemplate = ControlTemplate.NO_TEMPLATE;
        protected @NonNull CharSequence mStatusText = "";

        /**
         * @param controlId the identifier for the {@link Control}.
         * @param appIntent the pending intent linking to the device Activity.
         */
        public StatelessBuilder(@NonNull String controlId,
                @NonNull PendingIntent appIntent) {
            Preconditions.checkNotNull(controlId);
            Preconditions.checkNotNull(appIntent);
            mControlId = controlId;
            mAppIntent = appIntent;
        }

        /**
         * Creates a {@link StatelessBuilder} using an existing {@link Control} as a base.
         * @param control base for the builder.
         */
        public StatelessBuilder(@NonNull Control control) {
            Preconditions.checkNotNull(control);
            mControlId = control.mControlId;
            mDeviceType = control.mDeviceType;
            mTitle = control.mTitle;
            mSubtitle = control.mSubtitle;
            mStructure = control.mStructure;
            mZone = control.mZone;
            mAppIntent = control.mAppIntent;
        }

        /**
         * @param controlId the identifier for the {@link Control}.
         * @return {@code this}
         */
        @NonNull
        public StatelessBuilder setControlId(@NonNull String controlId) {
            Preconditions.checkNotNull(controlId);
            mControlId = controlId;
            return this;
        }

        @NonNull
        public StatelessBuilder setDeviceType(@DeviceTypes.DeviceType int deviceType) {
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
        public StatelessBuilder setTitle(@NonNull CharSequence title) {
            Preconditions.checkNotNull(title);
            mTitle = title;
            return this;
        }

        @NonNull
        public StatelessBuilder setSubtitle(@NonNull CharSequence subtitle) {
            Preconditions.checkNotNull(subtitle);
            mSubtitle = subtitle;
            return this;
        }

        @NonNull
        public StatelessBuilder setStructure(@Nullable CharSequence structure) {
            mStructure = structure;
            return this;
        }

        @NonNull
        public StatelessBuilder setZone(@Nullable CharSequence zone) {
            mZone = zone;
            return this;
        }

        /**
         * @param appIntent an {@link Intent} linking to an Activity for the {@link Control}
         * @return {@code this}
         */
        @NonNull
        public StatelessBuilder setAppIntent(@NonNull PendingIntent appIntent) {
            Preconditions.checkNotNull(appIntent);
            mAppIntent = appIntent;
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
                    mStatus,
                    mControlTemplate,
                    mStatusText);
        }
    }

    public static class StatefulBuilder extends StatelessBuilder {
        private static final String TAG = "StatefulBuilder";

        /**
         * @param controlId the identifier for the {@link Control}.
         * @param appIntent the pending intent linking to the device Activity.
         */
        public StatefulBuilder(@NonNull String controlId,
                @NonNull PendingIntent appIntent) {
            super(controlId, appIntent);
        }

        public StatefulBuilder(@NonNull Control control) {
            super(control);
            mStatus = control.mStatus;
            mControlTemplate = control.mControlTemplate;
            mStatusText = control.mStatusText;
        }

        /**
         * @param controlId the identifier for the {@link Control}.
         * @return {@code this}
         */
        @NonNull
        public StatefulBuilder setControlId(@NonNull String controlId) {
            super.setControlId(controlId);
            return this;
        }

        @NonNull
        public StatefulBuilder setDeviceType(@DeviceTypes.DeviceType int deviceType) {
            super.setDeviceType(deviceType);
            return this;
        }

        /**
         * @param title the user facing name of the {@link Control}
         * @return {@code this}
         */
        @NonNull
        public StatefulBuilder setTitle(@NonNull CharSequence title) {
            super.setTitle(title);
            return this;
        }

        @NonNull
        public StatefulBuilder setSubtitle(@NonNull CharSequence subtitle) {
            super.setSubtitle(subtitle);
            return this;
        }

        @NonNull
        public StatefulBuilder setStructure(@Nullable CharSequence structure) {
            super.setStructure(structure);
            return this;
        }

        @NonNull
        public StatefulBuilder setZone(@Nullable CharSequence zone) {
            super.setZone(zone);
            return this;
        }

        /**
         * @param appIntent an {@link Intent} linking to an Activity for the {@link Control}
         * @return {@code this}
         */
        @NonNull
        public StatefulBuilder setAppIntent(@NonNull PendingIntent appIntent) {
            super.setAppIntent(appIntent);
            return this;
        }

        @NonNull
        public StatefulBuilder setStatus(@Status int status) {
            if (status < 0 || status >= NUM_STATUS) {
                mStatus = STATUS_UNKNOWN;
                Log.e(TAG, "Status unknown:" + status);
            } else {
                mStatus = status;
            }
            return this;
        }

        @NonNull
        public StatefulBuilder setControlTemplate(@NonNull ControlTemplate controlTemplate) {
            Preconditions.checkNotNull(controlTemplate);
            mControlTemplate = controlTemplate;
            return this;
        }

        @NonNull
        public StatefulBuilder setStatusText(@NonNull CharSequence statusText) {
            Preconditions.checkNotNull(statusText);
            mStatusText = statusText;
            return this;
        }
    }
}
