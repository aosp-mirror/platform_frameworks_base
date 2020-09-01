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
        PullAtomMetadata metadata = new PullAtomMetadata.Builder().build();
        assertThat(metadata.getTimeoutMillis()).isEqualTo(StatsManager.DEFAULT_TIMEOUT_MILLIS);
        assertThat(metadata.getCoolDownMillis()).isEqualTo(StatsManager.DEFAULT_COOL_DOWN_MILLIS);
        assertThat(metadata.getAdditiveFields()).isNull();
    }

    @Test
    public void testSetTimeoutMillis() {
        long timeoutMillis = 500L;
        PullAtomMetadata metadata =
                new PullAtomMetadata.Builder().setTimeoutMillis(timeoutMillis).build();
        assertThat(metadata.getTimeoutMillis()).isEqualTo(timeoutMillis);
        assertThat(metadata.getCoolDownMillis()).isEqualTo(StatsManager.DEFAULT_COOL_DOWN_MILLIS);
        assertThat(metadata.getAdditiveFields()).isNull();
    }

    @Test
    public void testSetCoolDownMillis() {
        long coolDownMillis = 10_000L;
        PullAtomMetadata metadata =
                new PullAtomMetadata.Builder().setCoolDownMillis(coolDownMillis).build();
        assertThat(metadata.getTimeoutMillis()).isEqualTo(StatsManager.DEFAULT_TIMEOUT_MILLIS);
        assertThat(metadata.getCoolDownMillis()).isEqualTo(coolDownMillis);
        assertThat(metadata.getAdditiveFields()).isNull();
    }

    @Test
    public void testSetAdditiveFields() {
        int[] fields = {2, 4, 6};
        PullAtomMetadata metadata =
                new PullAtomMetadata.Builder().setAdditiveFields(fields).build();
        assertThat(metadata.getTimeoutMillis()).isEqualTo(StatsManager.DEFAULT_TIMEOUT_MILLIS);
        assertThat(metadata.getCoolDownMillis()).isEqualTo(StatsManager.DEFAULT_COOL_DOWN_MILLIS);
        assertThat(metadata.getAdditiveFields()).isEqualTo(fields);
    }

    @Test
    public void testSetAllElements() {
        long timeoutMillis = 300L;
        long coolDownMillis = 9572L;
        int[] fields = {3, 2};
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setTimeoutMillis(timeoutMillis)
                .setCoolDownMillis(coolDownMillis)
                .setAdditiveFields(fields)
                .build();
        assertThat(metadata.getTimeoutMillis()).isEqualTo(timeoutMillis);
        assertThat(metadata.getCoolDownMillis()).isEqualTo(coolDownMillis);
        assertThat(metadata.getAdditiveFields()).isEqualTo(fields);
    }
}
