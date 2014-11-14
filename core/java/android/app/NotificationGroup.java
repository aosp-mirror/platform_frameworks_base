/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** @hide */
public class NotificationGroup implements Parcelable {
    private static final String TAG = "NotificationGroup";

    private String mName;
    private int mNameResId;

    private UUID mUuid;

    private Set<String> mPackages = new HashSet<String>();

    private boolean mDirty;

    public static final Parcelable.Creator<NotificationGroup> CREATOR =
            new Parcelable.Creator<NotificationGroup>() {
        public NotificationGroup createFromParcel(Parcel in) {
            return new NotificationGroup(in);
        }

        @Override
        public NotificationGroup[] newArray(int size) {
            return new NotificationGroup[size];
        }
    };

    public NotificationGroup(String name) {
        this(name, -1, null);
    }

    public NotificationGroup(String name, int nameResId, UUID uuid) {
        mName = name;
        mNameResId = nameResId;
        mUuid = (uuid != null) ? uuid : UUID.randomUUID();
        mDirty = uuid == null;
    }

    private NotificationGroup(Parcel in) {
        readFromParcel(in);
    }

    @Override
    public String toString() {
        return getName();
    }

    public String getName() {
        return mName;
    }

    public void setName(String name) {
        mName = name;
        mNameResId = -1;
        mDirty = true;
    }

    public UUID getUuid() {
        return mUuid;
    }

    public void addPackage(String pkg) {
        mPackages.add(pkg);
        mDirty = true;
    }

    public String[] getPackages() {
        return mPackages.toArray(new String[mPackages.size()]);
    }

    public void removePackage(String pkg) {
        mPackages.remove(pkg);
        mDirty = true;
    }

    public boolean hasPackage(String pkg) {
        return mPackages.contains(pkg);
    }

    public boolean isDirty() {
        return mDirty;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mName);
        dest.writeInt(mNameResId);
        dest.writeInt(mDirty ? 1 : 0);
        new ParcelUuid(mUuid).writeToParcel(dest, 0);
        dest.writeStringArray(getPackages());
    }

    public void readFromParcel(Parcel in) {
        mName = in.readString();
        mNameResId = in.readInt();
        mDirty = in.readInt() != 0;
        mUuid = ParcelUuid.CREATOR.createFromParcel(in).getUuid();
        mPackages.addAll(Arrays.asList(in.readStringArray()));
    }

    public void getXmlString(StringBuilder builder, Context context) {
        builder.append("<notificationGroup ");
        if (mNameResId > 0) {
            builder.append("nameres=\"");
            builder.append(context.getResources().getResourceEntryName(mNameResId));
        } else {
            builder.append("name=\"");
            builder.append(TextUtils.htmlEncode(getName()));
        }
        builder.append("\" uuid=\"");
        builder.append(TextUtils.htmlEncode(getUuid().toString()));
        builder.append("\">\n");
        for (String pkg : mPackages) {
            builder.append("<package>" + TextUtils.htmlEncode(pkg) + "</package>\n");
        }
        builder.append("</notificationGroup>\n");
        mDirty = false;
    }

    public static NotificationGroup fromXml(XmlPullParser xpp, Context context)
            throws XmlPullParserException, IOException {
        String value = xpp.getAttributeValue(null, "nameres");
        int nameResId = -1;
        String name = null;
        UUID uuid = null;

        if (value != null) {
            nameResId = context.getResources().getIdentifier(value, "string", "android");
            if (nameResId > 0) {
                name = context.getResources().getString(nameResId);
            }
        }

        if (name == null) {
            name = xpp.getAttributeValue(null, "name");
        }

        value = xpp.getAttributeValue(null, "uuid");
        if (value != null) {
            try {
                uuid = UUID.fromString(value);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "UUID not recognized for " + name + ", using new one.");
            }
        }

        NotificationGroup notificationGroup = new NotificationGroup(name, nameResId, uuid);
        int event = xpp.next();
        while (event != XmlPullParser.END_TAG || !xpp.getName().equals("notificationGroup")) {
            if (event == XmlPullParser.START_TAG) {
                if (xpp.getName().equals("package")) {
                    String pkg = xpp.nextText();
                    notificationGroup.addPackage(pkg);
                }
            }
            event = xpp.next();
        }

        /* we just loaded from XML, no need to save */
        notificationGroup.mDirty = false;

        return notificationGroup;
    }
}
