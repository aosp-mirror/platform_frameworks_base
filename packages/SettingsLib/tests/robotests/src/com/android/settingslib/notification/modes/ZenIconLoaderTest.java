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

import static android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY;

import static com.google.common.truth.Truth.assertThat;

import android.app.AutomaticZenRule;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.service.notification.ZenPolicy;

import com.google.common.util.concurrent.ListenableFuture;
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
    public void getIcon_systemOwnedRuleWithIcon_loads() throws Exception {
        AutomaticZenRule systemRule = newRuleBuilder()
                .setPackage("android")
                .setIconResId(android.R.drawable.ic_media_play)
                .build();

        ListenableFuture<Drawable> loadFuture = mLoader.getIcon(mContext, systemRule);
        assertThat(loadFuture.isDone()).isTrue();
        assertThat(loadFuture.get()).isNotNull();
    }

    @Test
    public void getIcon_ruleWithoutSpecificIcon_loadsFallback() throws Exception {
        AutomaticZenRule rule = newRuleBuilder()
                .setType(AutomaticZenRule.TYPE_DRIVING)
                .setPackage("com.blah")
                .build();

        ListenableFuture<Drawable> loadFuture = mLoader.getIcon(mContext, rule);
        assertThat(loadFuture.isDone()).isTrue();
        assertThat(loadFuture.get()).isNotNull();
    }

    @Test
    public void getIcon_ruleWithAppIconWithLoadFailure_loadsFallback() throws Exception {
        AutomaticZenRule rule = newRuleBuilder()
                .setType(AutomaticZenRule.TYPE_DRIVING)
                .setPackage("com.blah")
                .setIconResId(-123456)
                .build();

        ListenableFuture<Drawable> loadFuture = mLoader.getIcon(mContext, rule);
        assertThat(loadFuture.get()).isNotNull();
    }

    private static AutomaticZenRule.Builder newRuleBuilder() {
        return new AutomaticZenRule.Builder("Driving", Uri.parse("drive"))
                .setInterruptionFilter(INTERRUPTION_FILTER_PRIORITY)
                .setZenPolicy(new ZenPolicy.Builder().build());
    }
}
