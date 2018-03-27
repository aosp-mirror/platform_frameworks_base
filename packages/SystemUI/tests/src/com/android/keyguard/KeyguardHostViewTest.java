/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static org.mockito.Mockito.mock;

import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.systemui.SysuiTestCase;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class KeyguardHostViewTest extends SysuiTestCase {

    private KeyguardHostView mKeyguardHostView;

    @Before
    public void setup() {
        mKeyguardHostView = new KeyguardHostView(getContext());
    }

    @Test
    public void testHasDismissActions() {
        Assert.assertFalse("Action not set yet", mKeyguardHostView.hasDismissActions());
        mKeyguardHostView.setOnDismissAction(mock(KeyguardHostView.OnDismissAction.class),
                null /* cancelAction */);
        Assert.assertTrue("Action should exist", mKeyguardHostView.hasDismissActions());
    }
}
