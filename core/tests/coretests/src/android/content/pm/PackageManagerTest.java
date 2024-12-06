/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.content.pm;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Modifier;
import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class PackageManagerTest {
    @Test
    public void testPackageInfoFlags() throws Exception {
        assertThat(PackageManager.PackageInfoFlags.of(42L).getValue()).isEqualTo(42L);
    }

    @Test
    public void testApplicationInfoFlags() throws Exception {
        assertThat(PackageManager.ApplicationInfoFlags.of(42L).getValue()).isEqualTo(42L);
    }

    @Test
    public void testComponentInfoFlags() throws Exception {
        assertThat(PackageManager.ComponentInfoFlags.of(42L).getValue()).isEqualTo(42L);
    }

    @Test
    public void testResolveInfoFlags() throws Exception {
        assertThat(PackageManager.ResolveInfoFlags.of(42L).getValue()).isEqualTo(42L);
    }

    @Test
    public void testSdkFeatureCount() throws Exception {
        // Check to make sure the system feature `SdkConst` annotation processor yields sensible
        // results. We don't care about the exactness, just that it's not pathologically wrong.
        assertThat(PackageManager.SDK_FEATURE_COUNT).isGreaterThan(150);
        assertThat(PackageManager.SDK_FEATURE_COUNT).isLessThan(500);
        assertThat(PackageManager.SDK_FEATURE_COUNT)
                .isWithin(50)
                .of(getApproximateFeatureCountUsingReflection());
    }

    /* Return a ballpark estimate of the feature count using FEATURE_ field names. */
    private static int getApproximateFeatureCountUsingReflection() {
        return (int)
                Arrays.stream(PackageManager.class.getFields())
                        .filter(field -> Modifier.isStatic(field.getModifiers()))
                        .filter(field -> Modifier.isFinal(field.getModifiers()))
                        .filter(field -> field.getName().startsWith("FEATURE_"))
                        .count();
    }
}
