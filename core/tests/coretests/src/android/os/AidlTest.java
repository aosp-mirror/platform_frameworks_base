/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.os.IInterface;
import android.os.Parcel;
import android.os.Parcelable;
import android.test.suitebuilder.annotation.SmallTest;
import com.google.android.collect.Lists;
import junit.framework.TestCase;

import java.util.List;

public class AidlTest extends TestCase {

    private IAidlTest mRemote;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        AidlObject mLocal = new AidlObject();
        mRemote = IAidlTest.Stub.asInterface(mLocal);
    }

    private static boolean check(TestParcelable p, int n, String s) {
        return p.mAnInt == n &&
                ((s == null && p.mAString == null) || s.equals(p.mAString));
    }

    public static class TestParcelable implements Parcelable {
        public int mAnInt;
        public String mAString;

        public TestParcelable() {
        }

        public TestParcelable(int i, String s) {
            mAnInt = i;
            mAString = s;
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel parcel, int flags) {
            parcel.writeInt(mAnInt);
            parcel.writeString(mAString);
        }

        public void readFromParcel(Parcel parcel) {
            mAnInt = parcel.readInt();
            mAString = parcel.readString();
        }

        public static final Parcelable.Creator<TestParcelable> CREATOR
                = new Parcelable.Creator<TestParcelable>() {
            public TestParcelable createFromParcel(Parcel parcel) {
                return new TestParcelable(parcel.readInt(),
                        parcel.readString());
            }

            public TestParcelable[] newArray(int size) {
                return new TestParcelable[size];
            }
        };

        public String toString() {
            return super.toString() + " {" + mAnInt + "/" + mAString + "}";
        }
    }

    private static class AidlObject extends IAidlTest.Stub {
        public IInterface queryLocalInterface(String descriptor) {
            // overriding this to return null makes asInterface always
            // generate a proxy
            return null;
        }

        public int intMethod(int a) {
            return a;
        }

        public TestParcelable parcelableIn(TestParcelable p) {
            p.mAnInt++;
            return p;
        }

        public TestParcelable parcelableOut(TestParcelable p) {
            p.mAnInt = 44;
            return p;
        }

        public TestParcelable parcelableInOut(TestParcelable p) {
            p.mAnInt++;
            return p;
        }

        public TestParcelable listParcelableLonger(List<TestParcelable> list, int index) {
            list.add(list.get(index));
            return list.get(index);
        }

        public int listParcelableShorter(List<TestParcelable> list, int index) {
            list.remove(index);
            return list.size();
        }

        public boolean[] booleanArray(boolean[] a0, boolean[] a1, boolean[] a2) {
            for (int i = 0; i < a0.length && i < a2.length; i++) {
                a2[i] = a0[i];
            }
            for (int i = 0; i < a0.length && i < a1.length; i++) {
                a1[i] = a0[i];
            }
            return a0;
        }

        public char[] charArray(char[] a0, char[] a1, char[] a2) {
            for (int i = 0; i < a0.length && i < a2.length; i++) {
                a2[i] = a0[i];
            }
            for (int i = 0; i < a0.length && i < a1.length; i++) {
                a1[i] = a0[i];
            }
            return a0;
        }

        public int[] intArray(int[] a0, int[] a1, int[] a2) {
            for (int i = 0; i < a0.length && i < a2.length; i++) {
                a2[i] = a0[i];
            }
            for (int i = 0; i < a0.length && i < a1.length; i++) {
                a1[i] = a0[i];
            }
            return a0;
        }

        public long[] longArray(long[] a0, long[] a1, long[] a2) {
            for (int i = 0; i < a0.length && i < a2.length; i++) {
                a2[i] = a0[i];
            }
            for (int i = 0; i < a0.length && i < a1.length; i++) {
                a1[i] = a0[i];
            }
            return a0;
        }

        public float[] floatArray(float[] a0, float[] a1, float[] a2) {
            for (int i = 0; i < a0.length && i < a2.length; i++) {
                a2[i] = a0[i];
            }
            for (int i = 0; i < a0.length && i < a1.length; i++) {
                a1[i] = a0[i];
            }
            return a0;
        }

        public double[] doubleArray(double[] a0, double[] a1, double[] a2) {
            for (int i = 0; i < a0.length && i < a2.length; i++) {
                a2[i] = a0[i];
            }
            for (int i = 0; i < a0.length && i < a1.length; i++) {
                a1[i] = a0[i];
            }
            return a0;
        }

        public String[] stringArray(String[] a0, String[] a1, String[] a2) {
            for (int i = 0; i < a0.length && i < a2.length; i++) {
                a2[i] = a0[i];
            }
            for (int i = 0; i < a0.length && i < a1.length; i++) {
                a1[i] = a0[i];
            }
            return a0;
        }

        public TestParcelable[] parcelableArray(TestParcelable[] a0,
                TestParcelable[] a1, TestParcelable[] a2) {
            return null;
        }
        
        public void voidSecurityException() {
            throw new SecurityException("gotcha!");
        }

        public int intSecurityException() {
            throw new SecurityException("gotcha!");
        }
    }

    @SmallTest
    public void testInt() throws Exception {
        int result = mRemote.intMethod(42);
        assertEquals(42, result);
    }

    @SmallTest
    public void testParcelableIn() throws Exception {
        TestParcelable arg = new TestParcelable(43, "hi");
        TestParcelable result = mRemote.parcelableIn(arg);
        assertNotSame(arg, result);

        assertEquals(43, arg.mAnInt);
        assertEquals(44, result.mAnInt);
    }

    @SmallTest
    public void testParcelableOut() throws Exception {
        TestParcelable arg = new TestParcelable(43, "hi");
        TestParcelable result = mRemote.parcelableOut(arg);
        assertNotSame(arg, result);
        assertEquals(44, arg.mAnInt);
    }

    @SmallTest
    public void testParcelableInOut() throws Exception {
        TestParcelable arg = new TestParcelable(43, "hi");
        TestParcelable result = mRemote.parcelableInOut(arg);
        assertNotSame(arg, result);
        assertEquals(44, arg.mAnInt);
    }

    @SmallTest
    public void testListParcelableLonger() throws Exception {
        List<TestParcelable> list = Lists.newArrayList();
        list.add(new TestParcelable(33, "asdf"));
        list.add(new TestParcelable(34, "jkl;"));

        TestParcelable result = mRemote.listParcelableLonger(list, 1);

//        System.out.println("result=" + result);
//        for (TestParcelable p : list) {
//            System.out.println("longer: " + p);
//        }

        assertEquals("jkl;", result.mAString);
        assertEquals(34, result.mAnInt);

        assertEquals(3, list.size());
        assertTrue("out parameter 0: " + list.get(0), check(list.get(0), 33, "asdf"));
        assertTrue("out parameter 1: " + list.get(1), check(list.get(1), 34, "jkl;"));
        assertTrue("out parameter 2: " + list.get(2), check(list.get(2), 34, "jkl;"));

        assertNotSame(list.get(1), list.get(2));
    }

    @SmallTest
    public void testListParcelableShorter() throws Exception {
        List<TestParcelable> list = Lists.newArrayList();
        list.add(new TestParcelable(33, "asdf"));
        list.add(new TestParcelable(34, "jkl;"));
        list.add(new TestParcelable(35, "qwerty"));

        int result = mRemote.listParcelableShorter(list, 2);

//        System.out.println("result=" + result);
//        for (TestParcelable p : list) {
//            System.out.println("shorter: " + p);
//        }

        assertEquals(2, result);
        assertEquals(2, list.size());
        assertTrue("out parameter 0: " + list.get(0), check(list.get(0), 33, "asdf"));
        assertTrue("out parameter 1: " + list.get(1), check(list.get(1), 34, "jkl;"));

        assertNotSame(list.get(0), list.get(1));
    }

    @SmallTest
    public void testArrays() throws Exception {
        // boolean
        boolean[] b0 = new boolean[]{true};
        boolean[] b1 = new boolean[]{false, true};
        boolean[] b2 = new boolean[]{true, false, true};
        boolean[] br = mRemote.booleanArray(b0, b1, b2);

        assertEquals(1, br.length);
        assertTrue(br[0]);

        assertTrue(b1[0]);
        assertFalse(b1[1]);

        assertTrue(b2[0]);
        assertFalse(b2[1]);
        assertTrue(b2[2]);

        // char
        char[] c0 = new char[]{'a'};
        char[] c1 = new char[]{'b', 'c'};
        char[] c2 = new char[]{'d', 'e', 'f'};
        char[] cr = mRemote.charArray(c0, c1, c2);

        assertEquals(1, cr.length);
        assertEquals('a', cr[0]);

        assertEquals('a', c1[0]);
        assertEquals('\0', c1[1]);

        assertEquals('a', c2[0]);
        assertEquals('e', c2[1]);
        assertEquals('f', c2[2]);

        // int
        int[] i0 = new int[]{34};
        int[] i1 = new int[]{38, 39};
        int[] i2 = new int[]{42, 43, 44};
        int[] ir = mRemote.intArray(i0, i1, i2);

        assertEquals(1, ir.length);
        assertEquals(34, ir[0]);

        assertEquals(34, i1[0]);
        assertEquals(0, i1[1]);

        assertEquals(34, i2[0]);
        assertEquals(43, i2[1]);
        assertEquals(44, i2[2]);

        // long
        long[] l0 = new long[]{50};
        long[] l1 = new long[]{51, 52};
        long[] l2 = new long[]{53, 54, 55};
        long[] lr = mRemote.longArray(l0, l1, l2);

        assertEquals(1, lr.length);
        assertEquals(50, lr[0]);

        assertEquals(50, l1[0]);
        assertEquals(0, l1[1]);

        assertEquals(50, l2[0]);
        assertEquals(54, l2[1]);
        assertEquals(55, l2[2]);

        // float
        float[] f0 = new float[]{90.1f};
        float[] f1 = new float[]{90.2f, 90.3f};
        float[] f2 = new float[]{90.4f, 90.5f, 90.6f};
        float[] fr = mRemote.floatArray(f0, f1, f2);

        assertEquals(1, fr.length);
        assertEquals(90.1f, fr[0]);

        assertEquals(90.1f, f1[0]);
        assertEquals(0f, f1[1], 0.0f);

        assertEquals(90.1f, f2[0]);
        assertEquals(90.5f, f2[1]);
        assertEquals(90.6f, f2[2]);

        // double
        double[] d0 = new double[]{100.1};
        double[] d1 = new double[]{100.2, 100.3};
        double[] d2 = new double[]{100.4, 100.5, 100.6};
        double[] dr = mRemote.doubleArray(d0, d1, d2);

        assertEquals(1, dr.length);
        assertEquals(100.1, dr[0]);

        assertEquals(100.1, d1[0]);
        assertEquals(0, d1[1], 0.0);

        assertEquals(100.1, d2[0]);
        assertEquals(100.5, d2[1]);
        assertEquals(100.6, d2[2]);

        // String
        String[] s0 = new String[]{"s0[0]"};
        String[] s1 = new String[]{"s1[0]", "s1[1]"};
        String[] s2 = new String[]{"s2[0]", "s2[1]", "s2[2]"};
        String[] sr = mRemote.stringArray(s0, s1, s2);

        assertEquals(1, sr.length);
        assertEquals("s0[0]", sr[0]);

        assertEquals("s0[0]", s1[0]);
        assertNull(s1[1]);

        assertEquals("s0[0]", s2[0]);
        assertEquals("s2[1]", s2[1]);
        assertEquals("s2[2]", s2[2]);
    }
    
    @SmallTest
    public void testVoidSecurityException() throws Exception {
        boolean good = false;
        try {
            mRemote.voidSecurityException();
        } catch (SecurityException e) {
            good = true;
        }
        assertEquals(good, true);
    }
    
    @SmallTest
    public void testIntSecurityException() throws Exception {
        boolean good = false;
        try {
            mRemote.intSecurityException();
        } catch (SecurityException e) {
            good = true;
        }
        assertEquals(good, true);
    }
}

