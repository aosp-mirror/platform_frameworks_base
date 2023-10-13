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
package android.content;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.util.ArraySet;
import android.view.contentcapture.ContentCaptureManager.ContentCaptureClient;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.List;

/**
 * Unit test for {@link ContentCaptureOptions}.
 *
 * <p>To run it:
 * {@code atest FrameworksCoreTests:android.content.ContentCaptureOptionsTest}
 */
@RunWith(MockitoJUnitRunner.class)
public class ContentCaptureOptionsTest {

    private static final ComponentName CONTEXT_COMPONENT = new ComponentName("marco", "polo");
    private static final ComponentName COMPONENT1 = new ComponentName("comp", "one");
    private static final ComponentName COMPONENT2 = new ComponentName("two", "comp");
    private static final List<List<String>> CONTENT_PROTECTION_REQUIRED_GROUPS =
            List.of(List.of("first"), List.of("second", "third"), List.of());
    private static final List<List<String>> CONTENT_PROTECTION_OPTIONAL_GROUPS = List.of();
    private static final ContentCaptureOptions CONTENT_CAPTURE_OPTIONS =
            new ContentCaptureOptions(
                    /* loggingLevel= */ 1000,
                    /* maxBufferSize= */ 1001,
                    /* idleFlushingFrequencyMs= */ 1002,
                    /* textChangeFlushingFrequencyMs= */ 1003,
                    /* logHistorySize= */ 1004,
                    /* disableFlushForViewTreeAppearing= */ true,
                    /* enableReceiver= */ false,
                    new ContentCaptureOptions.ContentProtectionOptions(
                            /* enableReceiver= */ true,
                            /* bufferSize= */ 2001,
                            CONTENT_PROTECTION_REQUIRED_GROUPS,
                            CONTENT_PROTECTION_OPTIONAL_GROUPS,
                            /* optionalGroupsThreshold= */ 2002),
                    /* whitelistedComponents= */ toSet(COMPONENT1, COMPONENT2));

    @Mock private Context mContext;
    @Mock private ContentCaptureClient mClient;

    @Before
    public void setExpectation() {
        when(mClient.contentCaptureClientGetComponentName()).thenReturn(CONTEXT_COMPONENT);
        when(mContext.getContentCaptureClient()).thenReturn(mClient);
    }

    @Test
    public void testIsWhitelisted_nullWhitelistedComponents() {
        ContentCaptureOptions options = new ContentCaptureOptions(null);
        assertThat(options.isWhitelisted(mContext)).isTrue();
    }

    @Test
    public void testIsWhitelisted_emptyWhitelistedComponents() {
        ContentCaptureOptions options = new ContentCaptureOptions(toSet((ComponentName) null));
        assertThat(options.isWhitelisted(mContext)).isFalse();
    }

    @Test
    public void testIsWhitelisted_notWhitelisted() {
        ContentCaptureOptions options = new ContentCaptureOptions(toSet(COMPONENT1, COMPONENT2));
        assertThat(options.isWhitelisted(mContext)).isFalse();
    }

    @Test
    public void testIsWhitelisted_whitelisted() {
        ContentCaptureOptions options =
                new ContentCaptureOptions(toSet(COMPONENT1, CONTEXT_COMPONENT));
        assertThat(options.isWhitelisted(mContext)).isTrue();
    }

    @Test
    public void testIsWhitelisted_invalidContext() {
        ContentCaptureOptions options = new ContentCaptureOptions(toSet(CONTEXT_COMPONENT));
        Context invalidContext = mock(Context.class); // has no client
        assertThat(options.isWhitelisted(invalidContext)).isFalse();
    }

    @Test
    public void testIsWhitelisted_clientWithNullComponentName() {
        ContentCaptureOptions options = new ContentCaptureOptions(toSet(CONTEXT_COMPONENT));
        ContentCaptureClient client = mock(ContentCaptureClient.class);
        Context context = mock(Context.class);
        when(context.getContentCaptureClient()).thenReturn(client);

        assertThat(options.isWhitelisted(context)).isFalse();
    }

    @Test
    public void testToString() {
        String actual = CONTENT_CAPTURE_OPTIONS.toString();

        String expected =
                new StringBuilder("ContentCaptureOptions [")
                        .append("loggingLevel=")
                        .append(CONTENT_CAPTURE_OPTIONS.loggingLevel)
                        .append(", maxBufferSize=")
                        .append(CONTENT_CAPTURE_OPTIONS.maxBufferSize)
                        .append(", idleFlushingFrequencyMs=")
                        .append(CONTENT_CAPTURE_OPTIONS.idleFlushingFrequencyMs)
                        .append(", textChangeFlushingFrequencyMs=")
                        .append(CONTENT_CAPTURE_OPTIONS.textChangeFlushingFrequencyMs)
                        .append(", logHistorySize=")
                        .append(CONTENT_CAPTURE_OPTIONS.logHistorySize)
                        .append(", disableFlushForViewTreeAppearing=")
                        .append(CONTENT_CAPTURE_OPTIONS.disableFlushForViewTreeAppearing)
                        .append(", enableReceiver=")
                        .append(CONTENT_CAPTURE_OPTIONS.enableReceiver)
                        .append(", contentProtectionOptions=ContentProtectionOptions [")
                        .append("enableReceiver=")
                        .append(CONTENT_CAPTURE_OPTIONS.contentProtectionOptions.enableReceiver)
                        .append(", bufferSize=")
                        .append(CONTENT_CAPTURE_OPTIONS.contentProtectionOptions.bufferSize)
                        .append(", requiredGroupsSize=")
                        .append(
                                CONTENT_CAPTURE_OPTIONS.contentProtectionOptions.requiredGroups
                                        .size())
                        .append(", optionalGroupsSize=")
                        .append(
                                CONTENT_CAPTURE_OPTIONS.contentProtectionOptions.optionalGroups
                                        .size())
                        .append(", optionalGroupsThreshold=")
                        .append(
                                CONTENT_CAPTURE_OPTIONS
                                        .contentProtectionOptions
                                        .optionalGroupsThreshold)
                        .append("], whitelisted=")
                        .append(CONTENT_CAPTURE_OPTIONS.whitelistedComponents)
                        .append(']')
                        .toString();
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    public void testParcelSerializationDeserialization() {
        Parcel parcel = Parcel.obtain();
        CONTENT_CAPTURE_OPTIONS.writeToParcel(parcel, /* flags= */ 0);
        parcel.setDataPosition(0);

        ContentCaptureOptions actual = ContentCaptureOptions.CREATOR.createFromParcel(parcel);
        parcel.recycle();

        assertThat(actual).isNotNull();
        assertThat(actual.loggingLevel).isEqualTo(CONTENT_CAPTURE_OPTIONS.loggingLevel);
        assertThat(actual.maxBufferSize).isEqualTo(CONTENT_CAPTURE_OPTIONS.maxBufferSize);
        assertThat(actual.idleFlushingFrequencyMs)
                .isEqualTo(CONTENT_CAPTURE_OPTIONS.idleFlushingFrequencyMs);
        assertThat(actual.textChangeFlushingFrequencyMs)
                .isEqualTo(CONTENT_CAPTURE_OPTIONS.textChangeFlushingFrequencyMs);
        assertThat(actual.logHistorySize).isEqualTo(CONTENT_CAPTURE_OPTIONS.logHistorySize);
        assertThat(actual.disableFlushForViewTreeAppearing)
                .isEqualTo(CONTENT_CAPTURE_OPTIONS.disableFlushForViewTreeAppearing);
        assertThat(actual.enableReceiver).isEqualTo(CONTENT_CAPTURE_OPTIONS.enableReceiver);
        assertThat(actual.contentProtectionOptions).isNotNull();
        assertThat(actual.contentProtectionOptions.enableReceiver)
                .isEqualTo(CONTENT_CAPTURE_OPTIONS.contentProtectionOptions.enableReceiver);
        assertThat(actual.contentProtectionOptions.bufferSize)
                .isEqualTo(CONTENT_CAPTURE_OPTIONS.contentProtectionOptions.bufferSize);
        assertThat(actual.contentProtectionOptions.requiredGroups)
                .containsExactlyElementsIn(
                        CONTENT_CAPTURE_OPTIONS.contentProtectionOptions.requiredGroups);
        assertThat(actual.contentProtectionOptions.optionalGroups)
                .containsExactlyElementsIn(
                        CONTENT_CAPTURE_OPTIONS.contentProtectionOptions.optionalGroups);
        assertThat(actual.contentProtectionOptions.optionalGroupsThreshold)
                .isEqualTo(
                        CONTENT_CAPTURE_OPTIONS.contentProtectionOptions.optionalGroupsThreshold);
        assertThat(actual.whitelistedComponents)
                .containsExactlyElementsIn(CONTENT_CAPTURE_OPTIONS.whitelistedComponents);
    }

    @NonNull
    private static ArraySet<ComponentName> toSet(@Nullable ComponentName... comps) {
        ArraySet<ComponentName> set = new ArraySet<>();
        if (comps != null) {
            for (int i = 0; i < comps.length; i++) {
                set.add(comps[i]);
            }
        }
        return set;
    }
}
