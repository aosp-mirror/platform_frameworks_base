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

package com.android.internal.statusbar;

import android.graphics.Rect;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArrayMap;

/**
 * An immutable data object to return a set of values from StatusBarManagerService to its clients.
 */
public final class RegisterStatusBarResult implements Parcelable {
    public final ArrayMap<String, StatusBarIcon> mIcons;
    public final int mDisabledFlags1;                  // switch[0]
    public final int mSystemUiVisibility;              // switch[1]
    public final boolean mMenuVisible;                 // switch[2]
    public final int mImeWindowVis;                    // switch[3]
    public final int mImeBackDisposition;              // switch[4]
    public final boolean mShowImeSwitcher;             // switch[5]
    public final int mDisabledFlags2;                  // switch[6]
    public final int mFullscreenStackSysUiVisibility;  // switch[7]
    public final int mDockedStackSysUiVisibility;      // switch[8]
    public final IBinder mImeToken;
    public final Rect mFullscreenStackBounds;
    public final Rect mDockedStackBounds;
    public final boolean mNavbarColorManagedByIme;

    public RegisterStatusBarResult(ArrayMap<String, StatusBarIcon> icons, int disabledFlags1,
            int systemUiVisibility, boolean menuVisible, int imeWindowVis, int imeBackDisposition,
            boolean showImeSwitcher, int disabledFlags2, int fullscreenStackSysUiVisibility,
            int dockedStackSysUiVisibility, IBinder imeToken, Rect fullscreenStackBounds,
            Rect dockedStackBounds, boolean navbarColorManagedByIme) {
        mIcons = new ArrayMap<>(icons);
        mDisabledFlags1 = disabledFlags1;
        mSystemUiVisibility = systemUiVisibility;
        mMenuVisible = menuVisible;
        mImeWindowVis = imeWindowVis;
        mImeBackDisposition = imeBackDisposition;
        mShowImeSwitcher = showImeSwitcher;
        mDisabledFlags2 = disabledFlags2;
        mFullscreenStackSysUiVisibility = fullscreenStackSysUiVisibility;
        mDockedStackSysUiVisibility = dockedStackSysUiVisibility;
        mImeToken = imeToken;
        mFullscreenStackBounds = fullscreenStackBounds;
        mDockedStackBounds = dockedStackBounds;
        mNavbarColorManagedByIme = navbarColorManagedByIme;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeTypedArrayMap(mIcons, flags);
        dest.writeInt(mDisabledFlags1);
        dest.writeInt(mSystemUiVisibility);
        dest.writeBoolean(mMenuVisible);
        dest.writeInt(mImeWindowVis);
        dest.writeInt(mImeBackDisposition);
        dest.writeBoolean(mShowImeSwitcher);
        dest.writeInt(mDisabledFlags2);
        dest.writeInt(mFullscreenStackSysUiVisibility);
        dest.writeInt(mDockedStackSysUiVisibility);
        dest.writeStrongBinder(mImeToken);
        dest.writeTypedObject(mFullscreenStackBounds, flags);
        dest.writeTypedObject(mDockedStackBounds, flags);
        dest.writeBoolean(mNavbarColorManagedByIme);
    }

    /**
     * Used to make this class parcelable.
     */
    public static final Parcelable.Creator<RegisterStatusBarResult> CREATOR =
            new Parcelable.Creator<RegisterStatusBarResult>() {
                @Override
                public RegisterStatusBarResult createFromParcel(Parcel source) {
                    final ArrayMap<String, StatusBarIcon> icons =
                            source.createTypedArrayMap(StatusBarIcon.CREATOR);
                    final int disabledFlags1 = source.readInt();
                    final int systemUiVisibility = source.readInt();
                    final boolean menuVisible = source.readBoolean();
                    final int imeWindowVis = source.readInt();
                    final int imeBackDisposition = source.readInt();
                    final boolean showImeSwitcher = source.readBoolean();
                    final int disabledFlags2 = source.readInt();
                    final int fullscreenStackSysUiVisibility = source.readInt();
                    final int dockedStackSysUiVisibility = source.readInt();
                    final IBinder imeToken = source.readStrongBinder();
                    final Rect fullscreenStackBounds = source.readTypedObject(Rect.CREATOR);
                    final Rect dockedStackBounds = source.readTypedObject(Rect.CREATOR);
                    final boolean navbarColorManagedByIme = source.readBoolean();
                    return new RegisterStatusBarResult(icons, disabledFlags1, systemUiVisibility,
                            menuVisible, imeWindowVis, imeBackDisposition, showImeSwitcher,
                            disabledFlags2, fullscreenStackSysUiVisibility,
                            dockedStackSysUiVisibility, imeToken, fullscreenStackBounds,
                            dockedStackBounds, navbarColorManagedByIme);
                }

                @Override
                public RegisterStatusBarResult[] newArray(int size) {
                    return new RegisterStatusBarResult[size];
                }
            };
}
