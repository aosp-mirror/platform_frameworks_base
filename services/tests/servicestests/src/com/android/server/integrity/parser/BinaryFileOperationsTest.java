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

package com.android.server.integrity.parser;

import static com.android.server.integrity.model.ComponentBitSize.VALUE_SIZE_BITS;
import static com.android.server.integrity.parser.BinaryFileOperations.getBooleanValue;
import static com.android.server.integrity.parser.BinaryFileOperations.getIntValue;
import static com.android.server.integrity.parser.BinaryFileOperations.getStringValue;
import static com.android.server.integrity.utils.TestUtils.getBits;
import static com.android.server.integrity.utils.TestUtils.getBytes;
import static com.android.server.integrity.utils.TestUtils.getValueBits;

import static com.google.common.truth.Truth.assertThat;

import android.content.integrity.IntegrityUtils;

import com.android.server.integrity.model.BitInputStream;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RunWith(JUnit4.class)
public class BinaryFileOperationsTest {

    private static final String IS_NOT_HASHED = "0";
    private static final String IS_HASHED = "1";
    private static final String PACKAGE_NAME = "com.test.app";
    private static final String APP_CERTIFICATE = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    @Test
    public void testGetStringValue() throws IOException {
        byte[] stringBytes =
                getBytes(
                        IS_NOT_HASHED
                                + getBits(PACKAGE_NAME.length(), VALUE_SIZE_BITS)
                                + getValueBits(PACKAGE_NAME));
        BitInputStream inputStream = new BitInputStream(new ByteArrayInputStream(stringBytes));

        String resultString = getStringValue(inputStream);

        assertThat(resultString).isEqualTo(PACKAGE_NAME);
    }

    @Test
    public void testGetHashedStringValue() throws IOException {
        byte[] ruleBytes =
                getBytes(
                        IS_HASHED
                                + getBits(APP_CERTIFICATE.length(), VALUE_SIZE_BITS)
                                + getValueBits(APP_CERTIFICATE));
        BitInputStream inputStream = new BitInputStream(new ByteArrayInputStream(ruleBytes));

        String resultString = getStringValue(inputStream);

        assertThat(resultString)
                .isEqualTo(IntegrityUtils.getHexDigest(
                        APP_CERTIFICATE.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void testGetStringValue_withSizeAndHashingInfo() throws IOException {
        byte[] ruleBytes = getBytes(getValueBits(PACKAGE_NAME));
        BitInputStream inputStream = new BitInputStream(new ByteArrayInputStream(ruleBytes));

        String resultString = getStringValue(inputStream,
                PACKAGE_NAME.length(), /* isHashedValue= */false);

        assertThat(resultString).isEqualTo(PACKAGE_NAME);
    }

    @Test
    public void testGetIntValue() throws IOException {
        int randomValue = 15;
        byte[] ruleBytes = getBytes(getBits(randomValue, /* numOfBits= */ 32));
        BitInputStream inputStream = new BitInputStream(new ByteArrayInputStream(ruleBytes));

        assertThat(getIntValue(inputStream)).isEqualTo(randomValue);
    }

    @Test
    public void testGetBooleanValue_true() throws IOException {
        String booleanValue = "1";
        byte[] ruleBytes = getBytes(booleanValue);
        BitInputStream inputStream = new BitInputStream(new ByteArrayInputStream(ruleBytes));

        assertThat(getBooleanValue(inputStream)).isEqualTo(true);
    }

    @Test
    public void testGetBooleanValue_false() throws IOException {
        String booleanValue = "0";
        byte[] ruleBytes = getBytes(booleanValue);
        BitInputStream inputStream = new BitInputStream(new ByteArrayInputStream(ruleBytes));

        assertThat(getBooleanValue(inputStream)).isEqualTo(false);
    }
}
