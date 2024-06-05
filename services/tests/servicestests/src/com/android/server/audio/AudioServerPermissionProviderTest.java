/*
 * Copyright 2024 The Android Open Source Project
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
package com.android.server.audio;

import static com.android.server.audio.AudioServerPermissionProvider.MONITORED_PERMS;

import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyByte;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.media.permission.INativePermissionController;
import com.android.media.permission.UidPackageState;
import com.android.server.pm.pkg.PackageState;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

@RunWith(AndroidJUnit4.class)
@Presubmit
public final class AudioServerPermissionProviderTest {

    // Class under test
    private AudioServerPermissionProvider mPermissionProvider;

    @Rule public final MockitoRule mockito = MockitoJUnit.rule();

    @Mock public INativePermissionController mMockPc;

    @Mock public PackageState mMockPackageStateOne_10000_one;
    @Mock public PackageState mMockPackageStateTwo_10001_two;
    @Mock public PackageState mMockPackageStateThree_10000_one;
    @Mock public PackageState mMockPackageStateFour_10000_three;
    @Mock public PackageState mMockPackageStateFive_10001_four;
    @Mock public PackageState mMockPackageStateSix_10000_two;

    @Mock public BiPredicate<Integer, String> mMockPermPred;
    @Mock public Supplier<int[]> mMockUserIdSupplier;

    public List<UidPackageState> mInitPackageListExpected;

    // Argument matcher which matches that the state is equal even if the package names are out of
    // order (since they are logically a set).
    public static final class UidPackageStateMatcher implements ArgumentMatcher<UidPackageState> {
        private final int mUid;
        private final List<String> mSortedPackages;

        public UidPackageStateMatcher(int uid, List<String> packageNames) {
            mUid = uid;
            if (packageNames != null) {
                mSortedPackages = new ArrayList(packageNames);
                Collections.sort(mSortedPackages);
            } else {
                mSortedPackages = null;
            }
        }

        public UidPackageStateMatcher(UidPackageState toMatch) {
            this(toMatch.uid, toMatch.packageNames);
        }

        @Override
        public boolean matches(UidPackageState state) {
            if (state == null) return false;
            if (state.uid != mUid) return false;
            if ((state.packageNames == null) != (mSortedPackages == null)) return false;
            var copy = new ArrayList(state.packageNames);
            Collections.sort(copy);
            return mSortedPackages.equals(copy);
        }

        @Override
        public String toString() {
            return "Matcher for UidState with uid: " + mUid + ": " + mSortedPackages;
        }
    }

    public static final class PackageStateListMatcher
            implements ArgumentMatcher<List<UidPackageState>> {

        private final List<UidPackageState> mToMatch;

        public PackageStateListMatcher(List<UidPackageState> toMatch) {
            mToMatch = Objects.requireNonNull(toMatch);
        }

        @Override
        public boolean matches(List<UidPackageState> other) {
            if (other == null) return false;
            if (other.size() != mToMatch.size()) return false;
            for (int i = 0; i < mToMatch.size(); i++) {
                var matcher = new UidPackageStateMatcher(mToMatch.get(i));
                if (!matcher.matches(other.get(i))) return false;
            }
            return true;
        }

        @Override
        public String toString() {
            return "Matcher for List<UidState> with uid: " + mToMatch;
        }
    }

    @Before
    public void setup() {
        when(mMockPackageStateOne_10000_one.getAppId()).thenReturn(10000);
        when(mMockPackageStateOne_10000_one.getPackageName()).thenReturn("com.package.one");

        when(mMockPackageStateTwo_10001_two.getAppId()).thenReturn(10001);
        when(mMockPackageStateTwo_10001_two.getPackageName()).thenReturn("com.package.two");

        // Same state as the first is intentional, emulating multi-user
        when(mMockPackageStateThree_10000_one.getAppId()).thenReturn(10000);
        when(mMockPackageStateThree_10000_one.getPackageName()).thenReturn("com.package.one");

        when(mMockPackageStateFour_10000_three.getAppId()).thenReturn(10000);
        when(mMockPackageStateFour_10000_three.getPackageName()).thenReturn("com.package.three");

        when(mMockPackageStateFive_10001_four.getAppId()).thenReturn(10001);
        when(mMockPackageStateFive_10001_four.getPackageName()).thenReturn("com.package.four");

        when(mMockPackageStateSix_10000_two.getAppId()).thenReturn(10000);
        when(mMockPackageStateSix_10000_two.getPackageName()).thenReturn("com.package.two");

        when(mMockUserIdSupplier.get()).thenReturn(new int[] {0, 1});

        when(mMockPermPred.test(eq(10000), eq(MONITORED_PERMS[0]))).thenReturn(true);
        when(mMockPermPred.test(eq(110001), eq(MONITORED_PERMS[0]))).thenReturn(true);
        when(mMockPermPred.test(eq(10001), eq(MONITORED_PERMS[1]))).thenReturn(true);
        when(mMockPermPred.test(eq(110000), eq(MONITORED_PERMS[1]))).thenReturn(true);
    }

    @Test
    public void testInitialPackagePopulation() throws Exception {
        var initPackageListData =
                List.of(
                        mMockPackageStateOne_10000_one,
                        mMockPackageStateTwo_10001_two,
                        mMockPackageStateThree_10000_one,
                        mMockPackageStateFour_10000_three,
                        mMockPackageStateFive_10001_four,
                        mMockPackageStateSix_10000_two);
        var expectedPackageList =
                List.of(
                        createUidPackageState(
                                10000,
                                List.of("com.package.one", "com.package.two", "com.package.three")),
                        createUidPackageState(
                                10001, List.of("com.package.two", "com.package.four")));

        mPermissionProvider =
                new AudioServerPermissionProvider(
                        initPackageListData, mMockPermPred, mMockUserIdSupplier);
        mPermissionProvider.onServiceStart(mMockPc);
        verify(mMockPc)
                .populatePackagesForUids(argThat(new PackageStateListMatcher(expectedPackageList)));
    }

    @Test
    public void testOnModifyPackageState_whenNewUid() throws Exception {
        // 10000: one | 10001: two
        var initPackageListData =
                List.of(mMockPackageStateOne_10000_one, mMockPackageStateTwo_10001_two);
        mPermissionProvider =
                new AudioServerPermissionProvider(
                        initPackageListData, mMockPermPred, mMockUserIdSupplier);
        mPermissionProvider.onServiceStart(mMockPc);

        // new uid, including user component
        mPermissionProvider.onModifyPackageState(1_10002, "com.package.new", false /* isRemove */);

        verify(mMockPc)
                .updatePackagesForUid(
                        argThat(new UidPackageStateMatcher(10002, List.of("com.package.new"))));
        verify(mMockPc).updatePackagesForUid(any()); // exactly once
    }

    @Test
    public void testOnModifyPackageState_whenRemoveUid() throws Exception {
        // 10000: one | 10001: two
        var initPackageListData =
                List.of(mMockPackageStateOne_10000_one, mMockPackageStateTwo_10001_two);
        mPermissionProvider =
                new AudioServerPermissionProvider(
                        initPackageListData, mMockPermPred, mMockUserIdSupplier);
        mPermissionProvider.onServiceStart(mMockPc);

        // Includes user-id
        mPermissionProvider.onModifyPackageState(1_10000, "com.package.one", true /* isRemove */);

        verify(mMockPc).updatePackagesForUid(argThat(new UidPackageStateMatcher(10000, List.of())));
        verify(mMockPc).updatePackagesForUid(any()); // exactly once
    }

    @Test
    public void testOnModifyPackageState_whenUpdatedUidAddition() throws Exception {
        // 10000: one | 10001: two
        var initPackageListData =
                List.of(mMockPackageStateOne_10000_one, mMockPackageStateTwo_10001_two);
        mPermissionProvider =
                new AudioServerPermissionProvider(
                        initPackageListData, mMockPermPred, mMockUserIdSupplier);
        mPermissionProvider.onServiceStart(mMockPc);

        // Includes user-id
        mPermissionProvider.onModifyPackageState(1_10000, "com.package.new", false /* isRemove */);

        verify(mMockPc)
                .updatePackagesForUid(
                        argThat(
                                new UidPackageStateMatcher(
                                        10000, List.of("com.package.one", "com.package.new"))));
        verify(mMockPc).updatePackagesForUid(any()); // exactly once
    }

    @Test
    public void testOnModifyPackageState_whenUpdateUidRemoval() throws Exception {
        // 10000: one, two | 10001: two
        var initPackageListData =
                List.of(
                        mMockPackageStateOne_10000_one,
                        mMockPackageStateTwo_10001_two,
                        mMockPackageStateSix_10000_two);
        mPermissionProvider =
                new AudioServerPermissionProvider(
                        initPackageListData, mMockPermPred, mMockUserIdSupplier);
        mPermissionProvider.onServiceStart(mMockPc);

        // Includes user-id
        mPermissionProvider.onModifyPackageState(1_10000, "com.package.one", true /* isRemove */);

        verify(mMockPc)
                .updatePackagesForUid(
                        argThat(
                                new UidPackageStateMatcher(
                                        createUidPackageState(10000, List.of("com.package.two")))));
        verify(mMockPc).updatePackagesForUid(any()); // exactly once
    }

    @Test
    public void testOnServiceStart() throws Exception {
        // 10000: one, two | 10001: two
        var initPackageListData =
                List.of(
                        mMockPackageStateOne_10000_one,
                        mMockPackageStateTwo_10001_two,
                        mMockPackageStateSix_10000_two);
        mPermissionProvider =
                new AudioServerPermissionProvider(
                        initPackageListData, mMockPermPred, mMockUserIdSupplier);
        mPermissionProvider.onServiceStart(mMockPc);
        mPermissionProvider.onModifyPackageState(1_10000, "com.package.one", true /* isRemove */);
        verify(mMockPc)
                .updatePackagesForUid(
                        argThat(new UidPackageStateMatcher(10000, List.of("com.package.two"))));

        verify(mMockPc).updatePackagesForUid(any()); // exactly once
        mPermissionProvider.onModifyPackageState(
                1_10000, "com.package.three", false /* isRemove */);
        verify(mMockPc)
                .updatePackagesForUid(
                        argThat(
                                new UidPackageStateMatcher(
                                        10000, List.of("com.package.two", "com.package.three"))));
        verify(mMockPc, times(2)).updatePackagesForUid(any()); // exactly twice
        // state is now 10000: two, three | 10001: two

        // simulate restart of the service
        mPermissionProvider.onServiceStart(null); // should handle null
        var newMockPc = mock(INativePermissionController.class);
        mPermissionProvider.onServiceStart(newMockPc);

        var expectedPackageList =
                List.of(
                        createUidPackageState(
                                10000, List.of("com.package.two", "com.package.three")),
                        createUidPackageState(10001, List.of("com.package.two")));

        verify(newMockPc)
                .populatePackagesForUids(argThat(new PackageStateListMatcher(expectedPackageList)));

        verify(newMockPc, never()).updatePackagesForUid(any());
        // updates should still work after restart
        mPermissionProvider.onModifyPackageState(10001, "com.package.four", false /* isRemove */);
        verify(newMockPc)
                .updatePackagesForUid(
                        argThat(
                                new UidPackageStateMatcher(
                                        10001, List.of("com.package.two", "com.package.four"))));
        // exactly once
        verify(newMockPc).updatePackagesForUid(any());
    }

    @Test
    public void testPermissionsPopulated_onStart() throws Exception {
        // expected state from setUp:
        // PERM[0]: [10000, 110001]
        // PERM[1]: [10001, 110000]
        // PERM[...]: []
        var initPackageListData =
                List.of(mMockPackageStateOne_10000_one, mMockPackageStateTwo_10001_two);
        mPermissionProvider =
                new AudioServerPermissionProvider(
                        initPackageListData, mMockPermPred, mMockUserIdSupplier);

        mPermissionProvider.onServiceStart(mMockPc);
        verify(mMockPc).populatePermissionState(eq((byte) 0), aryEq(new int[] {10000, 110001}));
        verify(mMockPc).populatePermissionState(eq((byte) 1), aryEq(new int[] {10001, 110000}));
        for (int i = 2; i < MONITORED_PERMS.length; i++) {
            verify(mMockPc).populatePermissionState(eq((byte) i), aryEq(new int[] {}));
        }
        verify(mMockPc, times(MONITORED_PERMS.length)).populatePermissionState(anyByte(), any());
    }

    @Test
    public void testPermissionsPopulated_onChange() throws Exception {
        var initPackageListData =
                List.of(mMockPackageStateOne_10000_one, mMockPackageStateTwo_10001_two);
        mPermissionProvider =
                new AudioServerPermissionProvider(
                        initPackageListData, mMockPermPred, mMockUserIdSupplier);

        mPermissionProvider.onServiceStart(mMockPc);
        clearInvocations(mMockPc);
        // Ensure the provided permission state is changed
        when(mMockPermPred.test(eq(110001), eq(MONITORED_PERMS[1]))).thenReturn(true);

        mPermissionProvider.onPermissionStateChanged();
        verify(mMockPc)
                .populatePermissionState(eq((byte) 1), aryEq(new int[] {10001, 110000, 110001}));
        verify(mMockPc).populatePermissionState(anyByte(), any()); // exactly once
    }

    @Test
    public void testPermissionPopulatedDeferred_onDeadService() throws Exception {
        var initPackageListData =
                List.of(mMockPackageStateOne_10000_one, mMockPackageStateTwo_10001_two);
        mPermissionProvider =
                new AudioServerPermissionProvider(
                        initPackageListData, mMockPermPred, mMockUserIdSupplier);

        // throw on the first call to mark the service as dead
        doThrow(new RemoteException())
                .doNothing()
                .when(mMockPc)
                .populatePermissionState(anyByte(), any());
        mPermissionProvider.onServiceStart(mMockPc);
        clearInvocations(mMockPc);
        clearInvocations(mMockPermPred);

        mPermissionProvider.onPermissionStateChanged();
        verify(mMockPermPred, never()).test(any(), any());
        verify(mMockPc, never()).populatePermissionState(anyByte(), any());
        mPermissionProvider.onServiceStart(mMockPc);
        for (int i = 0; i < MONITORED_PERMS.length; i++) {
            verify(mMockPc).populatePermissionState(eq((byte) i), any());
        }
    }

    private static UidPackageState createUidPackageState(int uid, List<String> packages) {
        var res = new UidPackageState();
        res.uid = uid;
        res.packageNames = packages;
        return res;
    }
}
