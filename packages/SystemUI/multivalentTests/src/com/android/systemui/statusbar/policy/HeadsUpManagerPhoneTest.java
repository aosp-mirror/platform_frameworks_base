/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import static com.android.systemui.log.LogBufferHelperKt.logcatLogBuffer;
import static com.android.systemui.util.concurrency.MockExecutorHandlerKt.mockExecutorHandler;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.res.R;
import com.android.systemui.shade.domain.interactor.ShadeInteractor;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.provider.VisualStabilityProvider;
import com.android.systemui.statusbar.notification.collection.render.GroupMembershipManager;
import com.android.systemui.statusbar.notification.shared.NotificationThrottleHun;
import com.android.systemui.statusbar.phone.ConfigurationControllerImpl;
import com.android.systemui.statusbar.phone.HeadsUpManagerPhone;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.kotlin.JavaAdapter;
import com.android.systemui.util.settings.GlobalSettings;
import com.android.systemui.util.time.SystemClock;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import kotlinx.coroutines.flow.StateFlowKt;

@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper
public class HeadsUpManagerPhoneTest extends BaseHeadsUpManagerTest {
    @Rule public MockitoRule rule = MockitoJUnit.rule();

    private final HeadsUpManagerLogger mHeadsUpManagerLogger = new HeadsUpManagerLogger(
            logcatLogBuffer());
    @Mock private GroupMembershipManager mGroupManager;
    @Mock private VisualStabilityProvider mVSProvider;
    @Mock private StatusBarStateController mStatusBarStateController;
    @Mock private KeyguardBypassController mBypassController;
    @Mock private ConfigurationControllerImpl mConfigurationController;
    @Mock private AccessibilityManagerWrapper mAccessibilityManagerWrapper;
    @Mock private UiEventLogger mUiEventLogger;
    @Mock private JavaAdapter mJavaAdapter;
    @Mock private ShadeInteractor mShadeInteractor;

    private static final class TestableHeadsUpManagerPhone extends HeadsUpManagerPhone {
        TestableHeadsUpManagerPhone(
                Context context,
                HeadsUpManagerLogger headsUpManagerLogger,
                GroupMembershipManager groupManager,
                VisualStabilityProvider visualStabilityProvider,
                StatusBarStateController statusBarStateController,
                KeyguardBypassController keyguardBypassController,
                ConfigurationController configurationController,
                GlobalSettings globalSettings,
                SystemClock systemClock,
                DelayableExecutor executor,
                AccessibilityManagerWrapper accessibilityManagerWrapper,
                UiEventLogger uiEventLogger,
                JavaAdapter javaAdapter,
                ShadeInteractor shadeInteractor
        ) {
            super(
                    context,
                    headsUpManagerLogger,
                    statusBarStateController,
                    keyguardBypassController,
                    groupManager,
                    visualStabilityProvider,
                    configurationController,
                    mockExecutorHandler(executor),
                    globalSettings,
                    systemClock,
                    executor,
                    accessibilityManagerWrapper,
                    uiEventLogger,
                    javaAdapter,
                    shadeInteractor
            );
            mMinimumDisplayTime = TEST_MINIMUM_DISPLAY_TIME;
            mAutoDismissTime = TEST_AUTO_DISMISS_TIME;
        }
    }

    private HeadsUpManagerPhone createHeadsUpManagerPhone() {
        return new TestableHeadsUpManagerPhone(
                mContext,
                mHeadsUpManagerLogger,
                mGroupManager,
                mVSProvider,
                mStatusBarStateController,
                mBypassController,
                mConfigurationController,
                mGlobalSettings,
                mSystemClock,
                mExecutor,
                mAccessibilityManagerWrapper,
                mUiEventLogger,
                mJavaAdapter,
                mShadeInteractor
        );
    }

    @Before
    public void setUp() {
        mSetFlagsRule.disableFlags(NotificationThrottleHun.FLAG_NAME);

        when(mShadeInteractor.isAnyExpanded()).thenReturn(StateFlowKt.MutableStateFlow(false));
        final AccessibilityManagerWrapper accessibilityMgr =
                mDependency.injectMockDependency(AccessibilityManagerWrapper.class);
        when(accessibilityMgr.getRecommendedTimeoutMillis(anyInt(), anyInt()))
                .thenReturn(TEST_AUTO_DISMISS_TIME);
        when(mVSProvider.isReorderingAllowed()).thenReturn(true);
        mDependency.injectMockDependency(NotificationShadeWindowController.class);
        mContext.getOrCreateTestableResources().addOverride(
                R.integer.ambient_notification_extension_time, 500);
    }

    @Test
    public void testSnooze() {
        final HeadsUpManager hmp = createHeadsUpManagerPhone();
        final NotificationEntry entry = HeadsUpManagerTestUtil.createEntry(/* id = */ 0, mContext);

        hmp.showNotification(entry);
        hmp.snooze();

        assertTrue(hmp.isSnoozed(entry.getSbn().getPackageName()));
    }

    @Test
    public void testSwipedOutNotification() {
        final HeadsUpManager hmp = createHeadsUpManagerPhone();
        final NotificationEntry entry = HeadsUpManagerTestUtil.createEntry(/* id = */ 0, mContext);

        hmp.showNotification(entry);
        hmp.addSwipedOutNotification(entry.getKey());

        // Remove should succeed because the notification is swiped out
        final boolean removedImmediately = hmp.removeNotification(entry.getKey(),
                /* releaseImmediately = */ false);

        assertTrue(removedImmediately);
        assertFalse(hmp.isHeadsUpEntry(entry.getKey()));
    }

    @Test
    public void testCanRemoveImmediately_swipedOut() {
        final HeadsUpManager hmp = createHeadsUpManagerPhone();
        final NotificationEntry entry = HeadsUpManagerTestUtil.createEntry(/* id = */ 0, mContext);

        hmp.showNotification(entry);
        hmp.addSwipedOutNotification(entry.getKey());

        // Notification is swiped so it can be immediately removed.
        assertTrue(hmp.canRemoveImmediately(entry.getKey()));
    }

    @Ignore("b/141538055")
    @Test
    public void testCanRemoveImmediately_notTopEntry() {
        final HeadsUpManager hmp = createHeadsUpManagerPhone();
        final NotificationEntry earlierEntry =
                HeadsUpManagerTestUtil.createEntry(/* id = */ 0, mContext);
        final NotificationEntry laterEntry =
                HeadsUpManagerTestUtil.createEntry(/* id = */ 1, mContext);
        laterEntry.setRow(mRow);

        hmp.showNotification(earlierEntry);
        hmp.showNotification(laterEntry);

        // Notification is "behind" a higher priority notification so we can remove it immediately.
        assertTrue(hmp.canRemoveImmediately(earlierEntry.getKey()));
    }

    @Test
    public void testExtendHeadsUp() {
        final HeadsUpManagerPhone hmp = createHeadsUpManagerPhone();
        final NotificationEntry entry = HeadsUpManagerTestUtil.createEntry(/* id = */ 0, mContext);

        hmp.showNotification(entry);
        hmp.extendHeadsUp();
        mSystemClock.advanceTime(TEST_AUTO_DISMISS_TIME + hmp.mExtensionTime / 2);

        assertTrue(hmp.isHeadsUpEntry(entry.getKey()));
    }
}
