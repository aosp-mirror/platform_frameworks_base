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

package com.android.systemui.accessibility.floatingmenu;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.view.WindowInsets.Type.displayCutout;
import static android.view.WindowInsets.Type.ime;
import static android.view.WindowInsets.Type.systemBars;

import static com.android.systemui.accessibility.floatingmenu.MenuNotificationFactory.ACTION_DELETE;
import static com.android.systemui.accessibility.floatingmenu.MenuNotificationFactory.ACTION_UNDO;
import static com.android.systemui.accessibility.floatingmenu.MenuViewLayer.LayerIndex;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Insets;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Build;
import android.os.UserHandle;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.provider.Settings;
import android.testing.TestableLooper;
import android.util.ArraySet;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityManager;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.accessibility.common.ShortcutConstants;
import com.android.internal.accessibility.dialog.AccessibilityTarget;
import com.android.internal.messages.nano.SystemMessageProto;
import com.android.systemui.Flags;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.SysuiTestableContext;
import com.android.systemui.accessibility.utils.TestUtils;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.res.R;
import com.android.systemui.util.settings.SecureSettings;
import com.android.wm.shell.shared.magnetictarget.MagnetizedObject;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.List;

/** Tests for {@link MenuViewLayer}. */
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
public class MenuViewLayerTest extends SysuiTestCase {
    private static final String SELECT_TO_SPEAK_PACKAGE_NAME = "com.google.android.marvin.talkback";
    private static final String SELECT_TO_SPEAK_SERVICE_NAME =
            "com.google.android.accessibility.selecttospeak.SelectToSpeakService";
    private static final ComponentName TEST_SELECT_TO_SPEAK_COMPONENT_NAME = new ComponentName(
            SELECT_TO_SPEAK_PACKAGE_NAME, SELECT_TO_SPEAK_SERVICE_NAME);

    private static final int DISPLAY_WINDOW_WIDTH = 1080;
    private static final int DISPLAY_WINDOW_HEIGHT = 2340;
    private static final int STATUS_BAR_HEIGHT = 75;
    private static final int NAVIGATION_BAR_HEIGHT = 125;
    private static final int IME_HEIGHT = 350;
    private static final int IME_TOP =
            DISPLAY_WINDOW_HEIGHT - STATUS_BAR_HEIGHT - NAVIGATION_BAR_HEIGHT - IME_HEIGHT;

    private MenuViewLayer mMenuViewLayer;
    private String mLastAccessibilityButtonTargets;
    private String mLastEnabledAccessibilityServices;
    private WindowMetrics mWindowMetrics;
    private MenuViewModel mMenuViewModel;
    private MenuView mMenuView;
    private MenuAnimationController mMenuAnimationController;

    @Rule
    public MockitoRule mockito = MockitoJUnit.rule();

    @Spy
    private SysuiTestableContext mSpyContext = getContext();
    @Mock
    private IAccessibilityFloatingMenu mFloatingMenu;
    @Mock
    private WindowManager mStubWindowManager;
    @Mock
    private AccessibilityManager mStubAccessibilityManager;
    @Mock
    private PackageManager mMockPackageManager;
    private final SecureSettings mSecureSettings = TestUtils.mockSecureSettings();

    private final NotificationManager mMockNotificationManager = mock(NotificationManager.class);

    private final ArgumentMatcher<IntentFilter> mNotificationMatcher =
            (arg) -> arg.hasAction(ACTION_UNDO) && arg.hasAction(ACTION_DELETE);

    @Before
    public void setUp() throws Exception {
        mSpyContext.addMockSystemService(Context.NOTIFICATION_SERVICE, mMockNotificationManager);

        final Rect mDisplayBounds = new Rect();
        mDisplayBounds.set(/* left= */ 0, /* top= */ 0, DISPLAY_WINDOW_WIDTH,
                DISPLAY_WINDOW_HEIGHT);
        mWindowMetrics = spy(
                new WindowMetrics(mDisplayBounds, fakeDisplayInsets(), /* density = */ 0.0f));
        doReturn(mWindowMetrics).when(mStubWindowManager).getCurrentWindowMetrics();

        mMenuViewModel = new MenuViewModel(
                mSpyContext, mStubAccessibilityManager, mSecureSettings);
        MenuViewAppearance menuViewAppearance = new MenuViewAppearance(
                mSpyContext, mStubWindowManager);
        mMenuView = spy(
                new MenuView(mSpyContext, mMenuViewModel, menuViewAppearance, mSecureSettings));
        // Ensure tests don't actually update metrics.
        doNothing().when(mMenuView).incrementTexMetric(any());

        mMenuViewLayer = spy(new MenuViewLayer(mSpyContext, mStubWindowManager,
                mStubAccessibilityManager, mMenuViewModel, menuViewAppearance, mMenuView,
                mFloatingMenu, mSecureSettings, mock(NavigationModeController.class)));
        mMenuAnimationController = mMenuView.getMenuAnimationController();

        doNothing().when(mSpyContext).startActivity(any());
        doNothing().when(mSpyContext).startActivityAsUser(any(), any());
        when(mSpyContext.getPackageManager()).thenReturn(mMockPackageManager);

        mLastAccessibilityButtonTargets =
                Settings.Secure.getStringForUser(mSpyContext.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS, UserHandle.USER_CURRENT);
        mLastEnabledAccessibilityServices =
                Settings.Secure.getStringForUser(mSpyContext.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, UserHandle.USER_CURRENT);

        mMenuViewLayer.onAttachedToWindow();
        Settings.Secure.putStringForUser(mSpyContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS, "", UserHandle.USER_CURRENT);
        Settings.Secure.putStringForUser(mSpyContext.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "", UserHandle.USER_CURRENT);
    }

    @After
    public void tearDown() throws Exception {
        Settings.Secure.putStringForUser(mSpyContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS, mLastAccessibilityButtonTargets,
                UserHandle.USER_CURRENT);
        Settings.Secure.putStringForUser(mSpyContext.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, mLastEnabledAccessibilityServices,
                UserHandle.USER_CURRENT);

        mMenuView.updateMenuMoveToTucked(/* isMoveToTucked= */ false);
        mMenuAnimationController.mPositionAnimations.values().forEach(DynamicAnimation::cancel);
        mMenuViewLayer.onDetachedFromWindow();
    }

    @Test
    public void onAttachedToWindow_menuIsVisible() {
        mMenuViewLayer.onAttachedToWindow();

        final View menuView = mMenuViewLayer.getChildAt(LayerIndex.MENU_VIEW);
        assertThat(menuView.getVisibility()).isEqualTo(VISIBLE);
    }

    @Test
    public void onDetachedFromWindow_menuIsGone() {
        mMenuViewLayer.onDetachedFromWindow();

        final View menuView = mMenuViewLayer.getChildAt(LayerIndex.MENU_VIEW);
        assertThat(menuView.getVisibility()).isEqualTo(GONE);
    }

    @Test
    public void triggerDismissMenuAction_hideFloatingMenu() {
        mMenuViewLayer.mDismissMenuAction.run();

        verify(mFloatingMenu).hide();
    }

    @Test
    @EnableFlags(android.view.accessibility.Flags.FLAG_A11Y_QS_SHORTCUT)
    public void triggerDismissMenuAction_callsA11yManagerEnableShortcutsForTargets() {
        final List<String> stubShortcutTargets = new ArrayList<>();
        stubShortcutTargets.add(TEST_SELECT_TO_SPEAK_COMPONENT_NAME.flattenToString());
        when(mStubAccessibilityManager.getAccessibilityShortcutTargets(
                ShortcutConstants.UserShortcutType.SOFTWARE)).thenReturn(stubShortcutTargets);

        mMenuViewLayer.mDismissMenuAction.run();

        verify(mStubAccessibilityManager).enableShortcutsForTargets(
                /* enable= */ false,
                ShortcutConstants.UserShortcutType.SOFTWARE,
                new ArraySet<>(stubShortcutTargets),
                mSecureSettings.getRealUserHandle(UserHandle.USER_CURRENT));
    }

    @Test
    @DisableFlags(android.view.accessibility.Flags.FLAG_A11Y_QS_SHORTCUT)
    public void triggerDismissMenuAction_matchA11yButtonTargetsResult() {
        mMenuViewLayer.mDismissMenuAction.run();
        verify(mSecureSettings).putStringForUser(
                Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS, /* value= */ "",
                UserHandle.USER_CURRENT);
    }

    @Test
    @DisableFlags(android.view.accessibility.Flags.FLAG_A11Y_QS_SHORTCUT)
    public void triggerDismissMenuAction_matchEnabledA11yServicesResult() {
        setupEnabledAccessibilityServiceList();

        mMenuViewLayer.mDismissMenuAction.run();
        final String value = Settings.Secure.getStringForUser(mSpyContext.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                mSecureSettings.getRealUserHandle(UserHandle.USER_CURRENT));

        assertThat(value).isEqualTo("");
    }

    @Test
    @DisableFlags(android.view.accessibility.Flags.FLAG_A11Y_QS_SHORTCUT)
    public void triggerDismissMenuAction_hasHardwareKeyShortcut_keepEnabledStatus() {
        setupEnabledAccessibilityServiceList();
        final List<String> stubShortcutTargets = new ArrayList<>();
        stubShortcutTargets.add(TEST_SELECT_TO_SPEAK_COMPONENT_NAME.flattenToString());
        when(mStubAccessibilityManager.getAccessibilityShortcutTargets(
                ShortcutConstants.UserShortcutType.HARDWARE)).thenReturn(stubShortcutTargets);

        mMenuViewLayer.mDismissMenuAction.run();
        final String value = Settings.Secure.getStringForUser(mSpyContext.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                mSecureSettings.getRealUserHandle(UserHandle.USER_CURRENT));

        assertThat(value).isEqualTo(TEST_SELECT_TO_SPEAK_COMPONENT_NAME.flattenToString());
    }

    @Test
    @EnableFlags(Flags.FLAG_FLOATING_MENU_DRAG_TO_EDIT)
    public void onEditAction_startsActivity() {
        mockActivityQuery(true);
        mMenuViewLayer.dispatchAccessibilityAction(R.id.action_edit);
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mSpyContext).startActivityAsUser(intentCaptor.capture(), eq(UserHandle.CURRENT));
        assertThat(intentCaptor.getValue().getAction()).isEqualTo(
                mMenuViewLayer.getIntentForEditScreen().getAction());
    }

    @Test
    @EnableFlags(Flags.FLAG_FLOATING_MENU_DRAG_TO_EDIT)
    public void onEditAction_noResolve_doesNotStart() {
        mockActivityQuery(false);
        mMenuViewLayer.dispatchAccessibilityAction(R.id.action_edit);
        verify(mSpyContext, never()).startActivity(any());
        verify(mSpyContext, never()).startActivityAsUser(any(), any());
    }

    @Test
    public void getIntentForEditScreen_validate() {
        Intent intent = mMenuViewLayer.getIntentForEditScreen();
        String[] targets = intent.getBundleExtra(
                ":settings:show_fragment_args").getStringArray("targets");

        assertThat(intent.getAction()).isEqualTo(Settings.ACTION_ACCESSIBILITY_SHORTCUT_SETTINGS);
        assertThat(targets).asList().containsExactlyElementsIn(TestUtils.TEST_BUTTON_TARGETS);
    }

    @Test
    @EnableFlags(Flags.FLAG_FLOATING_MENU_DRAG_TO_HIDE)
    public void onDismissAction_hideMenuAndShowNotification() {
        mMenuViewLayer.dispatchAccessibilityAction(R.id.action_remove_menu);
        verify(mMenuViewLayer).hideMenuAndShowNotification();
    }

    @Test
    @DisableFlags(Flags.FLAG_FLOATING_MENU_DRAG_TO_HIDE)
    public void onDismissAction_hideMenuAndShowMessage() {
        mMenuViewLayer.dispatchAccessibilityAction(R.id.action_remove_menu);
        verify(mMenuViewLayer).hideMenuAndShowMessage();
    }

    @Test
    public void showingImeInsetsChange_notOverlapOnIme_menuKeepOriginalPosition() {
        final float menuTop = STATUS_BAR_HEIGHT + 100;
        mMenuAnimationController.moveAndPersistPosition(new PointF(0, menuTop));

        dispatchShowingImeInsets();

        assertThat(mMenuView.getTranslationX()).isEqualTo(0);
        assertThat(mMenuView.getTranslationY()).isEqualTo(menuTop);
    }

    @Test
    public void showingImeInsetsChange_overlapOnIme_menuShownAboveIme() {
        mMenuAnimationController.moveAndPersistPosition(new PointF(0, IME_TOP + 100));
        final PointF beforePosition = mMenuView.getMenuPosition();

        dispatchShowingImeInsets();
        assertThat(isPositionAnimationRunning()).isTrue();
        skipPositionAnimations();

        final float menuBottom = mMenuView.getTranslationY() + mMenuView.getMenuHeight();

        assertThat(mMenuView.getTranslationX()).isEqualTo(beforePosition.x);
        assertThat(menuBottom).isLessThan(beforePosition.y);
    }

    @Test
    public void hidingImeInsetsChange_overlapOnIme_menuBackToOriginalPosition() {
        mMenuAnimationController.moveAndPersistPosition(new PointF(0, IME_TOP + 200));
        final PointF beforePosition = mMenuView.getMenuPosition();

        dispatchShowingImeInsets();
        assertThat(isPositionAnimationRunning()).isTrue();
        skipPositionAnimations();

        dispatchHidingImeInsets();
        assertThat(isPositionAnimationRunning()).isTrue();
        skipPositionAnimations();

        assertThat(mMenuView.getTranslationX()).isEqualTo(beforePosition.x);
        assertThat(mMenuView.getTranslationY()).isEqualTo(beforePosition.y);
    }
    @Test
    @EnableFlags(Flags.FLAG_FLOATING_MENU_DRAG_TO_HIDE)
    public void onReleasedInTarget_hideMenuAndShowNotificationWithExpectedActions() {
        dragMenuThenReleasedInTarget(R.id.action_remove_menu);

        verify(mMockNotificationManager).notify(
                eq(SystemMessageProto.SystemMessage.NOTE_A11Y_FLOATING_MENU_HIDDEN),
                any(Notification.class));
        verify(mSpyContext).registerReceiver(
                any(BroadcastReceiver.class), argThat(mNotificationMatcher), anyInt());
    }

    @Test
    @EnableFlags(Flags.FLAG_FLOATING_MENU_DRAG_TO_HIDE)
    public void receiveActionUndo_dismissNotificationAndMenuVisible() {
        ArgumentCaptor<BroadcastReceiver> broadcastReceiverCaptor = ArgumentCaptor.forClass(
                BroadcastReceiver.class);
        dragMenuThenReleasedInTarget(R.id.action_remove_menu);

        verify(mSpyContext).registerReceiver(broadcastReceiverCaptor.capture(),
                argThat(mNotificationMatcher), anyInt());
        broadcastReceiverCaptor.getValue().onReceive(mSpyContext, new Intent(ACTION_UNDO));

        verify(mSpyContext).unregisterReceiver(broadcastReceiverCaptor.getValue());
        verify(mMockNotificationManager).cancel(
                SystemMessageProto.SystemMessage.NOTE_A11Y_FLOATING_MENU_HIDDEN);
        assertThat(mMenuView.getVisibility()).isEqualTo(VISIBLE);
    }

    @Test
    @EnableFlags(Flags.FLAG_FLOATING_MENU_DRAG_TO_HIDE)
    public void receiveActionDelete_dismissNotificationAndHideMenu() {
        ArgumentCaptor<BroadcastReceiver> broadcastReceiverCaptor = ArgumentCaptor.forClass(
                BroadcastReceiver.class);
        dragMenuThenReleasedInTarget(R.id.action_remove_menu);

        verify(mSpyContext).registerReceiver(broadcastReceiverCaptor.capture(),
                argThat(mNotificationMatcher), anyInt());
        broadcastReceiverCaptor.getValue().onReceive(mSpyContext, new Intent(ACTION_DELETE));

        verify(mSpyContext).unregisterReceiver(broadcastReceiverCaptor.getValue());
        verify(mMockNotificationManager).cancel(
                SystemMessageProto.SystemMessage.NOTE_A11Y_FLOATING_MENU_HIDDEN);
        verify(mFloatingMenu).hide();
    }

    @Test
    @EnableFlags(Flags.FLAG_FLOATING_MENU_DRAG_TO_EDIT)
    public void onDismissAction_incrementsTexMetricDismiss() {
        mMenuViewModel.onTargetFeaturesChanged(
                List.of(new TestAccessibilityTarget(mSpyContext, 1234),
                        new TestAccessibilityTarget(mSpyContext, 5678)));

        mMenuViewLayer.dispatchAccessibilityAction(R.id.action_remove_menu);

        verify(mMenuView, times(1)).incrementTexMetric(eq(MenuViewLayer.TEX_METRIC_DISMISS));
    }

    @Test
    @EnableFlags(Flags.FLAG_FLOATING_MENU_DRAG_TO_EDIT)
    public void onEditAction_incrementsTexMetricEdit() {
        mMenuViewModel.onTargetFeaturesChanged(
                List.of(new TestAccessibilityTarget(mSpyContext, 1234),
                        new TestAccessibilityTarget(mSpyContext, 5678)));

        mMenuViewLayer.dispatchAccessibilityAction(R.id.action_edit);

        verify(mMenuView, times(1)).incrementTexMetric(eq(MenuViewLayer.TEX_METRIC_EDIT));
    }

    /** Simplified AccessibilityTarget for testing MenuViewLayer. */
    private static class TestAccessibilityTarget extends AccessibilityTarget {
        TestAccessibilityTarget(Context context, int uid) {
            // Set fields unused by tests to defaults that allow test compilation.
            super(context, ShortcutConstants.UserShortcutType.SOFTWARE, 0, false,
                    TEST_SELECT_TO_SPEAK_COMPONENT_NAME.flattenToString(), uid, null, null, null);
        }
    }

    private void setupEnabledAccessibilityServiceList() {
        Settings.Secure.putStringForUser(mSpyContext.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                TEST_SELECT_TO_SPEAK_COMPONENT_NAME.flattenToString(),
                mSecureSettings.getRealUserHandle(UserHandle.USER_CURRENT)
        );

        final ResolveInfo resolveInfo = new ResolveInfo();
        final ServiceInfo serviceInfo = new ServiceInfo();
        final ApplicationInfo applicationInfo = new ApplicationInfo();
        resolveInfo.serviceInfo = serviceInfo;
        serviceInfo.applicationInfo = applicationInfo;
        applicationInfo.targetSdkVersion = Build.VERSION_CODES.R;
        final AccessibilityServiceInfo accessibilityServiceInfo = new AccessibilityServiceInfo();
        accessibilityServiceInfo.setResolveInfo(resolveInfo);
        accessibilityServiceInfo.flags = AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON;
        final List<AccessibilityServiceInfo> serviceInfoList = new ArrayList<>();
        accessibilityServiceInfo.setComponentName(TEST_SELECT_TO_SPEAK_COMPONENT_NAME);
        serviceInfoList.add(accessibilityServiceInfo);
        when(mStubAccessibilityManager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK)).thenReturn(serviceInfoList);
    }

    private void dispatchShowingImeInsets() {
        final WindowInsets fakeShowingImeInsets = fakeImeInsets(/* isImeVisible= */ true);
        doReturn(fakeShowingImeInsets).when(mWindowMetrics).getWindowInsets();
        mMenuViewLayer.dispatchApplyWindowInsets(fakeShowingImeInsets);
    }

    private void dispatchHidingImeInsets() {
        final WindowInsets fakeHidingImeInsets = fakeImeInsets(/* isImeVisible= */ false);
        doReturn(fakeHidingImeInsets).when(mWindowMetrics).getWindowInsets();
        mMenuViewLayer.dispatchApplyWindowInsets(fakeHidingImeInsets);
    }

    private WindowInsets fakeDisplayInsets() {
        return new WindowInsets.Builder()
                .setVisible(systemBars() | displayCutout(), /* visible= */ true)
                .setInsets(systemBars() | displayCutout(),
                        Insets.of(/* left= */ 0, STATUS_BAR_HEIGHT, /* right= */ 0,
                                NAVIGATION_BAR_HEIGHT))
                .build();
    }

    private WindowInsets fakeImeInsets(boolean isImeVisible) {
        final int bottom = isImeVisible ? (IME_HEIGHT + NAVIGATION_BAR_HEIGHT) : 0;
        return new WindowInsets.Builder()
                .setVisible(ime(), isImeVisible)
                .setInsets(ime(),
                        Insets.of(/* left= */ 0, /* top= */ 0, /* right= */ 0, bottom))
                .build();
    }

    private boolean isPositionAnimationRunning() {
        return !mMenuAnimationController.mPositionAnimations.values().stream().filter(
                        (animation) -> animation.isRunning()).findAny().isEmpty();
    }

    private void skipPositionAnimations() {
        mMenuAnimationController.mPositionAnimations.values().stream().forEach(
                (animation) -> {
                    final SpringAnimation springAnimation = ((SpringAnimation) animation);
                    // The doAnimationFrame function is used for skipping animation to the end.
                    springAnimation.doAnimationFrame(500);
                    springAnimation.skipToEnd();
                    springAnimation.doAnimationFrame(500);
                });
    }

    private void dragMenuThenReleasedInTarget(int id) {
        MagnetizedObject.MagnetListener magnetListener =
                mMenuViewLayer.getDragToInteractAnimationController().getMagnetListener(id);
        View view = mock(View.class);
        when(view.getId()).thenReturn(id);
        magnetListener.onReleasedInTarget(
                new MagnetizedObject.MagneticTarget(view, 200), mock(MagnetizedObject.class));
    }

    private void mockActivityQuery(boolean successfulQuery) {
        // Query just needs to return a non-empty set to be successful.
        ArrayList<ResolveInfo> resolveInfos = new ArrayList<>();
        if (successfulQuery) {
            resolveInfos.add(new ResolveInfo());
        }
        when(mMockPackageManager.queryIntentActivities(
                any(), any(PackageManager.ResolveInfoFlags.class))).thenReturn(resolveInfos);
    }

}
