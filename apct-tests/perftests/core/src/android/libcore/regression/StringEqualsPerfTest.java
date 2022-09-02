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
import android.test.suitebuilder.annotation.LargeTest;

import androidx.test.runner.AndroidJUnit4;

import junit.framework.Assert;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Benchmarks to measure the performance of String.equals for Strings of varying lengths. Each
 * benchmarks makes 5 measurements, aiming at covering cases like strings of equal length that are
 * not equal, identical strings with different references, strings with different endings, interned
 * strings, and strings of different lengths.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class StringEqualsPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private final String mLong1 =
            "Ahead-of-time compilation is possible as the compiler may just convert an instruction"
                + " thus: dex code: add-int v1000, v2000, v3000 C code: setIntRegter(1000,"
                + " call_dex_add_int(getIntRegister(2000), getIntRegister(3000)) This means even"
                + " lidinstructions may have code generated, however, it is not expected that code"
                + " generate inthis way will perform well. The job of AOT verification is to tell"
                + " the compiler thatinstructions are sound and provide tests to detect unsound"
                + " sequences so slow path codemay be generated. Other than for totally invalid"
                + " code, the verification may fail at AOrrun-time. At AOT time it can be because"
                + " of incomplete information, at run-time it can ethat code in a different apk"
                + " that the application depends upon has changed. The Dalvikverifier would return"
                + " a bool to state whether a Class were good or bad. In ART the fail case becomes"
                + " either a soft or hard failure. Classes have new states to represent that a soft"
                + " failure occurred at compile time and should be re-verified at run-time.";

    private final String mVeryLong =
            "Garbage collection has two phases. The first distinguishes live objects from garbage"
                + " objects.  The second is reclaiming the rage of garbage objectIn the mark-sweep"
                + " algorithm used by Dalvik, the first phase is achievd by computing the closure"
                + " of all reachable objects in a process known as tracing from theoots.  After"
                + " thetrace has completed, garbage objects are reclaimed.  Each of these"
                + " operations can beparallelized and can be interleaved with the operation of the"
                + " applicationTraditionally,the tracing phase dominates the time spent in garbage"
                + " collection.  The greatreduction ipause time can be achieved by interleaving as"
                + " much of this phase as possible with theapplication. If we simply ran the GC in"
                + " a separate thread with no other changes, normaloperation of an application"
                + " would confound the trace.  Abstractly, the GC walks the h oall reachable"
                + " objects.  When the application is paused, the object graph cannot change.The GC"
                + " can therefore walk this structure and assume that all reachable objects"
                + " live.When the application is running, this graph may be altered. New nodes may"
                + " be addnd edgemay be changed.  These changes may cause live objects to be hidden"
                + " and falsely recla bythe GC.  To avoid this problem a write barrier is used to"
                + " intercept and record modifionto objects in a separate structure.  After"
                + " performing its walk, the GC will revisit theupdated objects and re-validate its"
                + " assumptions.  Without a card table, the garbagecollector would have to visit"
                + " all objects reached during the trace looking for dirtied objects.  The cost of"
                + " this operation would be proportional to the amount of live data.With a card"
                + " table, the cost of this operation is proportional to the amount of updateatThe"
                + " write barrier in Dalvik is a card marking write barrier.  Card marking is the"
                + " proceof noting the location of object connectivity changes on a sub-page"
                + " granularity.  A caris merely a colorful term for a contiguous extent of memory"
                + " smaller than a page, commonsomewhere between 128- and 512-bytes.  Card marking"
                + " is implemented by instrumenting alllocations in the virtual machine which can"
                + " assign a pointer to an object.  After themalpointer assignment has occurred, a"
                + " byte is written to a byte-map spanning the heap whiccorresponds to the location"
                + " of the updated object.  This byte map is known as a card taThe garbage"
                + " collector visits this card table and looks for written bytes to reckon"
                + " thelocation of updated objects.  It then rescans all objects located on the"
                + " dirty card,correcting liveness assumptions that were invalidated by the"
                + " application.  While cardmarking imposes a small burden on the application"
                + " outside of a garbage collection, theoverhead of maintaining the card table is"
                + " paid for by the reduced time spent insidegarbage collection. With the"
                + " concurrent garbage collection thread and a write barriersupported by the"
                + " interpreter, JIT, and Runtime we modify garbage collection";

    private final String[][] mShortStrings =
            new String[][] {
                // Equal, constant comparison
                {"a", "a"},
                // Different constants, first character different
                {":", " :"},
                // Different constants, last character different, same length
                {"ja M", "ja N"},
                // Different constants, different lengths
                {"$$$", "$$"},
                // Force execution of code beyond reference equality check
                {"hi", new String("hi")}
            };

    private final String[][] mMediumStrings =
            new String[][] {
                // Equal, constant comparison
                {"Hello my name is ", "Hello my name is "},
                // Different constants, different lengths
                {"What's your name?", "Whats your name?"},
                // Force execution of code beyond reference equality check
                {"Android Runtime", new String("Android Runtime")},
                // Different constants, last character different, same length
                {"v3ry Cre@tiVe?****", "v3ry Cre@tiVe?***."},
                // Different constants, first character different, same length
                {"!@#$%^&*()_++*^$#@", "0@#$%^&*()_++*^$#@"}
            };

    private final String[][] mLongStrings =
            new String[][] {
                // Force execution of code beyond reference equality check
                {mLong1, new String(mLong1)},
                // Different constants, last character different, same length
                {mLong1 + "fun!", mLong1 + "----"},
                // Equal, constant comparison
                {mLong1 + mLong1, mLong1 + mLong1},
                // Different constants, different lengths
                {mLong1 + "123456789", mLong1 + "12345678"},
                // Different constants, first character different, same length
                {"Android Runtime" + mLong1, "android Runtime" + mLong1}
            };

    private final String[][] mVeryLongStrings =
            new String[][] {
                // Force execution of code beyond reference equality check
                {mVeryLong, new String(mVeryLong)},
                // Different constants, different lengths
                {mVeryLong + mVeryLong, mVeryLong + " " + mVeryLong},
                // Equal, constant comparison
                {mVeryLong + mVeryLong + mVeryLong, mVeryLong + mVeryLong + mVeryLong},
                // Different constants, last character different, same length
                {mVeryLong + "77777", mVeryLong + "99999"},
                // Different constants, first character different
                {"Android Runtime" + mVeryLong, "android Runtime" + mVeryLong}
            };

    private final String[][] mEndStrings =
            new String[][] {
                // Different constants, medium but different lengths
                {"Hello", "Hello "},
                // Different constants, long but different lengths
                {mLong1, mLong1 + "x"},
                // Different constants, very long but different lengths
                {mVeryLong, mVeryLong + "?"},
                // Different constants, same medium lengths
                {"How are you doing today?", "How are you doing today "},
                // Different constants, short but different lengths
                {"1", "1."}
            };

    private final String mTmpStr1 =
            "012345678901234567890"
                    + "0123456789012345678901234567890123456789"
                    + "0123456789012345678901234567890123456789"
                    + "0123456789012345678901234567890123456789"
                    + "0123456789012345678901234567890123456789";

    private final String mTmpStr2 =
            "z012345678901234567890"
                    + "0123456789012345678901234567890123456789"
                    + "0123456789012345678901234567890123456789"
                    + "0123456789012345678901234567890123456789"
                    + "012345678901234567890123456789012345678x";

    private final String[][] mNonalignedStrings =
            new String[][] {
                // Different non-word aligned medium length strings
                {mTmpStr1, mTmpStr1.substring(1)},
                // Different differently non-word aligned medium length strings
                {mTmpStr2, mTmpStr2.substring(2)},
                // Different non-word aligned long length strings
                {mLong1, mLong1.substring(3)},
                // Different non-word aligned very long length strings
                {mVeryLong, mVeryLong.substring(1)},
                // Equal non-word aligned constant strings
                {"hello", "hello".substring(1)}
            };

    private final Object[] mObjects =
            new Object[] {
                // Compare to Double object
                new Double(1.5),
                // Compare to Integer object
                new Integer(9999999),
                // Compare to String array
                new String[] {"h", "i"},
                // Compare to int array
                new int[] {1, 2, 3},
                // Compare to Character object
                new Character('a')
            };

    // Check assumptions about how the compiler, new String(String), and String.intern() work.
    // Any failures here would invalidate these benchmarks.
    @Before
    public void setUp() throws Exception {
        // String constants are the same object
        Assert.assertSame("abc", "abc");
        // new String(String) makes a copy
        Assert.assertNotSame("abc", new String("abc"));
        // Interned strings are treated like constants, so it is not necessary to
        // separately benchmark interned strings.
        Assert.assertSame("abc", "abc".intern());
        Assert.assertSame("abc", new String("abc").intern());
        // Compiler folds constant strings into new constants
        Assert.assertSame(mLong1 + mLong1, mLong1 + mLong1);
    }

    // Benchmark cases of String.equals(null)
    @Test
    public void timeEqualsNull() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int i = 0; i < mMediumStrings.length; i++) {
                mMediumStrings[i][0].equals(null);
            }
        }
    }

    // Benchmark cases with very short (<5 character) Strings
    @Test
    public void timeEqualsShort() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int i = 0; i < mShortStrings.length; i++) {
                mShortStrings[i][0].equals(mShortStrings[i][1]);
            }
        }
    }

    // Benchmark cases with medium length (10-15 character) Strings
    @Test
    public void timeEqualsMedium() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int i = 0; i < mMediumStrings.length; i++) {
                mMediumStrings[i][0].equals(mMediumStrings[i][1]);
            }
        }
    }

    // Benchmark cases with long (>100 character) Strings
    @Test
    public void timeEqualsLong() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int i = 0; i < mLongStrings.length; i++) {
                mLongStrings[i][0].equals(mLongStrings[i][1]);
            }
        }
    }

    // Benchmark cases with very long (>1000 character) Strings
    @Test
    public void timeEqualsVeryLong() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int i = 0; i < mVeryLongStrings.length; i++) {
                mVeryLongStrings[i][0].equals(mVeryLongStrings[i][1]);
            }
        }
    }

    // Benchmark cases with non-word aligned Strings
    @Test
    public void timeEqualsNonWordAligned() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int i = 0; i < mNonalignedStrings.length; i++) {
                mNonalignedStrings[i][0].equals(mNonalignedStrings[i][1]);
            }
        }
    }

    // Benchmark cases with slight differences in the endings
    @Test
    public void timeEqualsEnd() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int i = 0; i < mEndStrings.length; i++) {
                mEndStrings[i][0].equals(mEndStrings[i][1]);
            }
        }
    }

    // Benchmark cases of comparing a string to a non-string object
    @Test
    public void timeEqualsNonString() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int i = 0; i < mMediumStrings.length; i++) {
                mMediumStrings[i][0].equals(mObjects[i]);
            }
        }
    }
}
