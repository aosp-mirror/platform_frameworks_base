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
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyBoolean;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyInt;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.argThat;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.eq;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static com.google.common.truth.Truth.assertThat;

import android.Manifest;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.app.trust.ITrustListener;
import android.app.trust.ITrustManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.UserInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.service.trust.TrustAgentService;
import android.testing.TestableContext;
import android.view.IWindowManager;
import android.view.WindowManagerGlobal;

import androidx.test.core.app.ApplicationProvider;

import com.android.internal.widget.LockPatternUtils;
import com.android.modules.utils.testing.ExtendedMockitoRule;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.SystemServiceManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class TrustManagerServiceTest {

    @Rule
    public final ExtendedMockitoRule mExtendedMockitoRule = new ExtendedMockitoRule.Builder(this)
            .mockStatic(ServiceManager.class)
            .mockStatic(WindowManagerGlobal.class)
            .build();

    @Rule
    public final MockContext mMockContext = new MockContext(
            ApplicationProvider.getApplicationContext());

    private static final String URI_SCHEME_PACKAGE = "package";
    private static final int TEST_USER_ID = 50;

    private final ArrayList<ResolveInfo> mTrustAgentResolveInfoList = new ArrayList<>();
    private final ArrayList<ComponentName> mKnownTrustAgents = new ArrayList<>();
    private final ArrayList<ComponentName> mEnabledTrustAgents = new ArrayList<>();

    private @Mock ActivityManager mActivityManager;
    private @Mock DevicePolicyManager mDevicePolicyManager;
    private @Mock LockPatternUtils mLockPatternUtils;
    private @Mock PackageManager mPackageManager;
    private @Mock UserManager mUserManager;
    private @Mock IWindowManager mWindowManager;

    private HandlerThread mHandlerThread;
    private TrustManagerService.Injector mInjector;
    private TrustManagerService mService;
    private ITrustManager mTrustManager;

    @Before
    public void setUp() throws Exception {
        when(mActivityManager.isUserRunning(TEST_USER_ID)).thenReturn(true);

        when(mLockPatternUtils.getDevicePolicyManager()).thenReturn(mDevicePolicyManager);
        when(mLockPatternUtils.isSecure(TEST_USER_ID)).thenReturn(true);
        when(mLockPatternUtils.getKnownTrustAgents(TEST_USER_ID)).thenReturn(mKnownTrustAgents);
        when(mLockPatternUtils.getEnabledTrustAgents(TEST_USER_ID)).thenReturn(mEnabledTrustAgents);
        doAnswer(invocation -> {
            mKnownTrustAgents.clear();
            mKnownTrustAgents.addAll((Collection<ComponentName>) invocation.getArgument(0));
            return null;
        }).when(mLockPatternUtils).setKnownTrustAgents(any(), eq(TEST_USER_ID));
        doAnswer(invocation -> {
            mEnabledTrustAgents.clear();
            mEnabledTrustAgents.addAll((Collection<ComponentName>) invocation.getArgument(0));
            return null;
        }).when(mLockPatternUtils).setEnabledTrustAgents(any(), eq(TEST_USER_ID));

        ArgumentMatcher<Intent> trustAgentIntentMatcher = new ArgumentMatcher<Intent>() {
            @Override
            public boolean matches(Intent argument) {
                return TrustAgentService.SERVICE_INTERFACE.equals(argument.getAction());
            }
        };
        when(mPackageManager.queryIntentServicesAsUser(argThat(trustAgentIntentMatcher),
                anyInt(), anyInt())).thenReturn(mTrustAgentResolveInfoList);
        when(mPackageManager.checkPermission(any(), any())).thenReturn(
                PackageManager.PERMISSION_GRANTED);

        when(mUserManager.getAliveUsers()).thenReturn(
                List.of(new UserInfo(TEST_USER_ID, "user", UserInfo.FLAG_FULL)));

        when(mWindowManager.isKeyguardLocked()).thenReturn(true);

        mMockContext.addMockSystemService(ActivityManager.class, mActivityManager);
        mMockContext.setMockPackageManager(mPackageManager);
        mMockContext.addMockSystemService(UserManager.class, mUserManager);
        doReturn(mWindowManager).when(() -> WindowManagerGlobal.getWindowManagerService());
        LocalServices.addService(SystemServiceManager.class, mock(SystemServiceManager.class));

        grantPermission(Manifest.permission.ACCESS_KEYGUARD_SECURE_STORAGE);
        grantPermission(Manifest.permission.TRUST_LISTENER);

        mHandlerThread = new HandlerThread("handler");
        mHandlerThread.start();
        mInjector = new TrustManagerService.Injector(mLockPatternUtils, mHandlerThread.getLooper());
        mService = new TrustManagerService(mMockContext, mInjector);

        // Get the ITrustManager from the new TrustManagerService.
        mService.onStart();
        ArgumentCaptor<IBinder> binderArgumentCaptor = ArgumentCaptor.forClass(IBinder.class);
        verify(() -> ServiceManager.addService(eq(Context.TRUST_SERVICE),
                    binderArgumentCaptor.capture(), anyBoolean(), anyInt()));
        mTrustManager = ITrustManager.Stub.asInterface(binderArgumentCaptor.getValue());
    }

    @After
    public void tearDown() {
        LocalServices.removeServiceForTest(SystemServiceManager.class);
        mHandlerThread.quit();
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

        assertThat(mEnabledTrustAgents).containsExactly(systemTrustAgent1, systemTrustAgent2);
        assertThat(mKnownTrustAgents).containsExactly(systemTrustAgent1, systemTrustAgent2,
                    userTrustAgent1, userTrustAgent2);
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

        assertThat(mEnabledTrustAgents).containsExactly(defaultTrustAgent);
        assertThat(mKnownTrustAgents).containsExactly(systemTrustAgent, defaultTrustAgent);
    }

    @Test
    public void serviceBooted_knownAgentsNotSet_enabledAgentsNotUpdated() {
        ComponentName trustAgent1 = ComponentName.unflattenFromString(
                "com.android/.SystemTrustAgent");
        ComponentName trustAgent2 = ComponentName.unflattenFromString(
                "com.android/.AnotherSystemTrustAgent");
        mEnabledTrustAgents.add(trustAgent1);
        Settings.Secure.putIntForUser(mMockContext.getContentResolver(),
                Settings.Secure.TRUST_AGENTS_INITIALIZED, 1, TEST_USER_ID);
        addTrustAgent(trustAgent1, /* isSystemApp= */ true);
        addTrustAgent(trustAgent2, /* isSystemApp= */ true);

        bootService();

        assertThat(mEnabledTrustAgents).containsExactly(trustAgent1);
        assertThat(mKnownTrustAgents).containsExactly(trustAgent1, trustAgent2);
    }

    @Test
    public void serviceBooted_knownAgentsSet_enabledAgentsUpdated() {
        ComponentName trustAgent1 = ComponentName.unflattenFromString(
                "com.android/.SystemTrustAgent");
        ComponentName trustAgent2 = ComponentName.unflattenFromString(
                "com.android/.AnotherSystemTrustAgent");
        Settings.Secure.putIntForUser(mMockContext.getContentResolver(),
                Settings.Secure.TRUST_AGENTS_INITIALIZED, 1, TEST_USER_ID);
        Settings.Secure.putIntForUser(mMockContext.getContentResolver(),
                Settings.Secure.KNOWN_TRUST_AGENTS_INITIALIZED, 1, TEST_USER_ID);
        addTrustAgent(trustAgent1, /* isSystemApp= */ true);
        addTrustAgent(trustAgent2, /* isSystemApp= */ true);

        bootService();

        assertThat(mEnabledTrustAgents).containsExactly(trustAgent1, trustAgent2);
        assertThat(mKnownTrustAgents).containsExactly(trustAgent1, trustAgent2);
    }

    @Test
    public void newSystemTrustAgent_setToEnabledAndKnown() {
        bootService();
        ComponentName newAgentComponentName = ComponentName.unflattenFromString(
                "com.android/.SystemTrustAgent");
        addTrustAgent(newAgentComponentName, /* isSystemApp= */ true);

        mMockContext.sendPackageChangedBroadcast(newAgentComponentName);

        assertThat(mEnabledTrustAgents).containsExactly(newAgentComponentName);
        assertThat(mKnownTrustAgents).containsExactly(newAgentComponentName);
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

        assertThat(mEnabledTrustAgents).containsExactly(defaultTrustAgent);
        assertThat(mKnownTrustAgents).containsExactly(defaultTrustAgent, newAgentComponentName);
    }

    @Test
    public void newNonSystemTrustAgent_notEnabledButMarkedAsKnown() {
        bootService();
        ComponentName newAgentComponentName = ComponentName.unflattenFromString(
                "com.user/.UserTrustAgent");
        addTrustAgent(newAgentComponentName, /* isSystemApp= */ false);

        mMockContext.sendPackageChangedBroadcast(newAgentComponentName);

        assertThat(mEnabledTrustAgents).isEmpty();
        assertThat(mKnownTrustAgents).containsExactly(newAgentComponentName);
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
        mLockPatternUtils.setEnabledTrustAgents(List.of(systemTrustAgent1), TEST_USER_ID);

        mMockContext.sendPackageChangedBroadcast(systemTrustAgent2);

        assertThat(mEnabledTrustAgents).containsExactly(systemTrustAgent1);
    }

    @Test
    public void reportEnabledTrustAgentsChangedInformsListener() throws RemoteException {
        final ITrustListener trustListener = mock(ITrustListener.class);
        mTrustManager.registerTrustListener(trustListener);
        mService.waitForIdle();
        mTrustManager.reportEnabledTrustAgentsChanged(TEST_USER_ID);
        mService.waitForIdle();
        verify(trustListener).onEnabledTrustAgentsChanged(TEST_USER_ID);
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

    private void bootService() {
        mService.onBootPhase(SystemService.PHASE_SYSTEM_SERVICES_READY);
        mService.onBootPhase(SystemService.PHASE_THIRD_PARTY_APPS_CAN_START);
        mService.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);
        mMockContext.sendUserStartedBroadcast();
    }

    private void grantPermission(String permission) {
        mMockContext.getTestablePermissions().setPermission(
                permission, PackageManager.PERMISSION_GRANTED);
    }

    /** A mock Context that allows the test process to send protected broadcasts. */
    private static final class MockContext extends TestableContext {

        private final ArrayList<BroadcastReceiver> mPackageChangedBroadcastReceivers =
                new ArrayList<>();
        private final ArrayList<BroadcastReceiver> mUserStartedBroadcastReceivers =
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
            if (filter.hasAction(Intent.ACTION_USER_STARTED)) {
                mUserStartedBroadcastReceivers.add(receiver);
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

        void sendUserStartedBroadcast() {
            Intent intent = new Intent(Intent.ACTION_USER_STARTED)
                    .putExtra(Intent.EXTRA_USER_HANDLE, TEST_USER_ID);
            for (BroadcastReceiver receiver : mUserStartedBroadcastReceivers) {
                receiver.onReceive(this, intent);
            }
        }
    }
}
