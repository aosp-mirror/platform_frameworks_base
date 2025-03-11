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

package com.android.internal.widget;

import static com.google.common.truth.Truth.assertThat;

import android.app.Flags;
import android.app.Notification;
import android.graphics.Color;
import android.os.Bundle;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.filters.SmallTest;

import org.junit.Rule;
import org.junit.Test;

import java.util.List;

@SmallTest
@EnableFlags(Flags.FLAG_API_RICH_ONGOING)
public class NotificationProgressModelTest {

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Test(expected = IllegalArgumentException.class)
    public void throw_exception_on_transparent_indeterminate_color() {
        new NotificationProgressModel(Color.TRANSPARENT);
    }

    @Test(expected = IllegalArgumentException.class)
    public void throw_exception_on_empty_segments() {
        new NotificationProgressModel(List.of(),
                List.of(),
                10,
                false);
    }

    @Test(expected = IllegalArgumentException.class)
    public void throw_exception_on_negative_progress() {
        new NotificationProgressModel(
                List.of(new Notification.ProgressStyle.Segment(50).setColor(Color.YELLOW)),
                List.of(),
                -1,
                false);
    }

    @Test
    public void save_and_restore_indeterminate_progress_model() {
        // GIVEN
        final NotificationProgressModel savedModel = new NotificationProgressModel(Color.RED);
        final Bundle bundle = savedModel.toBundle();

        // WHEN
        final NotificationProgressModel restoredModel =
                NotificationProgressModel.fromBundle(bundle);

        // THEN
        assertThat(restoredModel.getIndeterminateColor()).isEqualTo(Color.RED);
        assertThat(restoredModel.isIndeterminate()).isTrue();
        assertThat(restoredModel.getProgress()).isEqualTo(-1);
        assertThat(restoredModel.getSegments()).isEmpty();
        assertThat(restoredModel.getPoints()).isEmpty();
        assertThat(restoredModel.isStyledByProgress()).isFalse();
    }

    @Test
    public void save_and_restore_non_indeterminate_progress_model() {
        // GIVEN
        final List<Notification.ProgressStyle.Segment> segments = List.of(
                new Notification.ProgressStyle.Segment(50).setColor(Color.YELLOW),
                new Notification.ProgressStyle.Segment(50).setColor(Color.LTGRAY));
        final List<Notification.ProgressStyle.Point> points = List.of(
                new Notification.ProgressStyle.Point(0).setColor(Color.RED),
                new Notification.ProgressStyle.Point(20).setColor(Color.BLUE));
        final NotificationProgressModel savedModel = new NotificationProgressModel(segments,
                points,
                100,
                true);

        final Bundle bundle = savedModel.toBundle();

        // WHEN
        final NotificationProgressModel restoredModel =
                NotificationProgressModel.fromBundle(bundle);

        // THEN
        assertThat(restoredModel.isIndeterminate()).isFalse();
        assertThat(restoredModel.getSegments()).isEqualTo(segments);
        assertThat(restoredModel.getPoints()).isEqualTo(points);
        assertThat(restoredModel.getProgress()).isEqualTo(100);
        assertThat(restoredModel.isStyledByProgress()).isTrue();
        assertThat(restoredModel.getIndeterminateColor()).isEqualTo(-1);
    }
}
