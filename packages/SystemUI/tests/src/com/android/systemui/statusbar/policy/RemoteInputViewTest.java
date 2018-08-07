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

import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ShortcutManager;
import android.os.Handler;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.NotificationTestHelper;
import com.android.systemui.statusbar.RemoteInputController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
public class RemoteInputViewTest extends SysuiTestCase {

    private static final String TEST_RESULT_KEY = "test_result_key";
    private static final String TEST_REPLY = "hello";
    private static final String TEST_ACTION = "com.android.REMOTE_INPUT_VIEW_ACTION";

    @Mock private RemoteInputController mController;
    @Mock private ShortcutManager mShortcutManager;
    @Mock private RemoteInputQuickSettingsDisabler mRemoteInputQuickSettingsDisabler;
    private BlockingQueueIntentReceiver mReceiver;
    private RemoteInputView mView;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mDependency.injectTestDependency(RemoteInputQuickSettingsDisabler.class,
                mRemoteInputQuickSettingsDisabler);

        mReceiver = new BlockingQueueIntentReceiver();
        mContext.registerReceiver(mReceiver, new IntentFilter(TEST_ACTION), null,
                Handler.createAsync(Dependency.get(Dependency.BG_LOOPER)));

        // Avoid SecurityException RemoteInputView#sendRemoteInput().
        mContext.addMockSystemService(ShortcutManager.class, mShortcutManager);

        ExpandableNotificationRow row = new NotificationTestHelper(mContext).createRow();
        mView = RemoteInputView.inflate(mContext, null, row.getEntry(), mController);
    }

    @After
    public void tearDown() {
        mContext.unregisterReceiver(mReceiver);
    }

    @Test
    public void testSendRemoteInput_intentContainsResultsAndSource() throws InterruptedException {
        PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, 0,
                new Intent(TEST_ACTION), 0);
        RemoteInput input = new RemoteInput.Builder(TEST_RESULT_KEY).build();

        mView.setPendingIntent(pendingIntent);
        mView.setRemoteInput(new RemoteInput[]{input}, input);
        mView.focus();

        EditText editText = mView.findViewById(R.id.remote_input_text);
        editText.setText(TEST_REPLY);
        ImageButton sendButton = mView.findViewById(R.id.remote_input_send);
        sendButton.performClick();

        Intent resultIntent = mReceiver.waitForIntent();
        assertEquals(TEST_REPLY,
                RemoteInput.getResultsFromIntent(resultIntent).get(TEST_RESULT_KEY));
        assertEquals(RemoteInput.SOURCE_FREE_FORM_INPUT,
                RemoteInput.getResultsSource(resultIntent));
    }

    @Test
    public void testNoCrashWithoutVisibilityListener() {
        mView.setOnVisibilityChangedListener(null);
        mView.setVisibility(View.INVISIBLE);
        mView.setVisibility(View.VISIBLE);
    }
}
