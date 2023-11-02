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
package android.view.contentcapture;

import static android.view.contentcapture.ContentCaptureEvent.TYPE_SESSION_STARTED;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.assertThrows;

import android.content.ContentCaptureOptions;
import android.content.Context;
import android.view.WindowManager;

import com.android.internal.util.RingBuffer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Collections;

/**
 * Unit test for {@link ContentCaptureManager}.
 *
 * <p>To run it:
 * {@code atest FrameworksCoreTests:android.view.contentcapture.ContentCaptureManagerTest}
 */
@RunWith(MockitoJUnitRunner.class)
public class ContentCaptureManagerTest {

    private static final int BUFFER_SIZE = 100;

    private static final ContentCaptureOptions EMPTY_OPTIONS = new ContentCaptureOptions(null);

    @Mock
    private Context mMockContext;

    @Mock private IContentCaptureManager mMockContentCaptureManager;

    @Test
    public void testConstructor_invalidParametersThrowsException() {
        assertThrows(NullPointerException.class,
                () -> new ContentCaptureManager(mMockContext, /* service= */ null, /* options= */
                        null));
    }

    @Test
    public void testConstructor_contentProtection_default_bufferNotCreated() {
        ContentCaptureManager manager =
                new ContentCaptureManager(mMockContext, mMockContentCaptureManager, EMPTY_OPTIONS);

        assertThat(manager.getContentProtectionEventBuffer()).isNull();
    }

    @Test
    public void testConstructor_contentProtection_disabled_bufferNotCreated() {
        ContentCaptureOptions options =
                createOptions(
                        new ContentCaptureOptions.ContentProtectionOptions(
                                /* enableReceiver= */ false,
                                BUFFER_SIZE,
                                /* requiredGroups= */ Collections.emptyList(),
                                /* optionalGroups= */ Collections.emptyList(),
                                /* optionalGroupsThreshold= */ 0));

        ContentCaptureManager manager =
                new ContentCaptureManager(mMockContext, mMockContentCaptureManager, options);

        assertThat(manager.getContentProtectionEventBuffer()).isNull();
    }

    @Test
    public void testConstructor_contentProtection_invalidBufferSize_bufferNotCreated() {
        ContentCaptureOptions options =
                createOptions(
                        new ContentCaptureOptions.ContentProtectionOptions(
                                /* enableReceiver= */ true,
                                /* bufferSize= */ 0,
                                /* requiredGroups= */ Collections.emptyList(),
                                /* optionalGroups= */ Collections.emptyList(),
                                /* optionalGroupsThreshold= */ 0));

        ContentCaptureManager manager =
                new ContentCaptureManager(mMockContext, mMockContentCaptureManager, options);

        assertThat(manager.getContentProtectionEventBuffer()).isNull();
    }

    @Test
    public void testConstructor_contentProtection_enabled_bufferCreated() {
        ContentCaptureOptions options =
                createOptions(
                        new ContentCaptureOptions.ContentProtectionOptions(
                                /* enableReceiver= */ true,
                                BUFFER_SIZE,
                                /* requiredGroups= */ Collections.emptyList(),
                                /* optionalGroups= */ Collections.emptyList(),
                                /* optionalGroupsThreshold= */ 0));

        ContentCaptureManager manager =
                new ContentCaptureManager(mMockContext, mMockContentCaptureManager, options);
        RingBuffer<ContentCaptureEvent> buffer = manager.getContentProtectionEventBuffer();

        assertThat(buffer).isNotNull();
        ContentCaptureEvent[] expected = new ContentCaptureEvent[BUFFER_SIZE];
        int offset = 3;
        for (int i = 0; i < BUFFER_SIZE + offset; i++) {
            ContentCaptureEvent event = new ContentCaptureEvent(i, TYPE_SESSION_STARTED);
            buffer.append(event);
            expected[(i + BUFFER_SIZE - offset) % BUFFER_SIZE] = event;
        }
        assertThat(buffer.toArray()).isEqualTo(expected);
    }

    @Test
    public void testRemoveData_invalidParametersThrowsException() {
        final ContentCaptureManager manager =
                new ContentCaptureManager(mMockContext, mMockContentCaptureManager, EMPTY_OPTIONS);

        assertThrows(NullPointerException.class, () -> manager.removeData(null));
    }

    @Test
    @SuppressWarnings("GuardedBy")
    public void testFlushViewTreeAppearingEventDisabled_setAndGet() {
        final ContentCaptureManager manager =
                new ContentCaptureManager(mMockContext, mMockContentCaptureManager, EMPTY_OPTIONS);

        assertThat(manager.getFlushViewTreeAppearingEventDisabled()).isFalse();
        manager.setFlushViewTreeAppearingEventDisabled(true);
        assertThat(manager.getFlushViewTreeAppearingEventDisabled()).isTrue();
        manager.setFlushViewTreeAppearingEventDisabled(false);
        assertThat(manager.getFlushViewTreeAppearingEventDisabled()).isFalse();
    }

    @Test
    public void testUpdateWindowAttribute_setFlagSecure() {
        final ContentCaptureManager manager =
                new ContentCaptureManager(mMockContext, mMockContentCaptureManager, EMPTY_OPTIONS);
        // Ensure main session is created.
        final MainContentCaptureSession unused = manager.getMainContentCaptureSession();
        final WindowManager.LayoutParams initialParam = new WindowManager.LayoutParams();
        initialParam.flags |= WindowManager.LayoutParams.FLAG_SECURE;

        manager.updateWindowAttributes(initialParam);

        assertThat(manager.isContentCaptureEnabled()).isFalse();
    }

    @Test
    public void testUpdateWindowAttribute_clearFlagSecure() {
        final ContentCaptureManager manager =
                new ContentCaptureManager(mMockContext, mMockContentCaptureManager, EMPTY_OPTIONS);
        // Ensure main session is created.
        final MainContentCaptureSession unused = manager.getMainContentCaptureSession();
        final WindowManager.LayoutParams initialParam = new WindowManager.LayoutParams();
        initialParam.flags |= WindowManager.LayoutParams.FLAG_SECURE;
        // Default param does not have FLAG_SECURE set.
        final WindowManager.LayoutParams resetParam = new WindowManager.LayoutParams();

        manager.updateWindowAttributes(initialParam);
        manager.updateWindowAttributes(resetParam);

        assertThat(manager.isContentCaptureEnabled()).isTrue();
    }

    @Test
    public void testUpdateWindowAttribute_clearFlagSecureAfterDisabledByApp() {
        final ContentCaptureManager manager =
                new ContentCaptureManager(mMockContext, mMockContentCaptureManager, EMPTY_OPTIONS);
        // Ensure main session is created.
        final MainContentCaptureSession unused = manager.getMainContentCaptureSession();
        // Default param does not have FLAG_SECURE set.
        final WindowManager.LayoutParams resetParam = new WindowManager.LayoutParams();

        manager.setContentCaptureEnabled(false);
        manager.updateWindowAttributes(resetParam);

        assertThat(manager.isContentCaptureEnabled()).isFalse();
    }

    private ContentCaptureOptions createOptions(
            ContentCaptureOptions.ContentProtectionOptions contentProtectionOptions) {
        return new ContentCaptureOptions(
                /* loggingLevel= */ 0,
                /* maxBufferSize= */ 0,
                /* idleFlushingFrequencyMs= */ 0,
                /* textChangeFlushingFrequencyMs= */ 0,
                /* logHistorySize= */ 0,
                /* disableFlushForViewTreeAppearing= */ false,
                /* enableReceiver= */ true,
                contentProtectionOptions,
                /* whitelistedComponents= */ null);
    }
}
