package com.android.settingslib.drawer;

import static com.android.settingslib.drawer.TileUtils.META_DATA_KEY_PROFILE;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_ICON;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_ICON_URI;
import static com.android.settingslib.drawer.TileUtils.PROFILE_ALL;
import static com.android.settingslib.drawer.TileUtils.PROFILE_PRIMARY;

import static com.google.common.truth.Truth.assertThat;

import android.content.pm.ActivityInfo;
import android.os.Bundle;

import com.android.settingslib.R;
import com.android.settingslib.SettingsLibRobolectricTestRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;


@RunWith(SettingsLibRobolectricTestRunner.class)
public class TileTest {

    private ActivityInfo mActivityInfo;
    private Tile mTile;

    @Before
    public void setUp() {
        mActivityInfo = new ActivityInfo();
        mActivityInfo.packageName = RuntimeEnvironment.application.getPackageName();
        mActivityInfo.icon = R.drawable.ic_plus;
        mTile = new Tile(mActivityInfo);
        mTile.metaData = new Bundle();
    }

    @Test
    public void isPrimaryProfileOnly_profilePrimary_shouldReturnTrue() {
        mTile.metaData.putString(META_DATA_KEY_PROFILE, PROFILE_PRIMARY);
        assertThat(mTile.isPrimaryProfileOnly()).isTrue();
    }

    @Test
    public void isPrimaryProfileOnly_profileAll_shouldReturnFalse() {
        mTile.metaData.putString(META_DATA_KEY_PROFILE, PROFILE_ALL);
        assertThat(mTile.isPrimaryProfileOnly()).isFalse();
    }

    @Test
    public void isPrimaryProfileOnly_noExplicitValue_shouldReturnFalse() {
        assertThat(mTile.isPrimaryProfileOnly()).isFalse();
    }

    @Test
    public void isPrimaryProfileOnly_nullMetadata_shouldReturnFalse() {
        mTile.metaData = null;
        assertThat(mTile.isPrimaryProfileOnly()).isFalse();
    }

    @Test
    public void getIcon_noActivityOrMetadata_returnNull() {
        final Tile tile1 = new Tile((ActivityInfo) null);
        assertThat(tile1.getIcon()).isNull();

        final Tile tile2 = new Tile(new ActivityInfo());
        assertThat(tile2.getIcon()).isNull();
    }

    @Test
    public void getIcon_providedByUri_returnNull() {
        mTile.metaData.putString(META_DATA_PREFERENCE_ICON_URI, "content://foobar/icon");

        assertThat(mTile.getIcon()).isNull();
    }

    @Test
    public void getIcon_hasIconMetadata_returnIcon() {
        mTile.metaData.putInt(META_DATA_PREFERENCE_ICON, R.drawable.ic_info);

        assertThat(mTile.getIcon().getResId()).isEqualTo(R.drawable.ic_info);
    }

    @Test
    public void getIcon_noIconMetadata_returnActivityIcon() {
        mTile.metaData.putInt(META_DATA_PREFERENCE_ICON, 0);

        assertThat(mTile.getIcon().getResId()).isEqualTo(mActivityInfo.icon);
    }
}
