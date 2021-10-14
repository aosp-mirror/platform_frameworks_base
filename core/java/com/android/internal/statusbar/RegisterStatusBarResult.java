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

import android.annotation.NonNull;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArrayMap;

import com.android.internal.view.AppearanceRegion;

/**
 * An immutable data object to return a set of values from StatusBarManagerService to its clients.
 */
public final class RegisterStatusBarResult implements Parcelable {
    public final ArrayMap<String, StatusBarIcon> mIcons;
    public final int mDisabledFlags1;                   // switch[0]
    public final int mAppearance;                       // switch[1]
    public final AppearanceRegion[] mAppearanceRegions; // switch[2]
    public final int mImeWindowVis;                     // switch[3]
    public final int mImeBackDisposition;               // switch[4]
    public final boolean mShowImeSwitcher;              // switch[5]
    public final int mDisabledFlags2;                   // switch[6]
    public final IBinder mImeToken;
    public final boolean mNavbarColorManagedByIme;
    public final int mBehavior;
    public final boolean mAppFullscreen;
    public final int[] mTransientBarTypes;

    public RegisterStatusBarResult(ArrayMap<String, StatusBarIcon> icons, int disabledFlags1,
            int appearance, AppearanceRegion[] appearanceRegions, int imeWindowVis,
            int imeBackDisposition, boolean showImeSwitcher, int disabledFlags2, IBinder imeToken,
            boolean navbarColorManagedByIme, int behavior, boolean appFullscreen,
            @NonNull int[] transientBarTypes) {
        mIcons = new ArrayMap<>(icons);
        mDisabledFlags1 = disabledFlags1;
        mAppearance = appearance;
        mAppearanceRegions = appearanceRegions;
        mImeWindowVis = imeWindowVis;
        mImeBackDisposition = imeBackDisposition;
        mShowImeSwitcher = showImeSwitcher;
        mDisabledFlags2 = disabledFlags2;
        mImeToken = imeToken;
        mNavbarColorManagedByIme = navbarColorManagedByIme;
        mBehavior = behavior;
        mAppFullscreen = appFullscreen;
        mTransientBarTypes = transientBarTypes;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeTypedArrayMap(mIcons, flags);
        dest.writeInt(mDisabledFlags1);
        dest.writeInt(mAppearance);
        dest.writeParcelableArray(mAppearanceRegions, 0);
        dest.writeInt(mImeWindowVis);
        dest.writeInt(mImeBackDisposition);
        dest.writeBoolean(mShowImeSwitcher);
        dest.writeInt(mDisabledFlags2);
        dest.writeStrongBinder(mImeToken);
        dest.writeBoolean(mNavbarColorManagedByIme);
        dest.writeInt(mBehavior);
        dest.writeBoolean(mAppFullscreen);
        dest.writeIntArray(mTransientBarTypes);
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
                    final int appearance = source.readInt();
                    final AppearanceRegion[] appearanceRegions =
                            source.readParcelableArray(null, AppearanceRegion.class);
                    final int imeWindowVis = source.readInt();
                    final int imeBackDisposition = source.readInt();
                    final boolean showImeSwitcher = source.readBoolean();
                    final int disabledFlags2 = source.readInt();
                    final IBinder imeToken = source.readStrongBinder();
                    final boolean navbarColorManagedByIme = source.readBoolean();
                    final int behavior = source.readInt();
                    final boolean appFullscreen = source.readBoolean();
                    final int[] transientBarTypes = source.createIntArray();
                    return new RegisterStatusBarResult(icons, disabledFlags1, appearance,
                            appearanceRegions, imeWindowVis, imeBackDisposition, showImeSwitcher,
                            disabledFlags2, imeToken, navbarColorManagedByIme, behavior,
                            appFullscreen, transientBarTypes);
                }

                @Override
                public RegisterStatusBarResult[] newArray(int size) {
                    return new RegisterStatusBarResult[size];
                }
            };
}
