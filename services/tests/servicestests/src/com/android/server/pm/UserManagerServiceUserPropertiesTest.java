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

package com.android.server.pm;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.assertThrows;

import android.content.pm.UserProperties;
import android.os.Parcel;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;
import android.util.Xml;

import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * Tests for UserManager's {@link UserProperties}.
 *
 * Additional test coverage (that actually exercises the functionality) can be found in
 * {@link UserManagerTest} and
 * {@link UserManagerServiceUserTypeTest} (for {@link UserProperties#updateFromXml}).
 *
 * <p>Run with: atest UserManagerServiceUserPropertiesTest
 */
@Presubmit
@RunWith(AndroidJUnit4.class)
@MediumTest
public class UserManagerServiceUserPropertiesTest {
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    /** Test that UserProperties can properly read the xml information that it writes. */
    @Test
    public void testWriteReadXml() throws Exception {
        mSetFlagsRule.enableFlags(android.multiuser.Flags.FLAG_SUPPORT_HIDING_PROFILES);
        final UserProperties defaultProps = new UserProperties.Builder()
                .setShowInLauncher(21)
                .setStartWithParent(false)
                .setShowInSettings(45)
                .setShowInSharingSurfaces(78)
                .setShowInQuietMode(12)
                .setInheritDevicePolicy(67)
                .setUseParentsContacts(false)
                .setCrossProfileIntentFilterAccessControl(10)
                .setCrossProfileIntentResolutionStrategy(0)
                .setMediaSharedWithParent(false)
                .setCredentialShareableWithParent(true)
                .setAuthAlwaysRequiredToDisableQuietMode(false)
                .setAllowStoppingUserWithDelayedLocking(false)
                .setDeleteAppWithParent(false)
                .setAlwaysVisible(false)
                .setCrossProfileContentSharingStrategy(0)
                .setProfileApiVisibility(34)
                .build();
        final UserProperties actualProps = new UserProperties(defaultProps);
        actualProps.setShowInLauncher(14);
        actualProps.setShowInSettings(32);
        actualProps.setShowInSharingSurfaces(46);
        actualProps.setShowInQuietMode(27);
        actualProps.setInheritDevicePolicy(51);
        actualProps.setUseParentsContacts(true);
        actualProps.setCrossProfileIntentFilterAccessControl(20);
        actualProps.setCrossProfileIntentResolutionStrategy(1);
        actualProps.setMediaSharedWithParent(true);
        actualProps.setCredentialShareableWithParent(false);
        actualProps.setAuthAlwaysRequiredToDisableQuietMode(true);
        actualProps.setAllowStoppingUserWithDelayedLocking(true);
        actualProps.setDeleteAppWithParent(true);
        actualProps.setAlwaysVisible(true);
        actualProps.setCrossProfileContentSharingStrategy(1);
        actualProps.setProfileApiVisibility(36);

        // Write the properties to xml.
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        final TypedXmlSerializer out = Xml.newFastSerializer();
        out.setOutput(baos, StandardCharsets.UTF_8.name());
        out.startDocument(null, true);
        out.startTag(null, "testTag");
        actualProps.writeToXml(out);
        out.endTag(null, "testTag");
        out.endDocument();

        // Now read those properties from xml.
        final ByteArrayInputStream input = new ByteArrayInputStream(baos.toByteArray());
        final TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(input, StandardCharsets.UTF_8.name());
        parser.nextTag();
        final UserProperties readProps = new UserProperties(parser, defaultProps);

        assertUserPropertiesEquals(actualProps, readProps);
    }

    /** Tests parcelling an object in which all properties are present. */
    @Test
    public void testParcelUnparcel() throws Exception {
        mSetFlagsRule.enableFlags(android.multiuser.Flags.FLAG_SUPPORT_HIDING_PROFILES);
        final UserProperties originalProps = new UserProperties.Builder()
                .setShowInLauncher(2145)
                .build();
        final UserProperties readProps = parcelThenUnparcel(originalProps);
        assertUserPropertiesEquals(originalProps, readProps);
    }

    /** Tests copying a UserProperties object varying permissions. */
    @Test
    public void testCopyLacksPermissions() throws Exception {
        mSetFlagsRule.enableFlags(android.multiuser.Flags.FLAG_SUPPORT_HIDING_PROFILES);
        final UserProperties defaultProps = new UserProperties.Builder()
                .setShowInLauncher(2145)
                .setStartWithParent(true)
                .setShowInSettings(3452)
                .setInheritDevicePolicy(1732)
                .setMediaSharedWithParent(true)
                .setDeleteAppWithParent(true)
                .setAuthAlwaysRequiredToDisableQuietMode(false)
                .setAllowStoppingUserWithDelayedLocking(false)
                .setAlwaysVisible(true)
                .setProfileApiVisibility(110)
                .build();
        final UserProperties orig = new UserProperties(defaultProps);
        orig.setShowInLauncher(2841);
        orig.setStartWithParent(false);
        orig.setShowInSettings(1437);
        orig.setInheritDevicePolicy(9456);
        orig.setDeleteAppWithParent(false);
        orig.setAuthAlwaysRequiredToDisableQuietMode(true);
        orig.setAllowStoppingUserWithDelayedLocking(true);
        orig.setAlwaysVisible(false);

        // Test every permission level. (Currently, it's linear so it's easy.)
        for (int permLevel = 0; permLevel < 4; permLevel++) {
            final boolean exposeAll = permLevel >= 3;
            final boolean hasManage = permLevel >= 2;
            final boolean hasQuery = permLevel >= 1;

            // Make a possibly-not-full-permission (i.e. partial) copy and check that it is correct.
            final UserProperties copy = new UserProperties(orig, exposeAll, hasManage, hasQuery);
            verifyTestCopyLacksPermissions(orig, copy, exposeAll, hasManage, hasQuery);
            if (permLevel < 1) {
                // PropertiesPresent should definitely be different since not all items were copied.
                assertThat(orig.getPropertiesPresent()).isNotEqualTo(copy.getPropertiesPresent());
            }

            // Now, just like in the SystemServer, parcel/unparcel the copy and make sure that the
            // unparcelled version behaves just like the partial copy did.
            final UserProperties readProps = parcelThenUnparcel(copy);
            verifyTestCopyLacksPermissions(orig, readProps, exposeAll, hasManage, hasQuery);
        }
    }

    /**
     * Verifies that the copy of orig has the expected properties
     * for the test {@link #testCopyLacksPermissions}.
     */
    private void verifyTestCopyLacksPermissions(
            UserProperties orig,
            UserProperties copy,
            boolean exposeAll,
            boolean hasManagePermission,
            boolean hasQueryPermission) {

        // Items requiring exposeAll.
        assertEqualGetterOrThrows(orig::getStartWithParent, copy::getStartWithParent, exposeAll);
        assertEqualGetterOrThrows(orig::getInheritDevicePolicy,
                copy::getInheritDevicePolicy, exposeAll);
        assertEqualGetterOrThrows(orig::getCrossProfileIntentFilterAccessControl,
                copy::getCrossProfileIntentFilterAccessControl, exposeAll);
        assertEqualGetterOrThrows(orig::getCrossProfileIntentResolutionStrategy,
                copy::getCrossProfileIntentResolutionStrategy, exposeAll);
        assertEqualGetterOrThrows(orig::getDeleteAppWithParent,
                copy::getDeleteAppWithParent, exposeAll);
        assertEqualGetterOrThrows(orig::getAlwaysVisible, copy::getAlwaysVisible, exposeAll);
        assertEqualGetterOrThrows(orig::getAllowStoppingUserWithDelayedLocking,
                copy::getAllowStoppingUserWithDelayedLocking, exposeAll);

        // Items requiring hasManagePermission - put them here using hasManagePermission.
        assertEqualGetterOrThrows(orig::getShowInSettings, copy::getShowInSettings,
                hasManagePermission);
        assertEqualGetterOrThrows(orig::getUseParentsContacts,
                copy::getUseParentsContacts, hasManagePermission);
        assertEqualGetterOrThrows(orig::isAuthAlwaysRequiredToDisableQuietMode,
                copy::isAuthAlwaysRequiredToDisableQuietMode, hasManagePermission);

        // Items requiring hasQueryPermission - put them here using hasQueryPermission.

        // Items with no permission requirements.
        assertEqualGetterOrThrows(orig::getShowInLauncher, copy::getShowInLauncher, true);
        assertEqualGetterOrThrows(orig::isMediaSharedWithParent,
                copy::isMediaSharedWithParent, true);
        assertEqualGetterOrThrows(orig::isCredentialShareableWithParent,
                copy::isCredentialShareableWithParent, true);
        assertEqualGetterOrThrows(orig::getCrossProfileContentSharingStrategy,
                copy::getCrossProfileContentSharingStrategy, true);
        assertEqualGetterOrThrows(orig::getProfileApiVisibility, copy::getProfileApiVisibility,
                true);
    }

    /**
     * If hasPerm, then asserts that value of actualGetter equals value of expectedGetter.
     * If !hasPerm, then asserts that actualGetter throws a SecurityException.
     */
    @SuppressWarnings("ReturnValueIgnored")
    private void assertEqualGetterOrThrows(
            Supplier expectedGetter,
            Supplier actualGetter,
            boolean hasPerm) {
        if (hasPerm) {
            assertThat(expectedGetter.get()).isEqualTo(actualGetter.get());
        } else {
            assertThrows(SecurityException.class, actualGetter::get);
        }
    }

    private UserProperties parcelThenUnparcel(UserProperties originalProps) {
        final Parcel out = Parcel.obtain();
        originalProps.writeToParcel(out, 0);
        final byte[] data = out.marshall();
        out.recycle();

        final Parcel in = Parcel.obtain();
        in.unmarshall(data, 0, data.length);
        in.setDataPosition(0);
        final UserProperties readProps = UserProperties.CREATOR.createFromParcel(in);
        in.recycle();

        return readProps;
    }

    /** Checks that two UserProperties get the same values. */
    private void assertUserPropertiesEquals(UserProperties expected, UserProperties actual) {
        assertThat(expected.getPropertiesPresent()).isEqualTo(actual.getPropertiesPresent());
        assertThat(expected.getShowInLauncher()).isEqualTo(actual.getShowInLauncher());
        assertThat(expected.getStartWithParent()).isEqualTo(actual.getStartWithParent());
        assertThat(expected.getShowInSettings()).isEqualTo(actual.getShowInSettings());
        assertThat(expected.getShowInSharingSurfaces()).isEqualTo(
                actual.getShowInSharingSurfaces());
        assertThat(expected.getShowInQuietMode())
                .isEqualTo(actual.getShowInQuietMode());
        assertThat(expected.getInheritDevicePolicy()).isEqualTo(actual.getInheritDevicePolicy());
        assertThat(expected.getUseParentsContacts()).isEqualTo(actual.getUseParentsContacts());
        assertThat(expected.getCrossProfileIntentFilterAccessControl())
                .isEqualTo(actual.getCrossProfileIntentFilterAccessControl());
        assertThat(expected.getCrossProfileIntentResolutionStrategy())
                .isEqualTo(actual.getCrossProfileIntentResolutionStrategy());
        assertThat(expected.isMediaSharedWithParent())
                .isEqualTo(actual.isMediaSharedWithParent());
        assertThat(expected.isCredentialShareableWithParent())
                .isEqualTo(actual.isCredentialShareableWithParent());
        assertThat(expected.isAuthAlwaysRequiredToDisableQuietMode())
                .isEqualTo(actual.isAuthAlwaysRequiredToDisableQuietMode());
        assertThat(expected.getAllowStoppingUserWithDelayedLocking())
                .isEqualTo(actual.getAllowStoppingUserWithDelayedLocking());
        assertThat(expected.getDeleteAppWithParent()).isEqualTo(actual.getDeleteAppWithParent());
        assertThat(expected.getAlwaysVisible()).isEqualTo(actual.getAlwaysVisible());
        assertThat(expected.getCrossProfileContentSharingStrategy())
                .isEqualTo(actual.getCrossProfileContentSharingStrategy());
        assertThat(expected.getProfileApiVisibility()).isEqualTo(actual.getProfileApiVisibility());
    }
}
