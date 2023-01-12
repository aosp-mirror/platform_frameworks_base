/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.media.projection.MediaProjectionManager.EXTRA_MEDIA_PROJECTION_CONFIG;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.quality.Strictness.LENIENT;

import android.annotation.NonNull;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoSession;

/**
 * Tests for the {@link MediaProjectionManager} class.
 *
 * Build/Install/Run:
 * atest MediaProjectionTests:MediaProjectionManagerTest
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class MediaProjectionManagerTest {
    private MediaProjectionManager mMediaProjectionManager;
    private Context mContext;
    private MockitoSession mMockingSession;
    private static final MediaProjectionConfig DISPLAY_CONFIG =
            MediaProjectionConfig.createConfigForDefaultDisplay();
    private static final MediaProjectionConfig USERS_CHOICE_CONFIG =
            MediaProjectionConfig.createConfigForUserChoice();

    @Before
    public void setup() throws Exception {
        mMockingSession =
                mockitoSession()
                        .initMocks(this)
                        .strictness(LENIENT)
                        .startMocking();
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        spyOn(mContext);
        mMediaProjectionManager = new MediaProjectionManager(mContext);
    }

    @After
    public void teardown() {
        mMockingSession.finishMocking();
    }

    @Test
    public void testCreateScreenCaptureIntent() {
        final String dialogPackage = "test.package";
        preparePermissionDialogComponent(dialogPackage);

        final Intent intent = mMediaProjectionManager.createScreenCaptureIntent();
        assertThat(intent).isNotNull();
        assertThat(intent.getComponent().getPackageName()).contains(dialogPackage);
    }

    @Test
    public void testCreateScreenCaptureIntent_display() {
        final String dialogPackage = "test.package";
        preparePermissionDialogComponent(dialogPackage);

        final Intent intent = mMediaProjectionManager.createScreenCaptureIntent(DISPLAY_CONFIG);
        assertThat(intent).isNotNull();
        assertThat(intent.getComponent().getPackageName()).contains(dialogPackage);
        assertThat(intent.getParcelableExtra(EXTRA_MEDIA_PROJECTION_CONFIG,
                MediaProjectionConfig.class)).isEqualTo(DISPLAY_CONFIG);
    }

    @Test
    public void testCreateScreenCaptureIntent_usersChoice() {
        final String dialogPackage = "test.package";
        preparePermissionDialogComponent(dialogPackage);

        final Intent intent = mMediaProjectionManager.createScreenCaptureIntent(
                USERS_CHOICE_CONFIG);
        assertThat(intent).isNotNull();
        assertThat(intent.getComponent().getPackageName()).contains(dialogPackage);
        assertThat(intent.getParcelableExtra(EXTRA_MEDIA_PROJECTION_CONFIG,
                MediaProjectionConfig.class)).isEqualTo(USERS_CHOICE_CONFIG);
    }

    private void preparePermissionDialogComponent(@NonNull String dialogPackage) {
        final Resources mockResources = mock(Resources.class);
        when(mContext.getResources()).thenReturn(mockResources);
        doReturn(dialogPackage + "/.TestActivity").when(mockResources).getString(
                com.android.internal.R.string
                        .config_mediaProjectionPermissionDialogComponent);
    }
}
