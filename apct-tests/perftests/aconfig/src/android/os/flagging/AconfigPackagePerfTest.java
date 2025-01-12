/*
 * Copyright 2024 The Android Open Source Project
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

package android.os.flagging;

import static com.google.common.truth.Truth.assertThat;

import android.aconfig.DeviceProtosTestUtil;
import android.aconfig.nano.Aconfig.parsed_flag;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RunWith(Parameterized.class)
public class AconfigPackagePerfTest {

    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Parameterized.Parameters(name = "isPlatform_{0}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] {{false}, {true}});
    }

    private static final Set<String> PLATFORM_CONTAINERS = Set.of("system", "vendor", "product");
    private static List<parsed_flag> sFlags;

    @BeforeClass
    public static void init() {
        try {
            sFlags = DeviceProtosTestUtil.loadAndParseFlagProtos();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // if this variable is true, then the test query flags from system/product/vendor
    // if this variable is false, then the test query flags from updatable partitions
    @Parameterized.Parameter(0)
    public boolean mIsPlatform;

    @Test
    public void timeAconfigPackageLoadOnePackage() {
        String packageName = "";
        for (parsed_flag flag : sFlags) {
            if (mIsPlatform == PLATFORM_CONTAINERS.contains(flag.container)) {
                packageName = flag.package_;
                break;
            }
        }
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            AconfigPackage.load(packageName);
        }
    }

    @Test
    public void timeAconfigPackageLoadMultiplePackages() {
        // load num packages
        int packageNum = 25;
        Set<String> packageSet = new HashSet<>();
        for (parsed_flag flag : sFlags) {
            if (mIsPlatform == PLATFORM_CONTAINERS.contains(flag.container)) {
                packageSet.add(flag.package_);
            }
            if (packageSet.size() >= packageNum) {
                break;
            }
        }
        List<String> packageList = new ArrayList(packageSet);
        assertThat(packageList.size()).isAtLeast(packageNum);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        for (int i = 0; state.keepRunning(); i++) {
            AconfigPackage.load(packageList.get(i % packageNum));
        }
    }

    @Test
    public void timeAconfigPackageGetBooleanFlagValue() {
        // get one package contains num of flags
        int flagNum = 20;
        List<parsed_flag> l = findNumFlagsInSamePackage(flagNum, mIsPlatform);
        List<String> flagName = new ArrayList<>();
        String packageName = l.get(0).package_;
        for (parsed_flag flag : l) {
            flagName.add(flag.name);
        }
        assertThat(flagName.size()).isAtLeast(flagNum);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        AconfigPackage ap = AconfigPackage.load(packageName);
        for (int i = 0; state.keepRunning(); i++) {
            ap.getBooleanFlagValue(flagName.get(i % flagNum), false);
        }
    }

    private static List<parsed_flag> findNumFlagsInSamePackage(int num, boolean isPlatform) {
        Map<String, List<parsed_flag>> packageToFlag = new HashMap<>();
        List<parsed_flag> ret = new ArrayList<parsed_flag>();
        for (parsed_flag flag : sFlags) {
            if (isPlatform == PLATFORM_CONTAINERS.contains(flag.container)) {
                ret =
                        packageToFlag.computeIfAbsent(
                                flag.package_, k -> new ArrayList<parsed_flag>());
                ret.add(flag);
                if (ret.size() >= num) {
                    break;
                }
            }
        }
        return ret;
    }
}
