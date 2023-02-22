/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.settingslib.drawer;

import static com.google.common.truth.Truth.assertThat;

import android.util.ArraySet;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Set;

@RunWith(RobolectricTestRunner.class)
public class CategoryKeyTest {

    @Test
    public void testKeyCompatMap_allOldCategoryKeyAreMapped() {
        assertThat(CategoryKey.KEY_COMPAT_MAP.size()).isEqualTo(4);
    }

    @Test
    public void removingAnyKeyBreaksCompiler() {
        // The keys in this test can be added but cannot be removed. Removing any key will remove
        // categories from Settings app. Bad things will happen.
        final Set<String> allKeys = new ArraySet<>();

        // DO NOT REMOVE ANYTHING BELOW
        allKeys.add(CategoryKey.CATEGORY_HOMEPAGE);
        allKeys.add(CategoryKey.CATEGORY_CONNECT);
        allKeys.add(CategoryKey.CATEGORY_DEVICE);
        allKeys.add(CategoryKey.CATEGORY_APPS);
        allKeys.add(CategoryKey.CATEGORY_APPS_DEFAULT);
        allKeys.add(CategoryKey.CATEGORY_BATTERY);
        allKeys.add(CategoryKey.CATEGORY_DISPLAY);
        allKeys.add(CategoryKey.CATEGORY_SOUND);
        allKeys.add(CategoryKey.CATEGORY_STORAGE);
        allKeys.add(CategoryKey.CATEGORY_SECURITY);
        allKeys.add(CategoryKey.CATEGORY_SECURITY_LOCKSCREEN);
        allKeys.add(CategoryKey.CATEGORY_ACCOUNT);
        allKeys.add(CategoryKey.CATEGORY_ACCOUNT_DETAIL);
        allKeys.add(CategoryKey.CATEGORY_SYSTEM);
        allKeys.add(CategoryKey.CATEGORY_SYSTEM_LANGUAGE);
        allKeys.add(CategoryKey.CATEGORY_SYSTEM_DEVELOPMENT);
        allKeys.add(CategoryKey.CATEGORY_GESTURES);
        allKeys.add(CategoryKey.CATEGORY_NIGHT_DISPLAY);
        allKeys.add(CategoryKey.CATEGORY_SMART_BATTERY_SETTINGS);
        allKeys.add(CategoryKey.CATEGORY_COMMUNAL_SETTINGS);
        // DO NOT REMOVE ANYTHING ABOVE

        assertThat(allKeys.size()).isEqualTo(20);
    }
}
