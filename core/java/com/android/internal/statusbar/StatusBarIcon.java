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

package com.android.internal.statusbar;

import android.graphics.drawable.Icon;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.text.TextUtils;

public class StatusBarIcon implements Parcelable {
    public UserHandle user;
    public String pkg;
    public Icon icon;
    public int iconLevel;
    public boolean visible = true;
    public int number;
    public CharSequence contentDescription;

    public StatusBarIcon(UserHandle user, String resPackage, Icon icon, int iconLevel, int number,
            CharSequence contentDescription) {
        if (icon.getType() == Icon.TYPE_RESOURCE
                && TextUtils.isEmpty(icon.getResPackage())) {
            // This is an odd situation where someone's managed to hand us an icon without a
            // package inside, probably by mashing an int res into a Notification object.
            // Now that we have the correct package name handy, let's fix it.
            icon = Icon.createWithResource(resPackage, icon.getResId());
        }
        this.pkg = resPackage;
        this.user = user;
        this.icon = icon;
        this.iconLevel = iconLevel;
        this.number = number;
        this.contentDescription = contentDescription;
    }

    public StatusBarIcon(String iconPackage, UserHandle user,
            int iconId, int iconLevel, int number,
            CharSequence contentDescription) {
        this(user, iconPackage, Icon.createWithResource(iconPackage, iconId),
                iconLevel, number, contentDescription);
    }

    @Override
    public String toString() {
        return "StatusBarIcon(icon=" + icon
                + ((iconLevel != 0)?(" level=" + iconLevel):"")
                + (visible?" visible":"")
                + " user=" + user.getIdentifier()
                + ((number != 0)?(" num=" + number):"")
                + " )";
    }

    @Override
    public StatusBarIcon clone() {
        StatusBarIcon that = new StatusBarIcon(this.user, this.pkg, this.icon,
                this.iconLevel, this.number, this.contentDescription);
        that.visible = this.visible;
        return that;
    }

    /**
     * Unflatten the StatusBarIcon from a parcel.
     */
    public StatusBarIcon(Parcel in) {
        readFromParcel(in);
    }

    public void readFromParcel(Parcel in) {
        this.icon = (Icon) in.readParcelable(null, android.graphics.drawable.Icon.class);
        this.pkg = in.readString();
        this.user = (UserHandle) in.readParcelable(null, android.os.UserHandle.class);
        this.iconLevel = in.readInt();
        this.visible = in.readInt() != 0;
        this.number = in.readInt();
        this.contentDescription = in.readCharSequence();
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeParcelable(this.icon, 0);
        out.writeString(this.pkg);
        out.writeParcelable(this.user, 0);
        out.writeInt(this.iconLevel);
        out.writeInt(this.visible ? 1 : 0);
        out.writeInt(this.number);
        out.writeCharSequence(this.contentDescription);
    }

    public int describeContents() {
        return 0;
    }

    /**
     * Parcelable.Creator that instantiates StatusBarIcon objects
     */
    public static final Parcelable.Creator<StatusBarIcon> CREATOR
            = new Parcelable.Creator<StatusBarIcon>()
    {
        public StatusBarIcon createFromParcel(Parcel parcel)
        {
            return new StatusBarIcon(parcel);
        }

        public StatusBarIcon[] newArray(int size)
        {
            return new StatusBarIcon[size];
        }
    };
}

