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

package com.android.server.dreams;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.TypedArray;
import android.os.Looper;
import android.platform.test.annotations.EnableFlags;
import android.service.dreams.DreamService;
import android.service.dreams.Flags;
import android.service.dreams.IDreamOverlayCallback;
import android.testing.TestableLooper;
import android.view.KeyEvent;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class DreamServiceTest {
    private static final String TEST_PACKAGE_NAME = "com.android.frameworks.dreamservicetests";

    private TestableLooper mTestableLooper;

    @Before
    public void setup() throws Exception {
        mTestableLooper = new TestableLooper(Looper.getMainLooper());
    }

    @After
    public void tearDown() {
        mTestableLooper.destroy();
    }

    @Test
    public void testMetadataParsing() throws PackageManager.NameNotFoundException {
        final String testDreamClassName = "com.android.server.dreams.TestDreamService";
        final String testSettingsActivity =
                "com.android.frameworks.dreamservicetests/.TestDreamSettingsActivity";
        final DreamService.DreamMetadata metadata = getDreamMetadata(testDreamClassName);

        assertThat(metadata.settingsActivity).isEqualTo(
                ComponentName.unflattenFromString(testSettingsActivity));
        assertFalse(metadata.showComplications);
        assertThat(metadata.dreamCategory).isEqualTo(DreamService.DREAM_CATEGORY_HOME_PANEL);
    }

    @Test
    public void testMetadataParsing_invalidSettingsActivity()
            throws PackageManager.NameNotFoundException {
        final String testDreamClassName =
                "com.android.server.dreams.TestDreamServiceWithInvalidSettings";
        final DreamService.DreamMetadata metadata = getDreamMetadata(testDreamClassName);

        assertThat(metadata.settingsActivity).isNull();
        assertThat(metadata.dreamCategory).isEqualTo(DreamService.DREAM_CATEGORY_DEFAULT);
    }

    @Test
    public void testMetadataParsing_nonexistentSettingsActivity()
            throws PackageManager.NameNotFoundException {
        final String testDreamClassName =
                "com.android.server.dreams.TestDreamServiceWithNonexistentSettings";
        final DreamService.DreamMetadata metadata = getDreamMetadata(testDreamClassName);

        assertThat(metadata.settingsActivity).isNull();
        assertThat(metadata.dreamCategory).isEqualTo(DreamService.DREAM_CATEGORY_DEFAULT);
    }

    @Test
    public void testMetadataParsing_noPackage_nonexistentSettingsActivity()
            throws PackageManager.NameNotFoundException {
        final String testDreamClassName =
                "com.android.server.dreams.TestDreamServiceNoPackageNonexistentSettings";
        final DreamService.DreamMetadata metadata = getDreamMetadata(testDreamClassName);

        assertThat(metadata.settingsActivity).isNull();
        assertThat(metadata.dreamCategory).isEqualTo(DreamService.DREAM_CATEGORY_DEFAULT);
    }

    @Test
    public void testMetadataParsing_exceptionReading() {
        final PackageManager packageManager = Mockito.mock(PackageManager.class);
        final ServiceInfo serviceInfo = Mockito.mock(ServiceInfo.class);
        final TypedArray rawMetadata = Mockito.mock(TypedArray.class);
        when(packageManager.extractPackageItemInfoAttributes(eq(serviceInfo), any(), any(), any()))
                .thenReturn(rawMetadata);
        when(rawMetadata.getString(anyInt())).thenThrow(new RuntimeException("failure"));

        assertThat(DreamService.getDreamMetadata(packageManager, serviceInfo)).isNull();
    }

    private DreamService.DreamMetadata getDreamMetadata(String dreamClassName)
            throws PackageManager.NameNotFoundException {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final ServiceInfo si = context.getPackageManager().getServiceInfo(
                new ComponentName(TEST_PACKAGE_NAME, dreamClassName),
                PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA));
        return DreamService.getDreamMetadata(context, si);
    }

    /**
     * Verifies progressing a {@link DreamService} to creation
     */
    @Test
    public void testCreate() throws Exception {
        final TestDreamEnvironment environment = new TestDreamEnvironment.Builder(mTestableLooper)
                .setShouldShowComplications(true)
                .build();
        assertTrue(environment.advance(TestDreamEnvironment.DREAM_STATE_CREATE));
    }

    /**
     * Verifies progressing a {@link DreamService}  to binding
     */
    @Test
    public void testBind() throws Exception {
        final TestDreamEnvironment environment = new TestDreamEnvironment.Builder(mTestableLooper)
                .setShouldShowComplications(true)
                .build();
        assertTrue(environment.advance(TestDreamEnvironment.DREAM_STATE_BIND));
    }

    /**
     * Verifies progressing a {@link DreamService} through
     * {@link android.service.dreams.DreamActivity} creation.
     */
    @Test
    public void testDreamActivityCreate() throws Exception {
        final TestDreamEnvironment environment = new TestDreamEnvironment.Builder(mTestableLooper)
                .setShouldShowComplications(true)
                .build();
        assertTrue(environment.advance(TestDreamEnvironment.DREAM_STATE_DREAM_ACTIVITY_CREATED));
    }

    /**
     * Verifies progressing a {@link DreamService} through starting.
     */
    @Test
    public void testStart() throws Exception {
        final TestDreamEnvironment environment = new TestDreamEnvironment.Builder(mTestableLooper)
                .setShouldShowComplications(true)
                .build();
        assertTrue(environment.advance(TestDreamEnvironment.DREAM_STATE_STARTED));
    }

    /**
     * Verifies progressing a {@link DreamService} through waking.
     */
    @Test
    public void testWake() throws Exception {
        final TestDreamEnvironment environment = new TestDreamEnvironment.Builder(mTestableLooper)
                .setShouldShowComplications(true)
                .build();
        assertTrue(environment.advance(TestDreamEnvironment.DREAM_STATE_WOKEN));
    }

    @Test
    @EnableFlags(Flags.FLAG_DREAM_WAKE_REDIRECT)
    public void testRedirect() throws Exception {
        TestDreamEnvironment environment = new TestDreamEnvironment.Builder(mTestableLooper)
                .setDreamOverlayPresent(true)
                .setShouldShowComplications(true)
                .build();

        environment.advance(TestDreamEnvironment.DREAM_STATE_STARTED);
        final IDreamOverlayCallback overlayCallback = environment.getDreamOverlayCallback();
        overlayCallback.onRedirectWake(true);
        environment.resetClientInvocations();
        environment.advance(TestDreamEnvironment.DREAM_STATE_WOKEN);
        verify(environment.getDreamOverlayClient()).onWakeRequested();
    }

    @Test
    @EnableFlags(Flags.FLAG_DREAM_HANDLES_CONFIRM_KEYS)
    public void testPartialKeyHandling() throws Exception {
        TestDreamEnvironment environment = new TestDreamEnvironment.Builder(mTestableLooper)
                .build();
        environment.advance(TestDreamEnvironment.DREAM_STATE_STARTED);

        // Ensure service does not crash from only receiving up event.
        environment.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE));
    }
}
