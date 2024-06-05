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

package com.android.server.wm;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;

/**
 * Tests for the {@link CompatScaleProvider} interface.
 * See {@link CompatModePackages} class for implementation.
 *
 * Build/Install/Run:
 * atest WmTests:CompatScaleProviderTest
 */
@SmallTest
@Presubmit
public class CompatScaleProviderTest extends SystemServiceTestsBase {
    private static final String TEST_PACKAGE = "compat.mode.packages";
    static final int TEST_USER_ID = 1;

    private ActivityTaskManagerService mAtm;

    /**
     * setup method before every test.
     */
    @Before
    public void setUp() {
        mAtm = mSystemServicesTestRule.getActivityTaskManagerService();
    }

    /**
     * Registering a {@link CompatScaleProvider} with an invalid id should throw an exception.
     */
    @Test
    public void registerCompatScaleProviderWithInvalidId() {
        CompatScaleProvider compatScaleProvider = mock(CompatScaleProvider.class);
        assertThrows(
                IllegalArgumentException.class,
                () ->  mAtm.registerCompatScaleProvider(-1, compatScaleProvider)
        );
    }

    /**
     * Registering a {@code null} {@link CompatScaleProvider} should throw an exception.
     */
    @Test
    public void registerCompatScaleProviderFailIfCallbackIsNull() {
        assertThrows(
                IllegalArgumentException.class,
                () ->  mAtm.registerCompatScaleProvider(
                            CompatScaleProvider.COMPAT_SCALE_MODE_PRODUCT, null)
        );
    }

    /**
     * Registering a {@link CompatScaleProvider} with a already registered id should throw an
     * exception.
     */
    @Test
    public void registerCompatScaleProviderFailIfIdIsAlreadyRegistered() {
        CompatScaleProvider compatScaleProvider = mock(CompatScaleProvider.class);
        mAtm.registerCompatScaleProvider(CompatScaleProvider.COMPAT_SCALE_MODE_PRODUCT,
                compatScaleProvider);
        assertThrows(
                IllegalArgumentException.class,
                () ->  mAtm.registerCompatScaleProvider(
                            CompatScaleProvider.COMPAT_SCALE_MODE_PRODUCT, compatScaleProvider)
        );
        mAtm.unregisterCompatScaleProvider(CompatScaleProvider.COMPAT_SCALE_MODE_PRODUCT);
    }

    /**
     * Successfully registering a {@link CompatScaleProvider} with should result in callbacks
     * getting called.
     */
    @Test
    public void registerCompatScaleProviderSuccessfully() {
        CompatScaleProvider compatScaleProvider = mock(CompatScaleProvider.class);
        mAtm.registerCompatScaleProvider(CompatScaleProvider.COMPAT_SCALE_MODE_PRODUCT,
                compatScaleProvider);
        mAtm.mCompatModePackages.getCompatScale(TEST_PACKAGE, TEST_USER_ID);
        verify(compatScaleProvider, times(1)).getCompatScale(TEST_PACKAGE, TEST_USER_ID);
        mAtm.unregisterCompatScaleProvider(CompatScaleProvider.COMPAT_SCALE_MODE_PRODUCT);
    }

    /**
     * Unregistering a {@link CompatScaleProvider} with a unregistered id should throw an exception.
     */
    @Test
    public void unregisterCompatScaleProviderFailIfIdNotRegistered() {
        assertThrows(
                IllegalArgumentException.class,
                () ->  mAtm.unregisterCompatScaleProvider(
                            CompatScaleProvider.COMPAT_SCALE_MODE_PRODUCT)
        );
    }

    /**
     * Unregistering a {@link CompatScaleProvider} with an invalid id should throw an exception.
     */
    @Test
    public void unregisterCompatScaleProviderFailIfIdNotInRange() {
        assertThrows(
                IllegalArgumentException.class,
                () ->  mAtm.unregisterCompatScaleProvider(-1)
        );
    }

    /**
     * Successfully unregistering a {@link CompatScaleProvider} should stop the callbacks from
     * getting called.
     */
    @Test
    public void unregisterCompatScaleProviderSuccessfully() {
        CompatScaleProvider compatScaleProvider = mock(CompatScaleProvider.class);
        mAtm.registerCompatScaleProvider(CompatScaleProvider.COMPAT_SCALE_MODE_PRODUCT,
                compatScaleProvider);
        mAtm.unregisterCompatScaleProvider(CompatScaleProvider.COMPAT_SCALE_MODE_PRODUCT);
        mAtm.mCompatModePackages.getCompatScale(TEST_PACKAGE, TEST_USER_ID);
        verify(compatScaleProvider, never()).getCompatScale(TEST_PACKAGE, TEST_USER_ID);
    }

    /**
     * Order of calling {@link CompatScaleProvider} is same as the id that was used for
     * registering it.
     */
    @Test
    public void registerCompatScaleProviderRespectsOrderId() {
        CompatScaleProvider gameModeCompatScaleProvider = mock(CompatScaleProvider.class);
        CompatScaleProvider productCompatScaleProvider = mock(CompatScaleProvider.class);
        mAtm.registerCompatScaleProvider(CompatScaleProvider.COMPAT_SCALE_MODE_GAME,
                gameModeCompatScaleProvider);
        mAtm.registerCompatScaleProvider(CompatScaleProvider.COMPAT_SCALE_MODE_PRODUCT,
                productCompatScaleProvider);
        mAtm.mCompatModePackages.getCompatScale(TEST_PACKAGE, TEST_USER_ID);
        InOrder inOrder = inOrder(gameModeCompatScaleProvider, productCompatScaleProvider);
        inOrder.verify(gameModeCompatScaleProvider).getCompatScale(TEST_PACKAGE, TEST_USER_ID);
        inOrder.verify(productCompatScaleProvider).getCompatScale(TEST_PACKAGE, TEST_USER_ID);
    }
}
