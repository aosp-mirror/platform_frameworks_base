/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.policy;

import static android.view.ContentInfo.SOURCE_CLIPBOARD;

import static com.android.systemui.statusbar.notification.stack.StackStateAnimator.ANIMATION_DURATION_STANDARD;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ShortcutManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Process;
import android.os.UserHandle;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.ContentInfo;
import android.view.View;
import android.view.ViewRootImpl;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.window.WindowOnBackInvokedDispatcher;

import androidx.annotation.NonNull;
import androidx.core.animation.AnimatorTestRule;
import androidx.test.filters.SmallTest;

import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.testing.UiEventLoggerFake;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.flags.FakeFeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.RemoteInputController;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationTestHelper;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;
import com.android.systemui.statusbar.phone.LightBarController;

import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class RemoteInputViewTest extends SysuiTestCase {

    private static final String TEST_RESULT_KEY = "test_result_key";
    private static final String TEST_REPLY = "hello";
    private static final String TEST_ACTION = "com.android.REMOTE_INPUT_VIEW_ACTION";

    private static final String DUMMY_MESSAGE_APP_PKG =
            "com.android.sysuitest.dummynotificationsender";
    private static final int DUMMY_MESSAGE_APP_ID = Process.LAST_APPLICATION_UID - 1;

    @Mock private RemoteInputController mController;
    @Mock private ShortcutManager mShortcutManager;
    @Mock private RemoteInputQuickSettingsDisabler mRemoteInputQuickSettingsDisabler;
    @Mock private LightBarController mLightBarController;
    private BlockingQueueIntentReceiver mReceiver;
    private final UiEventLoggerFake mUiEventLoggerFake = new UiEventLoggerFake();

    @ClassRule
    public static AnimatorTestRule mAnimatorTestRule = new AnimatorTestRule();

    @Before
    public void setUp() throws Exception {
        allowTestableLooperAsMainThread();
        MockitoAnnotations.initMocks(this);

        mDependency.injectTestDependency(RemoteInputQuickSettingsDisabler.class,
                mRemoteInputQuickSettingsDisabler);
        mDependency.injectTestDependency(LightBarController.class,
                mLightBarController);
        mDependency.injectTestDependency(UiEventLogger.class, mUiEventLoggerFake);
        mDependency.injectMockDependency(NotificationRemoteInputManager.class);

        mReceiver = new BlockingQueueIntentReceiver();
        mContext.registerReceiver(mReceiver, new IntentFilter(TEST_ACTION), null,
                Handler.createAsync(Dependency.get(Dependency.BG_LOOPER)),
                Context.RECEIVER_EXPORTED_UNAUDITED);

        // Avoid SecurityException RemoteInputView#sendRemoteInput().
        mContext.addMockSystemService(ShortcutManager.class, mShortcutManager);
    }

    @After
    public void tearDown() {
        mContext.unregisterReceiver(mReceiver);
    }

    private void setTestPendingIntent(RemoteInputViewController controller) {
        PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, 0,
                new Intent(TEST_ACTION).setPackage(mContext.getPackageName()),
                PendingIntent.FLAG_MUTABLE);
        RemoteInput input = new RemoteInput.Builder(TEST_RESULT_KEY).build();
        RemoteInput[] inputs = {input};

        controller.setPendingIntent(pendingIntent);
        controller.setRemoteInput(input);
        controller.setRemoteInputs(inputs);
    }

    @Test
    public void testSendRemoteInput_intentContainsResultsAndSource() throws Exception {
        NotificationTestHelper helper = new NotificationTestHelper(
                mContext,
                mDependency,
                TestableLooper.get(this));
        ExpandableNotificationRow row = helper.createRow();
        RemoteInputView view = RemoteInputView.inflate(mContext, null, row.getEntry(), mController);
        RemoteInputViewController controller = bindController(view, row.getEntry());

        setTestPendingIntent(controller);

        view.focus();

        EditText editText = view.findViewById(R.id.remote_input_text);
        editText.setText(TEST_REPLY);
        ImageButton sendButton = view.findViewById(R.id.remote_input_send);
        sendButton.performClick();

        Intent resultIntent = mReceiver.waitForIntent();
        assertNotNull(resultIntent);
        assertEquals(TEST_REPLY,
                RemoteInput.getResultsFromIntent(resultIntent).get(TEST_RESULT_KEY));
        assertEquals(RemoteInput.SOURCE_FREE_FORM_INPUT,
                RemoteInput.getResultsSource(resultIntent));
    }

    private UserHandle getTargetInputMethodUser(UserHandle fromUser, UserHandle toUser)
            throws Exception {
        /**
         * RemoteInputView, Icon, and Bubble have the situation need to handle the other user.
         * SystemUI cross multiple user but this test(com.android.systemui.tests) doesn't cross
         * multiple user. It needs some of mocking multiple user environment to ensure the
         * createContextAsUser without throwing IllegalStateException.
         */
        Context contextSpy = spy(mContext);
        doReturn(contextSpy).when(contextSpy).createContextAsUser(any(), anyInt());
        doReturn(toUser.getIdentifier()).when(contextSpy).getUserId();

        NotificationTestHelper helper = new NotificationTestHelper(
                contextSpy,
                mDependency,
                TestableLooper.get(this));
        ExpandableNotificationRow row = helper.createRow(
                DUMMY_MESSAGE_APP_PKG,
                UserHandle.getUid(fromUser.getIdentifier(), DUMMY_MESSAGE_APP_ID),
                toUser);
        RemoteInputView view = RemoteInputView.inflate(mContext, null, row.getEntry(), mController);
        RemoteInputViewController controller = bindController(view, row.getEntry());
        EditText editText = view.findViewById(R.id.remote_input_text);

        setTestPendingIntent(controller);
        assertThat(editText.isEnabled()).isFalse();
        view.onVisibilityAggregated(true);
        assertThat(editText.isEnabled()).isTrue();

        view.focus();

        EditorInfo editorInfo = new EditorInfo();
        editorInfo.packageName = DUMMY_MESSAGE_APP_PKG;
        editorInfo.fieldId = editText.getId();
        InputConnection ic = editText.onCreateInputConnection(editorInfo);
        assertNotNull(ic);
        return editorInfo.targetInputMethodUser;
    }

    @Test
    public void testEditorInfoTargetInputMethodUserForCallingUser() throws Exception {
        UserHandle callingUser = Process.myUserHandle();
        assertEquals(callingUser, getTargetInputMethodUser(callingUser, callingUser));
    }

    @Test
    public void testEditorInfoTargetInputMethodUserForDifferentUser() throws Exception {
        UserHandle differentUser = UserHandle.of(UserHandle.getCallingUserId() + 1);
        assertEquals(differentUser, getTargetInputMethodUser(differentUser, differentUser));
    }

    @Test
    public void testEditorInfoTargetInputMethodUserForAllUser() throws Exception {
        // For the special pseudo user UserHandle.ALL, EditorInfo#targetInputMethodUser must be
        // resolved as the current user.
        UserHandle callingUser = Process.myUserHandle();
        assertEquals(UserHandle.of(ActivityManager.getCurrentUser()),
                getTargetInputMethodUser(callingUser, UserHandle.ALL));
    }

    @Test
    public void testNoCrashWithoutVisibilityListener() throws Exception {
        NotificationTestHelper helper = new NotificationTestHelper(
                mContext,
                mDependency,
                TestableLooper.get(this));
        ExpandableNotificationRow row = helper.createRow();
        RemoteInputView view = RemoteInputView.inflate(mContext, null, row.getEntry(), mController);

        view.addOnVisibilityChangedListener(null);
        view.setVisibility(View.INVISIBLE);
        view.setVisibility(View.VISIBLE);
    }

    @Test
    public void testPredictiveBack_registerAndUnregister() throws Exception {
        NotificationTestHelper helper = new NotificationTestHelper(
                mContext,
                mDependency,
                TestableLooper.get(this));
        ExpandableNotificationRow row = helper.createRow();
        RemoteInputView view = RemoteInputView.inflate(mContext, null, row.getEntry(), mController);

        ViewRootImpl viewRoot = mock(ViewRootImpl.class);
        WindowOnBackInvokedDispatcher backInvokedDispatcher = mock(
                WindowOnBackInvokedDispatcher.class);
        ArgumentCaptor<OnBackInvokedCallback> onBackInvokedCallbackCaptor = ArgumentCaptor.forClass(
                OnBackInvokedCallback.class);
        when(viewRoot.getOnBackInvokedDispatcher()).thenReturn(backInvokedDispatcher);
        view.setViewRootImpl(viewRoot);

        /* verify that predictive back callback registered when RemoteInputView becomes visible */
        view.onVisibilityAggregated(true);
        verify(backInvokedDispatcher).registerOnBackInvokedCallback(
                eq(OnBackInvokedDispatcher.PRIORITY_OVERLAY),
                onBackInvokedCallbackCaptor.capture());

        /* verify that same callback unregistered when RemoteInputView becomes invisible */
        view.onVisibilityAggregated(false);
        verify(backInvokedDispatcher).unregisterOnBackInvokedCallback(
                eq(onBackInvokedCallbackCaptor.getValue()));
    }

    @Test
    public void testUiPredictiveBack_openAndDispatchCallback() throws Exception {
        NotificationTestHelper helper = new NotificationTestHelper(
                mContext,
                mDependency,
                TestableLooper.get(this));
        ExpandableNotificationRow row = helper.createRow();
        RemoteInputView view = RemoteInputView.inflate(mContext, null, row.getEntry(), mController);
        ViewRootImpl viewRoot = mock(ViewRootImpl.class);
        WindowOnBackInvokedDispatcher backInvokedDispatcher = mock(
                WindowOnBackInvokedDispatcher.class);
        ArgumentCaptor<OnBackInvokedCallback> onBackInvokedCallbackCaptor = ArgumentCaptor.forClass(
                OnBackInvokedCallback.class);
        when(viewRoot.getOnBackInvokedDispatcher()).thenReturn(backInvokedDispatcher);
        view.setViewRootImpl(viewRoot);
        view.onVisibilityAggregated(true);
        view.setEditTextReferenceToSelf();

        /* capture the callback during registration */
        verify(backInvokedDispatcher).registerOnBackInvokedCallback(
                eq(OnBackInvokedDispatcher.PRIORITY_OVERLAY),
                onBackInvokedCallbackCaptor.capture());

        view.focus();

        /* invoke the captured callback */
        onBackInvokedCallbackCaptor.getValue().onBackInvoked();

        /* wait for RemoteInputView disappear animation to finish */
        mAnimatorTestRule.advanceTimeBy(StackStateAnimator.ANIMATION_DURATION_STANDARD);

        /* verify that the RemoteInputView goes away */
        assertEquals(view.getVisibility(), View.GONE);
    }

    @Test
    public void testUiEventLogging_openAndSend() throws Exception {
        NotificationTestHelper helper = new NotificationTestHelper(
                mContext,
                mDependency,
                TestableLooper.get(this));
        ExpandableNotificationRow row = helper.createRow();
        RemoteInputView view = RemoteInputView.inflate(mContext, null, row.getEntry(), mController);
        RemoteInputViewController controller = bindController(view, row.getEntry());

        setTestPendingIntent(controller);

        // Open view, send a reply
        view.focus();
        EditText editText = view.findViewById(R.id.remote_input_text);
        editText.setText(TEST_REPLY);
        ImageButton sendButton = view.findViewById(R.id.remote_input_send);
        sendButton.performClick();

        mReceiver.waitForIntent();

        assertEquals(2, mUiEventLoggerFake.numLogs());
        assertEquals(
                RemoteInputView.NotificationRemoteInputEvent.NOTIFICATION_REMOTE_INPUT_OPEN.getId(),
                mUiEventLoggerFake.eventId(0));
        assertEquals(
                RemoteInputView.NotificationRemoteInputEvent.NOTIFICATION_REMOTE_INPUT_SEND.getId(),
                mUiEventLoggerFake.eventId(1));
    }

    @Test
    public void testUiEventLogging_openAndAttach() throws Exception {
        NotificationTestHelper helper = new NotificationTestHelper(
                mContext,
                mDependency,
                TestableLooper.get(this));
        ExpandableNotificationRow row = helper.createRow();
        RemoteInputView view = RemoteInputView.inflate(mContext, null, row.getEntry(), mController);
        RemoteInputViewController controller = bindController(view, row.getEntry());

        setTestPendingIntent(controller);

        // Open view, attach an image
        view.focus();
        EditText editText = view.findViewById(R.id.remote_input_text);
        editText.setText(TEST_REPLY);
        ClipDescription description = new ClipDescription("", new String[] {"image/png"});
        // We need to use an (arbitrary) real resource here so that an actual image gets attached
        ClipData clip = new ClipData(description, new ClipData.Item(
                Uri.parse("android.resource://android/" + android.R.drawable.btn_default)));
        ContentInfo payload =
                new ContentInfo.Builder(clip, SOURCE_CLIPBOARD).build();
        view.setAttachment(payload);
        mReceiver.waitForIntent();

        assertEquals(2, mUiEventLoggerFake.numLogs());
        assertEquals(
                RemoteInputView.NotificationRemoteInputEvent.NOTIFICATION_REMOTE_INPUT_OPEN.getId(),
                mUiEventLoggerFake.eventId(0));
        assertEquals(
                RemoteInputView.NotificationRemoteInputEvent
                        .NOTIFICATION_REMOTE_INPUT_ATTACH_IMAGE.getId(),
                mUiEventLoggerFake.eventId(1));
    }

    @Test
    public void testFocusAnimation() throws Exception {
        NotificationTestHelper helper = new NotificationTestHelper(
                mContext,
                mDependency,
                TestableLooper.get(this));
        ExpandableNotificationRow row = helper.createRow();
        RemoteInputView view = RemoteInputView.inflate(mContext, null, row.getEntry(), mController);
        bindController(view, row.getEntry());
        view.setVisibility(View.GONE);

        View fadeOutView = new View(mContext);
        fadeOutView.setId(com.android.internal.R.id.actions_container_layout);

        FrameLayout parent = new FrameLayout(mContext);
        parent.addView(view);
        parent.addView(fadeOutView);

        // Start focus animation
        view.focusAnimated();
        assertTrue(view.isAnimatingAppearance());

        // fast forward to 1 ms before end of animation and verify fadeOutView has alpha set to 0f
        mAnimatorTestRule.advanceTimeBy(ANIMATION_DURATION_STANDARD - 1);
        assertEquals(0f, fadeOutView.getAlpha());

        // fast forward to end of animation
        mAnimatorTestRule.advanceTimeBy(1);

        // assert that fadeOutView's alpha is reset to 1f after the animation (hidden behind
        // RemoteInputView)
        assertEquals(1f, fadeOutView.getAlpha());
        assertFalse(view.isAnimatingAppearance());
        assertEquals(View.VISIBLE, view.getVisibility());
        assertEquals(1f, view.getAlpha());
    }

    @Test
    public void testDefocusAnimation() throws Exception {
        NotificationTestHelper helper = new NotificationTestHelper(
                mContext,
                mDependency,
                TestableLooper.get(this));
        ExpandableNotificationRow row = helper.createRow();
        RemoteInputView view = RemoteInputView.inflate(mContext, null, row.getEntry(), mController);
        bindController(view, row.getEntry());

        View fadeInView = new View(mContext);
        fadeInView.setId(com.android.internal.R.id.actions_container_layout);

        FrameLayout parent = new FrameLayout(mContext);
        parent.addView(view);
        parent.addView(fadeInView);

        // Start defocus animation
        view.onDefocus(true /* animate */, false /* logClose */, null /* doAfterDefocus */);
        assertEquals(View.VISIBLE, view.getVisibility());
        assertEquals(0f, fadeInView.getAlpha());

        // fast forward to end of animation
        mAnimatorTestRule.advanceTimeBy(ANIMATION_DURATION_STANDARD);

        // assert that RemoteInputView is no longer visible
        assertEquals(View.GONE, view.getVisibility());
        assertEquals(1f, fadeInView.getAlpha());
    }

    // NOTE: because we're refactoring the RemoteInputView and moving logic into the
    // RemoteInputViewController, it's easiest to just test the system of the two classes together.
    @NonNull
    private RemoteInputViewController bindController(
            RemoteInputView view,
            NotificationEntry entry) {
        FakeFeatureFlags fakeFeatureFlags = new FakeFeatureFlags();
        fakeFeatureFlags.set(Flags.NOTIFICATION_INLINE_REPLY_ANIMATION, true);
        RemoteInputViewControllerImpl viewController = new RemoteInputViewControllerImpl(
                view,
                entry,
                mRemoteInputQuickSettingsDisabler,
                mController,
                mShortcutManager,
                mUiEventLoggerFake,
                fakeFeatureFlags
                );
        viewController.bind();
        return viewController;
    }
}
