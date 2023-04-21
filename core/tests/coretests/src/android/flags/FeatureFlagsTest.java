/*
 * Copyright (C) 2020 The Android Open Source Project
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
package android.flags;

import static com.google.common.truth.Truth.assertThat;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;


@SmallTest
@Presubmit
public class FeatureFlagsTest {

    IFeatureFlagsFake mIFeatureFlagsFake = new IFeatureFlagsFake();
    FeatureFlags mFeatureFlags = new FeatureFlags(mIFeatureFlagsFake);

    @Before
    public void setup() {
        FeatureFlags.setInstance(mFeatureFlags);
    }

    @Test
    public void testFusedOff_Disabled() {
        FusedOffFlag flag = FeatureFlags.fusedOffFlag("test", "a");
        assertThat(mFeatureFlags.isEnabled(flag)).isFalse();
    }

    @Test
    public void testFusedOn_Enabled() {
        FusedOnFlag flag = FeatureFlags.fusedOnFlag("test", "a");
        assertThat(mFeatureFlags.isEnabled(flag)).isTrue();
    }

    @Test
    public void testBooleanFlag_DefaultDisabled() {
        BooleanFlag flag = FeatureFlags.booleanFlag("test", "a", false);
        assertThat(mFeatureFlags.isEnabled(flag)).isFalse();
    }

    @Test
    public void testBooleanFlag_DefaultEnabled() {
        BooleanFlag flag = FeatureFlags.booleanFlag("test", "a", true);
        assertThat(mFeatureFlags.isEnabled(flag)).isTrue();
    }

    @Test
    public void testDynamicBooleanFlag_DefaultDisabled() {
        DynamicBooleanFlag flag = FeatureFlags.dynamicBooleanFlag("test", "a", false);
        assertThat(mFeatureFlags.isCurrentlyEnabled(flag)).isFalse();
    }

    @Test
    public void testDynamicBooleanFlag_DefaultEnabled() {
        DynamicBooleanFlag flag = FeatureFlags.dynamicBooleanFlag("test", "a", true);
        assertThat(mFeatureFlags.isCurrentlyEnabled(flag)).isTrue();
    }

    @Test
    public void testBooleanFlag_OverrideBeforeRead() {
        BooleanFlag flag = FeatureFlags.booleanFlag("test", "a", false);
        SyncableFlag syncableFlag = new SyncableFlag(
                flag.getNamespace(), flag.getName(), "true", false);

        mIFeatureFlagsFake.setFlagOverrides(List.of(syncableFlag));

        assertThat(mFeatureFlags.isEnabled(flag)).isTrue();
    }

    @Test
    public void testFusedOffFlag_OverrideHasNoEffect() {
        FusedOffFlag flag = FeatureFlags.fusedOffFlag("test", "a");
        SyncableFlag syncableFlag = new SyncableFlag(
                flag.getNamespace(), flag.getName(), "true", false);

        mIFeatureFlagsFake.setFlagOverrides(List.of(syncableFlag));

        assertThat(mFeatureFlags.isEnabled(flag)).isFalse();
    }

    @Test
    public void testFusedOnFlag_OverrideHasNoEffect() {
        FusedOnFlag flag = FeatureFlags.fusedOnFlag("test", "a");
        SyncableFlag syncableFlag = new SyncableFlag(
                flag.getNamespace(), flag.getName(), "false", false);

        mIFeatureFlagsFake.setFlagOverrides(List.of(syncableFlag));

        assertThat(mFeatureFlags.isEnabled(flag)).isTrue();
    }

    @Test
    public void testDynamicFlag_OverrideBeforeRead() {
        DynamicBooleanFlag flag = FeatureFlags.dynamicBooleanFlag("test", "a", false);
        SyncableFlag syncableFlag = new SyncableFlag(
                flag.getNamespace(), flag.getName(), "true", true);

        mIFeatureFlagsFake.setFlagOverrides(List.of(syncableFlag));

        // Changes to true
        assertThat(mFeatureFlags.isCurrentlyEnabled(flag)).isTrue();
    }

    @Test
    public void testDynamicFlag_OverrideAfterRead() {
        DynamicBooleanFlag flag = FeatureFlags.dynamicBooleanFlag("test", "a", false);
        SyncableFlag syncableFlag = new SyncableFlag(
                flag.getNamespace(), flag.getName(), "true", true);

        // Starts false
        assertThat(mFeatureFlags.isCurrentlyEnabled(flag)).isFalse();

        mIFeatureFlagsFake.setFlagOverrides(List.of(syncableFlag));

        // Changes to true
        assertThat(mFeatureFlags.isCurrentlyEnabled(flag)).isTrue();
    }

    @Test
    public void testDynamicFlag_FiresListener() {
        DynamicBooleanFlag flag = FeatureFlags.dynamicBooleanFlag("test", "a", false);
        AtomicBoolean called = new AtomicBoolean(false);
        FeatureFlags.ChangeListener listener = flag1 -> called.set(true);

        mFeatureFlags.addChangeListener(listener);

        SyncableFlag syncableFlag = new SyncableFlag(
                flag.getNamespace(), flag.getName(), flag.getDefault().toString(), true);

        mIFeatureFlagsFake.setFlagOverrides(List.of(syncableFlag));

        // Fires listener.
        assertThat(called.get()).isTrue();
    }
}
