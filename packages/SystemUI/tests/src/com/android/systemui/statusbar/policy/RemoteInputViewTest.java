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

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;

import android.app.ActivityManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ShortcutManager;
import android.os.Handler;
import android.os.Process;
import android.os.UserHandle;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;
import android.widget.ImageButton;

import androidx.test.filters.SmallTest;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.RemoteInputController;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationTestHelper;
import com.android.systemui.statusbar.phone.LightBarController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
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
    private RemoteInputView mView;

    @Before
    public void setUp() throws Exception {
        allowTestableLooperAsMainThread();
        MockitoAnnotations.initMocks(this);

        mDependency.injectTestDependency(RemoteInputQuickSettingsDisabler.class,
                mRemoteInputQuickSettingsDisabler);
        mDependency.injectTestDependency(LightBarController.class,
                mLightBarController);

        mReceiver = new BlockingQueueIntentReceiver();
        mContext.registerReceiver(mReceiver, new IntentFilter(TEST_ACTION), null,
                Handler.createAsync(Dependency.get(Dependency.BG_LOOPER)));

        // Avoid SecurityException RemoteInputView#sendRemoteInput().
        mContext.addMockSystemService(ShortcutManager.class, mShortcutManager);
    }

    @After
    public void tearDown() {
        mContext.unregisterReceiver(mReceiver);
    }

    private void setTestPendingIntent(RemoteInputView view) {
        PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, 0,
                new Intent(TEST_ACTION), 0);
        RemoteInput input = new RemoteInput.Builder(TEST_RESULT_KEY).build();

        view.setPendingIntent(pendingIntent);
        view.setRemoteInput(new RemoteInput[]{input}, input, null /* editedSuggestionInfo */);
    }

    @Test
    public void testSendRemoteInput_intentContainsResultsAndSource() throws Exception {
        NotificationTestHelper helper = new NotificationTestHelper(
                mContext,
                mDependency,
                TestableLooper.get(this));
        ExpandableNotificationRow row = helper.createRow();
        RemoteInputView view = RemoteInputView.inflate(mContext, null, row.getEntry(), mController);

        setTestPendingIntent(view);

        view.focus();

        EditText editText = view.findViewById(R.id.remote_input_text);
        editText.setText(TEST_REPLY);
        ImageButton sendButton = view.findViewById(R.id.remote_input_send);
        sendButton.performClick();

        Intent resultIntent = mReceiver.waitForIntent();
        assertEquals(TEST_REPLY,
                RemoteInput.getResultsFromIntent(resultIntent).get(TEST_RESULT_KEY));
        assertEquals(RemoteInput.SOURCE_FREE_FORM_INPUT,
                RemoteInput.getResultsSource(resultIntent));
    }

    private UserHandle getTargetInputMethodUser(UserHandle fromUser, UserHandle toUser)
            throws Exception {
        NotificationTestHelper helper = new NotificationTestHelper(
                mContext,
                mDependency,
                TestableLooper.get(this));
        ExpandableNotificationRow row = helper.createRow(
                DUMMY_MESSAGE_APP_PKG,
                UserHandle.getUid(fromUser.getIdentifier(), DUMMY_MESSAGE_APP_ID),
                toUser);
        RemoteInputView view = RemoteInputView.inflate(mContext, null, row.getEntry(), mController);

        setTestPendingIntent(view);

        view.focus();

        EditText editText = view.findViewById(R.id.remote_input_text);
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

        view.setOnVisibilityChangedListener(null);
        view.setVisibility(View.INVISIBLE);
        view.setVisibility(View.VISIBLE);
    }
}
