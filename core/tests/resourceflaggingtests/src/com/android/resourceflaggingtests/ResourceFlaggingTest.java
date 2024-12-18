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
import android.content.ContextWrapper;
import android.content.res.ApkAssets;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.FileUtils;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.intenal.flaggedresources.R;

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
        assets.setApkAssets(
                new ApkAssets[]{
                        ApkAssets.loadFromPath(
                                extractApkAndGetPath(
                                        com.android.resourceflaggingtests.R.raw.resapp
                                )
                        )
                }, true);

        final DisplayMetrics dm = new DisplayMetrics();
        dm.setToDefaults();
        mResources = new Resources(assets, dm, new Configuration());
    }

    @Test
    public void testFlagDisabled() {
        assertThat(mResources.getBoolean(R.bool.bool1)).isTrue();
    }

    @Test
    public void testFlagEnabled() {
        assertThat(mResources.getBoolean(R.bool.bool2)).isTrue();
    }

    @Test
    public void testFlagEnabledDifferentCompilationUnit() {
        assertThat(mResources.getBoolean(R.bool.bool3)).isTrue();
    }

    @Test
    public void testFlagDisabledStringArrayElement() {
        assertThat(mResources.getStringArray(R.array.strarr1))
                .isEqualTo(new String[]{"one", "two", "three"});
    }

    @Test
    public void testFlagDisabledIntArrayElement() {
        assertThat(mResources.getIntArray(R.array.intarr1)).isEqualTo(new int[]{1, 2, 3});
    }

    @Test
    public void testLayoutWithDisabledElements() {
        LinearLayout ll = (LinearLayout) getLayoutInflater().inflate(R.layout.layout1, null);
        assertThat(ll).isNotNull();
        assertThat((View) ll.findViewById(R.id.text1)).isNotNull();
        assertThat((View) ll.findViewById(R.id.disabled_text)).isNull();
        assertThat((View) ll.findViewById(R.id.text2)).isNotNull();
    }

    private LayoutInflater getLayoutInflater() {
        ContextWrapper c = new ContextWrapper(mContext) {
            private LayoutInflater mInflater;

            @Override
            public Resources getResources() {
                return mResources;
            }

            @Override
            public Object getSystemService(String name) {
                if (LAYOUT_INFLATER_SERVICE.equals(name)) {
                    if (mInflater == null) {
                        mInflater = LayoutInflater.from(getBaseContext()).cloneInContext(this);
                    }
                    return mInflater;
                }
                return super.getSystemService(name);
            }
        };
        return LayoutInflater.from(c);
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
