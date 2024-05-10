/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.os;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.ravenwood.RavenwoodRule;
import android.util.ArrayMap;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SmallTest
@RunWith(AndroidJUnit4.class)
public final class ParcelNullabilityTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    @Test
    public void nullByteArray() {
        Parcel p = Parcel.obtain();
        p.writeByteArray(null);

        assertNull(throughBytes(p).createByteArray());
    }

    @Test
    public void nullBlob() {
        Parcel p = Parcel.obtain();
        p.writeBlob(null);

        assertNull(throughBytes(p).readBlob());
    }

    @Test
    public void nullString() {
        Parcel p = Parcel.obtain();
        p.writeString(null);

        assertNull(throughBytes(p).readString());
    }

    @Test
    @IgnoreUnderRavenwood(blockedBy = Parcel.class)
    public void nullCharSequence() {
        Parcel p = Parcel.obtain();
        p.writeCharSequence(null);

        assertNull(throughBytes(p).readCharSequence());
    }

    @Test
    @IgnoreUnderRavenwood(blockedBy = Parcel.class)
    public void nullStrongBinder() {
        Parcel p = Parcel.obtain();
        p.writeStrongBinder(null);

        assertNull(throughBytes(p).readStrongBinder());
    }

    @Test
    @IgnoreUnderRavenwood(blockedBy = Parcel.class)
    public void nullStringInterface() {
        Parcel p = Parcel.obtain();
        p.writeStrongInterface(null);

        assertNull(throughBytes(p).readStrongBinder());
    }

    @Test
    public void nullFileDescriptor() {
        Parcel p = Parcel.obtain();
        try {
            p.writeFileDescriptor(null);
            fail();
        } catch (RuntimeException expected) {
        }
    }

    @Test
    public void nullRawFileDescriptor() {
        Parcel p = Parcel.obtain();
        try {
            p.writeRawFileDescriptor(null);
            fail();
        } catch (RuntimeException expected) {
        }
    }

    @Test
    public void nullRawFileDescriptorArray() {
        Parcel p = Parcel.obtain();
        p.writeRawFileDescriptorArray(null);

        assertNull(throughBytes(p).createRawFileDescriptorArray());
    }

    @Test
    public void nullMap() {
        Parcel p = Parcel.obtain();
        p.writeMap(null);

        Map<Object, Object> map = new HashMap<>();
        throughBytes(p).readMap(map, null);
        assertTrue(map.isEmpty());
    }

    @Test
    public void nullArrayMap() {
        Parcel p = Parcel.obtain();
        p.writeArrayMap(null);

        ArrayMap<Object, Object> map = new ArrayMap<>();
        throughBytes(p).readArrayMap(map, null);
        assertTrue(map.isEmpty());
    }

    @Test
    public void nullArraySet() {
        Parcel p = Parcel.obtain();
        p.writeArraySet(null);

        assertNull(throughBytes(p).readArraySet(null));
    }

    @Test
    public void nullBundle() {
        Parcel p = Parcel.obtain();
        p.writeBundle(null);

        assertNull(throughBytes(p).readBundle());
    }

    @Test
    public void nullPersistableBundle() {
        Parcel p = Parcel.obtain();
        p.writePersistableBundle(null);

        assertNull(throughBytes(p).readPersistableBundle());
    }

    @Test
    public void nullSize() {
        Parcel p = Parcel.obtain();
        try {
            p.writeSize(null);
            fail();
        } catch (RuntimeException expected) {
        }
    }

    @Test
    public void nullSizeF() {
        Parcel p = Parcel.obtain();
        try {
            p.writeSizeF(null);
            fail();
        } catch (RuntimeException expected) {
        }
    }

    @Test
    public void nullList() {
        Parcel p = Parcel.obtain();
        p.writeList(null);

        List<Object> map = new ArrayList<>();
        throughBytes(p).readList(map, null);
        assertTrue(map.isEmpty());
    }

    @Test
    public void nullArray() {
        Parcel p = Parcel.obtain();
        p.writeArray(null);

        assertNull(throughBytes(p).readArray(null));
    }

    @Test
    public void nullSparseArray() {
        Parcel p = Parcel.obtain();
        p.writeSparseArray(null);

        assertNull(throughBytes(p).readSparseArray(null));
    }

    @Test
    public void nullSparseBooleanArray() {
        Parcel p = Parcel.obtain();
        p.writeSparseBooleanArray(null);

        assertNull(throughBytes(p).readSparseBooleanArray());
    }

    @Test
    public void nullSparseIntArray() {
        Parcel p = Parcel.obtain();
        p.writeSparseIntArray(null);

        assertNull(throughBytes(p).readSparseIntArray());
    }

    @Test
    public void nullBooleanArray() {
        Parcel p = Parcel.obtain();
        p.writeBooleanArray(null);

        assertNull(throughBytes(p).createBooleanArray());
    }

    @Test
    public void nullCharArray() {
        Parcel p = Parcel.obtain();
        p.writeCharArray(null);

        assertNull(throughBytes(p).createCharArray());
    }

    @Test
    public void nullIntArray() {
        Parcel p = Parcel.obtain();
        p.writeIntArray(null);

        assertNull(throughBytes(p).createIntArray());
    }

    @Test
    public void nullLongArray() {
        Parcel p = Parcel.obtain();
        p.writeLongArray(null);

        assertNull(throughBytes(p).createLongArray());
    }

    @Test
    public void nullFloatArray() {
        Parcel p = Parcel.obtain();
        p.writeFloatArray(null);

        assertNull(throughBytes(p).createFloatArray());
    }

    @Test
    public void nullDoubleArray() {
        Parcel p = Parcel.obtain();
        p.writeDoubleArray(null);

        assertNull(throughBytes(p).createDoubleArray());
    }

    @Test
    public void nullStringArray() {
        Parcel p = Parcel.obtain();
        p.writeStringArray(null);

        assertNull(throughBytes(p).createStringArray());
    }

    @Test
    public void nullCharSequenceArray() {
        Parcel p = Parcel.obtain();
        p.writeCharSequenceArray(null);

        assertNull(throughBytes(p).readCharSequenceArray());
    }

    @Test
    public void nullCharSequenceList() {
        Parcel p = Parcel.obtain();
        p.writeCharSequenceList(null);

        assertNull(throughBytes(p).readCharSequenceList());
    }

    @Test
    public void nullBinderArray() {
        Parcel p = Parcel.obtain();
        p.writeBinderArray(null);

        assertNull(throughBytes(p).createBinderArray());
    }

    @Test
    public void nullTypedList() {
        Parcel p = Parcel.obtain();
        p.writeTypedList(null);

        assertNull(throughBytes(p).createTypedArrayList(null));
    }

    @Test
    public void nullStringList() {
        Parcel p = Parcel.obtain();
        p.writeStringList(null);

        assertNull(throughBytes(p).createStringArrayList());
    }

    @Test
    public void nullBinderList() {
        Parcel p = Parcel.obtain();
        p.writeBinderList(null);

        assertNull(throughBytes(p).createBinderArrayList());
    }

    @Test
    public void nullParcelableList() {
        Parcel p = Parcel.obtain();
        p.writeParcelableList(null, 0);

        List<Parcelable> list = new ArrayList<>();
        throughBytes(p).readParcelableList(list, null);
        assertTrue(list.isEmpty());
    }

    @Test
    public void nullTypedArray() {
        Parcel p = Parcel.obtain();
        p.writeTypedArray(null, 0);

        assertNull(throughBytes(p).createTypedArray(null));
    }

    @Test
    public void nullTypedObject() {
        Parcel p = Parcel.obtain();
        p.writeTypedObject(null, 0);

        assertNull(throughBytes(p).readTypedObject(null));
    }

    @Test
    public void nullValue() {
        Parcel p = Parcel.obtain();
        p.writeValue(null);

        assertNull(throughBytes(p).readValue(null));
    }

    @Test
    public void nullParcelable() {
        Parcel p = Parcel.obtain();
        p.writeParcelable(null, 0);

        assertNull(throughBytes(p).readParcelable(null));
    }

    @Test
    public void nullSerializable() {
        Parcel p = Parcel.obtain();
        p.writeSerializable(null);

        assertNull(throughBytes(p).readSerializable());
    }

    @Test
    public void nullException() {
        Parcel p = Parcel.obtain();
        try {
            p.writeException(null);
            fail();
        } catch (RuntimeException expected) {
        }
    }

    private static Parcel throughBytes(Parcel p) {
        byte[] bytes = p.marshall();
        p = Parcel.obtain();
        p.unmarshall(bytes, 0, bytes.length);
        p.setDataPosition(0);
        return p;
    }
}
