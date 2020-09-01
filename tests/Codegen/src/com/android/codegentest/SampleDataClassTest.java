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
package com.android.codegentest;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;

import static java.util.concurrent.TimeUnit.SECONDS;

import android.net.LinkAddress;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.SparseArray;
import android.util.SparseIntArray;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests {@link SampleDataClass} after it's augmented with dataclass codegen.
 *
 * Use {@code $ . runTest.sh} to run.
 */
@RunWith(AndroidJUnit4.class)
public class SampleDataClassTest {

    private SampleDataClass mSpecimen = newBuilder().build();

    private static SampleDataClass.Builder newBuilder() {
        return newInvalidBuilder()
                .setNum(42)
                .setNum2(42)
                .setNum4(42)
                .setName4("foobar")
                .setLinkAddresses5();
    }

    private static SampleDataClass.Builder newInvalidBuilder() {
        return new SampleDataClass.Builder(1, 2, 3, "a", 0, null)
                .setName("some parcelable")
                .setFlags(SampleDataClass.FLAG_MANUAL_REQUEST);
    }

    @Test
    public void testParcelling_producesEqualInstance() {
        SampleDataClass copy = parcelAndUnparcel(mSpecimen, SampleDataClass.CREATOR);
        assertEquals(mSpecimen, copy);
        assertEquals(mSpecimen.hashCode(), copy.hashCode());
    }

    @Test
    public void testParcelling_producesInstanceWithEqualFields() {
        SampleDataClass copy = parcelAndUnparcel(mSpecimen, SampleDataClass.CREATOR);
        copy.forEachField((self, copyFieldName, copyFieldValue) -> {
            mSpecimen.forEachField((self2, specimenFieldName, specimenFieldValue) -> {
                if (copyFieldName.equals(specimenFieldName)
                        && !copyFieldName.equals("pattern")
                        && (specimenFieldValue == null
                                || !specimenFieldValue.getClass().isArray())) {
                    assertEquals("Mismatched field values for " + copyFieldName,
                            specimenFieldValue, copyFieldValue);
                }
            });
        });
    }

    @Test
    public void testCustomParcelling_instanceIsCached() {
        parcelAndUnparcel(mSpecimen, SampleDataClass.CREATOR);
        parcelAndUnparcel(mSpecimen, SampleDataClass.CREATOR);
        assertEquals(1, MyDateParcelling.sInstanceCount.get());
    }

    @Test
    public void testDefaultFieldValue_isPropagated() {
        assertEquals(new Date(42 * 42), mSpecimen.getDate());
    }

    @Test
    public void testForEachField_avoidsBoxing() {
        AtomicInteger intFieldCount = new AtomicInteger(0);
        mSpecimen.forEachField(
                (self, name, intValue) -> intFieldCount.getAndIncrement(),
                (self, name, objectValue) -> {
                    if (objectValue != null) {
                        assertThat("Boxed field " + name,
                                objectValue, not(instanceOf(Integer.class)));
                    }
                });
        assertThat(intFieldCount.get(), greaterThanOrEqualTo(1));
    }

    @Test
    public void testToString_containsEachField() {
        String toString = mSpecimen.toString();

        mSpecimen.forEachField((self, name, value) -> {
            assertThat(toString, containsString(name));
            if (value instanceof Integer) {
                // Could be flags, their special toString tested separately
            } else if (value instanceof Object[]) {
                assertThat(toString, containsString(Arrays.toString((Object[]) value)));
            } else if (value != null && value.getClass().isArray()) {
                // Primitive array, uses multiple specialized Arrays.toString overloads
            } else {
                assertThat(toString, containsString("" + value));
            }
        });
    }

    @Test
    public void testBuilder_propagatesValuesToInstance() {
        assertEquals(43, newBuilder().setNum(43).build().getNum());
    }

    @Test
    public void testPluralFields_canHaveCustomSingularBuilderName() {
        newBuilder().addLinkAddress(new LinkAddress("127.0.0.1/24"));
    }

    @Test(expected = IllegalStateException.class)
    public void testBuilder_usableOnlyOnce() {
        SampleDataClass.Builder builder = newBuilder();
        builder.build();
        builder.build();
    }

    @Test(expected = IllegalStateException.class)
    public void testBuilder_performsValidation() {
        newInvalidBuilder().build();
    }

    @Test
    public void testIntDefs_haveCorrectToString() {
        int flagsAsInt = SampleDataClass.FLAG_MANUAL_REQUEST
                | SampleDataClass.FLAG_COMPATIBILITY_MODE_REQUEST;
        String flagsAsString = SampleDataClass.requestFlagsToString(flagsAsInt);

        assertThat(flagsAsString, containsString("MANUAL_REQUEST"));
        assertThat(flagsAsString, containsString("COMPATIBILITY_MODE_REQUEST"));
        assertThat(flagsAsString, not(containsString("1")));
        assertThat(flagsAsString, not(containsString("" + flagsAsInt)));

        String dataclassToString = newBuilder()
                .setFlags(flagsAsInt)
                .setState(SampleDataClass.STATE_UNDEFINED)
                .build()
                .toString();
        assertThat(dataclassToString, containsString(flagsAsString));
        assertThat(dataclassToString, containsString("UNDEFINED"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFlags_getValidated() {
        newBuilder().setFlags(12345).build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testIntEnums_getValidated() {
        newBuilder().setState(12345).build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testStringEnums_getValidated() {
        newBuilder().setStateName("foo").build();
    }

    @Test(expected = IllegalStateException.class)
    public void testCustomValidation_isTriggered() {
        newBuilder().setNum2(-1).setNum4(1).build();
    }

    @Test
    public void testLazyInit_isLazilyCalledOnce() {
        assertNull(mSpecimen.mTmpStorage);

        int[] tmpStorage = mSpecimen.getTmpStorage();
        assertNotNull(tmpStorage);
        assertSame(tmpStorage, mSpecimen.mTmpStorage);

        int[] tmpStorageAgain = mSpecimen.getTmpStorage();
        assertSame(tmpStorage, tmpStorageAgain);
    }

    @Test(expected = IllegalStateException.class)
    public void testCustomAnnotationValidation_isRun() {
        newBuilder().setDayOfWeek(42).build();
    }

    @Test
    public void testDataStructures_parcelCorrectly() {
        SampleWithCustomBuilder otherParcelable = new SampleWithCustomBuilder.Builder().setDelay(3, SECONDS).build();

        ParcelAllTheThingsDataClass instance = new ParcelAllTheThingsDataClass.Builder()
                .setIntArray(40, 41)
                .addMap("foo", otherParcelable)
                .setSparseArray(new SparseArray<SampleWithCustomBuilder>() {{
                    put(45, otherParcelable);
                }})
                .setSparseIntArray(new SparseIntArray() {{
                    put(48, 49);
                }})
                .addStringMap("foo2", "fooValue")
                .setStringArray("foo", "bar")
                .addStringList("foo")
                .build();

        ParcelAllTheThingsDataClass unparceledInstance =
                parcelAndUnparcel(instance, ParcelAllTheThingsDataClass.CREATOR);

        // SparseArray and friends don't implement equals
        // so just compare string representations instead
        assertEquals(instance.toString(), unparceledInstance.toString());
    }

    @Test
    public void testNestedDataClasses_notMangledWhenParceled() {
        assertEqualsAfterParcelling(
                new SampleWithNestedDataClasses.NestedDataClass("1"),
                SampleWithNestedDataClasses.NestedDataClass.CREATOR);

        assertEqualsAfterParcelling(
                new SampleWithNestedDataClasses.NestedDataClass2("2"),
                SampleWithNestedDataClasses.NestedDataClass2.CREATOR);

        assertEqualsAfterParcelling(
                new SampleWithNestedDataClasses.NestedDataClass2.NestedDataClass3(3),
                SampleWithNestedDataClasses.NestedDataClass2.NestedDataClass3.CREATOR);
    }

    private static <T extends Parcelable> void assertEqualsAfterParcelling(
            T p, Parcelable.Creator<T> creator) {
        assertEquals(p, parcelAndUnparcel(p, creator));
    }

    private static <T extends Parcelable> T parcelAndUnparcel(
            T original, Parcelable.Creator<T> creator) {
        Parcel p = Parcel.obtain();
        try {
            original.writeToParcel(p, 0);
            p.setDataPosition(0);
            return creator.createFromParcel(p);
        } finally {
            p.recycle();
        }
    }
}
