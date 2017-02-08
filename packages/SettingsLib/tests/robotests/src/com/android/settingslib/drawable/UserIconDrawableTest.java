/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.settingslib.drawable;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.android.settingslib.R;
import com.android.settingslib.SettingLibRobolectricTestRunner;
import com.android.settingslib.TestConfig;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static com.google.common.truth.Truth.assertThat;

@RunWith(SettingLibRobolectricTestRunner.class)
@Config(manifest = TestConfig.MANIFEST_PATH, sdk = TestConfig.SDK_VERSION)
public class UserIconDrawableTest {

    private UserIconDrawable mDrawable;

    @Test
    public void getConstantState_shouldNotBeNull() {
        final Bitmap b = BitmapFactory.decodeResource(
                RuntimeEnvironment.application.getResources(),
                R.drawable.home);
        mDrawable = new UserIconDrawable(100 /* size */).setIcon(b).bake();

        assertThat(mDrawable.getConstantState()).isNotNull();
    }
}
