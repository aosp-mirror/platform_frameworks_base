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

import androidx.test.InstrumentationRegistry;
import androidx.test.annotation.UiThreadTest;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

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

    private static class TestDateTimeView extends DateTimeView {
        TestDateTimeView() {
            super(InstrumentationRegistry.getContext());
        }

        void attachedToWindow() {
            super.onAttachedToWindow();
        }

        void detachedFromWindow() {
            super.onDetachedFromWindow();
        }
    }
}
