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

package com.android.systemui.statusbar.phone;

import static org.mockito.Mockito.mock;

import android.content.Context;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.test.UiThreadTest;
import android.view.ContextThemeWrapper;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.keyguard.DismissCallbackRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class KeyguardBouncerTest extends SysuiTestCase {

    @UiThreadTest
    @Test
    public void inflateDetached() {
        final ViewGroup container = new FrameLayout(getContext());
        final KeyguardBouncer bouncer = new KeyguardBouncer(getContext(),
                mock(ViewMediatorCallback.class), mock(LockPatternUtils.class), container, mock(
                DismissCallbackRegistry.class));

        // Detached bouncer should still be able to be inflated
        bouncer.inflateView();
    }

}
