/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.media.tv;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.StringRes;
import android.annotation.SystemApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.HdmiUtils;
import android.hardware.hdmi.HdmiUtils.HdmiAddressRelativePosition;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseIntArray;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * This class is used to specify meta information of a TV input.
 */
public final class TvInputInfo implements Parcelable {
    private static final boolean DEBUG = false;
    private static final String TAG = "TvInputInfo";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({TYPE_TUNER, TYPE_OTHER, TYPE_COMPOSITE, TYPE_SVIDEO, TYPE_SCART, TYPE_COMPONENT,
            TYPE_VGA, TYPE_DVI, TYPE_HDMI, TYPE_DISPLAY_PORT})
    public @interface Type {}

    // Should be in sync with frameworks/base/core/res/res/values/attrs.xml
    /**
     * TV input type: the TV input service is a tuner which provides channels.
     */
    public static final int TYPE_TUNER = 0;
    /**
     * TV input type: a generic hardware TV input type.
     */
    public static final int TYPE_OTHER = 1000;
    /**
     * TV input type: the TV input service represents a composite port.
     */
    public static final int TYPE_COMPOSITE = 1001;
    /**
     * TV input type: the TV input service represents a SVIDEO port.
     */
    public static final int TYPE_SVIDEO = 1002;
    /**
     * TV input type: the TV input service represents a SCART port.
     */
    public static final int TYPE_SCART = 1003;
    /**
     * TV input type: the TV input service represents a component port.
     */
    public static final int TYPE_COMPONENT = 1004;
    /**
     * TV input type: the TV input service represents a VGA port.
     */
    public static final int TYPE_VGA = 1005;
    /**
     * TV input type: the TV input service represents a DVI port.
     */
    public static final int TYPE_DVI = 1006;
    /**
     * TV input type: the TV input service is HDMI. (e.g. HDMI 1)
     */
    public static final int TYPE_HDMI = 1007;
    /**
     * TV input type: the TV input service represents a display port.
     */
    public static final int TYPE_DISPLAY_PORT = 1008;

    /**
     * Used as a String extra field in setup intents created by {@link #createSetupIntent()} to
     * supply the ID of a specific TV input to set up.
     */
    public static final String EXTRA_INPUT_ID = "android.media.tv.extra.INPUT_ID";

    private final ResolveInfo mService;

    private final String mId;
    private final int mType;
    private final boolean mIsHardwareInput;

    // TODO: Remove mIconUri when createTvInputInfo() is removed.
    private Uri mIconUri;

    private final CharSequence mLabel;
    private final int mLabelResId;
    private final Icon mIcon;
    private final Icon mIconStandby;
    private final Icon mIconDisconnected;

    // Attributes from XML meta data.
    private final String mSetupActivity;
    private final boolean mCanRecord;
    private final int mTunerCount;

    // Attributes specific to HDMI
    private final HdmiDeviceInfo mHdmiDeviceInfo;
    private final boolean mIsConnectedToHdmiSwitch;
    @HdmiAddressRelativePosition
    private final int mHdmiConnectionRelativePosition;
    private final String mParentId;

    private final Bundle mExtras;

    /**
     * Create a new instance of the TvInputInfo class, instantiating it from the given Context,
     * ResolveInfo, and HdmiDeviceInfo.
     *
     * @param service The ResolveInfo returned from the package manager about this TV input service.
     * @param hdmiDeviceInfo The HdmiDeviceInfo for a HDMI CEC logical device.
     * @param parentId The ID of this TV input's parent input. {@code null} if none exists.
     * @param label The label of this TvInputInfo. If it is {@code null} or empty, {@code service}
     *            label will be loaded.
     * @param iconUri The {@link android.net.Uri} to load the icon image. See
     *            {@link android.content.ContentResolver#openInputStream}. If it is {@code null},
     *            the application icon of {@code service} will be loaded.
     * @hide
     * @deprecated Use {@link Builder} instead.
     */
    @Deprecated
    @SystemApi
    public static TvInputInfo createTvInputInfo(Context context, ResolveInfo service,
            HdmiDeviceInfo hdmiDeviceInfo, String parentId, String label, Uri iconUri)
                    throws XmlPullParserException, IOException {
        TvInputInfo info = new TvInputInfo.Builder(context, service)
                .setHdmiDeviceInfo(hdmiDeviceInfo)
                .setParentId(parentId)
                .setLabel(label)
                .build();
        info.mIconUri = iconUri;
        return info;
    }

    /**
     * Create a new instance of the TvInputInfo class, instantiating it from the given Context,
     * ResolveInfo, and HdmiDeviceInfo.
     *
     * @param service The ResolveInfo returned from the package manager about this TV input service.
     * @param hdmiDeviceInfo The HdmiDeviceInfo for a HDMI CEC logical device.
     * @param parentId The ID of this TV input's parent input. {@code null} if none exists.
     * @param labelRes The label resource ID of this TvInputInfo. If it is {@code 0},
     *            {@code service} label will be loaded.
     * @param icon The {@link android.graphics.drawable.Icon} to load the icon image. If it is
     *            {@code null}, the application icon of {@code service} will be loaded.
     * @hide
     * @deprecated Use {@link Builder} instead.
     */
    @Deprecated
    @SystemApi
    public static TvInputInfo createTvInputInfo(Context context, ResolveInfo service,
            HdmiDeviceInfo hdmiDeviceInfo, String parentId, int labelRes, Icon icon)
            throws XmlPullParserException, IOException {
        return new TvInputInfo.Builder(context, service)
                .setHdmiDeviceInfo(hdmiDeviceInfo)
                .setParentId(parentId)
                .setLabel(labelRes)
                .setIcon(icon)
                .build();
    }

    /**
     * Create a new instance of the TvInputInfo class, instantiating it from the given Context,
     * ResolveInfo, and TvInputHardwareInfo.
     *
     * @param service The ResolveInfo returned from the package manager about this TV input service.
     * @param hardwareInfo The TvInputHardwareInfo for a TV input hardware device.
     * @param label The label of this TvInputInfo. If it is {@code null} or empty, {@code service}
     *            label will be loaded.
     * @param iconUri The {@link android.net.Uri} to load the icon image. See
     *            {@link android.content.ContentResolver#openInputStream}. If it is {@code null},
     *            the application icon of {@code service} will be loaded.
     * @hide
     * @deprecated Use {@link Builder} instead.
     */
    @Deprecated
    @SystemApi
    public static TvInputInfo createTvInputInfo(Context context, ResolveInfo service,
            TvInputHardwareInfo hardwareInfo, String label, Uri iconUri)
                    throws XmlPullParserException, IOException {
        TvInputInfo info = new TvInputInfo.Builder(context, service)
                .setTvInputHardwareInfo(hardwareInfo)
                .setLabel(label)
                .build();
        info.mIconUri = iconUri;
        return info;
    }

    /**
     * Create a new instance of the TvInputInfo class, instantiating it from the given Context,
     * ResolveInfo, and TvInputHardwareInfo.
     *
     * @param service The ResolveInfo returned from the package manager about this TV input service.
     * @param hardwareInfo The TvInputHardwareInfo for a TV input hardware device.
     * @param labelRes The label resource ID of this TvInputInfo. If it is {@code 0},
     *            {@code service} label will be loaded.
     * @param icon The {@link android.graphics.drawable.Icon} to load the icon image. If it is
     *            {@code null}, the application icon of {@code service} will be loaded.
     * @hide
     * @deprecated Use {@link Builder} instead.
     */
    @Deprecated
    @SystemApi
    public static TvInputInfo createTvInputInfo(Context context, ResolveInfo service,
            TvInputHardwareInfo hardwareInfo, int labelRes, Icon icon)
            throws XmlPullParserException, IOException {
        return new TvInputInfo.Builder(context, service)
                .setTvInputHardwareInfo(hardwareInfo)
                .setLabel(labelRes)
                .setIcon(icon)
                .build();
    }

    private TvInputInfo(ResolveInfo service, String id, int type, boolean isHardwareInput,
            CharSequence label, int labelResId, Icon icon, Icon iconStandby, Icon iconDisconnected,
            String setupActivity, boolean canRecord, int tunerCount, HdmiDeviceInfo hdmiDeviceInfo,
            boolean isConnectedToHdmiSwitch,
            @HdmiAddressRelativePosition int hdmiConnectionRelativePosition, String parentId,
            Bundle extras) {
        mService = service;
        mId = id;
        mType = type;
        mIsHardwareInput = isHardwareInput;
        mLabel = label;
        mLabelResId = labelResId;
        mIcon = icon;
        mIconStandby = iconStandby;
        mIconDisconnected = iconDisconnected;
        mSetupActivity = setupActivity;
        mCanRecord = canRecord;
        mTunerCount = tunerCount;
        mHdmiDeviceInfo = hdmiDeviceInfo;
        mIsConnectedToHdmiSwitch = isConnectedToHdmiSwitch;
        mHdmiConnectionRelativePosition = hdmiConnectionRelativePosition;
        mParentId = parentId;
        mExtras = extras;
    }

    /**
     * Returns a unique ID for this TV input. The ID is generated from the package and class name
     * implementing the TV input service.
     */
    public String getId() {
        return mId;
    }

    /**
     * Returns the parent input ID.
     *
     * <p>A TV input may have a parent input if the TV input is actually a logical representation of
     * a device behind the hardware port represented by the parent input.
     * For example, a HDMI CEC logical device, connected to a HDMI port, appears as another TV
     * input. In this case, the parent input of this logical device is the HDMI port.
     *
     * <p>Applications may group inputs by parent input ID to provide an easier access to inputs
     * sharing the same physical port. In the example of HDMI CEC, logical HDMI CEC devices behind
     * the same HDMI port have the same parent ID, which is the ID representing the port. Thus
     * applications can group the hardware HDMI port and the logical HDMI CEC devices behind it
     * together using this method.
     *
     * @return the ID of the parent input, if exists. Returns {@code null} if the parent input is
     *         not specified.
     */
    public String getParentId() {
        return mParentId;
    }

    /**
     * Returns the information of the service that implements this TV input.
     */
    public ServiceInfo getServiceInfo() {
        return mService.serviceInfo;
    }

    /**
     * Returns the component of the service that implements this TV input.
     * @hide
     */
    @UnsupportedAppUsage
    public ComponentName getComponent() {
        return new ComponentName(mService.serviceInfo.packageName, mService.serviceInfo.name);
    }

    /**
     * Returns an intent to start the setup activity for this TV input.
     */
    public Intent createSetupIntent() {
        if (!TextUtils.isEmpty(mSetupActivity)) {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setClassName(mService.serviceInfo.packageName, mSetupActivity);
            intent.putExtra(EXTRA_INPUT_ID, getId());
            return intent;
        }
        return null;
    }

    /**
     * Returns an intent to start the settings activity for this TV input.
     *
     * @deprecated Use {@link #createSetupIntent()} instead. Settings activity is deprecated.
     *             Use setup activity instead to provide settings.
     */
    @Deprecated
    public Intent createSettingsIntent() {
        return null;
    }

    /**
     * Returns the type of this TV input.
     */
    @Type
    public int getType() {
        return mType;
    }

    /**
     * Returns the number of tuners this TV input has.
     *
     * <p>This method is valid only for inputs of type {@link #TYPE_TUNER}. For inputs of other
     * types, it returns 0.
     *
     * <p>Tuners correspond to physical/logical resources that allow reception of TV signal. Having
     * <i>N</i> tuners means that the TV input is capable of receiving <i>N</i> different channels
     * concurrently.
     */
    public int getTunerCount() {
        return mTunerCount;
    }

    /**
     * Returns {@code true} if this TV input can record TV programs, {@code false} otherwise.
     */
    public boolean canRecord() {
        return mCanRecord;
    }

    /**
     * Returns domain-specific extras associated with this TV input.
     */
    public Bundle getExtras() {
        return mExtras;
    }

    /**
     * Returns the HDMI device information of this TV input.
     * @hide
     */
    @SystemApi
    public HdmiDeviceInfo getHdmiDeviceInfo() {
        if (mType == TYPE_HDMI) {
            return mHdmiDeviceInfo;
        }
        return null;
    }

    /**
     * Returns {@code true} if this TV input is pass-though which does not have any real channels in
     * TvProvider. {@code false} otherwise.
     *
     * @see TvContract#buildChannelUriForPassthroughInput(String)
     */
    public boolean isPassthroughInput() {
        return mType != TYPE_TUNER;
    }

    /**
     * Returns {@code true} if this TV input represents a hardware device. (e.g. built-in tuner,
     * HDMI1) {@code false} otherwise.
     * @hide
     */
    @SystemApi
    public boolean isHardwareInput() {
        return mIsHardwareInput;
    }

    /**
     * Returns {@code true}, if a CEC device for this TV input is connected to an HDMI switch, i.e.,
     * the device isn't directly connected to a HDMI port.
     * TODO(b/110094868): add @Deprecated for Q
     * @hide
     */
    @SystemApi
    public boolean isConnectedToHdmiSwitch() {
        return mIsConnectedToHdmiSwitch;
    }

    /**
     * Returns the relative position of this HDMI input.
     * TODO(b/110094868): unhide for Q
     * @hide
     */
    @HdmiAddressRelativePosition
    public int getHdmiConnectionRelativePosition() {
        return mHdmiConnectionRelativePosition;
    }

    /**
     * Checks if this TV input is marked hidden by the user in the settings.
     *
     * @param context Supplies a {@link Context} used to check if this TV input is hidden.
     * @return {@code true} if the user marked this TV input hidden in settings. {@code false}
     *         otherwise.
     */
    public boolean isHidden(Context context) {
        return TvInputSettings.isHidden(context, mId, UserHandle.myUserId());
    }

    /**
     * Loads the user-displayed label for this TV input.
     *
     * @param context Supplies a {@link Context} used to load the label.
     * @return a CharSequence containing the TV input's label. If the TV input does not have
     *         a label, its name is returned.
     */
    public CharSequence loadLabel(@NonNull Context context) {
        if (mLabelResId != 0) {
            return context.getPackageManager().getText(mService.serviceInfo.packageName,
                    mLabelResId, null);
        } else if (!TextUtils.isEmpty(mLabel)) {
            return mLabel;
        }
        return mService.loadLabel(context.getPackageManager());
    }

    /**
     * Loads the custom label set by user in settings.
     *
     * @param context Supplies a {@link Context} used to load the custom label.
     * @return a CharSequence containing the TV input's custom label. {@code null} if there is no
     *         custom label.
     */
    public CharSequence loadCustomLabel(Context context) {
        return TvInputSettings.getCustomLabel(context, mId, UserHandle.myUserId());
    }

    /**
     * Loads the user-displayed icon for this TV input.
     *
     * @param context Supplies a {@link Context} used to load the icon.
     * @return a Drawable containing the TV input's icon. If the TV input does not have an icon,
     *         application's icon is returned. If it's unavailable too, {@code null} is returned.
     */
    public Drawable loadIcon(@NonNull Context context) {
        if (mIcon != null) {
            return mIcon.loadDrawable(context);
        } else if (mIconUri != null) {
            try (InputStream is = context.getContentResolver().openInputStream(mIconUri)) {
                Drawable drawable = Drawable.createFromStream(is, null);
                if (drawable != null) {
                    return drawable;
                }
            } catch (IOException e) {
                Log.w(TAG, "Loading the default icon due to a failure on loading " + mIconUri, e);
                // Falls back.
            }
        }
        return loadServiceIcon(context);
    }

    /**
     * Loads the user-displayed icon for this TV input per input state.
     *
     * @param context Supplies a {@link Context} used to load the icon.
     * @param state The input state. Should be one of the followings.
     *              {@link TvInputManager#INPUT_STATE_CONNECTED},
     *              {@link TvInputManager#INPUT_STATE_CONNECTED_STANDBY} and
     *              {@link TvInputManager#INPUT_STATE_DISCONNECTED}.
     * @return a Drawable containing the TV input's icon for the given state or {@code null} if such
     *         an icon is not defined.
     * @hide
     */
    @SystemApi
    public Drawable loadIcon(@NonNull Context context, int state) {
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            return loadIcon(context);
        } else if (state == TvInputManager.INPUT_STATE_CONNECTED_STANDBY) {
            if (mIconStandby != null) {
                return mIconStandby.loadDrawable(context);
            }
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            if (mIconDisconnected != null) {
                return mIconDisconnected.loadDrawable(context);
            }
        } else {
            throw new IllegalArgumentException("Unknown state: " + state);
        }
        return null;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public int hashCode() {
        return mId.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }

        if (!(o instanceof TvInputInfo)) {
            return false;
        }

        TvInputInfo obj = (TvInputInfo) o;
        return Objects.equals(mService, obj.mService)
                && TextUtils.equals(mId, obj.mId)
                && mType == obj.mType
                && mIsHardwareInput == obj.mIsHardwareInput
                && TextUtils.equals(mLabel, obj.mLabel)
                && Objects.equals(mIconUri, obj.mIconUri)
                && mLabelResId == obj.mLabelResId
                && Objects.equals(mIcon, obj.mIcon)
                && Objects.equals(mIconStandby, obj.mIconStandby)
                && Objects.equals(mIconDisconnected, obj.mIconDisconnected)
                && TextUtils.equals(mSetupActivity, obj.mSetupActivity)
                && mCanRecord == obj.mCanRecord
                && mTunerCount == obj.mTunerCount
                && Objects.equals(mHdmiDeviceInfo, obj.mHdmiDeviceInfo)
                && mIsConnectedToHdmiSwitch == obj.mIsConnectedToHdmiSwitch
                && mHdmiConnectionRelativePosition == obj.mHdmiConnectionRelativePosition
                && TextUtils.equals(mParentId, obj.mParentId)
                && Objects.equals(mExtras, obj.mExtras);
    }

    @Override
    public String toString() {
        return "TvInputInfo{id=" + mId
                + ", pkg=" + mService.serviceInfo.packageName
                + ", service=" + mService.serviceInfo.name + "}";
    }

    /**
     * Used to package this object into a {@link Parcel}.
     *
     * @param dest The {@link Parcel} to be written.
     * @param flags The flags used for parceling.
     */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        mService.writeToParcel(dest, flags);
        dest.writeString(mId);
        dest.writeInt(mType);
        dest.writeByte(mIsHardwareInput ? (byte) 1 : 0);
        TextUtils.writeToParcel(mLabel, dest, flags);
        dest.writeParcelable(mIconUri, flags);
        dest.writeInt(mLabelResId);
        dest.writeParcelable(mIcon, flags);
        dest.writeParcelable(mIconStandby, flags);
        dest.writeParcelable(mIconDisconnected, flags);
        dest.writeString(mSetupActivity);
        dest.writeByte(mCanRecord ? (byte) 1 : 0);
        dest.writeInt(mTunerCount);
        dest.writeParcelable(mHdmiDeviceInfo, flags);
        dest.writeByte(mIsConnectedToHdmiSwitch ? (byte) 1 : 0);
        dest.writeInt(mHdmiConnectionRelativePosition);
        dest.writeString(mParentId);
        dest.writeBundle(mExtras);
    }

    private Drawable loadServiceIcon(Context context) {
        if (mService.serviceInfo.icon == 0
                && mService.serviceInfo.applicationInfo.icon == 0) {
            return null;
        }
        return mService.serviceInfo.loadIcon(context.getPackageManager());
    }

    public static final @android.annotation.NonNull Parcelable.Creator<TvInputInfo> CREATOR =
            new Parcelable.Creator<TvInputInfo>() {
        @Override
        public TvInputInfo createFromParcel(Parcel in) {
            return new TvInputInfo(in);
        }

        @Override
        public TvInputInfo[] newArray(int size) {
            return new TvInputInfo[size];
        }
    };

    private TvInputInfo(Parcel in) {
        mService = ResolveInfo.CREATOR.createFromParcel(in);
        mId = in.readString();
        mType = in.readInt();
        mIsHardwareInput = in.readByte() == 1;
        mLabel = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
        mIconUri = in.readParcelable(null);
        mLabelResId = in.readInt();
        mIcon = in.readParcelable(null);
        mIconStandby = in.readParcelable(null);
        mIconDisconnected = in.readParcelable(null);
        mSetupActivity = in.readString();
        mCanRecord = in.readByte() == 1;
        mTunerCount = in.readInt();
        mHdmiDeviceInfo = in.readParcelable(null);
        mIsConnectedToHdmiSwitch = in.readByte() == 1;
        mHdmiConnectionRelativePosition = in.readInt();
        mParentId = in.readString();
        mExtras = in.readBundle();
    }

    /**
     * A convenience builder for creating {@link TvInputInfo} objects.
     */
    public static final class Builder {
        private static final int LENGTH_HDMI_PHYSICAL_ADDRESS = 4;
        private static final int LENGTH_HDMI_DEVICE_ID = 2;

        private static final String XML_START_TAG_NAME = "tv-input";
        private static final String DELIMITER_INFO_IN_ID = "/";
        private static final String PREFIX_HDMI_DEVICE = "HDMI";
        private static final String PREFIX_HARDWARE_DEVICE = "HW";

        private static final SparseIntArray sHardwareTypeToTvInputType = new SparseIntArray();
        static {
            sHardwareTypeToTvInputType.put(TvInputHardwareInfo.TV_INPUT_TYPE_OTHER_HARDWARE,
                    TYPE_OTHER);
            sHardwareTypeToTvInputType.put(TvInputHardwareInfo.TV_INPUT_TYPE_TUNER, TYPE_TUNER);
            sHardwareTypeToTvInputType.put(TvInputHardwareInfo.TV_INPUT_TYPE_COMPOSITE,
                    TYPE_COMPOSITE);
            sHardwareTypeToTvInputType.put(TvInputHardwareInfo.TV_INPUT_TYPE_SVIDEO, TYPE_SVIDEO);
            sHardwareTypeToTvInputType.put(TvInputHardwareInfo.TV_INPUT_TYPE_SCART, TYPE_SCART);
            sHardwareTypeToTvInputType.put(TvInputHardwareInfo.TV_INPUT_TYPE_COMPONENT,
                    TYPE_COMPONENT);
            sHardwareTypeToTvInputType.put(TvInputHardwareInfo.TV_INPUT_TYPE_VGA, TYPE_VGA);
            sHardwareTypeToTvInputType.put(TvInputHardwareInfo.TV_INPUT_TYPE_DVI, TYPE_DVI);
            sHardwareTypeToTvInputType.put(TvInputHardwareInfo.TV_INPUT_TYPE_HDMI, TYPE_HDMI);
            sHardwareTypeToTvInputType.put(TvInputHardwareInfo.TV_INPUT_TYPE_DISPLAY_PORT,
                    TYPE_DISPLAY_PORT);
        }

        private final Context mContext;
        private final ResolveInfo mResolveInfo;
        private CharSequence mLabel;
        private int mLabelResId;
        private Icon mIcon;
        private Icon mIconStandby;
        private Icon mIconDisconnected;
        private String mSetupActivity;
        private Boolean mCanRecord;
        private Integer mTunerCount;
        private TvInputHardwareInfo mTvInputHardwareInfo;
        private HdmiDeviceInfo mHdmiDeviceInfo;
        private String mParentId;
        private Bundle mExtras;

        /**
         * Constructs a new builder for {@link TvInputInfo}.
         *
         * @param context A Context of the application package implementing this class.
         * @param component The name of the application component to be used for the
         *            {@link TvInputService}.
         */
        public Builder(Context context, ComponentName component) {
            if (context == null) {
                throw new IllegalArgumentException("context cannot be null.");
            }
            Intent intent = new Intent(TvInputService.SERVICE_INTERFACE).setComponent(component);
            mResolveInfo = context.getPackageManager().resolveService(intent,
                    PackageManager.GET_SERVICES | PackageManager.GET_META_DATA);
            if (mResolveInfo == null) {
                throw new IllegalArgumentException("Invalid component. Can't find the service.");
            }
            mContext = context;
        }

        /**
         * Constructs a new builder for {@link TvInputInfo}.
         *
         * @param resolveInfo The ResolveInfo returned from the package manager about this TV input
         *            service.
         * @hide
         */
        public Builder(Context context, ResolveInfo resolveInfo) {
            if (context == null) {
                throw new IllegalArgumentException("context cannot be null");
            }
            if (resolveInfo == null) {
                throw new IllegalArgumentException("resolveInfo cannot be null");
            }
            mContext = context;
            mResolveInfo = resolveInfo;
        }

        /**
         * Sets the icon.
         *
         * @param icon The icon that represents this TV input.
         * @return This Builder object to allow for chaining of calls to builder methods.
         * @hide
         */
        @SystemApi
        public Builder setIcon(Icon icon) {
            this.mIcon = icon;
            return this;
        }

        /**
         * Sets the icon for a given input state.
         *
         * @param icon The icon that represents this TV input for the given state.
         * @param state The input state. Should be one of the followings.
         *              {@link TvInputManager#INPUT_STATE_CONNECTED},
         *              {@link TvInputManager#INPUT_STATE_CONNECTED_STANDBY} and
         *              {@link TvInputManager#INPUT_STATE_DISCONNECTED}.
         * @return This Builder object to allow for chaining of calls to builder methods.
         * @hide
         */
        @SystemApi
        public Builder setIcon(Icon icon, int state) {
            if (state == TvInputManager.INPUT_STATE_CONNECTED) {
                this.mIcon = icon;
            } else if (state == TvInputManager.INPUT_STATE_CONNECTED_STANDBY) {
                this.mIconStandby = icon;
            } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
                this.mIconDisconnected = icon;
            } else {
                throw new IllegalArgumentException("Unknown state: " + state);
            }
            return this;
        }

        /**
         * Sets the label.
         *
         * @param label The text to be used as label.
         * @return This Builder object to allow for chaining of calls to builder methods.
         * @hide
         */
        @SystemApi
        public Builder setLabel(CharSequence label) {
            if (mLabelResId != 0) {
                throw new IllegalStateException("Resource ID for label is already set.");
            }
            this.mLabel = label;
            return this;
        }

        /**
         * Sets the label.
         *
         * @param resId The resource ID of the text to use.
         * @return This Builder object to allow for chaining of calls to builder methods.
         * @hide
         */
        @SystemApi
        public Builder setLabel(@StringRes int resId) {
            if (mLabel != null) {
                throw new IllegalStateException("Label text is already set.");
            }
            this.mLabelResId = resId;
            return this;
        }

        /**
         * Sets the HdmiDeviceInfo.
         *
         * @param hdmiDeviceInfo The HdmiDeviceInfo for a HDMI CEC logical device.
         * @return This Builder object to allow for chaining of calls to builder methods.
         * @hide
         */
        @SystemApi
        public Builder setHdmiDeviceInfo(HdmiDeviceInfo hdmiDeviceInfo) {
            if (mTvInputHardwareInfo != null) {
                Log.w(TAG, "TvInputHardwareInfo will not be used to build this TvInputInfo");
                mTvInputHardwareInfo = null;
            }
            this.mHdmiDeviceInfo = hdmiDeviceInfo;
            return this;
        }

        /**
         * Sets the parent ID.
         *
         * @param parentId The parent ID.
         * @return This Builder object to allow for chaining of calls to builder methods.
         * @hide
         */
        @SystemApi
        public Builder setParentId(String parentId) {
            this.mParentId = parentId;
            return this;
        }

        /**
         * Sets the TvInputHardwareInfo.
         *
         * @param tvInputHardwareInfo
         * @return This Builder object to allow for chaining of calls to builder methods.
         * @hide
         */
        @SystemApi
        public Builder setTvInputHardwareInfo(TvInputHardwareInfo tvInputHardwareInfo) {
            if (mHdmiDeviceInfo != null) {
                Log.w(TAG, "mHdmiDeviceInfo will not be used to build this TvInputInfo");
                mHdmiDeviceInfo = null;
            }
            this.mTvInputHardwareInfo = tvInputHardwareInfo;
            return this;
        }

        /**
         * Sets the tuner count. Valid only for {@link #TYPE_TUNER}.
         *
         * @param tunerCount The number of tuners this TV input has.
         * @return This Builder object to allow for chaining of calls to builder methods.
         */
        public Builder setTunerCount(int tunerCount) {
            this.mTunerCount = tunerCount;
            return this;
        }

        /**
         * Sets whether this TV input can record TV programs or not.
         *
         * @param canRecord Whether this TV input can record TV programs.
         * @return This Builder object to allow for chaining of calls to builder methods.
         */
        public Builder setCanRecord(boolean canRecord) {
            this.mCanRecord = canRecord;
            return this;
        }

        /**
         * Sets domain-specific extras associated with this TV input.
         *
         * @param extras Domain-specific extras associated with this TV input. Keys <em>must</em> be
         *            a scoped name, i.e. prefixed with a package name you own, so that different
         *            developers will not create conflicting keys.
         * @return This Builder object to allow for chaining of calls to builder methods.
         */
        public Builder setExtras(Bundle extras) {
            this.mExtras = extras;
            return this;
        }

        /**
         * Creates a {@link TvInputInfo} instance with the specified fields. Most of the information
         * is obtained by parsing the AndroidManifest and {@link TvInputService#SERVICE_META_DATA}
         * for the {@link TvInputService} this TV input implements.
         *
         * @return TvInputInfo containing information about this TV input.
         */
        public TvInputInfo build() {
            ComponentName componentName = new ComponentName(mResolveInfo.serviceInfo.packageName,
                    mResolveInfo.serviceInfo.name);
            String id;
            int type;
            boolean isHardwareInput = false;
            boolean isConnectedToHdmiSwitch = false;
            @HdmiAddressRelativePosition
            int hdmiConnectionRelativePosition = HdmiUtils.HDMI_RELATIVE_POSITION_UNKNOWN;

            if (mHdmiDeviceInfo != null) {
                id = generateInputId(componentName, mHdmiDeviceInfo);
                type = TYPE_HDMI;
                isHardwareInput = true;
                hdmiConnectionRelativePosition = getRelativePosition(mContext, mHdmiDeviceInfo);
                isConnectedToHdmiSwitch =
                        hdmiConnectionRelativePosition
                                != HdmiUtils.HDMI_RELATIVE_POSITION_DIRECTLY_BELOW;
            } else if (mTvInputHardwareInfo != null) {
                id = generateInputId(componentName, mTvInputHardwareInfo);
                type = sHardwareTypeToTvInputType.get(mTvInputHardwareInfo.getType(), TYPE_TUNER);
                isHardwareInput = true;
            } else {
                id = generateInputId(componentName);
                type = TYPE_TUNER;
            }
            parseServiceMetadata(type);
            return new TvInputInfo(mResolveInfo, id, type, isHardwareInput, mLabel, mLabelResId,
                    mIcon, mIconStandby, mIconDisconnected, mSetupActivity,
                    mCanRecord == null ? false : mCanRecord, mTunerCount == null ? 0 : mTunerCount,
                    mHdmiDeviceInfo, isConnectedToHdmiSwitch, hdmiConnectionRelativePosition,
                    mParentId, mExtras);
        }

        private static String generateInputId(ComponentName name) {
            return name.flattenToShortString();
        }

        private static String generateInputId(ComponentName name, HdmiDeviceInfo hdmiDeviceInfo) {
            // Example of the format : "/HDMI%04X%02X"
            String format = DELIMITER_INFO_IN_ID + PREFIX_HDMI_DEVICE
                    + "%0" + LENGTH_HDMI_PHYSICAL_ADDRESS + "X"
                    + "%0" + LENGTH_HDMI_DEVICE_ID + "X";
            return name.flattenToShortString() + String.format(Locale.ENGLISH, format,
                    hdmiDeviceInfo.getPhysicalAddress(), hdmiDeviceInfo.getId());
        }

        private static String generateInputId(ComponentName name,
                TvInputHardwareInfo tvInputHardwareInfo) {
            return name.flattenToShortString() + DELIMITER_INFO_IN_ID + PREFIX_HARDWARE_DEVICE
                    + tvInputHardwareInfo.getDeviceId();
        }

        private static int getRelativePosition(Context context, HdmiDeviceInfo info) {
            HdmiControlManager hcm =
                    (HdmiControlManager) context.getSystemService(Context.HDMI_CONTROL_SERVICE);
            if (hcm == null) {
                return HdmiUtils.HDMI_RELATIVE_POSITION_UNKNOWN;
            }
            return HdmiUtils.getHdmiAddressRelativePosition(
                    info.getPhysicalAddress(), hcm.getPhysicalAddress());
        }

        private void parseServiceMetadata(int inputType) {
            ServiceInfo si = mResolveInfo.serviceInfo;
            PackageManager pm = mContext.getPackageManager();
            try (XmlResourceParser parser =
                         si.loadXmlMetaData(pm, TvInputService.SERVICE_META_DATA)) {
                if (parser == null) {
                    throw new IllegalStateException("No " + TvInputService.SERVICE_META_DATA
                            + " meta-data found for " + si.name);
                }

                Resources res = pm.getResourcesForApplication(si.applicationInfo);
                AttributeSet attrs = Xml.asAttributeSet(parser);

                int type;
                while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                        && type != XmlPullParser.START_TAG) {
                }

                String nodeName = parser.getName();
                if (!XML_START_TAG_NAME.equals(nodeName)) {
                    throw new IllegalStateException("Meta-data does not start with "
                            + XML_START_TAG_NAME + " tag for " + si.name);
                }

                TypedArray sa = res.obtainAttributes(attrs,
                        com.android.internal.R.styleable.TvInputService);
                mSetupActivity = sa.getString(
                        com.android.internal.R.styleable.TvInputService_setupActivity);
                if (mCanRecord == null) {
                    mCanRecord = sa.getBoolean(
                            com.android.internal.R.styleable.TvInputService_canRecord, false);
                }
                if (mTunerCount == null && inputType == TYPE_TUNER) {
                    mTunerCount = sa.getInt(
                            com.android.internal.R.styleable.TvInputService_tunerCount, 1);
                }
                sa.recycle();
            } catch (IOException | XmlPullParserException e) {
                throw new IllegalStateException("Failed reading meta-data for " + si.packageName, e);
            } catch (NameNotFoundException e) {
                throw new IllegalStateException("No resources found for " + si.packageName, e);
            }
        }
    }

    /**
     * Utility class for putting and getting settings for TV input.
     *
     * @hide
     */
    @SystemApi
    public static final class TvInputSettings {
        private static final String TV_INPUT_SEPARATOR = ":";
        private static final String CUSTOM_NAME_SEPARATOR = ",";

        private TvInputSettings() { }

        private static boolean isHidden(Context context, String inputId, int userId) {
            return getHiddenTvInputIds(context, userId).contains(inputId);
        }

        private static String getCustomLabel(Context context, String inputId, int userId) {
            return getCustomLabels(context, userId).get(inputId);
        }

        /**
         * Returns a set of TV input IDs which are marked as hidden by user in the settings.
         *
         * @param context The application context
         * @param userId The user ID for the stored hidden input set
         * @hide
         */
        @SystemApi
        public static Set<String> getHiddenTvInputIds(Context context, int userId) {
            String hiddenIdsString = Settings.Secure.getStringForUser(
                    context.getContentResolver(), Settings.Secure.TV_INPUT_HIDDEN_INPUTS, userId);
            Set<String> set = new HashSet<>();
            if (TextUtils.isEmpty(hiddenIdsString)) {
                return set;
            }
            String[] ids = hiddenIdsString.split(TV_INPUT_SEPARATOR);
            for (String id : ids) {
                set.add(Uri.decode(id));
            }
            return set;
        }

        /**
         * Returns a map of TV input ID/custom label pairs set by the user in the settings.
         *
         * @param context The application context
         * @param userId The user ID for the stored hidden input map
         * @hide
         */
        @SystemApi
        public static Map<String, String> getCustomLabels(Context context, int userId) {
            String labelsString = Settings.Secure.getStringForUser(
                    context.getContentResolver(), Settings.Secure.TV_INPUT_CUSTOM_LABELS, userId);
            Map<String, String> map = new HashMap<>();
            if (TextUtils.isEmpty(labelsString)) {
                return map;
            }
            String[] pairs = labelsString.split(TV_INPUT_SEPARATOR);
            for (String pairString : pairs) {
                String[] pair = pairString.split(CUSTOM_NAME_SEPARATOR);
                map.put(Uri.decode(pair[0]), Uri.decode(pair[1]));
            }
            return map;
        }

        /**
         * Stores a set of TV input IDs which are marked as hidden by user. This is expected to
         * be called from the settings app.
         *
         * @param context The application context
         * @param hiddenInputIds A set including all the hidden TV input IDs
         * @param userId The user ID for the stored hidden input set
         * @hide
         */
        @SystemApi
        public static void putHiddenTvInputs(Context context, Set<String> hiddenInputIds,
                int userId) {
            StringBuilder builder = new StringBuilder();
            boolean firstItem = true;
            for (String inputId : hiddenInputIds) {
                ensureValidField(inputId);
                if (firstItem) {
                    firstItem = false;
                } else {
                    builder.append(TV_INPUT_SEPARATOR);
                }
                builder.append(Uri.encode(inputId));
            }
            Settings.Secure.putStringForUser(context.getContentResolver(),
                    Settings.Secure.TV_INPUT_HIDDEN_INPUTS, builder.toString(), userId);

            // Notify of the TvInputInfo changes.
            TvInputManager tm = (TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);
            for (String inputId : hiddenInputIds) {
                TvInputInfo info = tm.getTvInputInfo(inputId);
                if (info != null) {
                    tm.updateTvInputInfo(info);
                }
            }
        }

        /**
         * Stores a map of TV input ID/custom label set by user. This is expected to be
         * called from the settings app.
         *
         * @param context The application context.
         * @param customLabels A map of TV input ID/custom label pairs
         * @param userId The user ID for the stored hidden input map
         * @hide
         */
        @SystemApi
        public static void putCustomLabels(Context context,
                Map<String, String> customLabels, int userId) {
            StringBuilder builder = new StringBuilder();
            boolean firstItem = true;
            for (Map.Entry<String, String> entry: customLabels.entrySet()) {
                ensureValidField(entry.getKey());
                ensureValidField(entry.getValue());
                if (firstItem) {
                    firstItem = false;
                } else {
                    builder.append(TV_INPUT_SEPARATOR);
                }
                builder.append(Uri.encode(entry.getKey()));
                builder.append(CUSTOM_NAME_SEPARATOR);
                builder.append(Uri.encode(entry.getValue()));
            }
            Settings.Secure.putStringForUser(context.getContentResolver(),
                    Settings.Secure.TV_INPUT_CUSTOM_LABELS, builder.toString(), userId);

            // Notify of the TvInputInfo changes.
            TvInputManager tm = (TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);
            for (String inputId : customLabels.keySet()) {
                TvInputInfo info = tm.getTvInputInfo(inputId);
                if (info != null) {
                    tm.updateTvInputInfo(info);
                }
            }
        }

        private static void ensureValidField(String value) {
            if (TextUtils.isEmpty(value)) {
                throw new IllegalArgumentException(value + " should not empty ");
            }
        }
    }
}
