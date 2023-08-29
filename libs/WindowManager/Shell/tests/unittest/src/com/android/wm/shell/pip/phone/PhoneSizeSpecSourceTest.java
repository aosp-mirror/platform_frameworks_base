/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.wm.shell.pip.phone;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.os.SystemProperties;
import android.testing.AndroidTestingRunner;
import android.util.Size;
import android.view.DisplayInfo;

import com.android.dx.mockito.inline.extended.StaticMockitoSession;
import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.pip.PhoneSizeSpecSource;
import com.android.wm.shell.common.pip.PipDisplayLayoutState;
import com.android.wm.shell.common.pip.SizeSpecSource;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.exceptions.misusing.InvalidUseOfMatchersException;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Unit test against {@link PhoneSizeSpecSource}
 */
@RunWith(AndroidTestingRunner.class)
public class PhoneSizeSpecSourceTest extends ShellTestCase {
    /** A sample overridden min edge size. */
    private static final int OVERRIDE_MIN_EDGE_SIZE = 40;
    /** A sample default min edge size */
    private static final int DEFAULT_MIN_EDGE_SIZE = 40;
    /** Display edge size */
    private static final int DISPLAY_EDGE_SIZE = 1000;
    /** Default sizing percentage */
    private static final float DEFAULT_PERCENT = 0.6f;
    /** Minimum sizing percentage */
    private static final float MIN_PERCENT = 0.5f;
    /** Aspect ratio that the new PIP size spec logic optimizes for. */
    private static final float OPTIMIZED_ASPECT_RATIO = 9f / 16;

    /** A map of aspect ratios to be tested to expected sizes */
    private static Map<Float, Size> sExpectedMaxSizes;
    private static Map<Float, Size> sExpectedDefaultSizes;
    private static Map<Float, Size> sExpectedMinSizes;
    /** A static mockito session object to mock {@link SystemProperties} */
    private static StaticMockitoSession sStaticMockitoSession;

    @Mock private Context mContext;
    @Mock private Resources mResources;

    private PipDisplayLayoutState mPipDisplayLayoutState;
    private SizeSpecSource mSizeSpecSource;

    /**
     * Sets up static Mockito session for SystemProperties and mocks necessary static methods.
     */
    private static void setUpStaticSystemPropertiesSession() {
        sStaticMockitoSession = mockitoSession()
                .mockStatic(SystemProperties.class).startMocking();
        when(SystemProperties.get(anyString(), anyString())).thenAnswer(invocation -> {
            String property = invocation.getArgument(0);
            if (property.equals("com.android.wm.shell.pip.phone.def_percentage")) {
                return Float.toString(DEFAULT_PERCENT);
            } else if (property.equals("com.android.wm.shell.pip.phone.min_percentage")) {
                return Float.toString(MIN_PERCENT);
            }

            // throw an exception if illegal arguments are used for these tests
            throw new InvalidUseOfMatchersException(
                String.format("Argument %s does not match", property)
            );
        });
    }

    /**
     * Initializes the map with the aspect ratios to be tested and corresponding expected max sizes.
     */
    private static void initExpectedSizes() {
        sExpectedMaxSizes = new HashMap<>();
        sExpectedDefaultSizes = new HashMap<>();
        sExpectedMinSizes = new HashMap<>();

        sExpectedMaxSizes.put(16f / 9, new Size(1000, 563));
        sExpectedDefaultSizes.put(16f / 9, new Size(600, 338));
        sExpectedMinSizes.put(16f / 9, new Size(501, 282));

        sExpectedMaxSizes.put(4f / 3, new Size(893, 670));
        sExpectedDefaultSizes.put(4f / 3, new Size(536, 402));
        sExpectedMinSizes.put(4f / 3, new Size(447, 335));

        sExpectedMaxSizes.put(3f / 4, new Size(670, 893));
        sExpectedDefaultSizes.put(3f / 4, new Size(402, 536));
        sExpectedMinSizes.put(3f / 4, new Size(335, 447));

        sExpectedMaxSizes.put(9f / 16, new Size(563, 1001));
        sExpectedDefaultSizes.put(9f / 16, new Size(338, 601));
        sExpectedMinSizes.put(9f / 16, new Size(282, 501));
    }

    private void forEveryTestCaseCheck(Map<Float, Size> expectedSizes,
            Function<Float, Size> callback) {
        for (Map.Entry<Float, Size> expectedSizesEntry : expectedSizes.entrySet()) {
            float aspectRatio = expectedSizesEntry.getKey();
            Size expectedSize = expectedSizesEntry.getValue();

            Assert.assertEquals(expectedSize, callback.apply(aspectRatio));
        }
    }

    @Before
    public void setUp() {
        initExpectedSizes();

        when(mResources.getDimensionPixelSize(anyInt())).thenReturn(DEFAULT_MIN_EDGE_SIZE);
        when(mResources.getFloat(anyInt())).thenReturn(OPTIMIZED_ASPECT_RATIO);
        when(mResources.getString(anyInt())).thenReturn("0x0");
        when(mResources.getDisplayMetrics())
                .thenReturn(getContext().getResources().getDisplayMetrics());

        // set up the mock context for spec handler specifically
        when(mContext.getResources()).thenReturn(mResources);

        DisplayInfo displayInfo = new DisplayInfo();
        displayInfo.logicalWidth = DISPLAY_EDGE_SIZE;
        displayInfo.logicalHeight = DISPLAY_EDGE_SIZE;

        // use the parent context (not the mocked one) to obtain the display layout
        // this is done to avoid unnecessary mocking while allowing for custom display dimensions
        DisplayLayout displayLayout = new DisplayLayout(displayInfo, getContext().getResources(),
                false, false);
        mPipDisplayLayoutState = new PipDisplayLayoutState(mContext);
        mPipDisplayLayoutState.setDisplayLayout(displayLayout);

        setUpStaticSystemPropertiesSession();
        mSizeSpecSource = new PhoneSizeSpecSource(mContext, mPipDisplayLayoutState);

        // no overridden min edge size by default
        mSizeSpecSource.setOverrideMinSize(null);
    }

    @After
    public void cleanUp() {
        sStaticMockitoSession.finishMocking();
    }

    @Test
    public void testGetMaxSize() {
        forEveryTestCaseCheck(sExpectedMaxSizes,
                (aspectRatio) -> mSizeSpecSource.getMaxSize(aspectRatio));
    }

    @Test
    public void testGetDefaultSize() {
        forEveryTestCaseCheck(sExpectedDefaultSizes,
                (aspectRatio) -> mSizeSpecSource.getDefaultSize(aspectRatio));
    }

    @Test
    public void testGetMinSize() {
        forEveryTestCaseCheck(sExpectedMinSizes,
                (aspectRatio) -> mSizeSpecSource.getMinSize(aspectRatio));
    }

    @Test
    public void testGetSizeForAspectRatio_noOverrideMinSize() {
        // an initial size with 16:9 aspect ratio
        Size initSize = new Size(600, 337);

        Size expectedSize = new Size(338, 601);
        Size actualSize = mSizeSpecSource.getSizeForAspectRatio(initSize, 9f / 16);

        Assert.assertEquals(expectedSize, actualSize);
    }

    @Test
    public void testGetSizeForAspectRatio_withOverrideMinSize() {
        // an initial size with a 1:1 aspect ratio
        Size initSize = new Size(OVERRIDE_MIN_EDGE_SIZE, OVERRIDE_MIN_EDGE_SIZE);
        mSizeSpecSource.setOverrideMinSize(initSize);

        Size expectedSize = new Size(40, 71);
        Size actualSize = mSizeSpecSource.getSizeForAspectRatio(initSize, 9f / 16);

        Assert.assertEquals(expectedSize, actualSize);
    }
}
