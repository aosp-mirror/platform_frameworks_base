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
import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.Icon;
import android.os.Parcel;
import android.os.Parcelable;
import android.service.controls.actions.ControlAction;
import android.service.controls.templates.ControlTemplate;
import android.service.controls.templates.ControlTemplateWrapper;
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
 * Each object will have an associated {@link DeviceTypes}. This will determine the icons and colors
 * used to display it.
 * <p>
 * An {@link Intent} linking to the provider Activity that expands on this {@link Control} and
 * allows for further actions should be provided.
 */
public final class Control implements Parcelable {
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

    /**
     * Reserved for use with the {@link StatelessBuilder}, and while loading. When state is
     * requested via {@link ControlsProviderService#createPublisherFor}, use other status codes
     * to indicate the proper device state.
     */
    public static final int STATUS_UNKNOWN = 0;

    /**
     * Used to indicate that the state of the device was successfully retrieved. This includes
     * all scenarios where the device may have a warning for the user, such as "Lock jammed",
     * or "Vacuum stuck". Any information for the user should be set through
     * {@link StatefulBuilder#setStatusText}.
     */
    public static final int STATUS_OK = 1;

    /**
     * The device corresponding to the {@link Control} cannot be found or was removed. The user
     * will be alerted and directed to the application to resolve.
     */
    public static final int STATUS_NOT_FOUND = 2;

    /**
     * Used to indicate that there was a temporary error while loading the device state. A default
     * error message will be displayed in place of any custom text that was set through
     * {@link StatefulBuilder#setStatusText}.
     */
    public static final int STATUS_ERROR = 3;

    /**
     * The {@link Control} is currently disabled.  A default error message will be displayed in
     * place of any custom text that was set through {@link StatefulBuilder#setStatusText}.
     */
    public static final int STATUS_DISABLED = 4;

    private final @NonNull String mControlId;
    private final @DeviceTypes.DeviceType int mDeviceType;
    private final @NonNull CharSequence mTitle;
    private final @NonNull CharSequence mSubtitle;
    private final @Nullable CharSequence mStructure;
    private final @Nullable CharSequence mZone;
    private final @NonNull PendingIntent mAppIntent;

    private final @Nullable Icon mCustomIcon;
    private final @Nullable ColorStateList mCustomColor;

    private final @Status int mStatus;
    private final @NonNull ControlTemplate mControlTemplate;
    private final @NonNull CharSequence mStatusText;
    private final boolean mAuthRequired;

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
     * @param customIcon
     * @param customColor
     * @param status
     * @param controlTemplate
     * @param statusText
     * @param authRequired true if the control can not be interacted with until the device is
     *                     unlocked
     */
    Control(@NonNull String controlId,
            @DeviceTypes.DeviceType int deviceType,
            @NonNull CharSequence title,
            @NonNull CharSequence subtitle,
            @Nullable CharSequence structure,
            @Nullable CharSequence zone,
            @NonNull PendingIntent appIntent,
            @Nullable Icon customIcon,
            @Nullable ColorStateList customColor,
            @Status int status,
            @NonNull ControlTemplate controlTemplate,
            @NonNull CharSequence statusText,
            boolean authRequired) {
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

        mCustomColor = customColor;
        mCustomIcon = customIcon;

        if (status < 0 || status >= NUM_STATUS) {
            mStatus = STATUS_UNKNOWN;
            Log.e(TAG, "Status unknown:" + status);
        } else {
            mStatus = status;
        }
        mControlTemplate = controlTemplate;
        mStatusText = statusText;
        mAuthRequired = authRequired;
    }

    /**
     * @param in
     * @hide
     */
    Control(Parcel in) {
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

        if (in.readByte() == (byte) 1) {
            mCustomIcon = Icon.CREATOR.createFromParcel(in);
        } else {
            mCustomIcon = null;
        }

        if (in.readByte() == (byte) 1) {
            mCustomColor = ColorStateList.CREATOR.createFromParcel(in);
        } else {
            mCustomColor = null;
        }

        mStatus = in.readInt();
        ControlTemplateWrapper wrapper = ControlTemplateWrapper.CREATOR.createFromParcel(in);
        mControlTemplate = wrapper.getWrappedTemplate();
        mStatusText = in.readCharSequence();
        mAuthRequired = in.readBoolean();
    }

    /**
     * @return the identifier for the {@link Control}
     */
    @NonNull
    public String getControlId() {
        return mControlId;
    }


    /**
     * @return type of device represented by this {@link Control}, used to determine the default
     *         icon and color
     */
    @DeviceTypes.DeviceType
    public int getDeviceType() {
        return mDeviceType;
    }

    /**
     * @return the user facing name of the {@link Control}
     */
    @NonNull
    public CharSequence getTitle() {
        return mTitle;
    }

    /**
     * @return additional information about the {@link Control}, to appear underneath the title
     */
    @NonNull
    public CharSequence getSubtitle() {
        return mSubtitle;
    }

    /**
     * Optional top-level group to help define the {@link Control}'s location, visible to the user.
     * If not present, the application name will be used as the top-level group. A structure
     * contains zones which contains controls.
     *
     * @return name of the structure containing the control
     */
    @Nullable
    public CharSequence getStructure() {
        return mStructure;
    }

    /**
     * Optional group name to help define the {@link Control}'s location within a structure,
     * visible to the user. A structure contains zones which contains controls.
     *
     * @return name of the zone containing the control
     */
    @Nullable
    public CharSequence getZone() {
        return mZone;
    }

    /**
     * @return a {@link PendingIntent} linking to an Activity for the {@link Control}
     */
    @NonNull
    public PendingIntent getAppIntent() {
        return mAppIntent;
    }

    /**
     * Optional icon to be shown with the {@link Control}. It is highly recommended
     * to let the system default the icon unless the default icon is not suitable.
     *
     * @return icon to show
     */
    @Nullable
    public Icon getCustomIcon() {
        return mCustomIcon;
    }

    /**
     * Optional color to be shown with the {@link Control}. It is highly recommended
     * to let the system default the color unless the default is not suitable for the
     * application.
     *
     * @return background color to use
     */
    @Nullable
    public ColorStateList getCustomColor() {
        return mCustomColor;
    }

    /**
     * @return status of the {@link Control}, used to convey information about the attempt to
     *         fetch the current state
     */
    @Status
    public int getStatus() {
        return mStatus;
    }

    /**
     * @return instance of {@link ControlTemplate}, that defines how the {@link Control} will
     *         behave and what interactions are available to the user
     */
    @NonNull
    public ControlTemplate getControlTemplate() {
        return mControlTemplate;
    }

    /**
     * @return user-facing text description of the {@link Control}'s status, describing its current
     *         state
     */
    @NonNull
    public CharSequence getStatusText() {
        return mStatusText;
    }

    /**
     * @return true if the control can not be interacted with until the device is unlocked
     */
    public boolean isAuthRequired() {
        return mAuthRequired;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
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
        if (mCustomIcon != null) {
            dest.writeByte((byte) 1);
            mCustomIcon.writeToParcel(dest, flags);
        } else {
            dest.writeByte((byte) 0);
        }
        if (mCustomColor != null) {
            dest.writeByte((byte) 1);
            mCustomColor.writeToParcel(dest, flags);
        } else {
            dest.writeByte((byte) 0);
        }

        dest.writeInt(mStatus);
        new ControlTemplateWrapper(mControlTemplate).writeToParcel(dest, flags);
        dest.writeCharSequence(mStatusText);
        dest.writeBoolean(mAuthRequired);
    }

    public static final @NonNull Creator<Control> CREATOR = new Creator<Control>() {
        @Override
        public Control createFromParcel(@NonNull Parcel source) {
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
     * This class facilitates the creation of {@link Control} with no state. Must be used to
     * provide controls for {@link ControlsProviderService#createPublisherForAllAvailable} and
     * {@link ControlsProviderService#createPublisherForSuggested}.
     *
     * It provides the following defaults for non-optional parameters:
     * <ul>
     *     <li> Device type: {@link DeviceTypes#TYPE_UNKNOWN}
     *     <li> Title: {@code ""}
     *     <li> Subtitle: {@code ""}
     * </ul>
     * This fixes the values relating to state of the {@link Control} as required by
     * {@link ControlsProviderService#createPublisherForAllAvailable}:
     * <ul>
     *     <li> Status: {@link #STATUS_UNKNOWN}
     *     <li> Control template: {@link ControlTemplate#getNoTemplateObject}
     *     <li> Status text: {@code ""}
     *     <li> Auth Required: {@code true}
     * </ul>
     */
    @SuppressLint("MutableBareField")
    public static final class StatelessBuilder {
        private static final String TAG = "StatelessBuilder";
        private @NonNull String mControlId;
        private @DeviceTypes.DeviceType int mDeviceType = DeviceTypes.TYPE_UNKNOWN;
        private @NonNull CharSequence mTitle = "";
        private @NonNull CharSequence mSubtitle = "";
        private @Nullable CharSequence mStructure;
        private @Nullable CharSequence mZone;
        private @NonNull PendingIntent mAppIntent;
        private @Nullable Icon mCustomIcon;
        private @Nullable ColorStateList mCustomColor;

        /**
         * @param controlId the identifier for the {@link Control}
         * @param appIntent the pending intent linking to the device Activity
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
         *
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
            mCustomIcon = control.mCustomIcon;
            mCustomColor = control.mCustomColor;
        }

        /**
         * @param controlId the identifier for the {@link Control}
         * @return {@code this}
         */
        @NonNull
        public StatelessBuilder setControlId(@NonNull String controlId) {
            Preconditions.checkNotNull(controlId);
            mControlId = controlId;
            return this;
        }

        /**
         * @param deviceType type of device represented by this {@link Control}, used to
         *                   determine the default icon and color
         * @return {@code this}
         */
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

        /**
         * @param subtitle additional information about the {@link Control}, to appear underneath
         *                 the title
         * @return {@code this}
         */
        @NonNull
        public StatelessBuilder setSubtitle(@NonNull CharSequence subtitle) {
            Preconditions.checkNotNull(subtitle);
            mSubtitle = subtitle;
            return this;
        }

        /**
         * Optional top-level group to help define the {@link Control}'s location, visible to the
         * user. If not present, the application name will be used as the top-level group. A
         * structure contains zones which contains controls.
         *
         * @param structure name of the structure containing the control
         * @return {@code this}
         */
        @NonNull
        public StatelessBuilder setStructure(@Nullable CharSequence structure) {
            mStructure = structure;
            return this;
        }

        /**
         * Optional group name to help define the {@link Control}'s location within a structure,
         * visible to the user. A structure contains zones which contains controls.
         *
         * @param zone name of the zone containing the control
         * @return {@code this}
         */
        @NonNull
        public StatelessBuilder setZone(@Nullable CharSequence zone) {
            mZone = zone;
            return this;
        }

        /**
         * @param appIntent a {@link PendingIntent} linking to an Activity for the {@link Control}
         * @return {@code this}
         */
        @NonNull
        public StatelessBuilder setAppIntent(@NonNull PendingIntent appIntent) {
            Preconditions.checkNotNull(appIntent);
            mAppIntent = appIntent;
            return this;
        }

        /**
         * Optional icon to be shown with the {@link Control}. It is highly recommended
         * to let the system default the icon unless the default icon is not suitable.
         *
         * @param customIcon icon to show
         * @return {@code this}
         */
        @NonNull
        public StatelessBuilder setCustomIcon(@Nullable Icon customIcon) {
            mCustomIcon = customIcon;
            return this;
        }

        /**
         * Optional color to be shown with the {@link Control}. It is highly recommended
         * to let the system default the color unless the default is not suitable for the
         * application.
         *
         * @param customColor background color to use
         * @return {@code this}
         */
        @NonNull
        public StatelessBuilder setCustomColor(@Nullable ColorStateList customColor) {
            mCustomColor = customColor;
            return this;
        }

        /**
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
                    mCustomIcon,
                    mCustomColor,
                    STATUS_UNKNOWN,
                    ControlTemplate.NO_TEMPLATE,
                    "",
                    true /* authRequired */);
        }
    }

    /**
     * Builder class for {@link Control} that contains state information.
     *
     * State information is passed through an instance of a {@link ControlTemplate} and will
     * determine how the user can interact with the {@link Control}. User interactions will
     * be sent through the method call {@link ControlsProviderService#performControlAction}
     * with an instance of {@link ControlAction} to convey any potential new value.
     *
     * Must be used to provide controls for {@link ControlsProviderService#createPublisherFor}.
     *
     * It provides the following defaults for non-optional parameters:
     * <ul>
     *     <li> Device type: {@link DeviceTypes#TYPE_UNKNOWN}
     *     <li> Title: {@code ""}
     *     <li> Subtitle: {@code ""}
     *     <li> Status: {@link #STATUS_UNKNOWN}
     *     <li> Control template: {@link ControlTemplate#getNoTemplateObject}
     *     <li> Status text: {@code ""}
     *     <li> Auth Required: {@code true}
     * </ul>
     */
    public static final class StatefulBuilder {
        private static final String TAG = "StatefulBuilder";
        private @NonNull String mControlId;
        private @DeviceTypes.DeviceType int mDeviceType = DeviceTypes.TYPE_UNKNOWN;
        private @NonNull CharSequence mTitle = "";
        private @NonNull CharSequence mSubtitle = "";
        private @Nullable CharSequence mStructure;
        private @Nullable CharSequence mZone;
        private @NonNull PendingIntent mAppIntent;
        private @Nullable Icon mCustomIcon;
        private @Nullable ColorStateList mCustomColor;
        private @Status int mStatus = STATUS_UNKNOWN;
        private @NonNull ControlTemplate mControlTemplate = ControlTemplate.NO_TEMPLATE;
        private @NonNull CharSequence mStatusText = "";
        private boolean mAuthRequired = true;

        /**
         * @param controlId the identifier for the {@link Control}.
         * @param appIntent the pending intent linking to the device Activity.
         */
        public StatefulBuilder(@NonNull String controlId,
                @NonNull PendingIntent appIntent) {
            Preconditions.checkNotNull(controlId);
            Preconditions.checkNotNull(appIntent);
            mControlId = controlId;
            mAppIntent = appIntent;
        }

        /**
         * Creates a {@link StatelessBuilder} using an existing {@link Control} as a base.
         *
         * @param control base for the builder.
         */
        public StatefulBuilder(@NonNull Control control) {
            Preconditions.checkNotNull(control);
            mControlId = control.mControlId;
            mDeviceType = control.mDeviceType;
            mTitle = control.mTitle;
            mSubtitle = control.mSubtitle;
            mStructure = control.mStructure;
            mZone = control.mZone;
            mAppIntent = control.mAppIntent;
            mCustomIcon = control.mCustomIcon;
            mCustomColor = control.mCustomColor;
            mStatus = control.mStatus;
            mControlTemplate = control.mControlTemplate;
            mStatusText = control.mStatusText;
            mAuthRequired = control.mAuthRequired;
        }

        /**
         * @param controlId the identifier for the {@link Control}.
         * @return {@code this}
         */
        @NonNull
        public StatefulBuilder setControlId(@NonNull String controlId) {
            Preconditions.checkNotNull(controlId);
            mControlId = controlId;
            return this;
        }

        /**
         * @param deviceType type of device represented by this {@link Control}, used to
         *                   determine the default icon and color
         * @return {@code this}
         */
        @NonNull
        public StatefulBuilder setDeviceType(@DeviceTypes.DeviceType int deviceType) {
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
        public StatefulBuilder setTitle(@NonNull CharSequence title) {
            Preconditions.checkNotNull(title);
            mTitle = title;
            return this;
        }

        /**
         * @param subtitle additional information about the {@link Control}, to appear underneath
         *                 the title
         * @return {@code this}
         */
        @NonNull
        public StatefulBuilder setSubtitle(@NonNull CharSequence subtitle) {
            Preconditions.checkNotNull(subtitle);
            mSubtitle = subtitle;
            return this;
        }

        /**
         * Optional top-level group to help define the {@link Control}'s location, visible to the
         * user. If not present, the application name will be used as the top-level group. A
         * structure contains zones which contains controls.
         *
         * @param structure name of the structure containing the control
         * @return {@code this}
         */
        @NonNull
        public StatefulBuilder setStructure(@Nullable CharSequence structure) {
            mStructure = structure;
            return this;
        }

        /**
         * Optional group name to help define the {@link Control}'s location within a structure,
         * visible to the user. A structure contains zones which contains controls.
         *
         * @param zone name of the zone containing the control
         * @return {@code this}
         */
        @NonNull
        public StatefulBuilder setZone(@Nullable CharSequence zone) {
            mZone = zone;
            return this;
        }

        /**
         * @param appIntent a {@link PendingIntent} linking to an Activity for the {@link Control}
         * @return {@code this}
         */
        @NonNull
        public StatefulBuilder setAppIntent(@NonNull PendingIntent appIntent) {
            Preconditions.checkNotNull(appIntent);
            mAppIntent = appIntent;
            return this;
        }

        /**
         * Optional icon to be shown with the {@link Control}. It is highly recommended
         * to let the system default the icon unless the default icon is not suitable.
         *
         * @param customIcon icon to show
         * @return {@code this}
         */
        @NonNull
        public StatefulBuilder setCustomIcon(@Nullable Icon customIcon) {
            mCustomIcon = customIcon;
            return this;
        }

        /**
         * Optional color to be shown with the {@link Control}. It is highly recommended
         * to let the system default the color unless the default is not suitable for the
         * application.
         *
         * @param customColor background color to use
         * @return {@code this}
         */
        @NonNull
        public StatefulBuilder setCustomColor(@Nullable ColorStateList customColor) {
            mCustomColor = customColor;
            return this;
        }

        /**
         * @param status status of the {@link Control}, used to convey information about the
         *               attempt to fetch the current state
         * @return {@code this}
         */
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

        /**
         * Set the {@link ControlTemplate} to define the primary user interaction
         *
         * Devices may support a variety of user interactions, and all interactions cannot be
         * represented with a single {@link ControlTemplate}. Therefore, the selected template
         * should be most closely aligned with what the expected primary device action will be.
         * Any secondary interactions can be done via the {@link #setAppIntent(PendingIntent)}.
         *
         * @param controlTemplate instance of {@link ControlTemplate}, that defines how the
         *                        {@link Control} will behave and what interactions are
         *                        available to the user
         * @return {@code this}
         */
        @NonNull
        public StatefulBuilder setControlTemplate(@NonNull ControlTemplate controlTemplate) {
            Preconditions.checkNotNull(controlTemplate);
            mControlTemplate = controlTemplate;
            return this;
        }

        /**
         * @param statusText user-facing text description of the {@link Control}'s status,
         *                   describing its current state
         * @return {@code this}
         */
        @NonNull
        public StatefulBuilder setStatusText(@NonNull CharSequence statusText) {
            Preconditions.checkNotNull(statusText);
            mStatusText = statusText;
            return this;
        }

        /**
         * @param authRequired true if the control can not be interacted with until the device is
         *                     unlocked
         * @return {@code this}
         */
        @NonNull
        public StatefulBuilder setAuthRequired(boolean authRequired) {
            mAuthRequired = authRequired;
            return this;
        }

        /**
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
                    mCustomIcon,
                    mCustomColor,
                    mStatus,
                    mControlTemplate,
                    mStatusText,
                    mAuthRequired);
        }
    }
}
