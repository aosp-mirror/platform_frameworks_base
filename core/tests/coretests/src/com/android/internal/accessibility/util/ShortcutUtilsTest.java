/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.internal.accessibility.util;

import static android.provider.Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_ENABLED;
import static android.provider.Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_MAGNIFICATION_CONTROLLER;

import static com.android.internal.accessibility.common.ShortcutConstants.SERVICES_SEPARATOR;
import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType.GESTURE;
import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType.SOFTWARE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.os.Handler;
import android.os.RemoteException;
import android.provider.Settings;
import android.testing.TestableContext;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.IAccessibilityManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.accessibility.AccessibilityShortcutController;
import com.android.internal.accessibility.TestUtils;
import com.android.internal.accessibility.common.ShortcutConstants;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Unit Tests for {@link com.android.internal.accessibility.util.ShortcutUtils}
 */
@RunWith(AndroidJUnit4.class)
public class ShortcutUtilsTest {
    private static final Set<String> ONE_COMPONENT = Set.of(
            new ComponentName("pkg", "serv").flattenToString());
    private static final Set<String> TWO_COMPONENTS = Set.of(
            new ComponentName("pkg", "serv").flattenToString(),
            AccessibilityShortcutController.MAGNIFICATION_CONTROLLER_NAME);
    private static final String ALWAYS_ON_SERVICE_PACKAGE_LABEL = "always on a11y service";
    private static final String ALWAYS_ON_SERVICE_COMPONENT_NAME =
            "fake.package/fake.alwayson.service.name";

    private static final String STANDARD_SERVICE_PACKAGE_LABEL = "standard a11y service";
    private static final String STANDARD_SERVICE_COMPONENT_NAME =
            "fake.package/fake.standard.service.name";
    private static final String SERVICE_NAME_SUMMARY = "Summary";
    @Mock
    private IAccessibilityManager mAccessibilityManagerService;
    private TestableContext mContext;
    @UserIdInt
    private int mDefaultUserId;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        mContext = new TestableContext(InstrumentationRegistry.getInstrumentation().getContext());
        mDefaultUserId = mContext.getContentResolver().getUserId();

        AccessibilityManager accessibilityManager =
                new AccessibilityManager(
                        mContext, mock(Handler.class),
                        mAccessibilityManagerService, mDefaultUserId,
                        /* serviceConnect= */ true);
        mContext.addMockSystemService(Context.ACCESSIBILITY_SERVICE, accessibilityManager);
        setupFakeInstalledA11yServiceInfos();
    }

    @Test
    public void getShortcutTargets_softwareShortcutNoService_emptyResult() {
        assertThat(
                ShortcutUtils.getShortcutTargetsFromSettings(
                        mContext, SOFTWARE, mDefaultUserId)
        ).isEmpty();
    }

    @Test
    public void getShortcutTargets_volumeKeyShortcutNoService_emptyResult() {
        assertThat(
                ShortcutUtils.getShortcutTargetsFromSettings(
                        mContext, ShortcutConstants.UserShortcutType.HARDWARE,
                        mDefaultUserId)
        ).isEmpty();
    }

    @Test
    public void getShortcutTargets_gestureShortcutNoService_emptyResult() {
        assertThat(
                ShortcutUtils.getShortcutTargetsFromSettings(
                        mContext, GESTURE, mDefaultUserId)
        ).isEmpty();
    }

    @Test
    public void getShortcutTargets_softwareShortcut1Service_return1Service() {
        setupShortcutTargets(ONE_COMPONENT, Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS);
        setupShortcutTargets(TWO_COMPONENTS, Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE);

        assertThat(
                ShortcutUtils.getShortcutTargetsFromSettings(
                        mContext, SOFTWARE,
                        mDefaultUserId)
        ).containsExactlyElementsIn(ONE_COMPONENT);
    }

    @Test
    public void getShortcutTargets_volumeShortcut2Service_return2Service() {
        setupShortcutTargets(ONE_COMPONENT, Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS);
        setupShortcutTargets(TWO_COMPONENTS, Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE);

        assertThat(
                ShortcutUtils.getShortcutTargetsFromSettings(
                        mContext, ShortcutConstants.UserShortcutType.HARDWARE,
                        mDefaultUserId)
        ).containsExactlyElementsIn(TWO_COMPONENTS);
    }

    @Test
    public void getShortcutTargets_tripleTapShortcut_magnificationDisabled_emptyResult() {
        enableTripleTapShortcutForMagnification(/* enable= */ false);
        setupShortcutTargets(ONE_COMPONENT, Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS);
        setupShortcutTargets(TWO_COMPONENTS, Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE);

        assertThat(
                ShortcutUtils.getShortcutTargetsFromSettings(
                        mContext, ShortcutConstants.UserShortcutType.TRIPLETAP,
                        mDefaultUserId)
        ).isEmpty();
    }

    @Test
    public void getShortcutTargets_tripleTapShortcut_magnificationEnabled_returnMagnification() {
        setupShortcutTargets(ONE_COMPONENT, Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS);
        setupShortcutTargets(TWO_COMPONENTS, Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE);
        enableTripleTapShortcutForMagnification(/* enable= */ true);

        assertThat(
                ShortcutUtils.getShortcutTargetsFromSettings(
                        mContext, ShortcutConstants.UserShortcutType.TRIPLETAP,
                        mDefaultUserId)
        ).containsExactly(ACCESSIBILITY_SHORTCUT_TARGET_MAGNIFICATION_CONTROLLER);
    }

    @Test
    public void updateAccessibilityServiceStateIfNeeded_alwaysOnServiceOn_noShortcuts_serviceTurnedOff() {
        setupA11yServiceAndShortcutState(
                ALWAYS_ON_SERVICE_COMPONENT_NAME, /* serviceOn= */ true, /* shortcutOn= */ false);

        ShortcutUtils.updateInvisibleToggleAccessibilityServiceEnableState(
                mContext,
                Set.of(ALWAYS_ON_SERVICE_COMPONENT_NAME),
                mDefaultUserId
        );

        assertA11yServiceState(ALWAYS_ON_SERVICE_COMPONENT_NAME, /* enabled= */ false);
    }

    @Test
    public void updateAccessibilityServiceStateIfNeeded_alwaysOnServiceOnForBothUsers_noShortcutsForGuestUser_serviceTurnedOffForGuestUserOnly() {
        // setup arbitrary userId by add 10 to the default user id
        final int guestUserId = mDefaultUserId + 10;
        setupA11yServiceAndShortcutStateForUser(
                ALWAYS_ON_SERVICE_COMPONENT_NAME, /* serviceOn= */ true,
                /* shortcutOn= */ true, mDefaultUserId);
        setupA11yServiceAndShortcutStateForUser(
                ALWAYS_ON_SERVICE_COMPONENT_NAME, /* serviceOn= */ true,
                /* shortcutOn= */ false, guestUserId);

        ShortcutUtils.updateInvisibleToggleAccessibilityServiceEnableState(
                mContext,
                Set.of(ALWAYS_ON_SERVICE_COMPONENT_NAME),
                guestUserId
        );

        assertA11yServiceStateForUser(
                ALWAYS_ON_SERVICE_COMPONENT_NAME, /* enabled= */ false, guestUserId);
        assertA11yServiceStateForUser(
                ALWAYS_ON_SERVICE_COMPONENT_NAME, /* enabled= */ true, mDefaultUserId);
    }

    @Test
    public void updateAccessibilityServiceStateIfNeeded_alwaysOnServiceOn_hasShortcut_serviceKeepsOn() {
        setupA11yServiceAndShortcutState(
                ALWAYS_ON_SERVICE_COMPONENT_NAME, /* serviceOn= */ true, /* shortcutOn= */ true);

        ShortcutUtils.updateInvisibleToggleAccessibilityServiceEnableState(
                mContext,
                Set.of(ALWAYS_ON_SERVICE_COMPONENT_NAME),
                mDefaultUserId
        );

        assertA11yServiceState(ALWAYS_ON_SERVICE_COMPONENT_NAME, /* enabled= */ true);
    }

    @Test
    public void updateAccessibilityServiceStateIfNeeded_alwaysOnServiceOff_noShortcuts_serviceKeepsOff() {
        setupA11yServiceAndShortcutState(
                ALWAYS_ON_SERVICE_COMPONENT_NAME, /* serviceOn= */ false, /* shortcutOn= */ false);

        ShortcutUtils.updateInvisibleToggleAccessibilityServiceEnableState(
                mContext,
                Set.of(ALWAYS_ON_SERVICE_COMPONENT_NAME),
                mDefaultUserId
        );

        assertA11yServiceState(ALWAYS_ON_SERVICE_COMPONENT_NAME, /* enabled= */ false);
    }

    @Test
    public void updateAccessibilityServiceStateIfNeeded_alwaysOnServiceOff_hasShortcuts_serviceTurnsOn() {
        setupA11yServiceAndShortcutState(
                ALWAYS_ON_SERVICE_COMPONENT_NAME, /* serviceOn= */ false, /* shortcutOn= */ true);

        ShortcutUtils.updateInvisibleToggleAccessibilityServiceEnableState(
                mContext,
                Set.of(ALWAYS_ON_SERVICE_COMPONENT_NAME),
                mDefaultUserId
        );

        assertA11yServiceState(ALWAYS_ON_SERVICE_COMPONENT_NAME, /* enabled= */ true);
    }

    @Test
    public void updateAccessibilityServiceStateIfNeeded_standardA11yServiceOn_noShortcuts_serviceKeepsOn() {
        setupA11yServiceAndShortcutState(
                STANDARD_SERVICE_COMPONENT_NAME, /* serviceOn= */ true, /* shortcutOn= */ false);

        ShortcutUtils.updateInvisibleToggleAccessibilityServiceEnableState(
                mContext,
                Set.of(STANDARD_SERVICE_COMPONENT_NAME),
                mDefaultUserId
        );

        assertA11yServiceState(STANDARD_SERVICE_COMPONENT_NAME, /* enabled= */ true);
    }

    @Test
    public void updateAccessibilityServiceStateIfNeeded_standardA11yServiceOn_hasShortcuts_serviceKeepsOn() {
        setupA11yServiceAndShortcutState(
                STANDARD_SERVICE_COMPONENT_NAME, /* serviceOn= */ true, /* shortcutOn= */ true);

        ShortcutUtils.updateInvisibleToggleAccessibilityServiceEnableState(
                mContext,
                Set.of(STANDARD_SERVICE_COMPONENT_NAME),
                mDefaultUserId
        );

        assertA11yServiceState(STANDARD_SERVICE_COMPONENT_NAME, /* enabled= */ true);
    }

    @Test
    public void updateAccessibilityServiceStateIfNeeded_standardA11yServiceOff_noShortcuts_serviceKeepsOff() {
        setupA11yServiceAndShortcutState(
                STANDARD_SERVICE_COMPONENT_NAME, /* serviceOn= */ false, /* shortcutOn= */ false);

        ShortcutUtils.updateInvisibleToggleAccessibilityServiceEnableState(
                mContext,
                Set.of(STANDARD_SERVICE_COMPONENT_NAME),
                mDefaultUserId
        );

        assertA11yServiceState(STANDARD_SERVICE_COMPONENT_NAME, /* enabled= */ false);
    }

    @Test
    public void updateAccessibilityServiceStateIfNeeded_standardA11yServiceOff_hasShortcuts_serviceKeepsOff() {
        setupA11yServiceAndShortcutState(
                STANDARD_SERVICE_COMPONENT_NAME, /* serviceOn= */ false, /* shortcutOn= */ true);

        ShortcutUtils.updateInvisibleToggleAccessibilityServiceEnableState(
                mContext,
                Set.of(STANDARD_SERVICE_COMPONENT_NAME),
                mDefaultUserId
        );

        assertA11yServiceState(STANDARD_SERVICE_COMPONENT_NAME, /* enabled= */ false);
    }

    private void setupShortcutTargets(Set<String> components, String shortcutSettingsKey) {
        final StringJoiner stringJoiner = new StringJoiner(String.valueOf(SERVICES_SEPARATOR));
        for (String target : components) {
            stringJoiner.add(target);
        }
        Settings.Secure.putStringForUser(
                mContext.getContentResolver(), shortcutSettingsKey,
                stringJoiner.toString(),
                mDefaultUserId);
    }

    private void enableTripleTapShortcutForMagnification(boolean enable) {
        Settings.Secure.putInt(
                mContext.getContentResolver(),
                ACCESSIBILITY_DISPLAY_MAGNIFICATION_ENABLED, enable ? 1 : 0);
    }

    private void setupFakeInstalledA11yServiceInfos() throws RemoteException {
        List<AccessibilityServiceInfo> serviceInfos = List.of(
                TestUtils.createFakeServiceInfo(
                        ALWAYS_ON_SERVICE_PACKAGE_LABEL,
                        ALWAYS_ON_SERVICE_COMPONENT_NAME,
                        SERVICE_NAME_SUMMARY,
                        /* isAlwaysOnService*/ true),
                TestUtils.createFakeServiceInfo(
                        STANDARD_SERVICE_PACKAGE_LABEL,
                        STANDARD_SERVICE_COMPONENT_NAME,
                        SERVICE_NAME_SUMMARY,
                        /* isAlwaysOnService*/ false)
        );
        when(mAccessibilityManagerService.getInstalledAccessibilityServiceList(anyInt()))
                .thenReturn(new ParceledListSlice<>(serviceInfos));
    }

    private void setupA11yServiceAndShortcutState(
            String a11yServiceComponentName, boolean serviceOn, boolean shortcutOn) {
        setupA11yServiceAndShortcutStateForUser(
                a11yServiceComponentName, serviceOn, shortcutOn, mDefaultUserId);
    }

    private void setupA11yServiceAndShortcutStateForUser(
            String a11yServiceComponentName, boolean serviceOn,
            boolean shortcutOn, @UserIdInt int userId) {
        enableA11yServiceForUser(a11yServiceComponentName, serviceOn, userId);
        addShortcutForA11yServiceForUser(a11yServiceComponentName, shortcutOn, userId);
    }

    private void assertA11yServiceState(String a11yServiceComponentName, boolean enabled) {
        assertA11yServiceStateForUser(a11yServiceComponentName, enabled, mDefaultUserId);
    }

    private void assertA11yServiceStateForUser(
            String a11yServiceComponentName, boolean enabled, @UserIdInt int userId) {
        if (enabled) {
            assertThat(
                    Settings.Secure.getStringForUser(
                            mContext.getContentResolver(),
                            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                            userId)
            ).contains(a11yServiceComponentName);
        } else {
            assertThat(
                    Settings.Secure.getStringForUser(
                            mContext.getContentResolver(),
                            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                            userId)
            ).doesNotContain(a11yServiceComponentName);
        }
    }

    private void enableA11yServiceForUser(
            String a11yServiceComponentName, boolean enable, @UserIdInt int userId) {
        Settings.Secure.putStringForUser(
                mContext.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                enable ? a11yServiceComponentName : "",
                userId);
    }

    private void addShortcutForA11yServiceForUser(
            String a11yServiceComponentName, boolean add, @UserIdInt int userId) {
        Settings.Secure.putStringForUser(
                mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE,
                add ? a11yServiceComponentName : "",
                userId);
    }
}
