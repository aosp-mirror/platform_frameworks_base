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

package android.app;

import static com.google.common.truth.Truth.assertThat;

import android.app.StatsManager.PullAtomMetadata;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public final class PullAtomMetadataTest {

    @Test
    public void testEmpty() {
        PullAtomMetadata metadata = PullAtomMetadata.newBuilder().build();
        assertThat(metadata.getTimeoutNs()).isEqualTo(StatsManager.DEFAULT_TIMEOUT_NS);
        assertThat(metadata.getCoolDownNs()).isEqualTo(StatsManager.DEFAULT_COOL_DOWN_NS);
        assertThat(metadata.getAdditiveFields()).isNull();
    }

    @Test
    public void testSetTimeoutNs() {
        long timeoutNs = 500_000_000L;
        PullAtomMetadata metadata =
                PullAtomMetadata.newBuilder().setTimeoutNs(timeoutNs).build();
        assertThat(metadata.getTimeoutNs()).isEqualTo(timeoutNs);
        assertThat(metadata.getCoolDownNs()).isEqualTo(StatsManager.DEFAULT_COOL_DOWN_NS);
        assertThat(metadata.getAdditiveFields()).isNull();
    }

    @Test
    public void testSetCoolDownNs() {
        long coolDownNs = 10_000_000_000L;
        PullAtomMetadata metadata =
                PullAtomMetadata.newBuilder().setCoolDownNs(coolDownNs).build();
        assertThat(metadata.getTimeoutNs()).isEqualTo(StatsManager.DEFAULT_TIMEOUT_NS);
        assertThat(metadata.getCoolDownNs()).isEqualTo(coolDownNs);
        assertThat(metadata.getAdditiveFields()).isNull();
    }

    @Test
    public void testSetAdditiveFields() {
        int[] fields = {2, 4, 6};
        PullAtomMetadata metadata =
                PullAtomMetadata.newBuilder().setAdditiveFields(fields).build();
        assertThat(metadata.getTimeoutNs()).isEqualTo(StatsManager.DEFAULT_TIMEOUT_NS);
        assertThat(metadata.getCoolDownNs()).isEqualTo(StatsManager.DEFAULT_COOL_DOWN_NS);
        assertThat(metadata.getAdditiveFields()).isEqualTo(fields);
    }

    @Test
    public void testSetAllElements() {
        long timeoutNs = 300L;
        long coolDownNs = 9572L;
        int[] fields = {3, 2};
        PullAtomMetadata metadata = PullAtomMetadata.newBuilder()
                .setTimeoutNs(timeoutNs)
                .setCoolDownNs(coolDownNs)
                .setAdditiveFields(fields)
                .build();
        assertThat(metadata.getTimeoutNs()).isEqualTo(timeoutNs);
        assertThat(metadata.getCoolDownNs()).isEqualTo(coolDownNs);
        assertThat(metadata.getAdditiveFields()).isEqualTo(fields);
    }
}
