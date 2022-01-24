/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.dreams;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.settingslib.dream.DreamBackend;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class ComplicationProviderTest {
    private TestComplicationProvider mComplicationProvider;

    @Before
    public void setup() {
        mComplicationProvider = new TestComplicationProvider();
    }

    @Test
    public void testConvertComplicationType() {
        assertEquals(ComplicationProvider.COMPLICATION_TYPE_TIME,
                mComplicationProvider.convertComplicationType(DreamBackend.COMPLICATION_TYPE_TIME));
        assertEquals(ComplicationProvider.COMPLICATION_TYPE_DATE,
                mComplicationProvider.convertComplicationType(DreamBackend.COMPLICATION_TYPE_DATE));
        assertEquals(ComplicationProvider.COMPLICATION_TYPE_WEATHER,
                mComplicationProvider.convertComplicationType(
                        DreamBackend.COMPLICATION_TYPE_WEATHER));
        assertEquals(ComplicationProvider.COMPLICATION_TYPE_AIR_QUALITY,
                mComplicationProvider.convertComplicationType(
                        DreamBackend.COMPLICATION_TYPE_AIR_QUALITY));
        assertEquals(ComplicationProvider.COMPLICATION_TYPE_CAST_INFO,
                mComplicationProvider.convertComplicationType(
                        DreamBackend.COMPLICATION_TYPE_CAST_INFO));
    }

    private static class TestComplicationProvider implements ComplicationProvider {
        @Override
        public void onCreateComplication(Context context,
                ComplicationHost.CreationCallback creationCallback,
                ComplicationHost.InteractionCallback interactionCallback) {
        }
    }
}
