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

package com.android.server.media.projection;


import static android.content.pm.ApplicationInfo.PRIVATE_FLAG_PRIVILEGED;
import static android.media.projection.MediaProjectionManager.TYPE_MIRRORING;
import static android.view.Display.DEFAULT_DISPLAY;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.testng.Assert.assertThrows;

import android.app.ActivityManagerInternal;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.ApplicationInfoFlags;
import android.content.pm.PackageManager.NameNotFoundException;
import android.media.projection.IMediaProjectionCallback;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.view.ContentRecordingSession;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.LocalServices;
import com.android.server.testutils.OffsettableClock;
import com.android.server.wm.WindowManagerInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for the {@link MediaProjectionManagerService} class.
 *
 * Build/Install/Run:
 * atest FrameworksServicesTests:MediaProjectionManagerServiceTest
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class MediaProjectionManagerServiceTest {
    private static final int UID = 10;
    private static final String PACKAGE_NAME = "test.package";
    private final ApplicationInfo mAppInfo = new ApplicationInfo();
    private static final ContentRecordingSession DISPLAY_SESSION =
            ContentRecordingSession.createDisplaySession(DEFAULT_DISPLAY);
    // Callback registered by an app on a MediaProjection instance.
    private final FakeIMediaProjectionCallback mIMediaProjectionCallback =
            new FakeIMediaProjectionCallback();

    private final MediaProjectionManagerService.Injector mPreventReusedTokenEnabledInjector =
            new MediaProjectionManagerService.Injector() {
                @Override
                boolean shouldMediaProjectionPreventReusingConsent(
                        MediaProjectionManagerService.MediaProjection projection) {
                    return true;
                }
            };

    private final MediaProjectionManagerService.Injector mPreventReusedTokenDisabledInjector =
            new MediaProjectionManagerService.Injector() {
                @Override
                boolean shouldMediaProjectionPreventReusingConsent(
                        MediaProjectionManagerService.MediaProjection projection) {
                    return false;
                }
            };

    private Context mContext;
    private MediaProjectionManagerService mService;
    private OffsettableClock mClock;
    private ContentRecordingSession mWaitingDisplaySession =
            ContentRecordingSession.createDisplaySession(DEFAULT_DISPLAY);

    @Mock
    private ActivityManagerInternal mAmInternal;
    @Mock
    private WindowManagerInternal mWindowManagerInternal;
    @Mock
    private PackageManager mPackageManager;

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);

        LocalServices.removeServiceForTest(ActivityManagerInternal.class);
        LocalServices.addService(ActivityManagerInternal.class, mAmInternal);
        LocalServices.removeServiceForTest(WindowManagerInternal.class);
        LocalServices.addService(WindowManagerInternal.class, mWindowManagerInternal);

        mContext = spy(new ContextWrapper(
                InstrumentationRegistry.getInstrumentation().getTargetContext()));
        doReturn(mPackageManager).when(mContext).getPackageManager();

        mClock = new OffsettableClock.Stopped();
        mWaitingDisplaySession.setWaitingToRecord(true);
        mWaitingDisplaySession.setVirtualDisplayId(5);

        mAppInfo.targetSdkVersion = 32;

        mService = new MediaProjectionManagerService(mContext);
    }

    @After
    public void tearDown() {
        LocalServices.removeServiceForTest(ActivityManagerInternal.class);
        LocalServices.removeServiceForTest(WindowManagerInternal.class);
    }

    @Test
    public void testGetActiveProjectionInfoInternal() throws NameNotFoundException {
        assertThat(mService.getActiveProjectionInfo()).isNull();

        MediaProjectionManagerService.MediaProjection projection = startProjectionPreconditions();

        // Create a projection, active is still null.
        assertThat(projection).isNotNull();
        assertThat(mService.getActiveProjectionInfo()).isNull();

        // Start the projection, active is now not null.
        projection.start(mIMediaProjectionCallback);
        assertThat(mService.getActiveProjectionInfo()).isNotNull();
    }

    @Test
    public void testCreateProjection() throws NameNotFoundException {
        MediaProjectionManagerService.MediaProjection projection =
                startProjectionPreconditions(/* packageAttemptedReusingGrantedConsent= */ false);
        projection.start(mIMediaProjectionCallback);

        MediaProjectionManagerService.MediaProjection secondProjection =
                startProjectionPreconditions(/* packageAttemptedReusingGrantedConsent= */ false);
        assertThat(secondProjection).isNotNull();
        assertThat(secondProjection).isNotEqualTo(projection);
    }

    @Test
    public void testCreateProjection_attemptReuse_noPriorProjectionGrant()
            throws NameNotFoundException {
        MediaProjectionManagerService.MediaProjection projection =
                startProjectionPreconditions(/* packageAttemptedReusingGrantedConsent= */ false);
        projection.start(mIMediaProjectionCallback);

        MediaProjectionManagerService.MediaProjection secondProjection =
                startProjectionPreconditions(/* packageAttemptedReusingGrantedConsent= */ true);

        assertThat(secondProjection).isNotNull();
        assertThat(secondProjection).isNotEqualTo(projection);
    }

    @Test
    public void testCreateProjection_attemptReuse_priorProjectionGrant_notWaiting()
            throws NameNotFoundException {
        MediaProjectionManagerService.MediaProjection projection =
                startProjectionPreconditions(/* packageAttemptedReusingGrantedConsent= */ false);
        projection.start(mIMediaProjectionCallback);

        // Mark this projection as not waiting.
        doReturn(true).when(mWindowManagerInternal).setContentRecordingSession(
                any(ContentRecordingSession.class));
        mService.setContentRecordingSession(DISPLAY_SESSION);

        // We are allowed to create another projection.
        MediaProjectionManagerService.MediaProjection secondProjection =
                startProjectionPreconditions(/* packageAttemptedReusingGrantedConsent= */ true);

        assertThat(secondProjection).isNotNull();

        // But this is a new projection.
        assertThat(secondProjection).isNotEqualTo(projection);
    }

    @Test
    public void testCreateProjection_attemptReuse_priorProjectionGrant_waiting_differentPackage()
            throws NameNotFoundException {
        MediaProjectionManagerService.MediaProjection projection =
                startProjectionPreconditions(/* packageAttemptedReusingGrantedConsent= */ false);
        projection.start(mIMediaProjectionCallback);

        // Mark this projection as not waiting.
        mService.setContentRecordingSession(mWaitingDisplaySession);

        // We are allowed to create another projection.
        MediaProjectionManagerService.MediaProjection secondProjection =
                mService.createProjectionInternal(UID + 10, PACKAGE_NAME + "foo",
                        TYPE_MIRRORING, /* isPermanentGrant= */ true,
                        UserHandle.CURRENT, /* packageAttemptedReusingGrantedConsent= */ true);

        assertThat(secondProjection).isNotNull();

        // But this is a new projection.
        assertThat(secondProjection).isNotEqualTo(projection);
    }

    @Test
    public void testIsValid_multipleStarts_preventionDisabled() throws NameNotFoundException {
        MediaProjectionManagerService service = new MediaProjectionManagerService(mContext,
                mPreventReusedTokenDisabledInjector);
        MediaProjectionManagerService.MediaProjection projection = startProjectionPreconditions(
                service);
        // No starts yet, and not timed out yet - so still valid.
        assertThat(projection.isValid()).isTrue();

        // Only one start - so still valid.
        projection.start(mIMediaProjectionCallback);
        assertThat(projection.isValid()).isTrue();

        // Second start - technically allowed to start again, without stopping in between.
        // Token should no longer be valid.
        projection.start(mIMediaProjectionCallback);
        assertThat(projection.isValid()).isFalse();
    }

    @Test
    public void testIsValid_restart() throws NameNotFoundException {
        MediaProjectionManagerService service = new MediaProjectionManagerService(mContext,
                mPreventReusedTokenDisabledInjector);
        MediaProjectionManagerService.MediaProjection projection = startProjectionPreconditions(
                service);
        // No starts yet, and not timed out yet - so still valid.
        assertThat(projection.isValid()).isTrue();

        // Only one start - so still valid.
        projection.start(mIMediaProjectionCallback);
        assertThat(projection.isValid()).isTrue();

        projection.stop();

        // Second start - so not valid.
        projection.start(mIMediaProjectionCallback);
        assertThat(projection.isValid()).isFalse();
    }

    @Test
    public void testIsValid_timeout() throws NameNotFoundException {
        final MediaProjectionManagerService.Injector mClockInjector =
                new MediaProjectionManagerService.Injector() {
                    @Override
                    MediaProjectionManagerService.Clock createClock() {
                        // Always return the same value for elapsed time.
                        return () -> mClock.now();
                    }
                    @Override
                    boolean shouldMediaProjectionPreventReusingConsent(
                            MediaProjectionManagerService.MediaProjection projection) {
                        return false;
                    }
                };
        final MediaProjectionManagerService service = new MediaProjectionManagerService(mContext,
                mClockInjector);
        MediaProjectionManagerService.MediaProjection projection = createProjectionPreconditions(
                service);
        mClock.fastForward(projection.mDefaultTimeoutMs + 10);

        // Immediate timeout - so no longer valid.
        assertThat(projection.isValid()).isFalse();
    }

    @Test
    public void testIsValid_virtualDisplayAlreadyCreated() throws NameNotFoundException {
        MediaProjectionManagerService service = new MediaProjectionManagerService(mContext,
                mPreventReusedTokenDisabledInjector);
        MediaProjectionManagerService.MediaProjection projection = startProjectionPreconditions(
                service);
        // Simulate MediaProjection#createVirtualDisplay being invoked previously.
        projection.notifyVirtualDisplayCreated(10);

        // Trying to re-use token on another MediaProjection#createVirtualDisplay - no longer valid.
        assertThat(projection.isValid()).isFalse();
    }

    // TODO(269273190): Test flag using compat annotations instead.
    @Test
    public void testIsValid_invalid_preventionEnabled()
            throws NameNotFoundException {
        MediaProjectionManagerService service = new MediaProjectionManagerService(mContext,
                mPreventReusedTokenEnabledInjector);
        MediaProjectionManagerService.MediaProjection projection = startProjectionPreconditions(
                service);
        projection.start(mIMediaProjectionCallback);
        projection.stop();
        // Second start - so not valid.
        projection.start(mIMediaProjectionCallback);

        assertThrows(IllegalStateException.class, projection::isValid);
    }

    // TODO(269273190): Test flag using compat annotations instead.
    @Test
    public void testIsValid_invalid_preventionDisabled()
            throws NameNotFoundException {
        MediaProjectionManagerService service = new MediaProjectionManagerService(mContext,
                mPreventReusedTokenDisabledInjector);
        MediaProjectionManagerService.MediaProjection projection = startProjectionPreconditions(
                service);
        projection.start(mIMediaProjectionCallback);
        projection.stop();

        // Second start - so not valid.
        projection.start(mIMediaProjectionCallback);

        assertThat(projection.isValid()).isFalse();
    }

    @Test
    public void testIsCurrentProjectionInternal_invalid() throws NameNotFoundException {
        IBinder iBinder = mock(IBinder.class);
        MediaProjectionManagerService.MediaProjection projection = startProjectionPreconditions();

        // Create a projection, current is false.
        assertThat(projection).isNotNull();
        assertThat(mService.isCurrentProjection(iBinder)).isFalse();

        // Start the projection, and test a random token.
        projection.start(mIMediaProjectionCallback);
        assertThat(mService.isCurrentProjection(iBinder)).isFalse();
    }

    @Test
    public void testIsCurrentProjectionInternal_noProjection() {
        IBinder iBinder = mock(IBinder.class);
        assertThat(mService.isCurrentProjection(iBinder)).isFalse();
    }

    @Test
    public void testIsCurrentProjectionInternal_currentProjection()
            throws NameNotFoundException {
        MediaProjectionManagerService.MediaProjection projection = startProjectionPreconditions();

        // Create a projection, current is false.
        assertThat(projection).isNotNull();
        assertThat(mService.isCurrentProjection(projection.asBinder())).isFalse();

        // Start the projection, is current is now true.
        projection.start(mIMediaProjectionCallback);
        assertThat(mService.isCurrentProjection(projection.asBinder())).isTrue();
    }

    // Set up preconditions for creating a projection.
    private MediaProjectionManagerService.MediaProjection createProjectionPreconditions(
            MediaProjectionManagerService service)
            throws NameNotFoundException {
        doReturn(mAppInfo).when(mPackageManager).getApplicationInfoAsUser(anyString(),
                any(ApplicationInfoFlags.class), any(UserHandle.class));
        return service.createProjectionInternal(UID, PACKAGE_NAME,
                TYPE_MIRRORING, /* isPermanentGrant= */ true, UserHandle.CURRENT,
                /* packageAttemptedReusingGrantedConsent= */ false);
    }

    // Set up preconditions for creating a projection.
    private MediaProjectionManagerService.MediaProjection createProjectionPreconditions()
            throws NameNotFoundException {
        return createProjectionPreconditions(mService);
    }

    // Set up preconditions for starting a projection, with no foreground service requirements.
    private MediaProjectionManagerService.MediaProjection startProjectionPreconditions(
            MediaProjectionManagerService service)
            throws NameNotFoundException {
        mAppInfo.privateFlags |= PRIVATE_FLAG_PRIVILEGED;
        return createProjectionPreconditions(service);
    }

    // Set up preconditions for starting a projection, specifying if it is possible to reuse the
    // the current projection.
    private MediaProjectionManagerService.MediaProjection startProjectionPreconditions(
            boolean packageAttemptedReusingGrantedConsent)
            throws NameNotFoundException {
        mAppInfo.privateFlags |= PRIVATE_FLAG_PRIVILEGED;
        doReturn(mAppInfo).when(mPackageManager).getApplicationInfoAsUser(anyString(),
                any(ApplicationInfoFlags.class), any(UserHandle.class));
        return mService.createProjectionInternal(UID, PACKAGE_NAME,
                TYPE_MIRRORING, /* isPermanentGrant= */ true, UserHandle.CURRENT,
                packageAttemptedReusingGrantedConsent);
    }

    // Set up preconditions for starting a projection, with no foreground service requirements.
    private MediaProjectionManagerService.MediaProjection startProjectionPreconditions()
            throws NameNotFoundException {
        mAppInfo.privateFlags |= PRIVATE_FLAG_PRIVILEGED;
        return createProjectionPreconditions(mService);
    }

    private static class FakeIMediaProjectionCallback extends IMediaProjectionCallback.Stub {
        @Override
        public void onStop() throws RemoteException {
        }

        @Override
        public void onCapturedContentResize(int width, int height) throws RemoteException {
        }

        @Override
        public void onCapturedContentVisibilityChanged(boolean isVisible) throws RemoteException {
        }
    }
}
