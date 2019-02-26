/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.app.timezone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Parcel;

import androidx.test.filters.LargeTest;

import org.junit.Test;

/**
 * Tests for {@link RulesState}.
 */
@LargeTest
public class RulesStateTest {

    @Test
    public void equalsAndHashCode() {
        RulesState one = new RulesState(
                "2016a", formatVersion(1, 2), false /* operationInProgress */,
                RulesState.STAGED_OPERATION_INSTALL, rulesVersion("2016a", 3),
                RulesState.DISTRO_STATUS_INSTALLED, rulesVersion("2016b", 2));
        assertEqualsContract(one, one);

        RulesState two = new RulesState(
                "2016a", formatVersion(1, 2), false /* operationInProgress */,
                RulesState.STAGED_OPERATION_INSTALL, rulesVersion("2016a", 3),
                RulesState.DISTRO_STATUS_INSTALLED, rulesVersion("2016b", 2));
        assertEqualsContract(one, two);

        RulesState differentBaseRules = new RulesState(
                "2016b", formatVersion(1, 2), false /* operationInProgress */,
                RulesState.STAGED_OPERATION_INSTALL, rulesVersion("2016a", 3),
                RulesState.DISTRO_STATUS_INSTALLED, rulesVersion("2016b", 2));
        assertFalse(one.equals(differentBaseRules));

        RulesState differentFormatVersion = new RulesState(
                "2016a", formatVersion(1, 1), false /* operationInProgress */,
                RulesState.STAGED_OPERATION_INSTALL, rulesVersion("2016a", 3),
                RulesState.DISTRO_STATUS_INSTALLED, rulesVersion("2016b", 2));
        assertFalse(one.equals(differentFormatVersion));

        RulesState differentOperationInProgress = new RulesState(
                "2016a", formatVersion(1, 1), true /* operationInProgress */,
                RulesState.STAGED_OPERATION_UNKNOWN, null /* stagedDistroRulesVersion */,
                RulesState.DISTRO_STATUS_UNKNOWN, null /* installedDistroRulesVersion */);
        assertFalse(one.equals(differentOperationInProgress));

        RulesState differentStagedOperation = new RulesState(
                "2016a", formatVersion(1, 1), false /* operationInProgress */,
                RulesState.STAGED_OPERATION_UNINSTALL, null /* stagedDistroRulesVersion */,
                RulesState.DISTRO_STATUS_INSTALLED, rulesVersion("2016b", 2));
        assertFalse(one.equals(differentStagedOperation));

        RulesState differentStagedInstallVersion = new RulesState(
                "2016a", formatVersion(1, 1), false /* operationInProgress */,
                RulesState.STAGED_OPERATION_INSTALL, rulesVersion("2016a", 4),
                RulesState.DISTRO_STATUS_INSTALLED, rulesVersion("2016b", 2));
        assertFalse(one.equals(differentStagedInstallVersion));

        RulesState differentInstalled = new RulesState(
                "2016a", formatVersion(1, 1), false /* operationInProgress */,
                RulesState.STAGED_OPERATION_INSTALL, rulesVersion("2016a", 3),
                RulesState.DISTRO_STATUS_NONE, null /* installedDistroRulesVersion */);
        assertFalse(one.equals(differentInstalled));

        RulesState differentInstalledVersion = new RulesState(
                "2016a", formatVersion(1, 1), false /* operationInProgress */,
                RulesState.STAGED_OPERATION_INSTALL, rulesVersion("2016a", 3),
                RulesState.DISTRO_STATUS_INSTALLED, rulesVersion("2016b", 3));
        assertFalse(one.equals(differentInstalledVersion));
    }

    @Test
    public void parcelable() {
        RulesState rulesState1 = new RulesState(
                "2016a", formatVersion(1, 1), false /* operationInProgress */,
                RulesState.STAGED_OPERATION_INSTALL, rulesVersion("2016b", 2),
                RulesState.DISTRO_STATUS_INSTALLED, rulesVersion("2016b", 3));
        checkParcelableRoundTrip(rulesState1);

        RulesState rulesStateWithNulls = new RulesState(
                "2016a", formatVersion(1, 1), false /* operationInProgress */,
                RulesState.STAGED_OPERATION_NONE, null /* stagedDistroRulesVersion */,
                RulesState.DISTRO_STATUS_NONE, null /* installedDistroRulesVersion */);
        checkParcelableRoundTrip(rulesStateWithNulls);

        RulesState rulesStateWithUnknowns = new RulesState(
                "2016a", formatVersion(1, 1), true /* operationInProgress */,
                RulesState.STAGED_OPERATION_UNKNOWN, null /* stagedDistroRulesVersion */,
                RulesState.DISTRO_STATUS_UNKNOWN, null /* installedDistroRulesVersion */);
        checkParcelableRoundTrip(rulesStateWithUnknowns);
    }

    private static void checkParcelableRoundTrip(RulesState rulesState) {
        Parcel parcel = Parcel.obtain();
        rulesState.writeToParcel(parcel, 0 /* flags */);
        parcel.setDataPosition(0);

        RulesState newVersion = RulesState.CREATOR.createFromParcel(parcel);

        assertEquals(rulesState, newVersion);
    }

    @Test
    public void isBaseVersionNewerThan() {
        RulesState rulesState = new RulesState(
                "2016b", formatVersion(1, 1), false /* operationInProgress */,
                RulesState.STAGED_OPERATION_NONE, null /* stagedDistroRulesVersion */,
                RulesState.DISTRO_STATUS_INSTALLED, rulesVersion("2016b", 3));
        assertTrue(rulesState.isBaseVersionNewerThan(rulesVersion("2016a", 1)));
        assertFalse(rulesState.isBaseVersionNewerThan(rulesVersion("2016b", 1)));
        assertFalse(rulesState.isBaseVersionNewerThan(rulesVersion("2016c", 1)));
    }

    private static void assertEqualsContract(RulesState one, RulesState two) {
        assertEquals(one, two);
        assertEquals(one.hashCode(), two.hashCode());
    }

    private static DistroRulesVersion rulesVersion(String rulesVersion, int revision) {
        return new DistroRulesVersion(rulesVersion, revision);
    }

    private static DistroFormatVersion formatVersion(int majorVersion, int minorVersion) {
        return new DistroFormatVersion(majorVersion, minorVersion);
    }
}
