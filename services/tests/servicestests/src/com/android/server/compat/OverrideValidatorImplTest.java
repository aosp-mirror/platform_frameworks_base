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

package com.android.server.compat;

import static com.android.internal.compat.OverrideAllowedState.ALLOWED;
import static com.android.internal.compat.OverrideAllowedState.DISABLED_NON_TARGET_SDK;
import static com.android.internal.compat.OverrideAllowedState.DISABLED_NOT_DEBUGGABLE;
import static com.android.internal.compat.OverrideAllowedState.DISABLED_TARGET_SDK_TOO_HIGH;
import static com.android.internal.compat.OverrideAllowedState.LOGGING_ONLY_CHANGE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.PackageManager;

import androidx.test.runner.AndroidJUnit4;

import com.android.internal.compat.AndroidBuildClassifier;
import com.android.internal.compat.IOverrideValidator;
import com.android.internal.compat.OverrideAllowedState;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
public class OverrideValidatorImplTest {
    private static final String PACKAGE_NAME = "my.package";
    private static final int TARGET_SDK = 10;
    private static final int TARGET_SDK_BEFORE = 9;
    private static final int TARGET_SDK_AFTER = 11;

    @Mock
    private PackageManager mPackageManager;
    @Mock
    Context mContext;

    private AndroidBuildClassifier debuggableBuild() {
        AndroidBuildClassifier buildClassifier = mock(AndroidBuildClassifier.class);
        when(buildClassifier.isDebuggableBuild()).thenReturn(true);
        return buildClassifier;
    }

    private AndroidBuildClassifier betaBuild() {
        AndroidBuildClassifier buildClassifier = mock(AndroidBuildClassifier.class);
        when(buildClassifier.isDebuggableBuild()).thenReturn(false);
        when(buildClassifier.isFinalBuild()).thenReturn(false);
        return buildClassifier;
    }

    private AndroidBuildClassifier finalBuild() {
        AndroidBuildClassifier buildClassifier = mock(AndroidBuildClassifier.class);
        when(buildClassifier.isDebuggableBuild()).thenReturn(false);
        when(buildClassifier.isFinalBuild()).thenReturn(true);
        return buildClassifier;
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        android.app.compat.ChangeIdStateCache.disable();
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
    }

    @Test
    public void getOverrideAllowedState_debugBuildAnyChangeDebugApp_allowOverride()
            throws Exception {
        CompatConfig config = CompatConfigBuilder.create(debuggableBuild(), mContext)
                    .addTargetSdkChangeWithId(TARGET_SDK_BEFORE, 1)
                    .addTargetSdkChangeWithId(TARGET_SDK, 2)
                    .addTargetSdkChangeWithId(TARGET_SDK_AFTER, 3)
                    .addEnabledChangeWithId(4)
                    .addDisabledChangeWithId(5)
                    .addLoggingOnlyChangeWithId(6).build();
        IOverrideValidator overrideValidator = config.getOverrideValidator();
        when(mPackageManager.getApplicationInfo(eq(PACKAGE_NAME), anyInt()))
                .thenReturn(ApplicationInfoBuilder.create()
                        .debuggable()
                        .withTargetSdk(TARGET_SDK)
                        .withPackageName(PACKAGE_NAME).build());

        OverrideAllowedState stateTargetSdkLessChange =
                overrideValidator.getOverrideAllowedState(1, PACKAGE_NAME);
        OverrideAllowedState stateTargetSdkEqualChange =
                overrideValidator.getOverrideAllowedState(2, PACKAGE_NAME);
        OverrideAllowedState stateTargetSdkAfterChange =
                overrideValidator.getOverrideAllowedState(3, PACKAGE_NAME);
        OverrideAllowedState stateEnabledChange =
                overrideValidator.getOverrideAllowedState(4, PACKAGE_NAME);
        OverrideAllowedState stateDisabledChange =
                overrideValidator.getOverrideAllowedState(5, PACKAGE_NAME);
        OverrideAllowedState stateDLoggingOnlyChange =
                overrideValidator.getOverrideAllowedState(6, PACKAGE_NAME);

        assertThat(stateTargetSdkLessChange)
                .isEqualTo(new OverrideAllowedState(ALLOWED, -1, -1));
        assertThat(stateTargetSdkEqualChange)
                .isEqualTo(new OverrideAllowedState(ALLOWED, -1, -1));
        assertThat(stateTargetSdkAfterChange)
                .isEqualTo(new OverrideAllowedState(ALLOWED, -1, -1));
        assertThat(stateEnabledChange)
                .isEqualTo(new OverrideAllowedState(ALLOWED, -1, -1));
        assertThat(stateDisabledChange)
                .isEqualTo(new OverrideAllowedState(ALLOWED, -1, -1));
        assertThat(stateDLoggingOnlyChange)
                .isEqualTo(new OverrideAllowedState(LOGGING_ONLY_CHANGE, -1, -1));
    }

    @Test
    public void getOverrideAllowedState_debugBuildAnyChangeReleaseApp_allowOverride()
            throws Exception {
        CompatConfig config = CompatConfigBuilder.create(debuggableBuild(), mContext)
                    .addTargetSdkChangeWithId(TARGET_SDK_BEFORE, 1)
                    .addTargetSdkChangeWithId(TARGET_SDK, 2)
                    .addTargetSdkChangeWithId(TARGET_SDK_AFTER, 3)
                    .addEnabledChangeWithId(4)
                    .addDisabledChangeWithId(5)
                    .addLoggingOnlyChangeWithId(6).build();
        IOverrideValidator overrideValidator = config.getOverrideValidator();
        when(mPackageManager.getApplicationInfo(eq(PACKAGE_NAME), anyInt()))
                .thenReturn(ApplicationInfoBuilder.create()
                        .withPackageName(PACKAGE_NAME)
                        .withTargetSdk(TARGET_SDK).build());

        OverrideAllowedState stateTargetSdkLessChange =
                overrideValidator.getOverrideAllowedState(1, PACKAGE_NAME);
        OverrideAllowedState stateTargetSdkEqualChange =
                overrideValidator.getOverrideAllowedState(2, PACKAGE_NAME);
        OverrideAllowedState stateTargetSdkAfterChange =
                overrideValidator.getOverrideAllowedState(3, PACKAGE_NAME);
        OverrideAllowedState stateEnabledChange =
                overrideValidator.getOverrideAllowedState(4, PACKAGE_NAME);
        OverrideAllowedState stateDisabledChange =
                overrideValidator.getOverrideAllowedState(5, PACKAGE_NAME);
        OverrideAllowedState stateDLoggingOnlyChange =
                overrideValidator.getOverrideAllowedState(6, PACKAGE_NAME);

        assertThat(stateTargetSdkLessChange)
                .isEqualTo(new OverrideAllowedState(ALLOWED, -1, -1));
        assertThat(stateTargetSdkEqualChange)
                .isEqualTo(new OverrideAllowedState(ALLOWED, -1, -1));
        assertThat(stateTargetSdkAfterChange)
                .isEqualTo(new OverrideAllowedState(ALLOWED, -1, -1));
        assertThat(stateEnabledChange)
                .isEqualTo(new OverrideAllowedState(ALLOWED, -1, -1));
        assertThat(stateDisabledChange)
                .isEqualTo(new OverrideAllowedState(ALLOWED, -1, -1));
        assertThat(stateDLoggingOnlyChange)
                .isEqualTo(new OverrideAllowedState(LOGGING_ONLY_CHANGE, -1, -1));
    }

    @Test
    public void getOverrideAllowedState_betaBuildTargetSdkChangeDebugApp_allowOverride()
            throws Exception {
        CompatConfig config = CompatConfigBuilder.create(betaBuild(), mContext)
                        .addTargetSdkChangeWithId(TARGET_SDK_BEFORE, 1)
                        .addTargetSdkChangeWithId(TARGET_SDK, 2)
                        .addTargetSdkChangeWithId(TARGET_SDK_AFTER, 3)
                        .addDisabledChangeWithId(4).build();
        IOverrideValidator overrideValidator = config.getOverrideValidator();
        when(mPackageManager.getApplicationInfo(eq(PACKAGE_NAME), anyInt()))
                .thenReturn(ApplicationInfoBuilder.create()
                        .debuggable()
                        .withTargetSdk(TARGET_SDK)
                        .withPackageName(PACKAGE_NAME).build());

        OverrideAllowedState stateTargetSdkLessChange =
                overrideValidator.getOverrideAllowedState(1, PACKAGE_NAME);
        OverrideAllowedState stateTargetSdkEqualChange =
                overrideValidator.getOverrideAllowedState(2, PACKAGE_NAME);
        OverrideAllowedState stateTargetSdkAfterChange =
                overrideValidator.getOverrideAllowedState(3, PACKAGE_NAME);
        OverrideAllowedState stateDisabledChange =
                overrideValidator.getOverrideAllowedState(4, PACKAGE_NAME);

        assertThat(stateTargetSdkLessChange)
                .isEqualTo(new OverrideAllowedState(ALLOWED, TARGET_SDK, TARGET_SDK_BEFORE));
        assertThat(stateTargetSdkEqualChange)
                .isEqualTo(new OverrideAllowedState(ALLOWED, TARGET_SDK, TARGET_SDK));
        assertThat(stateTargetSdkAfterChange)
                .isEqualTo(new OverrideAllowedState(ALLOWED, TARGET_SDK, TARGET_SDK_AFTER));
        assertThat(stateDisabledChange)
                .isEqualTo(new OverrideAllowedState(ALLOWED, TARGET_SDK, -1));
    }

    @Test
    public void getOverrideAllowedState_betaBuildEnabledChangeDebugApp_allowOverride()
            throws Exception {
        CompatConfig config = CompatConfigBuilder.create(betaBuild(), mContext)
                        .addEnabledChangeWithId(1).build();
        IOverrideValidator overrideValidator = config.getOverrideValidator();
        when(mPackageManager.getApplicationInfo(eq(PACKAGE_NAME), anyInt()))
                .thenReturn(ApplicationInfoBuilder.create()
                        .withPackageName(PACKAGE_NAME)
                        .debuggable()
                        .build());

        OverrideAllowedState allowedState =
                overrideValidator.getOverrideAllowedState(1, PACKAGE_NAME);

        assertThat(allowedState)
                .isEqualTo(new OverrideAllowedState(ALLOWED, -1, -1));
    }

    @Test
    public void getOverrideAllowedState_betaBuildDisabledChangeDebugApp_allowOverride()
            throws Exception {
        CompatConfig config = CompatConfigBuilder.create(betaBuild(), mContext)
                        .addDisabledChangeWithId(1).build();
        IOverrideValidator overrideValidator = config.getOverrideValidator();
        when(mPackageManager.getApplicationInfo(eq(PACKAGE_NAME), anyInt()))
                .thenReturn(ApplicationInfoBuilder.create()
                        .debuggable()
                        .withPackageName(PACKAGE_NAME).build());

        OverrideAllowedState allowedState =
                overrideValidator.getOverrideAllowedState(1, PACKAGE_NAME);

        assertThat(allowedState)
                .isEqualTo(new OverrideAllowedState(ALLOWED, -1, -1));
    }

    @Test
    public void getOverrideAllowedState_betaBuildAnyChangeReleaseApp_rejectOverride()
            throws Exception {
        CompatConfig config = CompatConfigBuilder.create(betaBuild(), mContext)
                        .addTargetSdkChangeWithId(TARGET_SDK_BEFORE, 1)
                        .addTargetSdkChangeWithId(TARGET_SDK, 2)
                        .addTargetSdkChangeWithId(TARGET_SDK_AFTER, 3)
                        .addEnabledChangeWithId(4)
                        .addDisabledChangeWithId(5)
                        .addLoggingOnlyChangeWithId(6).build();
        IOverrideValidator overrideValidator = config.getOverrideValidator();
        when(mPackageManager.getApplicationInfo(eq(PACKAGE_NAME), anyInt()))
                .thenReturn(ApplicationInfoBuilder.create()
                        .withPackageName(PACKAGE_NAME)
                        .withTargetSdk(TARGET_SDK).build());

        OverrideAllowedState stateTargetSdkLessChange =
                overrideValidator.getOverrideAllowedState(1, PACKAGE_NAME);
        OverrideAllowedState stateTargetSdkEqualChange =
                overrideValidator.getOverrideAllowedState(2, PACKAGE_NAME);
        OverrideAllowedState stateTargetSdkAfterChange =
                overrideValidator.getOverrideAllowedState(3, PACKAGE_NAME);
        OverrideAllowedState stateEnabledChange =
                overrideValidator.getOverrideAllowedState(4, PACKAGE_NAME);
        OverrideAllowedState stateDisabledChange =
                overrideValidator.getOverrideAllowedState(5, PACKAGE_NAME);
        OverrideAllowedState stateDLoggingOnlyChange =
                overrideValidator.getOverrideAllowedState(6, PACKAGE_NAME);

        assertThat(stateTargetSdkLessChange)
                .isEqualTo(new OverrideAllowedState(DISABLED_NOT_DEBUGGABLE, -1, -1));
        assertThat(stateTargetSdkEqualChange)
                .isEqualTo(new OverrideAllowedState(DISABLED_NOT_DEBUGGABLE, -1, -1));
        assertThat(stateTargetSdkAfterChange)
                .isEqualTo(new OverrideAllowedState(DISABLED_NOT_DEBUGGABLE, -1, -1));
        assertThat(stateEnabledChange)
                .isEqualTo(new OverrideAllowedState(DISABLED_NOT_DEBUGGABLE, -1, -1));
        assertThat(stateDisabledChange)
                .isEqualTo(new OverrideAllowedState(DISABLED_NOT_DEBUGGABLE, -1, -1));
        assertThat(stateDLoggingOnlyChange)
                .isEqualTo(new OverrideAllowedState(LOGGING_ONLY_CHANGE, -1, -1));
    }

    @Test
    public void getOverrideAllowedState_finalBuildTargetSdkChangeDebugAppOptin_allowOverride()
            throws Exception {
        CompatConfig config = CompatConfigBuilder.create(finalBuild(), mContext)
                        .addTargetSdkChangeWithId(TARGET_SDK_AFTER, 1).build();
        IOverrideValidator overrideValidator = config.getOverrideValidator();
        when(mPackageManager.getApplicationInfo(eq(PACKAGE_NAME), anyInt()))
                .thenReturn(ApplicationInfoBuilder.create()
                        .debuggable()
                        .withTargetSdk(TARGET_SDK)
                        .withPackageName(PACKAGE_NAME).build());

        OverrideAllowedState allowedState =
                overrideValidator.getOverrideAllowedState(1, PACKAGE_NAME);

        assertThat(allowedState)
                .isEqualTo(new OverrideAllowedState(ALLOWED, TARGET_SDK, TARGET_SDK_AFTER));
    }

    @Test
    public void getOverrideAllowedState_finalBuildTargetSdkChangeDebugAppOptout_rejectOverride()
            throws Exception {
        CompatConfig config = CompatConfigBuilder.create(finalBuild(), mContext)
                        .addTargetSdkChangeWithId(TARGET_SDK_BEFORE, 1)
                        .addTargetSdkChangeWithId(TARGET_SDK, 2).build();
        IOverrideValidator overrideValidator = config.getOverrideValidator();
        when(mPackageManager.getApplicationInfo(eq(PACKAGE_NAME), anyInt()))
                .thenReturn(ApplicationInfoBuilder.create()
                        .withPackageName(PACKAGE_NAME)
                        .withTargetSdk(TARGET_SDK)
                        .debuggable()
                        .build());

        OverrideAllowedState stateTargetSdkLessChange =
                overrideValidator.getOverrideAllowedState(1, PACKAGE_NAME);
        OverrideAllowedState stateTargetSdkEqualChange =
                overrideValidator.getOverrideAllowedState(2, PACKAGE_NAME);

        assertThat(stateTargetSdkLessChange).isEqualTo(
                new OverrideAllowedState(DISABLED_TARGET_SDK_TOO_HIGH, TARGET_SDK,
                                         TARGET_SDK_BEFORE));
        assertThat(stateTargetSdkEqualChange).isEqualTo(
                new OverrideAllowedState(DISABLED_TARGET_SDK_TOO_HIGH, TARGET_SDK, TARGET_SDK));
    }

    @Test
    public void getOverrideAllowedState_finalBuildEnabledChangeDebugApp_rejectOverride()
            throws Exception {
        CompatConfig config = CompatConfigBuilder.create(finalBuild(), mContext)
                        .addEnabledChangeWithId(1).build();
        IOverrideValidator overrideValidator = config.getOverrideValidator();
        when(mPackageManager.getApplicationInfo(eq(PACKAGE_NAME), anyInt()))
                .thenReturn(ApplicationInfoBuilder.create()
                        .withPackageName(PACKAGE_NAME)
                        .debuggable().build());

        OverrideAllowedState allowedState =
                overrideValidator.getOverrideAllowedState(1, PACKAGE_NAME);

        assertThat(allowedState)
                .isEqualTo(new OverrideAllowedState(DISABLED_NON_TARGET_SDK, -1, -1));
    }

    @Test
    public void getOverrideAllowedState_finalBuildDisabledChangeDebugApp_allowOverride()
            throws Exception {
        CompatConfig config = CompatConfigBuilder.create(finalBuild(), mContext)
                .addDisabledChangeWithId(1).build();
        IOverrideValidator overrideValidator = config.getOverrideValidator();
        when(mPackageManager.getApplicationInfo(eq(PACKAGE_NAME), anyInt()))
                .thenReturn(ApplicationInfoBuilder.create()
                        .withPackageName(PACKAGE_NAME)
                        .withTargetSdk(TARGET_SDK)
                        .debuggable().build());

        OverrideAllowedState allowedState =
                overrideValidator.getOverrideAllowedState(1, PACKAGE_NAME);

        assertThat(allowedState)
                .isEqualTo(new OverrideAllowedState(ALLOWED, TARGET_SDK, -1));
    }

    @Test
    public void getOverrideAllowedState_finalBuildAnyChangeReleaseApp_rejectOverride()
            throws Exception {
        CompatConfig config = CompatConfigBuilder.create(finalBuild(), mContext)
                        .addTargetSdkChangeWithId(TARGET_SDK_BEFORE, 1)
                        .addTargetSdkChangeWithId(TARGET_SDK, 2)
                        .addTargetSdkChangeWithId(TARGET_SDK_AFTER, 3)
                        .addEnabledChangeWithId(4)
                        .addDisabledChangeWithId(5)
                        .addLoggingOnlyChangeWithId(6).build();
        IOverrideValidator overrideValidator = config.getOverrideValidator();
        when(mPackageManager.getApplicationInfo(eq(PACKAGE_NAME), anyInt()))
                .thenReturn(ApplicationInfoBuilder.create()
                        .withPackageName(PACKAGE_NAME)
                        .withTargetSdk(TARGET_SDK).build());

        OverrideAllowedState stateTargetSdkLessChange =
                overrideValidator.getOverrideAllowedState(1, PACKAGE_NAME);
        OverrideAllowedState stateTargetSdkEqualChange =
                overrideValidator.getOverrideAllowedState(2, PACKAGE_NAME);
        OverrideAllowedState stateTargetSdkAfterChange =
                overrideValidator.getOverrideAllowedState(3, PACKAGE_NAME);
        OverrideAllowedState stateEnabledChange =
                overrideValidator.getOverrideAllowedState(4, PACKAGE_NAME);
        OverrideAllowedState stateDisabledChange =
                overrideValidator.getOverrideAllowedState(5, PACKAGE_NAME);
        OverrideAllowedState stateDLoggingOnlyChange =
                overrideValidator.getOverrideAllowedState(6, PACKAGE_NAME);

        assertThat(stateTargetSdkLessChange)
                .isEqualTo(new OverrideAllowedState(DISABLED_NOT_DEBUGGABLE, -1, -1));
        assertThat(stateTargetSdkEqualChange)
                .isEqualTo(new OverrideAllowedState(DISABLED_NOT_DEBUGGABLE, -1, -1));
        assertThat(stateTargetSdkAfterChange)
                .isEqualTo(new OverrideAllowedState(DISABLED_NOT_DEBUGGABLE, -1, -1));
        assertThat(stateEnabledChange)
                .isEqualTo(new OverrideAllowedState(DISABLED_NOT_DEBUGGABLE, -1, -1));
        assertThat(stateDisabledChange)
                .isEqualTo(new OverrideAllowedState(DISABLED_NOT_DEBUGGABLE, -1, -1));
        assertThat(stateDLoggingOnlyChange)
                .isEqualTo(new OverrideAllowedState(LOGGING_ONLY_CHANGE, -1, -1));
    }
}
