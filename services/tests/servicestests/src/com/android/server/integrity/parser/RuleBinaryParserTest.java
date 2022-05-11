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

package com.android.server.integrity.parser;

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
import static com.android.server.integrity.utils.TestUtils.getBits;
import static com.android.server.integrity.utils.TestUtils.getBytes;
import static com.android.server.integrity.utils.TestUtils.getValueBits;
import static com.android.server.testutils.TestUtils.assertExpectException;

import static com.google.common.truth.Truth.assertThat;

import android.content.integrity.AtomicFormula;
import android.content.integrity.CompoundFormula;
import android.content.integrity.IntegrityUtils;
import android.content.integrity.Rule;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(JUnit4.class)
public class RuleBinaryParserTest {

    private static final String COMPOUND_FORMULA_START_BITS =
            getBits(COMPOUND_FORMULA_START, SEPARATOR_BITS);
    private static final String COMPOUND_FORMULA_END_BITS =
            getBits(COMPOUND_FORMULA_END, SEPARATOR_BITS);
    private static final String ATOMIC_FORMULA_START_BITS =
            getBits(ATOMIC_FORMULA_START, SEPARATOR_BITS);
    private static final int INVALID_FORMULA_SEPARATOR_VALUE = (1 << SEPARATOR_BITS) - 1;
    private static final String INVALID_FORMULA_SEPARATOR_BITS =
            getBits(INVALID_FORMULA_SEPARATOR_VALUE, SEPARATOR_BITS);

    private static final String NOT = getBits(CompoundFormula.NOT, CONNECTOR_BITS);
    private static final String AND = getBits(CompoundFormula.AND, CONNECTOR_BITS);
    private static final String OR = getBits(CompoundFormula.OR, CONNECTOR_BITS);
    private static final int INVALID_CONNECTOR_VALUE = 3;
    private static final String INVALID_CONNECTOR =
            getBits(INVALID_CONNECTOR_VALUE, CONNECTOR_BITS);

    private static final String PACKAGE_NAME = getBits(AtomicFormula.PACKAGE_NAME, KEY_BITS);
    private static final String APP_CERTIFICATE = getBits(AtomicFormula.APP_CERTIFICATE, KEY_BITS);
    private static final String APP_CERTIFICATE_LINEAGE =
            getBits(AtomicFormula.APP_CERTIFICATE_LINEAGE, KEY_BITS);
    private static final String VERSION_CODE = getBits(AtomicFormula.VERSION_CODE, KEY_BITS);
    private static final String PRE_INSTALLED = getBits(AtomicFormula.PRE_INSTALLED, KEY_BITS);
    private static final int INVALID_KEY_VALUE = 9;
    private static final String INVALID_KEY = getBits(INVALID_KEY_VALUE, KEY_BITS);

    private static final String EQ = getBits(AtomicFormula.EQ, OPERATOR_BITS);
    private static final int INVALID_OPERATOR_VALUE = 5;
    private static final String INVALID_OPERATOR = getBits(INVALID_OPERATOR_VALUE, OPERATOR_BITS);

    private static final String IS_NOT_HASHED = "0";
    private static final String IS_HASHED = "1";

    private static final String DENY = getBits(Rule.DENY, EFFECT_BITS);
    private static final int INVALID_EFFECT_VALUE = 5;
    private static final String INVALID_EFFECT = getBits(INVALID_EFFECT_VALUE, EFFECT_BITS);

    private static final String START_BIT = "1";
    private static final String END_BIT = "1";
    private static final String INVALID_MARKER_BIT = "0";

    private static final byte[] DEFAULT_FORMAT_VERSION_BYTES =
            getBytes(getBits(DEFAULT_FORMAT_VERSION, FORMAT_VERSION_BITS));

    private static final List<RuleIndexRange> NO_INDEXING = Collections.emptyList();

    @Test
    public void testBinaryStream_validCompoundFormula_noIndexing() throws Exception {
        String packageName = "com.test.app";
        String ruleBits =
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
        byte[] ruleBytes = getBytes(ruleBits);
        ByteBuffer rule =
                ByteBuffer.allocate(DEFAULT_FORMAT_VERSION_BYTES.length + ruleBytes.length);
        rule.put(DEFAULT_FORMAT_VERSION_BYTES);
        rule.put(ruleBytes);
        RuleParser binaryParser = new RuleBinaryParser();
        Rule expectedRule =
                new Rule(
                        new CompoundFormula(
                                CompoundFormula.NOT,
                                Collections.singletonList(
                                        new AtomicFormula.StringAtomicFormula(
                                                AtomicFormula.PACKAGE_NAME,
                                                packageName,
                                                /* isHashedValue= */ false))),
                        Rule.DENY);

        List<Rule> rules =
                binaryParser.parse(RandomAccessObject.ofBytes(rule.array()), NO_INDEXING);

        assertThat(rules).isEqualTo(Collections.singletonList(expectedRule));
    }

    @Test
    public void testBinaryString_validCompoundFormula_notConnector_noIndexing() throws Exception {
        String packageName = "com.test.app";
        String ruleBits =
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
        byte[] ruleBytes = getBytes(ruleBits);
        ByteBuffer rule =
                ByteBuffer.allocate(DEFAULT_FORMAT_VERSION_BYTES.length + ruleBytes.length);
        rule.put(DEFAULT_FORMAT_VERSION_BYTES);
        rule.put(ruleBytes);
        RuleParser binaryParser = new RuleBinaryParser();
        Rule expectedRule =
                new Rule(
                        new CompoundFormula(
                                CompoundFormula.NOT,
                                Collections.singletonList(
                                        new AtomicFormula.StringAtomicFormula(
                                                AtomicFormula.PACKAGE_NAME,
                                                packageName,
                                                /* isHashedValue= */ false))),
                        Rule.DENY);

        List<Rule> rules = binaryParser.parse(rule.array());

        assertThat(rules).isEqualTo(Collections.singletonList(expectedRule));
    }

    @Test
    public void testBinaryString_validCompoundFormula_andConnector_noIndexing() throws Exception {
        String packageName = "com.test.app";
        String appCertificate = "test_cert";
        String ruleBits =
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
        byte[] ruleBytes = getBytes(ruleBits);
        ByteBuffer rule =
                ByteBuffer.allocate(DEFAULT_FORMAT_VERSION_BYTES.length + ruleBytes.length);
        rule.put(DEFAULT_FORMAT_VERSION_BYTES);
        rule.put(ruleBytes);
        RuleParser binaryParser = new RuleBinaryParser();
        Rule expectedRule =
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
        List<Rule> rules = binaryParser.parse(rule.array());

        assertThat(rules).isEqualTo(Collections.singletonList(expectedRule));
    }

    @Test
    public void testBinaryString_validCompoundFormula_orConnector_noIndexing() throws Exception {
        String packageName = "com.test.app";
        String appCertificate = "test_cert";
        String ruleBits =
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
        byte[] ruleBytes = getBytes(ruleBits);
        ByteBuffer rule =
                ByteBuffer.allocate(DEFAULT_FORMAT_VERSION_BYTES.length + ruleBytes.length);
        rule.put(DEFAULT_FORMAT_VERSION_BYTES);
        rule.put(ruleBytes);
        RuleParser binaryParser = new RuleBinaryParser();
        Rule expectedRule =
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

        List<Rule> rules = binaryParser.parse(rule.array());

        assertThat(rules).isEqualTo(Collections.singletonList(expectedRule));
    }

    @Test
    public void testBinaryString_validAtomicFormula_stringValue_noIndexing() throws Exception {
        String packageName = "com.test.app";
        String ruleBits =
                START_BIT
                        + ATOMIC_FORMULA_START_BITS
                        + PACKAGE_NAME
                        + EQ
                        + IS_NOT_HASHED
                        + getBits(packageName.length(), VALUE_SIZE_BITS)
                        + getValueBits(packageName)
                        + DENY
                        + END_BIT;
        byte[] ruleBytes = getBytes(ruleBits);
        ByteBuffer rule =
                ByteBuffer.allocate(DEFAULT_FORMAT_VERSION_BYTES.length + ruleBytes.length);
        rule.put(DEFAULT_FORMAT_VERSION_BYTES);
        rule.put(ruleBytes);
        RuleParser binaryParser = new RuleBinaryParser();
        Rule expectedRule =
                new Rule(
                        new AtomicFormula.StringAtomicFormula(
                                AtomicFormula.PACKAGE_NAME,
                                packageName,
                                /* isHashedValue= */ false),
                        Rule.DENY);

        List<Rule> rules = binaryParser.parse(rule.array());

        assertThat(rules).isEqualTo(Collections.singletonList(expectedRule));
    }

    @Test
    public void testBinaryString_validAtomicFormula_hashedValue_noIndexing() throws Exception {
        String appCertificate = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        String ruleBits =
                START_BIT
                        + ATOMIC_FORMULA_START_BITS
                        + APP_CERTIFICATE
                        + EQ
                        + IS_HASHED
                        + getBits(appCertificate.length(), VALUE_SIZE_BITS)
                        + getValueBits(appCertificate)
                        + DENY
                        + END_BIT;
        byte[] ruleBytes = getBytes(ruleBits);
        ByteBuffer rule =
                ByteBuffer.allocate(DEFAULT_FORMAT_VERSION_BYTES.length + ruleBytes.length);
        rule.put(DEFAULT_FORMAT_VERSION_BYTES);
        rule.put(ruleBytes);

        RuleParser binaryParser = new RuleBinaryParser();
        Rule expectedRule =
                new Rule(
                        new AtomicFormula.StringAtomicFormula(
                                AtomicFormula.APP_CERTIFICATE,
                                IntegrityUtils.getHexDigest(
                                        appCertificate.getBytes(StandardCharsets.UTF_8)),
                                /* isHashedValue= */ true),
                        Rule.DENY);

        List<Rule> rules = binaryParser.parse(rule.array());

        assertThat(rules).isEqualTo(Collections.singletonList(expectedRule));
    }

    @Test
    public void testBinaryString_validAtomicFormulaWithCertificateLineage() throws Exception {
        String appCertificate = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        String ruleBits =
                START_BIT
                        + ATOMIC_FORMULA_START_BITS
                        + APP_CERTIFICATE_LINEAGE
                        + EQ
                        + IS_HASHED
                        + getBits(appCertificate.length(), VALUE_SIZE_BITS)
                        + getValueBits(appCertificate)
                        + DENY
                        + END_BIT;
        byte[] ruleBytes = getBytes(ruleBits);
        ByteBuffer rule =
                ByteBuffer.allocate(DEFAULT_FORMAT_VERSION_BYTES.length + ruleBytes.length);
        rule.put(DEFAULT_FORMAT_VERSION_BYTES);
        rule.put(ruleBytes);

        RuleParser binaryParser = new RuleBinaryParser();
        Rule expectedRule =
                new Rule(
                        new AtomicFormula.StringAtomicFormula(
                                AtomicFormula.APP_CERTIFICATE_LINEAGE,
                                IntegrityUtils.getHexDigest(
                                        appCertificate.getBytes(StandardCharsets.UTF_8)),
                                /* isHashedValue= */ true),
                        Rule.DENY);

        List<Rule> rules = binaryParser.parse(rule.array());

        assertThat(rules).isEqualTo(Collections.singletonList(expectedRule));
    }

    @Test
    public void testBinaryString_validAtomicFormula_integerValue_noIndexing() throws Exception {
        int versionCode = 1;
        String ruleBits =
                START_BIT
                        + ATOMIC_FORMULA_START_BITS
                        + VERSION_CODE
                        + EQ
                        + getBits(versionCode, /* numOfBits= */ 64)
                        + DENY
                        + END_BIT;
        byte[] ruleBytes = getBytes(ruleBits);
        ByteBuffer rule =
                ByteBuffer.allocate(DEFAULT_FORMAT_VERSION_BYTES.length + ruleBytes.length);
        rule.put(DEFAULT_FORMAT_VERSION_BYTES);
        rule.put(ruleBytes);
        RuleParser binaryParser = new RuleBinaryParser();
        Rule expectedRule =
                new Rule(
                        new AtomicFormula.LongAtomicFormula(
                                AtomicFormula.VERSION_CODE, AtomicFormula.EQ, 1),
                        Rule.DENY);

        List<Rule> rules = binaryParser.parse(rule.array());

        assertThat(rules).isEqualTo(Collections.singletonList(expectedRule));
    }

    @Test
    public void testBinaryString_validAtomicFormula_booleanValue_noIndexing() throws Exception {
        String isPreInstalled = "1";
        String ruleBits =
                START_BIT
                        + ATOMIC_FORMULA_START_BITS
                        + PRE_INSTALLED
                        + EQ
                        + isPreInstalled
                        + DENY
                        + END_BIT;
        byte[] ruleBytes = getBytes(ruleBits);
        ByteBuffer rule =
                ByteBuffer.allocate(DEFAULT_FORMAT_VERSION_BYTES.length + ruleBytes.length);
        rule.put(DEFAULT_FORMAT_VERSION_BYTES);
        rule.put(ruleBytes);
        RuleParser binaryParser = new RuleBinaryParser();
        Rule expectedRule =
                new Rule(
                        new AtomicFormula.BooleanAtomicFormula(
                                AtomicFormula.PRE_INSTALLED, true),
                        Rule.DENY);

        List<Rule> rules = binaryParser.parse(rule.array());

        assertThat(rules).isEqualTo(Collections.singletonList(expectedRule));
    }

    @Test
    public void testBinaryString_invalidAtomicFormula_noIndexing() {
        int versionCode = 1;
        String ruleBits =
                START_BIT
                        + ATOMIC_FORMULA_START_BITS
                        + VERSION_CODE
                        + EQ
                        + getBits(versionCode, /* numOfBits= */ 64)
                        + DENY;
        byte[] ruleBytes = getBytes(ruleBits);
        ByteBuffer rule =
                ByteBuffer.allocate(DEFAULT_FORMAT_VERSION_BYTES.length + ruleBytes.length);
        rule.put(DEFAULT_FORMAT_VERSION_BYTES);
        rule.put(ruleBytes);
        RuleParser binaryParser = new RuleBinaryParser();

        assertExpectException(
                RuleParseException.class,
                /* expectedExceptionMessageRegex= */ "A rule must end with a '1' bit.",
                () -> binaryParser.parse(rule.array()));
    }

    @Test
    public void testBinaryString_withNoRuleList_noIndexing() throws RuleParseException {
        ByteBuffer rule = ByteBuffer.allocate(DEFAULT_FORMAT_VERSION_BYTES.length);
        rule.put(DEFAULT_FORMAT_VERSION_BYTES);
        RuleParser binaryParser = new RuleBinaryParser();

        List<Rule> rules = binaryParser.parse(rule.array());

        assertThat(rules).isEmpty();
    }

    @Test
    public void testBinaryString_withEmptyRule_noIndexing() {
        String ruleBits = START_BIT;
        byte[] ruleBytes = getBytes(ruleBits);
        ByteBuffer rule =
                ByteBuffer.allocate(DEFAULT_FORMAT_VERSION_BYTES.length + ruleBytes.length);
        rule.put(DEFAULT_FORMAT_VERSION_BYTES);
        rule.put(ruleBytes);
        RuleParser binaryParser = new RuleBinaryParser();

        assertExpectException(
                RuleParseException.class,
                /* expectedExceptionMessageRegex */ "",
                () -> binaryParser.parse(rule.array()));
    }

    @Test
    public void testBinaryString_invalidCompoundFormula_invalidNumberOfFormulas_noIndexing() {
        String packageName = "com.test.app";
        String appCertificate = "test_cert";
        String ruleBits =
                START_BIT
                        + COMPOUND_FORMULA_START_BITS
                        + NOT
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
        byte[] ruleBytes = getBytes(ruleBits);
        ByteBuffer rule =
                ByteBuffer.allocate(DEFAULT_FORMAT_VERSION_BYTES.length + ruleBytes.length);
        rule.put(DEFAULT_FORMAT_VERSION_BYTES);
        rule.put(ruleBytes);
        RuleParser binaryParser = new RuleBinaryParser();

        assertExpectException(
                RuleParseException.class,
                /* expectedExceptionMessageRegex */ "Connector NOT must have 1 formula only",
                () -> binaryParser.parse(rule.array()));
    }

    @Test
    public void testBinaryString_invalidRule_invalidOperator_noIndexing() {
        int versionCode = 1;
        String ruleBits =
                START_BIT
                        + COMPOUND_FORMULA_START_BITS
                        + NOT
                        + ATOMIC_FORMULA_START_BITS
                        + VERSION_CODE
                        + INVALID_OPERATOR
                        + getBits(versionCode, /* numOfBits= */ 64)
                        + COMPOUND_FORMULA_END_BITS
                        + DENY
                        + END_BIT;
        byte[] ruleBytes = getBytes(ruleBits);
        ByteBuffer rule =
                ByteBuffer.allocate(DEFAULT_FORMAT_VERSION_BYTES.length + ruleBytes.length);
        rule.put(DEFAULT_FORMAT_VERSION_BYTES);
        rule.put(ruleBytes);
        RuleParser binaryParser = new RuleBinaryParser();

        assertExpectException(
                RuleParseException.class,
                /* expectedExceptionMessageRegex */ String.format(
                        "Unknown operator: %d", INVALID_OPERATOR_VALUE),
                () -> binaryParser.parse(rule.array()));
    }

    @Test
    public void testBinaryString_invalidRule_invalidEffect_noIndexing() {
        String packageName = "com.test.app";
        String ruleBits =
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
                        + INVALID_EFFECT
                        + END_BIT;
        byte[] ruleBytes = getBytes(ruleBits);
        ByteBuffer rule =
                ByteBuffer.allocate(DEFAULT_FORMAT_VERSION_BYTES.length + ruleBytes.length);
        rule.put(DEFAULT_FORMAT_VERSION_BYTES);
        rule.put(ruleBytes);
        RuleParser binaryParser = new RuleBinaryParser();

        assertExpectException(
                RuleParseException.class,
                /* expectedExceptionMessageRegex */ String.format(
                        "Unknown effect: %d", INVALID_EFFECT_VALUE),
                () -> binaryParser.parse(rule.array()));
    }

    @Test
    public void testBinaryString_invalidRule_invalidConnector_noIndexing() {
        String packageName = "com.test.app";
        String ruleBits =
                START_BIT
                        + COMPOUND_FORMULA_START_BITS
                        + INVALID_CONNECTOR
                        + ATOMIC_FORMULA_START_BITS
                        + PACKAGE_NAME
                        + EQ
                        + IS_NOT_HASHED
                        + getBits(packageName.length(), VALUE_SIZE_BITS)
                        + getValueBits(packageName)
                        + COMPOUND_FORMULA_END_BITS
                        + DENY
                        + END_BIT;
        byte[] ruleBytes = getBytes(ruleBits);
        ByteBuffer rule =
                ByteBuffer.allocate(DEFAULT_FORMAT_VERSION_BYTES.length + ruleBytes.length);
        rule.put(DEFAULT_FORMAT_VERSION_BYTES);
        rule.put(ruleBytes);
        RuleParser binaryParser = new RuleBinaryParser();

        assertExpectException(
                RuleParseException.class,
                /* expectedExceptionMessageRegex */ String.format(
                        "Unknown connector: %d", INVALID_CONNECTOR_VALUE),
                () -> binaryParser.parse(rule.array()));
    }

    @Test
    public void testBinaryString_invalidRule_invalidKey_noIndexing() {
        String packageName = "com.test.app";
        String ruleBits =
                START_BIT
                        + COMPOUND_FORMULA_START_BITS
                        + NOT
                        + ATOMIC_FORMULA_START_BITS
                        + INVALID_KEY
                        + EQ
                        + IS_NOT_HASHED
                        + getBits(packageName.length(), VALUE_SIZE_BITS)
                        + getValueBits(packageName)
                        + COMPOUND_FORMULA_END_BITS
                        + DENY
                        + END_BIT;
        byte[] ruleBytes = getBytes(ruleBits);
        ByteBuffer rule =
                ByteBuffer.allocate(DEFAULT_FORMAT_VERSION_BYTES.length + ruleBytes.length);
        rule.put(DEFAULT_FORMAT_VERSION_BYTES);
        rule.put(ruleBytes);
        RuleParser binaryParser = new RuleBinaryParser();

        assertExpectException(
                RuleParseException.class,
                /* expectedExceptionMessageRegex */ String.format(
                        "Unknown key: %d", INVALID_KEY_VALUE),
                () -> binaryParser.parse(rule.array()));
    }

    @Test
    public void testBinaryString_invalidRule_invalidSeparator_noIndexing() {
        String packageName = "com.test.app";
        String ruleBits =
                START_BIT
                        + INVALID_FORMULA_SEPARATOR_BITS
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
        byte[] ruleBytes = getBytes(ruleBits);
        ByteBuffer rule =
                ByteBuffer.allocate(DEFAULT_FORMAT_VERSION_BYTES.length + ruleBytes.length);
        rule.put(DEFAULT_FORMAT_VERSION_BYTES);
        rule.put(ruleBytes);
        RuleParser binaryParser = new RuleBinaryParser();

        assertExpectException(
                RuleParseException.class,
                /* expectedExceptionMessageRegex */ String.format(
                        "Unknown formula separator: %d", INVALID_FORMULA_SEPARATOR_VALUE),
                () -> binaryParser.parse(rule.array()));
    }

    @Test
    public void testBinaryString_invalidRule_invalidEndMarker_noIndexing() {
        String packageName = "com.test.app";
        String ruleBits =
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
                        + INVALID_MARKER_BIT;
        byte[] ruleBytes = getBytes(ruleBits);
        ByteBuffer rule =
                ByteBuffer.allocate(DEFAULT_FORMAT_VERSION_BYTES.length + ruleBytes.length);
        rule.put(DEFAULT_FORMAT_VERSION_BYTES);
        rule.put(ruleBytes);
        RuleParser binaryParser = new RuleBinaryParser();

        assertExpectException(
                RuleParseException.class,
                /* expectedExceptionMessageRegex */ "A rule must end with a '1' bit",
                () -> binaryParser.parse(rule.array()));
    }
}
