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

package android.transparency.test.app;

import static android.Manifest.permission.INTERACT_ACROSS_USERS_FULL;
import static android.Manifest.permission.QUERY_ALL_PACKAGES;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.content.Context;
import android.os.Bundle;
import android.transparency.BinaryTransparencyManager;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.compatibility.common.util.ShellIdentityUtils;
import com.android.internal.os.IBinaryTransparencyService.AppInfo;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Collectors;

@RunWith(AndroidJUnit4.class)
public class BinaryTransparencyTest {
    private static final String TAG = "BinaryTransparencyTest";

    private BinaryTransparencyManager mBt;

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        mBt = context.getSystemService(BinaryTransparencyManager.class);
    }

    @Test
    public void testCollectAllApexInfo() {
        // Prepare the expectation received from host's shell command
        Bundle args = InstrumentationRegistry.getArguments();
        assertThat(args).isNotNull();
        int number = Integer.valueOf(args.getString("apex-number"));
        assertThat(number).isGreaterThan(0);
        var expectedApexNames = new ArrayList<String>();
        for (var i = 0; i < number; i++) {
            String moduleName = args.getString("apex-" + Integer.toString(i));
            expectedApexNames.add(moduleName);
        }
        assertThat(expectedApexNames).containsNoDuplicates();

        // Action
        var apexInfoList = mBt.collectAllApexInfo(/* includeTestOnly */ true);

        // Verify actual apex names
        var actualApexesNames = apexInfoList.stream().map((apex) -> apex.moduleName)
                .collect(Collectors.toList());
        assertThat(actualApexesNames).containsExactlyElementsIn(expectedApexNames);

        // Perform more valitidy checks
        var digestsSeen = new HashSet<String>();
        var hexFormatter = HexFormat.of();
        for (var apex : apexInfoList) {
            Log.d(TAG, "Verifying " + apex.packageName + " / " + apex.moduleName);

            assertThat(apex.longVersion).isGreaterThan(0);
            assertThat(apex.digestAlgorithm).isGreaterThan(0);
            assertThat(apex.signerDigests).asList().containsNoneOf(null, "");

            assertThat(apex.digest).isNotNull();
            String digestHex = hexFormatter.formatHex(apex.digest);
            boolean isNew = digestsSeen.add(digestHex);
            assertWithMessage(
                    "Digest should be unique, but received a dup: " + digestHex)
                    .that(isNew).isTrue();
        }
    }

    @Test
    public void testCollectAllUpdatedPreloadInfo() {
        var preloadInfoList = mBt.collectAllUpdatedPreloadInfo(new Bundle());
        assertThat(preloadInfoList).isNotEmpty();  // because we just installed from the host side
        AppInfo updatedPreload = null;
        for (var preload : preloadInfoList) {
            Log.d(TAG, "Received " + preload.packageName);
            if (preload.packageName.equals("com.android.egg")) {
                assertWithMessage("Received the same package").that(updatedPreload).isNull();
                updatedPreload = preload;
            }
        }

        // Verify
        assertThat(updatedPreload.longVersion).isGreaterThan(0);
        assertThat(updatedPreload.digestAlgorithm).isGreaterThan(0);
        assertThat(updatedPreload.digest).isNotEmpty();
        assertThat(updatedPreload.mbaStatus).isEqualTo(/* MBA_STATUS_UPDATED_PRELOAD */ 2);
        assertThat(updatedPreload.signerDigests).asList().containsNoneOf(null, "");
    }

    @Test
    public void testCollectAllSilentInstalledMbaInfo() {
        // Action
        var appInfoList =
                ShellIdentityUtils.invokeMethodWithShellPermissions(
                        mBt,
                        (Bt) ->
                                mBt.collectAllSilentInstalledMbaInfo(new Bundle()),
                        QUERY_ALL_PACKAGES,
                        INTERACT_ACROSS_USERS_FULL);

        // Verify
        assertThat(appInfoList).isNotEmpty();  // because we just installed from the host side

        var expectedAppNames = Set.of("com.android.test.split.feature", "com.android.egg");
        var actualAppNames = appInfoList.stream().map((appInfo) -> appInfo.packageName)
                .collect(Collectors.toList());
        assertThat(actualAppNames).containsAtLeastElementsIn(expectedAppNames);

        var actualSplitNames = new ArrayList<String>();
        for (var appInfo : appInfoList) {
            Log.d(TAG, "Received " + appInfo.packageName + " as a silent install");
            if (expectedAppNames.contains(appInfo.packageName)) {
                assertThat(appInfo.longVersion).isGreaterThan(0);
                assertThat(appInfo.digestAlgorithm).isGreaterThan(0);
                assertThat(appInfo.digest).isNotEmpty();
                assertThat(appInfo.mbaStatus).isEqualTo(/* MBA_STATUS_NEW_INSTALL */ 3);
                assertThat(appInfo.signerDigests).asList().containsNoneOf(null, "");

                if (appInfo.splitName != null) {
                    actualSplitNames.add(appInfo.splitName);
                }
            }
        }
        assertThat(actualSplitNames).containsExactly("feature1");  // Name of FeatureSplit1
    }
}
