/*
 * Copyright (C) 2024 The Android Open Source Project
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


import static android.Manifest.permission.RECORD_SENSITIVE_CONTENT;
import static android.provider.Settings.Global.DISABLE_SCREEN_SHARE_PROTECTIONS_FOR_APPS_AND_NOTIFICATIONS;
import static android.view.Display.INVALID_DISPLAY;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.app.role.RoleManager;
import android.companion.AssociationRequest;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.ApplicationInfoFlags;
import android.content.pm.PackageManager.NameNotFoundException;
import android.media.projection.MediaProjectionManager;
import android.os.UserHandle;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.testing.TestableContext;
import android.util.ArraySet;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.LocalServices;
import com.android.server.SystemConfig;
import com.android.server.wm.WindowManagerInternal;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Tests for the {@link MediaProjectionStopController} class.
 * <p>
 * Build/Install/Run:
 * atest FrameworksServicesTests:MediaProjectionStopControllerTest
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
@SuppressLint({"UseCheckPermission", "VisibleForTests", "MissingPermission"})
public class MediaProjectionStopControllerTest {
    private static final int UID = 10;
    private static final String PACKAGE_NAME = "test.package";
    private final ApplicationInfo mAppInfo = new ApplicationInfo();
    @Rule
    public final TestableContext mContext = spy(
            new TestableContext(InstrumentationRegistry.getInstrumentation().getContext()));

    private final MediaProjectionManagerService.Injector mMediaProjectionMetricsLoggerInjector =
            new MediaProjectionManagerService.Injector() {
                @Override
                MediaProjectionMetricsLogger mediaProjectionMetricsLogger(Context context) {
                    return mMediaProjectionMetricsLogger;
                }
            };

    private MediaProjectionManagerService mService;

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Mock
    private ActivityManagerInternal mAmInternal;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private KeyguardManager mKeyguardManager;
    @Mock
    private TelecomManager mTelecomManager;

    private AppOpsManager mAppOpsManager;
    @Mock
    private MediaProjectionMetricsLogger mMediaProjectionMetricsLogger;
    @Mock
    private Consumer<Integer> mStopConsumer;

    private MediaProjectionStopController mStopController;

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);

        LocalServices.removeServiceForTest(ActivityManagerInternal.class);
        LocalServices.addService(ActivityManagerInternal.class, mAmInternal);

        mAppOpsManager = mockAppOpsManager();
        mContext.addMockSystemService(AppOpsManager.class, mAppOpsManager);
        mContext.addMockSystemService(KeyguardManager.class, mKeyguardManager);
        mContext.addMockSystemService(TelecomManager.class, mTelecomManager);
        mContext.setMockPackageManager(mPackageManager);

        mStopController = new MediaProjectionStopController(mContext, mStopConsumer);
        mService = new MediaProjectionManagerService(mContext,
                mMediaProjectionMetricsLoggerInjector);

        mAppInfo.targetSdkVersion = 35;
    }

    private static AppOpsManager mockAppOpsManager() {
        return mock(AppOpsManager.class, invocationOnMock -> {
            if (invocationOnMock.getMethod().getName().startsWith("noteOp")) {
                // Mockito will return 0 for non-stubbed method which corresponds to MODE_ALLOWED
                // and is not what we want.
                return AppOpsManager.MODE_IGNORED;
            }
            return Answers.RETURNS_DEFAULTS.answer(invocationOnMock);
        });
    }

    @After
    public void tearDown() {
        LocalServices.removeServiceForTest(ActivityManagerInternal.class);
        LocalServices.removeServiceForTest(WindowManagerInternal.class);
    }

    @Test
    @EnableFlags(
            android.companion.virtualdevice.flags.Flags.FLAG_MEDIA_PROJECTION_KEYGUARD_RESTRICTIONS)
    public void testMediaProjectionNotRestricted() throws Exception {
        when(mKeyguardManager.isKeyguardLocked()).thenReturn(false);

        assertThat(mStopController.isStartForbidden(
                createMediaProjection(PACKAGE_NAME))).isFalse();
    }

    @Test
    @EnableFlags(
            android.companion.virtualdevice.flags.Flags.FLAG_MEDIA_PROJECTION_KEYGUARD_RESTRICTIONS)
    public void testMediaProjectionRestricted() throws Exception {
        MediaProjectionManagerService.MediaProjection mediaProjection = createMediaProjection();
        mediaProjection.notifyVirtualDisplayCreated(1);
        doReturn(PackageManager.PERMISSION_DENIED).when(mPackageManager).checkPermission(
                RECORD_SENSITIVE_CONTENT, mediaProjection.packageName);
        when(mKeyguardManager.isKeyguardLocked()).thenReturn(true);

        assertThat(mStopController.isStartForbidden(mediaProjection)).isTrue();
    }

    @Test
    public void testExemptFromStoppingNullProjection() throws Exception {
        assertThat(mStopController.isExemptFromStopping(null,
                MediaProjectionStopController.STOP_REASON_UNKNOWN)).isTrue();
    }

    @Test
    public void testExemptFromStoppingInvalidProjection() throws Exception {
        assertThat(mStopController.isExemptFromStopping(createMediaProjection(null),
                MediaProjectionStopController.STOP_REASON_UNKNOWN)).isTrue();
    }

    @Test
    public void testExemptFromStoppingDisableScreenshareProtections() throws Exception {
        MediaProjectionManagerService.MediaProjection mediaProjection = createMediaProjection();
        doReturn(PackageManager.PERMISSION_DENIED).when(mPackageManager).checkPermission(
                RECORD_SENSITIVE_CONTENT, mediaProjection.packageName);
        int value = Settings.Global.getInt(mContext.getContentResolver(),
                DISABLE_SCREEN_SHARE_PROTECTIONS_FOR_APPS_AND_NOTIFICATIONS, 0);
        try {
            Settings.Global.putInt(mContext.getContentResolver(),
                    DISABLE_SCREEN_SHARE_PROTECTIONS_FOR_APPS_AND_NOTIFICATIONS, 1);

            assertThat(mStopController.isExemptFromStopping(mediaProjection,
                    MediaProjectionStopController.STOP_REASON_UNKNOWN)).isTrue();
        } finally {
            Settings.Global.putInt(mContext.getContentResolver(),
                    DISABLE_SCREEN_SHARE_PROTECTIONS_FOR_APPS_AND_NOTIFICATIONS, value);
        }
    }

    @Test
    public void testExemptFromStoppingHasOpProjectMedia() throws Exception {
        MediaProjectionManagerService.MediaProjection mediaProjection = createMediaProjection();
        doReturn(PackageManager.PERMISSION_DENIED).when(mPackageManager).checkPermission(
                RECORD_SENSITIVE_CONTENT, mediaProjection.packageName);
        doReturn(AppOpsManager.MODE_ALLOWED).when(mAppOpsManager)
                .noteOpNoThrow(eq(AppOpsManager.OP_PROJECT_MEDIA),
                        eq(mediaProjection.uid), eq(mediaProjection.packageName),
                        nullable(String.class),
                        nullable(String.class));
        assertThat(mStopController.isExemptFromStopping(mediaProjection,
                MediaProjectionStopController.STOP_REASON_UNKNOWN)).isTrue();
    }

    @Test
    public void testExemptFromStoppingHasAppStreamingRole() throws Exception {
        runWithRole(
                AssociationRequest.DEVICE_PROFILE_APP_STREAMING,
                () -> {
                    try {
                        MediaProjectionManagerService.MediaProjection mediaProjection =
                                createMediaProjection();
                        doReturn(PackageManager.PERMISSION_DENIED).when(
                                mPackageManager).checkPermission(
                                RECORD_SENSITIVE_CONTENT, mediaProjection.packageName);
                        assertThat(mStopController.isExemptFromStopping(mediaProjection,
                                MediaProjectionStopController.STOP_REASON_UNKNOWN)).isTrue();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    @Test
    public void testExemptFromStoppingIsBugreportAllowlisted() throws Exception {
        ArraySet<String> packages = SystemConfig.getInstance().getBugreportWhitelistedPackages();
        if (packages.isEmpty()) {
            return;
        }
        MediaProjectionManagerService.MediaProjection mediaProjection = createMediaProjection(
                packages.valueAt(0));
        doReturn(PackageManager.PERMISSION_DENIED).when(mPackageManager).checkPermission(
                RECORD_SENSITIVE_CONTENT, mediaProjection.packageName);
        assertThat(mStopController.isExemptFromStopping(mediaProjection,
                MediaProjectionStopController.STOP_REASON_UNKNOWN)).isTrue();
    }

    @Test
    public void testExemptFromStoppingHasNoDisplay() throws Exception {
        MediaProjectionManagerService.MediaProjection mediaProjection = createMediaProjection(
                PACKAGE_NAME);
        doReturn(PackageManager.PERMISSION_DENIED).when(mPackageManager).checkPermission(
                RECORD_SENSITIVE_CONTENT, mediaProjection.packageName);
        assertThat(mStopController.isExemptFromStopping(mediaProjection,
                MediaProjectionStopController.STOP_REASON_UNKNOWN)).isTrue();
    }

    @Test
    public void testExemptFromStoppingHasRecordSensitiveContentPermission() throws Exception {
        MediaProjectionManagerService.MediaProjection mediaProjection = createMediaProjection();
        doReturn(PackageManager.PERMISSION_GRANTED).when(mPackageManager).checkPermission(
                RECORD_SENSITIVE_CONTENT, mediaProjection.packageName);
        assertThat(mStopController.isExemptFromStopping(mediaProjection,
                MediaProjectionStopController.STOP_REASON_UNKNOWN)).isTrue();
    }

    @Test
    public void testExemptFromStoppingIsFalse() throws Exception {
        MediaProjectionManagerService.MediaProjection mediaProjection = createMediaProjection();
        mediaProjection.notifyVirtualDisplayCreated(1);
        doReturn(PackageManager.PERMISSION_DENIED).when(mPackageManager).checkPermission(
                RECORD_SENSITIVE_CONTENT, mediaProjection.packageName);
        assertThat(mStopController.isExemptFromStopping(mediaProjection,
                MediaProjectionStopController.STOP_REASON_UNKNOWN)).isFalse();
    }

    @Test
    @EnableFlags(
            android.companion.virtualdevice.flags.Flags.FLAG_MEDIA_PROJECTION_KEYGUARD_RESTRICTIONS)
    public void testKeyguardLockedStateChanged_unlocked() {
        mStopController.onKeyguardLockedStateChanged(false);

        verify(mStopConsumer, never()).accept(anyInt());
    }

    @Test
    @EnableFlags(
            android.companion.virtualdevice.flags.Flags.FLAG_MEDIA_PROJECTION_KEYGUARD_RESTRICTIONS)
    public void testKeyguardLockedStateChanged_locked() {
        mStopController.onKeyguardLockedStateChanged(true);

        verify(mStopConsumer).accept(MediaProjectionStopController.STOP_REASON_KEYGUARD);
    }

    @Test
    @EnableFlags(com.android.media.projection.flags.Flags.FLAG_STOP_MEDIA_PROJECTION_ON_CALL_END)
    public void testCallStateChanged_callStarts() {
        // Setup call state to false
        when(mTelecomManager.isInCall()).thenReturn(false);
        mStopController.callStateChanged();

        clearInvocations(mStopConsumer);

        when(mTelecomManager.isInCall()).thenReturn(true);
        mStopController.callStateChanged();

        verify(mStopConsumer, never()).accept(anyInt());
    }

    @Test
    @EnableFlags(com.android.media.projection.flags.Flags.FLAG_STOP_MEDIA_PROJECTION_ON_CALL_END)
    public void testCallStateChanged_remainsInCall() {
        // Setup call state to false
        when(mTelecomManager.isInCall()).thenReturn(true);
        mStopController.callStateChanged();

        clearInvocations(mStopConsumer);

        when(mTelecomManager.isInCall()).thenReturn(true);
        mStopController.callStateChanged();

        verify(mStopConsumer, never()).accept(anyInt());
    }

    @Test
    @EnableFlags(com.android.media.projection.flags.Flags.FLAG_STOP_MEDIA_PROJECTION_ON_CALL_END)
    public void testCallStateChanged_remainsNoCall() {
        // Setup call state to false
        when(mTelecomManager.isInCall()).thenReturn(false);
        mStopController.callStateChanged();

        clearInvocations(mStopConsumer);

        when(mTelecomManager.isInCall()).thenReturn(false);
        mStopController.callStateChanged();

        verify(mStopConsumer, never()).accept(anyInt());
    }

    @Test
    @EnableFlags(com.android.media.projection.flags.Flags.FLAG_STOP_MEDIA_PROJECTION_ON_CALL_END)
    public void testCallStateChanged_callEnds() {
        // Setup call state to false
        when(mTelecomManager.isInCall()).thenReturn(true);
        mStopController.callStateChanged();

        clearInvocations(mStopConsumer);

        when(mTelecomManager.isInCall()).thenReturn(false);
        mStopController.callStateChanged();

        verify(mStopConsumer).accept(MediaProjectionStopController.STOP_REASON_CALL_END);
    }

    @Test
    @EnableFlags(com.android.media.projection.flags.Flags.FLAG_STOP_MEDIA_PROJECTION_ON_CALL_END)
    public void testExemptFromStopping_callEnd_callBeforeMediaProjection() throws Exception {
        when(mTelecomManager.isInCall()).thenReturn(true);
        mStopController.callStateChanged();

        MediaProjectionManagerService.MediaProjection mediaProjection = createMediaProjection();
        mediaProjection.notifyVirtualDisplayCreated(1);
        doReturn(PackageManager.PERMISSION_DENIED).when(mPackageManager).checkPermission(
                RECORD_SENSITIVE_CONTENT, mediaProjection.packageName);

        assertThat(mStopController.isExemptFromStopping(mediaProjection,
                MediaProjectionStopController.STOP_REASON_CALL_END)).isFalse();
    }

    @Test
    @EnableFlags(com.android.media.projection.flags.Flags.FLAG_STOP_MEDIA_PROJECTION_ON_CALL_END)
    public void testExemptFromStopping_callEnd_callAfterMediaProjection() throws Exception {
        MediaProjectionManagerService.MediaProjection mediaProjection = createMediaProjection();
        mediaProjection.notifyVirtualDisplayCreated(1);
        doReturn(PackageManager.PERMISSION_DENIED).when(mPackageManager).checkPermission(
                RECORD_SENSITIVE_CONTENT, mediaProjection.packageName);

        when(mTelecomManager.isInCall()).thenReturn(true);
        mStopController.callStateChanged();

        assertThat(mStopController.isExemptFromStopping(mediaProjection,
                MediaProjectionStopController.STOP_REASON_CALL_END)).isTrue();
    }

    private MediaProjectionManagerService.MediaProjection createMediaProjection()
            throws NameNotFoundException {
        return createMediaProjection(PACKAGE_NAME);
    }

    private MediaProjectionManagerService.MediaProjection createMediaProjection(String packageName)
            throws NameNotFoundException {
        doReturn(mAppInfo).when(mPackageManager).getApplicationInfoAsUser(anyString(),
                any(ApplicationInfoFlags.class), any(UserHandle.class));
        doReturn(mAppInfo).when(mPackageManager).getApplicationInfoAsUser(Mockito.isNull(),
                any(ApplicationInfoFlags.class), any(UserHandle.class));
        return mService.createProjectionInternal(UID, packageName,
                MediaProjectionManager.TYPE_SCREEN_CAPTURE, false, mContext.getUser(),
                INVALID_DISPLAY);
    }

    /**
     * Run the provided block giving the current context's package the provided role.
     */
    @SuppressWarnings("SameParameterValue")
    private void runWithRole(String role, Runnable block) {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        String packageName = mContext.getPackageName();
        UserHandle user = instrumentation.getTargetContext().getUser();
        RoleManager roleManager = Objects.requireNonNull(
                mContext.getSystemService(RoleManager.class));
        try {
            CountDownLatch latch = new CountDownLatch(1);
            instrumentation.getUiAutomation().adoptShellPermissionIdentity(
                    Manifest.permission.MANAGE_ROLE_HOLDERS,
                    Manifest.permission.BYPASS_ROLE_QUALIFICATION);

            roleManager.setBypassingRoleQualification(true);
            roleManager.addRoleHolderAsUser(role, packageName,
                    /* flags= */ RoleManager.MANAGE_HOLDERS_FLAG_DONT_KILL_APP, user,
                    mContext.getMainExecutor(), success -> {
                        if (success) {
                            latch.countDown();
                        } else {
                            Assert.fail("Couldn't set role for test (failure) " + role);
                        }
                    });
            assertWithMessage("Couldn't set role for test (timeout) : " + role)
                    .that(latch.await(1, TimeUnit.SECONDS)).isTrue();
            block.run();

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            roleManager.removeRoleHolderAsUser(role, packageName,
                    /* flags= */ RoleManager.MANAGE_HOLDERS_FLAG_DONT_KILL_APP, user,
                    mContext.getMainExecutor(), (aBool) -> {
                    });
            roleManager.setBypassingRoleQualification(false);
            instrumentation.getUiAutomation()
                    .dropShellPermissionIdentity();
        }
    }
}
