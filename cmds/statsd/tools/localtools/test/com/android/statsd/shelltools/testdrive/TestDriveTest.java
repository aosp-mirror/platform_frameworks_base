/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.statsd.shelltools.testdrive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Tests for {@link TestDrive}
 */
@RunWith(Parameterized.class)
public class TestDriveTest {
    /**
     * Expected results of a single iteration of the paramerized test.
     */
    static class Expect {
        public boolean success;
        public Integer[] atoms;
        public boolean onePushedAtomEvent = false;
        public String extraPackage = null;
        public String target;
        public boolean terse = false;

        static Expect success(Integer... atoms) {
            return new Expect(true, atoms,
                    TARGET);
        }
        Expect(boolean success, Integer[] atoms, String target) {
            this.success = success;
            this.atoms = atoms;
            this.target = target;
        }
        static final Expect FAILURE = new Expect(false, null, null);
        Expect onePushedAtomEvent() {
            this.onePushedAtomEvent = true;
            return this;
        }
        Expect extraPackage() {
            this.extraPackage = TestDriveTest.PACKAGE;
            return this;
        }
        Expect terse() {
            this.terse = true;
            return this;
        }
    }

    @Parameterized.Parameter(0)
    public String[] mArgs;

    @Parameterized.Parameter(1)
    public List<String> mConnectedDevices;

    @Parameterized.Parameter(2)
    public String mDefaultDevice;

    @Parameterized.Parameter(3)
    public Expect mExpect;

    private static final String TARGET = "target";
    private static final List<String> TARGET_AND_OTHER = Arrays.asList("otherDevice",
            TARGET);
    private static final List<String> TWO_OTHER_DEVICES = Arrays.asList(
            "other1", "other2");
    private static final List<String> TARGET_ONLY = Collections.singletonList(TARGET);
    private static final List<String> NOT_TARGET = Collections.singletonList("other");
    private static final List<String> NO_DEVICES = Collections.emptyList();
    private static final String PACKAGE = "extraPackage";

    @Parameterized.Parameters
    public static Collection<Object[]> data() {
        return Arrays.asList(
                new Object[]{new String[]{}, null, null,
                        Expect.FAILURE},  // Usage explanation
                new Object[]{new String[]{"244", "245"}, null, null,
                        Expect.FAILURE},  // Failure looking up connected devices
                new Object[]{new String[]{"244", "245"}, NO_DEVICES, null,
                        Expect.FAILURE},  // No connected devices
                new Object[]{new String[]{"-s", TARGET, "244", "245"}, NOT_TARGET, null,
                        Expect.FAILURE},  // Wrong device connected
                new Object[]{new String[]{"244", "245"}, TWO_OTHER_DEVICES, null,
                        Expect.FAILURE},  // Wrong devices connected
                new Object[]{new String[]{"244", "245"}, TARGET_ONLY, null,
                        Expect.success(244, 245)},  // If only one device connected, guess that one
                new Object[]{new String[]{"244", "not_an_atom"}, TARGET_ONLY, null,
                        Expect.success(244)},  // Ignore non-atoms
                new Object[]{new String[]{"not_an_atom"}, TARGET_ONLY, null,
                        Expect.FAILURE},  // Require at least one atom
                new Object[]{new String[]{"244", "245"}, TWO_OTHER_DEVICES, TARGET,
                        Expect.FAILURE},  // ANDROID_SERIAL specifies non-connected target
                new Object[]{new String[]{"244", "245"}, TARGET_AND_OTHER, TARGET,
                        Expect.success(244, 245)},  // ANDROID_SERIAL specifies a valid target
                new Object[]{new String[]{"244", "245"}, TARGET_AND_OTHER, null,
                        Expect.FAILURE},  // Two connected devices, no indication of which to use
                new Object[]{new String[]{"-one", "244", "245"}, TARGET_ONLY, null,
                        Expect.success(244, 245).onePushedAtomEvent()},
                new Object[]{new String[]{"-terse", "-one", "244", "245"}, TARGET_ONLY, null,
                        Expect.success(244, 245).onePushedAtomEvent().terse()},
                new Object[]{new String[]{"-one", "-terse", "244", "245"}, TARGET_ONLY, null,
                        Expect.success(244, 245).onePushedAtomEvent().terse()},
                new Object[]{new String[]{"-p", PACKAGE, "244", "245"}, TARGET_ONLY, null,
                        Expect.success(244, 245).extraPackage()},
                new Object[]{new String[]{"-p", PACKAGE, "-one", "244", "245"}, TARGET_ONLY, null,
                        Expect.success(244, 245).extraPackage().onePushedAtomEvent()},
                new Object[]{new String[]{"-one", "-p", PACKAGE, "244", "245"}, TARGET_ONLY, null,
                        Expect.success(244, 245).extraPackage().onePushedAtomEvent()},
                new Object[]{new String[]{"-s", TARGET, "-one", "-p", PACKAGE, "244", "245"},
                        TARGET_AND_OTHER, null,
                        Expect.success(244, 245).extraPackage().onePushedAtomEvent()},
                new Object[]{new String[]{"-one", "-s", TARGET, "-p", PACKAGE, "244", "245"},
                        TARGET_AND_OTHER, null,
                        Expect.success(244, 245).extraPackage().onePushedAtomEvent()},
                new Object[]{new String[]{"-one", "-p", PACKAGE, "-s", TARGET, "244", "245"},
                        TARGET_AND_OTHER, null,
                        Expect.success(244, 245).extraPackage().onePushedAtomEvent()},
                new Object[]{new String[]{"-terse", "-one", "-p", PACKAGE, "-s", TARGET,
                        "244", "245"},
                        TARGET_AND_OTHER, null,
                        Expect.success(244, 245).extraPackage().onePushedAtomEvent().terse()},
                new Object[]{new String[]{"-one", "-terse", "-p", PACKAGE, "-s", TARGET,
                        "244", "245"},
                        TARGET_AND_OTHER, null,
                        Expect.success(244, 245).extraPackage().onePushedAtomEvent().terse()},
                new Object[]{new String[]{"-one", "-p", PACKAGE, "-terse", "-s", TARGET,
                        "244", "245"},
                        TARGET_AND_OTHER, null,
                        Expect.success(244, 245).extraPackage().onePushedAtomEvent().terse()},
                new Object[]{new String[]{"-one", "-p", PACKAGE, "-s", TARGET, "-terse",
                        "244", "245"},
                        TARGET_AND_OTHER, null,
                        Expect.success(244, 245).extraPackage().onePushedAtomEvent().terse()}
        );
    }

    private final TestDrive.Configuration mConfiguration = new TestDrive.Configuration();
    private final TestDrive mTestDrive = new TestDrive();

    private static Integer[] collectAtoms(TestDrive.Configuration configuration) {
        Integer[] result = new Integer[configuration.mPulledAtoms.size()
                + configuration.mPushedAtoms.size()];
        int result_index = 0;
        for (Integer atom : configuration.mPushedAtoms) {
            result[result_index++] = atom;
        }
        for (Integer atom : configuration.mPulledAtoms) {
            result[result_index++] = atom;
        }
        Arrays.sort(result);
        return result;
    }

    @Test
    public void testProcessArgs() {
        boolean result = mTestDrive.processArgs(mConfiguration, mArgs, mConnectedDevices,
                mDefaultDevice);
        if (mExpect.success) {
            assertTrue(result);
            assertArrayEquals(mExpect.atoms, collectAtoms(mConfiguration));
            assertEquals(mExpect.onePushedAtomEvent, mConfiguration.mOnePushedAtomEvent);
            assertEquals(mExpect.target, mTestDrive.mDeviceSerial);
            if (mExpect.terse) {
                assertEquals(TestDrive.TerseDumper.class, mTestDrive.mDumper.getClass());
            } else {
                assertEquals(TestDrive.BasicDumper.class, mTestDrive.mDumper.getClass());
            }
        } else {
            assertFalse(result);
        }
    }
}
