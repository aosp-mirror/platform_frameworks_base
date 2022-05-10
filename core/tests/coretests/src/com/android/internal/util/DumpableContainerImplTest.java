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
package com.android.internal.util;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.testng.Assert.assertThrows;

import android.util.Dumpable;

import com.android.internal.util.dump.DumpableContainerImpl;

import org.junit.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.atomic.AtomicReference;

public final class DumpableContainerImplTest {

    private final DumpableContainerImpl mImpl = new DumpableContainerImpl();
    private final StringWriter mSw = new StringWriter();
    private final PrintWriter mWriter = new PrintWriter(mSw);

    @Test
    public void testAddDumpable_null() {
        assertThrows(NullPointerException.class, () -> mImpl.addDumpable(null));
    }

    @Test
    public void testAddDumpable_dumpableWithoutName() {
        Dumpable namelessDumpable = new Dumpable() {

            @Override
            public String getDumpableName() {
                return null;
            }

            @Override
            public void dump(PrintWriter writer, String[] args) {
                throw new UnsupportedOperationException("D'OH!");
            }

        };
        assertThrows(NullPointerException.class, () -> mImpl.addDumpable(namelessDumpable));
    }

    @Test
    public void testListDumpables_empty() {
        mImpl.listDumpables("...", mWriter);

        assertWithMessage("listDumpables(...)").that(getOutput()).isEqualTo("...No dumpables\n");
    }

    @Test
    public void testListDumpables_one() {
        CustomDumpable dumpable1 = new CustomDumpable("one", "not used");

        mImpl.addDumpable(dumpable1);
        mImpl.listDumpables("...", mWriter);

        assertWithMessage("listDumpables()").that(getOutput()).isEqualTo("...1 dumpables: one\n");
    }

    @Test
    public void testListDumpables_twoDistinctNames() {
        CustomDumpable dumpable1 = new CustomDumpable("one", "not used");
        CustomDumpable dumpable2 = new CustomDumpable("two", "NOT USED");

        boolean added1 = mImpl.addDumpable(dumpable1);
        assertWithMessage("addDumpable(dumpable1)").that(added1).isTrue();

        boolean added2 = mImpl.addDumpable(dumpable2);
        assertWithMessage("addDumpable(dumpable2)").that(added2).isTrue();

        mImpl.listDumpables("...", mWriter);
        assertWithMessage("listDumpables()").that(getOutput())
                .isEqualTo("...2 dumpables: one two\n");
    }

    @Test
    public void testListDumpables_twoSameName() {
        CustomDumpable dumpable1 = new CustomDumpable("alterego", "not used");
        CustomDumpable dumpable2 = new CustomDumpable("alterego", "NOT USED");

        boolean added1 = mImpl.addDumpable(dumpable1);
        assertWithMessage("addDumpable(dumpable1)").that(added1).isTrue();

        boolean added2 = mImpl.addDumpable(dumpable2);
        assertWithMessage("addDumpable(dumpable2)").that(added2).isFalse();

        mImpl.listDumpables("...", mWriter);
        assertWithMessage("listDumpables()").that(getOutput())
                .isEqualTo("...1 dumpables: alterego\n");
    }

    @Test
    public void testOneDumpable_notFound() {
        CustomDumpable dumpable = new CustomDumpable("one", "ONE");

        mImpl.addDumpable(dumpable);
        mImpl.dumpOneDumpable("...", mWriter, "two", /* args= */ null);

        assertWithMessage("dumpOneDumpable()").that(getOutput()).isEqualTo("...No two\n");
    }

    @Test
    public void testOneDumpable_noArgs() {
        CustomDumpable dumpable = new CustomDumpable("The name is Bond", "James Bond!");

        mImpl.addDumpable(dumpable);
        mImpl.dumpOneDumpable("...", mWriter, "The name is Bond", /* args= */ null);

        assertWithMessage("dumpOneDumpable()").that(getOutput())
                .isEqualTo("...The name is Bond:\n"
                        + "......James Bond!\n");
    }

    @Test
    public void testOneDumpable_withArgs() {
        CustomDumpable dumpable = new CustomDumpable("The name is Bond", "James Bond!");

        mImpl.addDumpable(dumpable);
        mImpl.dumpOneDumpable("...", mWriter, "The name is Bond",
                new String[] { "Shaken", "not", "stirred" });

        assertWithMessage("dumpOneDumpable()").that(getOutput())
                .isEqualTo("...The name is Bond:\n"
                        + "......James Bond!\n"
                        + "......3 Args: Shaken,not,stirred,\n");
    }

    @Test
    public void testDumpAllDumpables_noArgs() {
        CustomDumpable dumpable1 = new CustomDumpable("one", "ONE");
        CustomDumpable dumpable2 = new CustomDumpable("two", "TWO");

        mImpl.addDumpable(dumpable1);
        mImpl.addDumpable(dumpable2);
        mImpl.dumpAllDumpables("...", mWriter, /* args= */ null);

        assertWithMessage("dumpAllDumpables()").that(getOutput())
                .isEqualTo("...2 dumpables:\n"
                        + "...#0: one\n"
                        + "......ONE\n"
                        + "...#1: two\n"
                        + "......TWO\n");
    }

    @Test
    public void testDumpAllDumpables_withArgs() {
        CustomDumpable dumpable1 = new CustomDumpable("one", "ONE");
        CustomDumpable dumpable2 = new CustomDumpable("two", "TWO");

        mImpl.addDumpable(dumpable1);
        mImpl.addDumpable(dumpable2);
        mImpl.dumpAllDumpables("...", mWriter, new String[] { "4", "8", "15", "16", "23", "42" });

        assertWithMessage("dumpAllDumpables()").that(getOutput())
                .isEqualTo("...2 dumpables:\n"
                        + "...#0: one\n"
                        + "......ONE\n"
                        + "......6 Args: 4,8,15,16,23,42,\n"
                        + "...#1: two\n"
                        + "......TWO\n"
                        + "......6 Args: 4,8,15,16,23,42,\n");
    }

    @Test
    public void testRemoveDumpable_null() {
        assertThrows(NullPointerException.class, () -> mImpl.removeDumpable(null));
    }

    @Test
    public void testARemoveDumpable_dumpableWithoutName() {
        // Need a non-null name initially otherwise it won't be added
        AtomicReference<String> name = new AtomicReference<>("A Dumpable Has No Name");
        Dumpable dumpable = new Dumpable() {

            @Override
            public String getDumpableName() {
                return name.get();
            }

            @Override
            public void dump(PrintWriter writer, String[] args) {
                throw new UnsupportedOperationException("D'OH!");
            }

        };
        assertWithMessage("addDumpable(with name)").that(mImpl.addDumpable(dumpable)).isTrue();

        name.set(null);
        assertWithMessage("removeDumpable(nameless)").that(mImpl.removeDumpable(dumpable))
                .isFalse();
    }

    @Test
    public void testRemoveDumpable_empty() {
        CustomDumpable dumpable = new CustomDumpable("The name is Bond", "James Bond!");

        assertWithMessage("removeDumpable()").that(mImpl.removeDumpable(dumpable)).isFalse();
    }

    @Test
    public void testRemoveDumpable_sameNameButDifferentDumpable() {
        CustomDumpable real = new CustomDumpable("Slim Shade", "Please stand up!");
        CustomDumpable fake = new CustomDumpable("Slim Shade", "Please stand up!");

        mImpl.addDumpable(real);

        assertWithMessage("removeDumpable(fake)").that(mImpl.removeDumpable(fake)).isFalse();
        assertWithMessage("removeDumpable(real)").that(mImpl.removeDumpable(real)).isTrue();
    }

    @Test
    public void testRemoveDumpable_existing() {
        CustomDumpable dumpable = new CustomDumpable("Homer", "D'ohmp!");

        mImpl.addDumpable(dumpable);
        mImpl.listDumpables("...", mWriter);
        assertWithMessage("listDumpables()").that(getOutput()).isEqualTo("...1 dumpables: Homer\n");

        assertWithMessage("removeDumpable()").that(mImpl.removeDumpable(dumpable)).isTrue();
        resetOutput();
        mImpl.listDumpables("...", mWriter);
        assertWithMessage("listDumpables(...)").that(getOutput()).isEqualTo("...No dumpables\n");
    }

    private String getOutput() {
        mSw.flush();
        return mSw.toString();
    }

    private void resetOutput() {
        mSw.getBuffer().setLength(0);
    }

    private static final class CustomDumpable implements Dumpable {
        public final String name;
        public final String content;

        private CustomDumpable(String name, String content) {
            this.name = name;
            this.content = content;
        }

        @Override
        public String getDumpableName() {
            return name;
        }

        @Override
        public void dump(PrintWriter writer, String[] args) {
            writer.println(content);
            if (args != null) {
                writer.printf("%d Args: ", args.length);
                for (String arg : args) {
                    writer.printf("%s,", arg);
                }
                writer.println();
            }
        }
    }
}
