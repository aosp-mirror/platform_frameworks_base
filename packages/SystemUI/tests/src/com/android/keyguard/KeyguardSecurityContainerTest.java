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

package com.android.keyguard;

import org.junit.Test;
import org.junit.runner.RunWith;

import android.content.Context;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.test.UiThreadTest;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;

import com.android.systemui.SysuiTestCase;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class KeyguardSecurityContainerTest extends SysuiTestCase {

    @UiThreadTest
    @Test
    public void showSecurityScreen_canInflateAllModes() {
        KeyguardSecurityContainer keyguardSecurityContainer =
                new KeyguardSecurityContainer(getContext());

        Context context = getContext();

        for (int theme : new int[] {R.style.Theme_SystemUI, R.style.Theme_SystemUI_Light}) {
            context.setTheme(theme);
            final LayoutInflater inflater = LayoutInflater.from(context);
            KeyguardSecurityModel.SecurityMode[] modes =
                    KeyguardSecurityModel.SecurityMode.values();
            for (KeyguardSecurityModel.SecurityMode mode : modes) {
                final int resId = keyguardSecurityContainer.getLayoutIdFor(mode);
                if (resId == 0) {
                    continue;
                }
                inflater.inflate(resId, null /* root */, false /* attach */);
            }
        }
    }
}