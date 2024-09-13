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

package android.libcore.regression;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.util.ArrayList;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class SerializationPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private static byte[] bytes(Object o) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
        ObjectOutputStream out = new ObjectOutputStream(baos);
        out.writeObject(o);
        out.close();
        return baos.toByteArray();
    }

    @Test
    public void timeReadIntArray() throws Exception {
        int[] intArray = new int[256];
        readSingleObject(intArray);
    }

    @Test
    public void timeWriteIntArray() throws Exception {
        int[] intArray = new int[256];
        writeSingleObject(intArray);
    }

    @Test
    public void timeReadArrayListInteger() throws Exception {
        ArrayList<Integer> object = new ArrayList<Integer>();
        for (int i = 0; i < 256; ++i) {
            object.add(i);
        }
        readSingleObject(object);
    }

    @Test
    public void timeWriteArrayListInteger() throws Exception {
        ArrayList<Integer> object = new ArrayList<Integer>();
        for (int i = 0; i < 256; ++i) {
            object.add(i);
        }
        writeSingleObject(object);
    }

    @Test
    public void timeReadString() throws Exception {
        readSingleObject("hello");
    }

    @Test
    public void timeReadObjectStreamClass() throws Exception {
        // A special case because serialization itself requires this class.
        // (This should really be a unit test.)
        ObjectStreamClass osc = ObjectStreamClass.lookup(String.class);
        readSingleObject(osc);
    }

    @Test
    public void timeWriteString() throws Exception {
        // String is a special case that avoids JNI.
        writeSingleObject("hello");
    }

    @Test
    public void timeWriteObjectStreamClass() throws Exception {
        // A special case because serialization itself requires this class.
        // (This should really be a unit test.)
        ObjectStreamClass osc = ObjectStreamClass.lookup(String.class);
        writeSingleObject(osc);
    }

    // This is
    //
    // @Testa baseline for the others.
    public void timeWriteNoObjects() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
        ObjectOutputStream out = new ObjectOutputStream(baos);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            out.reset();
            baos.reset();
        }
        out.close();
    }

    private void readSingleObject(Object object) throws Exception {
        byte[] bytes = bytes(object);
        ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            ObjectInputStream in = new ObjectInputStream(bais);
            in.readObject();
            in.close();
            bais.reset();
        }
    }

    private void writeSingleObject(Object o) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
        ObjectOutputStream out = new ObjectOutputStream(baos);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            out.writeObject(o);
            out.reset();
            baos.reset();
        }
        out.close();
    }

    @Test
    public void timeWriteEveryKindOfField() throws Exception {
        writeSingleObject(new LittleBitOfEverything());
    }

    @Test
    public void timeWriteSerializableBoolean() throws Exception {
        writeSingleObject(new SerializableBoolean());
    }

    @Test
    public void timeWriteSerializableByte() throws Exception {
        writeSingleObject(new SerializableByte());
    }

    @Test
    public void timeWriteSerializableChar() throws Exception {
        writeSingleObject(new SerializableChar());
    }

    @Test
    public void timeWriteSerializableDouble() throws Exception {
        writeSingleObject(new SerializableDouble());
    }

    @Test
    public void timeWriteSerializableFloat() throws Exception {
        writeSingleObject(new SerializableFloat());
    }

    @Test
    public void timeWriteSerializableInt() throws Exception {
        writeSingleObject(new SerializableInt());
    }

    @Test
    public void timeWriteSerializableLong() throws Exception {
        writeSingleObject(new SerializableLong());
    }

    @Test
    public void timeWriteSerializableShort() throws Exception {
        writeSingleObject(new SerializableShort());
    }

    @Test
    public void timeWriteSerializableReference() throws Exception {
        writeSingleObject(new SerializableReference());
    }

    @Test
    public void timeReadEveryKindOfField() throws Exception {
        readSingleObject(new LittleBitOfEverything());
    }

    @Test
    public void timeReadSerializableBoolean() throws Exception {
        readSingleObject(new SerializableBoolean());
    }

    @Test
    public void timeReadSerializableByte() throws Exception {
        readSingleObject(new SerializableByte());
    }

    @Test
    public void timeReadSerializableChar() throws Exception {
        readSingleObject(new SerializableChar());
    }

    @Test
    public void timeReadSerializableDouble() throws Exception {
        readSingleObject(new SerializableDouble());
    }

    @Test
    public void timeReadSerializableFloat() throws Exception {
        readSingleObject(new SerializableFloat());
    }

    @Test
    public void timeReadSerializableInt() throws Exception {
        readSingleObject(new SerializableInt());
    }

    @Test
    public void timeReadSerializableLong() throws Exception {
        readSingleObject(new SerializableLong());
    }

    @Test
    public void timeReadSerializableShort() throws Exception {
        readSingleObject(new SerializableShort());
    }

    @Test
    public void timeReadSerializableReference() throws Exception {
        readSingleObject(new SerializableReference());
    }

    public static class SerializableBoolean implements Serializable {
        boolean mZ;
    }

    public static class SerializableByte implements Serializable {
        byte mB;
    }

    public static class SerializableChar implements Serializable {
        char mC;
    }

    public static class SerializableDouble implements Serializable {
        double mD;
    }

    public static class SerializableFloat implements Serializable {
        float mF;
    }

    public static class SerializableInt implements Serializable {
        int mI;
    }

    public static class SerializableLong implements Serializable {
        long mJ;
    }

    public static class SerializableShort implements Serializable {
        short mS;
    }

    public static class SerializableReference implements Serializable {
        Object mL;
    }

    public static class LittleBitOfEverything implements Serializable {
        boolean mZ;
        byte mB;
        char mC;
        double mD;
        float mF;
        int mI;
        long mJ;
        short mS;
        Object mL;
    }
}
