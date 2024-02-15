/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.flags;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.flags.IFeatureFlagsCallback;
import android.flags.SyncableFlag;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.List;

@Presubmit
@SmallTest
public class FeatureFlagsServiceTest {
    private static final String NS = "ns";
    private static final String NAME = "name";
    private static final String PROP_NAME = FlagOverrideStore.getPropName(NS, NAME);

    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    private FlagOverrideStore mFlagStore;
    @Mock
    private FlagsShellCommand mFlagCommand;
    @Mock
    private IFeatureFlagsCallback mIFeatureFlagsCallback;
    @Mock
    private IBinder mIFeatureFlagsCallbackAsBinder;
    @Mock
    private FeatureFlagsService.PermissionsChecker mPermissionsChecker;

    private FeatureFlagsBinder mFeatureFlagsService;

    @Before
    public void setup() {
        when(mIFeatureFlagsCallback.asBinder()).thenReturn(mIFeatureFlagsCallbackAsBinder);
        mFeatureFlagsService = new FeatureFlagsBinder(
                mFlagStore, mFlagCommand, mPermissionsChecker);
    }

    @Test
    public void testRegisterCallback() {
        mFeatureFlagsService.registerCallback(mIFeatureFlagsCallback);
        try {
            verify(mIFeatureFlagsCallbackAsBinder).linkToDeath(any(), eq(0));
        } catch (RemoteException e) {
            fail("Our mock threw a Remote Exception?");
        }
    }

    @Test
    public void testOverrideFlag_requiresWritePermission() {
        SecurityException exc = new SecurityException("not allowed");
        doThrow(exc).when(mPermissionsChecker).assertWritePermission();

        SyncableFlag f = new SyncableFlag(NS, "a", "false", false);

        try {
            mFeatureFlagsService.overrideFlag(f);
            fail("Should have thrown exception");
        } catch (SecurityException e) {
            assertThat(exc).isEqualTo(e);
        } catch (Exception e) {
            fail("should have thrown a security exception");
        }
    }

    @Test
    public void testResetFlag_requiresWritePermission() {
        SecurityException exc = new SecurityException("not allowed");
        doThrow(exc).when(mPermissionsChecker).assertWritePermission();

        SyncableFlag f = new SyncableFlag(NS, "a", "false", false);

        try {
            mFeatureFlagsService.resetFlag(f);
            fail("Should have thrown exception");
        } catch (SecurityException e) {
            assertThat(exc).isEqualTo(e);
        } catch (Exception e) {
            fail("should have thrown a security exception");
        }
    }

    @Test
    public void testSyncFlags_noOverrides() {
        List<SyncableFlag> inputFlags = List.of(
                new SyncableFlag(NS, "a", "false", false),
                new SyncableFlag(NS, "b", "true", false),
                new SyncableFlag(NS, "c", "false", false)
        );

        List<SyncableFlag> outputFlags = mFeatureFlagsService.syncFlags(inputFlags);

        assertThat(inputFlags.size()).isEqualTo(outputFlags.size());

        for (SyncableFlag inpF: inputFlags) {
            boolean found = false;
            for (SyncableFlag outF : outputFlags) {
                if (compareSyncableFlagsNames(inpF, outF)) {
                    found = true;
                    break;
                }
            }
            assertWithMessage("Failed to find input flag " + inpF + " in the output")
                    .that(found).isTrue();
        }
    }

    @Test
    public void testSyncFlags_withSomeOverrides() {
        List<SyncableFlag> inputFlags = List.of(
                new SyncableFlag(NS, "a", "false", false),
                new SyncableFlag(NS, "b", "true", false),
                new SyncableFlag(NS, "c", "false", false)
        );

        assertThat(mFlagStore).isNotNull();
        when(mFlagStore.get(NS, "c")).thenReturn("true");
        List<SyncableFlag> outputFlags = mFeatureFlagsService.syncFlags(inputFlags);

        assertThat(inputFlags.size()).isEqualTo(outputFlags.size());

        for (SyncableFlag inpF: inputFlags) {
            boolean found = false;
            for (SyncableFlag outF : outputFlags) {
                if (compareSyncableFlagsNames(inpF, outF)) {
                    found = true;

                    // Once we've found "c", do an extra check
                    if (outF.getName().equals("c")) {
                        assertWithMessage("Flag " + outF + "was not returned with an override")
                                .that(outF.getValue()).isEqualTo("true");
                    }
                    break;
                }
            }
            assertWithMessage("Failed to find input flag " + inpF + " in the output")
                    .that(found).isTrue();
        }
    }

    @Test
    public void testSyncFlags_twoCallsWithDifferentDefaults() {
        List<SyncableFlag> inputFlagsFirst = List.of(
                new SyncableFlag(NS, "a", "false", false)
        );
        List<SyncableFlag> inputFlagsSecond = List.of(
                new SyncableFlag(NS, "a", "true", false),
                new SyncableFlag(NS, "b", "false", false)
        );

        List<SyncableFlag> outputFlagsFirst = mFeatureFlagsService.syncFlags(inputFlagsFirst);
        List<SyncableFlag> outputFlagsSecond = mFeatureFlagsService.syncFlags(inputFlagsSecond);

        assertThat(inputFlagsFirst.size()).isEqualTo(outputFlagsFirst.size());
        assertThat(inputFlagsSecond.size()).isEqualTo(outputFlagsSecond.size());

        // This test only cares that the "a" flag passed in the second time came out with the
        // same value that was passed in the first time.

        boolean found = false;
        for (SyncableFlag second : outputFlagsSecond) {
            if (compareSyncableFlagsNames(second, inputFlagsFirst.get(0))) {
                found = true;
                assertThat(second.getValue()).isEqualTo(inputFlagsFirst.get(0).getValue());
                break;
            }
        }

        assertWithMessage(
                "Failed to find flag " + inputFlagsFirst.get(0) + " in the second calls output")
                .that(found).isTrue();
    }

    @Test
    public void testQueryFlags_onlyOnce() {
        List<SyncableFlag> inputFlags = List.of(
                new SyncableFlag(NS, "a", "false", false),
                new SyncableFlag(NS, "b", "true", false),
                new SyncableFlag(NS, "c", "false", false)
        );

        List<SyncableFlag> outputFlags = mFeatureFlagsService.queryFlags(inputFlags);

        assertThat(inputFlags.size()).isEqualTo(outputFlags.size());

        for (SyncableFlag inpF: inputFlags) {
            boolean found = false;
            for (SyncableFlag outF : outputFlags) {
                if (compareSyncableFlagsNames(inpF, outF)) {
                    found = true;
                    break;
                }
            }
            assertWithMessage("Failed to find input flag " + inpF + " in the output")
                    .that(found).isTrue();
        }
    }

    @Test
    public void testQueryFlags_twoCallsWithDifferentDefaults() {
        List<SyncableFlag> inputFlagsFirst = List.of(
                new SyncableFlag(NS, "a", "false", false)
        );
        List<SyncableFlag> inputFlagsSecond = List.of(
                new SyncableFlag(NS, "a", "true", false),
                new SyncableFlag(NS, "b", "false", false)
        );

        List<SyncableFlag> outputFlagsFirst = mFeatureFlagsService.queryFlags(inputFlagsFirst);
        List<SyncableFlag> outputFlagsSecond = mFeatureFlagsService.queryFlags(inputFlagsSecond);

        assertThat(inputFlagsFirst.size()).isEqualTo(outputFlagsFirst.size());
        assertThat(inputFlagsSecond.size()).isEqualTo(outputFlagsSecond.size());

        // This test only cares that the "a" flag passed in the second time came out with the
        // same value that was passed in (i.e. it wasn't cached).

        boolean found = false;
        for (SyncableFlag second : outputFlagsSecond) {
            if (compareSyncableFlagsNames(second, inputFlagsSecond.get(0))) {
                found = true;
                assertThat(second.getValue()).isEqualTo(inputFlagsSecond.get(0).getValue());
                break;
            }
        }

        assertWithMessage(
                "Failed to find flag " + inputFlagsSecond.get(0) + " in the second calls output")
                .that(found).isTrue();
    }

    @Test
    public void testOverrideFlag() {
        SyncableFlag f = new SyncableFlag(NS, "a", "false", false);

        mFeatureFlagsService.overrideFlag(f);

        verify(mFlagStore).set(f.getNamespace(), f.getName(), f.getValue());
    }

    @Test
    public void testResetFlag() {
        SyncableFlag f = new SyncableFlag(NS, "a", "false", false);

        mFeatureFlagsService.resetFlag(f);

        verify(mFlagStore).erase(f.getNamespace(), f.getName());
    }


    private static boolean compareSyncableFlagsNames(SyncableFlag a, SyncableFlag b) {
        return a.getNamespace().equals(b.getNamespace())
                && a.getName().equals(b.getName())
                && a.isDynamic() == b.isDynamic();
    }
}
