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

package com.android.settingslib.mobile;

import static com.android.settingslib.mobile.MobileIconCarrierIdOverridesImpl.parseNetworkIconOverrideTypedArray;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.res.TypedArray;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Map;

@RunWith(RobolectricTestRunner.class)
public final class MobileIconCarrierIdOverridesTest {
    private static final String OVERRIDE_ICON_1_NAME = "name_1";
    private static final int OVERRIDE_ICON_1_RES = 1;

    private static final String OVERRIDE_ICON_2_NAME = "name_2";
    private static final int OVERRIDE_ICON_2_RES = 2;

    NetworkOverrideTypedArrayMock mResourceMock;

    @Before
    public void setUp() {
        mResourceMock = new NetworkOverrideTypedArrayMock(
                new String[] { OVERRIDE_ICON_1_NAME, OVERRIDE_ICON_2_NAME },
                new int[] { OVERRIDE_ICON_1_RES, OVERRIDE_ICON_2_RES }
        );
    }

    @Test
    public void testParse_singleOverride() {
        mResourceMock.setOverrides(
                new String[] { OVERRIDE_ICON_1_NAME },
                new int[] { OVERRIDE_ICON_1_RES }
        );

        Map<String, Integer> parsed = parseNetworkIconOverrideTypedArray(mResourceMock.getMock());

        assertThat(parsed.get(OVERRIDE_ICON_1_NAME)).isEqualTo(OVERRIDE_ICON_1_RES);
    }

    @Test
    public void testParse_multipleOverrides() {
        mResourceMock.setOverrides(
                new String[] { OVERRIDE_ICON_1_NAME, OVERRIDE_ICON_2_NAME },
                new int[] { OVERRIDE_ICON_1_RES, OVERRIDE_ICON_2_RES }
        );

        Map<String, Integer> parsed = parseNetworkIconOverrideTypedArray(mResourceMock.getMock());

        assertThat(parsed.get(OVERRIDE_ICON_2_NAME)).isEqualTo(OVERRIDE_ICON_2_RES);
        assertThat(parsed.get(OVERRIDE_ICON_1_NAME)).isEqualTo(OVERRIDE_ICON_1_RES);
    }

    @Test
    public void testParse_nonexistentKey_isNull() {
        mResourceMock.setOverrides(
                new String[] { OVERRIDE_ICON_1_NAME },
                new int[] { OVERRIDE_ICON_1_RES }
        );

        Map<String, Integer> parsed = parseNetworkIconOverrideTypedArray(mResourceMock.getMock());

        assertThat(parsed.get(OVERRIDE_ICON_2_NAME)).isNull();
    }

    static class NetworkOverrideTypedArrayMock {
        private Object[] mInterleaved;

        private final TypedArray mMockTypedArray = mock(TypedArray.class);

        NetworkOverrideTypedArrayMock(
                String[] networkTypes,
                int[] iconOverrides) {

            mInterleaved = interleaveTypes(networkTypes, iconOverrides);

            doAnswer(invocation -> {
                return mInterleaved[(int) invocation.getArgument(0)];
            }).when(mMockTypedArray).getString(/* index */ anyInt());

            doAnswer(invocation -> {
                return mInterleaved[(int) invocation.getArgument(0)];
            }).when(mMockTypedArray).getResourceId(/* index */ anyInt(), /* default */ anyInt());

            when(mMockTypedArray.length()).thenAnswer(invocation -> {
                return mInterleaved.length;
            });
        }

        TypedArray getMock() {
            return mMockTypedArray;
        }

        void setOverrides(String[] types, int[] resIds) {
            mInterleaved = interleaveTypes(types, resIds);
        }

        private Object[] interleaveTypes(String[] strs, int[] ints) {
            assertThat(strs.length).isEqualTo(ints.length);

            Object[] ret = new Object[strs.length * 2];

            // Keep track of where we are in the interleaved array, but iterate the overrides
            int interleavedIndex = 0;
            for (int i = 0; i < strs.length; i++) {
                ret[interleavedIndex] = strs[i];
                interleavedIndex += 1;
                ret[interleavedIndex] = ints[i];
                interleavedIndex += 1;
            }
            return ret;
        }
    }
}
