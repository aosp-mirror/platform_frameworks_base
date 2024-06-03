/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.display;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.PropertyInvalidatedCache;
import android.content.Context;
import android.graphics.Point;
import android.os.IBinder;
import android.util.SparseArray;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.Surface;
import android.view.SurfaceControl;

import androidx.test.filters.SmallTest;

import com.android.server.display.layout.Layout;
import com.android.server.display.mode.SyntheticModeManager;

import org.junit.Before;
import org.junit.Test;
import org.mockito.AdditionalAnswers;

import java.io.InputStream;
import java.io.OutputStream;

@SmallTest
public class LogicalDisplayTest {
    private static final int DISPLAY_ID = 0;
    private static final int LAYER_STACK = 0;
    private static final int DISPLAY_WIDTH = 100;
    private static final int DISPLAY_HEIGHT = 200;
    private static final int MODE_ID = 1;
    private static final int OTHER_MODE_ID = 2;

    private LogicalDisplay mLogicalDisplay;
    private DisplayDevice mDisplayDevice;
    private DisplayAdapter mDisplayAdapter;
    private Context mContext;
    private IBinder mDisplayToken;
    private DisplayDeviceRepository mDeviceRepo;
    private SyntheticModeManager mSyntheticModeManager;
    private final DisplayDeviceInfo mDisplayDeviceInfo = new DisplayDeviceInfo();

    @Before
    public void setUp() {
        // Share classloader to allow package private access.
        System.setProperty("dexmaker.share_classloader", "true");
        mDisplayDevice = mock(DisplayDevice.class);
        mDisplayAdapter = mock(DisplayAdapter.class);
        mContext = mock(Context.class);
        mDisplayToken = mock(IBinder.class);
        mSyntheticModeManager = mock(SyntheticModeManager.class);
        mLogicalDisplay = new LogicalDisplay(DISPLAY_ID, LAYER_STACK, mDisplayDevice);

        mDisplayDeviceInfo.copyFrom(new DisplayDeviceInfo());
        mDisplayDeviceInfo.width = DISPLAY_WIDTH;
        mDisplayDeviceInfo.height = DISPLAY_HEIGHT;
        mDisplayDeviceInfo.touch = DisplayDeviceInfo.TOUCH_INTERNAL;
        mDisplayDeviceInfo.modeId = MODE_ID;
        mDisplayDeviceInfo.supportedModes = new Display.Mode[] {new Display.Mode(MODE_ID,
                DISPLAY_WIDTH, DISPLAY_HEIGHT, /* refreshRate= */ 60)};
        when(mDisplayDevice.getDisplayDeviceInfoLocked()).thenReturn(mDisplayDeviceInfo);
        when(mSyntheticModeManager.createAppSupportedModes(any(), any())).thenAnswer(
                AdditionalAnswers.returnsSecondArg());

        // Disable binder caches in this process.
        PropertyInvalidatedCache.disableForTestMode();

        mDeviceRepo = new DisplayDeviceRepository(
                new DisplayManagerService.SyncRoot(),
                new PersistentDataStore(new PersistentDataStore.Injector() {
                    @Override
                    public InputStream openRead() {
                        return null;
                    }

                    @Override
                    public OutputStream startWrite() {
                        return null;
                    }

                    @Override
                    public void finishWrite(OutputStream os, boolean success) {}
                }));
        mDeviceRepo.onDisplayDeviceEvent(mDisplayDevice, DisplayAdapter.DISPLAY_DEVICE_EVENT_ADDED);
        mLogicalDisplay.updateLocked(mDeviceRepo, mSyntheticModeManager);
    }

    @Test
    public void testLetterbox() {
        mLogicalDisplay = new LogicalDisplay(DISPLAY_ID, LAYER_STACK, mDisplayDevice);
        mDisplayDeviceInfo.xDpi = 0.5f;
        mDisplayDeviceInfo.yDpi = 1.0f;

        mLogicalDisplay.updateLocked(mDeviceRepo, mSyntheticModeManager);
        var originalDisplayInfo = mLogicalDisplay.getDisplayInfoLocked();
        assertEquals(DISPLAY_WIDTH, originalDisplayInfo.logicalWidth);
        assertEquals(DISPLAY_HEIGHT, originalDisplayInfo.logicalHeight);

        SurfaceControl.Transaction t = mock(SurfaceControl.Transaction.class);
        mLogicalDisplay.configureDisplayLocked(t, mDisplayDevice, false);
        assertEquals(new Point(0, 0), mLogicalDisplay.getDisplayPosition());

        /*
         * Content is too wide, should become letterboxed
         *  ______DISPLAY_WIDTH________
         * |                        |
         * |________________________|
         * |                        |
         * |       CONTENT          |
         * |                        |
         * |________________________|
         * |                        |
         * |________________________|
         */
        // Make a wide application content, by reducing its height.
        DisplayInfo displayInfo = new DisplayInfo();
        displayInfo.logicalWidth = DISPLAY_WIDTH;
        displayInfo.logicalHeight = DISPLAY_HEIGHT / 2;
        mLogicalDisplay.setDisplayInfoOverrideFromWindowManagerLocked(displayInfo);

        mLogicalDisplay.configureDisplayLocked(t, mDisplayDevice, false);
        assertEquals(new Point(0, DISPLAY_HEIGHT / 4), mLogicalDisplay.getDisplayPosition());
    }


    @Test
    public void testNoLetterbox_noAnisotropyCorrectionForInternalDisplay() {
        mDisplayDeviceInfo.type = Display.TYPE_INTERNAL;
        mLogicalDisplay = new LogicalDisplay(DISPLAY_ID, LAYER_STACK, mDisplayDevice,
                /*isAnisotropyCorrectionEnabled=*/ true,
                /*isAlwaysRotateDisplayDeviceEnabled=*/ true);

        // In case of Anisotropy of pixels, then the content should be rescaled so it would adjust
        // to using the whole screen. This is because display will rescale it back to fill the
        // screen (in case the display menu setting is set to stretch the pixels across the display)
        mDisplayDeviceInfo.xDpi = 0.5f;
        mDisplayDeviceInfo.yDpi = 1.0f;

        mLogicalDisplay.updateLocked(mDeviceRepo, mSyntheticModeManager);
        var originalDisplayInfo = mLogicalDisplay.getDisplayInfoLocked();
        // Content width not scaled
        assertEquals(DISPLAY_WIDTH, originalDisplayInfo.logicalWidth);
        assertEquals(DISPLAY_HEIGHT, originalDisplayInfo.logicalHeight);

        SurfaceControl.Transaction t = mock(SurfaceControl.Transaction.class);
        mLogicalDisplay.configureDisplayLocked(t, mDisplayDevice, false);

        // Applications need to think that they are shown on a display with square pixels.
        // as applications can be displayed on multiple displays simultaneously (mirrored).
        // Content is too wide, should have become letterboxed - but it won't because of anisotropy
        // correction
        assertEquals(new Point(0, 0), mLogicalDisplay.getDisplayPosition());
    }

    @Test
    public void testNoLetterbox_anisotropyCorrection() {
        mDisplayDeviceInfo.type = Display.TYPE_EXTERNAL;
        mLogicalDisplay = new LogicalDisplay(DISPLAY_ID, LAYER_STACK, mDisplayDevice,
                /*isAnisotropyCorrectionEnabled=*/ true,
                /*isAlwaysRotateDisplayDeviceEnabled=*/ true);

        // In case of Anisotropy of pixels, then the content should be rescaled so it would adjust
        // to using the whole screen. This is because display will rescale it back to fill the
        // screen (in case the display menu setting is set to stretch the pixels across the display)
        mDisplayDeviceInfo.xDpi = 0.5f;
        mDisplayDeviceInfo.yDpi = 1.0f;

        mLogicalDisplay.updateLocked(mDeviceRepo, mSyntheticModeManager);
        var originalDisplayInfo = mLogicalDisplay.getDisplayInfoLocked();
        // Content width re-scaled
        assertEquals(DISPLAY_WIDTH * 2, originalDisplayInfo.logicalWidth);
        assertEquals(DISPLAY_HEIGHT, originalDisplayInfo.logicalHeight);

        SurfaceControl.Transaction t = mock(SurfaceControl.Transaction.class);
        mLogicalDisplay.configureDisplayLocked(t, mDisplayDevice, false);

        // Applications need to think that they are shown on a display with square pixels.
        // as applications can be displayed on multiple displays simultaneously (mirrored).
        // Content is too wide, should have become letterboxed - but it won't because of anisotropy
        // correction
        assertEquals(new Point(0, 0), mLogicalDisplay.getDisplayPosition());
    }

    @Test
    public void testLetterbox_anisotropyCorrectionYDpi() {
        mDisplayDeviceInfo.type = Display.TYPE_EXTERNAL;
        mLogicalDisplay = new LogicalDisplay(DISPLAY_ID, LAYER_STACK, mDisplayDevice,
                /*isAnisotropyCorrectionEnabled=*/ true,
                /*isAlwaysRotateDisplayDeviceEnabled=*/ true);

        DisplayInfo displayInfo = new DisplayInfo();
        displayInfo.logicalWidth = DISPLAY_WIDTH;
        displayInfo.logicalHeight = DISPLAY_HEIGHT / 2;
        mDisplayDeviceInfo.xDpi = 1.0f;
        mDisplayDeviceInfo.yDpi = 0.5f;
        mLogicalDisplay.setDisplayInfoOverrideFromWindowManagerLocked(displayInfo);
        mLogicalDisplay.updateLocked(mDeviceRepo, mSyntheticModeManager);

        SurfaceControl.Transaction t = mock(SurfaceControl.Transaction.class);
        mLogicalDisplay.configureDisplayLocked(t, mDisplayDevice, false);

        assertEquals(new Point(0, 75), mLogicalDisplay.getDisplayPosition());
    }

    @Test
    public void testPillarbox() {
        mLogicalDisplay = new LogicalDisplay(DISPLAY_ID, LAYER_STACK, mDisplayDevice);
        mDisplayDeviceInfo.xDpi = 0.5f;
        mDisplayDeviceInfo.yDpi = 1.0f;

        DisplayInfo displayInfo = new DisplayInfo();
        displayInfo.rotation = Surface.ROTATION_90;
        displayInfo.logicalWidth = DISPLAY_WIDTH;
        displayInfo.logicalHeight = DISPLAY_HEIGHT;
        mDisplayDeviceInfo.flags = DisplayDeviceInfo.FLAG_ROTATES_WITH_CONTENT;
        mLogicalDisplay.setDisplayInfoOverrideFromWindowManagerLocked(displayInfo);
        mLogicalDisplay.updateLocked(mDeviceRepo, mSyntheticModeManager);

        var updatedDisplayInfo = mLogicalDisplay.getDisplayInfoLocked();
        assertEquals(Surface.ROTATION_90, updatedDisplayInfo.rotation);
        assertEquals(DISPLAY_WIDTH, updatedDisplayInfo.logicalWidth);
        assertEquals(DISPLAY_HEIGHT, updatedDisplayInfo.logicalHeight);

        /*
         * Content is too tall, should become pillarboxed
         *  ______DISPLAY_WIDTH________
         * |    |                |    |
         * |    |                |    |
         * |    |                |    |
         * |    |   CONTENT      |    |
         * |    |                |    |
         * |    |                |    |
         * |____|________________|____|
         */

        SurfaceControl.Transaction t = mock(SurfaceControl.Transaction.class);
        mLogicalDisplay.configureDisplayLocked(t, mDisplayDevice, false);

        assertEquals(new Point(75, 0), mLogicalDisplay.getDisplayPosition());
    }

    @Test
    public void testPillarbox_anisotropyCorrection() {
        mDisplayDeviceInfo.type = Display.TYPE_EXTERNAL;
        mLogicalDisplay = new LogicalDisplay(DISPLAY_ID, LAYER_STACK, mDisplayDevice,
                /*isAnisotropyCorrectionEnabled=*/ true,
                /*isAlwaysRotateDisplayDeviceEnabled=*/ true);

        DisplayInfo displayInfo = new DisplayInfo();
        displayInfo.logicalWidth = DISPLAY_WIDTH;
        displayInfo.logicalHeight = DISPLAY_HEIGHT;
        displayInfo.rotation = Surface.ROTATION_90;
        mDisplayDeviceInfo.flags = DisplayDeviceInfo.FLAG_ROTATES_WITH_CONTENT;
        // In case of Anisotropy of pixels, then the content should be rescaled so it would adjust
        // to using the whole screen. This is because display will rescale it back to fill the
        // screen (in case the display menu setting is set to stretch the pixels across the display)
        mDisplayDeviceInfo.xDpi = 0.5f;
        mDisplayDeviceInfo.yDpi = 1.0f;
        mLogicalDisplay.setDisplayInfoOverrideFromWindowManagerLocked(displayInfo);
        mLogicalDisplay.updateLocked(mDeviceRepo, mSyntheticModeManager);

        SurfaceControl.Transaction t = mock(SurfaceControl.Transaction.class);
        mLogicalDisplay.configureDisplayLocked(t, mDisplayDevice, false);

        // Applications need to think that they are shown on a display with square pixels.
        // as applications can be displayed on multiple displays simultaneously (mirrored).
        // Content is a bit wider than in #testPillarbox, due to content added stretching
        assertEquals(new Point(50, 0), mLogicalDisplay.getDisplayPosition());
    }

    @Test
    public void testNoPillarbox_anisotropyCorrectionYDpi() {
        mDisplayDeviceInfo.type = Display.TYPE_EXTERNAL;
        mLogicalDisplay = new LogicalDisplay(DISPLAY_ID, LAYER_STACK, mDisplayDevice,
                /*isAnisotropyCorrectionEnabled=*/ true,
                /*isAlwaysRotateDisplayDeviceEnabled=*/ true);

        // In case of Anisotropy of pixels, then the content should be rescaled so it would adjust
        // to using the whole screen. This is because display will rescale it back to fill the
        // screen (in case the display menu setting is set to stretch the pixels across the display)
        mDisplayDeviceInfo.xDpi = 1.0f;
        mDisplayDeviceInfo.yDpi = 0.5f;

        mLogicalDisplay.updateLocked(mDeviceRepo, mSyntheticModeManager);
        var originalDisplayInfo = mLogicalDisplay.getDisplayInfoLocked();
        // Content width re-scaled
        assertEquals(DISPLAY_WIDTH, originalDisplayInfo.logicalWidth);
        assertEquals(DISPLAY_HEIGHT * 2, originalDisplayInfo.logicalHeight);

        SurfaceControl.Transaction t = mock(SurfaceControl.Transaction.class);
        mLogicalDisplay.configureDisplayLocked(t, mDisplayDevice, false);

        // Applications need to think that they are shown on a display with square pixels.
        // as applications can be displayed on multiple displays simultaneously (mirrored).
        // Content is too tall, should have occupy the whole screen - but it won't because of
        // anisotropy correction
        assertEquals(new Point(0, 0), mLogicalDisplay.getDisplayPosition());
    }

    @Test
    public void testGetDisplayPosition() {
        Point expectedPosition = new Point(0, 0);

        SurfaceControl.Transaction t = mock(SurfaceControl.Transaction.class);
        mLogicalDisplay.configureDisplayLocked(t, mDisplayDevice, false);
        assertEquals(expectedPosition, mLogicalDisplay.getDisplayPosition());

        expectedPosition.set(20, 40);
        mLogicalDisplay.setDisplayOffsetsLocked(20, 40);
        mLogicalDisplay.configureDisplayLocked(t, mDisplayDevice, false);
        assertEquals(expectedPosition, mLogicalDisplay.getDisplayPosition());

        DisplayInfo displayInfo = new DisplayInfo();
        displayInfo.logicalWidth = DISPLAY_WIDTH;
        displayInfo.logicalHeight = DISPLAY_HEIGHT;
        // Rotation doesn't matter when the FLAG_ROTATES_WITH_CONTENT is absent.
        displayInfo.rotation = Surface.ROTATION_90;
        mLogicalDisplay.setDisplayInfoOverrideFromWindowManagerLocked(displayInfo);
        mLogicalDisplay.configureDisplayLocked(t, mDisplayDevice, false);
        assertEquals(expectedPosition, mLogicalDisplay.getDisplayPosition());

        expectedPosition.set(40, -20);
        mDisplayDeviceInfo.flags = DisplayDeviceInfo.FLAG_ROTATES_WITH_CONTENT;
        mLogicalDisplay.updateLocked(mDeviceRepo, mSyntheticModeManager);
        displayInfo.logicalWidth = DISPLAY_HEIGHT;
        displayInfo.logicalHeight = DISPLAY_WIDTH;
        displayInfo.rotation = Surface.ROTATION_90;
        mLogicalDisplay.setDisplayInfoOverrideFromWindowManagerLocked(displayInfo);
        mLogicalDisplay.configureDisplayLocked(t, mDisplayDevice, false);
        assertEquals(expectedPosition, mLogicalDisplay.getDisplayPosition());
    }

    @Test
    public void testGetDisplayPositionAlwaysRotateDisplayEnabled() {
        mDisplayDeviceInfo.type = Display.TYPE_EXTERNAL;
        mLogicalDisplay = new LogicalDisplay(DISPLAY_ID, LAYER_STACK, mDisplayDevice,
                /*isAnisotropyCorrectionEnabled=*/ true,
                /*isAlwaysRotateDisplayDeviceEnabled=*/ true);
        mLogicalDisplay.updateLocked(mDeviceRepo, mSyntheticModeManager);
        Point expectedPosition = new Point();

        SurfaceControl.Transaction t = mock(SurfaceControl.Transaction.class);
        mLogicalDisplay.configureDisplayLocked(t, mDisplayDevice, false);
        assertEquals(expectedPosition, mLogicalDisplay.getDisplayPosition());

        expectedPosition.set(20, 40);
        mLogicalDisplay.setDisplayOffsetsLocked(20, 40);
        mLogicalDisplay.configureDisplayLocked(t, mDisplayDevice, false);
        assertEquals(expectedPosition, mLogicalDisplay.getDisplayPosition());

        DisplayInfo displayInfo = new DisplayInfo();
        displayInfo.logicalWidth = DISPLAY_WIDTH;
        displayInfo.logicalHeight = DISPLAY_HEIGHT;
        // Rotation sent from WindowManager is always taken into account by LogicalDisplay
        // not matter whether FLAG_ROTATES_WITH_CONTENT is set or not.
        // This is because WindowManager takes care of rotation and expects that LogicalDisplay
        // will follow the rotation supplied by WindowManager
        expectedPosition.set(115, -20);
        displayInfo.rotation = Surface.ROTATION_90;
        mLogicalDisplay.setDisplayInfoOverrideFromWindowManagerLocked(displayInfo);
        mLogicalDisplay.configureDisplayLocked(t, mDisplayDevice, false);
        assertEquals(expectedPosition, mLogicalDisplay.getDisplayPosition());

        expectedPosition.set(40, -20);
        mDisplayDeviceInfo.flags = DisplayDeviceInfo.FLAG_ROTATES_WITH_CONTENT;
        mLogicalDisplay.updateLocked(mDeviceRepo, mSyntheticModeManager);
        displayInfo.logicalWidth = DISPLAY_HEIGHT;
        displayInfo.logicalHeight = DISPLAY_WIDTH;
        displayInfo.rotation = Surface.ROTATION_90;
        mLogicalDisplay.setDisplayInfoOverrideFromWindowManagerLocked(displayInfo);
        mLogicalDisplay.configureDisplayLocked(t, mDisplayDevice, false);
        assertEquals(expectedPosition, mLogicalDisplay.getDisplayPosition());
    }

    @Test
    public void testDisplayInputFlags() {
        DisplayDevice displayDevice = new DisplayDevice(mDisplayAdapter, mDisplayToken,
                "unique_display_id", mContext) {
            @Override
            public boolean hasStableUniqueId() {
                return false;
            }

            @Override
            public DisplayDeviceInfo getDisplayDeviceInfoLocked() {
                return mDisplayDeviceInfo;
            }
        };
        SurfaceControl.Transaction t = mock(SurfaceControl.Transaction.class);
        mLogicalDisplay.configureDisplayLocked(t, displayDevice, false);
        verify(t).setDisplayFlags(any(), eq(SurfaceControl.DISPLAY_RECEIVES_INPUT));
        reset(t);

        mDisplayDeviceInfo.touch = DisplayDeviceInfo.TOUCH_NONE;
        mLogicalDisplay.configureDisplayLocked(t, displayDevice, false);
        verify(t).setDisplayFlags(any(), eq(0));
        reset(t);

        mDisplayDeviceInfo.touch = DisplayDeviceInfo.TOUCH_VIRTUAL;
        mLogicalDisplay.configureDisplayLocked(t, displayDevice, false);
        verify(t).setDisplayFlags(any(), eq(SurfaceControl.DISPLAY_RECEIVES_INPUT));
        reset(t);

        mLogicalDisplay.setEnabledLocked(false);
        mLogicalDisplay.configureDisplayLocked(t, displayDevice, false);
        verify(t).setDisplayFlags(any(), eq(0));
        reset(t);

        mLogicalDisplay.setEnabledLocked(true);
        mDisplayDeviceInfo.touch = DisplayDeviceInfo.TOUCH_EXTERNAL;
        mLogicalDisplay.configureDisplayLocked(t, displayDevice, false);
        verify(t).setDisplayFlags(any(), eq(SurfaceControl.DISPLAY_RECEIVES_INPUT));
        reset(t);
    }

    @Test
    public void testRearDisplaysArePresentationDisplaysThatDestroyContentOnRemoval() {
        // Assert that the display isn't a presentation display by default, with a default remove
        // mode
        assertEquals(0, mLogicalDisplay.getDisplayInfoLocked().flags);
        assertEquals(Display.REMOVE_MODE_MOVE_CONTENT_TO_PRIMARY,
                mLogicalDisplay.getDisplayInfoLocked().removeMode);

        // Update position and test to see that it's been updated to a rear, presentation display
        // that destroys content on removal
        mLogicalDisplay.setDevicePositionLocked(Layout.Display.POSITION_REAR);
        mLogicalDisplay.updateLocked(mDeviceRepo, mSyntheticModeManager);
        assertEquals(Display.FLAG_REAR | Display.FLAG_PRESENTATION,
                mLogicalDisplay.getDisplayInfoLocked().flags);
        assertEquals(Display.REMOVE_MODE_DESTROY_CONTENT,
                mLogicalDisplay.getDisplayInfoLocked().removeMode);

        // And then check the unsetting the position resets both
        mLogicalDisplay.setDevicePositionLocked(Layout.Display.POSITION_UNKNOWN);
        mLogicalDisplay.updateLocked(mDeviceRepo, mSyntheticModeManager);
        assertEquals(0, mLogicalDisplay.getDisplayInfoLocked().flags);
        assertEquals(Display.REMOVE_MODE_MOVE_CONTENT_TO_PRIMARY,
                mLogicalDisplay.getDisplayInfoLocked().removeMode);
    }

    @Test
    public void testUpdateLayoutLimitedRefreshRate() {
        SurfaceControl.RefreshRateRange layoutLimitedRefreshRate =
                new SurfaceControl.RefreshRateRange(0, 120);
        DisplayInfo info1 = mLogicalDisplay.getDisplayInfoLocked();
        mLogicalDisplay.updateLayoutLimitedRefreshRateLocked(layoutLimitedRefreshRate);
        DisplayInfo info2 = mLogicalDisplay.getDisplayInfoLocked();
        // Display info should only be updated when updateLocked is called
        assertEquals(info2, info1);

        mLogicalDisplay.updateLocked(mDeviceRepo, mSyntheticModeManager);
        DisplayInfo info3 = mLogicalDisplay.getDisplayInfoLocked();
        assertNotEquals(info3, info2);
        assertEquals(layoutLimitedRefreshRate, info3.layoutLimitedRefreshRate);
    }

    @Test
    public void testUpdateLayoutLimitedRefreshRate_setsDirtyFlag() {
        SurfaceControl.RefreshRateRange layoutLimitedRefreshRate =
                new SurfaceControl.RefreshRateRange(0, 120);
        assertFalse(mLogicalDisplay.isDirtyLocked());

        mLogicalDisplay.updateLayoutLimitedRefreshRateLocked(layoutLimitedRefreshRate);
        assertTrue(mLogicalDisplay.isDirtyLocked());

        mLogicalDisplay.updateLocked(mDeviceRepo, mSyntheticModeManager);
        assertFalse(mLogicalDisplay.isDirtyLocked());
    }

    @Test
    public void testUpdateRefreshRateThermalThrottling() {
        SparseArray<SurfaceControl.RefreshRateRange> refreshRanges = new SparseArray<>();
        refreshRanges.put(0, new SurfaceControl.RefreshRateRange(0, 120));
        DisplayInfo info1 = mLogicalDisplay.getDisplayInfoLocked();
        mLogicalDisplay.updateThermalRefreshRateThrottling(refreshRanges);
        DisplayInfo info2 = mLogicalDisplay.getDisplayInfoLocked();
        // Display info should only be updated when updateLocked is called
        assertEquals(info2, info1);

        mLogicalDisplay.updateLocked(mDeviceRepo, mSyntheticModeManager);
        DisplayInfo info3 = mLogicalDisplay.getDisplayInfoLocked();
        assertNotEquals(info3, info2);
        assertTrue(refreshRanges.contentEquals(info3.thermalRefreshRateThrottling));
    }

    @Test
    public void testUpdateRefreshRateThermalThrottling_setsDirtyFlag() {
        SparseArray<SurfaceControl.RefreshRateRange> refreshRanges = new SparseArray<>();
        refreshRanges.put(0, new SurfaceControl.RefreshRateRange(0, 120));
        assertFalse(mLogicalDisplay.isDirtyLocked());

        mLogicalDisplay.updateThermalRefreshRateThrottling(refreshRanges);
        assertTrue(mLogicalDisplay.isDirtyLocked());

        mLogicalDisplay.updateLocked(mDeviceRepo, mSyntheticModeManager);
        assertFalse(mLogicalDisplay.isDirtyLocked());
    }

    @Test
    public void testUpdateDisplayGroupIdLocked() {
        int newId = 999;
        DisplayInfo info1 = mLogicalDisplay.getDisplayInfoLocked();
        mLogicalDisplay.updateDisplayGroupIdLocked(newId);
        DisplayInfo info2 = mLogicalDisplay.getDisplayInfoLocked();
        // Display info should only be updated when updateLocked is called
        assertEquals(info2, info1);

        mLogicalDisplay.updateLocked(mDeviceRepo, mSyntheticModeManager);
        DisplayInfo info3 = mLogicalDisplay.getDisplayInfoLocked();
        assertNotEquals(info3, info2);
        assertEquals(newId, info3.displayGroupId);
    }

    @Test
    public void testUpdateDisplayGroupIdLocked_setsDirtyFlag() {
        assertFalse(mLogicalDisplay.isDirtyLocked());

        mLogicalDisplay.updateDisplayGroupIdLocked(99);
        assertTrue(mLogicalDisplay.isDirtyLocked());

        mLogicalDisplay.updateLocked(mDeviceRepo, mSyntheticModeManager);
        assertFalse(mLogicalDisplay.isDirtyLocked());
    }

    @Test
    public void testSetThermalBrightnessThrottlingDataId() {
        String brightnessThrottlingDataId = "throttling_data_id";
        DisplayInfo info1 = mLogicalDisplay.getDisplayInfoLocked();
        mLogicalDisplay.setThermalBrightnessThrottlingDataIdLocked(brightnessThrottlingDataId);
        DisplayInfo info2 = mLogicalDisplay.getDisplayInfoLocked();
        // Display info should only be updated when updateLocked is called
        assertEquals(info2, info1);

        mLogicalDisplay.updateLocked(mDeviceRepo, mSyntheticModeManager);
        DisplayInfo info3 = mLogicalDisplay.getDisplayInfoLocked();
        assertNotEquals(info3, info2);
        assertEquals(brightnessThrottlingDataId, info3.thermalBrightnessThrottlingDataId);
    }

    @Test
    public void testSetThermalBrightnessThrottlingDataId_setsDirtyFlag() {
        assertFalse(mLogicalDisplay.isDirtyLocked());

        mLogicalDisplay.setThermalBrightnessThrottlingDataIdLocked("99");
        assertTrue(mLogicalDisplay.isDirtyLocked());

        mLogicalDisplay.updateLocked(mDeviceRepo, mSyntheticModeManager);
        assertFalse(mLogicalDisplay.isDirtyLocked());
    }

    @Test
    public void testGetsAppSupportedModesFromSyntheticModeManager() {
        mLogicalDisplay = new LogicalDisplay(DISPLAY_ID, LAYER_STACK, mDisplayDevice);
        Display.Mode[] appSupportedModes = new Display.Mode[] {new Display.Mode(OTHER_MODE_ID,
                DISPLAY_WIDTH, DISPLAY_HEIGHT, /* refreshRate= */ 45)};
        when(mSyntheticModeManager.createAppSupportedModes(
                any(), eq(mDisplayDeviceInfo.supportedModes))).thenReturn(appSupportedModes);

        mLogicalDisplay.updateLocked(mDeviceRepo, mSyntheticModeManager);
        DisplayInfo info = mLogicalDisplay.getDisplayInfoLocked();
        assertArrayEquals(appSupportedModes, info.appsSupportedModes);
    }
}
