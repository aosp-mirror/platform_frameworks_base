package com.android.settingslib.drawer;

import static com.android.settingslib.drawer.TileUtils.META_DATA_KEY_ORDER;
import static com.android.settingslib.drawer.TileUtils.META_DATA_KEY_PROFILE;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_ICON;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_ICON_URI;
import static com.android.settingslib.drawer.TileUtils.PROFILE_ALL;
import static com.android.settingslib.drawer.TileUtils.PROFILE_PRIMARY;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.os.Bundle;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
public class TileTest {

    private Context mContext;
    private ActivityInfo mActivityInfo;
    private Tile mTile;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.application;
        mActivityInfo = new ActivityInfo();
        mActivityInfo.applicationInfo = new ApplicationInfo();
        mActivityInfo.packageName = mContext.getPackageName();
        mActivityInfo.name = "abc";
        mActivityInfo.icon = com.android.internal.R.drawable.ic_plus;
        mActivityInfo.metaData = new Bundle();
        mTile = new Tile(mActivityInfo, "category");
    }

    @Test
    public void isPrimaryProfileOnly_profilePrimary_shouldReturnTrue() {
        mActivityInfo.metaData.putString(META_DATA_KEY_PROFILE, PROFILE_PRIMARY);
        assertThat(mTile.isPrimaryProfileOnly()).isTrue();
    }

    @Test
    public void isPrimaryProfileOnly_profileAll_shouldReturnFalse() {
        mActivityInfo.metaData.putString(META_DATA_KEY_PROFILE, PROFILE_ALL);
        assertThat(mTile.isPrimaryProfileOnly()).isFalse();
    }

    @Test
    public void isPrimaryProfileOnly_noExplicitValue_shouldReturnFalse() {
        assertThat(mTile.isPrimaryProfileOnly()).isFalse();
    }

    @Test
    public void isPrimaryProfileOnly_nullMetadata_shouldReturnFalse() {
        mActivityInfo.metaData = null;
        assertThat(mTile.isPrimaryProfileOnly()).isFalse();
    }

    @Test
    public void getIcon_noContextOrMetadata_returnNull() {
        mActivityInfo.metaData = null;
        final Tile tile = new Tile(mActivityInfo, "category");
        assertThat(tile.getIcon(null)).isNull();
        assertThat(tile.getIcon(RuntimeEnvironment.application)).isNull();
    }

    @Test
    public void getIcon_providedByUri_returnNull() {
        mActivityInfo.metaData.putString(META_DATA_PREFERENCE_ICON_URI, "content://foobar/icon");

        assertThat(mTile.getIcon(RuntimeEnvironment.application)).isNull();
    }

    @Test
    public void getIcon_hasIconMetadata_returnIcon() {
        mActivityInfo.metaData.putInt(META_DATA_PREFERENCE_ICON, android.R.drawable.ic_info);

        assertThat(mTile.getIcon(RuntimeEnvironment.application).getResId())
                .isEqualTo(android.R.drawable.ic_info);
    }

    @Test
    public void getIcon_noIconMetadata_returnActivityIcon() {
        mActivityInfo.metaData.putInt(META_DATA_PREFERENCE_ICON, 0);

        assertThat(mTile.getIcon(RuntimeEnvironment.application).getResId())
                .isEqualTo(mActivityInfo.icon);
    }

    @Test
    public void isIconTintable_hasMetadata_shouldReturnIconTintableMetadata() {
        final Tile tile = new Tile(mActivityInfo, "category");

        mActivityInfo.metaData.putBoolean(TileUtils.META_DATA_PREFERENCE_ICON_TINTABLE, false);
        assertThat(tile.isIconTintable(RuntimeEnvironment.application)).isFalse();

        mActivityInfo.metaData.putBoolean(TileUtils.META_DATA_PREFERENCE_ICON_TINTABLE, true);
        assertThat(tile.isIconTintable(RuntimeEnvironment.application)).isTrue();
    }

    @Test
    public void isIconTintable_noIcon_shouldReturnFalse() {
        final Tile tile = new Tile(mActivityInfo, "category");

        assertThat(tile.isIconTintable(RuntimeEnvironment.application)).isFalse();
    }

    @Test
    public void isIconTintable_noTintableMetadata_shouldReturnFalse() {
        final Tile tile = new Tile(mActivityInfo, "category");
        mActivityInfo.metaData.putInt(META_DATA_PREFERENCE_ICON, android.R.drawable.ic_info);

        assertThat(tile.isIconTintable(RuntimeEnvironment.application)).isFalse();
    }

    @Test
    public void getPriority_noMetadata_return0() {
        final Tile tile = new Tile(mActivityInfo, "category");

        assertThat(tile.getOrder()).isEqualTo(0);
    }

    @Test
    public void getPriority_badMetadata_return0() {
        mActivityInfo.metaData.putString(META_DATA_KEY_ORDER, "1");

        final Tile tile = new Tile(mActivityInfo, "category");

        assertThat(tile.getOrder()).isEqualTo(0);
    }

    @Test
    public void getPriority_validMetadata_returnMetadataValue() {
        mActivityInfo.metaData.putInt(META_DATA_KEY_ORDER, 1);

        final Tile tile = new Tile(mActivityInfo, "category");

        assertThat(tile.getOrder()).isEqualTo(1);
    }

    @Test
    public void getTitle_shouldEnsureMetadataNotStale() {
        final ResolveInfo info = new ResolveInfo();
        info.activityInfo = mActivityInfo;
        final ShadowPackageManager spm = Shadow.extract(mContext.getPackageManager());
        spm.addResolveInfoForIntent(
                new Intent().setClassName(mActivityInfo.packageName, mActivityInfo.name), info);

        final Tile tile = new Tile(mActivityInfo, "category");
        final long staleTimeStamp = -10000;
        tile.mLastUpdateTime = staleTimeStamp;

        tile.getTitle(RuntimeEnvironment.application);

        assertThat(tile.mLastUpdateTime).isNotEqualTo(staleTimeStamp);
    }

    @Test
    public void getTitle_noActivity_returnNull() {
        final ResolveInfo info = new ResolveInfo();
        info.activityInfo = mActivityInfo;
        final ShadowPackageManager spm = Shadow.extract(mContext.getPackageManager());
        spm.removePackage(mActivityInfo.packageName);

        final Tile tile = new Tile(mActivityInfo, "category");
        ReflectionHelpers.setField(tile, "mActivityInfo", null);

        assertThat(tile.getTitle(RuntimeEnvironment.application)).isNull();
    }
}
