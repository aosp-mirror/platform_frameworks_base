/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.app;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.Printer;
import android.util.Xml;

import java.io.IOException;

/**
 * This class is used to specify meta information of a device administrator
 * component.
 */
public final class DeviceAdminInfo implements Parcelable {
    static final String TAG = "DeviceAdminInfo";
    
    /**
     * The BroadcastReceiver that implements this device admin component.
     */
    final ResolveInfo mReceiver;
    
    /**
     * Constructor.
     * 
     * @param context The Context in which we are parsing the device admin.
     * @param receiver The ResolveInfo returned from the package manager about
     * this device admin's component.
     */
    public DeviceAdminInfo(Context context, ResolveInfo receiver)
            throws XmlPullParserException, IOException {
        mReceiver = receiver;
        ActivityInfo ai = receiver.activityInfo;
        
        PackageManager pm = context.getPackageManager();
        
        XmlResourceParser parser = null;
        try {
            parser = ai.loadXmlMetaData(pm, DeviceAdmin.DEVICE_ADMIN_META_DATA);
            if (parser == null) {
                throw new XmlPullParserException("No "
                        + DeviceAdmin.DEVICE_ADMIN_META_DATA + " meta-data");
            }
        
            AttributeSet attrs = Xml.asAttributeSet(parser);
            
            int type;
            while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
                    && type != XmlPullParser.START_TAG) {
            }
            
            String nodeName = parser.getName();
            if (!"device-admin".equals(nodeName)) {
                throw new XmlPullParserException(
                        "Meta-data does not start with device-admin tag");
            }
            
            TypedArray sa = context.getResources().obtainAttributes(attrs,
                    com.android.internal.R.styleable.Wallpaper);

            sa.recycle();
        } finally {
            if (parser != null) parser.close();
        }
    }

    DeviceAdminInfo(Parcel source) {
        mReceiver = ResolveInfo.CREATOR.createFromParcel(source);
    }
    
    /**
     * Return the .apk package that implements this device admin.
     */
    public String getPackageName() {
        return mReceiver.activityInfo.packageName;
    }
    
    /**
     * Return the class name of the receiver component that implements
     * this device admin.
     */
    public String getReceiverName() {
        return mReceiver.activityInfo.name;
    }

    /**
     * Return the raw information about the receiver implementing this
     * device admin.  Do not modify the returned object.
     */
    public ActivityInfo getActivityInfo() {
        return mReceiver.activityInfo;
    }

    /**
     * Return the component of the receiver that implements this device admin.
     */
    public ComponentName getComponent() {
        return new ComponentName(mReceiver.activityInfo.packageName,
                mReceiver.activityInfo.name);
    }
    
    /**
     * Load the user-displayed label for this device admin.
     * 
     * @param pm Supply a PackageManager used to load the device admin's
     * resources.
     */
    public CharSequence loadLabel(PackageManager pm) {
        return mReceiver.loadLabel(pm);
    }
    
    /**
     * Load the user-displayed icon for this device admin.
     * 
     * @param pm Supply a PackageManager used to load the device admin's
     * resources.
     */
    public Drawable loadIcon(PackageManager pm) {
        return mReceiver.loadIcon(pm);
    }
    
    public void dump(Printer pw, String prefix) {
        pw.println(prefix + "Receiver:");
        mReceiver.dump(pw, prefix + "  ");
    }
    
    @Override
    public String toString() {
        return "DeviceAdminInfo{" + mReceiver.activityInfo.name + "}";
    }

    /**
     * Used to package this object into a {@link Parcel}.
     * 
     * @param dest The {@link Parcel} to be written.
     * @param flags The flags used for parceling.
     */
    public void writeToParcel(Parcel dest, int flags) {
        mReceiver.writeToParcel(dest, flags);
    }

    /**
     * Used to make this class parcelable.
     */
    public static final Parcelable.Creator<DeviceAdminInfo> CREATOR =
            new Parcelable.Creator<DeviceAdminInfo>() {
        public DeviceAdminInfo createFromParcel(Parcel source) {
            return new DeviceAdminInfo(source);
        }

        public DeviceAdminInfo[] newArray(int size) {
            return new DeviceAdminInfo[size];
        }
    };

    public int describeContents() {
        return 0;
    }
}
