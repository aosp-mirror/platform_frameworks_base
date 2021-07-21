/*
 * Copyright 2020 The Android Open Source Project
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

package android.app.appsearch.util;

import static com.google.common.truth.Truth.assertThat;

import android.os.Build;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.os.Parcelable;
import android.util.Size;
import android.util.SizeF;
import android.util.SparseArray;

import com.google.common.collect.ImmutableList;

import org.junit.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.UUID;

public class BundleUtilTest {
    @Test
    public void testDeepEquals_self() {
        Bundle one = new Bundle();
        one.putString("a", "a");
        assertThat(BundleUtil.deepEquals(one, one)).isTrue();
    }

    @Test
    public void testDeepEquals_simple() {
        Bundle one = new Bundle();
        one.putString("a", "a");

        Bundle two = new Bundle();
        two.putString("a", "a");

        assertThat(one).isNotEqualTo(two);
        assertThat(BundleUtil.deepEquals(one, two)).isTrue();
    }

    @Test
    public void testDeepEquals_keyMismatch() {
        Bundle one = new Bundle();
        one.putString("a", "a");

        Bundle two = new Bundle();
        two.putString("a", "a");
        two.putString("b", "b");
        assertThat(BundleUtil.deepEquals(one, two)).isFalse();
    }

    @Test
    public void testDeepEquals_thorough_equal() {
        Bundle[] inputs = new Bundle[2];
        for (int i = 0; i < 2; i++) {
            inputs[i] = createThoroughBundle();
        }
        assertThat(inputs[0]).isNotEqualTo(inputs[1]);
        assertThat(BundleUtil.deepEquals(inputs[0], inputs[1])).isTrue();
    }

    @Test
    public void testDeepEquals_thorough_notEqual() {
        Bundle[] inputs = new Bundle[2];
        for (int i = 0; i < 2; i++) {
            Bundle b = createThoroughBundle();
            // Create a difference
            assertThat(b.containsKey("doubleArray")).isTrue();
            b.putDoubleArray("doubleArray", new double[] {18., i});
            inputs[i] = b;
        }
        assertThat(inputs[0]).isNotEqualTo(inputs[1]);
        assertThat(BundleUtil.deepEquals(inputs[0], inputs[1])).isFalse();
    }

    @Test
    public void testDeepEquals_nestedNotEquals() {
        Bundle one = new Bundle();
        one.putString("a", "a");
        Bundle two = new Bundle();
        two.putBundle("b", one);
        Bundle twoClone = new Bundle();
        twoClone.putBundle("b", one);
        Bundle three = new Bundle();
        three.putBundle("b", two);

        ArrayList<Bundle> listOne = new ArrayList<>(ImmutableList.of(one, two, three));
        ArrayList<Bundle> listOneClone = new ArrayList<>(ImmutableList.of(one, twoClone, three));
        ArrayList<Bundle> listTwo = new ArrayList<>(ImmutableList.of(one, three, two));
        Bundle b1 = new Bundle();
        b1.putParcelableArrayList("key", listOne);
        Bundle b1Clone = new Bundle();
        b1Clone.putParcelableArrayList("key", listOneClone);
        Bundle b2 = new Bundle();
        b2.putParcelableArrayList("key", listTwo);

        assertThat(b1).isNotEqualTo(b1Clone);
        assertThat(BundleUtil.deepEquals(b1, b1Clone)).isTrue();
        assertThat(BundleUtil.deepEquals(b1, b2)).isFalse();
        assertThat(BundleUtil.deepEquals(b1Clone, b2)).isFalse();
    }

    @Test
    public void testDeepEquals_sparseArray() {
        Parcelable parcelable1 = new ParcelUuid(UUID.randomUUID());
        Parcelable parcelable2 = new ParcelUuid(UUID.randomUUID());
        Parcelable parcelable3 = new ParcelUuid(UUID.randomUUID());

        SparseArray<Parcelable> array1 = new SparseArray<>();
        array1.put(1, parcelable1);
        array1.put(10, parcelable2);

        SparseArray<Parcelable> array1Clone = new SparseArray<>();
        array1Clone.put(1, parcelable1);
        array1Clone.put(10, parcelable2);

        SparseArray<Parcelable> array2 = new SparseArray<>();
        array2.put(1, parcelable1);
        array2.put(10, parcelable3); // Different

        Bundle b1 = new Bundle();
        b1.putSparseParcelableArray("array1", array1);
        Bundle b1Clone = new Bundle();
        b1Clone.putSparseParcelableArray("array1", array1Clone);
        Bundle b2 = new Bundle();
        b2.putSparseParcelableArray("array1", array2);

        assertThat(b1).isNotEqualTo(b1Clone);
        assertThat(BundleUtil.deepEquals(b1, b1Clone)).isTrue();
        assertThat(BundleUtil.deepEquals(b1, b2)).isFalse();
        assertThat(BundleUtil.deepEquals(b1Clone, b2)).isFalse();
    }

    @Test
    public void testDeepHashCode_same() {
        Bundle[] inputs = new Bundle[2];
        for (int i = 0; i < 2; i++) {
            inputs[i] = createThoroughBundle();
        }
        assertThat(BundleUtil.deepHashCode(inputs[0]))
                .isEqualTo(BundleUtil.deepHashCode(inputs[1]));
    }

    @Test
    public void testDeepHashCode_different() {
        Bundle[] inputs = new Bundle[2];
        for (int i = 0; i < 2; i++) {
            Bundle b = createThoroughBundle();
            // Create a difference
            assertThat(b.containsKey("doubleArray")).isTrue();
            b.putDoubleArray("doubleArray", new double[] {18., i});
            inputs[i] = b;
        }
        assertThat(BundleUtil.deepHashCode(inputs[0]))
                .isNotEqualTo(BundleUtil.deepHashCode(inputs[1]));
    }

    @Test
    public void testHashCode_sparseArray() {
        Parcelable parcelable1 = new ParcelUuid(UUID.randomUUID());
        Parcelable parcelable2 = new ParcelUuid(UUID.randomUUID());
        Parcelable parcelable3 = new ParcelUuid(UUID.randomUUID());

        SparseArray<Parcelable> array1 = new SparseArray<>();
        array1.put(1, parcelable1);
        array1.put(10, parcelable2);

        SparseArray<Parcelable> array1Clone = new SparseArray<>();
        array1Clone.put(1, parcelable1);
        array1Clone.put(10, parcelable2);

        SparseArray<Parcelable> array2 = new SparseArray<>();
        array2.put(1, parcelable1);
        array2.put(10, parcelable3); // Different

        Bundle b1 = new Bundle();
        b1.putSparseParcelableArray("array1", array1);
        Bundle b1Clone = new Bundle();
        b1Clone.putSparseParcelableArray("array1", array1Clone);
        Bundle b2 = new Bundle();
        b2.putSparseParcelableArray("array1", array2);

        assertThat(b1.hashCode()).isNotEqualTo(b1Clone.hashCode());
        assertThat(BundleUtil.deepHashCode(b1)).isEqualTo(BundleUtil.deepHashCode(b1Clone));
        assertThat(BundleUtil.deepHashCode(b1)).isNotEqualTo(BundleUtil.deepHashCode(b2));
    }

    @Test
    public void testDeepHashCode_differentKeys() {
        Bundle[] inputs = new Bundle[2];
        for (int i = 0; i < 2; i++) {
            Bundle b = new Bundle();
            b.putString("key" + i, "value");
            inputs[i] = b;
        }
        assertThat(BundleUtil.deepHashCode(inputs[0]))
                .isNotEqualTo(BundleUtil.deepHashCode(inputs[1]));
    }

    @Test
    public void testDeepCopy() {
        Bundle input = createThoroughBundle();
        Bundle output = BundleUtil.deepCopy(input);
        assertThat(input).isNotSameInstanceAs(output);
        assertThat(BundleUtil.deepEquals(input, output)).isTrue();

        output.getIntegerArrayList("integerArrayList").add(5);
        assertThat(BundleUtil.deepEquals(input, output)).isFalse();
    }

    private static Bundle createThoroughBundle() {
        Bundle toy1 = new Bundle();
        toy1.putString("a", "a");
        Bundle toy2 = new Bundle();
        toy2.putInt("b", 2);

        Bundle b = new Bundle();
        // BaseBundle stuff
        b.putBoolean("boolean", true);
        b.putByte("byte", (byte) 1);
        b.putChar("char", 'a');
        b.putShort("short", (short) 2);
        b.putInt("int", 3);
        b.putLong("long", 4L);
        b.putFloat("float", 5f);
        b.putDouble("double", 6f);
        b.putString("string", "b");
        b.putCharSequence("charSequence", "c");
        b.putIntegerArrayList("integerArrayList", new ArrayList<>(ImmutableList.of(7, 8)));
        b.putStringArrayList("stringArrayList", new ArrayList<>(ImmutableList.of("d", "e")));
        b.putCharSequenceArrayList(
                "charSequenceArrayList", new ArrayList<>(ImmutableList.of("f", "g")));
        b.putSerializable("serializable", new BigDecimal(9));
        b.putBooleanArray("booleanArray", new boolean[] {true, false, true});
        b.putByteArray("byteArray", new byte[] {(byte) 10, (byte) 11});
        b.putShortArray("shortArray", new short[] {(short) 12, (short) 13});
        b.putCharArray("charArray", new char[] {'h', 'i'});
        b.putLongArray("longArray", new long[] {14L, 15L});
        b.putFloatArray("floatArray", new float[] {16f, 17f});
        b.putDoubleArray("doubleArray", new double[] {18., 19.});
        b.putStringArray("stringArray", new String[] {"j", "k"});
        b.putCharSequenceArray("charSequenceArrayList", new CharSequence[] {"l", "m"});

        // Bundle stuff
        b.putParcelable("parcelable", toy1);
        if (Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            b.putSize("size", new Size(20, 21));
            b.putSizeF("sizeF", new SizeF(22f, 23f));
        }
        b.putParcelableArray("parcelableArray", new Parcelable[] {toy1, toy2});
        b.putParcelableArrayList(
                "parcelableArrayList", new ArrayList<>(ImmutableList.of(toy1, toy2)));
        SparseArray<Parcelable> sparseArray = new SparseArray<>();
        sparseArray.put(24, toy1);
        sparseArray.put(1025, toy2);
        b.putSparseParcelableArray("sparceParcelableArray", sparseArray);
        b.putBundle("bundle", toy1);

        return b;
    }
}
