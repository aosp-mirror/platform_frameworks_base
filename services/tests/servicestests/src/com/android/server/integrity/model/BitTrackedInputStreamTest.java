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

package com.android.server.integrity.model;

import static com.android.server.integrity.model.ComponentBitSize.ATOMIC_FORMULA_START;
import static com.android.server.integrity.model.ComponentBitSize.COMPOUND_FORMULA_END;
import static com.android.server.integrity.model.ComponentBitSize.COMPOUND_FORMULA_START;
import static com.android.server.integrity.model.ComponentBitSize.CONNECTOR_BITS;
import static com.android.server.integrity.model.ComponentBitSize.EFFECT_BITS;
import static com.android.server.integrity.model.ComponentBitSize.KEY_BITS;
import static com.android.server.integrity.model.ComponentBitSize.OPERATOR_BITS;
import static com.android.server.integrity.model.ComponentBitSize.SEPARATOR_BITS;
import static com.android.server.integrity.model.ComponentBitSize.VALUE_SIZE_BITS;
import static com.android.server.integrity.utils.TestUtils.getBits;
import static com.android.server.integrity.utils.TestUtils.getBytes;
import static com.android.server.integrity.utils.TestUtils.getValueBits;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.assertThrows;

import android.content.integrity.AtomicFormula;
import android.content.integrity.CompoundFormula;
import android.content.integrity.Rule;

import com.android.server.integrity.parser.BinaryFileOperations;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.IOException;

@RunWith(JUnit4.class)
public class BitTrackedInputStreamTest {
    private static final String COMPOUND_FORMULA_START_BITS =
            getBits(COMPOUND_FORMULA_START, SEPARATOR_BITS);
    private static final String COMPOUND_FORMULA_END_BITS =
            getBits(COMPOUND_FORMULA_END, SEPARATOR_BITS);
    private static final String ATOMIC_FORMULA_START_BITS =
            getBits(ATOMIC_FORMULA_START, SEPARATOR_BITS);
    private static final String NOT = getBits(CompoundFormula.NOT, CONNECTOR_BITS);
    private static final String PACKAGE_NAME = getBits(AtomicFormula.PACKAGE_NAME, KEY_BITS);
    private static final String EQ = getBits(AtomicFormula.EQ, OPERATOR_BITS);
    private static final String DENY = getBits(Rule.DENY, EFFECT_BITS);

    private static final String IS_NOT_HASHED = "0";
    private static final String START_BIT = "1";
    private static final String END_BIT = "1";

    @Test
    public void testBitOperationsCountBitsCorrectly() throws IOException {
        String packageName = "com.test.app";
        byte[] testInput =
                getBytes(
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
                                + END_BIT);

        BitTrackedInputStream bitTrackedInputStream = new BitTrackedInputStream(testInput);

        // Right after construction, the read bits count should be 0.
        assertThat(bitTrackedInputStream.getReadBitsCount()).isEqualTo(0);

        // Get next 10 bits should result with 10 bits read.
        bitTrackedInputStream.getNext(10);
        assertThat(bitTrackedInputStream.getReadBitsCount()).isEqualTo(10);

        // When we move the cursor 8 bytes, we should point to 64 bits.
        bitTrackedInputStream.setCursorToByteLocation(8);
        assertThat(bitTrackedInputStream.getReadBitsCount()).isEqualTo(64);

        // Read until the end and the total size of the input stream should be available.
        while (bitTrackedInputStream.hasNext()) {
            bitTrackedInputStream.getNext(1);
        }
        assertThat(bitTrackedInputStream.getReadBitsCount()).isEqualTo(128);
    }

    @Test
    public void testBitInputStreamOperationsStillWork() throws IOException {
        String packageName = "com.test.app";
        byte[] testInput =
                getBytes(
                        IS_NOT_HASHED
                                + getBits(packageName.length(), VALUE_SIZE_BITS)
                                + getValueBits(packageName));

        BitTrackedInputStream bitTrackedInputStream = new BitTrackedInputStream(testInput);
        assertThat(bitTrackedInputStream.getReadBitsCount()).isEqualTo(0);

        // Read until the string parameter.
        String stringValue = BinaryFileOperations.getStringValue(bitTrackedInputStream);

        // Verify that the read bytes are counted.
        assertThat(stringValue).isEqualTo(packageName);
        assertThat(bitTrackedInputStream.getReadBitsCount()).isGreaterThan(0);
    }

    @Test
    public void testBitTrackedInputStream_moveCursorForwardFailsIfAlreadyRead() throws IOException {
        String packageName = "com.test.app";
        byte[] testInput =
                getBytes(
                        IS_NOT_HASHED
                                + getBits(packageName.length(), VALUE_SIZE_BITS)
                                + getValueBits(packageName));

        BitTrackedInputStream bitTrackedInputStream = new BitTrackedInputStream(testInput);

        // Read more than two bytes.
        bitTrackedInputStream.getNext(20);

        // Ask to move the cursor to the second byte.
        assertThrows(
                IllegalStateException.class,
                () -> bitTrackedInputStream.setCursorToByteLocation(2));
    }
}
