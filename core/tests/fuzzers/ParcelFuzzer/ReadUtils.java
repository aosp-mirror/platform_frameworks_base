/*
 * Copyright (C) 2022 The Android Open Source Project
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
package parcelfuzzer;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.HashMap;

import parcelables.EmptyParcelable;
import parcelables.GenericDataParcelable;
import parcelables.SingleDataParcelable;

public class ReadUtils {
    public static int MAX_LEN = 1000000;
    public static int MIN_LEN = 0;

    private static class SomeParcelable implements Parcelable {
        private final int mValue;

        private SomeParcelable(Parcel in) {
            this.mValue = in.readInt();
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            out.writeInt(mValue);
        }

        public static Parcelable.Creator<SomeParcelable> CREATOR =
                new Parcelable.Creator<SomeParcelable>() {

                    @Override
                    public SomeParcelable createFromParcel(Parcel source) {
                        return new SomeParcelable(source);
                    }

                    @Override
                    public SomeParcelable[] newArray(int size) {
                        return new SomeParcelable[size];
                    }
                };
    }

    private static class TestClassLoader extends ClassLoader {
        TestClassLoader() {
            super();
        }
    }

    private static class TestInterface implements IInterface {
        public Binder binder;
        private static final String DESCRIPTOR = "TestInterface";

        TestInterface() {
            binder = new Binder();
            binder.attachInterface(this, DESCRIPTOR);
        }

        public IBinder asBinder() {
            return binder;
        }

        public static TestInterface asInterface(IBinder binder) {
            if (binder != null) {
                IInterface iface = binder.queryLocalInterface(DESCRIPTOR);
                if (iface != null && iface instanceof TestInterface) {
                    return (TestInterface) iface;
                }
            }
            return null;
        }
    }

    public static ReadOperation[] READ_OPERATIONS =
            new ReadOperation[] {
                    (parcel, provider) -> {
                        parcel.setDataPosition(provider.consumeInt(0, Integer.MAX_VALUE));
                    },
                    (parcel, provider) -> {
                        parcel.setDataCapacity(provider.consumeInt());
                    },
                    (parcel, provider) -> {
                        parcel.setDataSize(provider.consumeInt());
                    },
                    (parcel, provider) -> {
                        parcel.dataSize();
                    },
                    (parcel, provider) -> {
                        parcel.dataPosition();
                    },
                    (parcel, provider) -> {
                        parcel.dataCapacity();
                    },

                    // read basic types
                    (parcel, provider) -> {
                        parcel.readByte();
                    },
                    (parcel, provider) -> {
                        parcel.readBoolean();
                    },
                    (parcel, provider) -> {
                        parcel.readInt();
                    },
                    (parcel, provider) -> {
                        parcel.readLong();
                    },
                    (parcel, provider) -> {
                        parcel.readFloat();
                    },
                    (parcel, provider) -> {
                        parcel.readDouble();
                    },
                    (parcel, provider) -> {
                        parcel.readString();
                    },
                    (parcel, provider) -> {
                        parcel.readString8();
                    },
                    (parcel, provider) -> {
                        parcel.readString16();
                    },
                    (parcel, provider) -> {
                        parcel.readBlob();
                    },
                    (parcel, provider) -> {
                        parcel.readStrongBinder();
                    },

                    // read arrays of random length
                    (parcel, provider) -> {
                        byte[] array;
                        if (provider.consumeBoolean()) {
                            int pos = parcel.dataPosition();
                            if (pos < 0) return;
                            array = new byte[Math.min(MAX_LEN, parcel.readInt())];
                            parcel.setDataPosition(pos);
                        } else {
                            array = new byte[provider.consumeInt(MIN_LEN, MAX_LEN)];
                        }
                        parcel.readByteArray(array);
                    },
                    (parcel, provider) -> {
                        char[] array;
                        if (provider.consumeBoolean()) {
                            int pos = parcel.dataPosition();
                            if (pos < 0) return;
                            array = new char[Math.min(MAX_LEN, parcel.readInt())];
                            parcel.setDataPosition(pos);
                        } else {
                            array = new char[provider.consumeInt(MIN_LEN, MAX_LEN)];
                        }
                        parcel.readCharArray(array);
                    },
                    (parcel, provider) -> {
                        int[] array;
                        if (provider.consumeBoolean()) {
                            int pos = parcel.dataPosition();
                            if (pos < 0) return;
                            array = new int[Math.min(MAX_LEN, parcel.readInt())];
                            parcel.setDataPosition(pos);
                        } else {
                            array = new int[provider.consumeInt(MIN_LEN, MAX_LEN)];
                        }
                        parcel.readIntArray(array);
                    },
                    (parcel, provider) -> {
                        double[] array;
                        if (provider.consumeBoolean()) {
                            int pos = parcel.dataPosition();
                            if (pos < 0) return;
                            array = new double[Math.min(MAX_LEN, parcel.readInt())];
                            parcel.setDataPosition(pos);
                        } else {
                            array = new double[provider.consumeInt(MIN_LEN, MAX_LEN)];
                        }
                        parcel.readDoubleArray(array);
                    },
                    (parcel, provider) -> {
                        float[] array;
                        if (provider.consumeBoolean()) {
                            int pos = parcel.dataPosition();
                            if (pos < 0) return;
                            array = new float[Math.min(MAX_LEN, parcel.readInt())];
                            parcel.setDataPosition(pos);
                        } else {
                            array = new float[provider.consumeInt(MIN_LEN, MAX_LEN)];
                        }
                        parcel.readFloatArray(array);
                    },
                    (parcel, provider) -> {
                        boolean[] array;
                        if (provider.consumeBoolean()) {
                            int pos = parcel.dataPosition();
                            if (pos < 0) return;
                            array = new boolean[Math.min(MAX_LEN, parcel.readInt())];
                            parcel.setDataPosition(pos);
                        } else {
                            array = new boolean[provider.consumeInt(MIN_LEN, MAX_LEN)];
                        }
                        parcel.readBooleanArray(array);
                    },
                    (parcel, provider) -> {
                        long[] array;
                        if (provider.consumeBoolean()) {
                            int pos = parcel.dataPosition();
                            if (pos < 0) return;
                            array = new long[Math.min(MAX_LEN, parcel.readInt())];
                            parcel.setDataPosition(pos);
                        } else {
                            array = new long[provider.consumeInt(MIN_LEN, MAX_LEN)];
                        }
                        parcel.readLongArray(array);
                    },
                    (parcel, provider) -> {
                        IBinder[] array;
                        if (provider.consumeBoolean()) {
                            int pos = parcel.dataPosition();
                            if (pos < 0) return;
                            array = new IBinder[Math.min(MAX_LEN, parcel.readInt())];
                            parcel.setDataPosition(pos);
                        } else {
                            array = new IBinder[provider.consumeInt(MIN_LEN, MAX_LEN)];
                        }
                        parcel.readBinderArray(array);
                    },
                    (parcel, provider) -> {
                        ArrayList<IBinder> arrayList = new ArrayList<IBinder>();
                        parcel.readBinderList(arrayList);
                    },

                    // unmarshall from random parcel data and random bytes
                    (parcel, provider) -> {
                        byte[] data = parcel.marshall();
                        Parcel p = Parcel.obtain();
                        p.unmarshall(data, provider.consumeInt(), provider.consumeInt());
                        p.recycle();
                    },
                    (parcel, provider) -> {
                        byte[] data = provider.consumeRemainingAsBytes();
                        Parcel p = Parcel.obtain();
                        p.unmarshall(data, provider.consumeInt(), provider.consumeInt());
                        p.recycle();
                    },
                    (parcel, provider) -> {
                        parcel.hasFileDescriptors(provider.consumeInt(), provider.consumeInt());
                    },

                    // read AIDL generated parcelables
                    (parcel, provider) -> {
                        TestClassLoader loader = new TestClassLoader();
                        parcel.readParcelable(loader, SingleDataParcelable.class);
                    },
                    (parcel, provider) -> {
                        TestClassLoader loader = new TestClassLoader();
                        parcel.readParcelableArray(loader, SingleDataParcelable.class);
                    },
                    (parcel, provider) -> {
                        SingleDataParcelable[] array;
                        if (provider.consumeBoolean()) {
                            int pos = parcel.dataPosition();
                            if (pos < 0) return;
                            array = new SingleDataParcelable[Math.min(MAX_LEN, parcel.readInt())];
                            parcel.setDataPosition(pos);
                        } else {
                            array = new SingleDataParcelable[provider.consumeInt(MIN_LEN, MAX_LEN)];
                        }
                        parcel.readTypedArray(array, SingleDataParcelable.CREATOR);
                    },
                    (parcel, provider) -> {
                        TestClassLoader loader = new TestClassLoader();
                        parcel.readParcelable(loader, EmptyParcelable.class);
                    },
                    (parcel, provider) -> {
                        TestClassLoader loader = new TestClassLoader();
                        parcel.readParcelableArray(loader, EmptyParcelable.class);
                    },
                    (parcel, provider) -> {
                        EmptyParcelable[] array;
                        if (provider.consumeBoolean()) {
                            int pos = parcel.dataPosition();
                            if (pos < 0) return;
                            array = new EmptyParcelable[Math.min(MAX_LEN, parcel.readInt())];
                            parcel.setDataPosition(pos);
                        } else {
                            array = new EmptyParcelable[provider.consumeInt(MIN_LEN, MAX_LEN)];
                        }
                        parcel.readTypedArray(array, EmptyParcelable.CREATOR);
                    },
                    (parcel, provider) -> {
                        TestClassLoader loader = new TestClassLoader();
                        parcel.readParcelable(loader, GenericDataParcelable.class);
                    },
                    (parcel, provider) -> {
                        TestClassLoader loader = new TestClassLoader();
                        parcel.readParcelableArray(loader, GenericDataParcelable.class);
                    },
                    (parcel, provider) -> {
                        GenericDataParcelable[] array;
                        if (provider.consumeBoolean()) {
                            int pos = parcel.dataPosition();
                            if (pos < 0) return;
                            array = new GenericDataParcelable[Math.min(MAX_LEN, parcel.readInt())];
                            parcel.setDataPosition(pos);
                        } else {
                            int len = provider.consumeInt(MIN_LEN, MAX_LEN);
                            array = new GenericDataParcelable[len];
                        }
                        parcel.readTypedArray(array, GenericDataParcelable.CREATOR);
                    },

                    // read parcelables
                    (parcel, provider) -> {
                        TestClassLoader loader = new TestClassLoader();
                        parcel.readParcelable(loader, SomeParcelable.class);
                    },
                    (parcel, provider) -> {
                        TestClassLoader loader = new TestClassLoader();
                        parcel.readParcelableArray(loader, SomeParcelable.class);
                    },
                    (parcel, provider) -> {
                        SomeParcelable[] array;
                        if (provider.consumeBoolean()) {
                            int pos = parcel.dataPosition();
                            if (pos < 0) return;
                            array = new SomeParcelable[Math.min(MAX_LEN, parcel.readInt())];
                            parcel.setDataPosition(pos);
                        } else {
                            array = new SomeParcelable[provider.consumeInt(MIN_LEN, MAX_LEN)];
                        }
                        parcel.readTypedArray(array, SomeParcelable.CREATOR);
                    },
                    (parcel, provider) -> {
                        TestClassLoader loader = new TestClassLoader();
                        parcel.readParcelableArray(loader);
                    },
                    (parcel, provider) -> {
                        parcel.hasFileDescriptors(provider.consumeInt(), provider.consumeInt());
                    },
                    (parcel, provider) -> {
                        TestClassLoader loader = new TestClassLoader();
                        parcel.readParcelableArray(loader);
                    },
                    (parcel, provider) -> {
                        parcel.readParcelable(null);
                    },
                    (parcel, provider) -> {
                        parcel.readParcelableArray(null);
                    },

                    // read lists
                    (parcel, provider) -> {
                        TestClassLoader loader = new TestClassLoader();
                        parcel.readArrayList(loader);
                    },
                    (parcel, provider) -> {
                        TestClassLoader loader = new TestClassLoader();
                        parcel.readArrayList(loader, Object.class);
                    },
                    (parcel, provider) -> {
                        TestClassLoader loader = new TestClassLoader();
                        parcel.readArrayList(loader, SomeParcelable.class);
                    },

                    // read sparse arrays
                    (parcel, provider) -> {
                        TestClassLoader loader = new TestClassLoader();
                        parcel.readSparseArray(loader);
                    },
                    (parcel, provider) -> {
                        TestClassLoader loader = new TestClassLoader();
                        parcel.readSparseArray(loader, Object.class);
                    },
                    (parcel, provider) -> {
                        TestClassLoader loader = new TestClassLoader();
                        parcel.readSparseArray(loader, SomeParcelable.class);
                    },
                    (parcel, provider) -> {
                        TestClassLoader loader = new TestClassLoader();
                        parcel.readSerializable(loader, Object.class);
                    },

                    // read interface
                    (parcel, provider) -> {
                        TestInterface[] array;
                        if (provider.consumeBoolean()) {
                            int pos = parcel.dataPosition();
                            if (pos < 0) return;
                            array = new TestInterface[Math.min(MAX_LEN, parcel.readInt())];
                            parcel.setDataPosition(pos);
                        } else {
                            array = new TestInterface[provider.consumeInt(MIN_LEN, MAX_LEN)];
                        }
                        parcel.readInterfaceArray(array, TestInterface::asInterface);
                    },
                    (parcel, provider) -> {
                        int w = provider.consumeInt(MIN_LEN, MAX_LEN);
                        int h = provider.consumeInt(MIN_LEN, MAX_LEN);
                        TestInterface[][] array = new TestInterface[w][h];
                        parcel.readFixedArray(array, TestInterface::asInterface);
                    },
                    (parcel, provider) -> {
                        ArrayList<TestInterface> array = new ArrayList<TestInterface>();
                        parcel.readInterfaceList(array, TestInterface::asInterface);
                    },

                    // read bundle
                    (parcel, provider) -> {
                        TestClassLoader loader = new TestClassLoader();
                        parcel.readBundle(loader);
                    },
                    (parcel, provider) -> {
                        parcel.readBundle();
                    },

                    // read HashMap
                    (parcel, provider) -> {
                        TestClassLoader loader = new TestClassLoader();
                        parcel.readHashMap(loader);
                    },
                    (parcel, provider) -> {
                        TestClassLoader loader = new TestClassLoader();
                        parcel.readHashMap(loader, String.class, String.class);
                    },
                    (parcel, provider) -> {
                        HashMap<String, String> hashMap = new HashMap<>();
                        TestClassLoader loader = new TestClassLoader();
                        parcel.readMap(hashMap, loader, String.class, String.class);
                    },
            };
}
