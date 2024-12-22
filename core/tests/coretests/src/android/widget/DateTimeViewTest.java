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
