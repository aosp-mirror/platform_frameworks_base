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
import static com.android.server.integrity.utils.TestUtils.getBits;
import static com.android.server.integrity.utils.TestUtils.getBytes;
import static com.android.server.integrity.utils.TestUtils.getValueBits;
import static com.android.server.testutils.TestUtils.assertExpectException;

import static com.google.common.truth.Truth.assertThat;

import android.content.integrity.AppInstallMetadata;
import android.content.integrity.AtomicFormula;
import android.content.integrity.CompoundFormula;
import android.content.integrity.Formula;
import android.content.integrity.Rule;

import androidx.annotation.NonNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

@RunWith(JUnit4.class)
public class RuleBinarySerializerTest {

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
    private static final String VERSION_CODE = getBits(AtomicFormula.VERSION_CODE, KEY_BITS);
    private static final String PRE_INSTALLED = getBits(AtomicFormula.PRE_INSTALLED, KEY_BITS);

    private static final String EQ = getBits(AtomicFormula.EQ, OPERATOR_BITS);

    private static final String IS_NOT_HASHED = "0";

    private static final String DENY = getBits(Rule.DENY, EFFECT_BITS);

    private static final String START_BIT = "1";
    private static final String END_BIT = "1";

    private static final byte[] DEFAULT_FORMAT_VERSION_BYTES =
            getBytes(getBits(DEFAULT_FORMAT_VERSION, FORMAT_VERSION_BITS));

    @Test
    public void testBinaryString_serializeEmptyRule() throws Exception {
        Rule rule = null;
        RuleSerializer binarySerializer = new RuleBinarySerializer();

        assertExpectException(
                RuleSerializeException.class,
                /* expectedExceptionMessageRegex= */ "Null rule can not be serialized",
                () ->
                        binarySerializer.serialize(
                                Collections.singletonList(rule),
                                /* formatVersion= */ Optional.empty()));
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
        RuleSerializer binarySerializer = new RuleBinarySerializer();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
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

        binarySerializer.serialize(
                Collections.singletonList(rule),
                /* formatVersion= */ Optional.empty(),
                outputStream);

        byte[] actualRules = outputStream.toByteArray();
        assertThat(actualRules).isEqualTo(expectedRules);
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
    public void testBinaryString_serializeValidAtomicFormula_integerValue() throws Exception {
        String versionCode = "1";
        Rule rule =
                new Rule(
                        new AtomicFormula.IntAtomicFormula(
                                AtomicFormula.VERSION_CODE,
                                AtomicFormula.EQ,
                                Integer.parseInt(versionCode)),
                        Rule.DENY);
        RuleSerializer binarySerializer = new RuleBinarySerializer();
        String expectedBits =
                START_BIT
                        + ATOMIC_FORMULA_START_BITS
                        + VERSION_CODE
                        + EQ
                        + IS_NOT_HASHED
                        + getBits(versionCode.length(), VALUE_SIZE_BITS)
                        + getValueBits(versionCode)
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
                        + IS_NOT_HASHED
                        + getBits(preInstalled.length(), VALUE_SIZE_BITS)
                        + getValueBits(preInstalled)
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
        Formula invalidFormula = getInvalidFormula();
        Rule rule = new Rule(invalidFormula, Rule.DENY);
        RuleSerializer binarySerializer = new RuleBinarySerializer();

        assertExpectException(
                RuleSerializeException.class,
                /* expectedExceptionMessageRegex= */ "Invalid formula type",
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

    private static Formula getInvalidFormula() {
        return new Formula() {
            @Override
            public boolean isSatisfied(AppInstallMetadata appInstallMetadata) {
                return false;
            }

            @Override
            public int getTag() {
                return 0;
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
