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
import android.util.ArraySet;
import android.view.contentcapture.ContentCaptureManager.ContentCaptureClient;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Unit test for {@link ContentCaptureOptions}.
 *
 * <p>To run it:
 * {@code atest FrameworksCoreTests:android.content.ContentCaptureOptionsTest}
 */
@RunWith(MockitoJUnitRunner.class)
public class ContentCaptureOptionsTest {

    private final ComponentName mContextComponent = new ComponentName("marco", "polo");
    private final ComponentName mComp1 = new ComponentName("comp", "one");
    private final ComponentName mComp2 = new ComponentName("two", "comp");

    @Mock private Context mContext;
    @Mock private ContentCaptureClient mClient;

    @Before
    public void setExpectation() {
        when(mClient.contentCaptureClientGetComponentName()).thenReturn(mContextComponent);
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
        ContentCaptureOptions options = new ContentCaptureOptions(toSet(mComp1, mComp2));
        assertThat(options.isWhitelisted(mContext)).isFalse();
    }

    @Test
    public void testIsWhitelisted_whitelisted() {
        ContentCaptureOptions options = new ContentCaptureOptions(toSet(mComp1, mContextComponent));
        assertThat(options.isWhitelisted(mContext)).isTrue();
    }

    @Test
    public void testIsWhitelisted_invalidContext() {
        ContentCaptureOptions options = new ContentCaptureOptions(toSet(mContextComponent));
        Context invalidContext = mock(Context.class); // has no client
        assertThat(options.isWhitelisted(invalidContext)).isFalse();
    }

    @Test
    public void testIsWhitelisted_clientWithNullComponentName() {
        ContentCaptureOptions options = new ContentCaptureOptions(toSet(mContextComponent));
        ContentCaptureClient client = mock(ContentCaptureClient.class);
        Context context = mock(Context.class);
        when(context.getContentCaptureClient()).thenReturn(client);

        assertThat(options.isWhitelisted(context)).isFalse();
    }

    @NonNull
    private ArraySet<ComponentName> toSet(@Nullable ComponentName... comps) {
        ArraySet<ComponentName> set = new ArraySet<>();
        if (comps != null) {
            for (int i = 0; i < comps.length; i++) {
                set.add(comps[i]);
            }
        }
        return set;
    }
}
