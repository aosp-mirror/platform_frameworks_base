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

package android.core;

import org.apache.harmony.dalvik.NativeTestTarget;

import android.test.PerformanceTestBase;
import android.test.PerformanceTestCase;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import junit.framework.Assert;

public class PerformanceTests {
    public static String[] children() {
        return new String[] {
                //StringEquals2.class.getName(),
                //StringEquals10.class.getName(),
                //StringEquals20.class.getName(),
                //StringEquals200.class.getName(),
                //StringEquals200U.class.getName(),
                //StringCompareTo10.class.getName(),
                //StringCompareTo200.class.getName(),
                StringLength.class.getName(),
                StringCrawl.class.getName(),
                Ackermann.class.getName(),
                AddTest.class.getName(),
//                AddMemberVariableTest.class.getName(),
                ArrayListIterator.class.getName(),
                BoundsCheckTest.class.getName(),
//                EmptyClassBaseTest.class.getName(),
                EmptyJniStaticMethod0.class.getName(),
                EmptyJniStaticMethod6.class.getName(),
                EmptyJniStaticMethod6L.class.getName(),
                FibonacciFast.class.getName(),
                FibonacciSlow.class.getName(),
//                LoopTests.class.getName(),
//                HashMapTest.class.getName(),
//                InterfaceTests.class.getName(),
                LocalVariableAccess.class.getName(),
                MemeberVariableAccess.class.getName(),
                NestedLoop.class.getName(),
//                StringConcatenationTests.class.getName(),
//                ArrayListBase.class.getName(),
                SynchronizedGetAndSetInt.class.getName(),

                /* this will not work on JamVM -- lacks atomic ops */
                AtomicGetAndSetInt.class.getName(),
        };
    }

    public static class SizeTest {
        private int mSize;

        public SizeTest(int size) {
            mSize = size;
        }

        public int size() {
            return mSize;
        }
    }

    public static class LocalVariableAccess extends PerformanceTestBase {
        private static final int ITERATIONS = 100000;

        public int startPerformance(PerformanceTestCase.Intermediates intermediates) {
            intermediates.setInternalIterations(ITERATIONS * 20);
            return 0;
        }

        public void testRun() {
            boolean variable = false;
            boolean local = true;
            for (int i = ITERATIONS - 1; i >= 0; i--) {
                local = variable;
                local = variable;
                local = variable;
                local = variable;
                local = variable; // 5
                local = variable;
                local = variable;
                local = variable;
                local = variable;
                local = variable; // 10
                local = variable;
                local = variable;
                local = variable;
                local = variable;
                local = variable; // 15
                local = variable;
                local = variable;
                local = variable;
                local = variable;
                local = variable; // 20
            }
        }
    }

    /* This test is intentionally misspelled. Please do not rename it. Thanks! */
    public static class MemeberVariableAccess extends PerformanceTestBase {
        private static final int ITERATIONS = 100000;

        public volatile boolean mMember = false;

        public int startPerformance(PerformanceTestCase.Intermediates intermediates) {
            intermediates.setInternalIterations(ITERATIONS * 20);
            return 0;
        }

        public void testRun() {
            boolean local = true;
            for (int i = ITERATIONS - 1; i >= 0; i--) {
                local = mMember;
                local = mMember;
                local = mMember;
                local = mMember;
                local = mMember; // 5
                local = mMember;
                local = mMember;
                local = mMember;
                local = mMember;
                local = mMember; // 10
                local = mMember;
                local = mMember;
                local = mMember;
                local = mMember;
                local = mMember; // 15
                local = mMember;
                local = mMember;
                local = mMember;
                local = mMember;
                local = mMember; // 20
            }
        }
    }

    public static class ArrayListIterator extends PerformanceTestBase {
        private static final int ITERATIONS = 10000;
        private ArrayList mList;
        private String[] mKeys;
        private Iterator mIterator;

        public void setUp() throws Exception {
            super.setUp();
            mList = new ArrayList();
            mKeys = new String[ITERATIONS];

            for (int i = ITERATIONS - 1; i >= 0; i--) {
                mKeys[i] = Integer.toString(i, 16);
                mList.add(mKeys[i]);
            }
        }

        public int startPerformance(PerformanceTestCase.Intermediates intermediates) {
            intermediates.setInternalIterations(ITERATIONS);
            return 0;
        }

        public void testRun() {
            mIterator = mList.iterator();
            while (mIterator.hasNext()) {
                mIterator.next();
            }
        }
    }

    public static class Ackermann extends PerformanceTestBase {
        public static final int ITERATIONS = 100;
        public int startPerformance(PerformanceTestCase.Intermediates intermediates) {
            intermediates.setInternalIterations(ITERATIONS);
            return 0;
        }

        public void testRun() {
            for (int i = ITERATIONS - 1; i >= 0; i--) {
                ackermann(3, 13);
            }
        }

        private int ackermann(int m, int n) {
            if (m == 0)
                return n + 1;
            if (n == 0)
                return ackermann(m - 1, 1);
            return ackermann(m, n - 1);
        }
    }

    public static class FibonacciSlow extends PerformanceTestBase {
        public void setUp() throws Exception {
            super.setUp();
            Assert.assertEquals(0, fibonacci(0));
            Assert.assertEquals(1, fibonacci(1));
            Assert.assertEquals(1, fibonacci(2));
            Assert.assertEquals(2, fibonacci(3));
            Assert.assertEquals(6765, fibonacci(20));
        }

        public void tearDown() {
        }

        public int startPerformance(PerformanceTestCase.Intermediates intermediates) {
            return 0;
        }

        public void testRun() {
            fibonacci(20);
        }

        private long fibonacci(long n) {
            if (n == 0)
                return 0;
            if (n == 1)
                return 1;
            return fibonacci(n - 2) + fibonacci(n - 1);
        }
    }

    public static class FibonacciFast extends PerformanceTestBase {
        public void setUp() throws Exception {
            super.setUp();
            Assert.assertEquals(0, fibonacci(0));
            Assert.assertEquals(1, fibonacci(1));
            Assert.assertEquals(1, fibonacci(2));
            Assert.assertEquals(2, fibonacci(3));
            Assert.assertEquals(6765, fibonacci(20));
        }

        public void tearDown() {
        }

        public int startPerformance(PerformanceTestCase.Intermediates intermediates) {
            return 0;
        }

        public void testRun() {
            fibonacci(5000);
        }

        private long fibonacci(long n) {
            if (n == 0)
                return 0;
            if (n == 1)
                return 1;

            int x = 0;
            int y = 1;
            for (int i = 0; i < n - 1; i++) {
                y = y + x;
                x = y - x;
            }

            return y;
        }
    }

    public static class HashMapTest extends PerformanceTestBase {
        private static final int ITERATIONS = 10000;
        private HashMap mMap;
        private String[] mKeys;

        public void setUp() throws Exception {
            super.setUp();
            mMap = new HashMap();
            mKeys = new String[ITERATIONS];

            for (int i = ITERATIONS - 1; i >= 0; i--) {
                mKeys[i] = Integer.toString(i, 16);
                mMap.put(mKeys[i], i);
            }
        }

        public void tearDown() {
        }

        public int startPerformance(PerformanceTestCase.Intermediates intermediates) {
            intermediates.setInternalIterations(ITERATIONS);
            return 0;
        }

        public void testHashMapContainsKey() {
            for (int i = ITERATIONS - 1; i >= 0; i--) {
                mMap.containsKey(mKeys[i]);
            }
        }

        public void testHashMapIterator() {
            Iterator iterator;

            iterator = mMap.entrySet().iterator();
            while (iterator.hasNext()) {
                iterator.next();
            }
        }

        public void testHashMapPut() {
            HashMap map = new HashMap();
            String[] keys = mKeys;
            for (int i = ITERATIONS - 1; i >= 0; i--) {
                map.put(keys[i], i);
            }
        }
    }

    interface IA {
        void funcA0();
        void funcA1();
        void funcA2();
        void funcA3();
    }
    interface IAB extends IA {
        void funcAB0();
        void funcAB1();
        void funcAB2();
        void funcAB3();
    }
    interface IABC extends IAB {
        void funcABC0();
        void funcABC1();
        void funcABC2();
        void funcABC3();
    }
    interface IB {
        void funcB0();
        void funcB1();
        void funcB2();
        void funcB3();
    }
    interface IC {
        void funcC0();
        void funcC1();
        void funcC2();
        void funcC3();
    }

    static class Alphabet implements Cloneable, IB, IABC, IC, Runnable {
        public void funcA0() {
        }
        public void funcA1() {
        }
        public void funcA2() {
        }
        public void funcA3() {
        }
        public void funcAB0() {
        }
        public void funcAB1() {
        }
        public void funcAB2() {
        }
        public void funcAB3() {
        }
        public void funcABC0() {
        }
        public void funcABC1() {
        }
        public void funcABC2() {
        }
        public void funcABC3() {
        }
        public void funcB0() {
        }
        public void funcB1() {
        }
        public void funcB2() {
        }
        public void funcB3() {
        }
        public void funcC0() {
        }
        public void funcC1() {
        }
        public void funcC2() {
        }
        public void funcC3() {
        }
        public void run() {
        }
    };

    public static class InterfaceTests extends PerformanceTestBase {
        private static final int ITERATIONS = 10000;

        public int startPerformance(PerformanceTestCase.Intermediates intermediates) {
            intermediates.setInternalIterations(ITERATIONS * 10);
            return 0;
        }

        /* call method directly */
        public void testInterfaceCalls0() {
            Alphabet alpha = new Alphabet();

            for (int i = ITERATIONS - 1; i >= 0; i--) {
                alpha.funcABC1();
                alpha.funcABC1();
                alpha.funcABC1();
                alpha.funcABC1();
                alpha.funcABC1();
                alpha.funcABC1();
                alpha.funcABC1();
                alpha.funcABC1();
                alpha.funcABC1();
                alpha.funcABC1();
            }
        }

       /* call method through interface reference */
        public void testInterfaceCalls1() {
            Alphabet alpha = new Alphabet();
            IABC iabc = alpha;

            for (int i = ITERATIONS - 1; i >= 0; i--) {
                iabc.funcABC1();
                iabc.funcABC1();
                iabc.funcABC1();
                iabc.funcABC1();
                iabc.funcABC1();
                iabc.funcABC1();
                iabc.funcABC1();
                iabc.funcABC1();
                iabc.funcABC1();
                iabc.funcABC1();
            }
        }

        public void testInstanceOfTrivial() {
            Alphabet alpha = new Alphabet();
            IABC iabc = alpha;
            boolean val;

            for (int i = ITERATIONS - 1; i >= 0; i--) {
                val = iabc instanceof Alphabet;
                val = iabc instanceof Alphabet;
                val = iabc instanceof Alphabet;
                val = iabc instanceof Alphabet;
                val = iabc instanceof Alphabet;
                val = iabc instanceof Alphabet;
                val = iabc instanceof Alphabet;
                val = iabc instanceof Alphabet;
                val = iabc instanceof Alphabet;
                val = iabc instanceof Alphabet;
            }
        }

        public void testInstanceOfInterface() {
            Alphabet alpha = new Alphabet();
            IABC iabc = alpha;
            boolean val;

            for (int i = ITERATIONS - 1; i >= 0; i--) {
                val = iabc instanceof IA;
                val = iabc instanceof IA;
                val = iabc instanceof IA;
                val = iabc instanceof IA;
                val = iabc instanceof IA;
                val = iabc instanceof IA;
                val = iabc instanceof IA;
                val = iabc instanceof IA;
                val = iabc instanceof IA;
                val = iabc instanceof IA;
            }
        }

        public void testInstanceOfNot() {
            Alphabet alpha = new Alphabet();
            IABC iabc = alpha;
            boolean val;

            for (int i = ITERATIONS - 1; i >= 0; i--) {
                val = iabc instanceof EmptyInterface;
                val = iabc instanceof EmptyInterface;
                val = iabc instanceof EmptyInterface;
                val = iabc instanceof EmptyInterface;
                val = iabc instanceof EmptyInterface;
                val = iabc instanceof EmptyInterface;
                val = iabc instanceof EmptyInterface;
                val = iabc instanceof EmptyInterface;
                val = iabc instanceof EmptyInterface;
                val = iabc instanceof EmptyInterface;
            }
        }
    }

    public static class NestedLoop extends PerformanceTestBase {
        private static final int ITERATIONS = 10;
        private static final int LOOPS = 5;

        public int startPerformance(PerformanceTestCase.Intermediates intermediates) {
            intermediates.setInternalIterations(ITERATIONS * LOOPS);
            return 0;
        }

        public void testRun() {
            int x = 0;
            for (int a = 0; a < ITERATIONS; a++) {
                for (int b = 0; b < ITERATIONS; b++) {
                    for (int c = 0; c < ITERATIONS; c++) {
                        for (int d = 0; d < ITERATIONS; d++) {
                            for (int e = 0; e < ITERATIONS; e++) {
                                x++;
                            }
                        }
                    }
                }
            }
        }
    }

    public static class StringConcatenationTests extends PerformanceTestBase {
        private static final int ITERATIONS = 1000;

        public int startPerformance(PerformanceTestCase.Intermediates intermediates) {
            intermediates.setInternalIterations(ITERATIONS);
            return 0;
        }

        public void testStringConcatenation1() {
            StringBuffer buffer = new StringBuffer();
            for (int i = ITERATIONS - 1; i >= 0; i--) {
                buffer.append("Hello World!\n");
            }
            buffer = null;
        }

        public void testStringConcatenation2() {
            String string = "";
            for (int i = ITERATIONS - 1; i >= 0; i--) {
                string += "Hello World!\n";
            }
            string = null;
        }
    }

    public static class StringLength extends PerformanceTestBase {
        private static final int ITERATIONS = 10000;
        private static final String TEST_STRING = "This is the string we use for testing..."; // 40 chars

        public int startPerformance(PerformanceTestCase.Intermediates intermediates) {
            intermediates.setInternalIterations(ITERATIONS * 10);
            return 0;
        }

        public void testRun() {
            String testStr = TEST_STRING;
            int length;

            for (int i = ITERATIONS - 1; i >= 0; i--) {
                length = testStr.length();
                length = testStr.length();
                length = testStr.length();
                length = testStr.length();
                length = testStr.length();
                length = testStr.length();
                length = testStr.length();
                length = testStr.length();
                length = testStr.length();
                length = testStr.length();
            }
        }
    }

    public static class EmptyJniStaticMethod0 extends PerformanceTestBase {
        private static final int ITERATIONS = 10000;

        public int startPerformance(PerformanceTestCase.Intermediates intermediates) {
            intermediates.setInternalIterations(ITERATIONS * 10);
            return 0;
        }

        public void testRun() {
            int a, b, c, d, e, f;

            a = b = c = d = e = f = 0;

            for (int i = ITERATIONS - 1; i >= 0; i--) {
                NativeTestTarget.emptyJniStaticMethod0();
                NativeTestTarget.emptyJniStaticMethod0();
                NativeTestTarget.emptyJniStaticMethod0();
                NativeTestTarget.emptyJniStaticMethod0();
                NativeTestTarget.emptyJniStaticMethod0();
                NativeTestTarget.emptyJniStaticMethod0();
                NativeTestTarget.emptyJniStaticMethod0();
                NativeTestTarget.emptyJniStaticMethod0();
                NativeTestTarget.emptyJniStaticMethod0();
                NativeTestTarget.emptyJniStaticMethod0();
            }
        }
    }
    public static class EmptyJniStaticMethod6 extends PerformanceTestBase {
        private static final int ITERATIONS = 10000;

        public int startPerformance(PerformanceTestCase.Intermediates intermediates) {
            intermediates.setInternalIterations(ITERATIONS * 10);
            return 0;
        }

        public void testRun() {
            int a, b, c, d, e, f;

            a = b = c = d = e = f = 0;

            for (int i = ITERATIONS - 1; i >= 0; i--) {
                NativeTestTarget.emptyJniStaticMethod6(a, b, c, d, e, f);
                NativeTestTarget.emptyJniStaticMethod6(a, b, c, d, e, f);
                NativeTestTarget.emptyJniStaticMethod6(a, b, c, d, e, f);
                NativeTestTarget.emptyJniStaticMethod6(a, b, c, d, e, f);
                NativeTestTarget.emptyJniStaticMethod6(a, b, c, d, e, f);
                NativeTestTarget.emptyJniStaticMethod6(a, b, c, d, e, f);
                NativeTestTarget.emptyJniStaticMethod6(a, b, c, d, e, f);
                NativeTestTarget.emptyJniStaticMethod6(a, b, c, d, e, f);
                NativeTestTarget.emptyJniStaticMethod6(a, b, c, d, e, f);
                NativeTestTarget.emptyJniStaticMethod6(a, b, c, d, e, f);
            }
        }
    }
    public static class EmptyJniStaticMethod6L extends PerformanceTestBase {
        private static final int ITERATIONS = 10000;

        public int startPerformance(PerformanceTestCase.Intermediates intermediates) {
            intermediates.setInternalIterations(ITERATIONS * 10);
            return 0;
        }

        public void testRun() {
            String a = null;
            String[] b = null;
            int[][] c = null;
            Object d = null;
            Object[] e = null;
            Object[][][][] f = null;

            for (int i = ITERATIONS - 1; i >= 0; i--) {
                NativeTestTarget.emptyJniStaticMethod6L(a, b, c, d, e, f);
                NativeTestTarget.emptyJniStaticMethod6L(a, b, c, d, e, f);
                NativeTestTarget.emptyJniStaticMethod6L(a, b, c, d, e, f);
                NativeTestTarget.emptyJniStaticMethod6L(a, b, c, d, e, f);
                NativeTestTarget.emptyJniStaticMethod6L(a, b, c, d, e, f);
                NativeTestTarget.emptyJniStaticMethod6L(a, b, c, d, e, f);
                NativeTestTarget.emptyJniStaticMethod6L(a, b, c, d, e, f);
                NativeTestTarget.emptyJniStaticMethod6L(a, b, c, d, e, f);
                NativeTestTarget.emptyJniStaticMethod6L(a, b, c, d, e, f);
                NativeTestTarget.emptyJniStaticMethod6L(a, b, c, d, e, f);
            }
        }
    }

    public static class StringCrawl extends PerformanceTestBase {
        private static final int ITERATIONS = 10000;
        private static final String TEST_STRING = "This is the string we use for testing..."; // 40 chars

        public int startPerformance(PerformanceTestCase.Intermediates intermediates) {
            intermediates.setInternalIterations(ITERATIONS * TEST_STRING.length());
            return 0;
        }

        public void testRun() {
            String testStr = TEST_STRING;
            char ch;
            for (int i = ITERATIONS - 1; i >= 0; i--) {
                /* this is the wrong way to walk through a string */
                for (int j = 0; j < testStr.length(); j++) {
                    ch = testStr.charAt(j);
                }
            }
        }
    }

    public static class AddTest extends PerformanceTestBase {
        private static final int ITERATIONS = 10000;

        public int startPerformance(PerformanceTestCase.Intermediates intermediates) {
            intermediates.setInternalIterations(ITERATIONS * 20);
            return 0;
        }

        public void testRun() {
            int j = 0;
            for (int i = ITERATIONS - 1; i >= 0; i--) {
                j++;
                j++;
                j++;
                j++;
                j++;
                j++;
                j++;
                j++;
                j++;
                j++;
                j++;
                j++;
                j++;
                j++;
                j++;
                j++;
                j++;
                j++;
                j++;
                j++;
            }
        }
    }

    public static class AddMemberVariableTest extends PerformanceTestBase {
        private static final int ITERATIONS = 10000;
        private int j;

        public void setUp() throws Exception {
           super.setUp();
           j = 0;
        }

        public int startPerformance(PerformanceTestCase.Intermediates intermediates) {
            intermediates.setInternalIterations(ITERATIONS * 10);
            return 0;
        }

        public void testAddMemberVariableTest() {
            for (int i = ITERATIONS - 1; i >= 0; i--) {
                j++;
                j++;
                j++;
                j++;
                j++;
                j++;
                j++;
                j++;
                j++;
                j++;
            }
        }

        public void testAddMemberVariableInMethodTest() {
            for (int i = ITERATIONS - 1; i >= 0; i--) {
                add();
                add();
                add();
                add();
                add();
                add();
                add();
                add();
                add();
                add();
            }
        }

        public void add() {
            j++;
        }
    }

    private interface EmptyInterface {
        public void emptyVirtual();

    }

    private static class EmptyClass implements EmptyInterface {
        public void emptyVirtual() {
        }

        public static void emptyStatic() {
        }
    }

    public static class EmptyClassBaseTest extends PerformanceTestBase {
        protected EmptyInterface mEmptyInterface;
        protected EmptyClass mEmptyClass;

        public void setUp() throws Exception {
            super.setUp();
            mEmptyClass = new EmptyClass();
            mEmptyInterface = mEmptyClass;
        }
        private static final int ITERATIONS = 10000;

        public int startPerformance(PerformanceTestCase.Intermediates intermediates) {
            intermediates.setInternalIterations(ITERATIONS * 10);
            return 0;
        }

        public void testEmptyVirtualMethod() {
            //EmptyClass emtpyClass = mEmptyClass;
            for (int i = ITERATIONS - 1; i >= 0; i--) {
                mEmptyClass.emptyVirtual();
                mEmptyClass.emptyVirtual();
                mEmptyClass.emptyVirtual();
                mEmptyClass.emptyVirtual();
                mEmptyClass.emptyVirtual();
                mEmptyClass.emptyVirtual();
                mEmptyClass.emptyVirtual();
                mEmptyClass.emptyVirtual();
                mEmptyClass.emptyVirtual();
                mEmptyClass.emptyVirtual();
            }
        }

        public void testEmptyVirtualMethodTestInLocal() {
            EmptyClass empty = mEmptyClass;
            for (int i = ITERATIONS - 1; i >= 0; i--) {
                empty.emptyVirtual();
                empty.emptyVirtual();
                empty.emptyVirtual();
                empty.emptyVirtual();
                empty.emptyVirtual();
                empty.emptyVirtual();
                empty.emptyVirtual();
                empty.emptyVirtual();
                empty.emptyVirtual();
                empty.emptyVirtual();
            }
        }

    public void testEmptyStaticMethod () {
            for (int i = ITERATIONS - 1; i >= 0; i--) {
                EmptyClass.emptyStatic();
                EmptyClass.emptyStatic();
                EmptyClass.emptyStatic();
                EmptyClass.emptyStatic();
                EmptyClass.emptyStatic();
                EmptyClass.emptyStatic();
                EmptyClass.emptyStatic();
                EmptyClass.emptyStatic();
                EmptyClass.emptyStatic();
                EmptyClass.emptyStatic();
            }
        }

    public void testEmptyJniStaticMethod0() {

            for (int i = ITERATIONS - 1; i >= 0; i--) {
                NativeTestTarget.emptyJniStaticMethod0();
                NativeTestTarget.emptyJniStaticMethod0();
                NativeTestTarget.emptyJniStaticMethod0();
                NativeTestTarget.emptyJniStaticMethod0();
                NativeTestTarget.emptyJniStaticMethod0();
                NativeTestTarget.emptyJniStaticMethod0();
                NativeTestTarget.emptyJniStaticMethod0();
                NativeTestTarget.emptyJniStaticMethod0();
                NativeTestTarget.emptyJniStaticMethod0();
                NativeTestTarget.emptyJniStaticMethod0();
            }
        }

    public void testEmptyJniStaticMethod6() {
            int a, b, c, d, e, f;

            a = b = c = d = e = f = 0;

            for (int i = ITERATIONS - 1; i >= 0; i--) {
                NativeTestTarget.emptyJniStaticMethod6(a, b, c, d, e, f);
                NativeTestTarget.emptyJniStaticMethod6(a, b, c, d, e, f);
                NativeTestTarget.emptyJniStaticMethod6(a, b, c, d, e, f);
                NativeTestTarget.emptyJniStaticMethod6(a, b, c, d, e, f);
                NativeTestTarget.emptyJniStaticMethod6(a, b, c, d, e, f);
                NativeTestTarget.emptyJniStaticMethod6(a, b, c, d, e, f);
                NativeTestTarget.emptyJniStaticMethod6(a, b, c, d, e, f);
                NativeTestTarget.emptyJniStaticMethod6(a, b, c, d, e, f);
                NativeTestTarget.emptyJniStaticMethod6(a, b, c, d, e, f);
                NativeTestTarget.emptyJniStaticMethod6(a, b, c, d, e, f);
            }
        }

    public void testEmptyInternalStaticMethod() {
            /*
             * The method called is a VM-internal method with no extra
             * wrapping.
             */
            for (int i = ITERATIONS - 1; i >= 0; i--) {
                NativeTestTarget.emptyInternalStaticMethod();
                NativeTestTarget.emptyInternalStaticMethod();
                NativeTestTarget.emptyInternalStaticMethod();
                NativeTestTarget.emptyInternalStaticMethod();
                NativeTestTarget.emptyInternalStaticMethod();
                NativeTestTarget.emptyInternalStaticMethod();
                NativeTestTarget.emptyInternalStaticMethod();
                NativeTestTarget.emptyInternalStaticMethod();
                NativeTestTarget.emptyInternalStaticMethod();
                NativeTestTarget.emptyInternalStaticMethod();
            }
        }

    public void testEmptyInlineStaticMethod() {
            /*
             * The method called is a VM-internal method that gets
             * specially "inlined" in a bytecode transformation.
             */
            for (int i = ITERATIONS - 1; i >= 0; i--) {
                NativeTestTarget.emptyInlineMethod();
                NativeTestTarget.emptyInlineMethod();
                NativeTestTarget.emptyInlineMethod();
                NativeTestTarget.emptyInlineMethod();
                NativeTestTarget.emptyInlineMethod();
                NativeTestTarget.emptyInlineMethod();
                NativeTestTarget.emptyInlineMethod();
                NativeTestTarget.emptyInlineMethod();
                NativeTestTarget.emptyInlineMethod();
                NativeTestTarget.emptyInlineMethod();
            }
        }

    public void testEmptyInterfaceMethodTest() {
            EmptyInterface emptyInterface = mEmptyInterface;
            for (int i = ITERATIONS - 1; i >= 0; i--) {
                emptyInterface.emptyVirtual();
                emptyInterface.emptyVirtual();
                emptyInterface.emptyVirtual();
                emptyInterface.emptyVirtual();
                emptyInterface.emptyVirtual();
                emptyInterface.emptyVirtual();
                emptyInterface.emptyVirtual();
                emptyInterface.emptyVirtual();
                emptyInterface.emptyVirtual();
                emptyInterface.emptyVirtual();
            }
        }
    }

    public static class LoopTests extends PerformanceTestBase {
        private static final int ITERATIONS = 10000;
        private SizeTest mSizeTest = new SizeTest(ITERATIONS);

        public int startPerformance(PerformanceTestCase.Intermediates intermediates) {
            intermediates.setInternalIterations(ITERATIONS);
            return 0;
        }

        public void testForLoopTest() {
            int i = 0;
            for (; i < 10000; i++) {
            }
        }

        public void testWhileLoopTest() {
            int i = 0;

            while (i < 10000) {
                i++;
            }
        }

        public void testForLoopSizeCalledInside() {
            for (int i = 0; i < mSizeTest.size(); i++) {
            }
        }

        public void testForLoopSizeCalledOutside() {
            final int size = mSizeTest.size();
            for (int i = 0; i < size; i++) {
            }
        }
    }

    public static class BoundsCheckTest extends PerformanceTestBase {
        private static final int ITERATIONS = 10000;
        public int startPerformance(PerformanceTestCase.Intermediates intermediates) {
            intermediates.setInternalIterations(ITERATIONS * 10);
            return 0;
        }

        public void testRun() {
            int[] data = new int[1];

            for (int i = ITERATIONS - 1; i >= 0; i--) {
                data[0] = i;
                data[0] = i;
                data[0] = i;
                data[0] = i;
                data[0] = i;
                data[0] = i;
                data[0] = i;
                data[0] = i;
                data[0] = i;
                data[0] = i;
            }
        }
    }

    public static class ArrayListBase extends PerformanceTestBase {
        public void setUp() throws Exception {
            super.setUp();
            mList = new ArrayList();
            mList.add(0);
            mList.add(1);
            mList.add(2);
            mList.add(3);
            mList.add(4);
            mList.add(5);
            mList.add(6);
            mList.add(7);
            mList.add(8);
            mList.add(9);
        }

        public int startPerformance(PerformanceTestCase.Intermediates intermediates) {
            intermediates.setInternalIterations(100);
            return 0;
        }

        ArrayList<Integer> mList;

        public void testForArrayList() {
            int i = 0;
            int res = 0;
            for (; i < 100; i++) {
                for (int j = 0; j < mList.size(); j++) {
                    res += mList.get(j);
                }
            }
        }

        public void testForLocalArrayList() {
            int i = 0;
            int res = 0;
            for (; i < 100; i++) {
                final List<Integer> list = mList;
                final int N = list.size();
                for (int j = 0; j < N; j++) {
                    res += list.get(j);
                }
            }
        }

        public void testForEachArrayList() {
            int i = 0;
            int res = 0;
            for (; i < 100; i++) {
                for (Integer v : mList) {
                    res += v;
                }
            }
        }
    }

    public static class SynchronizedGetAndSetInt extends PerformanceTestBase {
        private static final int ITERATIONS = 100000;

        public int mMember = 0;

        public int startPerformance(PerformanceTestCase.Intermediates intermediates) {
            intermediates.setInternalIterations(ITERATIONS);
            return 0;
        }

        public void testRun() {
            int result = 0;
            for (int i = ITERATIONS - 1; i >= 0; i--) {
                synchronized (this) {
                    result = mMember;
                    mMember = i;
                }
            }
        }
    }

    public static class AtomicGetAndSetInt extends PerformanceTestBase {
        private static final int ITERATIONS = 100000;

        public AtomicInteger mMember = new AtomicInteger(0);

        public int startPerformance(PerformanceTestCase.Intermediates intermediates) {
            intermediates.setInternalIterations(ITERATIONS);
            return 0;
        }

        public void testRun() {
            int result = 0;
            for (int i = ITERATIONS - 1; i >= 0; i--) {
                result = mMember.getAndSet(i);
            }
        }
    }

    public static abstract class StringEquals extends PerformanceTestBase {
        private static final int ITERATIONS = 10000;

        protected String mString1, mString2;
        public void setUp() throws Exception {
          super.setUp();
        }

        public int startPerformance(PerformanceTestCase.Intermediates intermediates) {
            intermediates.setInternalIterations(ITERATIONS * 10);
            return 0;
        }

        public void testRun() {
            String string1 = mString1;
            String string2 = mString2;

            for (int i = ITERATIONS - 1; i >= 0; i--) {
                string1.equals(string2);
                string1.equals(string2);
                string1.equals(string2);
                string1.equals(string2);
                string1.equals(string2);
                string1.equals(string2);
                string1.equals(string2);
                string1.equals(string2);
                string1.equals(string2);
                string1.equals(string2);
            }
        }
    }

    public static class StringEquals2 extends StringEquals {
        public void setUp() throws Exception {
            mString1 = "01";
            mString2 = "0x";
        }
    }
    public static class StringEquals10 extends StringEquals {
        public void setUp() throws Exception {
            mString1 = "0123456789";
            mString2 = "012345678x";
        }
    }
    public static class StringEquals20 extends StringEquals {
        public void setUp() throws Exception {
            mString1 = "01234567890123456789";
            mString2 = "0123456789012345678x";
        }
    }

    public static class StringEquals200 extends StringEquals {
        public void setUp() throws Exception {
            mString1 = "0123456789012345678901234567890123456789"
                    + "0123456789012345678901234567890123456789"
                    + "0123456789012345678901234567890123456789"
                    + "0123456789012345678901234567890123456789"
                    + "0123456789012345678901234567890123456789";
            mString2 = "0123456789012345678901234567890123456789"
                    + "0123456789012345678901234567890123456789"
                    + "0123456789012345678901234567890123456789"
                    + "0123456789012345678901234567890123456789"
                    + "012345678901234567890123456789012345678x";
        }
    }
    public static class StringEquals200U extends StringEquals {
        /* make one of the strings non-word aligned (bad memcmp case) */
        public void setUp() throws Exception {
            String tmpStr;
            mString1 = "0123456789012345678901234567890123456789"
                    + "0123456789012345678901234567890123456789"
                    + "0123456789012345678901234567890123456789"
                    + "0123456789012345678901234567890123456789"
                    + "0123456789012345678901234567890123456789";
            tmpStr = "z0123456789012345678901234567890123456789"
                    + "0123456789012345678901234567890123456789"
                    + "0123456789012345678901234567890123456789"
                    + "0123456789012345678901234567890123456789"
                    + "012345678901234567890123456789012345678x";
            mString2 = tmpStr.substring(1);
        }
    }

    public static abstract class StringCompareTo extends PerformanceTestBase {
        private static final int ITERATIONS = 10000;

        protected String mString1, mString2;

        public int startPerformance(PerformanceTestCase.Intermediates intermediates) {
            intermediates.setInternalIterations(ITERATIONS * 10);
            return 0;
        }

        public void testRun() {
            String string1 = mString1;
            String string2 = mString2;

            for (int i = ITERATIONS - 1; i >= 0; i--) {
                string1.compareTo(string2);
                string1.compareTo(string2);
                string1.compareTo(string2);
                string1.compareTo(string2);
                string1.compareTo(string2);
                string1.compareTo(string2);
                string1.compareTo(string2);
                string1.compareTo(string2);
                string1.compareTo(string2);
                string1.compareTo(string2);
            }
        }
    }
    public static class StringCompareTo10 extends StringCompareTo {
        public void setUp() throws Exception {
            mString1 = "0123456789";
            mString2 = "012345678x";
        }
    }
    public static class StringCompareTo200 extends StringCompareTo {
        public void setUp() throws Exception {
            mString1 = "0123456789012345678901234567890123456789"
                    + "0123456789012345678901234567890123456789"
                    + "0123456789012345678901234567890123456789"
                    + "0123456789012345678901234567890123456789"
                    + "0123456789012345678901234567890123456789";
            mString2 = "0123456789012345678901234567890123456789"
                    + "0123456789012345678901234567890123456789"
                    + "0123456789012345678901234567890123456789"
                    + "0123456789012345678901234567890123456789"
                    + "012345678901234567890123456789012345678x";
        }
    }
}

