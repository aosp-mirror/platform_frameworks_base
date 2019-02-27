/**
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
package android.ext.services.notification;

import static com.google.common.truth.Truth.assertThat;

import android.view.textclassifier.TextClassifier;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class EntityTypeCounterTest {
    private EntityTypeCounter mCounter;

    @Before
    public void setup() {
        mCounter = new EntityTypeCounter();
    }

    @Test
    public void testIncrementAndGetCount() {
        mCounter.increment(TextClassifier.TYPE_URL);
        mCounter.increment(TextClassifier.TYPE_URL);
        mCounter.increment(TextClassifier.TYPE_URL);

        mCounter.increment(TextClassifier.TYPE_PHONE);
        mCounter.increment(TextClassifier.TYPE_PHONE);

        assertThat(mCounter.getCount(TextClassifier.TYPE_URL)).isEqualTo(3);
        assertThat(mCounter.getCount(TextClassifier.TYPE_PHONE)).isEqualTo(2);
        assertThat(mCounter.getCount(TextClassifier.TYPE_DATE_TIME)).isEqualTo(0);
    }

    @Test
    public void testIncrementAndGetCount_typeDateAndDateTime() {
        mCounter.increment(TextClassifier.TYPE_DATE_TIME);
        mCounter.increment(TextClassifier.TYPE_DATE);

        assertThat(mCounter.getCount(TextClassifier.TYPE_DATE_TIME)).isEqualTo(2);
        assertThat(mCounter.getCount(TextClassifier.TYPE_DATE)).isEqualTo(2);
    }
}
