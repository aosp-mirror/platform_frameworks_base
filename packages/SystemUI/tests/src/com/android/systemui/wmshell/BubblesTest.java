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

package com.android.systemui.wmshell;

import static android.app.Notification.FLAG_BUBBLE;
import static android.app.PendingIntent.FLAG_MUTABLE;
import static android.provider.Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED;
import static android.provider.Settings.Global.HEADS_UP_ON;
import static android.service.notification.NotificationListenerService.NOTIFICATION_CHANNEL_OR_GROUP_DELETED;
import static android.service.notification.NotificationListenerService.NOTIFICATION_CHANNEL_OR_GROUP_UPDATED;
import static android.service.notification.NotificationListenerService.REASON_APP_CANCEL;
import static android.service.notification.NotificationListenerService.REASON_GROUP_SUMMARY_CANCELED;

import static androidx.test.ext.truth.content.IntentSubject.assertThat;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.notification.Flags.FLAG_SCREENSHARE_NOTIFICATION_HIDING;
import static com.android.wm.shell.Flags.FLAG_ENABLE_BUBBLE_BAR;

import static com.google.common.truth.Truth.assertThat;

import static kotlinx.coroutines.flow.StateFlowKt.MutableStateFlow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.UserInfo;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.hardware.display.AmbientDisplayConfiguration;
import android.os.Handler;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.FlagsParameterization;
import android.service.dreams.IDreamManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.ZenModeConfig;
import android.testing.TestableLooper;
import android.util.Pair;
import android.util.SparseArray;
import android.view.Display;
import android.view.IWindowManager;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.test.filters.SmallTest;

import com.android.internal.colorextraction.ColorExtractor;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.statusbar.IStatusBarService;
import com.android.launcher3.icons.BubbleIconFactory;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryUdfpsInteractor;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FakeFeatureFlags;
import com.android.systemui.flags.SceneContainerFlagParameterizationKt;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.kosmos.KosmosJavaAdapter;
import com.android.systemui.model.SysUiState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.scene.FakeWindowRootViewComponent;
import com.android.systemui.settings.FakeDisplayTracker;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shade.NotificationShadeWindowControllerImpl;
import com.android.systemui.shade.NotificationShadeWindowView;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.shade.ShadeWindowLogger;
import com.android.systemui.shade.domain.interactor.ShadeInteractor;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.statusbar.NotificationEntryHelper;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.RankingBuilder;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.NotifPipelineFlags;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
import com.android.systemui.statusbar.notification.collection.render.NotificationVisibilityProvider;
import com.android.systemui.statusbar.notification.interruption.AvalancheProvider;
import com.android.systemui.statusbar.notification.interruption.KeyguardNotificationVisibilityProvider;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptLogger;
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionDecisionLogger;
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionDecisionProvider;
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionDecisionProviderTestUtil;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationTestHelper;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.SensitiveNotificationProtectionController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.statusbar.policy.data.repository.FakeDeviceProvisioningRepository;
import com.android.systemui.user.domain.interactor.SelectedUserInteractor;
import com.android.systemui.util.FakeEventLog;
import com.android.systemui.util.settings.FakeGlobalSettings;
import com.android.systemui.util.settings.SystemSettings;
import com.android.systemui.util.time.SystemClock;
import com.android.wm.shell.Flags;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.WindowManagerShellWrapper;
import com.android.wm.shell.bubbles.Bubble;
import com.android.wm.shell.bubbles.BubbleData;
import com.android.wm.shell.bubbles.BubbleDataRepository;
import com.android.wm.shell.bubbles.BubbleEducationController;
import com.android.wm.shell.bubbles.BubbleEntry;
import com.android.wm.shell.bubbles.BubbleExpandedViewManager;
import com.android.wm.shell.bubbles.BubbleLogger;
import com.android.wm.shell.bubbles.BubbleOverflow;
import com.android.wm.shell.bubbles.BubbleStackView;
import com.android.wm.shell.bubbles.BubbleTaskView;
import com.android.wm.shell.bubbles.BubbleViewInfoTask;
import com.android.wm.shell.bubbles.BubbleViewProvider;
import com.android.wm.shell.bubbles.Bubbles;
import com.android.wm.shell.bubbles.StackEducationView;
import com.android.wm.shell.bubbles.bar.BubbleBarLayerView;
import com.android.wm.shell.bubbles.properties.BubbleProperties;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.FloatingContentCoordinator;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.TaskStackListenerImpl;
import com.android.wm.shell.common.bubbles.BubbleBarLocation;
import com.android.wm.shell.common.bubbles.BubbleBarUpdate;
import com.android.wm.shell.draganddrop.DragAndDropController;
import com.android.wm.shell.onehanded.OneHandedController;
import com.android.wm.shell.sysui.ShellCommandHandler;
import com.android.wm.shell.sysui.ShellController;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.taskview.TaskView;
import com.android.wm.shell.taskview.TaskViewTransitions;
import com.android.wm.shell.transition.Transitions;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;

@SmallTest
@RunWith(ParameterizedAndroidJunit4.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class BubblesTest extends SysuiTestCase {
    @Mock
    private CommonNotifCollection mCommonNotifCollection;
    @Mock
    private BubblesManager.NotifCallback mNotifCallback;
    @Mock
    private WindowManager mWindowManager;
    @Mock
    private IActivityManager mActivityManager;
    @Mock
    private DozeParameters mDozeParameters;
    @Mock
    private ConfigurationController mConfigurationController;
    @Mock
    private ZenModeController mZenModeController;
    @Mock
    private ZenModeConfig mZenModeConfig;
    @Mock
    private NotificationLockscreenUserManager mLockscreenUserManager;
    @Mock
    private SysuiStatusBarStateController mStatusBarStateController;
    @Mock
    private KeyguardViewMediator mKeyguardViewMediator;
    @Mock
    private KeyguardBypassController mKeyguardBypassController;
    @Mock
    private FloatingContentCoordinator mFloatingContentCoordinator;
    @Mock
    private BubbleDataRepository mDataRepository;
    @Mock
    private NotificationShadeWindowView mNotificationShadeWindowView;
    @Mock
    private AuthController mAuthController;
    @Mock
    private SensitiveNotificationProtectionController mSensitiveNotificationProtectionController;

    private SysUiState mSysUiState;
    private boolean mSysUiStateBubblesExpanded;
    private boolean mSysUiStateBubblesManageMenuExpanded;

    @Captor
    private ArgumentCaptor<NotifCollectionListener> mNotifListenerCaptor;
    @Captor
    private ArgumentCaptor<List<Bubble>> mBubbleListCaptor;
    @Captor
    private ArgumentCaptor<IntentFilter> mFilterArgumentCaptor;
    @Captor
    private ArgumentCaptor<BroadcastReceiver> mBroadcastReceiverArgumentCaptor;
    @Captor
    private ArgumentCaptor<KeyguardStateController.Callback> mKeyguardStateControllerCallbackCaptor;
    @Captor
    private ArgumentCaptor<Runnable> mSensitiveStateChangedListener;

    private BubblesManager mBubblesManager;
    private TestableBubbleController mBubbleController;
    private NotificationShadeWindowControllerImpl mNotificationShadeWindowController;
    private NotifCollectionListener mEntryListener;
    private NotificationTestHelper mNotificationTestHelper;
    private NotificationEntry mRow;
    private NotificationEntry mRow2;
    private ExpandableNotificationRow mNonBubbleNotifRow;
    private BubbleEntry mBubbleEntry;
    private BubbleEntry mBubbleEntry2;

    private BubbleEntry mBubbleEntryUser11;
    private BubbleEntry mBubbleEntry2User11;

    private Intent mAppBubbleIntent;

    @Mock
    private ShellInit mShellInit;
    @Mock
    private ShellCommandHandler mShellCommandHandler;
    @Mock
    private ShellController mShellController;
    @Mock
    private Bubbles.BubbleExpandListener mBubbleExpandListener;
    @Mock
    private PendingIntent mDeleteIntent;
    @Mock
    private SysuiColorExtractor mColorExtractor;
    @Mock
    ColorExtractor.GradientColors mGradientColors;
    @Mock
    private ShadeController mShadeController;
    @Mock
    private NotifPipeline mNotifPipeline;
    @Mock
    private DumpManager mDumpManager;
    @Mock
    private IStatusBarService mStatusBarService;
    @Mock
    private IDreamManager mIDreamManager;
    @Mock
    private NotificationVisibilityProvider mVisibilityProvider;
    @Mock
    private LauncherApps mLauncherApps;
    @Mock
    private WindowManagerShellWrapper mWindowManagerShellWrapper;
    @Mock
    private BubbleLogger mBubbleLogger;
    @Mock
    private BubbleEducationController mEducationController;
    @Mock
    private TaskStackListenerImpl mTaskStackListener;
    @Mock
    private KeyguardStateController mKeyguardStateController;
    @Mock
    Transitions mTransitions;
    @Mock
    private Optional<OneHandedController> mOneHandedOptional;
    @Mock
    private UserManager mUserManager;
    @Mock
    private ShadeWindowLogger mShadeWindowLogger;
    @Mock
    private SelectedUserInteractor mSelectedUserInteractor;
    @Mock
    private UserTracker mUserTracker;
    @Mock
    private NotifPipelineFlags mNotifPipelineFlags;
    @Mock
    private Icon mAppBubbleIcon;
    @Mock
    private Display mDefaultDisplay;

    private final KosmosJavaAdapter mKosmos = new KosmosJavaAdapter(this);
    private ShadeInteractor mShadeInteractor;
    private ShellTaskOrganizer mShellTaskOrganizer;
    private TaskViewTransitions mTaskViewTransitions;

    private TestableBubblePositioner mPositioner;

    private BubbleData mBubbleData;

    private TestableLooper mTestableLooper;

    private final FakeDisplayTracker mDisplayTracker = new FakeDisplayTracker(mContext);
    private final FakeFeatureFlags mFeatureFlags = new FakeFeatureFlags();

    private UserHandle mUser0;

    private FakeBubbleProperties mBubbleProperties;

    @Parameters(name = "{0}")
    public static List<FlagsParameterization> getParams() {
        return SceneContainerFlagParameterizationKt.parameterizeSceneContainerFlag();
    }

    public BubblesTest(FlagsParameterization flags) {
        mSetFlagsRule.setFlagsParameterization(flags);
    }


    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            doReturn(true).when(mTransitions).isRegistered();
        }
        mTaskViewTransitions = new TaskViewTransitions(mTransitions);

        mTestableLooper = TestableLooper.get(this);

        // For the purposes of this test, just run everything synchronously
        ShellExecutor syncExecutor = new SyncExecutor();

        mUser0 = createUserHandle(/* userId= */ 0);

        when(mColorExtractor.getNeutralColors()).thenReturn(mGradientColors);
        when(mNotificationShadeWindowView.getViewTreeObserver())
                .thenReturn(mock(ViewTreeObserver.class));
        when(mWindowManager.getDefaultDisplay()).thenReturn(mDefaultDisplay);


        FakeDeviceProvisioningRepository deviceProvisioningRepository =
                mKosmos.getFakeDeviceProvisioningRepository();
        deviceProvisioningRepository.setDeviceProvisioned(true);

        DeviceEntryUdfpsInteractor deviceEntryUdfpsInteractor =
                mock(DeviceEntryUdfpsInteractor.class);
        when(deviceEntryUdfpsInteractor.isUdfpsSupported()).thenReturn(MutableStateFlow(false));

        mShadeInteractor = mKosmos.getShadeInteractor();

        mNotificationShadeWindowController = new NotificationShadeWindowControllerImpl(
                mContext,
                new FakeWindowRootViewComponent.Factory(mNotificationShadeWindowView),
                mWindowManager,
                mActivityManager,
                mDozeParameters,
                mStatusBarStateController,
                mConfigurationController,
                mKeyguardViewMediator,
                mKeyguardBypassController,
                syncExecutor,
                syncExecutor,
                mColorExtractor,
                mDumpManager,
                mKeyguardStateController,
                mAuthController,
                () -> mShadeInteractor,
                mShadeWindowLogger,
                () -> mSelectedUserInteractor,
                mUserTracker,
                mKosmos::getCommunalInteractor
        );
        mNotificationShadeWindowController.fetchWindowRootView();
        mNotificationShadeWindowController.attach();

        mAppBubbleIntent = new Intent(mContext, BubblesTestActivity.class);
        mAppBubbleIntent.setPackage(mContext.getPackageName());

        mZenModeConfig.suppressedVisualEffects = 0;
        when(mZenModeController.getConfig()).thenReturn(mZenModeConfig);

        mSysUiState = new SysUiState(mDisplayTracker, mKosmos.getSceneContainerPlugin());
        mSysUiState.addCallback(sysUiFlags -> {
            mSysUiStateBubblesManageMenuExpanded =
                    (sysUiFlags
                            & QuickStepContract.SYSUI_STATE_BUBBLES_MANAGE_MENU_EXPANDED) != 0;
            mSysUiStateBubblesExpanded =
                    (sysUiFlags & QuickStepContract.SYSUI_STATE_BUBBLES_EXPANDED) != 0;
        });

        mPositioner = new TestableBubblePositioner(mContext,
                mContext.getSystemService(WindowManager.class));
        mPositioner.setMaxBubbles(5);
        mBubbleData = new BubbleData(mContext, mBubbleLogger, mPositioner, mEducationController,
                syncExecutor);

        when(mUserManager.getProfiles(ActivityManager.getCurrentUser())).thenReturn(
                Collections.singletonList(mock(UserInfo.class)));

        final FakeGlobalSettings fakeGlobalSettings = new FakeGlobalSettings();
        fakeGlobalSettings.putInt(HEADS_UP_NOTIFICATIONS_ENABLED, HEADS_UP_ON);

        final VisualInterruptionDecisionProvider interruptionDecisionProvider =
                VisualInterruptionDecisionProviderTestUtil.INSTANCE.createProviderByFlag(
                        mock(AmbientDisplayConfiguration.class),
                        mock(BatteryController.class),
                        mock(DeviceProvisionedController.class),
                        new FakeEventLog(),
                        mock(NotifPipelineFlags.class),
                        fakeGlobalSettings,
                        mock(HeadsUpManager.class),
                        mock(KeyguardNotificationVisibilityProvider.class),
                        mock(KeyguardStateController.class),
                        mock(Handler.class),
                        mock(VisualInterruptionDecisionLogger.class),
                        mock(NotificationInterruptLogger.class),
                        mock(PowerManager.class),
                        mock(StatusBarStateController.class),
                        mock(SystemClock.class),
                        mock(UiEventLogger.class),
                        mock(UserTracker.class),
                        mock(AvalancheProvider.class),
                        mock(SystemSettings.class),
                        mock(PackageManager.class),
                        Optional.of(mock(Bubbles.class)),
                        mContext,
                        mock(NotificationManager.class)
                        );
        interruptionDecisionProvider.start();

        mShellTaskOrganizer = new ShellTaskOrganizer(mock(ShellInit.class),
                mock(ShellCommandHandler.class),
                null,
                Optional.empty(),
                Optional.empty(),
                syncExecutor);
        mBubbleProperties = new FakeBubbleProperties();
        mBubbleController = new TestableBubbleController(
                mContext,
                mShellInit,
                mShellCommandHandler,
                mShellController,
                mBubbleData,
                mFloatingContentCoordinator,
                mDataRepository,
                mStatusBarService,
                mWindowManager,
                mWindowManagerShellWrapper,
                mUserManager,
                mLauncherApps,
                mBubbleLogger,
                mTaskStackListener,
                mShellTaskOrganizer,
                mPositioner,
                mock(DisplayController.class),
                mOneHandedOptional,
                mock(DragAndDropController.class),
                syncExecutor,
                mock(Handler.class),
                mTaskViewTransitions,
                mTransitions,
                mock(SyncTransactionQueue.class),
                mock(IWindowManager.class),
                mBubbleProperties);
        mBubbleController.setExpandListener(mBubbleExpandListener);
        spyOn(mBubbleController);

        mBubblesManager = new BubblesManager(
                mContext,
                mBubbleController.asBubbles(),
                mNotificationShadeWindowController,
                mKeyguardStateController,
                mShadeController,
                mStatusBarService,
                mock(INotificationManager.class),
                mIDreamManager,
                mVisibilityProvider,
                interruptionDecisionProvider,
                mZenModeController,
                mLockscreenUserManager,
                mSensitiveNotificationProtectionController,
                mCommonNotifCollection,
                mNotifPipeline,
                mSysUiState,
                mFeatureFlags,
                mNotifPipelineFlags,
                syncExecutor,
                syncExecutor);
        mBubblesManager.addNotifCallback(mNotifCallback);

        // Need notifications for bubbles
        mNotificationTestHelper = new NotificationTestHelper(
                mContext,
                mDependency,
                TestableLooper.get(this),
                mFeatureFlags);
        mRow = mNotificationTestHelper.createBubble(mDeleteIntent);
        mRow2 = mNotificationTestHelper.createBubble(mDeleteIntent);
        mNonBubbleNotifRow = mNotificationTestHelper.createRow();
        mBubbleEntry = mBubblesManager.notifToBubbleEntry(mRow);
        mBubbleEntry2 = mBubblesManager.notifToBubbleEntry(mRow2);

        UserHandle handle = mock(UserHandle.class);
        when(handle.getIdentifier()).thenReturn(11);
        mBubbleEntryUser11 = mBubblesManager.notifToBubbleEntry(
                mNotificationTestHelper.createBubble(handle));
        mBubbleEntry2User11 = mBubblesManager.notifToBubbleEntry(
                mNotificationTestHelper.createBubble(handle));

        // Get a reference to the BubbleController's entry listener
        verify(mNotifPipeline, atLeastOnce())
                .addCollectionListener(mNotifListenerCaptor.capture());
        mEntryListener = mNotifListenerCaptor.getValue();

        // Get a reference to KeyguardStateController.Callback
        verify(mKeyguardStateController, atLeastOnce())
                .addCallback(mKeyguardStateControllerCallbackCaptor.capture());
    }

    @After
    public void tearDown() {
        ArrayList<Bubble> bubbles = new ArrayList<>(mBubbleData.getBubbles());
        for (int i = 0; i < bubbles.size(); i++) {
            mBubbleController.removeBubble(bubbles.get(i).getKey(),
                    Bubbles.DISMISS_NO_LONGER_BUBBLE);
        }
    }

    @Test
    public void bubblesHiddenWhileDreaming() throws RemoteException {
        mBubbleController.updateBubble(mBubbleEntry);
        assertTrue(mBubbleController.hasBubbles());
        assertThat(mBubbleController.getStackView().getVisibility()).isEqualTo(View.VISIBLE);

        when(mIDreamManager.isDreamingOrInPreview()).thenReturn(true); // dreaming is happening
        when(mKeyguardStateController.isShowing()).thenReturn(false); // device is unlocked
        KeyguardStateController.Callback callback =
                mKeyguardStateControllerCallbackCaptor.getValue();
        callback.onKeyguardShowingChanged();

        // Dreaming should hide bubbles
        assertThat(mBubbleController.getStackView().getVisibility()).isEqualTo(View.INVISIBLE);

        // Finish dreaming should show bubbles
        mNotificationShadeWindowController.setDreaming(false);
        when(mIDreamManager.isDreamingOrInPreview()).thenReturn(false); // dreaming finished

        // Dreaming updates come through mNotificationShadeWindowController
        mNotificationShadeWindowController.notifyStateChangedCallbacks();

        assertThat(mBubbleController.getStackView().getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void instantiateController_addInitCallback() {
        verify(mShellInit, times(1)).addInitCallback(any(), any());
    }

    @Test
    public void instantiateController_registerConfigChangeListener() {
        verify(mShellController, times(1)).addConfigurationChangeListener(any());
    }

    @Test
    public void instantiateController_registerTransitionObserver() {
        verify(mTransitions).registerObserver(any());
    }

    @Test
    public void testAddBubble() {
        mBubbleController.updateBubble(mBubbleEntry);
        assertTrue(mBubbleController.hasBubbles());
        assertSysuiStates(false /* stackExpanded */, false /* manageMenuExpanded */);
    }

    @Test
    public void testHasBubbles() {
        assertFalse(mBubbleController.hasBubbles());
        mBubbleController.updateBubble(mBubbleEntry);
        assertTrue(mBubbleController.hasBubbles());
        assertSysuiStates(false /* stackExpanded */, false /* manageMenuExpanded */);
    }

    @Test
    public void testRemoveBubble() {
        mBubbleController.updateBubble(mBubbleEntry);
        assertNotNull(mBubbleData.getBubbleInStackWithKey(mRow.getKey()));
        assertTrue(mBubbleController.hasBubbles());
        verify(mNotifCallback, times(1)).invalidateNotifications(anyString());

        mBubbleController.removeBubble(
                mRow.getKey(), Bubbles.DISMISS_USER_GESTURE);
        assertNull(mBubbleData.getBubbleInStackWithKey(mRow.getKey()));
        verify(mNotifCallback, times(2)).invalidateNotifications(anyString());

        assertSysuiStates(false /* stackExpanded */, false /* manageMenuExpanded */);
    }

    @Test
    public void testRemoveBubble_withDismissedNotif_inOverflow() {
        mEntryListener.onEntryAdded(mRow);
        mBubbleController.updateBubble(mBubbleEntry);

        assertTrue(mBubbleController.hasBubbles());
        assertBubbleNotificationNotSuppressedFromShade(mBubbleEntry);

        // Make it look like dismissed notif
        mBubbleData.getBubbleInStackWithKey(mRow.getKey()).setSuppressNotification(true);

        // Now remove the bubble
        mBubbleController.removeBubble(
                mRow.getKey(), Bubbles.DISMISS_USER_GESTURE);
        assertTrue(mBubbleData.hasOverflowBubbleWithKey(mRow.getKey()));

        // We don't remove the notification since the bubble is still in overflow.
        verify(mNotifCallback, never()).removeNotification(eq(mRow), any(), anyInt());
        assertFalse(mBubbleController.hasBubbles());
    }

    @Test
    public void testRemoveBubble_withDismissedNotif_notInOverflow() {
        mEntryListener.onEntryAdded(mRow);
        mBubbleController.updateBubble(mBubbleEntry);
        when(mCommonNotifCollection.getEntry(mRow.getKey())).thenReturn(mRow);

        assertTrue(mBubbleController.hasBubbles());
        assertBubbleNotificationNotSuppressedFromShade(mBubbleEntry);

        // Make it look like dismissed notif
        mBubbleData.getBubbleInStackWithKey(mRow.getKey()).setSuppressNotification(true);

        // Now remove the bubble
        mBubbleController.removeBubble(
                mRow.getKey(), Bubbles.DISMISS_NOTIF_CANCEL);
        assertFalse(mBubbleData.hasOverflowBubbleWithKey(mRow.getKey()));

        // Since the notif is dismissed and not in overflow, once the bubble is removed,
        // removeNotification gets called to really remove the notif
        verify(mNotifCallback, times(1)).removeNotification(eq(mRow),
                any(), anyInt());
        assertFalse(mBubbleController.hasBubbles());
    }

    @Test
    public void testDismissStack() {
        mBubbleController.updateBubble(mBubbleEntry);
        verify(mNotifCallback, times(1)).invalidateNotifications(anyString());
        assertNotNull(mBubbleData.getBubbleInStackWithKey(mRow.getKey()));
        mBubbleController.updateBubble(mBubbleEntry2);
        verify(mNotifCallback, times(2)).invalidateNotifications(anyString());
        assertNotNull(mBubbleData.getBubbleInStackWithKey(mRow2.getKey()));
        assertTrue(mBubbleController.hasBubbles());

        mBubbleData.dismissAll(Bubbles.DISMISS_USER_GESTURE);
        verify(mNotifCallback, times(3)).invalidateNotifications(anyString());
        assertNull(mBubbleData.getBubbleInStackWithKey(mRow.getKey()));
        assertNull(mBubbleData.getBubbleInStackWithKey(mRow2.getKey()));

        assertSysuiStates(false /* stackExpanded */, false /* manageMenuExpanded */);
    }

    @Test
    public void testExpandCollapseStack() {
        assertStackCollapsed();

        // Mark it as a bubble and add it explicitly
        mEntryListener.onEntryAdded(mRow);
        mBubbleController.updateBubble(mBubbleEntry);

        // We should have bubbles & their notifs should not be suppressed
        assertTrue(mBubbleController.hasBubbles());
        assertBubbleNotificationNotSuppressedFromShade(mBubbleEntry);

        // Expand the stack
        mBubbleData.setExpanded(true);
        assertStackExpanded();
        verify(mBubbleExpandListener).onBubbleExpandChanged(true, mRow.getKey());
        assertSysuiStates(true /* stackExpanded */, false /* manageMenuExpanded */);

        // Make sure the notif is suppressed
        assertBubbleNotificationSuppressedFromShade(mBubbleEntry);

        // Collapse
        mBubbleController.collapseStack();
        verify(mBubbleExpandListener).onBubbleExpandChanged(false, mRow.getKey());
        assertStackCollapsed();
        assertSysuiStates(false /* stackExpanded */, false /* manageMenuExpanded */);
    }

    @Test
    public void testCollapseAfterChangingExpandedBubble() {
        // Mark it as a bubble and add it explicitly
        mEntryListener.onEntryAdded(mRow);
        mEntryListener.onEntryAdded(mRow2);
        mBubbleController.updateBubble(mBubbleEntry);
        mBubbleController.updateBubble(mBubbleEntry2);

        // We should have bubbles & their notifs should not be suppressed
        assertTrue(mBubbleController.hasBubbles());
        assertBubbleNotificationNotSuppressedFromShade(mBubbleEntry);
        assertBubbleNotificationNotSuppressedFromShade(mBubbleEntry2);

        // Expand
        BubbleStackView stackView = mBubbleController.getStackView();
        mBubbleData.setExpanded(true);
        assertStackExpanded();
        verify(mBubbleExpandListener, atLeastOnce()).onBubbleExpandChanged(
                true, mRow2.getKey());
        assertSysuiStates(true /* stackExpanded */, false /* manageMenuExpanded */);

        // Last added is the one that is expanded
        assertEquals(mRow2.getKey(), mBubbleData.getSelectedBubble().getKey());
        assertBubbleNotificationSuppressedFromShade(mBubbleEntry2);

        // Switch which bubble is expanded
        mBubbleData.setSelectedBubble(mBubbleData.getBubbleInStackWithKey(
                mRow.getKey()));
        mBubbleData.setExpanded(true);
        assertEquals(mRow.getKey(), mBubbleData.getBubbleInStackWithKey(
                stackView.getExpandedBubble().getKey()).getKey());
        assertBubbleNotificationSuppressedFromShade(mBubbleEntry);

        // collapse for previous bubble
        verify(mBubbleExpandListener, atLeastOnce()).onBubbleExpandChanged(
                false, mRow2.getKey());
        // expand for selected bubble
        verify(mBubbleExpandListener, atLeastOnce()).onBubbleExpandChanged(
                true, mRow.getKey());


        // Collapse
        mBubbleController.collapseStack();
        assertStackCollapsed();
        assertSysuiStates(false /* stackExpanded */, false /* manageMenuExpanded */);
    }

    @Test
    public void testExpansionRemovesShowInShadeAndDot() {
        // Mark it as a bubble and add it explicitly
        mEntryListener.onEntryAdded(mRow);
        mBubbleController.updateBubble(mBubbleEntry);

        // We should have bubbles & their notifs should not be suppressed
        assertTrue(mBubbleController.hasBubbles());
        assertBubbleNotificationNotSuppressedFromShade(mBubbleEntry);

        mTestableLooper.processAllMessages();
        assertTrue(mBubbleData.getBubbleInStackWithKey(mRow.getKey()).showDot());

        // Expand
        mBubbleData.setExpanded(true);
        assertStackExpanded();
        verify(mBubbleExpandListener).onBubbleExpandChanged(true, mRow.getKey());
        assertSysuiStates(true /* stackExpanded */, false /* manageMenuExpanded */);

        // Notif is suppressed after expansion
        assertBubbleNotificationSuppressedFromShade(mBubbleEntry);
        // Notif shouldn't show dot after expansion
        assertFalse(mBubbleData.getBubbleInStackWithKey(mRow.getKey()).showDot());
    }

    @Test
    public void testUpdateWhileExpanded_DoesntChangeShowInShadeAndDot() {
        // Mark it as a bubble and add it explicitly
        mEntryListener.onEntryAdded(mRow);
        mBubbleController.updateBubble(mBubbleEntry);

        // We should have bubbles & their notifs should not be suppressed
        assertTrue(mBubbleController.hasBubbles());
        assertBubbleNotificationNotSuppressedFromShade(mBubbleEntry);

        mTestableLooper.processAllMessages();
        assertTrue(mBubbleData.getBubbleInStackWithKey(mRow.getKey()).showDot());

        // Expand
        mBubbleData.setExpanded(true);
        assertStackExpanded();
        verify(mBubbleExpandListener).onBubbleExpandChanged(true, mRow.getKey());
        assertSysuiStates(true /* stackExpanded */, false /* manageMenuExpanded */);

        // Notif is suppressed after expansion
        assertBubbleNotificationSuppressedFromShade(mBubbleEntry);
        // Notif shouldn't show dot after expansion
        assertFalse(mBubbleData.getBubbleInStackWithKey(mRow.getKey()).showDot());

        // Send update
        mEntryListener.onEntryUpdated(mRow, /* fromSystem= */ true);

        // Nothing should have changed
        // Notif is suppressed after expansion
        assertBubbleNotificationSuppressedFromShade(mBubbleEntry);
        // Notif shouldn't show dot after expansion
        assertFalse(mBubbleData.getBubbleInStackWithKey(mRow.getKey()).showDot());
    }

    @Test
    public void testRemoveLastExpanded_collapses() {
        // Mark it as a bubble and add it explicitly
        mEntryListener.onEntryAdded(mRow);
        mEntryListener.onEntryAdded(mRow2);
        mBubbleController.updateBubble(mBubbleEntry);
        mBubbleController.updateBubble(mBubbleEntry2);

        // Expand
        BubbleStackView stackView = mBubbleController.getStackView();
        mBubbleData.setExpanded(true);

        assertSysuiStates(true /* stackExpanded */, false /* manageMenuExpanded */);

        assertStackExpanded();
        verify(mBubbleExpandListener).onBubbleExpandChanged(true, mRow2.getKey());

        // Last added is the one that is expanded
        assertEquals(mRow2.getKey(), mBubbleData.getBubbleInStackWithKey(
                stackView.getExpandedBubble().getKey()).getKey());
        assertBubbleNotificationSuppressedFromShade(mBubbleEntry2);

        // Dismiss currently expanded
        mBubbleController.removeBubble(
                mBubbleData.getBubbleInStackWithKey(
                        stackView.getExpandedBubble().getKey()).getKey(),
                Bubbles.DISMISS_USER_GESTURE);
        verify(mBubbleExpandListener).onBubbleExpandChanged(false, mRow2.getKey());

        // Make sure first bubble is selected
        assertEquals(mRow.getKey(), mBubbleData.getBubbleInStackWithKey(
                stackView.getExpandedBubble().getKey()).getKey());
        verify(mBubbleExpandListener).onBubbleExpandChanged(true, mRow.getKey());

        // Dismiss that one
        mBubbleController.removeBubble(
                mBubbleData.getBubbleInStackWithKey(
                        stackView.getExpandedBubble().getKey()).getKey(),
                Bubbles.DISMISS_USER_GESTURE);

        // We should be collapsed
        verify(mBubbleExpandListener).onBubbleExpandChanged(false, mRow.getKey());
        assertFalse(mBubbleController.hasBubbles());
        assertSysuiStates(false /* stackExpanded */, false /* manageMenuExpanded */);
    }

    @Test
    public void testRemoveLastExpandedEmptyOverflow_collapses() {
        // Mark it as a bubble and add it explicitly
        mEntryListener.onEntryAdded(mRow);
        mBubbleController.updateBubble(mBubbleEntry);

        // Expand
        BubbleStackView stackView = mBubbleController.getStackView();
        mBubbleData.setExpanded(true);

        assertSysuiStates(true /* stackExpanded */, false /* manageMenuExpanded */);
        assertStackExpanded();
        verify(mBubbleExpandListener).onBubbleExpandChanged(true, mRow.getKey());

        // Block the bubble so it won't be in the overflow
        mBubbleController.removeBubble(
                mBubbleData.getBubbleInStackWithKey(
                        stackView.getExpandedBubble().getKey()).getKey(),
                Bubbles.DISMISS_BLOCKED);

        verify(mBubbleExpandListener).onBubbleExpandChanged(false, mRow.getKey());

        // We should be collapsed
        verify(mBubbleExpandListener).onBubbleExpandChanged(false, mRow.getKey());
        assertFalse(mBubbleController.hasBubbles());
        assertSysuiStates(false /* stackExpanded */, false /* manageMenuExpanded */);
    }


    @Test
    public void testAutoExpand_fails_noFlag() {
        assertStackCollapsed();
        setMetadataFlags(mRow,
                Notification.BubbleMetadata.FLAG_AUTO_EXPAND_BUBBLE, false /* enableFlag */);

        // Add the auto expand bubble
        mEntryListener.onEntryAdded(mRow);
        mBubbleController.updateBubble(mBubbleEntry);

        // Expansion shouldn't change
        verify(mBubbleExpandListener, never()).onBubbleExpandChanged(false /* expanded */,
                mRow.getKey());
        assertStackCollapsed();
        assertSysuiStates(false /* stackExpanded */, false /* manageMenuExpanded */);
    }

    @Test
    public void testAutoExpand_succeeds_withFlag() {
        setMetadataFlags(mRow,
                Notification.BubbleMetadata.FLAG_AUTO_EXPAND_BUBBLE, true /* enableFlag */);

        // Add the auto expand bubble
        mEntryListener.onEntryAdded(mRow);
        mBubbleController.updateBubble(mBubbleEntry);

        // Expansion should change
        verify(mBubbleExpandListener).onBubbleExpandChanged(true /* expanded */,
                mRow.getKey());
        assertStackExpanded();
        assertSysuiStates(true /* stackExpanded */, false /* manageMenuExpanded */);
    }

    @Test
    public void testSuppressNotif_onInitialNotif() {
        setMetadataFlags(mRow,
                Notification.BubbleMetadata.FLAG_SUPPRESS_NOTIFICATION, true /* enableFlag */);

        // Add the suppress notif bubble
        mEntryListener.onEntryAdded(mRow);
        mBubbleController.updateBubble(mBubbleEntry);

        // Notif should be suppressed because we were foreground
        assertBubbleNotificationSuppressedFromShade(mBubbleEntry);
        // Dot + flyout is hidden because notif is suppressed
        assertFalse(mBubbleData.getBubbleInStackWithKey(mRow.getKey()).showDot());
        assertFalse(mBubbleData.getBubbleInStackWithKey(mRow.getKey()).showFlyout());
        assertSysuiStates(false /* stackExpanded */, false /* manageMenuExpanded */);
    }

    @Test
    public void testSuppressNotif_onUpdateNotif() {
        mBubbleController.updateBubble(mBubbleEntry);

        // Should not be suppressed
        assertBubbleNotificationNotSuppressedFromShade(mBubbleEntry);
        // Should show dot
        assertTrue(mBubbleData.getBubbleInStackWithKey(mRow.getKey()).showDot());

        // Update to suppress notif
        setMetadataFlags(mRow,
                Notification.BubbleMetadata.FLAG_SUPPRESS_NOTIFICATION, true /* enableFlag */);
        mBubbleController.updateBubble(mBubbleEntry);

        // Notif should be suppressed
        assertBubbleNotificationSuppressedFromShade(mBubbleEntry);
        // Dot + flyout is hidden because notif is suppressed
        assertFalse(mBubbleData.getBubbleInStackWithKey(mRow.getKey()).showDot());
        assertFalse(mBubbleData.getBubbleInStackWithKey(mRow.getKey()).showFlyout());
        assertSysuiStates(false /* stackExpanded */, false /* manageMenuExpanded */);
    }

    @Test
    public void testMarkNewNotificationAsShowInShade() {
        mEntryListener.onEntryAdded(mRow);
        assertBubbleNotificationNotSuppressedFromShade(mBubbleEntry);

        mTestableLooper.processAllMessages();
        assertTrue(mBubbleData.getBubbleInStackWithKey(mRow.getKey()).showDot());
    }

    @Test
    public void testAddNotif_notBubble() {
        mEntryListener.onEntryAdded(mNonBubbleNotifRow.getEntry());
        mEntryListener.onEntryUpdated(mNonBubbleNotifRow.getEntry(), /* fromSystem= */ true);

        assertThat(mBubbleController.hasBubbles()).isFalse();
    }

    @Test
    public void testDeleteIntent_removeBubble_aged() throws PendingIntent.CanceledException {
        mBubbleController.updateBubble(mBubbleEntry);
        mBubbleController.removeBubble(mRow.getKey(), Bubbles.DISMISS_AGED);
        verify(mDeleteIntent, never()).send();
    }

    @Test
    public void testDeleteIntent_removeBubble_user() throws PendingIntent.CanceledException {
        mBubbleController.updateBubble(mBubbleEntry);
        mBubbleController.removeBubble(
                mRow.getKey(), Bubbles.DISMISS_USER_GESTURE);
        verify(mDeleteIntent, times(1)).send();
    }

    @Test
    public void testDeleteIntent_dismissStack() throws PendingIntent.CanceledException {
        mBubbleController.updateBubble(mBubbleEntry);
        mBubbleController.updateBubble(mBubbleEntry2);
        mBubbleData.dismissAll(Bubbles.DISMISS_USER_GESTURE);
        verify(mDeleteIntent, times(2)).send();
    }

    @Test
    public void testRemoveBubble_noLongerBubbleAfterUpdate()
            throws PendingIntent.CanceledException {
        mBubbleController.updateBubble(mBubbleEntry);
        assertTrue(mBubbleController.hasBubbles());

        mRow.getSbn().getNotification().flags &= ~FLAG_BUBBLE;
        NotificationListenerService.Ranking ranking = new RankingBuilder(
                mRow.getRanking()).setCanBubble(false).build();
        mRow.setRanking(ranking);
        mEntryListener.onEntryUpdated(mRow, /* fromSystem= */ true);

        assertFalse(mBubbleController.hasBubbles());
        verify(mDeleteIntent, never()).send();
    }

    @Test
    public void testRemoveBubble_entryListenerRemove() {
        mEntryListener.onEntryAdded(mRow);
        mBubbleController.updateBubble(mBubbleEntry);

        assertTrue(mBubbleController.hasBubbles());

        // Removes the notification
        mEntryListener.onEntryRemoved(mRow, REASON_APP_CANCEL);
        assertFalse(mBubbleController.hasBubbles());
    }

    @Test
    public void removeBubble_intercepted() {
        mEntryListener.onEntryAdded(mRow);
        mBubbleController.updateBubble(mBubbleEntry);

        assertTrue(mBubbleController.hasBubbles());
        assertBubbleNotificationNotSuppressedFromShade(mBubbleEntry);

        boolean intercepted = mBubblesManager.handleDismissalInterception(mRow);

        // Intercept!
        assertTrue(intercepted);
        // Should update show in shade state
        assertBubbleNotificationSuppressedFromShade(mBubbleEntry);
    }

    @Test
    public void removeBubble_dismissIntoOverflow_intercepted() {
        mEntryListener.onEntryAdded(mRow);
        mBubbleController.updateBubble(mBubbleEntry);

        assertTrue(mBubbleController.hasBubbles());
        assertBubbleNotificationNotSuppressedFromShade(mBubbleEntry);

        // Dismiss the bubble
        mBubbleController.removeBubble(mRow.getKey(), Bubbles.DISMISS_USER_GESTURE);
        assertFalse(mBubbleController.hasBubbles());

        // Dismiss the notification
        boolean intercepted = mBubblesManager.handleDismissalInterception(mRow);

        // Intercept dismissal since bubble is going into overflow
        assertTrue(intercepted);
    }

    @Test
    public void removeBubble_notIntercepted() {
        mEntryListener.onEntryAdded(mRow);
        mBubbleController.updateBubble(mBubbleEntry);

        assertTrue(mBubbleController.hasBubbles());
        assertBubbleNotificationNotSuppressedFromShade(mBubbleEntry);

        // Dismiss the bubble
        mBubbleController.removeBubble(mRow.getKey(), Bubbles.DISMISS_NOTIF_CANCEL);
        assertFalse(mBubbleController.hasBubbles());

        // Dismiss the notification
        boolean intercepted = mBubblesManager.handleDismissalInterception(mRow);

        // Not a bubble anymore so we don't intercept dismissal.
        assertFalse(intercepted);
    }

    @Test
    public void testNotifyShadeSuppressionChange_notificationDismiss() {
        mEntryListener.onEntryAdded(mRow);

        assertTrue(mBubbleController.hasBubbles());
        assertBubbleNotificationNotSuppressedFromShade(mBubbleEntry);

        mBubblesManager.handleDismissalInterception(mRow);

        // Should update show in shade state
        assertBubbleNotificationSuppressedFromShade(mBubbleEntry);

        // Should notify delegate that shade state changed
        verify(mBubbleController).onBubbleMetadataFlagChanged(
                mBubbleData.getBubbleInStackWithKey(mRow.getKey()));
    }

    @Test
    public void testNotifyShadeSuppressionChange_bubbleExpanded() {
        mEntryListener.onEntryAdded(mRow);

        assertTrue(mBubbleController.hasBubbles());
        assertBubbleNotificationNotSuppressedFromShade(mBubbleEntry);

        mBubbleData.setExpanded(true);

        // Once a bubble is expanded the notif is suppressed
        assertBubbleNotificationSuppressedFromShade(mBubbleEntry);

        // Should notify delegate that shade state changed
        verify(mBubbleController).onBubbleMetadataFlagChanged(
                mBubbleData.getBubbleInStackWithKey(mRow.getKey()));
    }

    @Test
    public void testBubbleSummaryDismissal_suppressesSummaryAndBubbleFromShade() throws Exception {
        // GIVEN a group summary with a bubble child
        ExpandableNotificationRow groupSummary = mNotificationTestHelper.createGroup(0);
        ExpandableNotificationRow groupedBubble = mNotificationTestHelper.createBubbleInGroup();
        mEntryListener.onEntryAdded(groupedBubble.getEntry());
        when(mCommonNotifCollection.getEntry(groupedBubble.getEntry().getKey()))
                .thenReturn(groupedBubble.getEntry());
        groupSummary.addChildNotification(groupedBubble);
        assertTrue(mBubbleData.hasBubbleInStackWithKey(groupedBubble.getEntry().getKey()));

        // WHEN the summary is dismissed
        mBubblesManager.handleDismissalInterception(groupSummary.getEntry());

        // THEN the summary and bubbled child are suppressed from the shade
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                groupedBubble.getEntry().getKey(),
                groupedBubble.getEntry().getSbn().getGroupKey()));
        assertTrue(mBubbleController.getImplCachedState().isBubbleNotificationSuppressedFromShade(
                groupedBubble.getEntry().getKey(),
                groupedBubble.getEntry().getSbn().getGroupKey()));
        assertTrue(mBubbleData.isSummarySuppressed(groupSummary.getEntry().getSbn().getGroupKey()));
    }

    @Test
    public void testAppRemovesSummary_removesAllBubbleChildren() throws Exception {
        // GIVEN a group summary with a bubble child
        ExpandableNotificationRow groupSummary = mNotificationTestHelper.createGroup(0);
        ExpandableNotificationRow groupedBubble = mNotificationTestHelper.createBubbleInGroup();
        mEntryListener.onEntryAdded(groupedBubble.getEntry());
        when(mCommonNotifCollection.getEntry(groupedBubble.getEntry().getKey()))
                .thenReturn(groupedBubble.getEntry());
        groupSummary.addChildNotification(groupedBubble);
        assertTrue(mBubbleData.hasBubbleInStackWithKey(groupedBubble.getEntry().getKey()));

        // GIVEN the summary is dismissed
        mBubblesManager.handleDismissalInterception(groupSummary.getEntry());

        // WHEN the summary is cancelled by the app
        mEntryListener.onEntryRemoved(groupSummary.getEntry(), REASON_APP_CANCEL);

        // THEN the summary and its children are removed from bubble data
        assertFalse(mBubbleData.hasBubbleInStackWithKey(groupedBubble.getEntry().getKey()));
        assertFalse(mBubbleData.isSummarySuppressed(
                groupSummary.getEntry().getSbn().getGroupKey()));
    }

    @Test
    public void testSummaryDismissalMarksBubblesHiddenFromShadeAndDismissesNonBubbledChildren()
            throws Exception {
        // GIVEN a group summary with two (non-bubble) children and one bubble child
        ExpandableNotificationRow groupSummary = mNotificationTestHelper.createGroup(2);
        ExpandableNotificationRow groupedBubble = mNotificationTestHelper.createBubbleInGroup();
        mEntryListener.onEntryAdded(groupedBubble.getEntry());
        when(mCommonNotifCollection.getEntry(groupedBubble.getEntry().getKey()))
                .thenReturn(groupedBubble.getEntry());
        groupSummary.addChildNotification(groupedBubble);

        // WHEN the summary is dismissed
        mBubblesManager.handleDismissalInterception(groupSummary.getEntry());

        // THEN only the NON-bubble children are dismissed
        List<ExpandableNotificationRow> childrenRows = groupSummary.getAttachedChildren();
        verify(mNotifCallback, times(1)).removeNotification(
                eq(childrenRows.get(0).getEntry()), any(), eq(REASON_GROUP_SUMMARY_CANCELED));
        verify(mNotifCallback, times(1)).removeNotification(
                eq(childrenRows.get(1).getEntry()), any(), eq(REASON_GROUP_SUMMARY_CANCELED));
        verify(mNotifCallback, never()).removeNotification(eq(groupedBubble.getEntry()),
                any(), anyInt());

        // THEN the bubble child still exists as a bubble and is suppressed from the shade
        assertTrue(mBubbleData.hasBubbleInStackWithKey(groupedBubble.getEntry().getKey()));
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                groupedBubble.getEntry().getKey(),
                groupedBubble.getEntry().getSbn().getGroupKey()));
        assertTrue(mBubbleController.getImplCachedState().isBubbleNotificationSuppressedFromShade(
                groupedBubble.getEntry().getKey(),
                groupedBubble.getEntry().getSbn().getGroupKey()));

        // THEN the summary is also suppressed from the shade
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                groupSummary.getEntry().getKey(),
                groupSummary.getEntry().getSbn().getGroupKey()));
        assertTrue(mBubbleController.getImplCachedState().isBubbleNotificationSuppressedFromShade(
                groupSummary.getEntry().getKey(),
                groupSummary.getEntry().getSbn().getGroupKey()));
    }


    /**
     * Verifies that when the user changes, the bubbles in the overflow list is cleared. Doesn't
     * test the loading from the repository which would be a nice thing to add.
     */
    @Test
    public void testOnUserChanged_overflowState() {
        int firstUserId = mBubbleEntry.getStatusBarNotification().getUser().getIdentifier();
        int secondUserId = mBubbleEntryUser11.getStatusBarNotification().getUser().getIdentifier();

        mBubbleController.updateBubble(mBubbleEntry);
        mBubbleController.updateBubble(mBubbleEntry2);
        assertTrue(mBubbleController.hasBubbles());
        mBubbleData.dismissAll(Bubbles.DISMISS_USER_GESTURE);

        // Verify these are in the overflow
        assertThat(mBubbleData.getOverflowBubbleWithKey(mBubbleEntry.getKey())).isNotNull();
        assertThat(mBubbleData.getOverflowBubbleWithKey(mBubbleEntry2.getKey())).isNotNull();

        // Switch users
        switchUser(secondUserId);
        assertThat(mBubbleData.getOverflowBubbles()).isEmpty();

        // Give this user some bubbles
        mBubbleController.updateBubble(mBubbleEntryUser11);
        mBubbleController.updateBubble(mBubbleEntry2User11);
        assertTrue(mBubbleController.hasBubbles());
        mBubbleData.dismissAll(Bubbles.DISMISS_USER_GESTURE);

        // Verify these are in the overflow
        assertThat(mBubbleData.getOverflowBubbleWithKey(mBubbleEntryUser11.getKey())).isNotNull();
        assertThat(mBubbleData.getOverflowBubbleWithKey(mBubbleEntry2User11.getKey())).isNotNull();

        // Would have loaded bubbles twice because of user switch
        verify(mDataRepository, times(2)).loadBubbles(anyInt(), anyList(), any());
    }

    @Test
    public void testOnUserChanged_bubblesRestored() {
        int firstUserId = mBubbleEntry.getStatusBarNotification().getUser().getIdentifier();
        int secondUserId = mBubbleEntryUser11.getStatusBarNotification().getUser().getIdentifier();
        // Mock current profile
        when(mLockscreenUserManager.isCurrentProfile(firstUserId)).thenReturn(true);
        when(mLockscreenUserManager.isCurrentProfile(secondUserId)).thenReturn(false);

        mBubbleController.updateBubble(mBubbleEntry);
        assertThat(mBubbleController.hasBubbles()).isTrue();
        // We start with 1 bubble
        assertThat(mBubbleData.getBubbles()).hasSize(1);

        // Switch to second user
        switchUser(secondUserId);

        // Second user has no bubbles
        assertThat(mBubbleController.hasBubbles()).isFalse();

        // Send bubble update for first user, ensure it does not show up
        mBubbleController.updateBubble(mBubbleEntry2);
        assertThat(mBubbleController.hasBubbles()).isFalse();

        // Start returning notif for first user again
        when(mCommonNotifCollection.getAllNotifs()).thenReturn(Arrays.asList(mRow, mRow2));

        // Switch back to first user
        switchUser(firstUserId);

        // Check we now have two bubbles, one previous and one new that came in
        assertThat(mBubbleController.hasBubbles()).isTrue();
        // Now there are 2 bubbles
        assertThat(mBubbleData.getBubbles()).hasSize(2);
    }

    /**
     * Verifies we only load the overflow data once.
     */
    @Test
    public void testOverflowLoadedOnce() {
        // XXX
        when(mCommonNotifCollection.getEntry(mRow.getKey())).thenReturn(mRow);
        when(mCommonNotifCollection.getEntry(mRow2.getKey())).thenReturn(mRow2);

        mEntryListener.onEntryAdded(mRow);
        mEntryListener.onEntryAdded(mRow2);
        mBubbleData.dismissAll(Bubbles.DISMISS_USER_GESTURE);
        assertThat(mBubbleData.getOverflowBubbles()).isNotEmpty();

        mEntryListener.onEntryRemoved(mRow, REASON_APP_CANCEL);
        mEntryListener.onEntryRemoved(mRow2, REASON_APP_CANCEL);
        assertThat(mBubbleData.getOverflowBubbles()).isEmpty();

        verify(mDataRepository, times(1)).loadBubbles(anyInt(), anyList(), any());
    }

    /**
     * Verifies that shortcut deletions triggers that bubble being removed from XML.
     */
    @Test
    public void testDeleteShortcutsDeletesXml() throws Exception {
        ExpandableNotificationRow row = mNotificationTestHelper.createShortcutBubble("shortcutId");
        BubbleEntry shortcutBubbleEntry = mBubblesManager.notifToBubbleEntry(row.getEntry());
        mBubbleController.updateBubble(shortcutBubbleEntry);

        mBubbleData.dismissBubbleWithKey(shortcutBubbleEntry.getKey(),
                Bubbles.DISMISS_SHORTCUT_REMOVED);

        verify(mDataRepository, atLeastOnce()).removeBubbles(anyInt(), mBubbleListCaptor.capture());
        assertThat(mBubbleListCaptor.getValue().get(0).getKey()).isEqualTo(
                shortcutBubbleEntry.getKey());
    }

    /**
     * Verifies that the package manager for the user is used when loading info for the bubble.
     */
    @Test
    public void test_bubbleViewInfoGetPackageForUser() throws Exception {
        final int workProfileUserId = 10;
        final UserHandle workUser = new UserHandle(workProfileUserId);
        final String workPkg = "work.pkg";

        final Bubble bubble = createBubble(workProfileUserId, workPkg);
        assertEquals(workProfileUserId, bubble.getUser().getIdentifier());

        final Context context = setUpContextWithPackageManager(workPkg, null /* AppInfo */);
        when(context.getResources()).thenReturn(mContext.getResources());
        final Context userContext = setUpContextWithPackageManager(workPkg,
                mock(ApplicationInfo.class));

        // If things are working correctly, CentralSurfaces.getPackageManagerForUser will call this
        when(context.createPackageContextAsUser(eq(workPkg), anyInt(), eq(workUser)))
                .thenReturn(userContext);

        BubbleViewInfoTask.BubbleViewInfo info = BubbleViewInfoTask.BubbleViewInfo.populate(context,
                BubbleExpandedViewManager.fromBubbleController(mBubbleController),
                () -> new BubbleTaskView(mock(TaskView.class), mock(Executor.class)),
                mPositioner,
                mBubbleController.getStackView(),
                new BubbleIconFactory(mContext,
                        mContext.getResources().getDimensionPixelSize(com.android.wm.shell.R.dimen.bubble_size),
                        mContext.getResources().getDimensionPixelSize(com.android.wm.shell.R.dimen.bubble_badge_size),
                        mContext.getResources().getColor(com.android.launcher3.icons.R.color.important_conversation),
                        mContext.getResources().getDimensionPixelSize(
                                com.android.internal.R.dimen.importance_ring_stroke_width)),
                bubble,
                true /* skipInflation */);
        verify(userContext, times(1)).getPackageManager();
        verify(context, times(1)).createPackageContextAsUser(eq(workPkg),
                eq(Context.CONTEXT_RESTRICTED),
                eq(workUser));
        assertNotNull(info);
    }

    @Test
    public void testShowManageMenuChangesSysuiState() {
        mBubbleController.updateBubble(mBubbleEntry);
        assertTrue(mBubbleController.hasBubbles());

        // Expand the stack
        BubbleStackView stackView = mBubbleController.getStackView();
        mBubbleData.setExpanded(true);
        assertStackExpanded();
        assertSysuiStates(true /* stackExpanded */, false /* manageMenuExpanded */);

        // Show the menu
        stackView.showManageMenu(true);
        assertSysuiStates(true /* stackExpanded */, true /* manageMenuExpanded */);
        assertTrue(stackView.isManageMenuSettingsVisible());
        assertTrue(stackView.isManageMenuDontBubbleVisible());
    }

    @Test
    public void testShowManageMenuChangesSysuiState_appBubble() {
        mBubbleController.showOrHideAppBubble(mAppBubbleIntent, mUser0, mAppBubbleIcon);
        assertTrue(mBubbleController.hasBubbles());

        // Expand the stack
        BubbleStackView stackView = mBubbleController.getStackView();
        mBubbleData.setExpanded(true);
        assertStackExpanded();
        assertSysuiStates(true /* stackExpanded */, false /* manageMenuExpanded */);

        // Show the menu
        stackView.showManageMenu(true);
        assertSysuiStates(true /* stackExpanded */, true /* manageMenuExpanded */);
        assertFalse(stackView.isManageMenuSettingsVisible());
        assertFalse(stackView.isManageMenuDontBubbleVisible());
    }

    @Test
    public void testHideManageMenuChangesSysuiState() {
        mBubbleController.updateBubble(mBubbleEntry);
        assertTrue(mBubbleController.hasBubbles());

        // Expand the stack
        BubbleStackView stackView = mBubbleController.getStackView();
        mBubbleData.setExpanded(true);
        assertStackExpanded();
        assertSysuiStates(true /* stackExpanded */, false /* manageMenuExpanded */);

        // Show the menu
        stackView.showManageMenu(true);
        assertSysuiStates(true /* stackExpanded */, true /* manageMenuExpanded */);

        // Hide the menu
        stackView.showManageMenu(false);
        assertSysuiStates(true /* stackExpanded */, false /* manageMenuExpanded */);
    }

    @Test
    public void testCollapseBubbleManageMenuChangesSysuiState() {
        mBubbleController.updateBubble(mBubbleEntry);
        assertTrue(mBubbleController.hasBubbles());

        // Expand the stack
        BubbleStackView stackView = mBubbleController.getStackView();
        mBubbleData.setExpanded(true);
        assertStackExpanded();
        assertSysuiStates(true /* stackExpanded */, false /* manageMenuExpanded */);

        // Show the menu
        stackView.showManageMenu(true);
        assertSysuiStates(true /* stackExpanded */, true /* manageMenuExpanded */);

        // Collapse the stack
        mBubbleData.setExpanded(false);

        assertSysuiStates(false /* stackExpanded */, false /* manageMenuExpanded */);
    }

    @Test
    public void testNotificationChannelModified_channelUpdated_removesOverflowBubble()
            throws Exception {
        // Setup
        ExpandableNotificationRow row = mNotificationTestHelper.createShortcutBubble("shortcutId");
        NotificationEntry entry = row.getEntry();
        entry.getChannel().setConversationId(
                row.getEntry().getChannel().getParentChannelId(),
                "shortcutId");
        mBubbleController.updateBubble(mBubblesManager.notifToBubbleEntry(row.getEntry()));
        assertTrue(mBubbleController.hasBubbles());

        // Overflow it
        mBubbleData.dismissBubbleWithKey(entry.getKey(),
                Bubbles.DISMISS_USER_GESTURE);
        assertThat(mBubbleData.hasOverflowBubbleWithKey(entry.getKey())).isTrue();

        // Test
        entry.getChannel().setDeleted(true);
        mBubbleController.onNotificationChannelModified(entry.getSbn().getPackageName(),
                entry.getSbn().getUser(),
                entry.getChannel(),
                NOTIFICATION_CHANNEL_OR_GROUP_UPDATED);
        assertThat(mBubbleData.hasOverflowBubbleWithKey(entry.getKey())).isFalse();
    }

    @Test
    public void testNotificationChannelModified_channelDeleted_removesOverflowBubble()
            throws Exception {
        // Setup
        ExpandableNotificationRow row = mNotificationTestHelper.createShortcutBubble("shortcutId");
        NotificationEntry entry = row.getEntry();
        entry.getChannel().setConversationId(
                row.getEntry().getChannel().getParentChannelId(),
                "shortcutId");
        mBubbleController.updateBubble(mBubblesManager.notifToBubbleEntry(row.getEntry()));
        assertTrue(mBubbleController.hasBubbles());

        // Overflow it
        mBubbleData.dismissBubbleWithKey(entry.getKey(),
                Bubbles.DISMISS_USER_GESTURE);
        assertThat(mBubbleData.hasOverflowBubbleWithKey(entry.getKey())).isTrue();

        // Test
        entry.getChannel().setDeleted(true);
        mBubbleController.onNotificationChannelModified(entry.getSbn().getPackageName(),
                entry.getSbn().getUser(),
                entry.getChannel(),
                NOTIFICATION_CHANNEL_OR_GROUP_DELETED);
        assertThat(mBubbleData.hasOverflowBubbleWithKey(entry.getKey())).isFalse();
    }

    @Test
    public void testStackViewOnBackPressed_updatesBubbleDataExpandState() {
        mBubbleController.updateBubble(mBubbleEntry);

        // Expand the stack
        mBubbleData.setExpanded(true);
        assertStackExpanded();

        // Hit back
        BubbleStackView stackView = mBubbleController.getStackView();
        stackView.onBackPressed();

        // Make sure we're collapsed
        assertStackCollapsed();
    }


    @Test
    public void testRegisterUnregisterBroadcastListener() {
        spyOn(mContext);
        mBubbleController.updateBubble(mBubbleEntry);
        verify(mContext).registerReceiver(mBroadcastReceiverArgumentCaptor.capture(),
                mFilterArgumentCaptor.capture(), eq(Context.RECEIVER_EXPORTED));
        assertThat(mFilterArgumentCaptor.getValue()
                .hasAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)).isTrue();
        assertThat(mFilterArgumentCaptor.getValue()
                .hasAction(Intent.ACTION_SCREEN_OFF)).isTrue();

        mBubbleData.dismissBubbleWithKey(mBubbleEntry.getKey(), REASON_APP_CANCEL);
        // TODO: not certain why this isn't called normally when tests are run, perhaps because
        // it's after an animation in BSV. This calls BubbleController#removeFromWindowManagerMaybe
        mBubbleController.onAllBubblesAnimatedOut();

        verify(mContext).unregisterReceiver(eq(mBroadcastReceiverArgumentCaptor.getValue()));
    }

    @Test
    public void testBroadcastReceiverCloseDialogs_notGestureNav() {
        spyOn(mContext);
        mBubbleController.updateBubble(mBubbleEntry);
        mBubbleData.setExpanded(true);
        verify(mContext).registerReceiver(mBroadcastReceiverArgumentCaptor.capture(),
                mFilterArgumentCaptor.capture(), eq(Context.RECEIVER_EXPORTED));
        Intent i = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        mBroadcastReceiverArgumentCaptor.getValue().onReceive(mContext, i);

        assertStackExpanded();
    }

    @Test
    public void testBroadcastReceiverCloseDialogs_reasonGestureNav() {
        spyOn(mContext);
        mBubbleController.updateBubble(mBubbleEntry);
        mBubbleData.setExpanded(true);

        verify(mContext).registerReceiver(mBroadcastReceiverArgumentCaptor.capture(),
                mFilterArgumentCaptor.capture(), eq(Context.RECEIVER_EXPORTED));
        Intent i = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        i.putExtra("reason", "gestureNav");
        mBroadcastReceiverArgumentCaptor.getValue().onReceive(mContext, i);
        assertStackCollapsed();
    }

    @Test
    public void testBroadcastReceiverCloseDialogs_reasonHomeKey() {
        spyOn(mContext);
        mBubbleController.updateBubble(mBubbleEntry);
        mBubbleData.setExpanded(true);

        verify(mContext).registerReceiver(mBroadcastReceiverArgumentCaptor.capture(),
                mFilterArgumentCaptor.capture(), eq(Context.RECEIVER_EXPORTED));
        Intent i = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        i.putExtra("reason", "homekey");
        mBroadcastReceiverArgumentCaptor.getValue().onReceive(mContext, i);
        assertStackCollapsed();
    }

    @Test
    public void testBroadcastReceiverCloseDialogs_reasonRecentsKey() {
        spyOn(mContext);
        mBubbleController.updateBubble(mBubbleEntry);
        mBubbleData.setExpanded(true);

        verify(mContext).registerReceiver(mBroadcastReceiverArgumentCaptor.capture(),
                mFilterArgumentCaptor.capture(), eq(Context.RECEIVER_EXPORTED));
        Intent i = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        i.putExtra("reason", "recentapps");
        mBroadcastReceiverArgumentCaptor.getValue().onReceive(mContext, i);
        assertStackCollapsed();
    }

    @Test
    public void testBroadcastReceiver_screenOff() {
        spyOn(mContext);
        mBubbleController.updateBubble(mBubbleEntry);
        mBubbleData.setExpanded(true);

        verify(mContext).registerReceiver(mBroadcastReceiverArgumentCaptor.capture(),
                mFilterArgumentCaptor.capture(), eq(Context.RECEIVER_EXPORTED));

        Intent i = new Intent(Intent.ACTION_SCREEN_OFF);
        mBroadcastReceiverArgumentCaptor.getValue().onReceive(mContext, i);
        assertStackCollapsed();
    }

    @Test
    public void testOnStatusBarStateChanged() {
        mBubbleController.updateBubble(mBubbleEntry);
        mBubbleData.setExpanded(true);
        assertStackExpanded();
        BubbleStackView stackView = mBubbleController.getStackView();
        assertThat(stackView.getVisibility()).isEqualTo(View.VISIBLE);

        mBubbleController.onStatusBarStateChanged(false);

        assertStackCollapsed();
        assertThat(stackView.getVisibility()).isEqualTo(View.INVISIBLE);

        mBubbleController.onStatusBarStateChanged(true);
        assertThat(stackView.getVisibility()).isEqualTo(View.VISIBLE);
    }

    /**
     * Test to verify behavior for following situation:
     * <ul>
     *     <li>status bar shade state is set to <code>false</code></li>
     *     <li>there is a bubble pending to be expanded</li>
     * </ul>
     * Test that duplicate status bar state updates to <code>false</code> do not clear the
     * pending bubble to be
     * expanded.
     */
    @Test
    public void testOnStatusBarStateChanged_statusBarChangeDoesNotClearExpandingBubble() {
        mBubbleController.updateBubble(mBubbleEntry);
        mBubbleController.onStatusBarStateChanged(false);
        // Set the bubble to expand once status bar state changes
        mBubbleController.expandStackAndSelectBubble(mBubbleEntry);
        // Check that stack is currently collapsed
        assertStackCollapsed();
        // Post status bar state change update with the same value
        mBubbleController.onStatusBarStateChanged(false);
        // Stack should remain collapsed
        assertStackCollapsed();
        // Post status bar state change which should trigger bubble to expand
        mBubbleController.onStatusBarStateChanged(true);
        assertStackExpanded();
    }

    /**
     * Test to verify behavior for the following scenario:
     * <ol>
     *     <li>device is locked with keyguard on, status bar shade state updates to
     *     <code>false</code></li>
     *     <li>notification entry is marked to be a bubble and it is set to auto-expand</li>
     *     <li>device unlock starts, status bar shade state receives another update to
     *     <code>false</code></li>
     *     <li>device is unlocked and status bar shade state is set to <code>true</code></li>
     *     <li>bubble should be expanded</li>
     * </ol>
     */
    @Test
    public void testOnStatusBarStateChanged_newAutoExpandedBubbleRemainsExpanded() {
        // Set device as locked
        mBubbleController.onStatusBarStateChanged(false);

        // Create a auto-expanded bubble
        NotificationEntry entry = mNotificationTestHelper.createAutoExpandedBubble();
        mEntryListener.onEntryAdded(entry);

        // When unlocking, we may receive duplicate updates with shade=false, ensure they don't
        // clear the expanded state
        mBubbleController.onStatusBarStateChanged(false);
        mBubbleController.onStatusBarStateChanged(true);

        // After unlocking, stack should be expanded
        assertStackExpanded();
    }

    @Test
    public void testSetShouldAutoExpand_notifiesFlagChanged() {
        mBubbleController.updateBubble(mBubbleEntry);

        assertTrue(mBubbleController.hasBubbles());
        Bubble b = mBubbleData.getBubbleInStackWithKey(mBubbleEntry.getKey());
        assertThat(b.shouldAutoExpand()).isFalse();

        // Set it to the same thing
        b.setShouldAutoExpand(false);

        // Verify it doesn't notify
        verify(mBubbleController, never()).onBubbleMetadataFlagChanged(any());

        // Set it to something different
        b.setShouldAutoExpand(true);
        verify(mBubbleController).onBubbleMetadataFlagChanged(b);
    }

    @Test
    public void testUpdateBubble_skipsDndSuppressListNotifs() {
        mBubbleEntry = new BubbleEntry(mRow.getSbn(), mRow.getRanking(), true, /* isDismissable */
                mRow.shouldSuppressNotificationDot(), true /* DndSuppressNotifFromList */,
                mRow.shouldSuppressPeek());
        mBubbleEntry.getBubbleMetadata().setFlags(
                Notification.BubbleMetadata.FLAG_AUTO_EXPAND_BUBBLE);

        mBubbleController.updateBubble(mBubbleEntry);

        Bubble b = mBubbleData.getPendingBubbleWithKey(mBubbleEntry.getKey());
        assertThat(b.shouldAutoExpand()).isFalse();
        assertThat(mBubbleData.getBubbleInStackWithKey(mBubbleEntry.getKey())).isNull();
    }

    @Test
    public void testOnRankingUpdate_DndSuppressListNotif() {
        // It's in the stack
        mBubbleController.updateBubble(mBubbleEntry);
        assertThat(mBubbleData.hasBubbleInStackWithKey(mBubbleEntry.getKey())).isTrue();

        // Set current user profile
        SparseArray<UserInfo> userInfos = new SparseArray<>();
        userInfos.put(mBubbleEntry.getStatusBarNotification().getUser().getIdentifier(),
                mock(UserInfo.class));
        mBubbleController.onCurrentProfilesChanged(userInfos);

        // Send ranking update that the notif is suppressed from the list.
        HashMap<String, Pair<BubbleEntry, Boolean>> entryDataByKey = new HashMap<>();
        mBubbleEntry = new BubbleEntry(mRow.getSbn(), mRow.getRanking(), true /* isDismissable */,
                mRow.shouldSuppressNotificationDot(), true /* DndSuppressNotifFromList */,
                mRow.shouldSuppressPeek());
        Pair<BubbleEntry, Boolean> pair = new Pair(mBubbleEntry, true);
        entryDataByKey.put(mBubbleEntry.getKey(), pair);

        NotificationListenerService.RankingMap rankingMap =
                mock(NotificationListenerService.RankingMap.class);
        when(rankingMap.getOrderedKeys()).thenReturn(new String[]{mBubbleEntry.getKey()});
        mBubbleController.onRankingUpdated(rankingMap, entryDataByKey);

        // Should no longer be in the stack
        assertThat(mBubbleData.hasBubbleInStackWithKey(mBubbleEntry.getKey())).isFalse();
    }

    /**
     * Verifies that if a bubble is in the overflow and a non-interruptive notification update
     * comes in for it, it stays in the overflow but the entry is updated.
     */
    @Test
    public void testNonInterruptiveUpdate_doesntBubbleFromOverflow() {
        mEntryListener.onEntryAdded(mRow);
        mEntryListener.onEntryUpdated(mRow, /* fromSystem= */ true);
        assertBubbleNotificationNotSuppressedFromShade(mBubbleEntry);

        // Dismiss the bubble so it's in the overflow
        mBubbleController.removeBubble(
                mRow.getKey(), Bubbles.DISMISS_USER_GESTURE);
        assertThat(mBubbleData.hasOverflowBubbleWithKey(mRow.getKey())).isTrue();

        // Update the entry to not show in shade
        setMetadataFlags(mRow,
                Notification.BubbleMetadata.FLAG_SUPPRESS_NOTIFICATION, /* enableFlag= */ true);
        mBubbleController.updateBubble(mBubbleEntry,
                /* suppressFlyout= */ false, /* showInShade= */ true);

        // Check that the update was applied - shouldn't be show in shade
        assertBubbleNotificationSuppressedFromShade(mBubbleEntry);
        // Check that it wasn't inflated (1 because it would've been inflated via onEntryAdded)
        verify(mBubbleController, times(1)).inflateAndAdd(
                any(Bubble.class), anyBoolean(), anyBoolean());
    }

    /**
     * Verifies that if a bubble is active, and a non-interruptive notification update comes in for
     * it, it doesn't trigger a new inflate and add for that bubble.
     */
    @Test
    public void testNonInterruptiveUpdate_doesntTriggerInflate() {
        mEntryListener.onEntryAdded(mRow);
        mEntryListener.onEntryUpdated(mRow, /* fromSystem= */ true);
        assertBubbleNotificationNotSuppressedFromShade(mBubbleEntry);

        // Update the entry to not show in shade
        setMetadataFlags(mRow,
                Notification.BubbleMetadata.FLAG_SUPPRESS_NOTIFICATION, /* enableFlag= */ true);
        mBubbleController.updateBubble(mBubbleEntry,
                /* suppressFlyout= */ false, /* showInShade= */ true);

        // Check that the update was applied - shouldn't be show in shade
        assertBubbleNotificationSuppressedFromShade(mBubbleEntry);
        // Check that it wasn't inflated (1 because it would've been inflated via onEntryAdded)
        verify(mBubbleController, times(1)).inflateAndAdd(
                any(Bubble.class), anyBoolean(), anyBoolean());
    }

    /**
     * Verifies that if a bubble is in the overflow and a non-interruptive notification update
     * comes in for it with FLAG_BUBBLE that the flag is removed.
     */
    @Test
    public void testNonInterruptiveUpdate_doesntOverrideOverflowFlagBubble() {
        mEntryListener.onEntryAdded(mRow);
        mEntryListener.onEntryUpdated(mRow, /* fromSystem= */ true);
        assertBubbleNotificationNotSuppressedFromShade(mBubbleEntry);

        // Dismiss the bubble so it's in the overflow
        mBubbleController.removeBubble(
                mRow.getKey(), Bubbles.DISMISS_USER_GESTURE);
        assertThat(mBubbleData.hasOverflowBubbleWithKey(mRow.getKey())).isTrue();
        // Once it's in the overflow it's not actively a bubble (doesn't have FLAG_BUBBLE)
        Bubble b = mBubbleData.getOverflowBubbleWithKey(mBubbleEntry.getKey());
        assertThat(b.isBubble()).isFalse();

        // Send a non-notifying update that has FLAG_BUBBLE
        mRow.getSbn().getNotification().flags = FLAG_BUBBLE;
        assertThat(mRow.getSbn().getNotification().isBubbleNotification()).isTrue();
        mBubbleController.updateBubble(mBubbleEntry,
                /* suppressFlyout= */ false, /* showInShade= */ true);

        // Verify that it still doesn't have FLAG_BUBBLE because it's in the overflow.
        b = mBubbleData.getOverflowBubbleWithKey(mBubbleEntry.getKey());
        assertThat(b.isBubble()).isFalse();
    }

    @Test
    public void testNonSystemUpdatesIgnored() {
        mEntryListener.onEntryAdded(mRow);
        assertThat(mBubbleController.hasBubbles()).isTrue();

        mEntryListener.onEntryUpdated(mRow, /* fromSystem= */ false);
        mEntryListener.onEntryUpdated(mRow, /* fromSystem= */ false);
        mEntryListener.onEntryUpdated(mRow, /* fromSystem= */ false);

        // Check that it wasn't inflated (1 because it would've been inflated via onEntryAdded)
        verify(mBubbleController, times(1)).inflateAndAdd(
                any(Bubble.class), anyBoolean(), anyBoolean());
    }

    @Test
    public void testShowStackEdu_isNotConversationBubble() {
        // Setup
        setPrefBoolean(StackEducationView.PREF_STACK_EDUCATION, false);
        BubbleEntry bubbleEntry = createBubbleEntry(false /* isConversation */);
        mBubbleController.updateBubble(bubbleEntry);
        assertTrue(mBubbleController.hasBubbles());

        // Click on bubble
        Bubble bubble = mBubbleData.getBubbleInStackWithKey(bubbleEntry.getKey());
        assertFalse(bubble.isConversation());
        bubble.getIconView().callOnClick();

        // Check education is not shown
        BubbleStackView stackView = mBubbleController.getStackView();
        assertFalse(stackView.isStackEduVisible());
    }

    @Test
    public void testShowStackEdu_isConversationBubble() {
        // Setup
        setPrefBoolean(StackEducationView.PREF_STACK_EDUCATION, false);
        BubbleEntry bubbleEntry = createBubbleEntry(true /* isConversation */);
        mBubbleController.updateBubble(bubbleEntry);
        assertTrue(mBubbleController.hasBubbles());

        // Click on bubble
        Bubble bubble = mBubbleData.getBubbleInStackWithKey(bubbleEntry.getKey());
        assertTrue(bubble.isConversation());
        bubble.getIconView().callOnClick();

        // Check education is shown
        BubbleStackView stackView = mBubbleController.getStackView();
        assertTrue(stackView.isStackEduVisible());
    }

    @Test
    public void testShowStackEdu_isSeenConversationBubble() {
        // Setup
        setPrefBoolean(StackEducationView.PREF_STACK_EDUCATION, true);
        BubbleEntry bubbleEntry = createBubbleEntry(true /* isConversation */);
        mBubbleController.updateBubble(bubbleEntry);
        assertTrue(mBubbleController.hasBubbles());

        // Click on bubble
        Bubble bubble = mBubbleData.getBubbleInStackWithKey(bubbleEntry.getKey());
        assertTrue(bubble.isConversation());
        bubble.getIconView().callOnClick();

        // Check education is not shown
        BubbleStackView stackView = mBubbleController.getStackView();
        assertFalse(stackView.isStackEduVisible());
    }

    @Test
    public void testShowOrHideAppBubble_addsAndExpand() {
        assertThat(mBubbleController.isStackExpanded()).isFalse();

        mBubbleController.showOrHideAppBubble(mAppBubbleIntent, mUser0, mAppBubbleIcon);

        verify(mBubbleController).inflateAndAdd(any(Bubble.class), /* suppressFlyout= */ eq(true),
                /* showInShade= */ eq(false));
        assertThat(mBubbleData.getSelectedBubble().getKey()).isEqualTo(
                Bubble.getAppBubbleKeyForApp(mContext.getPackageName(), mUser0));
        assertThat(mBubbleController.isStackExpanded()).isTrue();
    }

    @Test
    public void testShowOrHideAppBubble_expandIfCollapsed() {
        mBubbleController.showOrHideAppBubble(mAppBubbleIntent, mUser0, mAppBubbleIcon);
        mBubbleController.updateBubble(mBubbleEntry);
        mBubbleController.collapseStack();
        assertThat(mBubbleController.isStackExpanded()).isFalse();

        // Calling this while collapsed will expand the app bubble
        mBubbleController.showOrHideAppBubble(mAppBubbleIntent, mUser0, mAppBubbleIcon);

        assertThat(mBubbleData.getSelectedBubble().getKey()).isEqualTo(
                Bubble.getAppBubbleKeyForApp(mContext.getPackageName(), mUser0));
        assertThat(mBubbleController.isStackExpanded()).isTrue();
        assertThat(mBubbleData.getBubbles().size()).isEqualTo(2);
    }

    @Test
    public void testShowOrHideAppBubble_collapseIfSelected() {
        mBubbleController.showOrHideAppBubble(mAppBubbleIntent, mUser0, mAppBubbleIcon);
        assertThat(mBubbleData.getSelectedBubble().getKey()).isEqualTo(
                Bubble.getAppBubbleKeyForApp(mContext.getPackageName(), mUser0));
        assertThat(mBubbleController.isStackExpanded()).isTrue();

        // Calling this while the app bubble is expanded should collapse the stack
        mBubbleController.showOrHideAppBubble(mAppBubbleIntent, mUser0, mAppBubbleIcon);

        assertThat(mBubbleData.getSelectedBubble().getKey()).isEqualTo(
                Bubble.getAppBubbleKeyForApp(mContext.getPackageName(), mUser0));
        assertThat(mBubbleController.isStackExpanded()).isFalse();
        assertThat(mBubbleData.getBubbles().size()).isEqualTo(1);
        assertThat(mBubbleData.getBubbles().get(0).getUser()).isEqualTo(mUser0);
    }

    @Test
    public void testShowOrHideAppBubbleWithNonPrimaryUser_bubbleCollapsedWithExpectedUser() {
        UserHandle user10 = createUserHandle(/* userId = */ 10);
        String appBubbleKey = Bubble.getAppBubbleKeyForApp(mContext.getPackageName(), user10);
        mBubbleController.showOrHideAppBubble(mAppBubbleIntent, user10, mAppBubbleIcon);
        assertThat(mBubbleData.getSelectedBubble().getKey()).isEqualTo(appBubbleKey);
        assertThat(mBubbleController.isStackExpanded()).isTrue();
        assertThat(mBubbleData.getBubbles().size()).isEqualTo(1);
        assertThat(mBubbleData.getBubbles().get(0).getUser()).isEqualTo(user10);

        // Calling this while the app bubble is expanded should collapse the stack
        mBubbleController.showOrHideAppBubble(mAppBubbleIntent, user10, mAppBubbleIcon);

        assertThat(mBubbleData.getSelectedBubble().getKey()).isEqualTo(appBubbleKey);
        assertThat(mBubbleController.isStackExpanded()).isFalse();
        assertThat(mBubbleData.getBubbles().size()).isEqualTo(1);
        assertThat(mBubbleData.getBubbles().get(0).getUser()).isEqualTo(user10);
    }

    @Test
    public void testShowOrHideAppBubbleOnUser10AndThenUser0_user0BubbleExpanded() {
        UserHandle user10 = createUserHandle(/* userId = */ 10);
        mBubbleController.showOrHideAppBubble(mAppBubbleIntent, user10, mAppBubbleIcon);

        String appBubbleUser0Key = Bubble.getAppBubbleKeyForApp(mContext.getPackageName(), mUser0);
        mBubbleController.showOrHideAppBubble(mAppBubbleIntent, mUser0, mAppBubbleIcon);

        assertThat(mBubbleData.getSelectedBubble().getKey()).isEqualTo(appBubbleUser0Key);
        assertThat(mBubbleController.isStackExpanded()).isTrue();
        assertThat(mBubbleData.getBubbles()).hasSize(2);
        assertThat(mBubbleData.getBubbles().get(0).getUser()).isEqualTo(mUser0);
        assertThat(mBubbleData.getBubbles().get(1).getUser()).isEqualTo(user10);
    }

    @Test
    public void testShowOrHideAppBubble_selectIfNotSelected() {
        mBubbleController.showOrHideAppBubble(mAppBubbleIntent, mUser0, mAppBubbleIcon);
        mBubbleController.updateBubble(mBubbleEntry);
        mBubbleController.expandStackAndSelectBubble(mBubbleEntry);
        assertThat(mBubbleData.getSelectedBubble().getKey()).isEqualTo(mBubbleEntry.getKey());
        assertThat(mBubbleController.isStackExpanded()).isTrue();

        mBubbleController.showOrHideAppBubble(mAppBubbleIntent, mUser0, mAppBubbleIcon);
        assertThat(mBubbleData.getSelectedBubble().getKey()).isEqualTo(
                Bubble.getAppBubbleKeyForApp(mContext.getPackageName(), mUser0));
        assertThat(mBubbleController.isStackExpanded()).isTrue();
        assertThat(mBubbleData.getBubbles().size()).isEqualTo(2);
    }

    @Test
    public void testShowOrHideAppBubble_addsFromOverflow() {
        String appBubbleKey = Bubble.getAppBubbleKeyForApp(mAppBubbleIntent.getPackage(), mUser0);
        mBubbleController.showOrHideAppBubble(mAppBubbleIntent, mUser0, mAppBubbleIcon);

        // Collapse the stack so we don't need to wait for the dismiss animation in the test
        mBubbleController.collapseStack();

        // Dismiss the app bubble so it's in the overflow
        mBubbleController.dismissBubble(appBubbleKey, Bubbles.DISMISS_USER_GESTURE);
        assertThat(mBubbleData.getOverflowBubbleWithKey(appBubbleKey)).isNotNull();

        // Calling this while collapsed will re-add and expand the app bubble
        mBubbleController.showOrHideAppBubble(mAppBubbleIntent, mUser0, mAppBubbleIcon);
        assertThat(mBubbleData.getSelectedBubble().getKey()).isEqualTo(appBubbleKey);
        assertThat(mBubbleController.isStackExpanded()).isTrue();
        assertThat(mBubbleData.getBubbles().size()).isEqualTo(1);
        assertThat(mBubbleData.getOverflowBubbleWithKey(appBubbleKey)).isNull();
    }

    @Test
    public void testShowOrHideAppBubble_updateExistedBubbleInOverflow_updateIntentInBubble() {
        String appBubbleKey = Bubble.getAppBubbleKeyForApp(mAppBubbleIntent.getPackage(), mUser0);
        mBubbleController.showOrHideAppBubble(mAppBubbleIntent, mUser0, mAppBubbleIcon);
        // Collapse the stack so we don't need to wait for the dismiss animation in the test
        mBubbleController.collapseStack();
        // Dismiss the app bubble so it's in the overflow
        mBubbleController.dismissBubble(appBubbleKey, Bubbles.DISMISS_USER_GESTURE);
        assertThat(mBubbleData.getOverflowBubbleWithKey(appBubbleKey)).isNotNull();

        // Modify the intent to include new extras.
        Intent newAppBubbleIntent = new Intent(mContext, BubblesTestActivity.class)
                .setPackage(mContext.getPackageName())
                .putExtra("hello", "world");

        // Calling this while collapsed will re-add and expand the app bubble
        mBubbleController.showOrHideAppBubble(newAppBubbleIntent, mUser0, mAppBubbleIcon);
        assertThat(mBubbleData.getSelectedBubble().getKey()).isEqualTo(appBubbleKey);
        assertThat(mBubbleController.isStackExpanded()).isTrue();
        assertThat(mBubbleData.getBubbles().size()).isEqualTo(1);
        assertThat(mBubbleData.getBubbles().get(0).getAppBubbleIntent()).extras().string(
                "hello").isEqualTo("world");
        assertThat(mBubbleData.getOverflowBubbleWithKey(appBubbleKey)).isNull();
    }

    @Test
    public void testCreateBubbleFromOngoingNotification() {
        NotificationEntry notif = new NotificationEntryBuilder()
                .setFlag(mContext, Notification.FLAG_ONGOING_EVENT, true)
                .setCanBubble(true)
                .build();

        BubbleEntry bubble = mBubblesManager.notifToBubbleEntry(notif);

        assertTrue("Ongoing Notifis should be dismissable", bubble.isDismissable());
    }


    @Test
    public void testCreateBubbleFromNoDismissNotification() {
        NotificationEntry notif = new NotificationEntryBuilder()
                .setFlag(mContext, Notification.FLAG_NO_DISMISS, true)
                .setCanBubble(true)
                .build();

        BubbleEntry bubble = mBubblesManager.notifToBubbleEntry(notif);

        assertFalse("FLAG_NO_DISMISS Notifs should be non-dismissable", bubble.isDismissable());
    }

    @Test
    public void registerBubbleBarListener_barDisabled_largeScreen_shouldBeIgnored() {
        mBubbleProperties.mIsBubbleBarEnabled = false;
        mPositioner.setIsLargeScreen(true);
        mEntryListener.onEntryAdded(mRow);
        mBubbleController.updateBubble(mBubbleEntry);
        assertTrue(mBubbleController.hasBubbles());

        assertStackMode();

        FakeBubbleStateListener bubbleStateListener = new FakeBubbleStateListener();
        mBubbleController.registerBubbleStateListener(bubbleStateListener);

        assertStackMode();

        assertThat(mBubbleController.getStackView().getBubbleCount()).isEqualTo(1);
    }

    @Test
    public void registerBubbleBarListener_barEnabled_smallScreen_shouldBeIgnored() {
        mBubbleProperties.mIsBubbleBarEnabled = true;
        mPositioner.setIsLargeScreen(false);
        mEntryListener.onEntryAdded(mRow);
        mBubbleController.updateBubble(mBubbleEntry);
        assertTrue(mBubbleController.hasBubbles());

        assertStackMode();

        FakeBubbleStateListener bubbleStateListener = new FakeBubbleStateListener();
        mBubbleController.registerBubbleStateListener(bubbleStateListener);

        assertStackMode();

        assertThat(mBubbleController.getStackView().getBubbleCount()).isEqualTo(1);
    }

    @Test
    public void registerBubbleBarListener_switchToBarAndBackToStack() {
        mBubbleProperties.mIsBubbleBarEnabled = true;
        mPositioner.setIsLargeScreen(true);
        mEntryListener.onEntryAdded(mRow);
        mBubbleController.updateBubble(mBubbleEntry);
        assertTrue(mBubbleController.hasBubbles());

        assertStackMode();

        assertThat(mBubbleData.getBubbles()).hasSize(1);
        assertBubbleIsInflatedForStack(mBubbleData.getBubbles().get(0));
        assertBubbleIsInflatedForStack(mBubbleData.getOverflow());

        FakeBubbleStateListener bubbleStateListener = new FakeBubbleStateListener();
        mBubbleController.registerBubbleStateListener(bubbleStateListener);

        assertBarMode();

        assertThat(mBubbleData.getBubbles()).hasSize(1);
        assertBubbleIsInflatedForBar(mBubbleData.getBubbles().get(0));
        assertBubbleIsInflatedForBar(mBubbleData.getOverflow());

        mBubbleController.unregisterBubbleStateListener();

        assertStackMode();

        assertThat(mBubbleData.getBubbles()).hasSize(1);
        assertBubbleIsInflatedForStack(mBubbleData.getBubbles().get(0));
        assertBubbleIsInflatedForStack(mBubbleData.getOverflow());
    }

    @Test
    public void registerBubbleBarListener_switchToBarWhileExpanded() {
        mBubbleProperties.mIsBubbleBarEnabled = true;
        mPositioner.setIsLargeScreen(true);

        mEntryListener.onEntryAdded(mRow);
        mBubbleController.updateBubble(mBubbleEntry);
        BubbleStackView stackView = mBubbleController.getStackView();
        spyOn(stackView);

        mBubbleData.setExpanded(true);

        assertStackMode();
        assertThat(mBubbleData.isExpanded()).isTrue();
        assertThat(stackView.isExpanded()).isTrue();

        FakeBubbleStateListener bubbleStateListener = new FakeBubbleStateListener();
        mBubbleController.registerBubbleStateListener(bubbleStateListener);

        BubbleBarLayerView layerView = mBubbleController.getLayerView();
        spyOn(layerView);

        assertBarMode();
        assertThat(mBubbleData.isExpanded()).isTrue();
        assertThat(layerView.isExpanded()).isTrue();
    }

    @Test
    public void switchBetweenBarAndStack_noBubbles_shouldBeIgnored() {
        mBubbleProperties.mIsBubbleBarEnabled = false;
        mPositioner.setIsLargeScreen(true);
        assertFalse(mBubbleController.hasBubbles());

        assertNoBubbleContainerViews();

        FakeBubbleStateListener bubbleStateListener = new FakeBubbleStateListener();
        mBubbleController.registerBubbleStateListener(bubbleStateListener);

        assertNoBubbleContainerViews();

        mBubbleController.unregisterBubbleStateListener();

        assertNoBubbleContainerViews();
    }

    @Test
    public void bubbleBarBubbleExpandedAndCollapsed() {
        mBubbleProperties.mIsBubbleBarEnabled = true;
        mPositioner.setIsLargeScreen(true);
        mEntryListener.onEntryAdded(mRow);
        mBubbleController.updateBubble(mBubbleEntry);

        FakeBubbleStateListener bubbleStateListener = new FakeBubbleStateListener();
        mBubbleController.registerBubbleStateListener(bubbleStateListener);
        mBubbleController.expandStackAndSelectBubbleFromLauncher(mBubbleEntry.getKey(), 1000);

        assertThat(mBubbleController.getLayerView().isExpanded()).isTrue();

        mBubbleController.collapseStack();

        assertThat(mBubbleController.getLayerView().isExpanded()).isFalse();
    }

    @EnableFlags(FLAG_ENABLE_BUBBLE_BAR)
    @Test
    public void dragBubbleBarBubble_selectedBubble_expandedViewCollapsesDuringDrag() {
        mBubbleProperties.mIsBubbleBarEnabled = true;
        mPositioner.setIsLargeScreen(true);
        FakeBubbleStateListener bubbleStateListener = new FakeBubbleStateListener();
        mBubbleController.registerBubbleStateListener(bubbleStateListener);

        // Add 2 bubbles
        mEntryListener.onEntryAdded(mRow);
        mEntryListener.onEntryAdded(mRow2);
        mBubbleController.updateBubble(mBubbleEntry);
        mBubbleController.updateBubble(mBubbleEntry2);

        // Select first bubble
        mBubbleController.expandStackAndSelectBubbleFromLauncher(mBubbleEntry.getKey(), 0);
        assertThat(mBubbleData.getSelectedBubbleKey()).isEqualTo(mBubbleEntry.getKey());
        assertThat(mBubbleController.getLayerView().isExpanded()).isTrue();

        // Drag first bubble, bubble should collapse
        mBubbleController.startBubbleDrag(mBubbleEntry.getKey());
        assertThat(mBubbleController.getLayerView().isExpanded()).isFalse();

        // Stop dragging, first bubble should be expanded
        mBubbleController.stopBubbleDrag(BubbleBarLocation.LEFT, 0);
        assertThat(mBubbleData.getSelectedBubbleKey()).isEqualTo(mBubbleEntry.getKey());
        assertThat(mBubbleController.getLayerView().isExpanded()).isTrue();
    }

    @EnableFlags(FLAG_ENABLE_BUBBLE_BAR)
    @Test
    public void dragBubbleBarBubble_unselectedBubble_expandedViewCollapsesDuringDrag() {
        mBubbleProperties.mIsBubbleBarEnabled = true;
        mPositioner.setIsLargeScreen(true);
        FakeBubbleStateListener bubbleStateListener = new FakeBubbleStateListener();
        mBubbleController.registerBubbleStateListener(bubbleStateListener);

        // Add 2 bubbles
        mEntryListener.onEntryAdded(mRow);
        mEntryListener.onEntryAdded(mRow2);
        mBubbleController.updateBubble(mBubbleEntry);
        mBubbleController.updateBubble(mBubbleEntry2);

        // Select first bubble
        mBubbleController.expandStackAndSelectBubbleFromLauncher(mBubbleEntry.getKey(), 0);
        assertThat(mBubbleData.getSelectedBubbleKey()).isEqualTo(mBubbleEntry.getKey());
        assertThat(mBubbleController.getLayerView().isExpanded()).isTrue();

        // Drag second bubble, bubble should collapse
        mBubbleController.startBubbleDrag(mBubbleEntry2.getKey());
        assertThat(mBubbleController.getLayerView().isExpanded()).isFalse();

        // Stop dragging, first bubble should be expanded
        mBubbleController.stopBubbleDrag(BubbleBarLocation.LEFT, 0);
        assertThat(mBubbleData.getSelectedBubbleKey()).isEqualTo(mBubbleEntry.getKey());
        assertThat(mBubbleController.getLayerView().isExpanded()).isTrue();
    }

    @EnableFlags(FLAG_ENABLE_BUBBLE_BAR)
    @Test
    public void dismissBubbleBarBubble_selected_selectsAndExpandsNext() {
        mBubbleProperties.mIsBubbleBarEnabled = true;
        mPositioner.setIsLargeScreen(true);
        FakeBubbleStateListener bubbleStateListener = new FakeBubbleStateListener();
        mBubbleController.registerBubbleStateListener(bubbleStateListener);

        // Add 2 bubbles
        mEntryListener.onEntryAdded(mRow);
        mEntryListener.onEntryAdded(mRow2);
        mBubbleController.updateBubble(mBubbleEntry);
        mBubbleController.updateBubble(mBubbleEntry2);

        // Select first bubble
        mBubbleController.expandStackAndSelectBubbleFromLauncher(mBubbleEntry.getKey(), 0);
        // Drag first bubble to dismiss
        mBubbleController.startBubbleDrag(mBubbleEntry.getKey());
        mBubbleController.dragBubbleToDismiss(mBubbleEntry.getKey());
        // Second bubble is selected and expanded
        assertThat(mBubbleData.getSelectedBubbleKey()).isEqualTo(mBubbleEntry2.getKey());
        assertThat(mBubbleController.getLayerView().isExpanded()).isTrue();
    }

    @EnableFlags(FLAG_ENABLE_BUBBLE_BAR)
    @Test
    public void dismissBubbleBarBubble_unselected_selectionDoesNotChange() {
        mBubbleProperties.mIsBubbleBarEnabled = true;
        mPositioner.setIsLargeScreen(true);
        FakeBubbleStateListener bubbleStateListener = new FakeBubbleStateListener();
        mBubbleController.registerBubbleStateListener(bubbleStateListener);

        // Add 2 bubbles
        mEntryListener.onEntryAdded(mRow);
        mEntryListener.onEntryAdded(mRow2);
        mBubbleController.updateBubble(mBubbleEntry);
        mBubbleController.updateBubble(mBubbleEntry2);

        // Select first bubble
        mBubbleController.expandStackAndSelectBubbleFromLauncher(mBubbleEntry.getKey(), 0);
        // Drag second bubble to dismiss
        mBubbleController.startBubbleDrag(mBubbleEntry2.getKey());
        mBubbleController.dragBubbleToDismiss(mBubbleEntry2.getKey());
        // First bubble remains selected and expanded
        assertThat(mBubbleData.getSelectedBubbleKey()).isEqualTo(mBubbleEntry.getKey());
        assertThat(mBubbleController.getLayerView().isExpanded()).isTrue();
    }

    @DisableFlags(FLAG_SCREENSHARE_NOTIFICATION_HIDING)
    @Test
    public void doesNotRegisterSensitiveStateListener() {
        verifyZeroInteractions(mSensitiveNotificationProtectionController);
    }

    @EnableFlags(FLAG_SCREENSHARE_NOTIFICATION_HIDING)
    @Test
    public void registerSensitiveStateListener() {
        verify(mSensitiveNotificationProtectionController).registerSensitiveStateListener(any());
    }

    @EnableFlags(FLAG_SCREENSHARE_NOTIFICATION_HIDING)
    @Test
    public void onSensitiveNotificationProtectionStateChanged() {
        verify(mSensitiveNotificationProtectionController, atLeastOnce())
                .registerSensitiveStateListener(mSensitiveStateChangedListener.capture());

        when(mSensitiveNotificationProtectionController.isSensitiveStateActive()).thenReturn(true);
        mSensitiveStateChangedListener.getValue().run();
        verify(mBubbleController).onSensitiveNotificationProtectionStateChanged(true);

        when(mSensitiveNotificationProtectionController.isSensitiveStateActive()).thenReturn(false);
        mSensitiveStateChangedListener.getValue().run();
        verify(mBubbleController).onSensitiveNotificationProtectionStateChanged(false);
    }

    @Test
    public void setBubbleBarLocation_listenerNotified() {
        mBubbleProperties.mIsBubbleBarEnabled = true;
        mPositioner.setIsLargeScreen(true);

        FakeBubbleStateListener bubbleStateListener = new FakeBubbleStateListener();
        mBubbleController.registerBubbleStateListener(bubbleStateListener);
        mBubbleController.setBubbleBarLocation(BubbleBarLocation.LEFT);
        assertThat(bubbleStateListener.mLastUpdate).isNotNull();
        assertThat(bubbleStateListener.mLastUpdate.bubbleBarLocation).isEqualTo(
                BubbleBarLocation.LEFT);
    }

    @Test
    public void setBubbleBarLocation_barDisabled_shouldBeIgnored() {
        mBubbleProperties.mIsBubbleBarEnabled = false;
        mPositioner.setIsLargeScreen(true);

        FakeBubbleStateListener bubbleStateListener = new FakeBubbleStateListener();
        mBubbleController.registerBubbleStateListener(bubbleStateListener);
        mBubbleController.setBubbleBarLocation(BubbleBarLocation.LEFT);
        assertThat(bubbleStateListener.mStateChangeCalls).isEqualTo(0);
    }

    @EnableFlags(Flags.FLAG_ENABLE_OPTIONAL_BUBBLE_OVERFLOW)
    @Test
    public void showBubbleOverflow_hasOverflowContents() {
        mEntryListener.onEntryAdded(mRow);
        mEntryListener.onEntryUpdated(mRow, /* fromSystem= */ true);
        assertThat(mBubbleData.getOverflowBubbles()).isEmpty();

        BubbleStackView stackView = mBubbleController.getStackView();
        spyOn(stackView);

        // Dismiss the bubble so it's in the overflow
        mBubbleController.removeBubble(mRow.getKey(), Bubbles.DISMISS_USER_GESTURE);
        assertThat(mBubbleData.getOverflowBubbles()).isNotEmpty();

        verify(stackView).showOverflow(eq(true));
    }

    @EnableFlags(Flags.FLAG_ENABLE_OPTIONAL_BUBBLE_OVERFLOW)
    @Test
    public void showBubbleOverflow_isEmpty() {
        mEntryListener.onEntryAdded(mRow);
        mEntryListener.onEntryUpdated(mRow, /* fromSystem= */ true);
        assertThat(mBubbleData.getOverflowBubbles()).isEmpty();

        BubbleStackView stackView = mBubbleController.getStackView();
        spyOn(stackView);

        // Dismiss the bubble so it's in the overflow
        mBubbleController.removeBubble(mRow.getKey(), Bubbles.DISMISS_USER_GESTURE);
        assertThat(mBubbleData.getOverflowBubbles()).isNotEmpty();
        verify(stackView).showOverflow(eq(true));

        // Cancel the bubble so it's removed from the overflow
        mBubbleController.removeBubble(mRow.getKey(), Bubbles.DISMISS_NOTIF_CANCEL);
        assertThat(mBubbleData.getOverflowBubbles()).isEmpty();
        verify(stackView).showOverflow(eq(false));
    }

    @DisableFlags(Flags.FLAG_ENABLE_OPTIONAL_BUBBLE_OVERFLOW)
    @Test
    public void showBubbleOverflow_ignored() {
        mEntryListener.onEntryAdded(mRow);
        mEntryListener.onEntryUpdated(mRow, /* fromSystem= */ true);
        assertThat(mBubbleData.getOverflowBubbles()).isEmpty();

        BubbleStackView stackView = mBubbleController.getStackView();
        spyOn(stackView);

        // Dismiss the bubble so it's in the overflow
        mBubbleController.removeBubble(mRow.getKey(), Bubbles.DISMISS_USER_GESTURE);
        assertThat(mBubbleData.getOverflowBubbles()).isNotEmpty();

        // Cancel the bubble so it's removed from the overflow
        mBubbleController.removeBubble(mRow.getKey(), Bubbles.DISMISS_NOTIF_CANCEL);
        assertThat(mBubbleData.getOverflowBubbles()).isEmpty();

        // Show overflow should never be called if the flag is off
        verify(stackView, never()).showOverflow(anyBoolean());
    }

    /** Creates a bubble using the userId and package. */
    private Bubble createBubble(int userId, String pkg) {
        final UserHandle userHandle = new UserHandle(userId);
        NotificationEntry workEntry = new NotificationEntryBuilder()
                .setPkg(pkg)
                .setUser(userHandle)
                .build();
        workEntry.setBubbleMetadata(getMetadata());
        workEntry.setFlagBubble(true);

        return new Bubble(mBubblesManager.notifToBubbleEntry(workEntry),
                null,
                mock(Bubbles.PendingIntentCanceledListener.class), new SyncExecutor());
    }

    private BubbleEntry createBubbleEntry(boolean isConversation) {
        NotificationEntry notificationEntry = mNotificationTestHelper.createBubble(mDeleteIntent);
        if (isConversation) {
            ShortcutInfo shortcutInfo = new ShortcutInfo.Builder(mContext)
                    .setId("shortcutId")
                    .build();
            NotificationEntryHelper.modifyRanking(notificationEntry)
                    .setIsConversation(true)
                    .setShortcutInfo(shortcutInfo)
                    .build();
        }
        return mBubblesManager.notifToBubbleEntry(notificationEntry);
    }

    /** Creates a context that will return a PackageManager with specific AppInfo. */
    private Context setUpContextWithPackageManager(String pkg, ApplicationInfo info)
            throws Exception {
        final PackageManager pm = mock(PackageManager.class);
        when(pm.getApplicationInfo(eq(pkg), anyInt())).thenReturn(info);

        if (info != null) {
            Drawable d = mock(Drawable.class);
            when(d.getBounds()).thenReturn(new Rect());
            when(pm.getApplicationIcon(anyString())).thenReturn(d);
            when(pm.getUserBadgedIcon(any(), any())).thenReturn(d);
        }

        final Context context = mock(Context.class);
        when(context.getPackageName()).thenReturn(pkg);
        when(context.getPackageManager()).thenReturn(pm);
        return context;
    }

    /**
     * Sets the bubble metadata flags for this entry. These flags are normally set by
     * NotificationManagerService when the notification is sent, however, these tests do not
     * go through that path so we set them explicitly when testing.
     */
    private void setMetadataFlags(NotificationEntry entry, int flag, boolean enableFlag) {
        Notification.BubbleMetadata bubbleMetadata =
                entry.getSbn().getNotification().getBubbleMetadata();
        int flags = bubbleMetadata.getFlags();
        if (enableFlag) {
            flags |= flag;
        } else {
            flags &= ~flag;
        }
        bubbleMetadata.setFlags(flags);
    }

    /**
     * Set preferences boolean value for key
     * Used to setup global state for stack view education tests
     */
    private void setPrefBoolean(String key, boolean enabled) {
        mContext.getSharedPreferences(mContext.getPackageName(), Context.MODE_PRIVATE)
                .edit().putBoolean(key, enabled).apply();
    }

    private Notification.BubbleMetadata getMetadata() {
        Intent target = new Intent(mContext, BubblesTestActivity.class);
        PendingIntent bubbleIntent = PendingIntent.getActivity(mContext, 0, target, FLAG_MUTABLE);
        return new Notification.BubbleMetadata.Builder(
                bubbleIntent,
                Icon.createWithResource(
                        mContext,
                        com.android.wm.shell.R.drawable.bubble_ic_create_bubble))
                .build();
    }

    private void switchUser(int userId) {
        when(mLockscreenUserManager.isCurrentProfile(anyInt())).thenAnswer(
                (Answer<Boolean>) invocation -> invocation.<Integer>getArgument(0) == userId);
        SparseArray<UserInfo> userInfos = new SparseArray<>(1);
        userInfos.put(userId, mock(UserInfo.class));
        mBubbleController.onCurrentProfilesChanged(userInfos);
        mBubbleController.onUserChanged(userId);
    }

    private UserHandle createUserHandle(int userId) {
        UserHandle user = mock(UserHandle.class);
        when(user.getIdentifier()).thenReturn(userId);
        return user;
    }

    /**
     * Asserts that the bubble stack is expanded and also validates the cached state is updated.
     */
    private void assertStackExpanded() {
        assertTrue(mBubbleController.isStackExpanded());
        assertTrue(mBubbleController.getImplCachedState().isStackExpanded());
    }

    /**
     * Asserts that the bubble stack is collapsed and also validates the cached state is updated.
     */
    private void assertStackCollapsed() {
        assertFalse(mBubbleController.isStackExpanded());
        assertFalse(mBubbleController.getImplCachedState().isStackExpanded());
    }

    /** Asserts that both the bubble stack and bar views don't exist. */
    private void assertNoBubbleContainerViews() {
        assertThat(mBubbleController.getStackView()).isNull();
        assertThat(mBubbleController.getLayerView()).isNull();
    }

    /** Asserts that the stack is created and the bar is null. */
    private void assertStackMode() {
        assertThat(mBubbleController.getStackView()).isNotNull();
        assertThat(mBubbleController.getLayerView()).isNull();
    }

    /** Asserts that the given bubble has the stack expanded view inflated. */
    private void assertBubbleIsInflatedForStack(BubbleViewProvider b) {
        assertThat(b.getIconView()).isNotNull();
        assertThat(b.getExpandedView()).isNotNull();
        assertThat(b.getBubbleBarExpandedView()).isNull();
    }

    /** Asserts that the bar is created and the stack is null. */
    private void assertBarMode() {
        assertThat(mBubbleController.getStackView()).isNull();
        assertThat(mBubbleController.getLayerView()).isNotNull();
    }

    /** Asserts that the given bubble has the bar expanded view inflated. */
    private void assertBubbleIsInflatedForBar(BubbleViewProvider b) {
        // the icon view should be inflated for the overflow but not for other bubbles when showing
        // in the bar
        if (b instanceof Bubble) {
            assertThat(b.getIconView()).isNull();
        } else if (b instanceof BubbleOverflow) {
            assertThat(b.getIconView()).isNotNull();
        }
        assertThat(b.getExpandedView()).isNull();
        assertThat(b.getBubbleBarExpandedView()).isNotNull();
    }

    /**
     * Asserts that a bubble notification is suppressed from the shade and also validates the cached
     * state is updated.
     */
    private void assertBubbleNotificationSuppressedFromShade(BubbleEntry entry) {
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                entry.getKey(), entry.getGroupKey()));
        assertTrue(mBubbleController.getImplCachedState().isBubbleNotificationSuppressedFromShade(
                entry.getKey(), entry.getGroupKey()));
    }

    /**
     * Asserts that a bubble notification is not suppressed from the shade and also validates the
     * cached state is updated.
     */
    private void assertBubbleNotificationNotSuppressedFromShade(BubbleEntry entry) {
        assertFalse(mBubbleController.isBubbleNotificationSuppressedFromShade(
                entry.getKey(), entry.getGroupKey()));
        assertFalse(mBubbleController.getImplCachedState().isBubbleNotificationSuppressedFromShade(
                entry.getKey(), entry.getGroupKey()));
    }

    /**
     * Asserts that the system ui states associated to bubbles are in the correct state.
     */
    private void assertSysuiStates(boolean stackExpanded, boolean manageMenuExpanded) {
        assertThat(mSysUiStateBubblesExpanded).isEqualTo(stackExpanded);
        assertThat(mSysUiStateBubblesManageMenuExpanded).isEqualTo(manageMenuExpanded);
    }

    private static class FakeBubbleStateListener implements Bubbles.BubbleStateListener {

        int mStateChangeCalls = 0;
        @Nullable
        BubbleBarUpdate mLastUpdate;

        @Override
        public void onBubbleStateChange(BubbleBarUpdate update) {
            mStateChangeCalls++;
            mLastUpdate = update;
        }

        @Override
        public void animateBubbleBarLocation(BubbleBarLocation location) {
        }
    }

    private static class FakeBubbleProperties implements BubbleProperties {
        boolean mIsBubbleBarEnabled = false;

        @Override
        public boolean isBubbleBarEnabled() {
            return mIsBubbleBarEnabled;
        }

        @Override
        public void refresh() {}
    }
}
