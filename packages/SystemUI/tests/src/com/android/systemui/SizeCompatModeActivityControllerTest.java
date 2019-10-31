/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.inputmethodservice.InputMethodService;
import android.os.IBinder;
import android.testing.AndroidTestingRunner;
import android.view.View;

import androidx.test.filters.SmallTest;

import com.android.systemui.SizeCompatModeActivityController.RestartActivityButton;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.statusbar.CommandQueue;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * runtest systemui -c com.android.systemui.SizeCompatModeActivityControllerTest
 */
@RunWith(AndroidTestingRunner.class)
@SmallTest
public class SizeCompatModeActivityControllerTest extends SysuiTestCase {
    private static final int DISPLAY_ID = 0;

    private SizeCompatModeActivityController mController;
    private TaskStackChangeListener mTaskStackListener;
    private @Mock ActivityManagerWrapper mMockAm;
    private @Mock RestartActivityButton mMockButton;
    private @Mock IBinder mMockActivityToken;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        doReturn(true).when(mMockButton).show();

        mController = new SizeCompatModeActivityController(mContext, mMockAm,
                new CommandQueue(mContext)) {
            @Override
            RestartActivityButton createRestartButton(Context context) {
                return mMockButton;
            };
        };

        ArgumentCaptor<TaskStackChangeListener> listenerCaptor =
                ArgumentCaptor.forClass(TaskStackChangeListener.class);
        verify(mMockAm).registerTaskStackListener(listenerCaptor.capture());
        mTaskStackListener = listenerCaptor.getValue();
    }

    @Test
    public void testOnSizeCompatModeActivityChanged() {
        // Verifies that the restart button is added with non-null component name.
        mTaskStackListener.onSizeCompatModeActivityChanged(DISPLAY_ID, mMockActivityToken);
        verify(mMockButton).show();
        verify(mMockButton).updateLastTargetActivity(eq(mMockActivityToken));

        // Verifies that the restart button is removed with null component name.
        mTaskStackListener.onSizeCompatModeActivityChanged(DISPLAY_ID, null /* activityToken */);
        verify(mMockButton).remove();
    }

    @Test
    public void testChangeButtonVisibilityOnImeShowHide() {
        mTaskStackListener.onSizeCompatModeActivityChanged(DISPLAY_ID, mMockActivityToken);

        // Verifies that the restart button is hidden when IME is visible.
        doReturn(View.VISIBLE).when(mMockButton).getVisibility();
        mController.setImeWindowStatus(DISPLAY_ID, null /* token */, InputMethodService.IME_VISIBLE,
                0 /* backDisposition */, false /* showImeSwitcher */);
        verify(mMockButton).setVisibility(eq(View.GONE));

        // Verifies that the restart button is visible when IME is hidden.
        doReturn(View.GONE).when(mMockButton).getVisibility();
        mController.setImeWindowStatus(DISPLAY_ID, null /* token */, 0 /* vis */,
                0 /* backDisposition */, false /* showImeSwitcher */);
        verify(mMockButton).setVisibility(eq(View.VISIBLE));
    }
}
