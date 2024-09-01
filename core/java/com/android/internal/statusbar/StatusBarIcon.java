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

import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Representation of an icon that should appear in the status bar.
 *
 * <p>This includes notifications, conversations, and icons displayed on the right side (e.g.
 * Wifi, Vibration/Silence, Priority Modes, etc).
 *
 * <p>This class is {@link Parcelable} but the {@link #preloadedIcon} is not (and will be lost if
 * the object is copied through parcelling). If {@link #preloadedIcon} is supplied, it must match
 * the {@link #icon} resource/bitmap.
 */
public class StatusBarIcon implements Parcelable {
    public enum Type {
        // Notification: the sender avatar for important conversations
        PeopleAvatar,
        // Notification: the monochrome version of the app icon if available; otherwise fall back to
        // the small icon
        MaybeMonochromeAppIcon,
        // Notification: the small icon from the notification
        NotifSmallIcon,
        // The wi-fi, cellular or battery icon.
        SystemIcon,
        // Some other icon, corresponding to a resource (possibly in a different package).
        ResourceIcon
    }

    public enum Shape {
        /**
         * Icon view should use WRAP_CONTENT -- so that the horizontal space occupied depends on the
         * icon's shape (skinny/fat icons take less/more). Most icons will want to use this option
         * for a nicer-looking overall spacing in the status bar, as long as the icon is "known"
         * (i.e. not coming from a 3P package).
         */
        WRAP_CONTENT,

        /** Icon should always be displayed in a space as wide as the status bar is tall. */
        FIXED_SPACE,
    }

    public UserHandle user;
    public String pkg;
    public Icon icon;
    public int iconLevel;
    public boolean visible = true;
    public int number;
    public CharSequence contentDescription;
    public Type type;
    public Shape shape;

    /**
     * Optional {@link Drawable} corresponding to {@link #icon}. This field is not parcelable, so
     * will be lost if the object is sent to a different process. If you set it, make sure to
     * <em>also</em> set {@link #icon} pointing to the corresponding resource.
     */
    @Nullable public Drawable preloadedIcon;

    public StatusBarIcon(UserHandle user, String resPackage, Icon icon, int iconLevel, int number,
            CharSequence contentDescription, Type type, Shape shape) {
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
        this.type = type;
        this.shape = shape;
    }

    public StatusBarIcon(UserHandle user, String resPackage, Icon icon, int iconLevel, int number,
            CharSequence contentDescription, Type type) {
        this(user, resPackage, icon, iconLevel, number, contentDescription, type,
                Shape.WRAP_CONTENT);
    }

    public StatusBarIcon(String iconPackage, UserHandle user,
            int iconId, int iconLevel, int number,
            CharSequence contentDescription, Type type) {
        this(user, iconPackage, Icon.createWithResource(iconPackage, iconId),
                iconLevel, number, contentDescription, type);
    }

    @NonNull
    @Override
    public String toString() {
        return "StatusBarIcon(icon=" + icon
                + ((iconLevel != 0)?(" level=" + iconLevel):"")
                + (visible?" visible":"")
                + " user=" + user.getIdentifier()
                + ((number != 0)?(" num=" + number):"")
                + " )";
    }

    @NonNull
    @Override
    public StatusBarIcon clone() {
        StatusBarIcon that = new StatusBarIcon(this.user, this.pkg, this.icon,
                this.iconLevel, this.number, this.contentDescription, this.type, this.shape);
        that.visible = this.visible;
        that.preloadedIcon = this.preloadedIcon;
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
        this.type = Type.valueOf(in.readString());
        this.shape = Shape.valueOf(in.readString());
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeParcelable(this.icon, 0);
        out.writeString(this.pkg);
        out.writeParcelable(this.user, 0);
        out.writeInt(this.iconLevel);
        out.writeInt(this.visible ? 1 : 0);
        out.writeInt(this.number);
        out.writeCharSequence(this.contentDescription);
        out.writeString(this.type.name());
        out.writeString(this.shape.name());
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

