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

package android.view;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
@Presubmit
public final class ScrollFeedbackProviderTest {
    private final Context mContext = InstrumentationRegistry.getContext();

    @Test
    public void testDefaultProviderType() {
        View view = new View(mContext);

        ScrollFeedbackProvider provider = ScrollFeedbackProvider.createProvider(view);

        assertThat(provider).isInstanceOf(HapticScrollFeedbackProvider.class);
    }

    @Test
    public void testDefaultProvider_createsDistinctProvidesOnMultipleCalls() {
        View view1 = new View(mContext);
        View view2 = new View(mContext);

        ScrollFeedbackProvider view1Provider1 = ScrollFeedbackProvider.createProvider(view1);
        ScrollFeedbackProvider view1Provider2 = ScrollFeedbackProvider.createProvider(view1);
        ScrollFeedbackProvider view2Provider = ScrollFeedbackProvider.createProvider(view2);

        assertThat(view1Provider1 == view1Provider2).isFalse();
        assertThat(view1Provider1 == view2Provider).isFalse();
    }
}
