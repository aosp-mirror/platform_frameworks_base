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

package com.android.settingslib.notification.modes;

import static com.google.common.truth.Truth.assertThat;

import android.app.AutomaticZenRule;
import android.content.Context;
import android.service.notification.SystemZenRules;

import com.google.common.util.concurrent.MoreExecutors;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class ZenIconLoaderTest {

    private Context mContext;
    private ZenIconLoader mLoader;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.application;
        mLoader = new ZenIconLoader(MoreExecutors.newDirectExecutorService());
    }

    @Test
    public void getIcon_systemOwnedModeWithIcon_loads() throws Exception {
        ZenMode mode = new TestModeBuilder()
                .setPackage(SystemZenRules.PACKAGE_ANDROID)
                .setIconResId(android.R.drawable.ic_media_play)
                .build();

        ZenIcon icon = mLoader.getIcon(mContext, mode).get();

        assertThat(icon.drawable()).isNotNull();
        assertThat(icon.key().resPackage()).isNull();
        assertThat(icon.key().resId()).isEqualTo(android.R.drawable.ic_media_play);
    }

    @Test
    public void getIcon_modeWithoutSpecificIcon_loadsFallback() throws Exception {
        ZenMode mode = new TestModeBuilder()
                .setType(AutomaticZenRule.TYPE_DRIVING)
                .setPackage("com.blah")
                .build();

        ZenIcon icon = mLoader.getIcon(mContext, mode).get();

        assertThat(icon.drawable()).isNotNull();
        assertThat(icon.key().resPackage()).isNull();
        assertThat(icon.key().resId()).isEqualTo(
                com.android.internal.R.drawable.ic_zen_mode_type_driving);
    }

    @Test
    public void getIcon_ruleWithAppIconWithLoadFailure_loadsFallback() throws Exception {
        ZenMode mode = new TestModeBuilder()
                .setType(AutomaticZenRule.TYPE_DRIVING)
                .setPackage("com.blah")
                .setIconResId(-123456)
                .build();

        ZenIcon icon = mLoader.getIcon(mContext, mode).get();

        assertThat(icon.drawable()).isNotNull();
        assertThat(icon.key().resPackage()).isNull();
        assertThat(icon.key().resId()).isEqualTo(
                com.android.internal.R.drawable.ic_zen_mode_type_driving);
    }

    @Test
    public void getIcon_cachesCustomIcons() throws Exception {
        ZenMode mode = new TestModeBuilder()
                .setPackage(SystemZenRules.PACKAGE_ANDROID)
                .setIconResId(android.R.drawable.ic_media_play)
                .build();

        ZenIcon iconOne = mLoader.getIcon(mContext, mode).get();
        ZenIcon iconTwo = mLoader.getIcon(mContext, mode).get();

        assertThat(iconOne.drawable()).isSameInstanceAs(iconTwo.drawable());
    }

    @Test
    public void getIcon_cachesDefaultIcons() throws Exception {
        ZenMode mode = new TestModeBuilder()
                .setPackage(SystemZenRules.PACKAGE_ANDROID)
                .setType(AutomaticZenRule.TYPE_IMMERSIVE)
                .build();

        ZenIcon iconOne = mLoader.getIcon(mContext, mode).get();
        ZenIcon iconTwo = mLoader.getIcon(mContext, mode).get();

        assertThat(iconOne.drawable()).isSameInstanceAs(iconTwo.drawable());
    }
}
