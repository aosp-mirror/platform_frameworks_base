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
 * limitations under the License.
 */

package com.android.systemui;

import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.CountDownLatch;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ImageWallpaperTest extends SysuiTestCase {

    private CountDownLatch mEventCountdown;
    private CountDownLatch mAmbientEventCountdown;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mEventCountdown = new CountDownLatch(1);
        mAmbientEventCountdown = new CountDownLatch(2);
    }

    @Test
    public void testDeliversAmbientModeChanged() {
        //TODO: We need add tests for GLEngine.
    }

    // TODO: Add more test cases for GLEngine, tracing in b/124838911.
}
