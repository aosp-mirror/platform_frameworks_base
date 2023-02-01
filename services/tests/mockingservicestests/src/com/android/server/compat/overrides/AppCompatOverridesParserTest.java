/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.compat.overrides;

import static android.content.pm.PackageManager.CERT_INPUT_SHA256;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import static java.util.Collections.emptySet;

import android.app.compat.PackageOverride;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.platform.test.annotations.Presubmit;
import android.util.ArraySet;

import androidx.test.filters.SmallTest;

import libcore.util.HexEncoding;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;

/**
 * Test class for {@link AppCompatOverridesParser}.
 *
 * Build/Install/Run:
 * atest FrameworksMockingServicesTests:AppCompatOverridesParserTest
 */
@RunWith(MockitoJUnitRunner.class)
@SmallTest
@Presubmit
public class AppCompatOverridesParserTest {
    private static final String PACKAGE_1 = "com.android.test1";
    private static final String PACKAGE_2 = "com.android.test2";
    private static final String PACKAGE_3 = "com.android.test3";
    private static final String PACKAGE_4 = "com.android.test4";

    private AppCompatOverridesParser mParser;

    @Mock
    private PackageManager mPackageManager;

    @Before
    public void setUp() throws Exception {
        mParser = new AppCompatOverridesParser(mPackageManager);
    }

    @Test
    public void parseRemoveOverrides_emptyConfig_returnsEmpty() {
        Set<Long> ownedChangeIds = new ArraySet<>(Arrays.asList(123L, 456L));

        assertThat(mParser.parseRemoveOverrides("", ownedChangeIds)).isEmpty();
    }

    @Test
    public void parseRemoveOverrides_configHasWildcardNoOwnedChangeIds_returnsEmpty() {
        when(mPackageManager.getInstalledApplications(anyInt()))
                .thenReturn(Arrays.asList(createAppInfo(PACKAGE_1), createAppInfo(PACKAGE_2)));

        assertThat(mParser.parseRemoveOverrides("*", /* ownedChangeIds= */ emptySet())).isEmpty();
    }

    @Test
    public void parseRemoveOverrides_configHasWildcard_returnsAllInstalledPackagesToAllOwnedIds() {
        Set<Long> ownedChangeIds = new ArraySet<>(Arrays.asList(123L, 456L));
        when(mPackageManager.getInstalledApplications(anyInt()))
                .thenReturn(Arrays.asList(createAppInfo(PACKAGE_1), createAppInfo(PACKAGE_2),
                        createAppInfo(PACKAGE_3)));

        Map<String, Set<Long>> result = mParser.parseRemoveOverrides("*", ownedChangeIds);

        assertThat(result).hasSize(3);
        assertThat(result.get(PACKAGE_1)).containsExactly(123L, 456L);
        assertThat(result.get(PACKAGE_2)).containsExactly(123L, 456L);
        assertThat(result.get(PACKAGE_3)).containsExactly(123L, 456L);
    }

    @Test
    public void parseRemoveOverrides_configHasInvalidWildcardSymbol_returnsEmpty() {
        Set<Long> ownedChangeIds = new ArraySet<>(Arrays.asList(123L, 456L));
        when(mPackageManager.getInstalledApplications(anyInt())).thenReturn(
                Arrays.asList(createAppInfo(PACKAGE_1), createAppInfo(PACKAGE_2)));

        assertThat(mParser.parseRemoveOverrides("**", ownedChangeIds)).isEmpty();
    }

    @Test
    public void parseRemoveOverrides_configHasSingleEntry_returnsPackageToChangeIds() {
        Map<String, Set<Long>> result = mParser.parseRemoveOverrides(
                PACKAGE_1 + "=12:34", /* ownedChangeIds= */ emptySet());

        assertThat(result).hasSize(1);
        assertThat(result.get(PACKAGE_1)).containsExactly(12L, 34L);
    }

    @Test
    public void parseRemoveOverrides_configHasMultipleEntries_returnsPackagesToChangeIds() {
        Set<Long> ownedChangeIds = new ArraySet<>(Arrays.asList(12L, 34L, 56L, 78L));

        Map<String, Set<Long>> result = mParser.parseRemoveOverrides(
                PACKAGE_1 + "=12," + PACKAGE_2 + "=*," + PACKAGE_3 + "=12:56:78," + PACKAGE_4
                        + "=", ownedChangeIds);

        assertThat(result).hasSize(3);
        assertThat(result.get(PACKAGE_1)).containsExactly(12L);
        assertThat(result.get(PACKAGE_2)).containsExactly(12L, 34L, 56L, 78L);
        assertThat(result.get(PACKAGE_3)).containsExactly(12L, 56L, 78L);
    }

    @Test
    public void parseRemoveOverrides_configHasPackageWithWildcardNoOwnedId_returnsWithoutPackage() {
        Map<String, Set<Long>> result = mParser.parseRemoveOverrides(
                PACKAGE_1 + "=*," + PACKAGE_2 + "=12", /* ownedChangeIds= */ emptySet());

        assertThat(result).hasSize(1);
        assertThat(result.get(PACKAGE_2)).containsExactly(12L);
    }

    @Test
    public void parseRemoveOverrides_configHasInvalidKeyValueListFormat_returnsEmpty() {
        Set<Long> ownedChangeIds = new ArraySet<>(Arrays.asList(12L, 34L));

        assertThat(mParser.parseRemoveOverrides(
                PACKAGE_1 + "=12," + PACKAGE_2 + ">34", ownedChangeIds)).isEmpty();
    }


    @Test
    public void parseRemoveOverrides_configHasInvalidChangeIds_returnsWithoutInvalidChangeIds() {
        Map<String, Set<Long>> result = mParser.parseRemoveOverrides(
                PACKAGE_1 + "=12," + PACKAGE_2 + "=12:56L:78," + PACKAGE_3
                        + "=34L", /* ownedChangeIds= */ emptySet());

        assertThat(result).hasSize(2);
        assertThat(result.get(PACKAGE_1)).containsExactly(12L);
        assertThat(result.get(PACKAGE_2)).containsExactly(12L, 78L);
    }

    @Test
    public void parseOwnedChangeIds_emptyConfig_returnsEmpty() {
        assertThat(AppCompatOverridesParser.parseOwnedChangeIds("")).isEmpty();
    }

    @Test
    public void parseOwnedChangeIds_configHasSingleChangeId_returnsChangeId() {
        assertThat(AppCompatOverridesParser.parseOwnedChangeIds("123")).containsExactly(123L);
    }

    @Test
    public void parseOwnedChangeIds_configHasMultipleChangeIds_returnsChangeIds() {
        assertThat(AppCompatOverridesParser.parseOwnedChangeIds("12,34,56")).containsExactly(12L,
                34L, 56L);
    }

    @Test
    public void parseOwnedChangeIds_configHasInvalidChangeIds_returnsWithoutInvalidChangeIds() {
        // We add a valid entry before and after the invalid ones to make sure they are applied.
        assertThat(AppCompatOverridesParser.parseOwnedChangeIds("12,C34,56")).containsExactly(12L,
                56L);
    }

    @Test
    public void parsePackageOverrides_emptyConfigNoOwnedChangeIds_returnsEmpty() {
        Map<Long, PackageOverride> result = mParser.parsePackageOverrides(
                /* configStr= */ "", PACKAGE_1, /* versionCode= */ 0, /* changeIdsToSkip= */
                emptySet());

        assertThat(result).isEmpty();
    }

    @Test
    public void parsePackageOverrides_configWithSingleOverride_returnsOverride() {
        Map<Long, PackageOverride> result = mParser.parsePackageOverrides(
                /* configStr= */ "123:::true", PACKAGE_1, /* versionCode= */
                5, /* changeIdsToSkip= */
                emptySet());

        assertThat(result).hasSize(1);
        assertThat(result.get(123L)).isEqualTo(
                new PackageOverride.Builder().setEnabled(true).build());
    }

    @Test
    public void parsePackageOverrides_configWithMultipleOverrides_returnsOverrides() {
        Map<Long, PackageOverride> result = mParser.parsePackageOverrides(
                /* configStr= */ "910:3:4:false,78:10::false,12:::false,34:1:2:true,34:10::true,"
                        + "56::2:true,56:3:4:false,34:4:8:true,78:6:7:true,910:5::true,"
                        + "1112::5:true,56:6::true,1112:6:7:false", PACKAGE_1, /* versionCode= */
                5, /* changeIdsToSkip= */ emptySet());

        assertThat(result).hasSize(6);
        assertThat(result.get(12L)).isEqualTo(
                new PackageOverride.Builder().setEnabled(false).build());
        assertThat(result.get(34L)).isEqualTo(
                new PackageOverride.Builder().setMinVersionCode(4).setMaxVersionCode(8).setEnabled(
                        true).build());
        assertThat(result.get(56L)).isEqualTo(
                new PackageOverride.Builder().setMinVersionCode(3).setMaxVersionCode(4).setEnabled(
                        false).build());
        assertThat(result.get(78L)).isEqualTo(
                new PackageOverride.Builder().setMinVersionCode(6).setMaxVersionCode(7).setEnabled(
                        true).build());
        assertThat(result.get(910L)).isEqualTo(
                new PackageOverride.Builder().setMinVersionCode(5).setEnabled(true).build());
        assertThat(result.get(1112L)).isEqualTo(
                new PackageOverride.Builder().setMaxVersionCode(5).setEnabled(true).build());
    }

    @Test
    public void parsePackageOverrides_changeIdsToSkipSpecified_returnsWithoutChangeIdsToSkip() {
        ArraySet<Long> changeIdsToSkip = new ArraySet<>(Arrays.asList(34L, 56L));
        Map<Long, PackageOverride> result = mParser.parsePackageOverrides(
                /* configStr= */ "12:::true,56:3:7:true", PACKAGE_1, /* versionCode= */ 5,
                changeIdsToSkip);

        assertThat(result).hasSize(1);
        assertThat(result.get(12L)).isEqualTo(
                new PackageOverride.Builder().setEnabled(true).build());
    }

    @Test
    public void parsePackageOverrides_changeIdsToSkipContainsAllIds_returnsEmpty() {
        ArraySet<Long> changeIdsToSkip = new ArraySet<>(Arrays.asList(12L, 34L));
        Map<Long, PackageOverride> result = mParser.parsePackageOverrides(
                /* configStr= */ "12:::true", PACKAGE_1, /* versionCode= */ 5, changeIdsToSkip);

        assertThat(result).isEmpty();
    }

    @Test
    public void parsePackageOverrides_signatureInvalid() {
        when(mPackageManager.hasSigningCertificate(PACKAGE_1, HexEncoding.decode("aa"),
                CERT_INPUT_SHA256)).thenReturn(false);

        Map<Long, PackageOverride> result = mParser.parsePackageOverrides(
                /* configStr= */ "aa~12:::true,56:::true", PACKAGE_1,
                /* versionCode= */ 0, /* changeIdsToSkip= */ emptySet());

        assertThat(result).isEmpty();
    }

    @Test
    public void parsePackageOverrides_signatureInvalid_oddNumberOfCharacters() {
        // Valid hex encoding should always be an even number of characters.
        Map<Long, PackageOverride> result = mParser.parsePackageOverrides(
                /* configStr= */ "a~12:::true,56:::true", PACKAGE_1,
                /* versionCode= */ 0, /* changeIdsToSkip= */ emptySet());

        assertThat(result).isEmpty();
    }

    @Test
    public void parsePackageOverrides_signatureValid() {
        when(mPackageManager.hasSigningCertificate(PACKAGE_1, HexEncoding.decode("bb"),
                CERT_INPUT_SHA256)).thenReturn(true);

        Map<Long, PackageOverride> result = mParser.parsePackageOverrides(
                /* configStr= */ "bb~12:::true,56:::false", PACKAGE_1,
                /* versionCode= */ 0, /* changeIdsToSkip= */ emptySet());

        assertThat(result.keySet()).containsExactly(12L, 56L);
    }

    @Test
    public void parsePackageOverrides_emptySignature() {
        Map<Long, PackageOverride> result = mParser.parsePackageOverrides(
                /* configStr= */ "~12:::true,56:::false", PACKAGE_1,
                /* versionCode= */ 0, /* changeIdsToSkip= */ emptySet());

        assertThat(result.keySet()).containsExactly(12L, 56L);
    }

    @Test
    public void parsePackageOverrides_multipleSignatures() {
        when(mPackageManager.hasSigningCertificate(PACKAGE_1, HexEncoding.decode("aa"),
                CERT_INPUT_SHA256)).thenReturn(true);
        when(mPackageManager.hasSigningCertificate(PACKAGE_1, HexEncoding.decode("bb"),
                CERT_INPUT_SHA256)).thenReturn(true);

        Map<Long, PackageOverride> result = mParser.parsePackageOverrides(
                /* configStr= */ "aa~bb~12:::true,56:::false", PACKAGE_1,
                /* versionCode= */0, /* changeIdsToSkip= */ emptySet());

        assertThat(result).isEmpty();
    }

    @Test
    public void parsePackageOverrides_signatureOnly() {
        when(mPackageManager.hasSigningCertificate(PACKAGE_1, HexEncoding.decode("aa"),
                CERT_INPUT_SHA256)).thenReturn(true);

        Map<Long, PackageOverride> result = mParser.parsePackageOverrides(
                /* configStr= */ "aa~", PACKAGE_1,
                /* versionCode= */ 0, /* changeIdsToSkip= */ emptySet());

        assertThat(result).isEmpty();
    }

    @Test
    public void parsePackageOverrides_someOverridesAreInvalid_returnsWithoutInvalidOverrides() {
        // We add a valid entry before and after the invalid ones to make sure they are applied.
        Map<Long, PackageOverride> result = mParser.parsePackageOverrides(
                /* configStr= */ "12:::True,56:1:2:FALSE,56:3:true,78:4:8:true:,C1:::true,910:::no,"
                        + "1112:1:ten:true,1112:one:10:true,,1314:7:3:false,34:::", PACKAGE_1,
                /* versionCode= */ 5, /* changeIdsToSkip= */ emptySet());

        assertThat(result).hasSize(2);
        assertThat(result.get(12L)).isEqualTo(
                new PackageOverride.Builder().setEnabled(true).build());
        assertThat(result.get(56L)).isEqualTo(
                new PackageOverride.Builder().setMinVersionCode(1).setMaxVersionCode(2).setEnabled(
                        false).build());
    }

    private static ApplicationInfo createAppInfo(String packageName) {
        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.packageName = packageName;
        return appInfo;
    }
}
