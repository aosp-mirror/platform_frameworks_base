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

import static android.view.View.MeasureSpec;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.fail;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.service.notification.StatusBarNotification;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

import com.android.keyguard.KeyguardHostView.OnDismissAction;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.SmartReplyController;
import com.android.systemui.statusbar.phone.KeyguardDismissUtil;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class SmartReplyViewTest extends SysuiTestCase {
    private static final String TEST_RESULT_KEY = "test_result_key";
    private static final String TEST_ACTION = "com.android.SMART_REPLY_VIEW_ACTION";

    private static final String[] TEST_CHOICES = new String[]{"Hello", "What's up?", "I'm here"};
    private static final String TEST_NOTIFICATION_KEY = "akey";

    private static final int WIDTH_SPEC = MeasureSpec.makeMeasureSpec(500, MeasureSpec.EXACTLY);
    private static final int HEIGHT_SPEC = MeasureSpec.makeMeasureSpec(400, MeasureSpec.AT_MOST);

    private BlockingQueueIntentReceiver mReceiver;
    private SmartReplyView mView;
    private View mContainer;

    private int mSingleLinePaddingHorizontal;
    private int mDoubleLinePaddingHorizontal;
    private int mSpacing;

    @Mock private SmartReplyController mLogger;
    private NotificationData.Entry mEntry;
    private Notification mNotification;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mReceiver = new BlockingQueueIntentReceiver();
        mContext.registerReceiver(mReceiver, new IntentFilter(TEST_ACTION));
        mDependency.get(KeyguardDismissUtil.class).setDismissHandler(
            (action, cancelAction, afterKeyguardGone) -> action.onDismiss());

        mContainer = new View(mContext, null);
        mView = SmartReplyView.inflate(mContext, null);


        final Resources res = mContext.getResources();
        mSingleLinePaddingHorizontal = res.getDimensionPixelSize(
                R.dimen.smart_reply_button_padding_horizontal_single_line);
        mDoubleLinePaddingHorizontal = res.getDimensionPixelSize(
                R.dimen.smart_reply_button_padding_horizontal_double_line);
        mSpacing = res.getDimensionPixelSize(R.dimen.smart_reply_button_spacing);

        mNotification = new Notification.Builder(mContext, "")
                .setSmallIcon(R.drawable.ic_person)
                .setContentTitle("Title")
                .setContentText("Text").build();
        StatusBarNotification sbn = mock(StatusBarNotification.class);
        when(sbn.getNotification()).thenReturn(mNotification);
        when(sbn.getKey()).thenReturn(TEST_NOTIFICATION_KEY);
        mEntry = new NotificationData.Entry(sbn);
    }

    @After
    public void tearDown() {
        mContext.unregisterReceiver(mReceiver);
    }

    @Test
    public void testSendSmartReply_intentContainsResultsAndSource() throws InterruptedException {
        setRepliesFromRemoteInput(TEST_CHOICES);

        mView.getChildAt(2).performClick();

        Intent resultIntent = mReceiver.waitForIntent();
        assertEquals(TEST_CHOICES[2],
                RemoteInput.getResultsFromIntent(resultIntent).get(TEST_RESULT_KEY));
        assertEquals(RemoteInput.SOURCE_CHOICE, RemoteInput.getResultsSource(resultIntent));
    }

    @Test
    public void testSendSmartReply_keyguardCancelled() throws InterruptedException {
        mDependency.get(KeyguardDismissUtil.class).setDismissHandler(
            (action, cancelAction, afterKeyguardGone) -> {
                if (cancelAction != null) {
                    cancelAction.run();
                }
            });
        setRepliesFromRemoteInput(TEST_CHOICES);

        mView.getChildAt(2).performClick();

        assertNull(mReceiver.waitForIntent());
    }

    @Test
    public void testSendSmartReply_waitsForKeyguard() throws InterruptedException {
        AtomicReference<OnDismissAction> actionRef = new AtomicReference<>();
        mDependency.get(KeyguardDismissUtil.class).setDismissHandler(
            (action, cancelAction, afterKeyguardGone) -> actionRef.set(action));
        setRepliesFromRemoteInput(TEST_CHOICES);

        mView.getChildAt(2).performClick();

        // No intent until the screen is unlocked.
        assertNull(mReceiver.waitForIntent());

        actionRef.get().onDismiss();

        // Now the intent should arrive.
        Intent resultIntent = mReceiver.waitForIntent();
        assertEquals(TEST_CHOICES[2],
                RemoteInput.getResultsFromIntent(resultIntent).get(TEST_RESULT_KEY));
        assertEquals(RemoteInput.SOURCE_CHOICE, RemoteInput.getResultsSource(resultIntent));
    }

    @Test
    public void testSendSmartReply_controllerCalled() {
        setRepliesFromRemoteInput(TEST_CHOICES);
        mView.getChildAt(2).performClick();
        verify(mLogger).smartReplySent(mEntry, 2, TEST_CHOICES[2]);
    }

    @Test
    public void testSendSmartReply_hidesContainer() {
        mContainer.setVisibility(View.VISIBLE);
        setRepliesFromRemoteInput(TEST_CHOICES);
        mView.getChildAt(0).performClick();
        assertEquals(View.GONE, mContainer.getVisibility());
    }

    @Test
    public void testMeasure_empty() {
        mView.measure(WIDTH_SPEC, HEIGHT_SPEC);
        assertEquals(500, mView.getMeasuredWidthAndState());
        assertEquals(0, mView.getMeasuredHeightAndState());
    }

    @Test
    public void testLayout_empty() {
        mView.measure(WIDTH_SPEC, HEIGHT_SPEC);
        mView.layout(0, 0, 500, 0);
    }


    // Instead of manually calculating the expected measurement/layout results, we build the
    // expectations as ordinary linear layouts and then check that the relevant parameters in the
    // corresponding SmartReplyView and LinearView are equal.

    @Test
    public void testMeasure_shortChoices() {
        final CharSequence[] choices = new CharSequence[]{"Hi", "Hello", "Bye"};

        // All choices should be displayed as SINGLE-line smart reply buttons.
        ViewGroup expectedView = buildExpectedView(choices, 1);
        expectedView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);

        setRepliesFromRemoteInput(choices);
        mView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);

        assertEqualMeasures(expectedView, mView);
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(0), mView.getChildAt(0));
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(1), mView.getChildAt(1));
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(2), mView.getChildAt(2));
    }

    @Test
    public void testLayout_shortChoices() {
        final CharSequence[] choices = new CharSequence[]{"Hi", "Hello", "Bye"};

        // All choices should be displayed as SINGLE-line smart reply buttons.
        ViewGroup expectedView = buildExpectedView(choices, 1);
        expectedView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
        expectedView.layout(10, 10, 10 + expectedView.getMeasuredWidth(),
                10 + expectedView.getMeasuredHeight());

        setRepliesFromRemoteInput(choices);
        mView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
        mView.layout(10, 10, 10 + mView.getMeasuredWidth(), 10 + mView.getMeasuredHeight());

        assertEqualLayouts(expectedView, mView);
        assertEqualLayouts(expectedView.getChildAt(0), mView.getChildAt(0));
        assertEqualLayouts(expectedView.getChildAt(1), mView.getChildAt(1));
        assertEqualLayouts(expectedView.getChildAt(2), mView.getChildAt(2));
    }

    @Test
    public void testMeasure_choiceWithTwoLines() {
        final CharSequence[] choices = new CharSequence[]{"Hi", "Hello\neveryone", "Bye"};

        // All choices should be displayed as DOUBLE-line smart reply buttons.
        ViewGroup expectedView = buildExpectedView(choices, 2);
        expectedView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);

        setRepliesFromRemoteInput(choices);
        mView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);

        assertEqualMeasures(expectedView, mView);
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(0), mView.getChildAt(0));
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(1), mView.getChildAt(1));
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(2), mView.getChildAt(2));
    }

    @Test
    public void testLayout_choiceWithTwoLines() {
        final CharSequence[] choices = new CharSequence[]{"Hi", "Hello\neveryone", "Bye"};

        // All choices should be displayed as DOUBLE-line smart reply buttons.
        ViewGroup expectedView = buildExpectedView(choices, 2);
        expectedView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
        expectedView.layout(10, 10, 10 + expectedView.getMeasuredWidth(),
                10 + expectedView.getMeasuredHeight());

        setRepliesFromRemoteInput(choices);
        mView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
        mView.layout(10, 10, 10 + mView.getMeasuredWidth(), 10 + mView.getMeasuredHeight());

        assertEqualLayouts(expectedView, mView);
        assertEqualLayouts(expectedView.getChildAt(0), mView.getChildAt(0));
        assertEqualLayouts(expectedView.getChildAt(1), mView.getChildAt(1));
        assertEqualLayouts(expectedView.getChildAt(2), mView.getChildAt(2));
    }

    @Test
    public void testMeasure_choiceWithThreeLines() {
        final CharSequence[] choices = new CharSequence[]{"Hi", "Hello\nevery\nbody", "Bye"};

        // The choice with three lines should NOT be displayed. All other choices should be
        // displayed as SINGLE-line smart reply buttons.
        ViewGroup expectedView = buildExpectedView(new CharSequence[]{"Hi", "Bye"}, 1);
        expectedView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);

        setRepliesFromRemoteInput(choices);
        mView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);

        assertEqualMeasures(expectedView, mView);
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(0), mView.getChildAt(0));
        assertReplyButtonHidden(mView.getChildAt(1));
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(1), mView.getChildAt(2));
    }

    @Test
    public void testLayout_choiceWithThreeLines() {
        final CharSequence[] choices = new CharSequence[]{"Hi", "Hello\nevery\nbody", "Bye"};

        // The choice with three lines should NOT be displayed. All other choices should be
        // displayed as SINGLE-line smart reply buttons.
        ViewGroup expectedView = buildExpectedView(new CharSequence[]{"Hi", "Bye"}, 1);
        expectedView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
        expectedView.layout(10, 10, 10 + expectedView.getMeasuredWidth(),
                10 + expectedView.getMeasuredHeight());

        setRepliesFromRemoteInput(choices);
        mView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
        mView.layout(10, 10, 10 + mView.getMeasuredWidth(), 10 + mView.getMeasuredHeight());

        assertEqualLayouts(expectedView, mView);
        assertEqualLayouts(expectedView.getChildAt(0), mView.getChildAt(0));
        // We don't care about mView.getChildAt(1)'s layout because it's hidden (see
        // testMeasure_choiceWithThreeLines).
        assertEqualLayouts(expectedView.getChildAt(1), mView.getChildAt(2));
    }

    @Test
    public void testMeasure_squeezeLongest() {
        final CharSequence[] choices = new CharSequence[]{"Short", "Short", "Looooooong replyyyyy"};

        // All choices should be displayed as DOUBLE-line smart reply buttons.
        ViewGroup expectedView = buildExpectedView(
                new CharSequence[]{"Short", "Short", "Looooooong \nreplyyyyy"}, 2);
        expectedView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);

        setRepliesFromRemoteInput(choices);
        mView.measure(
                MeasureSpec.makeMeasureSpec(expectedView.getMeasuredWidth(), MeasureSpec.AT_MOST),
                MeasureSpec.UNSPECIFIED);

        assertEqualMeasures(expectedView, mView);
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(0), mView.getChildAt(0));
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(1), mView.getChildAt(1));
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(2), mView.getChildAt(2));
    }

    @Test
    public void testLayout_squeezeLongest() {
        final CharSequence[] choices = new CharSequence[]{"Short", "Short", "Looooooong replyyyyy"};

        // All choices should be displayed as DOUBLE-line smart reply buttons.
        ViewGroup expectedView = buildExpectedView(
                new CharSequence[]{"Short", "Short", "Looooooong \nreplyyyyy"}, 2);
        expectedView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
        expectedView.layout(10, 10, 10 + expectedView.getMeasuredWidth(),
                10 + expectedView.getMeasuredHeight());

        setRepliesFromRemoteInput(choices);
        mView.measure(
                MeasureSpec.makeMeasureSpec(expectedView.getMeasuredWidth(), MeasureSpec.AT_MOST),
                MeasureSpec.UNSPECIFIED);
        mView.layout(10, 10, 10 + mView.getMeasuredWidth(), 10 + mView.getMeasuredHeight());

        assertEqualLayouts(expectedView, mView);
        assertEqualLayouts(expectedView.getChildAt(0), mView.getChildAt(0));
        assertEqualLayouts(expectedView.getChildAt(1), mView.getChildAt(1));
        assertEqualLayouts(expectedView.getChildAt(2), mView.getChildAt(2));
    }

    private void setRepliesFromRemoteInput(CharSequence[] choices) {
        PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, 0,
                new Intent(TEST_ACTION), 0);
        RemoteInput input = new RemoteInput.Builder(TEST_RESULT_KEY).setChoices(choices).build();
        mView.setRepliesFromRemoteInput(input, pendingIntent, mLogger, mEntry, mContainer);
    }

    /** Builds a {@link ViewGroup} whose measures and layout mirror a {@link SmartReplyView}. */
    private ViewGroup buildExpectedView(CharSequence[] choices, int lineCount) {
        LinearLayout layout = new LinearLayout(mContext);
        layout.setOrientation(LinearLayout.HORIZONTAL);

        // Baseline alignment causes expected heights to be off by one or two pixels on some
        // devices.
        layout.setBaselineAligned(false);

        final boolean isRtl = mView.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        final int paddingHorizontal;
        switch (lineCount) {
            case 1:
                paddingHorizontal = mSingleLinePaddingHorizontal;
                break;
            case 2:
                paddingHorizontal = mDoubleLinePaddingHorizontal;
                break;
            default:
                fail("Invalid line count " + lineCount);
                return null;
        }

        Button previous = null;
        for (int i = 0; i < choices.length; ++i) {
            Button current = mView.inflateReplyButton(mContext, mView, i, choices[i],
                    null, null, null, null);
            current.setPadding(paddingHorizontal, current.getPaddingTop(), paddingHorizontal,
                    current.getPaddingBottom());
            if (previous != null) {
                ViewGroup.MarginLayoutParams lp =
                        (ViewGroup.MarginLayoutParams) previous.getLayoutParams();
                if (isRtl) {
                    lp.leftMargin = mSpacing;
                } else {
                    lp.rightMargin = mSpacing;
                }
            }
            layout.addView(current);
            previous = current;
        }

        return layout;
    }

    private static void assertEqualMeasures(View expected, View actual) {
        assertEquals(expected.getMeasuredWidth(), actual.getMeasuredWidth());
        assertEquals(expected.getMeasuredHeight(), actual.getMeasuredHeight());
    }

    private static void assertReplyButtonShownWithEqualMeasures(View expected, View actual) {
        assertReplyButtonShown(actual);
        assertEqualMeasures(expected, actual);
        assertEquals(expected.getPaddingLeft(), actual.getPaddingLeft());
        assertEquals(expected.getPaddingTop(), actual.getPaddingTop());
        assertEquals(expected.getPaddingRight(), actual.getPaddingRight());
        assertEquals(expected.getPaddingBottom(), actual.getPaddingBottom());
    }

    private static void assertReplyButtonShown(View view) {
        assertTrue(((SmartReplyView.LayoutParams) view.getLayoutParams()).isShown());
    }

    private static void assertReplyButtonHidden(View view) {
        assertFalse(((SmartReplyView.LayoutParams) view.getLayoutParams()).isShown());
    }

    private static void assertEqualLayouts(View expected, View actual) {
        assertEquals(expected.getLeft(), actual.getLeft());
        assertEquals(expected.getTop(), actual.getTop());
        assertEquals(expected.getRight(), actual.getRight());
        assertEquals(expected.getBottom(), actual.getBottom());
    }
}
