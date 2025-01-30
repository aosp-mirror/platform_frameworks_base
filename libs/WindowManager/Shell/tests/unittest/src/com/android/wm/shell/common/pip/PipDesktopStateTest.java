/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.wm.shell.common.pip;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;

import static com.android.window.flags.Flags.FLAG_ENABLE_CONNECTED_DISPLAYS_PIP;
import static com.android.window.flags.Flags.FLAG_ENABLE_DESKTOP_WINDOWING_PIP;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.platform.test.annotations.EnableFlags;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.window.DisplayAreaInfo;
import android.window.WindowContainerToken;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.desktopmode.DesktopRepository;
import com.android.wm.shell.desktopmode.DesktopUserRepositories;
import com.android.wm.shell.desktopmode.desktopwallpaperactivity.DesktopWallpaperActivityTokenProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

/**
 * Unit test against {@link PipDesktopState}.
 */
@SmallTest
@TestableLooper.RunWithLooper
@RunWith(AndroidTestingRunner.class)
@EnableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_PIP)
public class PipDesktopStateTest {
    @Mock private PipDisplayLayoutState mMockPipDisplayLayoutState;
    @Mock private Optional<DesktopUserRepositories> mMockDesktopUserRepositoriesOptional;
    @Mock private Optional<DesktopWallpaperActivityTokenProvider>
            mMockDesktopWallpaperActivityTokenProviderOptional;
    @Mock private DesktopUserRepositories mMockDesktopUserRepositories;
    @Mock private DesktopWallpaperActivityTokenProvider mMockDesktopWallpaperActivityTokenProvider;
    @Mock private DesktopRepository mMockDesktopRepository;
    @Mock private RootTaskDisplayAreaOrganizer mMockRootTaskDisplayAreaOrganizer;
    @Mock private ActivityManager.RunningTaskInfo mMockTaskInfo;

    private static final int DISPLAY_ID = 1;
    private DisplayAreaInfo mDefaultTda;
    private PipDesktopState mPipDesktopState;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mMockDesktopUserRepositoriesOptional.get()).thenReturn(mMockDesktopUserRepositories);
        when(mMockDesktopWallpaperActivityTokenProviderOptional.get()).thenReturn(
                mMockDesktopWallpaperActivityTokenProvider);
        when(mMockDesktopUserRepositories.getCurrent()).thenReturn(mMockDesktopRepository);
        when(mMockDesktopUserRepositoriesOptional.isPresent()).thenReturn(true);
        when(mMockDesktopWallpaperActivityTokenProviderOptional.isPresent()).thenReturn(true);

        when(mMockTaskInfo.getDisplayId()).thenReturn(DISPLAY_ID);
        when(mMockPipDisplayLayoutState.getDisplayId()).thenReturn(DISPLAY_ID);

        mDefaultTda = new DisplayAreaInfo(Mockito.mock(WindowContainerToken.class), DISPLAY_ID, 0);
        when(mMockRootTaskDisplayAreaOrganizer.getDisplayAreaInfo(DISPLAY_ID)).thenReturn(
                mDefaultTda);

        mPipDesktopState = new PipDesktopState(mMockPipDisplayLayoutState,
                mMockDesktopUserRepositoriesOptional,
                mMockDesktopWallpaperActivityTokenProviderOptional,
                mMockRootTaskDisplayAreaOrganizer);
    }

    @Test
    public void isDesktopWindowingPipEnabled_returnsTrue() {
        assertTrue(mPipDesktopState.isDesktopWindowingPipEnabled());
    }

    @Test
    public void isDesktopWindowingPipEnabled_desktopRepositoryEmpty_returnsFalse() {
        when(mMockDesktopUserRepositoriesOptional.isPresent()).thenReturn(false);

        assertFalse(mPipDesktopState.isDesktopWindowingPipEnabled());
    }

    @Test
    public void isDesktopWindowingPipEnabled_desktopWallpaperEmpty_returnsFalse() {
        when(mMockDesktopWallpaperActivityTokenProviderOptional.isPresent()).thenReturn(false);

        assertFalse(mPipDesktopState.isDesktopWindowingPipEnabled());
    }

    @Test
    @EnableFlags(FLAG_ENABLE_CONNECTED_DISPLAYS_PIP)
    public void isConnectedDisplaysPipEnabled_returnsTrue() {
        assertTrue(mPipDesktopState.isConnectedDisplaysPipEnabled());
    }

    @Test
    public void isPipEnteringInDesktopMode_visibleCountZero_minimizedPipPresent_returnsTrue() {
        when(mMockDesktopRepository.getVisibleTaskCount(DISPLAY_ID)).thenReturn(0);
        when(mMockDesktopRepository.isMinimizedPipPresentInDisplay(DISPLAY_ID)).thenReturn(true);

        assertTrue(mPipDesktopState.isPipEnteringInDesktopMode(mMockTaskInfo));
    }

    @Test
    public void isPipEnteringInDesktopMode_visibleCountNonzero_minimizedPipAbsent_returnsTrue() {
        when(mMockDesktopRepository.getVisibleTaskCount(DISPLAY_ID)).thenReturn(1);
        when(mMockDesktopRepository.isMinimizedPipPresentInDisplay(DISPLAY_ID)).thenReturn(false);

        assertTrue(mPipDesktopState.isPipEnteringInDesktopMode(mMockTaskInfo));
    }

    @Test
    public void isPipEnteringInDesktopMode_visibleCountZero_minimizedPipAbsent_returnsFalse() {
        when(mMockDesktopRepository.getVisibleTaskCount(DISPLAY_ID)).thenReturn(0);
        when(mMockDesktopRepository.isMinimizedPipPresentInDisplay(DISPLAY_ID)).thenReturn(false);

        assertFalse(mPipDesktopState.isPipEnteringInDesktopMode(mMockTaskInfo));
    }

    @Test
    public void shouldExitPipExitDesktopMode_visibleCountZero_wallpaperInvisible_returnsFalse() {
        when(mMockDesktopRepository.getVisibleTaskCount(DISPLAY_ID)).thenReturn(0);
        when(mMockDesktopWallpaperActivityTokenProvider.isWallpaperActivityVisible(
                DISPLAY_ID)).thenReturn(false);

        assertFalse(mPipDesktopState.shouldExitPipExitDesktopMode());
    }

    @Test
    public void shouldExitPipExitDesktopMode_visibleCountNonzero_wallpaperVisible_returnsFalse() {
        when(mMockDesktopRepository.getVisibleTaskCount(DISPLAY_ID)).thenReturn(1);
        when(mMockDesktopWallpaperActivityTokenProvider.isWallpaperActivityVisible(
                DISPLAY_ID)).thenReturn(true);

        assertFalse(mPipDesktopState.shouldExitPipExitDesktopMode());
    }

    @Test
    public void shouldExitPipExitDesktopMode_visibleCountZero_wallpaperVisible_returnsTrue() {
        when(mMockDesktopRepository.getVisibleTaskCount(DISPLAY_ID)).thenReturn(0);
        when(mMockDesktopWallpaperActivityTokenProvider.isWallpaperActivityVisible(
                DISPLAY_ID)).thenReturn(true);

        assertTrue(mPipDesktopState.shouldExitPipExitDesktopMode());
    }

    @Test
    public void getOutPipWindowingMode_exitToDesktop_displayFreeform_returnsUndefined() {
        // Set visible task count to 1 so isPipExitingToDesktopMode returns true
        when(mMockDesktopRepository.getVisibleTaskCount(DISPLAY_ID)).thenReturn(1);
        setDisplayWindowingMode(WINDOWING_MODE_FREEFORM);

        assertEquals(WINDOWING_MODE_UNDEFINED, mPipDesktopState.getOutPipWindowingMode());
    }

    @Test
    public void getOutPipWindowingMode_exitToDesktop_displayFullscreen_returnsFreeform() {
        // Set visible task count to 1 so isPipExitingToDesktopMode returns true
        when(mMockDesktopRepository.getVisibleTaskCount(DISPLAY_ID)).thenReturn(1);
        setDisplayWindowingMode(WINDOWING_MODE_FULLSCREEN);

        assertEquals(WINDOWING_MODE_FREEFORM, mPipDesktopState.getOutPipWindowingMode());
    }

    @Test
    public void getOutPipWindowingMode_exitToFullscreen_displayFullscreen_returnsUndefined() {
        setDisplayWindowingMode(WINDOWING_MODE_FULLSCREEN);

        assertEquals(WINDOWING_MODE_UNDEFINED, mPipDesktopState.getOutPipWindowingMode());
    }

    private void setDisplayWindowingMode(int windowingMode) {
        mDefaultTda.configuration.windowConfiguration.setWindowingMode(windowingMode);
    }
}
