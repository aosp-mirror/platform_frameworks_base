/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.asllib.marshallable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RunWith(JUnit4.class)
public class DataTypeEqualityTest {

    public static final List<String> OPTIONAL_FIELD_NAMES =
            List.of("isDataDeletable", "isDataEncrypted");
    public static final List<String> OPTIONAL_FIELD_NAMES_OD =
            List.of("is_data_deletable", "is_data_encrypted");

    /** Logic for setting up tests (empty if not yet needed). */
    public static void main(String[] params) throws Exception {}

    @Before
    public void setUp() throws Exception {
        System.out.println("set up.");
    }

    /** Test for equality different order. */
    @Test
    public void testEqualityDifferentOrder() throws Exception {
        System.out.println("starting testEqualityDifferentOrder.");
        DataType dataType1 =
                new DataType(
                        "datatype1",
                        Arrays.asList(
                                DataType.Purpose.ADVERTISING, DataType.Purpose.PERSONALIZATION),
                        true,
                        false,
                        null);
        DataType dataType2 =
                new DataType(
                        "datatype1",
                        Arrays.asList(
                                DataType.Purpose.PERSONALIZATION, DataType.Purpose.ADVERTISING),
                        true,
                        false,
                        null);
        assertEquals(dataType1, dataType2);
        assertEquals(dataType2, dataType1);
    }

    /** Test for contains different order. */
    @Test
    public void testContainsDifferentOrder() throws Exception {
        System.out.println("starting testContainsDifferentOrder.");
        DataType dataType1 =
                new DataType(
                        "datatype1",
                        Arrays.asList(
                                DataType.Purpose.ADVERTISING, DataType.Purpose.PERSONALIZATION),
                        true,
                        false,
                        null);
        DataType dataType2 =
                new DataType(
                        "datatype1",
                        Arrays.asList(
                                DataType.Purpose.PERSONALIZATION, DataType.Purpose.ADVERTISING),
                        true,
                        false,
                        null);
        Set<DataType> set = new HashSet<>();
        set.add(dataType1);
        assertTrue(set.contains(dataType2));
    }

    /** Test for inequality. */
    @Test
    public void testInequality() throws Exception {
        System.out.println("starting testInequality.");
        DataType dataType1 =
                new DataType(
                        "datatype1",
                        Arrays.asList(
                                DataType.Purpose.ADVERTISING, DataType.Purpose.PERSONALIZATION),
                        true,
                        false,
                        null);
        DataType dataType2 =
                new DataType(
                        "datatype1",
                        Arrays.asList(DataType.Purpose.PERSONALIZATION),
                        true,
                        false,
                        null);
        assertNotEquals(dataType1, dataType2);
        assertNotEquals(dataType2, dataType1);
    }

    /** Test for inequality bool. */
    @Test
    public void testInequalityBool() throws Exception {
        System.out.println("starting testInequalityBool.");
        DataType dataType1 =
                new DataType(
                        "datatype1",
                        Arrays.asList(
                                DataType.Purpose.ADVERTISING, DataType.Purpose.PERSONALIZATION),
                        true,
                        false,
                        null);
        DataType dataType2 =
                new DataType(
                        "datatype1",
                        Arrays.asList(
                                DataType.Purpose.ADVERTISING, DataType.Purpose.PERSONALIZATION),
                        true,
                        false,
                        true);
        assertNotEquals(dataType1, dataType2);
        assertNotEquals(dataType2, dataType1);
    }

    /** Test for does not contain. */
    @Test
    public void testDoesNotContain() throws Exception {
        System.out.println("starting testDoesNotContain.");
        System.out.println("starting testContainsDifferentOrder.");
        DataType dataType1 =
                new DataType(
                        "datatype1",
                        Arrays.asList(
                                DataType.Purpose.ADVERTISING, DataType.Purpose.PERSONALIZATION),
                        true,
                        false,
                        null);
        DataType dataType2 =
                new DataType(
                        "datatype1",
                        Arrays.asList(DataType.Purpose.PERSONALIZATION),
                        true,
                        false,
                        null);
        Set<DataType> set = new HashSet<>();
        set.add(dataType1);
        assertFalse(set.contains(dataType2));
    }
}
