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

package com.android.server.trust;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyInt;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.argThat;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static com.google.common.truth.Truth.assertThat;

import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.os.test.TestLooper;
import android.provider.Settings;
import android.service.trust.TrustAgentService;
import android.testing.TestableContext;

import androidx.test.core.app.ApplicationProvider;

import com.android.internal.widget.LockPatternUtils;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.SystemServiceManager;

import com.google.android.collect.Lists;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.Collections;

public class TrustManagerServiceTest {

    @Rule
    public MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Rule
    public final MockContext mMockContext = new MockContext(
            ApplicationProvider.getApplicationContext());

    private static final String URI_SCHEME_PACKAGE = "package";
    private static final int TEST_USER_ID = UserHandle.USER_SYSTEM;

    private final TestLooper mLooper = new TestLooper();
    private final ArrayList<ResolveInfo> mTrustAgentResolveInfoList = new ArrayList<>();
    private final LockPatternUtils mLockPatternUtils = new LockPatternUtils(mMockContext);
    private final TrustManagerService mService = new TrustManagerService(mMockContext);

    @Mock
    private PackageManager mPackageManagerMock;

    @Before
    public void setUp() {
        resetTrustAgentLockSettings();
        LocalServices.addService(SystemServiceManager.class, mock(SystemServiceManager.class));

        ArgumentMatcher<Intent> trustAgentIntentMatcher = new ArgumentMatcher<Intent>() {
            @Override
            public boolean matches(Intent argument) {
                return TrustAgentService.SERVICE_INTERFACE.equals(argument.getAction());
            }
        };
        when(mPackageManagerMock.queryIntentServicesAsUser(argThat(trustAgentIntentMatcher),
                anyInt(), anyInt())).thenReturn(mTrustAgentResolveInfoList);
        when(mPackageManagerMock.checkPermission(any(), any())).thenReturn(
                PackageManager.PERMISSION_GRANTED);
        mMockContext.setMockPackageManager(mPackageManagerMock);
    }

    @After
    public void tearDown() {
        resetTrustAgentLockSettings();
        LocalServices.removeServiceForTest(SystemServiceManager.class);
    }

    @Test
    public void firstBootCompleted_systemTrustAgentsEnabled() {
        ComponentName systemTrustAgent1 = ComponentName.unflattenFromString(
                "com.android/.SystemTrustAgent");
        ComponentName systemTrustAgent2 = ComponentName.unflattenFromString(
                "com.android/.AnotherSystemTrustAgent");
        ComponentName userTrustAgent1 = ComponentName.unflattenFromString(
                "com.user/.UserTrustAgent");
        ComponentName userTrustAgent2 = ComponentName.unflattenFromString(
                "com.user/.AnotherUserTrustAgent");
        addTrustAgent(systemTrustAgent1, /* isSystemApp= */ true);
        addTrustAgent(systemTrustAgent2, /* isSystemApp= */ true);
        addTrustAgent(userTrustAgent1, /* isSystemApp= */ false);
        addTrustAgent(userTrustAgent2, /* isSystemApp= */ false);

        bootService();

        assertThat(mLockPatternUtils.getEnabledTrustAgents(TEST_USER_ID)).containsExactly(
                systemTrustAgent1, systemTrustAgent2);
        assertThat(mLockPatternUtils.getKnownTrustAgents(TEST_USER_ID)).containsExactly(
                systemTrustAgent1, systemTrustAgent2, userTrustAgent1, userTrustAgent2);
    }

    @Test
    public void firstBootCompleted_defaultTrustAgentEnabled() {
        ComponentName systemTrustAgent = ComponentName.unflattenFromString(
                "com.android/.SystemTrustAgent");
        ComponentName defaultTrustAgent = ComponentName.unflattenFromString(
                "com.user/.DefaultTrustAgent");
        addTrustAgent(systemTrustAgent, /* isSystemApp= */ true);
        addTrustAgent(defaultTrustAgent, /* isSystemApp= */ false);
        mMockContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.string.config_defaultTrustAgent,
                defaultTrustAgent.flattenToString());

        bootService();

        assertThat(mLockPatternUtils.getEnabledTrustAgents(TEST_USER_ID)).containsExactly(
                defaultTrustAgent);
        assertThat(mLockPatternUtils.getKnownTrustAgents(TEST_USER_ID)).containsExactly(
                systemTrustAgent, defaultTrustAgent);
    }

    @Test
    public void serviceBooted_knownAgentsNotSet_enabledAgentsNotUpdated() {
        ComponentName trustAgent1 = ComponentName.unflattenFromString(
                "com.android/.SystemTrustAgent");
        ComponentName trustAgent2 = ComponentName.unflattenFromString(
                "com.android/.AnotherSystemTrustAgent");
        initializeEnabledAgents(trustAgent1);
        addTrustAgent(trustAgent1, /* isSystemApp= */ true);
        addTrustAgent(trustAgent2, /* isSystemApp= */ true);

        bootService();

        assertThat(mLockPatternUtils.getEnabledTrustAgents(TEST_USER_ID)).containsExactly(
                trustAgent1);
        assertThat(mLockPatternUtils.getKnownTrustAgents(TEST_USER_ID)).containsExactly(
                trustAgent1, trustAgent2);
    }

    @Test
    public void serviceBooted_knownAgentsSet_enabledAgentsUpdated() {
        ComponentName trustAgent1 = ComponentName.unflattenFromString(
                "com.android/.SystemTrustAgent");
        ComponentName trustAgent2 = ComponentName.unflattenFromString(
                "com.android/.AnotherSystemTrustAgent");
        initializeEnabledAgents(trustAgent1);
        initializeKnownAgents(trustAgent1);
        addTrustAgent(trustAgent1, /* isSystemApp= */ true);
        addTrustAgent(trustAgent2, /* isSystemApp= */ true);

        bootService();

        assertThat(mLockPatternUtils.getEnabledTrustAgents(TEST_USER_ID)).containsExactly(
                trustAgent1, trustAgent2);
        assertThat(mLockPatternUtils.getKnownTrustAgents(TEST_USER_ID)).containsExactly(
                trustAgent1, trustAgent2);
    }

    @Test
    public void newSystemTrustAgent_setToEnabledAndKnown() {
        bootService();
        ComponentName newAgentComponentName = ComponentName.unflattenFromString(
                "com.android/.SystemTrustAgent");
        addTrustAgent(newAgentComponentName, /* isSystemApp= */ true);

        mMockContext.sendPackageChangedBroadcast(newAgentComponentName);

        assertThat(mLockPatternUtils.getEnabledTrustAgents(TEST_USER_ID)).containsExactly(
                newAgentComponentName);
        assertThat(mLockPatternUtils.getKnownTrustAgents(TEST_USER_ID)).containsExactly(
                newAgentComponentName);
    }

    @Test
    public void newSystemTrustAgent_notEnabledWhenDefaultAgentIsSet() {
        ComponentName defaultTrustAgent = ComponentName.unflattenFromString(
                "com.user/.DefaultTrustAgent");
        addTrustAgent(defaultTrustAgent, /* isSystemApp= */ false);
        mMockContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.string.config_defaultTrustAgent,
                defaultTrustAgent.flattenToString());
        bootService();
        ComponentName newAgentComponentName = ComponentName.unflattenFromString(
                "com.android/.SystemTrustAgent");
        addTrustAgent(newAgentComponentName, /* isSystemApp= */ true);

        mMockContext.sendPackageChangedBroadcast(newAgentComponentName);

        assertThat(mLockPatternUtils.getEnabledTrustAgents(TEST_USER_ID)).containsExactly(
                defaultTrustAgent);
        assertThat(mLockPatternUtils.getKnownTrustAgents(TEST_USER_ID)).containsExactly(
                defaultTrustAgent, newAgentComponentName);
    }

    @Test
    public void newNonSystemTrustAgent_notEnabledButMarkedAsKnown() {
        bootService();
        ComponentName newAgentComponentName = ComponentName.unflattenFromString(
                "com.user/.UserTrustAgent");
        addTrustAgent(newAgentComponentName, /* isSystemApp= */ false);

        mMockContext.sendPackageChangedBroadcast(newAgentComponentName);

        assertThat(mLockPatternUtils.getEnabledTrustAgents(TEST_USER_ID)).isEmpty();
        assertThat(mLockPatternUtils.getKnownTrustAgents(TEST_USER_ID)).containsExactly(
                newAgentComponentName);
    }

    @Test
    public void existingTrustAgentChanged_notEnabled() {
        ComponentName systemTrustAgent1 = ComponentName.unflattenFromString(
                "com.android/.SystemTrustAgent");
        ComponentName systemTrustAgent2 = ComponentName.unflattenFromString(
                "com.android/.AnotherSystemTrustAgent");
        addTrustAgent(systemTrustAgent1, /* isSystemApp= */ true);
        addTrustAgent(systemTrustAgent2, /* isSystemApp= */ true);
        bootService();
        // Simulate user turning off systemTrustAgent2
        mLockPatternUtils.setEnabledTrustAgents(Collections.singletonList(systemTrustAgent1),
                TEST_USER_ID);

        mMockContext.sendPackageChangedBroadcast(systemTrustAgent2);

        assertThat(mLockPatternUtils.getEnabledTrustAgents(TEST_USER_ID)).containsExactly(
                systemTrustAgent1);
    }

    private void addTrustAgent(ComponentName agentComponentName, boolean isSystemApp) {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        if (isSystemApp) {
            applicationInfo.flags = ApplicationInfo.FLAG_SYSTEM;
        }

        ServiceInfo serviceInfo = new ServiceInfo();
        serviceInfo.packageName = agentComponentName.getPackageName();
        serviceInfo.name = agentComponentName.getClassName();
        serviceInfo.applicationInfo = applicationInfo;

        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.serviceInfo = serviceInfo;
        mTrustAgentResolveInfoList.add(resolveInfo);
    }

    private void initializeEnabledAgents(ComponentName... enabledAgents) {
        mLockPatternUtils.setEnabledTrustAgents(Lists.newArrayList(enabledAgents), TEST_USER_ID);
        Settings.Secure.putIntForUser(mMockContext.getContentResolver(),
                Settings.Secure.TRUST_AGENTS_INITIALIZED, 1, TEST_USER_ID);
    }

    private void initializeKnownAgents(ComponentName... knownAgents) {
        mLockPatternUtils.setKnownTrustAgents(Lists.newArrayList(knownAgents), TEST_USER_ID);
        Settings.Secure.putIntForUser(mMockContext.getContentResolver(),
                Settings.Secure.KNOWN_TRUST_AGENTS_INITIALIZED, 1, TEST_USER_ID);
    }

    private void bootService() {
        mService.onBootPhase(SystemService.PHASE_SYSTEM_SERVICES_READY);
        mService.onBootPhase(SystemService.PHASE_THIRD_PARTY_APPS_CAN_START);
        mService.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);
    }

    private void resetTrustAgentLockSettings() {
        mLockPatternUtils.setEnabledTrustAgents(Collections.emptyList(), TEST_USER_ID);
        mLockPatternUtils.setKnownTrustAgents(Collections.emptyList(), TEST_USER_ID);
    }

    /** A mock Context that allows the test process to send protected broadcasts. */
    private static final class MockContext extends TestableContext {

        private final ArrayList<BroadcastReceiver> mPackageChangedBroadcastReceivers =
                new ArrayList<>();

        MockContext(Context base) {
            super(base);
        }

        @Override
        @Nullable
        public Intent registerReceiverAsUser(BroadcastReceiver receiver,
                UserHandle user, IntentFilter filter, @Nullable String broadcastPermission,
                @Nullable Handler scheduler) {

            if (filter.hasAction(Intent.ACTION_PACKAGE_CHANGED)) {
                mPackageChangedBroadcastReceivers.add(receiver);
            }
            return super.registerReceiverAsUser(receiver, user, filter, broadcastPermission,
                    scheduler);
        }

        void sendPackageChangedBroadcast(ComponentName changedComponent) {
            Intent intent = new Intent(
                    Intent.ACTION_PACKAGE_CHANGED,
                    Uri.fromParts(URI_SCHEME_PACKAGE,
                            changedComponent.getPackageName(), /* fragment= */ null))
                    .putExtra(Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST,
                            new String[]{changedComponent.getClassName()})
                    .putExtra(Intent.EXTRA_USER_HANDLE, TEST_USER_ID)
                    .putExtra(Intent.EXTRA_UID, UserHandle.of(TEST_USER_ID).getUid(1234));
            for (BroadcastReceiver receiver : mPackageChangedBroadcastReceivers) {
                receiver.onReceive(this, intent);
            }
        }
    }
}
