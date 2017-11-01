/**
 * Copyright (c) 2016, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.utils;

import static com.android.server.utils.PriorityDump.dump;

import static org.junit.Assert.assertSame;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.same;
import static org.mockito.Mockito.verify;

import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import com.android.server.utils.PriorityDump.PriorityDumper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.FileDescriptor;
import java.io.PrintWriter;

@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class PriorityDumpTest {

    private static final String[] EMPTY_ARGS = {};

    @Mock
    private PriorityDumper mDumper;
    @Mock
    private PrintWriter mPw;

    private final FileDescriptor mFd = FileDescriptor.err;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testNullArgs() {
        dump(mDumper, mFd, mPw, null);
        verify(mDumper).dump(same(mFd), same(mPw), eq(null));
    }

    @Test
    public void testNoArgs() {
        dump(mDumper, mFd, mPw, EMPTY_ARGS);
        verify(mDumper).dump(same(mFd), same(mPw), same(EMPTY_ARGS));
    }

    @Test
    public void testNonPriorityArgs() {
        final String[] args = {
                "--dumb_priority"
        };
        dump(mDumper, mFd, mPw, args);
        verify(mDumper).dump(same(mFd), same(mPw), same(args));
    }

    @Test
    public void testMissingPriority() {
        final String[] args = {
                "--dump_priority"
        };
        dump(mDumper, mFd, mPw, args);
        verify(mDumper).dump(same(mFd), same(mPw), same(args));
    }

    @Test
    public void testInvalidPriorityNoExtraArgs() {
        final String[] args = {
                "--dump_priority", "SUPER_HIGH"
        };
        dump(mDumper, mFd, mPw, args);
        verify(mDumper).dump(same(mFd), same(mPw), same(args));
    }

    @Test
    public void testInvalidPriorityExtraArgs() {
        final String[] args = {
                "--dump_priority", "SUPER_HIGH", "--high", "--five"
        };
        dump(mDumper, mFd, mPw, args);
        verify(mDumper).dump(same(mFd), same(mPw), same(args));
    }

    @Test
    public void testNoPriorityCallsAllMethods() {
        final String[] args = {
                "1", "2", "3"
        };

        // Cannot use mDumper here because it would mock the dump() call.
        final FakeDumper fakeDumper = new FakeDumper();

        dump(fakeDumper, mFd, mPw, args);

        assertSame(mFd, fakeDumper.criticalFd);
        assertSame(mPw, fakeDumper.criticalPw);
        assertSame(args, fakeDumper.criticalArgs);
        assertSame(mFd, fakeDumper.highFd);
        assertSame(mPw, fakeDumper.highPw);
        assertSame(args, fakeDumper.highArgs);
        assertSame(mFd, fakeDumper.normalFd);
        assertSame(mPw, fakeDumper.normalPw);
        assertSame(args, fakeDumper.normalArgs);
    }

    @Test
    public void testCriticalNoExtraArgs() {
        dump(mDumper, mFd, mPw, new String[] {
                "--dump_priority", "CRITICAL"
        });
        verify(mDumper).dumpCritical(same(mFd), same(mPw), eq(EMPTY_ARGS));
    }

    @Test
    public void testCriticalExtraArgs() {
        dump(mDumper, mFd, mPw, new String[] {
                "--dump_priority", "CRITICAL", "--high", "--five"
        });
        verify(mDumper).dumpCritical(same(mFd), same(mPw), eq(new String[] {
                "--high", "--five"
        }));
    }

    @Test
    public void testHighNoExtraArgs() {
        dump(mDumper, mFd, mPw, new String[] {
                "--dump_priority", "HIGH"
        });
        verify(mDumper).dumpHigh(same(mFd), same(mPw), eq(EMPTY_ARGS));
    }

    @Test
    public void testHighExtraArgs() {
        dump(mDumper, mFd, mPw, new String[] {
                "--dump_priority", "HIGH", "--high", "--five"
        });
        verify(mDumper).dumpHigh(same(mFd), same(mPw), eq(new String[] {
                "--high", "--five"
        }));
    }

    @Test
    public void testNormalNoExtraArgs() {
        dump(mDumper, mFd, mPw, new String[] {
                "--dump_priority", "NORMAL"
        });
        verify(mDumper).dumpNormal(same(mFd), same(mPw), eq(EMPTY_ARGS));
    }

    @Test
    public void testNormalExtraArgs() {
        dump(mDumper, mFd, mPw, new String[] {
                "--dump_priority", "NORMAL", "--high", "--five"
        });
        verify(mDumper).dumpNormal(same(mFd), same(mPw), eq(new String[] {
                "--high", "--five"
        }));
    }

    private final class FakeDumper implements PriorityDumper {

        String[] criticalArgs, highArgs, normalArgs;
        FileDescriptor criticalFd, highFd, normalFd;
        PrintWriter criticalPw, highPw, normalPw;

        @Override
        public void dumpCritical(FileDescriptor fd, PrintWriter pw, String[] args) {
            criticalFd = fd;
            criticalPw = pw;
            criticalArgs = args;
        }

        @Override
        public void dumpHigh(FileDescriptor fd, PrintWriter pw, String[] args) {
            highFd = fd;
            highPw = pw;
            highArgs = args;
        }

        @Override
        public void dumpNormal(FileDescriptor fd, PrintWriter pw, String[] args) {
            normalFd = fd;
            normalPw = pw;
            normalArgs = args;
        }
    }
}
