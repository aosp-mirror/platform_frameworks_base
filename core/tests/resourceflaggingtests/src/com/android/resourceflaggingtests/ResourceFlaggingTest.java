/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.resourceflaggingtests;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.FileUtils;
import android.util.DisplayMetrics;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.io.InputStream;

@RunWith(JUnit4.class)
@SmallTest
public class ResourceFlaggingTest {
    private Context mContext;
    private Resources mResources;

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getTargetContext();
        AssetManager assets = new AssetManager();
        assertThat(assets.addAssetPath(extractApkAndGetPath(R.raw.resapp))).isNotEqualTo(0);

        final DisplayMetrics dm = new DisplayMetrics();
        dm.setToDefaults();
        mResources = new Resources(assets, dm, new Configuration());
    }

    @Test
    public void testFlagDisabled() {
        assertThat(getBoolean("res1")).isTrue();
    }

    @Test
    public void testFlagEnabled() {
        assertThat(getBoolean("res2")).isTrue();
    }

    private boolean getBoolean(String name) {
        int resId = mResources.getIdentifier(name, "bool", "com.android.intenal.flaggedresources");
        assertThat(resId).isNotEqualTo(0);
        return mResources.getBoolean(resId);
    }

    private String extractApkAndGetPath(int id) throws Exception {
        final Resources resources = mContext.getResources();
        try (InputStream is = resources.openRawResource(id)) {
            File path = new File(mContext.getFilesDir(), resources.getResourceEntryName(id));
            path.deleteOnExit();
            FileUtils.copyToFileOrThrow(is, path);
            return path.getAbsolutePath();
        }
    }
}
