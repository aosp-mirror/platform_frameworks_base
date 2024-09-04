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

import android.inputmethodservice.InputMethodService.BackDispositionMode;
import android.inputmethodservice.InputMethodService.ImeWindowVisibility;
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
    @ImeWindowVisibility
    public final int mImeWindowVis;                     // switch[3]
    @BackDispositionMode
    public final int mImeBackDisposition;               // switch[4]
    public final boolean mShowImeSwitcher;              // switch[5]
    public final int mDisabledFlags2;                   // switch[6]
    public final boolean mNavbarColorManagedByIme;
    public final int mBehavior;
    public final int mRequestedVisibleTypes;
    public final String mPackageName;
    public final int mTransientBarTypes;
    public final LetterboxDetails[] mLetterboxDetails;

    public RegisterStatusBarResult(ArrayMap<String, StatusBarIcon> icons, int disabledFlags1,
            int appearance, AppearanceRegion[] appearanceRegions,
            @ImeWindowVisibility int imeWindowVis, @BackDispositionMode int imeBackDisposition,
            boolean showImeSwitcher, int disabledFlags2, boolean navbarColorManagedByIme,
            int behavior, int requestedVisibleTypes, String packageName, int transientBarTypes,
            LetterboxDetails[] letterboxDetails) {
        mIcons = new ArrayMap<>(icons);
        mDisabledFlags1 = disabledFlags1;
        mAppearance = appearance;
        mAppearanceRegions = appearanceRegions;
        mImeWindowVis = imeWindowVis;
        mImeBackDisposition = imeBackDisposition;
        mShowImeSwitcher = showImeSwitcher;
        mDisabledFlags2 = disabledFlags2;
        mNavbarColorManagedByIme = navbarColorManagedByIme;
        mBehavior = behavior;
        mRequestedVisibleTypes = requestedVisibleTypes;
        mPackageName = packageName;
        mTransientBarTypes = transientBarTypes;
        mLetterboxDetails = letterboxDetails;
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
        dest.writeBoolean(mNavbarColorManagedByIme);
        dest.writeInt(mBehavior);
        dest.writeInt(mRequestedVisibleTypes);
        dest.writeString(mPackageName);
        dest.writeInt(mTransientBarTypes);
        dest.writeParcelableArray(mLetterboxDetails, flags);
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
                    final boolean navbarColorManagedByIme = source.readBoolean();
                    final int behavior = source.readInt();
                    final int requestedVisibleTypes = source.readInt();
                    final String packageName = source.readString();
                    final int transientBarTypes = source.readInt();
                    final LetterboxDetails[] letterboxDetails =
                            source.readParcelableArray(null, LetterboxDetails.class);
                    return new RegisterStatusBarResult(icons, disabledFlags1, appearance,
                            appearanceRegions, imeWindowVis, imeBackDisposition, showImeSwitcher,
                            disabledFlags2, navbarColorManagedByIme, behavior,
                            requestedVisibleTypes, packageName, transientBarTypes,
                            letterboxDetails);
                }

                @Override
                public RegisterStatusBarResult[] newArray(int size) {
                    return new RegisterStatusBarResult[size];
                }
            };
}
