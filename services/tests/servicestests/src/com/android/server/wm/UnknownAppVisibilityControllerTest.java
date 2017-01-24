/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wm;

import static junit.framework.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;

import android.app.ActivityManagerInternal;
import android.content.Context;
import android.platform.test.annotations.Presubmit;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.server.LocalServices;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;

/**
 * Test class for {@link AppTransition}.
 *
 * runtest frameworks-services -c com.android.server.wm.UnknownAppVisibilityControllerTest
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class UnknownAppVisibilityControllerTest extends WindowTestsBase {

    private WindowManagerService mWm;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        final Context context = InstrumentationRegistry.getTargetContext();
        doAnswer((InvocationOnMock invocationOnMock) -> {
            invocationOnMock.getArgumentAt(0, Runnable.class).run();
            return null;
        }).when(sMockAm).notifyKeyguardFlagsChanged(any());
        mWm = TestWindowManagerPolicy.getWindowManagerService(context);
    }

    @After
    public void tearDown() throws Exception {
        LocalServices.removeServiceForTest(ActivityManagerInternal.class);
    }

    @Test
    public void testFlow() throws Exception {
        AppWindowToken token = createAppToken();
        mWm.mUnknownAppVisibilityController.notifyLaunched(token);
        mWm.mUnknownAppVisibilityController.notifyAppResumedFinished(token);
        mWm.mUnknownAppVisibilityController.notifyRelayouted(token);

        // Make sure our handler processed the message.
        Thread.sleep(100);
        assertTrue(mWm.mUnknownAppVisibilityController.allResolved());
    }

    @Test
    public void testMultiple() throws Exception {
        AppWindowToken token1 = createAppToken();
        AppWindowToken token2 = createAppToken();
        mWm.mUnknownAppVisibilityController.notifyLaunched(token1);
        mWm.mUnknownAppVisibilityController.notifyAppResumedFinished(token1);
        mWm.mUnknownAppVisibilityController.notifyLaunched(token2);
        mWm.mUnknownAppVisibilityController.notifyRelayouted(token1);
        mWm.mUnknownAppVisibilityController.notifyAppResumedFinished(token2);
        mWm.mUnknownAppVisibilityController.notifyRelayouted(token2);

        // Make sure our handler processed the message.
        Thread.sleep(100);
        assertTrue(mWm.mUnknownAppVisibilityController.allResolved());
    }

    @Test
    public void testClear() throws Exception {
        AppWindowToken token = createAppToken();
        mWm.mUnknownAppVisibilityController.notifyLaunched(token);
        mWm.mUnknownAppVisibilityController.clear();;
        assertTrue(mWm.mUnknownAppVisibilityController.allResolved());
    }

    @Test
    public void testAppRemoved() throws Exception {
        AppWindowToken token = createAppToken();
        mWm.mUnknownAppVisibilityController.notifyLaunched(token);
        mWm.mUnknownAppVisibilityController.appRemoved(token);
        assertTrue(mWm.mUnknownAppVisibilityController.allResolved());
    }

    private AppWindowToken createAppToken() {
        return new AppWindowToken(mWm, null, false, mWm.getDefaultDisplayContentLocked());
    }
}
