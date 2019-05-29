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

package com.android.keyguard;

import android.content.Context;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.keyguard.KeyguardDisplayManager.KeyguardPresentation;
import com.android.systemui.SystemUIFactory;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.InjectionInflationController;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class KeyguardPresentationTest extends SysuiTestCase {
    @Test
    public void testInflation_doesntCrash() {
        com.android.systemui.util.Assert.sMainLooper = TestableLooper.get(this).getLooper();
        InjectionInflationController inflationController = new InjectionInflationController(
                SystemUIFactory.getInstance().getRootComponent());
        Context context = getContext();
        KeyguardPresentation keyguardPresentation =
                new KeyguardPresentation(context, context.getDisplay(), inflationController);
        keyguardPresentation.onCreate(null /*savedInstanceState */);
    }
}
