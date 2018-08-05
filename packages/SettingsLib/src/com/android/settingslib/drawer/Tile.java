/**
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settingslib.drawer;

import static com.android.settingslib.drawer.TileUtils.META_DATA_KEY_PROFILE;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_ICON;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_ICON_URI;
import static com.android.settingslib.drawer.TileUtils.PROFILE_ALL;
import static com.android.settingslib.drawer.TileUtils.PROFILE_PRIMARY;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Description of a single dashboard tile that the user can select.
 */
public class Tile implements Parcelable {

    private static final String TAG = "Tile";

    /**
     * Title of the tile that is shown to the user.
     *
     * @attr ref android.R.styleable#PreferenceHeader_title
     */
    public CharSequence title;

    /**
     * Optional summary describing what this tile controls.
     *
     * @attr ref android.R.styleable#PreferenceHeader_summary
     */
    public CharSequence summary;

    /**
     * Intent to launch when the preference is selected.
     */
    public Intent intent;

    /**
     * Optional list of user handles which the intent should be launched on.
     */
    public ArrayList<UserHandle> userHandle = new ArrayList<>();

    private String mCategory;

    /**
     * Priority of the intent filter that created this tile, used for display ordering.
     */
    public int priority;

    /**
     * The metaData from the activity that defines this tile.
     */
    private Bundle mMetaData;

    /**
     * Optional key to use for this tile.
     */
    public String key;

    private final String mActivityPackage;
    private final String mActivityName;
    private ActivityInfo mActivityInfo;

    public Tile(ActivityInfo activityInfo, String category) {
        mActivityInfo = activityInfo;
        mActivityPackage = mActivityInfo.packageName;
        mActivityName = mActivityInfo.name;
        mMetaData = activityInfo.metaData;
        mCategory = category;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mActivityPackage);
        dest.writeString(mActivityName);
        TextUtils.writeToParcel(title, dest, flags);
        TextUtils.writeToParcel(summary, dest, flags);
        if (intent != null) {
            dest.writeByte((byte) 1);
            intent.writeToParcel(dest, flags);
        } else {
            dest.writeByte((byte) 0);
        }
        final int N = userHandle.size();
        dest.writeInt(N);
        for (int i = 0; i < N; i++) {
            userHandle.get(i).writeToParcel(dest, flags);
        }
        dest.writeString(mCategory);
        dest.writeInt(priority);
        dest.writeBundle(mMetaData);
        dest.writeString(key);
    }

    /**
     * Category in which the tile should be placed.
     */
    public String getCategory() {
        return mCategory;
    }

    public void setCategory(String newCategoryKey) {
        mCategory = newCategoryKey;
    }

    /**
     * Priority of the intent filter that created this tile, used for display ordering.
     */
    public int getPriority() {
        return 0;
    }

    public Bundle getMetaData() {
        return mMetaData;
    }

    /**
     * Optional icon to show for this tile.
     *
     * @attr ref android.R.styleable#PreferenceHeader_icon
     */
    public Icon getIcon(Context context) {
        if (context == null || mMetaData == null) {
            return null;
        }

        int iconResId = mMetaData.getInt(META_DATA_PREFERENCE_ICON);
        // Set the icon
        if (iconResId == 0) {
            // Only fallback to activityinfo.icon if metadata does not contain ICON_URI.
            // ICON_URI should be loaded in app UI when need the icon object. Handling IPC at this
            // level is too complex because we don't have a strong threading contract for this class
            if (!mMetaData.containsKey(META_DATA_PREFERENCE_ICON_URI)) {
                iconResId = getActivityInfo(context).icon;
            }
        }
        if (iconResId != 0) {
            return Icon.createWithResource(getActivityInfo(context).packageName, iconResId);
        } else {
            return null;
        }
    }

    /**
     * Whether the icon can be tinted. This is true when icon needs to be monochrome (single-color)
     */
    public boolean isIconTintable(Context context) {
        if (mMetaData != null
                && mMetaData.containsKey(TileUtils.META_DATA_PREFERENCE_ICON_TINTABLE)) {
            return mMetaData.getBoolean(TileUtils.META_DATA_PREFERENCE_ICON_TINTABLE);
        }
        final String pkgName = context.getPackageName();
        // If this drawable is coming from outside Settings, tint it to match the color.
        final ActivityInfo activityInfo = getActivityInfo(context);
        return activityInfo != null
                && !TextUtils.equals(pkgName, activityInfo.packageName);
    }

    Tile(Parcel in) {
        mActivityPackage = in.readString();
        mActivityName = in.readString();
        title = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
        summary = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
        if (in.readByte() != 0) {
            intent = Intent.CREATOR.createFromParcel(in);
        }
        final int N = in.readInt();
        for (int i = 0; i < N; i++) {
            userHandle.add(UserHandle.CREATOR.createFromParcel(in));
        }
        mCategory = in.readString();
        priority = in.readInt();
        mMetaData = in.readBundle();
        key = in.readString();
    }

    private ActivityInfo getActivityInfo(Context context) {
        if (mActivityInfo == null) {
            final PackageManager pm = context.getApplicationContext().getPackageManager();
            final Intent intent = new Intent().setClassName(mActivityPackage, mActivityName);
            final List<ResolveInfo> infoList =
                    pm.queryIntentActivities(intent, PackageManager.GET_META_DATA);
            if (infoList != null && !infoList.isEmpty()) {
                mActivityInfo = infoList.get(0).activityInfo;
            }
        }
        return mActivityInfo;
    }

    public static final Creator<Tile> CREATOR = new Creator<Tile>() {
        public Tile createFromParcel(Parcel source) {
            return new Tile(source);
        }

        public Tile[] newArray(int size) {
            return new Tile[size];
        }
    };

    public boolean isPrimaryProfileOnly() {
        String profile = mMetaData != null ?
                mMetaData.getString(META_DATA_KEY_PROFILE) : PROFILE_ALL;
        profile = (profile != null ? profile : PROFILE_ALL);
        return TextUtils.equals(profile, PROFILE_PRIMARY);
    }

    public static final Comparator<Tile> TILE_COMPARATOR =
            (lhs, rhs) -> rhs.priority - lhs.priority;
}
