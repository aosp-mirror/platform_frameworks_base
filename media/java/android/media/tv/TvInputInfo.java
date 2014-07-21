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
import android.hardware.hdmi.HdmiCecDeviceInfo;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/**
 * This class is used to specify meta information of a TV input.
 */
public final class TvInputInfo implements Parcelable {
    private static final boolean DEBUG = false;
    private static final String TAG = "TvInputInfo";

    /**
     * TV input type: the TV input service is not handling input from hardware. For example,
     * services showing streaming from the internet falls into this type.
     */
    public static final int TYPE_VIRTUAL = 0;

    // Should be in sync with TvInputHardwareInfo.

    /**
     * TV input type: a generic hardware TV input type.
     */
    public static final int TYPE_OTHER_HARDWARE = TvInputHardwareInfo.TV_INPUT_TYPE_OTHER_HARDWARE;
    /**
     * TV input type: the TV input service is a tuner. (e.g. terrestrial tuner)
     */
    public static final int TYPE_TUNER = TvInputHardwareInfo.TV_INPUT_TYPE_TUNER;
    /**
     * TV input type: the TV input service represents a composite port.
     */
    public static final int TYPE_COMPOSITE = TvInputHardwareInfo.TV_INPUT_TYPE_COMPOSITE;
    /**
     * TV input type: the TV input service represents a SVIDEO port.
     */
    public static final int TYPE_SVIDEO = TvInputHardwareInfo.TV_INPUT_TYPE_SVIDEO;
    /**
     * TV input type: the TV input service represents a SCART port.
     */
    public static final int TYPE_SCART = TvInputHardwareInfo.TV_INPUT_TYPE_SCART;
    /**
     * TV input type: the TV input service represents a component port.
     */
    public static final int TYPE_COMPONENT = TvInputHardwareInfo.TV_INPUT_TYPE_COMPONENT;
    /**
     * TV input type: the TV input service represents a VGA port.
     */
    public static final int TYPE_VGA = TvInputHardwareInfo.TV_INPUT_TYPE_VGA;
    /**
     * TV input type: the TV input service represents a DVI port.
     */
    public static final int TYPE_DVI = TvInputHardwareInfo.TV_INPUT_TYPE_DVI;
    /**
     * TV input type: the TV input service is HDMI. (e.g. HDMI 1)
     */
    public static final int TYPE_HDMI = TvInputHardwareInfo.TV_INPUT_TYPE_HDMI;
    /**
     * TV input type: the TV input service represents a display port.
     */
    public static final int TYPE_DISPLAY_PORT = TvInputHardwareInfo.TV_INPUT_TYPE_DISPLAY_PORT;

    /**
     * The ID of the TV input to provide to the setup activity and settings activity.
     */
    public static final String EXTRA_INPUT_ID = "inputId";

    private static final String XML_START_TAG_NAME = "tv-input";

    private final ResolveInfo mService;
    private final String mId;
    private final String mParentId;

    // Attributes from XML meta data.
    private String mSetupActivity;
    private String mSettingsActivity;
    private int mType = TYPE_VIRTUAL;

    /**
     * Create a new instance of the TvInputInfo class,
     * instantiating it from the given Context and ResolveInfo.
     *
     * @param service The ResolveInfo returned from the package manager about this TV input service.
     * @hide
     */
    public static TvInputInfo createTvInputInfo(Context context, ResolveInfo service)
            throws XmlPullParserException, IOException {
        return createTvInputInfo(context, service, generateInputIdForComponentName(
                new ComponentName(service.serviceInfo.packageName, service.serviceInfo.name)),
                null);
    }

    /**
     * Create a new instance of the TvInputInfo class,
     * instantiating it from the given Context, ResolveInfo, and HdmiCecDeviceInfo.
     *
     * @param service The ResolveInfo returned from the package manager about this TV input service.
     * @param cecInfo The HdmiCecDeviceInfo for a HDMI CEC logical device.
     * @hide
     */
    public static TvInputInfo createTvInputInfo(Context context, ResolveInfo service,
            HdmiCecDeviceInfo cecInfo, String parentId) throws XmlPullParserException, IOException {
        return createTvInputInfo(context, service, generateInputIdForHdmiCec(
                new ComponentName(service.serviceInfo.packageName, service.serviceInfo.name),
                cecInfo), parentId);
    }

    /**
     * Create a new instance of the TvInputInfo class,
     * instantiating it from the given Context, ResolveInfo, and TvInputHardwareInfo.
     *
     * @param service The ResolveInfo returned from the package manager about this TV input service.
     * @param hardwareInfo The TvInputHardwareInfo for a TV input hardware device.
     * @hide
     */
    public static TvInputInfo createTvInputInfo(Context context, ResolveInfo service,
            TvInputHardwareInfo hardwareInfo) throws XmlPullParserException, IOException {
        return createTvInputInfo(context, service, generateInputIdForHardware(
                new ComponentName(service.serviceInfo.packageName, service.serviceInfo.name),
                hardwareInfo), null);
    }

    private static TvInputInfo createTvInputInfo(Context context, ResolveInfo service,
            String id, String parentId) throws XmlPullParserException, IOException {
        ServiceInfo si = service.serviceInfo;
        PackageManager pm = context.getPackageManager();
        XmlResourceParser parser = null;
        try {
            parser = si.loadXmlMetaData(pm, TvInputService.SERVICE_META_DATA);
            if (parser == null) {
                throw new XmlPullParserException("No " + TvInputService.SERVICE_META_DATA
                        + " meta-data for " + si.name);
            }

            Resources res = pm.getResourcesForApplication(si.applicationInfo);
            AttributeSet attrs = Xml.asAttributeSet(parser);

            int type;
            while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
                    && type != XmlPullParser.START_TAG) {
            }

            String nodeName = parser.getName();
            if (!XML_START_TAG_NAME.equals(nodeName)) {
                throw new XmlPullParserException(
                        "Meta-data does not start with tv-input-service tag in " + si.name);
            }

            TvInputInfo input = new TvInputInfo(service, id, parentId);
            TypedArray sa = res.obtainAttributes(attrs,
                    com.android.internal.R.styleable.TvInputService);
            input.mSetupActivity = sa.getString(
                    com.android.internal.R.styleable.TvInputService_setupActivity);
            if (DEBUG) {
                Log.d(TAG, "Setup activity loaded. [" + input.mSetupActivity + "] for " + si.name);
            }
            input.mSettingsActivity = sa.getString(
                    com.android.internal.R.styleable.TvInputService_settingsActivity);
            if (DEBUG) {
                Log.d(TAG, "Settings activity loaded. [" + input.mSettingsActivity + "] for "
                        + si.name);
            }
            if (pm.checkPermission(android.Manifest.permission.TV_INPUT_HARDWARE, si.packageName)
                    == PackageManager.PERMISSION_GRANTED) {
                input.mType = sa.getInt(
                        com.android.internal.R.styleable.TvInputService_tvInputType, TYPE_VIRTUAL);
                if (DEBUG) {
                    Log.d(TAG, "Type loaded. [" + input.mType + "] for " + si.name);
                }
            }
            sa.recycle();

            return input;
        } catch (NameNotFoundException e) {
            throw new XmlPullParserException("Unable to create context for: " + si.packageName);
        } finally {
            if (parser != null) {
                parser.close();
            }
        }
    }

    /**
     * Constructor.
     *
     * @param service The ResolveInfo returned from the package manager about this TV input service.
     * @param id ID of this TV input. Should be generated via generateInputId*().
     * @param parentId ID of this TV input's parent input. {@code null} if none exists.
     */
    private TvInputInfo(ResolveInfo service, String id, String parentId) {
        mService = service;
        mId = id;
        mParentId = parentId;
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
     * <p>
     * A TV input may have a parent input if the TV input is actually a logical representation of
     * a device behind the hardware port represented by the parent input.
     * For example, a HDMI CEC logical device, connected to a HDMI port, appears as another TV
     * input. In this case, the parent input of this logical device is the HDMI port.
     * </p><p>
     * Applications may group inputs by parent input ID to provide an easier access to inputs
     * sharing the same physical port. In the example of HDMI CEC, logical HDMI CEC devices behind
     * the same HDMI port have the same parent ID, which is the ID representing the port. Thus
     * applications can group the hardware HDMI port and the logical HDMI CEC devices behind it
     * together using this method.
     * </p>
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
    public ComponentName getComponent() {
        return new ComponentName(mService.serviceInfo.packageName, mService.serviceInfo.name);
    }

    /**
     * Returns an intent to start the setup activity for this TV input service.
     */
    public Intent getIntentForSetupActivity() {
        if (!TextUtils.isEmpty(mSetupActivity)) {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setClassName(mService.serviceInfo.packageName, mSetupActivity);
            intent.putExtra(EXTRA_INPUT_ID, getId());
            return intent;
        }
        return null;
    }

    /**
     * Returns an intent to start the settings activity for this TV input service.
     */
    public Intent getIntentForSettingsActivity() {
        if (!TextUtils.isEmpty(mSettingsActivity)) {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setClassName(mService.serviceInfo.packageName, mSettingsActivity);
            intent.putExtra(EXTRA_INPUT_ID, getId());
            return intent;
        }
        return null;
    }

    /**
     * Returns the type of this TV input service.
     */
    public int getType() {
        return mType;
    }

    /**
     * Returns {@code true} if this TV input is pass-though which does not have any real channels
     * in TvProvider. {@code false} otherwise.
     *
     * @see TvContract#buildChannelUriForPassthroughTvInput(String)
     */
    public boolean isPassthroughInputType() {
        if (mType == TYPE_HDMI || mType == TYPE_DISPLAY_PORT || mType == TYPE_SCART
                || mType == TYPE_DVI || mType == TYPE_VGA || mType == TYPE_COMPONENT
                || mType == TYPE_COMPOSITE || mType == TYPE_SVIDEO) {
            return true;
        }
        return false;
    }

    /**
     * Loads the user-displayed label for this TV input service.
     *
     * @param context Supplies a {@link Context} used to load the label.
     * @return a CharSequence containing the TV input's label. If the TV input does not have
     *         a label, its name is returned.
     */
    public CharSequence loadLabel(Context context) {
        return mService.loadLabel(context.getPackageManager());
    }

    /**
     * Loads the user-displayed icon for this TV input service.
     *
     * @param context Supplies a {@link Context} used to load the icon.
     * @return a Drawable containing the TV input's icon. If the TV input does not have
     *         an icon, application icon is returned. If it's unavailable too, system default is
     *         returned.
     */
    public Drawable loadIcon(Context context) {
        return mService.serviceInfo.loadIcon(context.getPackageManager());
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
        return mId.equals(obj.mId);
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
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mId);
        dest.writeString(mParentId);
        mService.writeToParcel(dest, flags);
        dest.writeString(mSetupActivity);
        dest.writeString(mSettingsActivity);
        dest.writeInt(mType);
    }

    /**
     * Used to generate an input id from a ComponentName.
     *
     * @param name the component name for generating an input id.
     * @return the generated input id for the given {@code name}.
     */
    private static final String generateInputIdForComponentName(ComponentName name) {
        return name.flattenToShortString();
    }

    /**
     * Used to generate an input id from a ComponentName and HdmiCecDeviceInfo.
     *
     * @param name the component name for generating an input id.
     * @param cecInfo HdmiCecDeviceInfo describing this TV input.
     * @return the generated input id for the given {@code name} and {@code cecInfo}.
     */
    private static final String generateInputIdForHdmiCec(
            ComponentName name, HdmiCecDeviceInfo cecInfo) {
        return name.flattenToShortString() + String.format("|CEC%08X%08X",
                cecInfo.getPhysicalAddress(), cecInfo.getLogicalAddress());
    }

    /**
     * Used to generate an input id from a ComponentName and TvInputHardwareInfo
     *
     * @param name the component name for generating an input id.
     * @param hardwareInfo TvInputHardwareInfo describing this TV input.
     * @return the generated input id for the given {@code name} and {@code hardwareInfo}.
     */
    private static final String generateInputIdForHardware(
            ComponentName name, TvInputHardwareInfo hardwareInfo) {
        return name.flattenToShortString() + String.format("|HW%d", hardwareInfo.getDeviceId());
    }

    /**
     * Used to make this class parcelable.
     * @hide
     */
    public static final Parcelable.Creator<TvInputInfo> CREATOR =
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
        mId = in.readString();
        mParentId = in.readString();
        mService = ResolveInfo.CREATOR.createFromParcel(in);
        mSetupActivity = in.readString();
        mSettingsActivity = in.readString();
        mType = in.readInt();
    }
}
