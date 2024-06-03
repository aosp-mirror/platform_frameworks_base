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

package com.android.systemui.statusbar.notification.collection.coordinator;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertFalse;

import static kotlinx.coroutines.flow.StateFlowKt.MutableStateFlow;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.TestableLooper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.compose.animation.scene.ObservableTransitionState;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.communal.shared.model.CommunalScenes;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.kosmos.KosmosJavaAdapter;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shade.data.repository.FakeShadeRepository;
import com.android.systemui.shade.data.repository.ShadeAnimationRepository;
import com.android.systemui.shade.data.repository.ShadeRepository;
import com.android.systemui.shade.domain.interactor.ShadeAnimationInteractor;
import com.android.systemui.shade.domain.interactor.ShadeAnimationInteractorLegacyImpl;
import com.android.systemui.statusbar.notification.VisibilityLocationProvider;
import com.android.systemui.statusbar.notification.collection.GroupEntry;
import com.android.systemui.statusbar.notification.collection.GroupEntryBuilder;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifStabilityManager;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.Pluggable;
import com.android.systemui.statusbar.notification.collection.provider.VisualStabilityProvider;
import com.android.systemui.statusbar.notification.domain.interactor.SeenNotificationsInteractor;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.kotlin.JavaAdapter;
import com.android.systemui.util.time.FakeSystemClock;

import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.test.TestScope;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.verification.VerificationMode;

@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper
public class VisualStabilityCoordinatorTest extends SysuiTestCase {

    private VisualStabilityCoordinator mCoordinator;

    @Mock private DumpManager mDumpManager;
    @Mock private NotifPipeline mNotifPipeline;
    @Mock private WakefulnessLifecycle mWakefulnessLifecycle;
    @Mock private StatusBarStateController mStatusBarStateController;
    @Mock private Pluggable.PluggableListener<NotifStabilityManager> mInvalidateListener;
    @Mock private SeenNotificationsInteractor mSeenNotificationsInteractor;
    @Mock private HeadsUpManager mHeadsUpManager;
    @Mock private VisibilityLocationProvider mVisibilityLocationProvider;
    @Mock private VisualStabilityProvider mVisualStabilityProvider;

    @Captor private ArgumentCaptor<WakefulnessLifecycle.Observer> mWakefulnessObserverCaptor;
    @Captor private ArgumentCaptor<StatusBarStateController.StateListener> mSBStateListenerCaptor;
    @Captor private ArgumentCaptor<NotifStabilityManager> mNotifStabilityManagerCaptor;

    private final KosmosJavaAdapter mKosmos = new KosmosJavaAdapter(this);
    private FakeSystemClock mFakeSystemClock = new FakeSystemClock();
    private FakeExecutor mFakeExecutor = new FakeExecutor(mFakeSystemClock);
    private final TestScope mTestScope = mKosmos.getTestScope();
    private final JavaAdapter mJavaAdapter = new JavaAdapter(mTestScope.getBackgroundScope());

    private ShadeAnimationInteractor mShadeAnimationInteractor;
    private ShadeRepository mShadeRepository;
    private WakefulnessLifecycle.Observer mWakefulnessObserver;
    private StatusBarStateController.StateListener mStatusBarStateListener;
    private NotifStabilityManager mNotifStabilityManager;
    private NotificationEntry mEntry;
    private GroupEntry mGroupEntry;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mShadeRepository = new FakeShadeRepository();
        mShadeAnimationInteractor = new ShadeAnimationInteractorLegacyImpl(
                new ShadeAnimationRepository(), mShadeRepository);
        mCoordinator = new VisualStabilityCoordinator(
                mFakeExecutor,
                mDumpManager,
                mHeadsUpManager,
                mShadeAnimationInteractor,
                mJavaAdapter,
                mSeenNotificationsInteractor,
                mStatusBarStateController,
                mVisibilityLocationProvider,
                mVisualStabilityProvider,
                mWakefulnessLifecycle,
                mKosmos.getCommunalInteractor());
        mCoordinator.attach(mNotifPipeline);
        mTestScope.getTestScheduler().runCurrent();

        // capture arguments:
        verify(mWakefulnessLifecycle).addObserver(mWakefulnessObserverCaptor.capture());
        mWakefulnessObserver = mWakefulnessObserverCaptor.getValue();

        verify(mStatusBarStateController).addCallback(mSBStateListenerCaptor.capture());
        mStatusBarStateListener = mSBStateListenerCaptor.getValue();

        verify(mNotifPipeline).setVisualStabilityManager(mNotifStabilityManagerCaptor.capture());
        mNotifStabilityManager = mNotifStabilityManagerCaptor.getValue();
        mNotifStabilityManager.setInvalidationListener(mInvalidateListener);

        mEntry = new NotificationEntryBuilder()
                .setPkg("testPkg1")
                .build();

        mGroupEntry = new GroupEntryBuilder()
                .setSummary(mEntry)
                .build();

        when(mHeadsUpManager.isHeadsUpEntry(mEntry.getKey())).thenReturn(false);

        // Whenever we invalidate, the pipeline runs again, so we invalidate the state
        doAnswer(i -> {
            mNotifStabilityManager.onBeginRun();
            return null;
        }).when(mInvalidateListener).onPluggableInvalidated(eq(mNotifStabilityManager), any());
    }

    @Test
    public void testScreenOff_groupAndSectionChangesAllowed() {
        // GIVEN screen is off, panel isn't expanded and device isn't pulsing
        setFullyDozed(true);
        setSleepy(true);
        setPanelExpanded(false);
        setPulsing(false);

        // THEN group changes are allowed
        assertTrue(mNotifStabilityManager.isGroupChangeAllowed(mEntry));
        assertTrue(mNotifStabilityManager.isGroupPruneAllowed(mGroupEntry));

        // THEN section changes are allowed
        assertTrue(mNotifStabilityManager.isSectionChangeAllowed(mEntry));
    }

    @Test
    public void testScreenTurningOff_groupAndSectionChangesNotAllowed() {
        // GIVEN the screen is turning off (sleepy but partially dozed)
        setFullyDozed(false);
        setSleepy(true);
        setPanelExpanded(true);
        setPulsing(false);

        // THEN group changes are NOT allowed
        assertFalse(mNotifStabilityManager.isGroupChangeAllowed(mEntry));
        assertFalse(mNotifStabilityManager.isGroupPruneAllowed(mGroupEntry));

        // THEN section changes are NOT allowed
        assertFalse(mNotifStabilityManager.isSectionChangeAllowed(mEntry));
    }

    @Test
    public void testScreenTurningOn_groupAndSectionChangesNotAllowed() {
        // GIVEN the screen is turning on (still fully dozed, not sleepy)
        setFullyDozed(true);
        setSleepy(false);
        setPanelExpanded(true);
        setPulsing(false);

        // THEN group changes are NOT allowed
        assertFalse(mNotifStabilityManager.isGroupChangeAllowed(mEntry));
        assertFalse(mNotifStabilityManager.isGroupPruneAllowed(mGroupEntry));

        // THEN section changes are NOT allowed
        assertFalse(mNotifStabilityManager.isSectionChangeAllowed(mEntry));
    }

    @Test
    public void testPanelNotExpanded_groupAndSectionChangesAllowed() {
        // GIVEN screen is on but the panel isn't expanded and device isn't pulsing
        setFullyDozed(false);
        setSleepy(false);
        setPanelExpanded(false);
        setPulsing(false);

        // THEN group changes are allowed
        assertTrue(mNotifStabilityManager.isGroupChangeAllowed(mEntry));
        assertTrue(mNotifStabilityManager.isGroupPruneAllowed(mGroupEntry));

        // THEN section changes are allowed
        assertTrue(mNotifStabilityManager.isSectionChangeAllowed(mEntry));
    }

    @Test
    public void testPanelExpanded_groupAndSectionChangesNotAllowed() {
        // GIVEN the panel true expanded and device isn't pulsing
        setFullyDozed(false);
        setSleepy(false);
        setPanelExpanded(true);
        setPulsing(false);

        // THEN group changes are NOT allowed
        assertFalse(mNotifStabilityManager.isGroupChangeAllowed(mEntry));
        assertFalse(mNotifStabilityManager.isGroupPruneAllowed(mGroupEntry));

        // THEN section changes are NOT allowed
        assertFalse(mNotifStabilityManager.isSectionChangeAllowed(mEntry));
    }

    @Test
    public void testPulsing_screenOff_groupAndSectionChangesNotAllowed() {
        // GIVEN the device is pulsing and screen is off
        setFullyDozed(true);
        setSleepy(true);
        setPulsing(true);

        // THEN group changes are NOT allowed
        assertFalse(mNotifStabilityManager.isGroupChangeAllowed(mEntry));
        assertFalse(mNotifStabilityManager.isGroupPruneAllowed(mGroupEntry));

        // THEN section changes are NOT allowed
        assertFalse(mNotifStabilityManager.isSectionChangeAllowed(mEntry));
    }

    @Test
    public void testPulsing_panelNotExpanded_groupAndSectionChangesNotAllowed() {
        // GIVEN the device is pulsing and screen is off with the panel not expanded
        setFullyDozed(true);
        setSleepy(true);
        setPanelExpanded(false);
        setPulsing(true);

        // THEN group changes are NOT allowed
        assertFalse(mNotifStabilityManager.isGroupChangeAllowed(mEntry));
        assertFalse(mNotifStabilityManager.isGroupPruneAllowed(mGroupEntry));

        // THEN section changes are NOT allowed
        assertFalse(mNotifStabilityManager.isSectionChangeAllowed(mEntry));
    }

    @Test
    public void testOverrideReorderingSuppression_onlySectionChangesAllowed() {
        // GIVEN section changes typically wouldn't be allowed because the panel is expanded and
        // we're not pulsing
        setFullyDozed(false);
        setSleepy(false);
        setPanelExpanded(true);
        setPulsing(true);

        // WHEN we temporarily allow section changes for this notification entry
        mCoordinator.temporarilyAllowSectionChanges(mEntry, mFakeSystemClock.currentTimeMillis());

        // THEN group changes aren't allowed
        assertFalse(mNotifStabilityManager.isGroupChangeAllowed(mEntry));
        assertFalse(mNotifStabilityManager.isGroupPruneAllowed(mGroupEntry));

        // THEN section changes are allowed for this notification but not other notifications
        assertTrue(mNotifStabilityManager.isSectionChangeAllowed(mEntry));
        assertFalse(mNotifStabilityManager.isSectionChangeAllowed(
                new NotificationEntryBuilder()
                        .setPkg("testPkg2")
                        .build()));
    }

    @Test
    public void testTemporarilyAllowSectionChanges_callsInvalidate() {
        // GIVEN section changes typically wouldn't be allowed because the panel is expanded
        setFullyDozed(false);
        setSleepy(false);
        setPanelExpanded(true);
        setPulsing(false);

        // WHEN we temporarily allow section changes for this notification entry
        mCoordinator.temporarilyAllowSectionChanges(mEntry, mFakeSystemClock.uptimeMillis());

        // THEN the notification list is invalidated
        verifyStabilityManagerWasInvalidated(times(1));
    }

    @Test
    public void testTemporarilyAllowSectionChanges_noInvalidationCalled() {
        // GIVEN section changes typically WOULD be allowed
        setFullyDozed(true);
        setSleepy(true);
        setPanelExpanded(false);
        setPulsing(false);

        // WHEN we temporarily allow section changes for this notification entry
        mCoordinator.temporarilyAllowSectionChanges(mEntry, mFakeSystemClock.currentTimeMillis());

        // THEN invalidate is not called because this entry was never suppressed from reordering
        verifyStabilityManagerWasInvalidated(never());
    }

    @Test
    public void testTemporarilyAllowSectionChangesTimeout() {
        // GIVEN section changes typically WOULD be allowed
        setFullyDozed(true);
        setSleepy(true);
        setPanelExpanded(false);
        setPulsing(false);
        assertTrue(mNotifStabilityManager.isSectionChangeAllowed(mEntry));

        // WHEN we temporarily allow section changes for this notification entry
        mCoordinator.temporarilyAllowSectionChanges(mEntry, mFakeSystemClock.currentTimeMillis());

        // THEN invalidate is not called because this entry was never suppressed from reordering;
        // THEN section changes are allowed for this notification
        verifyStabilityManagerWasInvalidated(never());
        assertTrue(mNotifStabilityManager.isSectionChangeAllowed(mEntry));

        // WHEN we're pulsing (now disallowing reordering)
        setPulsing(true);

        // THEN we're still allowed to reorder this section because it's still in the list of
        // notifications to allow section changes
        assertTrue(mNotifStabilityManager.isSectionChangeAllowed(mEntry));

        // WHEN the timeout for the temporarily allow section reordering runnable is finsihed
        mFakeExecutor.advanceClockToNext();
        mFakeExecutor.runNextReady();

        // THEN section changes aren't allowed anymore
        assertFalse(mNotifStabilityManager.isSectionChangeAllowed(mEntry));
    }

    @Test
    public void testTemporarilyAllowSectionChanges_isPulsingChangeBeforeTimeout() {
        // GIVEN section changes typically wouldn't be allowed because the device is pulsing
        setFullyDozed(true);
        setSleepy(true);
        setPanelExpanded(false);
        setPulsing(true);

        // WHEN we temporarily allow section changes for this notification entry
        mCoordinator.temporarilyAllowSectionChanges(mEntry, mFakeSystemClock.currentTimeMillis());
        // can now reorder, so invalidates
        verifyStabilityManagerWasInvalidated(times(1));

        // WHEN reordering is now allowed because device isn't pulsing anymore
        setPulsing(false);

        // THEN invalidate isn't called a second time since reordering was already allowed
        verifyStabilityManagerWasInvalidated(times(1));
    }

    @Test
    public void testMovingVisibleHeadsUpNotAllowed() {
        // GIVEN stability enforcing conditions
        setPanelExpanded(true);
        setSleepy(false);

        // WHEN a notification is alerting and visible
        when(mHeadsUpManager.isHeadsUpEntry(mEntry.getKey())).thenReturn(true);
        when(mVisibilityLocationProvider.isInVisibleLocation(any(NotificationEntry.class)))
                .thenReturn(true);

        // VERIFY the notification cannot be reordered
        assertThat(mNotifStabilityManager.isEntryReorderingAllowed(mEntry)).isFalse();
        assertThat(mNotifStabilityManager.isSectionChangeAllowed(mEntry)).isFalse();
    }

    @Test
    public void testMovingInvisibleHeadsUpAllowed() {
        // GIVEN stability enforcing conditions
        setPanelExpanded(true);
        setSleepy(false);

        // WHEN a notification is alerting but not visible
        when(mHeadsUpManager.isHeadsUpEntry(mEntry.getKey())).thenReturn(true);
        when(mVisibilityLocationProvider.isInVisibleLocation(any(NotificationEntry.class)))
                .thenReturn(false);

        // VERIFY the notification can be reordered
        assertThat(mNotifStabilityManager.isEntryReorderingAllowed(mEntry)).isTrue();
        assertThat(mNotifStabilityManager.isSectionChangeAllowed(mEntry)).isTrue();
    }

    @Test
    public void testNeverSuppressedChanges_noInvalidationCalled() {
        // GIVEN no notifications are currently being suppressed from grouping nor being sorted

        // WHEN device isn't pulsing anymore
        setPulsing(false);

        // WHEN fully dozed
        setFullyDozed(true);

        // WHEN sleepy
        setSleepy(true);

        // WHEN panel isn't expanded
        setPanelExpanded(false);

        // THEN we never see any calls to invalidate since there weren't any notifications that
        // were being suppressed from grouping or section changes
        verifyStabilityManagerWasInvalidated(never());
    }

    @Test
    public void testNotSuppressingGroupChangesAnymore_invalidationCalled() {
        // GIVEN visual stability is being maintained b/c panel is expanded
        setPulsing(false);
        setFullyDozed(false);
        setSleepy(false);
        setPanelExpanded(true);

        assertFalse(mNotifStabilityManager.isGroupChangeAllowed(mEntry));
        assertFalse(mNotifStabilityManager.isGroupPruneAllowed(mGroupEntry));

        // WHEN the panel isn't expanded anymore
        setPanelExpanded(false);

        //  invalidate is called because we were previously suppressing a group change
        verifyStabilityManagerWasInvalidated(times(1));
    }

    @Test
    public void testNotLaunchingActivityAnymore_invalidationCalled() {
        // GIVEN visual stability is being maintained b/c animation is playing
        setActivityLaunching(true);

        assertFalse(mNotifStabilityManager.isPipelineRunAllowed());

        // WHEN the animation has stopped playing
        setActivityLaunching(false);

        // invalidate is called, b/c we were previously suppressing the pipeline from running
        verifyStabilityManagerWasInvalidated(times(1));
    }

    @Test
    public void testNotCollapsingPanelAnymore_invalidationCalled() {
        // GIVEN visual stability is being maintained b/c animation is playing
        setPanelCollapsing(true);

        assertFalse(mNotifStabilityManager.isPipelineRunAllowed());

        // WHEN the animation has stopped playing
        setPanelCollapsing(false);

        // invalidate is called, b/c we were previously suppressing the pipeline from running
        verifyStabilityManagerWasInvalidated(times(1));
    }

    @Test
    public void testNeverSuppressPipelineRunFromPanelCollapse_noInvalidationCalled() {
        // GIVEN animation is playing
        setPanelCollapsing(true);

        // WHEN the animation has stopped playing
        setPanelCollapsing(false);

        // THEN invalidate is not called, b/c nothing has been suppressed
        verifyStabilityManagerWasInvalidated(never());
    }

    @Test
    public void testNeverSuppressPipelineRunFromLaunchActivity_noInvalidationCalled() {
        // GIVEN animation is playing
        setActivityLaunching(true);

        // WHEN the animation has stopped playing
        setActivityLaunching(false);

        // THEN invalidate is not called, b/c nothing has been suppressed
        verifyStabilityManagerWasInvalidated(never());
    }

    @Test
    public void testNotSuppressingEntryReorderingAnymoreWillInvalidate() {
        // GIVEN visual stability is being maintained b/c panel is expanded
        setPulsing(false);
        setFullyDozed(false);
        setSleepy(false);
        setPanelExpanded(true);
        setCommunalShowing(false);

        assertFalse(mNotifStabilityManager.isEntryReorderingAllowed(mEntry));
        // The pipeline still has to report back that entry reordering was suppressed
        mNotifStabilityManager.onEntryReorderSuppressed();

        // WHEN the panel isn't expanded anymore
        setPanelExpanded(false);

        //  invalidate is called because we were previously suppressing an entry reorder
        verifyStabilityManagerWasInvalidated(times(1));
    }

    @Test
    public void testCommunalShowingWillNotSuppressReordering() {
        // GIVEN panel is expanded and communal is showing
        setPulsing(false);
        setFullyDozed(false);
        setSleepy(false);
        setPanelExpanded(true);
        setCommunalShowing(true);

        // Reordering should be allowed
        assertTrue(mNotifStabilityManager.isEntryReorderingAllowed(mEntry));
    }

    @Test
    public void testQueryingEntryReorderingButNotReportingReorderSuppressedDoesNotInvalidate() {
        // GIVEN visual stability is being maintained b/c panel is expanded
        setPulsing(false);
        setFullyDozed(false);
        setSleepy(false);
        setPanelExpanded(true);

        assertFalse(mNotifStabilityManager.isEntryReorderingAllowed(mEntry));

        // WHEN the panel isn't expanded anymore
        setPanelExpanded(false);

        // invalidate is not called because we were not told that an entry reorder was suppressed
        verifyStabilityManagerWasInvalidated(never());
    }

    @Test
    public void testHeadsUp_allowedToChangeGroupAndSection() {
        // GIVEN group + section changes disallowed
        setFullyDozed(false);
        setSleepy(false);
        setPanelExpanded(true);
        setPulsing(true);
        assertFalse(mNotifStabilityManager.isGroupChangeAllowed(mEntry));
        assertFalse(mNotifStabilityManager.isGroupPruneAllowed(mGroupEntry));
        assertFalse(mNotifStabilityManager.isSectionChangeAllowed(mEntry));

        // GIVEN mEntry is a HUN
        when(mHeadsUpManager.isHeadsUpEntry(mEntry.getKey())).thenReturn(true);

        // THEN group + section changes are allowed
        assertTrue(mNotifStabilityManager.isGroupChangeAllowed(mEntry));
        assertTrue(mNotifStabilityManager.isSectionChangeAllowed(mEntry));

        // BUT pruning the group for which this is the summary would still NOT be allowed.
        assertFalse(mNotifStabilityManager.isGroupPruneAllowed(mGroupEntry));
    }

    private void verifyStabilityManagerWasInvalidated(VerificationMode mode) {
        verify(mInvalidateListener, mode).onPluggableInvalidated(eq(mNotifStabilityManager), any());
    }

    private void setActivityLaunching(boolean activityLaunching) {
        mShadeAnimationInteractor.setIsLaunchingActivity(activityLaunching);
        mTestScope.getTestScheduler().runCurrent();
    }

    private void setPanelCollapsing(boolean collapsing) {
        mShadeRepository.setLegacyIsClosing(collapsing);
        mTestScope.getTestScheduler().runCurrent();
    }

    private void setCommunalShowing(boolean isShowing) {
        final MutableStateFlow<ObservableTransitionState> showingFlow =
                MutableStateFlow(
                        new ObservableTransitionState.Idle(
                                isShowing ? CommunalScenes.Communal : CommunalScenes.Blank)
                );
        mKosmos.getCommunalRepository().setTransitionState(showingFlow);
        mTestScope.getTestScheduler().runCurrent();
    }

    private void setPulsing(boolean pulsing) {
        mStatusBarStateListener.onPulsingChanged(pulsing);
    }

    private void setFullyDozed(boolean fullyDozed) {
        float dozeAmount = fullyDozed ? 1 : 0;
        mStatusBarStateListener.onDozeAmountChanged(dozeAmount, dozeAmount);
    }

    private void setSleepy(boolean sleepy) {
        if (sleepy) {
            mWakefulnessObserver.onFinishedGoingToSleep();
        } else {
            mWakefulnessObserver.onStartedWakingUp();
        }
    }

    private void setPanelExpanded(boolean expanded) {
        mStatusBarStateListener.onExpandedChanged(expanded);
    }

}
