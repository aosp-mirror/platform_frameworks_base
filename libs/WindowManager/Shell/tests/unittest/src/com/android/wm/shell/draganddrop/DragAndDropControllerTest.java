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

package com.android.wm.shell.draganddrop;

import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import android.content.Context;
import android.os.RemoteException;
import android.view.Display;
import android.view.DragEvent;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.logging.UiEventLogger;
import com.android.launcher3.icons.IconProvider;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.ShellExecutor;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for the drag and drop controller.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class DragAndDropControllerTest {

    @Mock
    private Context mContext;

    @Mock
    private DisplayController mDisplayController;

    @Mock
    private UiEventLogger mUiEventLogger;

    private DragAndDropController mController;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        mController = new DragAndDropController(mContext, mDisplayController, mUiEventLogger,
                mock(IconProvider.class), mock(ShellExecutor.class));
    }

    @Test
    public void testIgnoreNonDefaultDisplays() {
        final int nonDefaultDisplayId = 12345;
        final View dragLayout = mock(View.class);
        final Display display = mock(Display.class);
        doReturn(nonDefaultDisplayId).when(display).getDisplayId();
        doReturn(display).when(dragLayout).getDisplay();

        // Expect no per-display layout to be added
        mController.onDisplayAdded(nonDefaultDisplayId);
        assertFalse(mController.onDrag(dragLayout, mock(DragEvent.class)));
    }
}
