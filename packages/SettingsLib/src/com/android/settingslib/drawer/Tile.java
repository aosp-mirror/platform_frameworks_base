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

import static com.android.settingslib.drawer.TileUtils.META_DATA_KEY_ORDER;
import static com.android.settingslib.drawer.TileUtils.META_DATA_KEY_PROFILE;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_ICON;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_ICON_URI;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_KEYHINT;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_SUMMARY;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_SUMMARY_URI;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_TITLE;
import static com.android.settingslib.drawer.TileUtils.PROFILE_ALL;
import static com.android.settingslib.drawer.TileUtils.PROFILE_PRIMARY;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Description of a single dashboard tile that the user can select.
 */
public class Tile implements Parcelable {

    private static final String TAG = "Tile";

    /**
     * Optional list of user handles which the intent should be launched on.
     */
    public ArrayList<UserHandle> userHandle = new ArrayList<>();

    @VisibleForTesting
    long mLastUpdateTime;
    private final String mActivityPackage;
    private final String mActivityName;
    private final Intent mIntent;

    private ActivityInfo mActivityInfo;
    private CharSequence mSummaryOverride;
    private Bundle mMetaData;
    private String mCategory;

    public Tile(ActivityInfo activityInfo, String category) {
        mActivityInfo = activityInfo;
        mActivityPackage = mActivityInfo.packageName;
        mActivityName = mActivityInfo.name;
        mMetaData = activityInfo.metaData;
        mCategory = category;
        mIntent = new Intent().setClassName(mActivityPackage, mActivityName);
    }

    Tile(Parcel in) {
        mActivityPackage = in.readString();
        mActivityName = in.readString();
        mIntent = new Intent().setClassName(mActivityPackage, mActivityName);
        final int N = in.readInt();
        for (int i = 0; i < N; i++) {
            userHandle.add(UserHandle.CREATOR.createFromParcel(in));
        }
        mCategory = in.readString();
        mMetaData = in.readBundle();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mActivityPackage);
        dest.writeString(mActivityName);
        final int N = userHandle.size();
        dest.writeInt(N);
        for (int i = 0; i < N; i++) {
            userHandle.get(i).writeToParcel(dest, flags);
        }
        dest.writeString(mCategory);
        dest.writeBundle(mMetaData);
    }

    public int getId() {
        return Objects.hash(mActivityPackage, mActivityName);
    }

    public String getDescription() {
        return mActivityPackage + "/" + mActivityName;
    }

    public String getPackageName() {
        return mActivityPackage;
    }

    /**
     * Intent to launch when the preference is selected.
     */
    public Intent getIntent() {
        return mIntent;
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
     * Priority of this tile, used for display ordering.
     */
    public int getOrder() {
        if (hasOrder()) {
            return mMetaData.getInt(META_DATA_KEY_ORDER);
        } else {
            return 0;
        }
    }

    public boolean hasOrder() {
        return mMetaData.containsKey(META_DATA_KEY_ORDER)
                && mMetaData.get(META_DATA_KEY_ORDER) instanceof Integer;
    }

    /**
     * Title of the tile that is shown to the user.
     */
    public CharSequence getTitle(Context context) {
        CharSequence title = null;
        ensureMetadataNotStale(context);
        final PackageManager packageManager = context.getPackageManager();
        if (mMetaData.containsKey(META_DATA_PREFERENCE_TITLE)) {
            if (mMetaData.get(META_DATA_PREFERENCE_TITLE) instanceof Integer) {
                try {
                    final Resources res =
                            packageManager.getResourcesForApplication(mActivityPackage);
                    title = res.getString(mMetaData.getInt(META_DATA_PREFERENCE_TITLE));
                } catch (PackageManager.NameNotFoundException | Resources.NotFoundException e) {
                    Log.d(TAG, "Couldn't find info", e);
                }
            } else {
                title = mMetaData.getString(META_DATA_PREFERENCE_TITLE);
            }
        }
        // Set the preference title to the activity's label if no
        // meta-data is found
        if (title == null) {
            title = getActivityInfo(context).loadLabel(packageManager);
        }
        return title;
    }

    /**
     * Returns the raw metadata for summary, this is used for comparing 2 summary text without
     * loading the real string.
     */
    public String getSummaryReference() {
        if (mSummaryOverride != null) {
            return mSummaryOverride.toString();
        }
        if (mMetaData != null && mMetaData.containsKey(META_DATA_PREFERENCE_SUMMARY)) {
            return mMetaData.get(META_DATA_PREFERENCE_SUMMARY).toString();
        }
        return null;
    }

    /**
     * Overrides the summary. This can happen when injected tile wants to provide dynamic summary.
     */
    public void overrideSummary(CharSequence summaryOverride) {
        mSummaryOverride = summaryOverride;
    }

    /**
     * Optional summary describing what this tile controls.
     */
    public CharSequence getSummary(Context context) {
        if (mSummaryOverride != null) {
            return mSummaryOverride;
        }
        ensureMetadataNotStale(context);
        CharSequence summary = null;
        final PackageManager packageManager = context.getPackageManager();
        if (mMetaData != null) {
            if (mMetaData.containsKey(META_DATA_PREFERENCE_SUMMARY_URI)) {
                return null;
            }
            if (mMetaData.containsKey(META_DATA_PREFERENCE_SUMMARY)) {
                if (mMetaData.get(META_DATA_PREFERENCE_SUMMARY) instanceof Integer) {
                    try {
                        final Resources res =
                                packageManager.getResourcesForApplication(mActivityPackage);
                        summary = res.getString(mMetaData.getInt(META_DATA_PREFERENCE_SUMMARY));
                    } catch (PackageManager.NameNotFoundException | Resources.NotFoundException e) {
                        Log.d(TAG, "Couldn't find info", e);
                    }
                } else {
                    summary = mMetaData.getString(META_DATA_PREFERENCE_SUMMARY);
                }
            }
        }
        return summary;
    }

    public void setMetaData(Bundle metaData) {
        mMetaData = metaData;
    }

    /**
     * The metaData from the activity that defines this tile.
     */
    public Bundle getMetaData() {
        return mMetaData;
    }

    /**
     * Optional key to use for this tile.
     */
    public String getKey(Context context) {
        if (!hasKey()) {
            return null;
        }
        ensureMetadataNotStale(context);
        if (mMetaData.get(META_DATA_PREFERENCE_KEYHINT) instanceof Integer) {
            return context.getResources().getString(mMetaData.getInt(META_DATA_PREFERENCE_KEYHINT));
        } else {
            return mMetaData.getString(META_DATA_PREFERENCE_KEYHINT);
        }
    }

    public boolean hasKey() {
        return mMetaData != null && mMetaData.containsKey(META_DATA_PREFERENCE_KEYHINT);
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
        ensureMetadataNotStale(context);
        final ActivityInfo activityInfo = getActivityInfo(context);
        if (activityInfo == null) {
            Log.w(TAG, "Cannot find ActivityInfo for " + getDescription());
            return null;
        }

        int iconResId = mMetaData.getInt(META_DATA_PREFERENCE_ICON);
        // Set the icon
        if (iconResId == 0) {
            // Only fallback to activityinfo.icon if metadata does not contain ICON_URI.
            // ICON_URI should be loaded in app UI when need the icon object. Handling IPC at this
            // level is too complex because we don't have a strong threading contract for this class
            if (!mMetaData.containsKey(META_DATA_PREFERENCE_ICON_URI)) {
                iconResId = activityInfo.icon;
            }
        }
        if (iconResId != 0) {
            return Icon.createWithResource(activityInfo.packageName, iconResId);
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
        ensureMetadataNotStale(context);
        final String pkgName = context.getPackageName();
        // If this drawable is coming from outside Settings, tint it to match the color.
        final ActivityInfo activityInfo = getActivityInfo(context);
        return activityInfo != null
                && !TextUtils.equals(pkgName, activityInfo.packageName);
    }

    /**
     * Ensures metadata is not stale for this tile.
     */
    private void ensureMetadataNotStale(Context context) {
        final PackageManager pm = context.getApplicationContext().getPackageManager();

        try {
            final long lastUpdateTime = pm.getPackageInfo(mActivityPackage,
                    PackageManager.GET_META_DATA).lastUpdateTime;
            if (lastUpdateTime == mLastUpdateTime) {
                // All good. Do nothing
                return;
            }
            // App has been updated since we load metadata last time. Reload metadata.
            mActivityInfo = null;
            getActivityInfo(context);
            mLastUpdateTime = lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            Log.d(TAG, "Can't find package, probably uninstalled.");
        }
    }

    private ActivityInfo getActivityInfo(Context context) {
        if (mActivityInfo == null) {
            final PackageManager pm = context.getApplicationContext().getPackageManager();
            final Intent intent = new Intent().setClassName(mActivityPackage, mActivityName);
            final List<ResolveInfo> infoList =
                    pm.queryIntentActivities(intent, PackageManager.GET_META_DATA);
            if (infoList != null && !infoList.isEmpty()) {
                mActivityInfo = infoList.get(0).activityInfo;
                mMetaData = mActivityInfo.metaData;
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
            (lhs, rhs) -> rhs.getOrder() - lhs.getOrder();
}
