/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.inputmethod;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.annotation.NonNull;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

@RunWith(AndroidJUnit4.class)
public final class ImmutableSparseArrayTest {

    @Test
    public void testEmptyObject() {
        final ImmutableSparseArray<Object> empty = ImmutableSparseArray.empty();

        assertThat(empty.size()).isEqualTo(0);
        verifyCommonBehaviors(empty);
    }

    @Test
    public void testEmptyMethod() {
        assertThat(ImmutableSparseArray.empty()).isSameInstanceAs(ImmutableSparseArray.empty());
    }

    @Test
    public void testCloneWithPutOrSelf_appendingFromEmpty() {
        final int key1 = 1;
        final Object value1 = new Object();
        final int key2 = -2;  // intentionally negative
        final Object value2 = new Object();
        final int key3 = -3;  // intentionally negative
        final Object value3 = new Object();
        final int key4 = 4;
        final Object value4 = new Object();

        final ImmutableSparseArray<Object> oneItemArray = ImmutableSparseArray.empty()
                .cloneWithPutOrSelf(key1, value1);
        verifyCommonBehaviors(oneItemArray);
        assertThat(oneItemArray.size()).isEqualTo(1);
        assertThat(oneItemArray.get(key1)).isSameInstanceAs(value1);

        final ImmutableSparseArray<Object> twoItemArray =
                oneItemArray.cloneWithPutOrSelf(key2, value2);
        assertThat(twoItemArray).isNotSameInstanceAs(oneItemArray);
        verifyCommonBehaviors(twoItemArray);
        assertThat(twoItemArray.size()).isEqualTo(2);
        assertThat(twoItemArray.get(key1)).isSameInstanceAs(value1);
        assertThat(twoItemArray.get(key2)).isSameInstanceAs(value2);

        final ImmutableSparseArray<Object> threeItemArray =
                twoItemArray.cloneWithPutOrSelf(key3, value3);
        assertThat(threeItemArray).isNotSameInstanceAs(twoItemArray);
        verifyCommonBehaviors(threeItemArray);
        assertThat(threeItemArray.size()).isEqualTo(3);
        assertThat(threeItemArray.get(key1)).isSameInstanceAs(value1);
        assertThat(threeItemArray.get(key2)).isSameInstanceAs(value2);
        assertThat(threeItemArray.get(key3)).isSameInstanceAs(value3);

        final ImmutableSparseArray<Object> fourItemArray =
                threeItemArray.cloneWithPutOrSelf(key4, value4);
        assertThat(fourItemArray).isNotSameInstanceAs(threeItemArray);
        verifyCommonBehaviors(fourItemArray);
        assertThat(fourItemArray.size()).isEqualTo(4);
        assertThat(fourItemArray.get(key1)).isSameInstanceAs(value1);
        assertThat(fourItemArray.get(key2)).isSameInstanceAs(value2);
        assertThat(fourItemArray.get(key3)).isSameInstanceAs(value3);
        assertThat(fourItemArray.get(key4)).isSameInstanceAs(value4);
    }

    @Test
    public void testCloneWithPutOrSelf_returnSelf() {
        final int key1 = 1;
        final Object value1 = new Object();
        final ImmutableSparseArray<Object> array = ImmutableSparseArray
                .empty()
                .cloneWithPutOrSelf(key1, value1);
        assertThat(array.cloneWithPutOrSelf(key1, value1)).isSameInstanceAs(array);
    }

    @Test
    public void testCloneWithPutOrSelf_updateExistingValue() {
        final int key1 = 1;
        final Object value1 = new Object();
        final int key2 = 2;
        final Object value2 = new Object();
        final Object value2updated = new Object();
        final int key3 = 3;
        final Object value3 = new Object();

        final ImmutableSparseArray<Object> array = ImmutableSparseArray
                .empty()
                .cloneWithPutOrSelf(key1, value1)
                .cloneWithPutOrSelf(key2, value2)
                .cloneWithPutOrSelf(key3, value3);

        final var updatedArray = array.cloneWithPutOrSelf(key2, value2updated);
        verifyCommonBehaviors(updatedArray);

        assertThat(updatedArray.size()).isEqualTo(3);
        assertThat(updatedArray.get(key1)).isSameInstanceAs(value1);
        assertThat(updatedArray.get(key2)).isSameInstanceAs(value2updated);
        assertThat(updatedArray.get(key3)).isSameInstanceAs(value3);
    }

    @Test
    public void testCloneWithRemoveOrSelf_empty() {
        final ImmutableSparseArray<Object> empty = ImmutableSparseArray.empty();
        assertThat(empty.cloneWithRemoveOrSelf(0)).isSameInstanceAs(empty);
    }

    @Test
    public void testCloneWithRemoveOrSelf_singleInstance() {
        final int key = 1;
        final Object value = new Object();
        final ImmutableSparseArray<Object> array = ImmutableSparseArray
                .empty()
                .cloneWithPutOrSelf(key, value);
        assertThat(array.cloneWithRemoveOrSelf(key)).isSameInstanceAs(ImmutableSparseArray.empty());
    }

    @Test
    public void testCloneWithRemoveOrSelf_firstItem() {
        final int key1 = 1;
        final Object value1 = new Object();
        final int key2 = 2;
        final Object value2 = new Object();
        final int key3 = 3;
        final Object value3 = new Object();

        final ImmutableSparseArray<Object> array = ImmutableSparseArray
                .empty()
                .cloneWithPutOrSelf(key1, value1)
                .cloneWithPutOrSelf(key2, value2)
                .cloneWithPutOrSelf(key3, value3)
                .cloneWithRemoveOrSelf(key1);
        verifyCommonBehaviors(array);

        assertThat(array.size()).isEqualTo(2);
        assertThat(array.get(key1)).isNull();
        assertThat(array.get(key2)).isSameInstanceAs(value2);
        assertThat(array.get(key3)).isSameInstanceAs(value3);
        assertThat(array.keyAt(0)).isEqualTo(key2);
        assertThat(array.keyAt(1)).isEqualTo(key3);
    }

    @Test
    public void testCloneWithRemoveOrSelf_lastItem() {
        final int key1 = 1;
        final Object value1 = new Object();
        final int key2 = 2;
        final Object value2 = new Object();
        final int key3 = 3;
        final Object value3 = new Object();

        final ImmutableSparseArray<Object> array = ImmutableSparseArray
                .empty()
                .cloneWithPutOrSelf(key1, value1)
                .cloneWithPutOrSelf(key2, value2)
                .cloneWithPutOrSelf(key3, value3)
                .cloneWithRemoveOrSelf(key3);
        verifyCommonBehaviors(array);

        assertThat(array.size()).isEqualTo(2);
        assertThat(array.get(key1)).isSameInstanceAs(value1);
        assertThat(array.get(key2)).isSameInstanceAs(value2);
        assertThat(array.get(key3)).isNull();
    }

    @Test
    public void testCloneWithRemoveOrSelf_middleItem() {
        final int key1 = 1;
        final Object value1 = new Object();
        final int key2 = 2;
        final Object value2 = new Object();
        final int key3 = 3;
        final Object value3 = new Object();

        final ImmutableSparseArray<Object> array = ImmutableSparseArray
                .empty()
                .cloneWithPutOrSelf(key1, value1)
                .cloneWithPutOrSelf(key2, value2)
                .cloneWithPutOrSelf(key3, value3)
                .cloneWithRemoveOrSelf(key2);
        verifyCommonBehaviors(array);

        assertThat(array.size()).isEqualTo(2);
        assertThat(array.get(key1)).isSameInstanceAs(value1);
        assertThat(array.get(key2)).isNull();
        assertThat(array.get(key3)).isSameInstanceAs(value3);
    }

    @Test
    public void testCloneWithRemoveOrSelf_nonExistentItem() {
        final int key1 = 1;
        final Object value1 = new Object();
        final int key2 = 2;
        final Object value2 = new Object();
        final int key3 = 3;
        final Object value3 = new Object();
        final int key4 = 4;

        final ImmutableSparseArray<Object> array = ImmutableSparseArray
                .empty()
                .cloneWithPutOrSelf(key1, value1)
                .cloneWithPutOrSelf(key2, value2)
                .cloneWithPutOrSelf(key3, value3);

        assertThat(array.cloneWithRemoveOrSelf(key4)).isSameInstanceAs(array);
    }

    @Test
    public void testForEach() {
        final int key1 = 1;
        final Object value1 = new Object();
        final int key2 = 2;
        final Object value2 = new Object();
        final int key3 = 3;
        final Object value3 = new Object();

        final ImmutableSparseArray<Object> array = ImmutableSparseArray
                .empty()
                .cloneWithPutOrSelf(key1, value1)
                .cloneWithPutOrSelf(key2, value2)
                .cloneWithPutOrSelf(key3, value3);

        final ArrayList<Object> list = new ArrayList<>();
        array.forEach(list::add);
        assertThat(list).containsExactlyElementsIn(new Object[]{ value1, value2, value3 })
                .inOrder();
    }


    private void verifyCommonBehaviors(@NonNull ImmutableSparseArray<Object> sparseArray) {
        verifyInvalidKeyBehaviors(sparseArray);
        verifyOutOfBoundsBehaviors(sparseArray);
    }

    private void verifyInvalidKeyBehaviors(@NonNull ImmutableSparseArray<Object> sparseArray) {
        final int invalid_key = -123456678;
        assertThat(sparseArray.get(invalid_key)).isNull();
        assertThat(sparseArray.indexOfKey(invalid_key)).isEqualTo(-1);
    }

    private void verifyOutOfBoundsBehaviors(@NonNull ImmutableSparseArray<Object> sparseArray) {
        final int size = sparseArray.size();
        assertThrows(ArrayIndexOutOfBoundsException.class,
                () -> sparseArray.keyAt(size));
        assertThrows(ArrayIndexOutOfBoundsException.class,
                () -> sparseArray.valueAt(size));
        assertThrows(ArrayIndexOutOfBoundsException.class,
                () -> sparseArray.keyAt(-1));
        assertThrows(ArrayIndexOutOfBoundsException.class,
                () -> sparseArray.valueAt(-1));
    }
}
