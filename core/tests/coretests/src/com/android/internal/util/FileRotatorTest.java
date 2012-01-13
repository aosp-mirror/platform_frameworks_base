/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.internal.util;

import static android.text.format.DateUtils.DAY_IN_MILLIS;
import static android.text.format.DateUtils.HOUR_IN_MILLIS;
import static android.text.format.DateUtils.MINUTE_IN_MILLIS;
import static android.text.format.DateUtils.SECOND_IN_MILLIS;
import static android.text.format.DateUtils.WEEK_IN_MILLIS;
import static android.text.format.DateUtils.YEAR_IN_MILLIS;

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.Suppress;
import android.util.Log;

import com.android.internal.util.FileRotator.Reader;
import com.android.internal.util.FileRotator.Writer;
import com.google.android.collect.Lists;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProtocolException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

import libcore.io.IoUtils;

/**
 * Tests for {@link FileRotator}.
 */
public class FileRotatorTest extends AndroidTestCase {
    private static final String TAG = "FileRotatorTest";

    private File mBasePath;

    private static final String PREFIX = "rotator";
    private static final String ANOTHER_PREFIX = "another_rotator";

    private static final long TEST_TIME = 1300000000000L;

    // TODO: test throwing rolls back correctly

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mBasePath = getContext().getFilesDir();
        IoUtils.deleteContents(mBasePath);
    }

    public void testEmpty() throws Exception {
        final FileRotator rotate1 = new FileRotator(
                mBasePath, PREFIX, DAY_IN_MILLIS, WEEK_IN_MILLIS);
        final FileRotator rotate2 = new FileRotator(
                mBasePath, ANOTHER_PREFIX, DAY_IN_MILLIS, WEEK_IN_MILLIS);

        final RecordingReader reader = new RecordingReader();
        long currentTime = TEST_TIME;

        // write single new value
        rotate1.combineActive(reader, writer("foo"), currentTime);
        reader.assertRead();

        // assert that one rotator doesn't leak into another
        assertReadAll(rotate1, "foo");
        assertReadAll(rotate2);
    }

    public void testCombine() throws Exception {
        final FileRotator rotate = new FileRotator(
                mBasePath, PREFIX, DAY_IN_MILLIS, WEEK_IN_MILLIS);

        final RecordingReader reader = new RecordingReader();
        long currentTime = TEST_TIME;

        // first combine should have empty read, but still write data.
        rotate.combineActive(reader, writer("foo"), currentTime);
        reader.assertRead();
        assertReadAll(rotate, "foo");

        // second combine should replace contents; should read existing data,
        // and write final data to disk.
        currentTime += SECOND_IN_MILLIS;
        reader.reset();
        rotate.combineActive(reader, writer("bar"), currentTime);
        reader.assertRead("foo");
        assertReadAll(rotate, "bar");
    }

    public void testRotate() throws Exception {
        final FileRotator rotate = new FileRotator(
                mBasePath, PREFIX, DAY_IN_MILLIS, WEEK_IN_MILLIS);

        final RecordingReader reader = new RecordingReader();
        long currentTime = TEST_TIME;

        // combine first record into file
        rotate.combineActive(reader, writer("foo"), currentTime);
        reader.assertRead();
        assertReadAll(rotate, "foo");

        // push time a few minutes forward; shouldn't rotate file
        reader.reset();
        currentTime += MINUTE_IN_MILLIS;
        rotate.combineActive(reader, writer("bar"), currentTime);
        reader.assertRead("foo");
        assertReadAll(rotate, "bar");

        // push time forward enough to rotate file; should still have same data
        currentTime += DAY_IN_MILLIS + SECOND_IN_MILLIS;
        rotate.maybeRotate(currentTime);
        assertReadAll(rotate, "bar");

        // combine a second time, should leave rotated value untouched, and
        // active file should be empty.
        reader.reset();
        rotate.combineActive(reader, writer("baz"), currentTime);
        reader.assertRead();
        assertReadAll(rotate, "bar", "baz");
    }

    public void testDelete() throws Exception {
        final FileRotator rotate = new FileRotator(
                mBasePath, PREFIX, MINUTE_IN_MILLIS, DAY_IN_MILLIS);

        final RecordingReader reader = new RecordingReader();
        long currentTime = TEST_TIME;

        // create first record and trigger rotating it
        rotate.combineActive(reader, writer("foo"), currentTime);
        reader.assertRead();
        currentTime += MINUTE_IN_MILLIS + SECOND_IN_MILLIS;
        rotate.maybeRotate(currentTime);

        // create second record
        reader.reset();
        rotate.combineActive(reader, writer("bar"), currentTime);
        reader.assertRead();
        assertReadAll(rotate, "foo", "bar");

        // push time far enough to expire first record
        currentTime = TEST_TIME + DAY_IN_MILLIS + (2 * MINUTE_IN_MILLIS);
        rotate.maybeRotate(currentTime);
        assertReadAll(rotate, "bar");

        // push further to delete second record
        currentTime += WEEK_IN_MILLIS;
        rotate.maybeRotate(currentTime);
        assertReadAll(rotate);
    }

    public void testThrowRestoresBackup() throws Exception {
        final FileRotator rotate = new FileRotator(
                mBasePath, PREFIX, MINUTE_IN_MILLIS, DAY_IN_MILLIS);

        final RecordingReader reader = new RecordingReader();
        long currentTime = TEST_TIME;

        // first, write some valid data
        rotate.combineActive(reader, writer("foo"), currentTime);
        reader.assertRead();
        assertReadAll(rotate, "foo");

        try {
            // now, try writing which will throw
            reader.reset();
            rotate.combineActive(reader, new Writer() {
                public void write(OutputStream out) throws IOException {
                    new DataOutputStream(out).writeUTF("bar");
                    throw new ProtocolException("yikes");
                }
            }, currentTime);

            fail("woah, somehow able to write exception");
        } catch (ProtocolException e) {
            // expected from above
        }

        // assert that we read original data, and that it's still intact after
        // the failed write above.
        reader.assertRead("foo");
        assertReadAll(rotate, "foo");
    }

    public void testOtherFilesAndMalformed() throws Exception {
        final FileRotator rotate = new FileRotator(
                mBasePath, PREFIX, SECOND_IN_MILLIS, SECOND_IN_MILLIS);

        // should ignore another prefix
        touch("another_rotator.1024");
        touch("another_rotator.1024-2048");
        assertReadAll(rotate);

        // verify that broken filenames don't crash
        touch("rotator");
        touch("rotator...");
        touch("rotator.-");
        touch("rotator.---");
        touch("rotator.a-b");
        touch("rotator_but_not_actually");
        assertReadAll(rotate);

        // and make sure that we can read something from a legit file
        write("rotator.100-200", "meow");
        assertReadAll(rotate, "meow");
    }

    private static final String RED = "red";
    private static final String GREEN = "green";
    private static final String BLUE = "blue";
    private static final String YELLOW = "yellow";

    public void testQueryMatch() throws Exception {
        final FileRotator rotate = new FileRotator(
                mBasePath, PREFIX, HOUR_IN_MILLIS, YEAR_IN_MILLIS);

        final RecordingReader reader = new RecordingReader();
        long currentTime = TEST_TIME;

        // rotate a bunch of historical data
        rotate.maybeRotate(currentTime);
        rotate.combineActive(reader, writer(RED), currentTime);

        currentTime += DAY_IN_MILLIS;
        rotate.maybeRotate(currentTime);
        rotate.combineActive(reader, writer(GREEN), currentTime);

        currentTime += DAY_IN_MILLIS;
        rotate.maybeRotate(currentTime);
        rotate.combineActive(reader, writer(BLUE), currentTime);

        currentTime += DAY_IN_MILLIS;
        rotate.maybeRotate(currentTime);
        rotate.combineActive(reader, writer(YELLOW), currentTime);

        final String[] FULL_SET = { RED, GREEN, BLUE, YELLOW };

        assertReadAll(rotate, FULL_SET);
        assertReadMatching(rotate, Long.MIN_VALUE, Long.MAX_VALUE, FULL_SET);
        assertReadMatching(rotate, Long.MIN_VALUE, currentTime, FULL_SET);
        assertReadMatching(rotate, TEST_TIME + SECOND_IN_MILLIS, currentTime, FULL_SET);

        // should omit last value, since it only touches at currentTime
        assertReadMatching(rotate, TEST_TIME + SECOND_IN_MILLIS, currentTime - SECOND_IN_MILLIS,
                RED, GREEN, BLUE);

        // check boundary condition
        assertReadMatching(rotate, TEST_TIME + DAY_IN_MILLIS, Long.MAX_VALUE, FULL_SET);
        assertReadMatching(rotate, TEST_TIME + DAY_IN_MILLIS + SECOND_IN_MILLIS, Long.MAX_VALUE,
                GREEN, BLUE, YELLOW);

        // test range smaller than file
        final long blueStart = TEST_TIME + (DAY_IN_MILLIS * 2);
        final long blueEnd = TEST_TIME + (DAY_IN_MILLIS * 3);
        assertReadMatching(rotate, blueStart + SECOND_IN_MILLIS, blueEnd - SECOND_IN_MILLIS, BLUE);

        // outside range should return nothing
        assertReadMatching(rotate, Long.MIN_VALUE, TEST_TIME - DAY_IN_MILLIS);
    }

    public void testClockRollingBackwards() throws Exception {
        final FileRotator rotate = new FileRotator(
                mBasePath, PREFIX, DAY_IN_MILLIS, YEAR_IN_MILLIS);

        final RecordingReader reader = new RecordingReader();
        long currentTime = TEST_TIME;

        // create record at current time
        // --> foo
        rotate.combineActive(reader, writer("foo"), currentTime);
        reader.assertRead();
        assertReadAll(rotate, "foo");

        // record a day in past; should create a new active file
        // --> bar
        currentTime -= DAY_IN_MILLIS;
        reader.reset();
        rotate.combineActive(reader, writer("bar"), currentTime);
        reader.assertRead();
        assertReadAll(rotate, "bar", "foo");

        // verify that we rewrite current active file
        // bar --> baz
        currentTime += SECOND_IN_MILLIS;
        reader.reset();
        rotate.combineActive(reader, writer("baz"), currentTime);
        reader.assertRead("bar");
        assertReadAll(rotate, "baz", "foo");

        // return to present and verify we write oldest active file
        // baz --> meow
        currentTime = TEST_TIME + SECOND_IN_MILLIS;
        reader.reset();
        rotate.combineActive(reader, writer("meow"), currentTime);
        reader.assertRead("baz");
        assertReadAll(rotate, "meow", "foo");

        // current time should trigger rotate of older active file
        rotate.maybeRotate(currentTime);

        // write active file, verify this time we touch original
        // foo --> yay
        reader.reset();
        rotate.combineActive(reader, writer("yay"), currentTime);
        reader.assertRead("foo");
        assertReadAll(rotate, "meow", "yay");
    }

    @Suppress
    public void testFuzz() throws Exception {
        final FileRotator rotate = new FileRotator(
                mBasePath, PREFIX, HOUR_IN_MILLIS, DAY_IN_MILLIS);

        final RecordingReader reader = new RecordingReader();
        long currentTime = TEST_TIME;

        // walk forward through time, ensuring that files are cleaned properly
        final Random random = new Random();
        for (int i = 0; i < 1024; i++) {
            currentTime += Math.abs(random.nextLong()) % DAY_IN_MILLIS;

            reader.reset();
            rotate.combineActive(reader, writer("meow"), currentTime);

            if (random.nextBoolean()) {
                rotate.maybeRotate(currentTime);
            }
        }

        rotate.maybeRotate(currentTime);

        Log.d(TAG, "currentTime=" + currentTime);
        Log.d(TAG, Arrays.toString(mBasePath.list()));
    }

    public void testRecoverAtomic() throws Exception {
        write("rotator.1024-2048", "foo");
        write("rotator.1024-2048.backup", "bar");
        write("rotator.2048-4096", "baz");
        write("rotator.2048-4096.no_backup", "");

        final FileRotator rotate = new FileRotator(
                mBasePath, PREFIX, SECOND_IN_MILLIS, SECOND_IN_MILLIS);

        // verify backup value was recovered; no_backup indicates that
        // corresponding file had no backup and should be discarded.
        assertReadAll(rotate, "bar");
    }

    private void touch(String... names) throws IOException {
        for (String name : names) {
            final OutputStream out = new FileOutputStream(new File(mBasePath, name));
            out.close();
        }
    }

    private void write(String name, String value) throws IOException {
        final DataOutputStream out = new DataOutputStream(
                new FileOutputStream(new File(mBasePath, name)));
        out.writeUTF(value);
        out.close();
    }

    private static Writer writer(final String value) {
        return new Writer() {
            public void write(OutputStream out) throws IOException {
                new DataOutputStream(out).writeUTF(value);
            }
        };
    }

    private static void assertReadAll(FileRotator rotate, String... expected) throws IOException {
        assertReadMatching(rotate, Long.MIN_VALUE, Long.MAX_VALUE, expected);
    }

    private static void assertReadMatching(
            FileRotator rotate, long matchStartMillis, long matchEndMillis, String... expected)
            throws IOException {
        final RecordingReader reader = new RecordingReader();
        rotate.readMatching(reader, matchStartMillis, matchEndMillis);
        reader.assertRead(expected);
    }

    private static class RecordingReader implements Reader {
        private ArrayList<String> mActual = Lists.newArrayList();

        public void read(InputStream in) throws IOException {
            mActual.add(new DataInputStream(in).readUTF());
        }

        public void reset() {
            mActual.clear();
        }

        public void assertRead(String... expected) {
            assertEquals(expected.length, mActual.size());

            final ArrayList<String> actualCopy = new ArrayList<String>(mActual);
            for (String value : expected) {
                if (!actualCopy.remove(value)) {
                    final String expectedString = Arrays.toString(expected);
                    final String actualString = Arrays.toString(mActual.toArray());
                    fail("expected: " + expectedString + " but was: " + actualString);
                }
            }
        }
    }
}
