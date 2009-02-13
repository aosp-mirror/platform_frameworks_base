/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.gadget;

import android.os.Parcel;
import android.os.Parcelable;
import android.content.ComponentName;

/**
 * Describes the meta data for an installed gadget provider.  The fields in this class
 * correspond to the fields in the <code>&lt;gadget-provider&gt;</code> xml tag.
 */
public class GadgetProviderInfo implements Parcelable {
    /**
     * Identity of this gadget component.  This component should be a {@link
     * android.content.BroadcastReceiver}, and it will be sent the Gadget intents
     * {@link android.gadget as described in the gadget package documentation}.
     *
     * <p>This field corresponds to the <code>android:name</code> attribute in
     * the <code>&lt;receiver&gt;</code> element in the AndroidManifest.xml file.
     */
    public ComponentName provider;

    /**
     * Minimum width of the gadget, in dp.
     *
     * <p>This field corresponds to the <code>android:minWidth</code> attribute in
     * the gadget meta-data file.
     */
    public int minWidth;

    /**
     * Minimum height of the gadget, in dp.
     *
     * <p>This field corresponds to the <code>android:minHeight</code> attribute in
     * the gadget meta-data file.
     */
    public int minHeight;

    /**
     * How often, in milliseconds, that this gadget wants to be updated.
     * The gadget manager may place a limit on how often a gadget is updated.
     *
     * <p>This field corresponds to the <code>android:updatePeriodMillis</code> attribute in
     * the gadget meta-data file.
     */
    public int updatePeriodMillis;

    /**
     * The resource id of the initial layout for this gadget.  This should be
     * displayed until the RemoteViews for the gadget is available.
     *
     * <p>This field corresponds to the <code>android:initialLayout</code> attribute in
     * the gadget meta-data file.
     */
    public int initialLayout;

    /**
     * The activity to launch that will configure the gadget.
     *
     * <p>This class name of field corresponds to the <code>android:configure</code> attribute in
     * the gadget meta-data file.  The package name always corresponds to the package containing
     * the gadget provider.
     */
    public ComponentName configure;

    /**
     * The label to display to the user in the gadget picker.  If not supplied in the
     * xml, the application label will be used.
     *
     * <p>This field corresponds to the <code>android:label</code> attribute in
     * the <code>&lt;receiver&gt;</code> element in the AndroidManifest.xml file.
     */
    public String label;

    /**
     * The icon to display for this gadget in the gadget picker.  If not supplied in the
     * xml, the application icon will be used.
     *
     * <p>This field corresponds to the <code>android:icon</code> attribute in
     * the <code>&lt;receiver&gt;</code> element in the AndroidManifest.xml file.
     */
    public int icon;

    public GadgetProviderInfo() {
    }

    /**
     * Unflatten the GadgetProviderInfo from a parcel.
     */
    public GadgetProviderInfo(Parcel in) {
        if (0 != in.readInt()) {
            this.provider = new ComponentName(in);
        }
        this.minWidth = in.readInt();
        this.minHeight = in.readInt();
        this.updatePeriodMillis = in.readInt();
        this.initialLayout = in.readInt();
        if (0 != in.readInt()) {
            this.configure = new ComponentName(in);
        }
        this.label = in.readString();
        this.icon = in.readInt();
    }


    public void writeToParcel(android.os.Parcel out, int flags) {
        if (this.provider != null) {
            out.writeInt(1);
            this.provider.writeToParcel(out, flags);
        } else {
            out.writeInt(0);
        }
        out.writeInt(this.minWidth);
        out.writeInt(this.minHeight);
        out.writeInt(this.updatePeriodMillis);
        out.writeInt(this.initialLayout);
        if (this.configure != null) {
            out.writeInt(1);
            this.configure.writeToParcel(out, flags);
        } else {
            out.writeInt(0);
        }
        out.writeString(this.label);
        out.writeInt(this.icon);
    }

    public int describeContents() {
        return 0;
    }

    /**
     * Parcelable.Creator that instantiates GadgetProviderInfo objects
     */
    public static final Parcelable.Creator<GadgetProviderInfo> CREATOR
            = new Parcelable.Creator<GadgetProviderInfo>()
    {
        public GadgetProviderInfo createFromParcel(Parcel parcel)
        {
            return new GadgetProviderInfo(parcel);
        }

        public GadgetProviderInfo[] newArray(int size)
        {
            return new GadgetProviderInfo[size];
        }
    };

    public String toString() {
        return "GadgetProviderInfo(provider=" + this.provider + ")";
    }
}


