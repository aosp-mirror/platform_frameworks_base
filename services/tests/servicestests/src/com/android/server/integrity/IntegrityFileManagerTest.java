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

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.Assert.assertThat;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import android.content.integrity.AppInstallMetadata;
import android.content.integrity.AtomicFormula;
import android.content.integrity.AtomicFormula.IntAtomicFormula;
import android.content.integrity.AtomicFormula.StringAtomicFormula;
import android.content.integrity.CompoundFormula;
import android.content.integrity.Rule;
import android.util.Slog;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.integrity.parser.RuleXmlParser;
import com.android.server.integrity.serializer.RuleXmlSerializer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Unit test for {@link IntegrityFileManager} */
@RunWith(AndroidJUnit4.class)
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
                        new RuleXmlParser(), new RuleXmlSerializer(), mTmpDir);
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
        assertNull(mIntegrityFileManager.readMetadata());
        mIntegrityFileManager.writeRules(VERSION, RULE_PROVIDER, Collections.EMPTY_LIST);

        assertNotNull(mIntegrityFileManager.readMetadata());
        assertEquals(VERSION, mIntegrityFileManager.readMetadata().getVersion());
        assertEquals(RULE_PROVIDER, mIntegrityFileManager.readMetadata().getRuleProvider());
    }

    @Test
    public void testGetRules() throws Exception {
        String packageName = "package";
        String packageCert = "cert";
        int version = 123;
        Rule packageNameRule =
                new Rule(
                        new StringAtomicFormula(
                                AtomicFormula.PACKAGE_NAME,
                                packageName,
                                /* isHashedValue= */ false),
                        Rule.DENY);
        Rule packageCertRule =
                new Rule(
                        new StringAtomicFormula(
                                AtomicFormula.APP_CERTIFICATE,
                                packageCert,
                                /* isHashedValue= */ false),
                        Rule.DENY);
        Rule versionCodeRule =
                new Rule(
                        new IntAtomicFormula(AtomicFormula.VERSION_CODE, AtomicFormula.LE, version),
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
                                        new IntAtomicFormula(
                                                AtomicFormula.VERSION_CODE,
                                                AtomicFormula.LE,
                                                version))),
                        Rule.DENY);
        // We will test the specifics of indexing in other classes. Here, we just require that all
        // rules that are related to the given AppInstallMetadata are returned and do not assert
        // anything on other rules.
        List<Rule> rules =
                Arrays.asList(packageNameRule, packageCertRule, versionCodeRule, randomRule);
        mIntegrityFileManager.writeRules(VERSION, RULE_PROVIDER, rules);

        AppInstallMetadata appInstallMetadata = new AppInstallMetadata.Builder()
                .setPackageName(packageName)
                .setAppCertificate(packageCert)
                .setVersionCode(version)
                .setInstallerName("abc")
                .setInstallerCertificate("abc")
                .setIsPreInstalled(true)
                .build();
        List<Rule> rulesFetched = mIntegrityFileManager.readRules(appInstallMetadata);

        assertThat(rulesFetched, hasItems(
                equalTo(packageNameRule),
                equalTo(packageCertRule),
                equalTo(versionCodeRule)
        ));
    }

    @Test
    public void testIsInitialized() throws Exception {
        assertFalse(mIntegrityFileManager.initialized());
        mIntegrityFileManager.writeRules(VERSION, RULE_PROVIDER, Collections.EMPTY_LIST);
        assertTrue(mIntegrityFileManager.initialized());
    }
}
