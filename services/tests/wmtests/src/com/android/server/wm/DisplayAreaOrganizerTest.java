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

package com.android.server.wm;

import static android.window.DisplayAreaOrganizer.FEATURE_VENDOR_FIRST;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Binder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.view.SurfaceControl;
import android.window.DisplayAreaInfo;
import android.window.IDisplayAreaOrganizer;

import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class DisplayAreaOrganizerTest extends WindowTestsBase {

    private DisplayArea mTestDisplayArea;

    @Before
    public void setUp() {
        WindowContainer parentWindow = mDisplayContent.getDefaultTaskDisplayArea().getParent();
        mTestDisplayArea = new DisplayArea(mWm, DisplayArea.Type.ANY,
                "TestDisplayArea", FEATURE_VENDOR_FIRST);
        parentWindow.addChild(mTestDisplayArea,
                parentWindow.mChildren.indexOf(mDisplayContent.getDefaultTaskDisplayArea()) + 1);
    }

    @After
    public void tearDown() {
        mTestDisplayArea.removeImmediately();
    }

    private IDisplayAreaOrganizer registerMockOrganizer(int feature) {
        final IDisplayAreaOrganizer organizer = mock(IDisplayAreaOrganizer.class);
        when(organizer.asBinder()).thenReturn(new Binder());

        mWm.mAtmService.mWindowOrganizerController.mDisplayAreaOrganizerController
                .registerOrganizer(organizer, feature);
        return organizer;
    }

    private void unregisterMockOrganizer(IDisplayAreaOrganizer organizer) {
        mWm.mAtmService.mWindowOrganizerController.mDisplayAreaOrganizerController
                .unregisterOrganizer(organizer);
    }

    @Test
    public void testAppearedVanished() throws RemoteException {
        IDisplayAreaOrganizer organizer = registerMockOrganizer(FEATURE_VENDOR_FIRST);
        verify(organizer)
                .onDisplayAreaAppeared(any(DisplayAreaInfo.class), any(SurfaceControl.class));

        unregisterMockOrganizer(organizer);
        verify(organizer).onDisplayAreaVanished(any());
    }

    @Test
    public void testChanged() throws RemoteException {
        IDisplayAreaOrganizer organizer = registerMockOrganizer(FEATURE_VENDOR_FIRST);
        verify(organizer)
                .onDisplayAreaAppeared(any(DisplayAreaInfo.class), any(SurfaceControl.class));

        mDisplayContent.setBounds(new Rect(0, 0, 1000, 1000));
        verify(organizer).onDisplayAreaInfoChanged(any());

        Configuration tmpConfiguration = new Configuration();
        tmpConfiguration.setTo(mDisplayContent.getRequestedOverrideConfiguration());
        mDisplayContent.onRequestedOverrideConfigurationChanged(tmpConfiguration);
        // Ensure it was still only called once if the bounds didn't change
        verify(organizer).onDisplayAreaInfoChanged(any());
    }
}
