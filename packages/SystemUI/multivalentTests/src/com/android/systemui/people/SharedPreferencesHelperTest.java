/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.systemui.people;

import static com.android.systemui.people.PeopleSpaceUtils.INVALID_USER_ID;
import static com.android.systemui.people.PeopleSpaceUtils.PACKAGE_NAME;
import static com.android.systemui.people.PeopleSpaceUtils.SHORTCUT_ID;
import static com.android.systemui.people.PeopleSpaceUtils.USER_ID;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.people.widget.PeopleTileKey;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class SharedPreferencesHelperTest extends SysuiTestCase {
    private static final String SHORTCUT_ID_1 = "101";
    private static final String PACKAGE_NAME_1 = "package_name";
    private static final int USER_ID_1 = 0;

    private static final PeopleTileKey PEOPLE_TILE_KEY =
            new PeopleTileKey(SHORTCUT_ID_1, USER_ID_1, PACKAGE_NAME_1);

    private static final int WIDGET_ID = 1;

    private void setStorageForTile(PeopleTileKey peopleTileKey, int widgetId) {
        SharedPreferences widgetSp = mContext.getSharedPreferences(
                String.valueOf(widgetId),
                Context.MODE_PRIVATE);
        SharedPreferences.Editor widgetEditor = widgetSp.edit();
        widgetEditor.putString(PeopleSpaceUtils.PACKAGE_NAME, peopleTileKey.getPackageName());
        widgetEditor.putString(PeopleSpaceUtils.SHORTCUT_ID, peopleTileKey.getShortcutId());
        widgetEditor.putInt(PeopleSpaceUtils.USER_ID, peopleTileKey.getUserId());
        widgetEditor.apply();
    }

    @Test
    public void testGetPeopleTileKey() {
        setStorageForTile(PEOPLE_TILE_KEY, WIDGET_ID);

        SharedPreferences sp = mContext.getSharedPreferences(
                String.valueOf(WIDGET_ID),
                Context.MODE_PRIVATE);
        PeopleTileKey actual = SharedPreferencesHelper.getPeopleTileKey(sp);

        assertThat(actual.getPackageName()).isEqualTo(PACKAGE_NAME_1);
        assertThat(actual.getShortcutId()).isEqualTo(SHORTCUT_ID_1);
        assertThat(actual.getUserId()).isEqualTo(USER_ID_1);
    }

    @Test
    public void testSetPeopleTileKey() {
        SharedPreferences sp = mContext.getSharedPreferences(
                String.valueOf(WIDGET_ID),
                Context.MODE_PRIVATE);
        SharedPreferencesHelper.setPeopleTileKey(sp, PEOPLE_TILE_KEY);

        assertThat(sp.getString(SHORTCUT_ID, null)).isEqualTo(SHORTCUT_ID_1);
        assertThat(sp.getString(PACKAGE_NAME, null)).isEqualTo(PACKAGE_NAME_1);
        assertThat(sp.getInt(USER_ID, INVALID_USER_ID)).isEqualTo(USER_ID_1);
    }

    @Test
    public void testClear() {
        setStorageForTile(PEOPLE_TILE_KEY, WIDGET_ID);

        SharedPreferences sp = mContext.getSharedPreferences(
                String.valueOf(WIDGET_ID),
                Context.MODE_PRIVATE);
        SharedPreferencesHelper.clear(sp);

        assertThat(sp.getString(SHORTCUT_ID, null)).isEqualTo(null);
        assertThat(sp.getString(PACKAGE_NAME, null)).isEqualTo(null);
        assertThat(sp.getInt(USER_ID, INVALID_USER_ID)).isEqualTo(INVALID_USER_ID);

    }
}
