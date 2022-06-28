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

package com.android.server.integrity;

import static com.android.server.integrity.model.IndexingFileConstants.INDEXING_BLOCK_SIZE;

import static com.google.common.truth.Truth.assertThat;

import android.content.integrity.AppInstallMetadata;
import android.content.integrity.AtomicFormula;
import android.content.integrity.AtomicFormula.LongAtomicFormula;
import android.content.integrity.AtomicFormula.StringAtomicFormula;
import android.content.integrity.CompoundFormula;
import android.content.integrity.Rule;
import android.util.Slog;

import com.android.server.integrity.parser.RuleBinaryParser;
import com.android.server.integrity.serializer.RuleBinarySerializer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Unit test for {@link IntegrityFileManager} */
@RunWith(JUnit4.class)
public class IntegrityFileManagerTest {
    private static final String TAG = "IntegrityFileManagerTest";

    private static final String VERSION = "version";
    private static final String RULE_PROVIDER = "rule_provider";

    private File mTmpDir;

    // under test
    private IntegrityFileManager mIntegrityFileManager;

    @Before
    public void setUp() throws Exception {
        mTmpDir = Files.createTempDirectory("IntegrityFileManagerTest").toFile();
        Slog.i(TAG, "Using temp directory " + mTmpDir);

        // Use Xml Parser/Serializer to help with debugging since we can just print the file.
        mIntegrityFileManager =
                new IntegrityFileManager(
                        new RuleBinaryParser(), new RuleBinarySerializer(), mTmpDir);
        Files.walk(mTmpDir.toPath())
                .forEach(
                        path -> {
                            Slog.i(TAG, "before " + path);
                        });
    }

    @After
    public void tearDown() throws Exception {
        Files.walk(mTmpDir.toPath())
                .forEach(
                        path -> {
                            Slog.i(TAG, "after " + path);
                        });
        // Sorting paths in reverse order guarantees that we delete inside files before deleting
        // directory.
        Files.walk(mTmpDir.toPath())
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
    }

    @Test
    public void testGetMetadata() throws Exception {
        assertThat(mIntegrityFileManager.readMetadata()).isNull();
        mIntegrityFileManager.writeRules(VERSION, RULE_PROVIDER, Collections.EMPTY_LIST);

        assertThat(mIntegrityFileManager.readMetadata()).isNotNull();
        assertThat(mIntegrityFileManager.readMetadata().getVersion()).isEqualTo(VERSION);
        assertThat(mIntegrityFileManager.readMetadata().getRuleProvider()).isEqualTo(RULE_PROVIDER);
    }

    @Test
    public void testIsInitialized() throws Exception {
        assertThat(mIntegrityFileManager.initialized()).isFalse();
        mIntegrityFileManager.writeRules(VERSION, RULE_PROVIDER, Collections.EMPTY_LIST);
        assertThat(mIntegrityFileManager.initialized()).isTrue();
    }

    @Test
    public void testGetRules() throws Exception {
        String packageName = "package";
        String packageCert = "cert";
        int version = 123;
        Rule packageNameRule = getPackageNameIndexedRule(packageName);
        Rule packageCertRule = getAppCertificateIndexedRule(packageCert);
        Rule versionCodeRule =
                new Rule(
                        new LongAtomicFormula(
                                AtomicFormula.VERSION_CODE, AtomicFormula.EQ, version),
                        Rule.DENY);
        Rule randomRule =
                new Rule(
                        new CompoundFormula(
                                CompoundFormula.OR,
                                Arrays.asList(
                                        new StringAtomicFormula(
                                                AtomicFormula.PACKAGE_NAME,
                                                "abc",
                                                /* isHashedValue= */ false),
                                        new LongAtomicFormula(
                                                AtomicFormula.VERSION_CODE,
                                                AtomicFormula.EQ,
                                                version))),
                        Rule.DENY);

        List<Rule> rules =
                Arrays.asList(packageNameRule, packageCertRule, versionCodeRule, randomRule);
        mIntegrityFileManager.writeRules(VERSION, RULE_PROVIDER, rules);

        AppInstallMetadata appInstallMetadata =
                new AppInstallMetadata.Builder()
                        .setPackageName(packageName)
                        .setAppCertificates(Collections.singletonList(packageCert))
                        .setAppCertificateLineage(Collections.singletonList(packageCert))
                        .setVersionCode(version)
                        .setInstallerName("abc")
                        .setInstallerCertificates(Collections.singletonList("abc"))
                        .setIsPreInstalled(true)
                        .build();
        List<Rule> rulesFetched = mIntegrityFileManager.readRules(appInstallMetadata);

        assertThat(rulesFetched)
                .containsExactly(packageNameRule, packageCertRule, versionCodeRule, randomRule);
    }

    @Test
    public void testGetRules_indexedForManyRules() throws Exception {
        String packageName = "package";
        String installerName = "installer";
        String appCertificate = "cert";

        // Create a rule set with 2500 package name indexed, 2500 app certificate indexed and
        // 500 unindexed rules.
        List<Rule> rules = new ArrayList<>();
        int unindexedRuleCount = 70;

        for (int i = 0; i < 2500; i++) {
            rules.add(getPackageNameIndexedRule(String.format("%s%04d", packageName, i)));
            rules.add(getAppCertificateIndexedRule(String.format("%s%04d", appCertificate, i)));
        }

        for (int i = 0; i < unindexedRuleCount; i++) {
            rules.add(getInstallerCertificateRule(String.format("%s%04d", installerName, i)));
        }

        // Write the rules and get them indexed.
        mIntegrityFileManager.writeRules(VERSION, RULE_PROVIDER, rules);

        // Read the rules for a specific rule.
        String installedPackageName = String.format("%s%04d", packageName, 264);
        String installedAppCertificate = String.format("%s%04d", appCertificate, 1264);
        AppInstallMetadata appInstallMetadata =
                new AppInstallMetadata.Builder()
                        .setPackageName(installedPackageName)
                        .setAppCertificates(Collections.singletonList(installedAppCertificate))
                        .setAppCertificateLineage(
                                Collections.singletonList(installedAppCertificate))
                        .setVersionCode(250)
                        .setInstallerName("abc")
                        .setInstallerCertificates(Collections.singletonList("abc"))
                        .setIsPreInstalled(true)
                        .build();
        List<Rule> rulesFetched = mIntegrityFileManager.readRules(appInstallMetadata);

        // Verify that we do not load all the rules and we have the necessary rules to evaluate.
        assertThat(rulesFetched.size())
                .isEqualTo(INDEXING_BLOCK_SIZE * 2 + unindexedRuleCount);
        assertThat(rulesFetched)
                .containsAtLeast(
                        getPackageNameIndexedRule(installedPackageName),
                        getAppCertificateIndexedRule(installedAppCertificate));
    }

    private Rule getPackageNameIndexedRule(String packageName) {
        return new Rule(
                new StringAtomicFormula(
                        AtomicFormula.PACKAGE_NAME, packageName, /* isHashedValue= */false),
                Rule.DENY);
    }

    private Rule getAppCertificateIndexedRule(String appCertificate) {
        return new Rule(
                new StringAtomicFormula(
                        AtomicFormula.APP_CERTIFICATE,
                        appCertificate, /* isHashedValue= */ false),
                Rule.DENY);
    }

    private Rule getInstallerCertificateRule(String installerCert) {
        return new Rule(
                new StringAtomicFormula(
                        AtomicFormula.INSTALLER_NAME, installerCert, /* isHashedValue= */false),
                Rule.DENY);
    }

    @Test
    public void testStagingDirectoryCleared() throws Exception {
        // We must push rules two times to ensure that staging directory is empty because we cleared
        // it, rather than because original rules directory is empty.
        mIntegrityFileManager.writeRules(VERSION, RULE_PROVIDER, Collections.EMPTY_LIST);
        mIntegrityFileManager.writeRules(VERSION, RULE_PROVIDER, Collections.EMPTY_LIST);

        assertStagingDirectoryCleared();
    }

    private void assertStagingDirectoryCleared() {
        File stagingDir = new File(mTmpDir, "integrity_staging");
        assertThat(stagingDir.exists()).isTrue();
        assertThat(stagingDir.isDirectory()).isTrue();
        assertThat(stagingDir.listFiles()).isEmpty();
    }
}
