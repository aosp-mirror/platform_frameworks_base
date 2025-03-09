/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.widget;

import android.view.View;
import android.view.ViewGroup;

import androidx.test.InstrumentationRegistry;
import androidx.test.annotation.UiThreadTest;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Duration;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class DateTimeViewTest {

    @UiThreadTest
    @Test
    public void additionalOnDetachedFromWindow_noException() {
        final TestDateTimeView dateTimeView = new TestDateTimeView();
        dateTimeView.attachedToWindow();
        dateTimeView.detachedFromWindow();
        // Even there is an additional detach (abnormal), DateTimeView should not unregister
        // receiver again that raises "java.lang.IllegalArgumentException: Receiver not registered".
        dateTimeView.detachedFromWindow();
    }

    @UiThreadTest
    @Test
    public void noChangeInRelativeText_doesNotTriggerRelayout() {
        // Week in the future is chosen because it'll result in a stable string during this test
        // run. This should be improved once the class is refactored to be more testable in
        // respect of clock retrieval.
        final long weekInTheFuture = System.currentTimeMillis() + Duration.ofDays(7).toMillis();
        final TestDateTimeView dateTimeView = new TestDateTimeView();
        dateTimeView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        dateTimeView.setShowRelativeTime(true);
        dateTimeView.setTime(weekInTheFuture);
        // View needs to be measured to request layout, skipping this would make this test pass
        // always.
        dateTimeView.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.UNSPECIFIED));
        dateTimeView.reset();

        // This should not change the text content and thus no relayout is expected.
        dateTimeView.setTime(weekInTheFuture + 1000);

        Assert.assertFalse(dateTimeView.wasLayoutRequested());
    }

    @UiThreadTest
    @Test
    public void disambiguationTextMask_none_noPastOrFutureDisambiguationText() {
        final TestDateTimeView dateTimeView = new TestDateTimeView();
        dateTimeView.setShowRelativeTime(true);
        dateTimeView.setRelativeTimeDisambiguationTextMask(0);

        // Minutes
        dateTimeView.setTime(System.currentTimeMillis() + Duration.ofMinutes(8).toMillis());
        Assert.assertFalse(dateTimeView.getText().toString().contains("in"));

        dateTimeView.setTime(System.currentTimeMillis() - Duration.ofMinutes(8).toMillis());
        Assert.assertFalse(dateTimeView.getText().toString().contains("ago"));

        // Hours
        dateTimeView.setTime(System.currentTimeMillis() + Duration.ofHours(4).toMillis());
        Assert.assertFalse(dateTimeView.getText().toString().contains("in"));

        dateTimeView.setTime(System.currentTimeMillis() - Duration.ofHours(4).toMillis());
        Assert.assertFalse(dateTimeView.getText().toString().contains("ago"));

        // Days
        dateTimeView.setTime(System.currentTimeMillis() + Duration.ofDays(14).toMillis());
        Assert.assertFalse(dateTimeView.getText().toString().contains("in"));

        dateTimeView.setTime(System.currentTimeMillis() - Duration.ofDays(14).toMillis());
        Assert.assertFalse(dateTimeView.getText().toString().contains("ago"));

        // Years
        dateTimeView.setTime(System.currentTimeMillis() + Duration.ofDays(400).toMillis());
        Assert.assertFalse(dateTimeView.getText().toString().contains("in"));

        dateTimeView.setTime(System.currentTimeMillis() - Duration.ofDays(400).toMillis());
        Assert.assertFalse(dateTimeView.getText().toString().contains("ago"));
    }

    @UiThreadTest
    @Test
    public void disambiguationTextMask_bothPastAndFuture_usesPastAndFutureDisambiguationText() {
        final TestDateTimeView dateTimeView = new TestDateTimeView();
        dateTimeView.setShowRelativeTime(true);
        dateTimeView.setRelativeTimeDisambiguationTextMask(
                DateTimeView.DISAMBIGUATION_TEXT_PAST | DateTimeView.DISAMBIGUATION_TEXT_FUTURE);

        // Minutes
        dateTimeView.setTime(System.currentTimeMillis() + Duration.ofMinutes(8).toMillis());
        Assert.assertTrue(dateTimeView.getText().toString().contains("in"));

        dateTimeView.setTime(System.currentTimeMillis() - Duration.ofMinutes(8).toMillis());
        Assert.assertTrue(dateTimeView.getText().toString().contains("ago"));

        // Hours
        dateTimeView.setTime(System.currentTimeMillis() + Duration.ofHours(4).toMillis());
        Assert.assertTrue(dateTimeView.getText().toString().contains("in"));

        dateTimeView.setTime(System.currentTimeMillis() - Duration.ofHours(4).toMillis());
        Assert.assertTrue(dateTimeView.getText().toString().contains("ago"));

        // Days
        dateTimeView.setTime(System.currentTimeMillis() + Duration.ofDays(14).toMillis());
        Assert.assertTrue(dateTimeView.getText().toString().contains("in"));

        dateTimeView.setTime(System.currentTimeMillis() - Duration.ofDays(14).toMillis());
        Assert.assertTrue(dateTimeView.getText().toString().contains("ago"));

        // Years
        dateTimeView.setTime(System.currentTimeMillis() + Duration.ofDays(400).toMillis());
        Assert.assertTrue(dateTimeView.getText().toString().contains("in"));

        dateTimeView.setTime(System.currentTimeMillis() - Duration.ofDays(400).toMillis());
        Assert.assertTrue(dateTimeView.getText().toString().contains("ago"));
    }

    @UiThreadTest
    @Test
    public void unitDisplayLength_shortest_noMediumText() {
        final TestDateTimeView dateTimeView = new TestDateTimeView();
        dateTimeView.setShowRelativeTime(true);
        dateTimeView.setRelativeTimeUnitDisplayLength(DateTimeView.UNIT_DISPLAY_LENGTH_SHORTEST);

        // Minutes
        dateTimeView.setTime(System.currentTimeMillis() + Duration.ofMinutes(8).toMillis());
        Assert.assertFalse(dateTimeView.getText().toString().contains("min"));

        dateTimeView.setTime(System.currentTimeMillis() - Duration.ofMinutes(8).toMillis());
        Assert.assertFalse(dateTimeView.getText().toString().contains("min"));

        // Hours
        dateTimeView.setTime(System.currentTimeMillis() + Duration.ofHours(4).toMillis());
        Assert.assertFalse(dateTimeView.getText().toString().contains("hr"));

        dateTimeView.setTime(System.currentTimeMillis() - Duration.ofHours(4).toMillis());
        Assert.assertFalse(dateTimeView.getText().toString().contains("hr"));

        // Days excluded because the string is the same for both shortest length and medium length

        // Years
        dateTimeView.setTime(System.currentTimeMillis() + Duration.ofDays(400).toMillis());
        Assert.assertFalse(dateTimeView.getText().toString().contains("yr"));

        dateTimeView.setTime(System.currentTimeMillis() - Duration.ofDays(400).toMillis());
        Assert.assertFalse(dateTimeView.getText().toString().contains("yr"));
    }

    @UiThreadTest
    @Test
    public void unitDisplayLength_medium_usesMediumText() {
        final TestDateTimeView dateTimeView = new TestDateTimeView();
        dateTimeView.setShowRelativeTime(true);
        dateTimeView.setRelativeTimeUnitDisplayLength(DateTimeView.UNIT_DISPLAY_LENGTH_MEDIUM);

        // Minutes
        dateTimeView.setTime(System.currentTimeMillis() + Duration.ofMinutes(8).toMillis());
        Assert.assertTrue(dateTimeView.getText().toString().contains("min"));

        dateTimeView.setTime(System.currentTimeMillis() - Duration.ofMinutes(8).toMillis());
        Assert.assertTrue(dateTimeView.getText().toString().contains("min"));

        // Hours
        dateTimeView.setTime(System.currentTimeMillis() + Duration.ofHours(4).toMillis());
        Assert.assertTrue(dateTimeView.getText().toString().contains("hr"));

        dateTimeView.setTime(System.currentTimeMillis() - Duration.ofHours(4).toMillis());
        Assert.assertTrue(dateTimeView.getText().toString().contains("hr"));

        // Days excluded because the string is the same for both shortest length and medium length

        // Years
        dateTimeView.setTime(System.currentTimeMillis() + Duration.ofDays(400).toMillis());
        Assert.assertTrue(dateTimeView.getText().toString().contains("yr"));

        dateTimeView.setTime(System.currentTimeMillis() - Duration.ofDays(400).toMillis());
        Assert.assertTrue(dateTimeView.getText().toString().contains("yr"));
    }

    private static class TestDateTimeView extends DateTimeView {
        private boolean mRequestedLayout = false;

        TestDateTimeView() {
            super(InstrumentationRegistry.getContext());
        }

        void attachedToWindow() {
            super.onAttachedToWindow();
        }

        void detachedFromWindow() {
            super.onDetachedFromWindow();
        }

        public void requestLayout() {
            super.requestLayout();
            mRequestedLayout = true;
        }

        public boolean wasLayoutRequested() {
            return mRequestedLayout;
        }

        public void reset() {
            mRequestedLayout = false;
        }
    }
}
