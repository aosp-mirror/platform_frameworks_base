/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.doze;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.app.IWallpaperManager;
import android.os.Handler;
import android.os.RemoteException;
import android.support.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
@SmallTest
public class DozeWallpaperStateTest extends SysuiTestCase {

    @Test
    public void testDreamNotification() throws RemoteException {
        IWallpaperManager wallpaperManagerService = mock(IWallpaperManager.class);
        DozeWallpaperState dozeWallpaperState = new DozeWallpaperState(wallpaperManagerService);
        dozeWallpaperState.transitionTo(DozeMachine.State.UNINITIALIZED,
                DozeMachine.State.DOZE_AOD);
        verify(wallpaperManagerService).setInAmbientMode(eq(true));
        dozeWallpaperState.transitionTo(DozeMachine.State.DOZE_AOD, DozeMachine.State.FINISH);
        verify(wallpaperManagerService).setInAmbientMode(eq(false));
    }
}
