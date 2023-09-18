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

import org.junit.Before;
import org.junit.Test;

import java.io.InputStream;
import java.io.OutputStream;

@SmallTest
public class LogicalDisplayTest {
    private static final int DISPLAY_ID = 0;
    private static final int LAYER_STACK = 0;
    private static final int DISPLAY_WIDTH = 100;
    private static final int DISPLAY_HEIGHT = 200;
    private static final int MODE_ID = 1;

    private LogicalDisplay mLogicalDisplay;
    private DisplayDevice mDisplayDevice;
    private DisplayAdapter mDisplayAdapter;
    private Context mContext;
    private IBinder mDisplayToken;
    private DisplayDeviceRepository mDeviceRepo;
    private final DisplayDeviceInfo mDisplayDeviceInfo = new DisplayDeviceInfo();

    @Before
    public void setUp() {
        // Share classloader to allow package private access.
        System.setProperty("dexmaker.share_classloader", "true");
        mDisplayDevice = mock(DisplayDevice.class);
        mDisplayAdapter = mock(DisplayAdapter.class);
        mContext = mock(Context.class);
        mDisplayToken = mock(IBinder.class);
        mLogicalDisplay = new LogicalDisplay(DISPLAY_ID, LAYER_STACK, mDisplayDevice);

        mDisplayDeviceInfo.copyFrom(new DisplayDeviceInfo());
        mDisplayDeviceInfo.width = DISPLAY_WIDTH;
        mDisplayDeviceInfo.height = DISPLAY_HEIGHT;
        mDisplayDeviceInfo.touch = DisplayDeviceInfo.TOUCH_INTERNAL;
        mDisplayDeviceInfo.modeId = MODE_ID;
        mDisplayDeviceInfo.supportedModes = new Display.Mode[] {new Display.Mode(MODE_ID,
                DISPLAY_WIDTH, DISPLAY_HEIGHT, /* refreshRate= */ 60)};
        when(mDisplayDevice.getDisplayDeviceInfoLocked()).thenReturn(mDisplayDeviceInfo);

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
        mLogicalDisplay.updateLocked(mDeviceRepo);
    }

    @Test
    public void testGetDisplayPosition() {
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
        // Rotation doesn't matter when the FLAG_ROTATES_WITH_CONTENT is absent.
        displayInfo.rotation = Surface.ROTATION_90;
        mLogicalDisplay.setDisplayInfoOverrideFromWindowManagerLocked(displayInfo);
        mLogicalDisplay.configureDisplayLocked(t, mDisplayDevice, false);
        assertEquals(expectedPosition, mLogicalDisplay.getDisplayPosition());

        expectedPosition.set(40, -20);
        mDisplayDeviceInfo.flags = DisplayDeviceInfo.FLAG_ROTATES_WITH_CONTENT;
        mLogicalDisplay.updateLocked(mDeviceRepo);
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
        mLogicalDisplay.updateLocked(mDeviceRepo);
        assertEquals(Display.FLAG_REAR | Display.FLAG_PRESENTATION,
                mLogicalDisplay.getDisplayInfoLocked().flags);
        assertEquals(Display.REMOVE_MODE_DESTROY_CONTENT,
                mLogicalDisplay.getDisplayInfoLocked().removeMode);

        // And then check the unsetting the position resets both
        mLogicalDisplay.setDevicePositionLocked(Layout.Display.POSITION_UNKNOWN);
        mLogicalDisplay.updateLocked(mDeviceRepo);
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

        mLogicalDisplay.updateLocked(mDeviceRepo);
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

        mLogicalDisplay.updateLocked(mDeviceRepo);
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

        mLogicalDisplay.updateLocked(mDeviceRepo);
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

        mLogicalDisplay.updateLocked(mDeviceRepo);
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

        mLogicalDisplay.updateLocked(mDeviceRepo);
        DisplayInfo info3 = mLogicalDisplay.getDisplayInfoLocked();
        assertNotEquals(info3, info2);
        assertEquals(newId, info3.displayGroupId);
    }

    @Test
    public void testUpdateDisplayGroupIdLocked_setsDirtyFlag() {
        assertFalse(mLogicalDisplay.isDirtyLocked());

        mLogicalDisplay.updateDisplayGroupIdLocked(99);
        assertTrue(mLogicalDisplay.isDirtyLocked());

        mLogicalDisplay.updateLocked(mDeviceRepo);
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

        mLogicalDisplay.updateLocked(mDeviceRepo);
        DisplayInfo info3 = mLogicalDisplay.getDisplayInfoLocked();
        assertNotEquals(info3, info2);
        assertEquals(brightnessThrottlingDataId, info3.thermalBrightnessThrottlingDataId);
    }

    @Test
    public void testSetThermalBrightnessThrottlingDataId_setsDirtyFlag() {
        assertFalse(mLogicalDisplay.isDirtyLocked());

        mLogicalDisplay.setThermalBrightnessThrottlingDataIdLocked("99");
        assertTrue(mLogicalDisplay.isDirtyLocked());

        mLogicalDisplay.updateLocked(mDeviceRepo);
        assertFalse(mLogicalDisplay.isDirtyLocked());
    }
}
