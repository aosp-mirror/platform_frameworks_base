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

package com.android.server.integrity.serializer;

import static com.android.server.integrity.model.ComponentBitSize.ATOMIC_FORMULA_START;
import static com.android.server.integrity.model.ComponentBitSize.COMPOUND_FORMULA_END;
import static com.android.server.integrity.model.ComponentBitSize.COMPOUND_FORMULA_START;
import static com.android.server.integrity.model.ComponentBitSize.CONNECTOR_BITS;
import static com.android.server.integrity.model.ComponentBitSize.DEFAULT_FORMAT_VERSION;
import static com.android.server.integrity.model.ComponentBitSize.EFFECT_BITS;
import static com.android.server.integrity.model.ComponentBitSize.FORMAT_VERSION_BITS;
import static com.android.server.integrity.model.ComponentBitSize.KEY_BITS;
import static com.android.server.integrity.model.ComponentBitSize.OPERATOR_BITS;
import static com.android.server.integrity.model.ComponentBitSize.SEPARATOR_BITS;
import static com.android.server.integrity.model.ComponentBitSize.VALUE_SIZE_BITS;
import static com.android.server.integrity.model.IndexingFileConstants.END_INDEXING_KEY;
import static com.android.server.integrity.model.IndexingFileConstants.INDEXING_BLOCK_SIZE;
import static com.android.server.integrity.model.IndexingFileConstants.START_INDEXING_KEY;
import static com.android.server.integrity.serializer.RuleBinarySerializer.INDEXED_RULE_SIZE_LIMIT;
import static com.android.server.integrity.serializer.RuleBinarySerializer.NONINDEXED_RULE_SIZE_LIMIT;
import static com.android.server.integrity.utils.TestUtils.getBits;
import static com.android.server.integrity.utils.TestUtils.getBytes;
import static com.android.server.integrity.utils.TestUtils.getValueBits;
import static com.android.server.testutils.TestUtils.assertExpectException;

import static com.google.common.truth.Truth.assertThat;

import android.content.integrity.AppInstallMetadata;
import android.content.integrity.AtomicFormula;
import android.content.integrity.CompoundFormula;
import android.content.integrity.IntegrityFormula;
import android.content.integrity.IntegrityUtils;
import android.content.integrity.Rule;

import androidx.annotation.NonNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@RunWith(JUnit4.class)
public class RuleBinarySerializerTest {

    private static final String SAMPLE_INSTALLER_NAME = "com.test.installer";
    private static final String SAMPLE_INSTALLER_CERT = "installer_cert";

    private static final String COMPOUND_FORMULA_START_BITS =
            getBits(COMPOUND_FORMULA_START, SEPARATOR_BITS);
    private static final String COMPOUND_FORMULA_END_BITS =
            getBits(COMPOUND_FORMULA_END, SEPARATOR_BITS);
    private static final String ATOMIC_FORMULA_START_BITS =
            getBits(ATOMIC_FORMULA_START, SEPARATOR_BITS);

    private static final String NOT = getBits(CompoundFormula.NOT, CONNECTOR_BITS);
    private static final String AND = getBits(CompoundFormula.AND, CONNECTOR_BITS);
    private static final String OR = getBits(CompoundFormula.OR, CONNECTOR_BITS);

    private static final String PACKAGE_NAME = getBits(AtomicFormula.PACKAGE_NAME, KEY_BITS);
    private static final String APP_CERTIFICATE = getBits(AtomicFormula.APP_CERTIFICATE, KEY_BITS);
    private static final String INSTALLER_NAME = getBits(AtomicFormula.INSTALLER_NAME, KEY_BITS);
    private static final String INSTALLER_CERTIFICATE =
            getBits(AtomicFormula.INSTALLER_CERTIFICATE, KEY_BITS);
    private static final String VERSION_CODE = getBits(AtomicFormula.VERSION_CODE, KEY_BITS);
    private static final String PRE_INSTALLED = getBits(AtomicFormula.PRE_INSTALLED, KEY_BITS);

    private static final String EQ = getBits(AtomicFormula.EQ, OPERATOR_BITS);

    private static final String IS_NOT_HASHED = "0";
    private static final String IS_HASHED = "1";

    private static final String DENY = getBits(Rule.DENY, EFFECT_BITS);

    private static final String START_BIT = "1";
    private static final String END_BIT = "1";

    private static final byte[] DEFAULT_FORMAT_VERSION_BYTES =
            getBytes(getBits(DEFAULT_FORMAT_VERSION, FORMAT_VERSION_BITS));

    private static final String SERIALIZED_START_INDEXING_KEY =
            IS_NOT_HASHED
                    + getBits(START_INDEXING_KEY.length(), VALUE_SIZE_BITS)
                    + getValueBits(START_INDEXING_KEY);
    private static final String SERIALIZED_END_INDEXING_KEY =
            IS_NOT_HASHED
                    + getBits(END_INDEXING_KEY.length(), VALUE_SIZE_BITS)
                    + getValueBits(END_INDEXING_KEY);

    @Test
    public void testBinaryString_serializeNullRules() {
        RuleSerializer binarySerializer = new RuleBinarySerializer();

        assertExpectException(
                RuleSerializeException.class,
                /* expectedExceptionMessageRegex= */ "Null rules cannot be serialized.",
                () -> binarySerializer.serialize(null, /* formatVersion= */ Optional.empty()));
    }

    @Test
    public void testBinaryString_emptyRules() throws Exception {
        ByteArrayOutputStream ruleOutputStream = new ByteArrayOutputStream();
        ByteArrayOutputStream indexingOutputStream = new ByteArrayOutputStream();
        RuleSerializer binarySerializer = new RuleBinarySerializer();

        binarySerializer.serialize(
                Collections.emptyList(),
                /* formatVersion= */ Optional.empty(),
                ruleOutputStream,
                indexingOutputStream);

        ByteArrayOutputStream expectedRuleOutputStream = new ByteArrayOutputStream();
        expectedRuleOutputStream.write(DEFAULT_FORMAT_VERSION_BYTES);
        assertThat(ruleOutputStream.toByteArray())
                .isEqualTo(expectedRuleOutputStream.toByteArray());

        ByteArrayOutputStream expectedIndexingOutputStream = new ByteArrayOutputStream();
        String serializedIndexingBytes =
                SERIALIZED_START_INDEXING_KEY
                        + getBits(DEFAULT_FORMAT_VERSION_BYTES.length, /* numOfBits= */ 32)
                        + SERIALIZED_END_INDEXING_KEY
                        + getBits(DEFAULT_FORMAT_VERSION_BYTES.length, /* numOfBits= */ 32);
        byte[] expectedIndexingBytes =
                getBytes(
                        serializedIndexingBytes
                                + serializedIndexingBytes
                                + serializedIndexingBytes);
        expectedIndexingOutputStream.write(expectedIndexingBytes);
        assertThat(indexingOutputStream.toByteArray())
                .isEqualTo(expectedIndexingOutputStream.toByteArray());
    }

    @Test
    public void testBinaryStream_serializeValidCompoundFormula() throws Exception {
        String packageName = "com.test.app";
        Rule rule =
                new Rule(
                        new CompoundFormula(
                                CompoundFormula.NOT,
                                Collections.singletonList(
                                        new AtomicFormula.StringAtomicFormula(
                                                AtomicFormula.PACKAGE_NAME,
                                                packageName,
                                                /* isHashedValue= */ false))),
                        Rule.DENY);

        ByteArrayOutputStream ruleOutputStream = new ByteArrayOutputStream();
        ByteArrayOutputStream indexingOutputStream = new ByteArrayOutputStream();
        RuleSerializer binarySerializer = new RuleBinarySerializer();
        binarySerializer.serialize(
                Collections.singletonList(rule),
                /* formatVersion= */ Optional.empty(),
                ruleOutputStream,
                indexingOutputStream);

        String expectedBits =
                START_BIT
                        + COMPOUND_FORMULA_START_BITS
                        + NOT
                        + ATOMIC_FORMULA_START_BITS
                        + PACKAGE_NAME
                        + EQ
                        + IS_NOT_HASHED
                        + getBits(packageName.length(), VALUE_SIZE_BITS)
                        + getValueBits(packageName)
                        + COMPOUND_FORMULA_END_BITS
                        + DENY
                        + END_BIT;
        ByteArrayOutputStream expectedRuleOutputStream = new ByteArrayOutputStream();
        expectedRuleOutputStream.write(DEFAULT_FORMAT_VERSION_BYTES);
        expectedRuleOutputStream.write(getBytes(expectedBits));
        assertThat(ruleOutputStream.toByteArray())
                .isEqualTo(expectedRuleOutputStream.toByteArray());

        ByteArrayOutputStream expectedIndexingOutputStream = new ByteArrayOutputStream();
        String expectedIndexingBitsForIndexed =
                SERIALIZED_START_INDEXING_KEY
                        + getBits(DEFAULT_FORMAT_VERSION_BYTES.length, /* numOfBits= */ 32)
                        + SERIALIZED_END_INDEXING_KEY
                        + getBits(DEFAULT_FORMAT_VERSION_BYTES.length, /* numOfBits= */ 32);
        String expectedIndexingBitsForUnindexed =
                SERIALIZED_START_INDEXING_KEY
                        + getBits(DEFAULT_FORMAT_VERSION_BYTES.length, /* numOfBits= */ 32)
                        + SERIALIZED_END_INDEXING_KEY
                        + getBits(
                                DEFAULT_FORMAT_VERSION_BYTES.length + getBytes(expectedBits).length,
                                /* numOfBits= */ 32);
        expectedIndexingOutputStream.write(
                getBytes(
                        expectedIndexingBitsForIndexed
                                + expectedIndexingBitsForIndexed
                                + expectedIndexingBitsForUnindexed));

        assertThat(indexingOutputStream.toByteArray())
                .isEqualTo(expectedIndexingOutputStream.toByteArray());
    }

    @Test
    public void testBinaryString_serializeValidCompoundFormula_notConnector() throws Exception {
        String packageName = "com.test.app";
        Rule rule =
                new Rule(
                        new CompoundFormula(
                                CompoundFormula.NOT,
                                Collections.singletonList(
                                        new AtomicFormula.StringAtomicFormula(
                                                AtomicFormula.PACKAGE_NAME,
                                                packageName,
                                                /* isHashedValue= */ false))),
                        Rule.DENY);
        RuleSerializer binarySerializer = new RuleBinarySerializer();
        String expectedBits =
                START_BIT
                        + COMPOUND_FORMULA_START_BITS
                        + NOT
                        + ATOMIC_FORMULA_START_BITS
                        + PACKAGE_NAME
                        + EQ
                        + IS_NOT_HASHED
                        + getBits(packageName.length(), VALUE_SIZE_BITS)
                        + getValueBits(packageName)
                        + COMPOUND_FORMULA_END_BITS
                        + DENY
                        + END_BIT;
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        byteArrayOutputStream.write(DEFAULT_FORMAT_VERSION_BYTES);
        byteArrayOutputStream.write(getBytes(expectedBits));
        byte[] expectedRules = byteArrayOutputStream.toByteArray();

        byte[] actualRules =
                binarySerializer.serialize(
                        Collections.singletonList(rule), /* formatVersion= */ Optional.empty());

        assertThat(actualRules).isEqualTo(expectedRules);
    }

    @Test
    public void testBinaryString_serializeValidCompoundFormula_andConnector() throws Exception {
        String packageName = "com.test.app";
        String appCertificate = "test_cert";
        Rule rule =
                new Rule(
                        new CompoundFormula(
                                CompoundFormula.AND,
                                Arrays.asList(
                                        new AtomicFormula.StringAtomicFormula(
                                                AtomicFormula.PACKAGE_NAME,
                                                packageName,
                                                /* isHashedValue= */ false),
                                        new AtomicFormula.StringAtomicFormula(
                                                AtomicFormula.APP_CERTIFICATE,
                                                appCertificate,
                                                /* isHashedValue= */ false))),
                        Rule.DENY);
        RuleSerializer binarySerializer = new RuleBinarySerializer();
        String expectedBits =
                START_BIT
                        + COMPOUND_FORMULA_START_BITS
                        + AND
                        + ATOMIC_FORMULA_START_BITS
                        + PACKAGE_NAME
                        + EQ
                        + IS_NOT_HASHED
                        + getBits(packageName.length(), VALUE_SIZE_BITS)
                        + getValueBits(packageName)
                        + ATOMIC_FORMULA_START_BITS
                        + APP_CERTIFICATE
                        + EQ
                        + IS_NOT_HASHED
                        + getBits(appCertificate.length(), VALUE_SIZE_BITS)
                        + getValueBits(appCertificate)
                        + COMPOUND_FORMULA_END_BITS
                        + DENY
                        + END_BIT;
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        byteArrayOutputStream.write(DEFAULT_FORMAT_VERSION_BYTES);
        byteArrayOutputStream.write(getBytes(expectedBits));
        byte[] expectedRules = byteArrayOutputStream.toByteArray();

        byte[] actualRules =
                binarySerializer.serialize(
                        Collections.singletonList(rule), /* formatVersion= */ Optional.empty());

        assertThat(actualRules).isEqualTo(expectedRules);
    }

    @Test
    public void testBinaryString_serializeValidCompoundFormula_orConnector() throws Exception {
        String packageName = "com.test.app";
        String appCertificate = "test_cert";
        Rule rule =
                new Rule(
                        new CompoundFormula(
                                CompoundFormula.OR,
                                Arrays.asList(
                                        new AtomicFormula.StringAtomicFormula(
                                                AtomicFormula.PACKAGE_NAME,
                                                packageName,
                                                /* isHashedValue= */ false),
                                        new AtomicFormula.StringAtomicFormula(
                                                AtomicFormula.APP_CERTIFICATE,
                                                appCertificate,
                                                /* isHashedValue= */ false))),
                        Rule.DENY);
        RuleSerializer binarySerializer = new RuleBinarySerializer();
        String expectedBits =
                START_BIT
                        + COMPOUND_FORMULA_START_BITS
                        + OR
                        + ATOMIC_FORMULA_START_BITS
                        + PACKAGE_NAME
                        + EQ
                        + IS_NOT_HASHED
                        + getBits(packageName.length(), VALUE_SIZE_BITS)
                        + getValueBits(packageName)
                        + ATOMIC_FORMULA_START_BITS
                        + APP_CERTIFICATE
                        + EQ
                        + IS_NOT_HASHED
                        + getBits(appCertificate.length(), VALUE_SIZE_BITS)
                        + getValueBits(appCertificate)
                        + COMPOUND_FORMULA_END_BITS
                        + DENY
                        + END_BIT;
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        byteArrayOutputStream.write(DEFAULT_FORMAT_VERSION_BYTES);
        byteArrayOutputStream.write(getBytes(expectedBits));
        byte[] expectedRules = byteArrayOutputStream.toByteArray();

        byte[] actualRules =
                binarySerializer.serialize(
                        Collections.singletonList(rule), /* formatVersion= */ Optional.empty());

        assertThat(actualRules).isEqualTo(expectedRules);
    }

    @Test
    public void testBinaryString_serializeValidAtomicFormula_stringValue() throws Exception {
        String packageName = "com.test.app";
        Rule rule =
                new Rule(
                        new AtomicFormula.StringAtomicFormula(
                                AtomicFormula.PACKAGE_NAME,
                                packageName,
                                /* isHashedValue= */ false),
                        Rule.DENY);
        RuleSerializer binarySerializer = new RuleBinarySerializer();
        String expectedBits =
                START_BIT
                        + ATOMIC_FORMULA_START_BITS
                        + PACKAGE_NAME
                        + EQ
                        + IS_NOT_HASHED
                        + getBits(packageName.length(), VALUE_SIZE_BITS)
                        + getValueBits(packageName)
                        + DENY
                        + END_BIT;
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        byteArrayOutputStream.write(DEFAULT_FORMAT_VERSION_BYTES);
        byteArrayOutputStream.write(getBytes(expectedBits));
        byte[] expectedRules = byteArrayOutputStream.toByteArray();

        byte[] actualRules =
                binarySerializer.serialize(
                        Collections.singletonList(rule), /* formatVersion= */ Optional.empty());

        assertThat(actualRules).isEqualTo(expectedRules);
    }

    @Test
    public void testBinaryString_serializeValidAtomicFormula_hashedValue() throws Exception {
        String appCertificate = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        Rule rule =
                new Rule(
                        new AtomicFormula.StringAtomicFormula(
                                AtomicFormula.APP_CERTIFICATE,
                                IntegrityUtils.getHexDigest(
                                        appCertificate.getBytes(StandardCharsets.UTF_8)),
                                /* isHashedValue= */ true),
                        Rule.DENY);
        RuleSerializer binarySerializer = new RuleBinarySerializer();
        String expectedBits =
                START_BIT
                        + ATOMIC_FORMULA_START_BITS
                        + APP_CERTIFICATE
                        + EQ
                        + IS_HASHED
                        + getBits(appCertificate.length(), VALUE_SIZE_BITS)
                        + getValueBits(appCertificate)
                        + DENY
                        + END_BIT;
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        byteArrayOutputStream.write(DEFAULT_FORMAT_VERSION_BYTES);
        byteArrayOutputStream.write(getBytes(expectedBits));
        byte[] expectedRules = byteArrayOutputStream.toByteArray();

        byte[] actualRules =
                binarySerializer.serialize(
                        Collections.singletonList(rule), /* formatVersion= */ Optional.empty());

        assertThat(actualRules).isEqualTo(expectedRules);
    }

    @Test
    public void testBinaryString_serializeValidAtomicFormula_integerValue() throws Exception {
        long versionCode = 1;
        Rule rule =
                new Rule(
                        new AtomicFormula.LongAtomicFormula(
                                AtomicFormula.VERSION_CODE, AtomicFormula.EQ, versionCode),
                        Rule.DENY);
        RuleSerializer binarySerializer = new RuleBinarySerializer();
        String expectedBits =
                START_BIT
                        + ATOMIC_FORMULA_START_BITS
                        + VERSION_CODE
                        + EQ
                        + getBits(versionCode, /* numOfBits= */ 64)
                        + DENY
                        + END_BIT;
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        byteArrayOutputStream.write(DEFAULT_FORMAT_VERSION_BYTES);
        byteArrayOutputStream.write(getBytes(expectedBits));
        byte[] expectedRules = byteArrayOutputStream.toByteArray();

        byte[] actualRules =
                binarySerializer.serialize(
                        Collections.singletonList(rule), /* formatVersion= */ Optional.empty());

        assertThat(actualRules).isEqualTo(expectedRules);
    }

    @Test
    public void testBinaryString_serializeValidAtomicFormula_booleanValue() throws Exception {
        String preInstalled = "1";
        Rule rule =
                new Rule(
                        new AtomicFormula.BooleanAtomicFormula(AtomicFormula.PRE_INSTALLED, true),
                        Rule.DENY);
        RuleSerializer binarySerializer = new RuleBinarySerializer();
        String expectedBits =
                START_BIT
                        + ATOMIC_FORMULA_START_BITS
                        + PRE_INSTALLED
                        + EQ
                        + preInstalled
                        + DENY
                        + END_BIT;
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        byteArrayOutputStream.write(DEFAULT_FORMAT_VERSION_BYTES);
        byteArrayOutputStream.write(getBytes(expectedBits));
        byte[] expectedRules = byteArrayOutputStream.toByteArray();

        byte[] actualRules =
                binarySerializer.serialize(
                        Collections.singletonList(rule), /* formatVersion= */ Optional.empty());

        assertThat(actualRules).isEqualTo(expectedRules);
    }

    @Test
    public void testBinaryString_serializeInvalidFormulaType() throws Exception {
        IntegrityFormula invalidFormula = getInvalidFormula();
        Rule rule = new Rule(invalidFormula, Rule.DENY);
        RuleSerializer binarySerializer = new RuleBinarySerializer();

        assertExpectException(
                RuleSerializeException.class,
                /* expectedExceptionMessageRegex= */ "Malformed rule identified.",
                () ->
                        binarySerializer.serialize(
                                Collections.singletonList(rule),
                                /* formatVersion= */ Optional.empty()));
    }

    @Test
    public void testBinaryString_serializeFormatVersion() throws Exception {
        int formatVersion = 1;
        RuleSerializer binarySerializer = new RuleBinarySerializer();
        String expectedBits = getBits(formatVersion, FORMAT_VERSION_BITS);
        byte[] expectedRules = getBytes(expectedBits);

        byte[] actualRules =
                binarySerializer.serialize(
                        Collections.emptyList(), /* formatVersion= */ Optional.of(formatVersion));

        assertThat(actualRules).isEqualTo(expectedRules);
    }

    @Test
    public void testBinaryString_verifyManyRulesAreIndexedCorrectly() throws Exception {
        int ruleCount = 225;
        String packagePrefix = "package.name.";
        String appCertificatePrefix = "app.cert.";
        String installerNamePrefix = "installer.";

        // Create the rule set with 225 package name based rules, 225 app certificate indexed rules,
        // and 225 non-indexed rules..
        List<Rule> ruleList = new ArrayList();
        for (int count = 0; count < ruleCount; count++) {
            ruleList.add(
                    getRuleWithPackageNameAndSampleInstallerName(
                            String.format("%s%04d", packagePrefix, count)));
        }
        for (int count = 0; count < ruleCount; count++) {
            ruleList.add(
                    getRuleWithAppCertificateAndSampleInstallerName(
                            String.format("%s%04d", appCertificatePrefix, count)));
        }
        for (int count = 0; count < ruleCount; count++) {
            ruleList.add(
                    getNonIndexedRuleWithInstallerName(
                            String.format("%s%04d", installerNamePrefix, count)));
        }

        // Serialize the rules.
        ByteArrayOutputStream ruleOutputStream = new ByteArrayOutputStream();
        ByteArrayOutputStream indexingOutputStream = new ByteArrayOutputStream();
        RuleSerializer binarySerializer = new RuleBinarySerializer();
        binarySerializer.serialize(
                ruleList,
                /* formatVersion= */ Optional.empty(),
                ruleOutputStream,
                indexingOutputStream);

        // Verify the rules file and index files.
        ByteArrayOutputStream expectedOrderedRuleOutputStream = new ByteArrayOutputStream();
        ByteArrayOutputStream expectedIndexingOutputStream = new ByteArrayOutputStream();

        expectedOrderedRuleOutputStream.write(DEFAULT_FORMAT_VERSION_BYTES);
        int totalBytesWritten = DEFAULT_FORMAT_VERSION_BYTES.length;

        String expectedIndexingBytesForPackageNameIndexed =
                SERIALIZED_START_INDEXING_KEY + getBits(totalBytesWritten, /* numOfBits= */ 32);
        for (int count = 0; count < ruleCount; count++) {
            String packageName = String.format("%s%04d", packagePrefix, count);
            if (count > 0 && count % INDEXING_BLOCK_SIZE == 0) {
                expectedIndexingBytesForPackageNameIndexed +=
                        IS_NOT_HASHED
                                + getBits(packageName.length(), VALUE_SIZE_BITS)
                                + getValueBits(packageName)
                                + getBits(totalBytesWritten, /* numOfBits= */ 32);
            }

            byte[] bytesForPackage =
                    getBytes(
                            getSerializedCompoundRuleWithPackageNameAndSampleInstallerName(
                                    packageName));
            expectedOrderedRuleOutputStream.write(bytesForPackage);
            totalBytesWritten += bytesForPackage.length;
        }
        expectedIndexingBytesForPackageNameIndexed +=
                SERIALIZED_END_INDEXING_KEY + getBits(totalBytesWritten, /* numOfBits= */ 32);

        String expectedIndexingBytesForAppCertificateIndexed =
                SERIALIZED_START_INDEXING_KEY + getBits(totalBytesWritten, /* numOfBits= */ 32);
        for (int count = 0; count < ruleCount; count++) {
            String appCertificate = String.format("%s%04d", appCertificatePrefix, count);
            if (count > 0 && count % INDEXING_BLOCK_SIZE == 0) {
                expectedIndexingBytesForAppCertificateIndexed +=
                        IS_NOT_HASHED
                                + getBits(appCertificate.length(), VALUE_SIZE_BITS)
                                + getValueBits(appCertificate)
                                + getBits(totalBytesWritten, /* numOfBits= */ 32);
            }

            byte[] bytesForPackage =
                    getBytes(
                            getSerializedCompoundRuleWithCertificateNameAndSampleInstallerName(
                                    appCertificate));
            expectedOrderedRuleOutputStream.write(bytesForPackage);
            totalBytesWritten += bytesForPackage.length;
        }
        expectedIndexingBytesForAppCertificateIndexed +=
                SERIALIZED_END_INDEXING_KEY + getBits(totalBytesWritten, /* numOfBits= */ 32);

        String expectedIndexingBytesForUnindexed =
                SERIALIZED_START_INDEXING_KEY + getBits(totalBytesWritten, /* numOfBits= */ 32);
        for (int count = 0; count < ruleCount; count++) {
            byte[] bytesForPackage =
                    getBytes(
                            getSerializedCompoundRuleWithInstallerNameAndInstallerCert(
                                    String.format("%s%04d", installerNamePrefix, count)));
            expectedOrderedRuleOutputStream.write(bytesForPackage);
            totalBytesWritten += bytesForPackage.length;
        }
        expectedIndexingBytesForUnindexed +=
                SERIALIZED_END_INDEXING_KEY + getBits(totalBytesWritten, /* numOfBits= */ 32);
        expectedIndexingOutputStream.write(
                getBytes(
                        expectedIndexingBytesForPackageNameIndexed
                                + expectedIndexingBytesForAppCertificateIndexed
                                + expectedIndexingBytesForUnindexed));

        assertThat(ruleOutputStream.toByteArray())
                .isEqualTo(expectedOrderedRuleOutputStream.toByteArray());
        assertThat(indexingOutputStream.toByteArray())
                .isEqualTo(expectedIndexingOutputStream.toByteArray());
    }

    @Test
    public void testBinaryString_totalRuleSizeLimitReached() {
        int ruleCount = INDEXED_RULE_SIZE_LIMIT - 1;
        String packagePrefix = "package.name.";
        String appCertificatePrefix = "app.cert.";
        String installerNamePrefix = "installer.";

        // Create the rule set with more rules than the system can handle in total.
        List<Rule> ruleList = new ArrayList();
        for (int count = 0; count < ruleCount; count++) {
            ruleList.add(
                    getRuleWithPackageNameAndSampleInstallerName(
                            String.format("%s%04d", packagePrefix, count)));
        }
        for (int count = 0; count < ruleCount; count++) {
            ruleList.add(
                    getRuleWithAppCertificateAndSampleInstallerName(
                            String.format("%s%04d", appCertificatePrefix, count)));
        }
        for (int count = 0; count < NONINDEXED_RULE_SIZE_LIMIT - 1; count++) {
            ruleList.add(
                    getNonIndexedRuleWithInstallerName(
                            String.format("%s%04d", installerNamePrefix, count)));
        }

        // Serialize the rules.
        ByteArrayOutputStream ruleOutputStream = new ByteArrayOutputStream();
        ByteArrayOutputStream indexingOutputStream = new ByteArrayOutputStream();
        RuleSerializer binarySerializer = new RuleBinarySerializer();

        assertExpectException(
                RuleSerializeException.class,
                "Too many rules provided",
                () ->
                        binarySerializer.serialize(
                                ruleList,
                                /* formatVersion= */ Optional.empty(),
                                ruleOutputStream,
                                indexingOutputStream));
    }

    @Test
    public void testBinaryString_tooManyPackageNameIndexedRules() {
        String packagePrefix = "package.name.";

        // Create a rule set with too many package name indexed rules.
        List<Rule> ruleList = new ArrayList();
        for (int count = 0; count < INDEXED_RULE_SIZE_LIMIT + 1; count++) {
            ruleList.add(
                    getRuleWithPackageNameAndSampleInstallerName(
                            String.format("%s%04d", packagePrefix, count)));
        }

        // Serialize the rules.
        ByteArrayOutputStream ruleOutputStream = new ByteArrayOutputStream();
        ByteArrayOutputStream indexingOutputStream = new ByteArrayOutputStream();
        RuleSerializer binarySerializer = new RuleBinarySerializer();

        assertExpectException(
                RuleSerializeException.class,
                "Too many rules provided in the indexing group.",
                () ->
                        binarySerializer.serialize(
                                ruleList,
                                /* formatVersion= */ Optional.empty(),
                                ruleOutputStream,
                                indexingOutputStream));
    }

    @Test
    public void testBinaryString_tooManyAppCertificateIndexedRules() {
        String appCertificatePrefix = "app.cert.";

        // Create a rule set with too many app certificate indexed rules.
        List<Rule> ruleList = new ArrayList();
        for (int count = 0; count < INDEXED_RULE_SIZE_LIMIT + 1; count++) {
            ruleList.add(
                    getRuleWithAppCertificateAndSampleInstallerName(
                            String.format("%s%04d", appCertificatePrefix, count)));
        }

        // Serialize the rules.
        ByteArrayOutputStream ruleOutputStream = new ByteArrayOutputStream();
        ByteArrayOutputStream indexingOutputStream = new ByteArrayOutputStream();
        RuleSerializer binarySerializer = new RuleBinarySerializer();

        assertExpectException(
                RuleSerializeException.class,
                "Too many rules provided in the indexing group.",
                () ->
                        binarySerializer.serialize(
                                ruleList,
                                /* formatVersion= */ Optional.empty(),
                                ruleOutputStream,
                                indexingOutputStream));
    }

    @Test
    public void testBinaryString_tooManyNonIndexedRules() {
        String installerNamePrefix = "installer.";

        // Create a rule set with too many unindexed rules.
        List<Rule> ruleList = new ArrayList();
        for (int count = 0; count < NONINDEXED_RULE_SIZE_LIMIT + 1; count++) {
            ruleList.add(
                    getNonIndexedRuleWithInstallerName(
                            String.format("%s%04d", installerNamePrefix, count)));
        }

        // Serialize the rules.
        ByteArrayOutputStream ruleOutputStream = new ByteArrayOutputStream();
        ByteArrayOutputStream indexingOutputStream = new ByteArrayOutputStream();
        RuleSerializer binarySerializer = new RuleBinarySerializer();

        assertExpectException(
                RuleSerializeException.class,
                "Too many rules provided in the indexing group.",
                () ->
                        binarySerializer.serialize(
                                ruleList,
                                /* formatVersion= */ Optional.empty(),
                                ruleOutputStream,
                                indexingOutputStream));
    }

    private Rule getRuleWithPackageNameAndSampleInstallerName(String packageName) {
        return new Rule(
                new CompoundFormula(
                        CompoundFormula.AND,
                        Arrays.asList(
                                new AtomicFormula.StringAtomicFormula(
                                        AtomicFormula.PACKAGE_NAME,
                                        packageName,
                                        /* isHashedValue= */ false),
                                new AtomicFormula.StringAtomicFormula(
                                        AtomicFormula.INSTALLER_NAME,
                                        SAMPLE_INSTALLER_NAME,
                                        /* isHashedValue= */ false))),
                Rule.DENY);
    }

    private String getSerializedCompoundRuleWithPackageNameAndSampleInstallerName(
            String packageName) {
        return START_BIT
                + COMPOUND_FORMULA_START_BITS
                + AND
                + ATOMIC_FORMULA_START_BITS
                + PACKAGE_NAME
                + EQ
                + IS_NOT_HASHED
                + getBits(packageName.length(), VALUE_SIZE_BITS)
                + getValueBits(packageName)
                + ATOMIC_FORMULA_START_BITS
                + INSTALLER_NAME
                + EQ
                + IS_NOT_HASHED
                + getBits(SAMPLE_INSTALLER_NAME.length(), VALUE_SIZE_BITS)
                + getValueBits(SAMPLE_INSTALLER_NAME)
                + COMPOUND_FORMULA_END_BITS
                + DENY
                + END_BIT;
    }

    private Rule getRuleWithAppCertificateAndSampleInstallerName(String certificate) {
        return new Rule(
                new CompoundFormula(
                        CompoundFormula.AND,
                        Arrays.asList(
                                new AtomicFormula.StringAtomicFormula(
                                        AtomicFormula.APP_CERTIFICATE,
                                        certificate,
                                        /* isHashedValue= */ false),
                                new AtomicFormula.StringAtomicFormula(
                                        AtomicFormula.INSTALLER_NAME,
                                        SAMPLE_INSTALLER_NAME,
                                        /* isHashedValue= */ false))),
                Rule.DENY);
    }

    private String getSerializedCompoundRuleWithCertificateNameAndSampleInstallerName(
            String appCertificate) {
        return START_BIT
                + COMPOUND_FORMULA_START_BITS
                + AND
                + ATOMIC_FORMULA_START_BITS
                + APP_CERTIFICATE
                + EQ
                + IS_NOT_HASHED
                + getBits(appCertificate.length(), VALUE_SIZE_BITS)
                + getValueBits(appCertificate)
                + ATOMIC_FORMULA_START_BITS
                + INSTALLER_NAME
                + EQ
                + IS_NOT_HASHED
                + getBits(SAMPLE_INSTALLER_NAME.length(), VALUE_SIZE_BITS)
                + getValueBits(SAMPLE_INSTALLER_NAME)
                + COMPOUND_FORMULA_END_BITS
                + DENY
                + END_BIT;
    }

    private Rule getNonIndexedRuleWithInstallerName(String installerName) {
        return new Rule(
                new CompoundFormula(
                        CompoundFormula.AND,
                        Arrays.asList(
                                new AtomicFormula.StringAtomicFormula(
                                        AtomicFormula.INSTALLER_NAME,
                                        installerName,
                                        /* isHashedValue= */ false),
                                new AtomicFormula.StringAtomicFormula(
                                        AtomicFormula.INSTALLER_CERTIFICATE,
                                        SAMPLE_INSTALLER_CERT,
                                        /* isHashedValue= */ false))),
                Rule.DENY);
    }

    private String getSerializedCompoundRuleWithInstallerNameAndInstallerCert(
            String installerName) {
        return START_BIT
                + COMPOUND_FORMULA_START_BITS
                + AND
                + ATOMIC_FORMULA_START_BITS
                + INSTALLER_NAME
                + EQ
                + IS_NOT_HASHED
                + getBits(installerName.length(), VALUE_SIZE_BITS)
                + getValueBits(installerName)
                + ATOMIC_FORMULA_START_BITS
                + INSTALLER_CERTIFICATE
                + EQ
                + IS_NOT_HASHED
                + getBits(SAMPLE_INSTALLER_CERT.length(), VALUE_SIZE_BITS)
                + getValueBits(SAMPLE_INSTALLER_CERT)
                + COMPOUND_FORMULA_END_BITS
                + DENY
                + END_BIT;
    }

    private static IntegrityFormula getInvalidFormula() {
        return new AtomicFormula(0) {
            @Override
            public int getTag() {
                return 0;
            }

            @Override
            public boolean matches(AppInstallMetadata appInstallMetadata) {
                return false;
            }

            @Override
            public boolean isAppCertificateFormula() {
                return false;
            }

            @Override
            public boolean isInstallerFormula() {
                return false;
            }

            @Override
            public int hashCode() {
                return super.hashCode();
            }

            @Override
            public boolean equals(Object obj) {
                return super.equals(obj);
            }

            @NonNull
            @Override
            protected Object clone() throws CloneNotSupportedException {
                return super.clone();
            }

            @Override
            public String toString() {
                return super.toString();
            }

            @Override
            protected void finalize() throws Throwable {
                super.finalize();
            }
        };
    }
}
