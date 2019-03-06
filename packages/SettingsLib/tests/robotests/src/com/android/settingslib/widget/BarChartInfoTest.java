/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.settingslib.widget;

import static com.google.common.truth.Truth.assertThat;

import android.view.View;

import androidx.annotation.StringRes;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class BarChartInfoTest {

    @Rule
    public final ExpectedException thrown = ExpectedException.none();
    @StringRes
    private final int mTitle = 0x11111111;
    @StringRes
    private final int mDetails = 0x22222222;
    @StringRes
    private final int mEmptyText = 0x33333333;
    private final View.OnClickListener mClickListener = v -> {
    };

    @Test
    public void builder_shouldSetFieldsInTheInfo() {
        final BarChartInfo barChartInfo = new BarChartInfo.Builder()
                .setTitle(mTitle)
                .setDetails(mDetails)
                .setEmptyText(mEmptyText)
                .setDetailsOnClickListener(mClickListener)
                .build();
        assertThat(barChartInfo.getTitle()).isEqualTo(mTitle);
        assertThat(barChartInfo.getDetails()).isEqualTo(mDetails);
        assertThat(barChartInfo.getEmptyText()).isEqualTo(mEmptyText);
        assertThat(barChartInfo.getDetailsOnClickListener()).isEqualTo(mClickListener);
    }

    @Test
    public void builder_noTitle_shouldThrowIllegalStateException() {
        thrown.expect(IllegalStateException.class);

        new BarChartInfo.Builder()
                .setDetails(mDetails)
                .setEmptyText(mEmptyText)
                .setDetailsOnClickListener(mClickListener)
                .build();
    }

    @Test
    public void addBarViewInfo_oneBarViewInfo_shouldSetOneBarViewInfo() {
        final BarViewInfo barViewInfo = new BarViewInfo(
                null /* icon */,
                50,
                mTitle,
                null);

        final BarChartInfo mBarChartInfo = new BarChartInfo.Builder()
                .setTitle(mTitle)
                .setDetails(mDetails)
                .setEmptyText(mEmptyText)
                .setDetailsOnClickListener(mClickListener)
                .addBarViewInfo(barViewInfo)
                .build();

        assertThat(mBarChartInfo.getBarViewInfos().length).isEqualTo(1);
        assertThat(mBarChartInfo.getBarViewInfos()[0]).isEqualTo(barViewInfo);
    }

    @Test
    public void addBarViewInfo_maxNumberOfInfoAllowed_shouldSetMaxBarViewInfos() {
        final BarViewInfo barViewInfo = new BarViewInfo(
                null /* icon */,
                50,
                mTitle,
                null);
        final BarChartInfo mBarChartInfo = new BarChartInfo.Builder()
                .setTitle(mTitle)
                .setDetails(mDetails)
                .setEmptyText(mEmptyText)
                .setDetailsOnClickListener(mClickListener)
                .addBarViewInfo(barViewInfo)
                .addBarViewInfo(barViewInfo)
                .addBarViewInfo(barViewInfo)
                .addBarViewInfo(barViewInfo)
                .build();

        assertThat(mBarChartInfo.getBarViewInfos().length).isEqualTo(4);
    }

    @Test
    public void addBarViewInfo_moreInfosThanMaxAllowed_shouldThrowIllegalStateException() {
        thrown.expect(IllegalStateException.class);

        final BarViewInfo barViewInfo = new BarViewInfo(
                null /* icon */,
                50,
                mTitle,
                null);
        new BarChartInfo.Builder()
                .setTitle(mTitle)
                .setDetails(mDetails)
                .setEmptyText(mEmptyText)
                .setDetailsOnClickListener(mClickListener)
                .addBarViewInfo(barViewInfo)
                .addBarViewInfo(barViewInfo)
                .addBarViewInfo(barViewInfo)
                .addBarViewInfo(barViewInfo)
                .addBarViewInfo(barViewInfo)
                .build();
    }
}
