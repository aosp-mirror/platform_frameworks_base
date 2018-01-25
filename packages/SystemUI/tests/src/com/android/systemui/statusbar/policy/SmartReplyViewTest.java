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
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class SmartReplyViewTest extends SysuiTestCase {

    private static final String TEST_RESULT_KEY = "test_result_key";
    private static final String TEST_ACTION = "com.android.ACTION";
    private static final String[] TEST_CHOICES = new String[]{"Hello", "What's up?", "I'm here"};

    private BlockingQueueIntentReceiver mReceiver;
    private SmartReplyView mView;

    @Before
    public void setUp() {
        mReceiver = new BlockingQueueIntentReceiver();
        mContext.registerReceiver(mReceiver, new IntentFilter(TEST_ACTION));

        mView = SmartReplyView.inflate(mContext, null);
    }

    @Test
    public void testSendSmartReply_intentContainsResultsAndSource() throws InterruptedException {
        PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, 0,
                new Intent(TEST_ACTION), 0);
        RemoteInput input = new RemoteInput.Builder(TEST_RESULT_KEY).setChoices(
                TEST_CHOICES).build();

        mView.setRepliesFromRemoteInput(input, pendingIntent);

        mView.getChildAt(2).performClick();

        Intent resultIntent = mReceiver.waitForIntent();
        assertEquals(TEST_CHOICES[2],
                RemoteInput.getResultsFromIntent(resultIntent).get(TEST_RESULT_KEY));
        assertEquals(RemoteInput.SOURCE_CHOICE, RemoteInput.getResultsSource(resultIntent));
    }
}
