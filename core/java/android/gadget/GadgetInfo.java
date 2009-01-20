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
 * Describes the meta data for an installed gadget.
 */
public class GadgetInfo implements Parcelable {
    /**
     * Identity of this gadget component.  This component should be a {@link
     * android.content.BroadcastReceiver}, and it will be sent the Gadget intents
     * {@link android.gadget as described in the gadget package documentation}.
     */
    public ComponentName provider;

    /**
     * Minimum width of the gadget, in dp.
     */
    public int minWidth;

    /**
     * Minimum height of the gadget, in dp.
     */
    public int minHeight;

    /**
     * How often, in milliseconds, that this gadget wants to be updated.
     * The gadget manager may place a limit on how often a gadget is updated.
     */
    public int updatePeriodMillis;

    /**
     * The resource id of the initial layout for this gadget.  This should be
     * displayed until the RemoteViews for the gadget is available.
     */
    public int initialLayout;

    /**
     * The activity to launch that will configure the gadget.
     */
    public ComponentName configure;

    public GadgetInfo() {
    }

    /**
     * Unflatten the GadgetInfo from a parcel.
     */
    public GadgetInfo(Parcel in) {
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
    }

    public int describeContents() {
        return 0;
    }

    /**
     * Parcelable.Creator that instantiates GadgetInfo objects
     */
    public static final Parcelable.Creator<GadgetInfo> CREATOR
            = new Parcelable.Creator<GadgetInfo>()
    {
        public GadgetInfo createFromParcel(Parcel parcel)
        {
            return new GadgetInfo(parcel);
        }

        public GadgetInfo[] newArray(int size)
        {
            return new GadgetInfo[size];
        }
    };

    public String toString() {
        return "GadgetInfo(provider=" + this.provider + ")";
    }
}


