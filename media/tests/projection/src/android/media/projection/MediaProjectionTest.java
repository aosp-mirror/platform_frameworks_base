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

package android.media.projection;

import static android.Manifest.permission.MANAGE_MEDIA_PROJECTION;
import static android.media.projection.MediaProjection.MEDIA_PROJECTION_REQUIRES_CALLBACK;
import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static com.google.common.truth.Truth.assertThat;

import static libcore.junit.util.compat.CoreCompatChangeRule.DisableCompatChanges;
import static libcore.junit.util.compat.CoreCompatChangeRule.EnableCompatChanges;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doReturn;

import android.annotation.Nullable;
import android.app.ActivityOptions.LaunchCookie;
import android.compat.testing.PlatformCompatChangeRule;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.display.VirtualDisplayConfig;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.test.FakePermissionEnforcer;
import android.platform.test.annotations.Presubmit;
import android.testing.TestableContext;
import android.view.Display;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;


/**
 * Tests for the {@link MediaProjection} class.
 *
 * Build/Install/Run:
 * atest MediaProjectionTests:MediaProjectionTest
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class MediaProjectionTest {
    // Values for creating a VirtualDisplay.
    private static final String VIRTUAL_DISPLAY_NAME = "MEDIA_PROJECTION_VIRTUAL_DISPLAY";
    private static final int VIRTUAL_DISPLAY_WIDTH = 500;
    private static final int VIRTUAL_DISPLAY_HEIGHT = 600;
    private static final int VIRTUAL_DISPLAY_DENSITY = 100;
    private static final int VIRTUAL_DISPLAY_FLAGS = 0;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    // Fake the connection to the system server.
    private FakeIMediaProjection mFakeIMediaProjection;
    // Callback registered by an app.
    private MediaProjection mMediaProjection;

    @Mock
    private MediaProjection.Callback mMediaProjectionCallback;
    @Mock
    private Display mDisplay;
    @Mock
    private VirtualDisplay.Callback mVirtualDisplayCallback;
    @Mock
    private VirtualDisplay mVirtualDisplay;
    @Mock
    private DisplayManager mDisplayManager;

    @Rule
    public TestRule compatChangeRule = new PlatformCompatChangeRule();

    @Rule
    public final TestableContext mTestableContext = new TestableContext(
            InstrumentationRegistry.getInstrumentation().getTargetContext());

    private MockitoSession mMockingSession;

    @Before
    public void setup() throws Exception {
        mMockingSession =
                mockitoSession()
                        .initMocks(this)
                        .strictness(Strictness.LENIENT)
                        .startMocking();

        FakePermissionEnforcer permissionEnforcer = new FakePermissionEnforcer();
        permissionEnforcer.grant(MANAGE_MEDIA_PROJECTION);
        // Support the MediaProjection instance.
        mFakeIMediaProjection = new FakeIMediaProjection(permissionEnforcer);
        mFakeIMediaProjection.setLaunchCookie(new LaunchCookie());
        mMediaProjection = new MediaProjection(mTestableContext, mFakeIMediaProjection,
                mDisplayManager);

        // Support creation of the VirtualDisplay.
        mTestableContext.addMockSystemService(DisplayManager.class, mDisplayManager);
        doReturn(mDisplay).when(mVirtualDisplay).getDisplay();
        doReturn(DEFAULT_DISPLAY + 7).when(mDisplay).getDisplayId();
        doReturn(mVirtualDisplay).when(mDisplayManager).createVirtualDisplay(
                any(MediaProjection.class), any(VirtualDisplayConfig.class),
                nullable(VirtualDisplay.Callback.class), nullable(Handler.class));
    }

    @After
    public void tearDown() {
        mMockingSession.finishMocking();
    }

    @Test
    public void testConstruction() throws RemoteException {
        assertThat(mMediaProjection).isNotNull();
        assertThat(mFakeIMediaProjection.mIsStarted).isTrue();
    }

    @Test
    public void testRegisterCallback_null() {
        assertThrows(NullPointerException.class,
                () -> mMediaProjection.registerCallback(null, mHandler));
    }

    @Test
    public void testUnregisterCallback_null() {
        mMediaProjection.registerCallback(mMediaProjectionCallback, mHandler);
        assertThrows(NullPointerException.class,
                () -> mMediaProjection.unregisterCallback(null));
    }

    @Test
    @DisableCompatChanges({MEDIA_PROJECTION_REQUIRES_CALLBACK})
    public void createVirtualDisplay_noCallbackRequired_missingMediaProjectionCallback() {
        assertThat(createVirtualDisplay(null)).isNotNull();
        assertThat(createVirtualDisplay(mVirtualDisplayCallback)).isNotNull();
    }

    @Test
    @DisableCompatChanges({MEDIA_PROJECTION_REQUIRES_CALLBACK})
    public void createVirtualDisplay_noCallbackRequired_givenMediaProjectionCallback() {
        mMediaProjection.registerCallback(mMediaProjectionCallback, mHandler);
        assertThat(createVirtualDisplay(null)).isNotNull();
        assertThat(createVirtualDisplay(mVirtualDisplayCallback)).isNotNull();
    }

    @Test
    @EnableCompatChanges({MEDIA_PROJECTION_REQUIRES_CALLBACK})
    public void createVirtualDisplay_callbackRequired_missingMediaProjectionCallback() {
        assertThrows(IllegalStateException.class,
                () -> createVirtualDisplay(mVirtualDisplayCallback));
    }

    @Test
    @EnableCompatChanges({MEDIA_PROJECTION_REQUIRES_CALLBACK})
    public void createVirtualDisplay_callbackRequired_givenMediaProjectionCallback() {
        mMediaProjection.registerCallback(mMediaProjectionCallback, mHandler);
        assertThat(createVirtualDisplay(null)).isNotNull();
        assertThat(createVirtualDisplay(mVirtualDisplayCallback)).isNotNull();
    }

    private VirtualDisplay createVirtualDisplay(@Nullable VirtualDisplay.Callback callback) {
        // No recording will take place with a null surface.
        return mMediaProjection.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME, VIRTUAL_DISPLAY_WIDTH,
                VIRTUAL_DISPLAY_HEIGHT, VIRTUAL_DISPLAY_DENSITY,
                VIRTUAL_DISPLAY_FLAGS, /* surface = */ null,
                callback, /* handler= */ mHandler);
    }
}
