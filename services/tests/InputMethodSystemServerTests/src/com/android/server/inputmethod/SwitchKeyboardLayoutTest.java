/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.inputmethod;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.ExtendedMockito;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class SwitchKeyboardLayoutTest extends InputMethodManagerServiceTestBase {
    @Test
    public void testSwitchToNextKeyboardLayout() {
        ExtendedMockito.spyOn(mInputMethodManagerService.mSwitchingController);
        InputMethodManagerInternal.get().switchKeyboardLayout(1);
        verify(mInputMethodManagerService.mSwitchingController)
                .getNextInputMethodLocked(eq(true) /* onlyCurrentIme */, any(), any());
    }
}
