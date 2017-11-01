/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.policy;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.os.Handler;
import android.service.notification.ZenModeConfig;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.policy.ZenModeController.Callback;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class ZenModeControllerImplTest extends SysuiTestCase {

    private Callback mCallback;

    @Test
    public void testRemoveDuringCallback() {
        ZenModeControllerImpl controller = new ZenModeControllerImpl(mContext, new Handler());
        mCallback = new Callback() {
            @Override
            public void onConfigChanged(ZenModeConfig config) {
                controller.removeCallback(mCallback);
            }
        };
        controller.addCallback(mCallback);
        Callback mockCallback = mock(Callback.class);
        controller.addCallback(mockCallback);
        controller.fireConfigChanged(null);
        verify(mockCallback).onConfigChanged(eq(null));
    }

}