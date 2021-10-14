/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static com.android.server.display.DisplayAdapter.DISPLAY_DEVICE_EVENT_ADDED;
import static com.android.server.display.DisplayAdapter.DISPLAY_DEVICE_EVENT_CHANGED;
import static com.android.server.display.DisplayAdapter.DISPLAY_DEVICE_EVENT_REMOVED;
import static com.android.server.display.LogicalDisplayMapper.LOGICAL_DISPLAY_EVENT_ADDED;
import static com.android.server.display.LogicalDisplayMapper.LOGICAL_DISPLAY_EVENT_REMOVED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.PropertyInvalidatedCache;
import android.content.Context;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Parcel;
import android.os.Process;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;
import android.view.Display;
import android.view.DisplayAddress;
import android.view.DisplayInfo;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class LogicalDisplayMapperTest {
    private static int sUniqueTestDisplayId = 0;

    private DisplayDeviceRepository mDisplayDeviceRepo;
    private LogicalDisplayMapper mLogicalDisplayMapper;
    private TestLooper mLooper;
    private Handler mHandler;

    @Mock LogicalDisplayMapper.Listener mListenerMock;
    @Mock Context mContextMock;
    @Mock Resources mResourcesMock;

    @Captor ArgumentCaptor<LogicalDisplay> mDisplayCaptor;

    @Before
    public void setUp() {
        // Share classloader to allow package private access.
        System.setProperty("dexmaker.share_classloader", "true");
        MockitoAnnotations.initMocks(this);

        mDisplayDeviceRepo = new DisplayDeviceRepository(
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

        // Disable binder caches in this process.
        PropertyInvalidatedCache.disableForTestMode();

        when(mContextMock.getResources()).thenReturn(mResourcesMock);
        when(mResourcesMock.getBoolean(
                com.android.internal.R.bool.config_supportsConcurrentInternalDisplays))
                .thenReturn(true);

        mLooper = new TestLooper();
        mHandler = new Handler(mLooper.getLooper());
        mLogicalDisplayMapper = new LogicalDisplayMapper(mContextMock, mDisplayDeviceRepo,
                mListenerMock, new DisplayManagerService.SyncRoot(), mHandler);
    }


    /////////////////
    // Test Methods
    /////////////////

    @Test
    public void testDisplayDeviceAddAndRemove_Internal() {
        DisplayDevice device = createDisplayDevice(Display.TYPE_INTERNAL, 600, 800,
                DisplayDeviceInfo.FLAG_DEFAULT_DISPLAY);

        // add
        LogicalDisplay displayAdded = add(device);
        assertEquals(info(displayAdded).address, info(device).address);
        assertEquals(Display.DEFAULT_DISPLAY, id(displayAdded));

        // remove
        mDisplayDeviceRepo.onDisplayDeviceEvent(device, DISPLAY_DEVICE_EVENT_REMOVED);
        verify(mListenerMock).onLogicalDisplayEventLocked(
                mDisplayCaptor.capture(), eq(LOGICAL_DISPLAY_EVENT_REMOVED));
        LogicalDisplay displayRemoved = mDisplayCaptor.getValue();
        assertEquals(Display.DEFAULT_DISPLAY, id(displayRemoved));
        assertEquals(displayAdded, displayRemoved);
    }

    @Test
    public void testDisplayDeviceAddAndRemove_NonInternalTypes() {
        testDisplayDeviceAddAndRemove_NonInternal(Display.TYPE_EXTERNAL);
        testDisplayDeviceAddAndRemove_NonInternal(Display.TYPE_WIFI);
        testDisplayDeviceAddAndRemove_NonInternal(Display.TYPE_OVERLAY);
        testDisplayDeviceAddAndRemove_NonInternal(Display.TYPE_VIRTUAL);
        testDisplayDeviceAddAndRemove_NonInternal(Display.TYPE_UNKNOWN);

        // Call the internal test again, just to verify that adding non-internal displays
        // doesn't affect the ability for an internal display to become the default display.
        testDisplayDeviceAddAndRemove_Internal();
    }

    @Test
    public void testDisplayDeviceAdd_TwoInternalOneDefault() {
        DisplayDevice device1 = createDisplayDevice(Display.TYPE_INTERNAL, 600, 800, 0);
        DisplayDevice device2 = createDisplayDevice(Display.TYPE_INTERNAL, 600, 800,
                DisplayDeviceInfo.FLAG_DEFAULT_DISPLAY);

        LogicalDisplay display1 = add(device1);
        assertEquals(info(display1).address, info(device1).address);
        assertNotEquals(Display.DEFAULT_DISPLAY, id(display1));

        LogicalDisplay display2 = add(device2);
        assertEquals(info(display2).address, info(device2).address);
        assertEquals(Display.DEFAULT_DISPLAY, id(display2));
    }

    @Test
    public void testDisplayDeviceAdd_TwoInternalBothDefault() {
        DisplayDevice device1 = createDisplayDevice(Display.TYPE_INTERNAL, 600, 800,
                DisplayDeviceInfo.FLAG_DEFAULT_DISPLAY);
        DisplayDevice device2 = createDisplayDevice(Display.TYPE_INTERNAL, 600, 800,
                DisplayDeviceInfo.FLAG_DEFAULT_DISPLAY);

        LogicalDisplay display1 = add(device1);
        assertEquals(info(display1).address, info(device1).address);
        assertEquals(Display.DEFAULT_DISPLAY, id(display1));

        LogicalDisplay display2 = add(device2);
        assertEquals(info(display2).address, info(device2).address);
        // Despite the flags, we can only have one default display
        assertNotEquals(Display.DEFAULT_DISPLAY, id(display2));
    }

    @Test
    public void testGetDisplayIdsLocked() {
        add(createDisplayDevice(Display.TYPE_INTERNAL, 600, 800,
                DisplayDeviceInfo.FLAG_DEFAULT_DISPLAY));
        add(createDisplayDevice(Display.TYPE_EXTERNAL, 600, 800, 0));
        add(createDisplayDevice(Display.TYPE_VIRTUAL, 600, 800, 0));

        int [] ids = mLogicalDisplayMapper.getDisplayIdsLocked(Process.SYSTEM_UID);
        assertEquals(3, ids.length);
        Arrays.sort(ids);
        assertEquals(Display.DEFAULT_DISPLAY, ids[0]);
    }

    @Test
    public void testSingleDisplayGroup() {
        LogicalDisplay display1 = add(createDisplayDevice(Display.TYPE_INTERNAL, 600, 800,
                DisplayDeviceInfo.FLAG_DEFAULT_DISPLAY));
        LogicalDisplay display2 = add(createDisplayDevice(Display.TYPE_INTERNAL, 600, 800, 0));
        LogicalDisplay display3 = add(createDisplayDevice(Display.TYPE_VIRTUAL, 600, 800, 0));

        assertEquals(Display.DEFAULT_DISPLAY_GROUP,
                mLogicalDisplayMapper.getDisplayGroupIdFromDisplayIdLocked(id(display1)));
        assertEquals(Display.DEFAULT_DISPLAY_GROUP,
                mLogicalDisplayMapper.getDisplayGroupIdFromDisplayIdLocked(id(display2)));
        assertEquals(Display.DEFAULT_DISPLAY_GROUP,
                mLogicalDisplayMapper.getDisplayGroupIdFromDisplayIdLocked(id(display3)));
    }

    @Test
    public void testMultipleDisplayGroups() {
        LogicalDisplay display1 = add(createDisplayDevice(Display.TYPE_INTERNAL, 600, 800,
                DisplayDeviceInfo.FLAG_DEFAULT_DISPLAY));
        LogicalDisplay display2 = add(createDisplayDevice(Display.TYPE_INTERNAL, 600, 800, 0));


        TestDisplayDevice device3 = createDisplayDevice(Display.TYPE_VIRTUAL, 600, 800,
                DisplayDeviceInfo.FLAG_OWN_DISPLAY_GROUP);
        LogicalDisplay display3 = add(device3);

        assertEquals(Display.DEFAULT_DISPLAY_GROUP,
                mLogicalDisplayMapper.getDisplayGroupIdFromDisplayIdLocked(id(display1)));
        assertEquals(Display.DEFAULT_DISPLAY_GROUP,
                mLogicalDisplayMapper.getDisplayGroupIdFromDisplayIdLocked(id(display2)));
        assertNotEquals(Display.DEFAULT_DISPLAY_GROUP,
                mLogicalDisplayMapper.getDisplayGroupIdFromDisplayIdLocked(id(display3)));

        // Now switch it back to the default group by removing the flag and issuing an update
        DisplayDeviceInfo info = device3.getSourceInfo();
        info.flags = info.flags & ~DisplayDeviceInfo.FLAG_OWN_DISPLAY_GROUP;
        mDisplayDeviceRepo.onDisplayDeviceEvent(device3, DISPLAY_DEVICE_EVENT_CHANGED);

        // Verify the new group is correct.
        assertEquals(Display.DEFAULT_DISPLAY_GROUP,
                mLogicalDisplayMapper.getDisplayGroupIdFromDisplayIdLocked(id(display3)));
    }


    /////////////////
    // Helper Methods
    /////////////////

    private TestDisplayDevice createDisplayDevice(int type, int width, int height, int flags) {
        return createDisplayDevice(new DisplayAddressImpl(), type, width, height, flags);
    }

    private TestDisplayDevice createDisplayDevice(
            DisplayAddress address, int type, int width, int height, int flags) {
        TestDisplayDevice device = new TestDisplayDevice();
        DisplayDeviceInfo displayDeviceInfo = device.getSourceInfo();
        displayDeviceInfo.type = type;
        displayDeviceInfo.width = width;
        displayDeviceInfo.height = height;
        displayDeviceInfo.flags = flags;
        displayDeviceInfo.supportedModes = new Display.Mode[1];
        displayDeviceInfo.supportedModes[0] = new Display.Mode(1, width, height, 60f);
        displayDeviceInfo.modeId = 1;
        displayDeviceInfo.address = new DisplayAddressImpl();
        return device;
    }

    private DisplayDeviceInfo info(DisplayDevice device) {
        return device.getDisplayDeviceInfoLocked();
    }

    private DisplayInfo info(LogicalDisplay display) {
        return display.getDisplayInfoLocked();
    }

    private int id(LogicalDisplay display) {
        return display.getDisplayIdLocked();
    }

    private LogicalDisplay add(DisplayDevice device) {
        mDisplayDeviceRepo.onDisplayDeviceEvent(device, DISPLAY_DEVICE_EVENT_ADDED);
        ArgumentCaptor<LogicalDisplay> displayCaptor =
                ArgumentCaptor.forClass(LogicalDisplay.class);
        verify(mListenerMock).onLogicalDisplayEventLocked(
                displayCaptor.capture(), eq(LOGICAL_DISPLAY_EVENT_ADDED));
        clearInvocations(mListenerMock);
        return displayCaptor.getValue();
    }

    private void testDisplayDeviceAddAndRemove_NonInternal(int type) {
        DisplayDevice device = createDisplayDevice(type, 600, 800, 0);

        // add
        LogicalDisplay displayAdded = add(device);
        assertEquals(info(displayAdded).address, info(device).address);
        assertNotEquals(Display.DEFAULT_DISPLAY, id(displayAdded));

        // remove
        mDisplayDeviceRepo.onDisplayDeviceEvent(device, DISPLAY_DEVICE_EVENT_REMOVED);
        verify(mListenerMock).onLogicalDisplayEventLocked(
                mDisplayCaptor.capture(), eq(LOGICAL_DISPLAY_EVENT_REMOVED));
        LogicalDisplay displayRemoved = mDisplayCaptor.getValue();
        assertNotEquals(Display.DEFAULT_DISPLAY, id(displayRemoved));
    }

    /**
     * Create a custom {@link DisplayAddress} to ensure we're not relying on any specific
     * display-address implementation in our code. Intentionally uses default object (reference)
     * equality rules.
     */
    class DisplayAddressImpl extends DisplayAddress {
        @Override
        public void writeToParcel(Parcel out, int flags) { }
    }

    class TestDisplayDevice extends DisplayDevice {
        private DisplayDeviceInfo mInfo = new DisplayDeviceInfo();
        private DisplayDeviceInfo mSentInfo;

        TestDisplayDevice() {
            super(null, null, "test_display_" + sUniqueTestDisplayId++, mContextMock);
            mInfo = new DisplayDeviceInfo();
        }

        @Override
        public DisplayDeviceInfo getDisplayDeviceInfoLocked() {
            if (mSentInfo == null) {
                mSentInfo = new DisplayDeviceInfo();
                mSentInfo.copyFrom(mInfo);
            }
            return mSentInfo;
        }

        @Override
        public void applyPendingDisplayDeviceInfoChangesLocked() {
            mSentInfo = null;
        }

        @Override
        public boolean hasStableUniqueId() {
            return true;
        }

        public DisplayDeviceInfo getSourceInfo() {
            return mInfo;
        }
    }
}

