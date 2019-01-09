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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.service.notification.StatusBarNotification;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.ActivityStarter.OnDismissAction;
import com.android.systemui.statusbar.SmartReplyController;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.phone.KeyguardDismissUtil;
import com.android.systemui.statusbar.phone.ShadeController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class SmartReplyViewTest extends SysuiTestCase {
    private static final String TEST_RESULT_KEY = "test_result_key";
    private static final String TEST_ACTION = "com.android.SMART_REPLY_VIEW_ACTION";

    private static final String[] TEST_CHOICES = new String[]{"Hello", "What's up?", "I'm here"};
    private static final String TEST_NOTIFICATION_KEY = "akey";

    private static final String[] TEST_ACTION_TITLES = new String[]{
            "First action", "Open something", "Action"
    };

    private static final int WIDTH_SPEC = MeasureSpec.makeMeasureSpec(500, MeasureSpec.EXACTLY);
    private static final int HEIGHT_SPEC = MeasureSpec.makeMeasureSpec(400, MeasureSpec.AT_MOST);

    private BlockingQueueIntentReceiver mReceiver;
    private SmartReplyView mView;
    private View mContainer;

    private Icon mActionIcon;

    private int mSingleLinePaddingHorizontal;
    private int mDoubleLinePaddingHorizontal;
    private int mSpacing;

    @Mock private SmartReplyController mLogger;
    private NotificationEntry mEntry;
    private Notification mNotification;

    @Mock ActivityStarter mActivityStarter;
    @Mock HeadsUpManager mHeadsUpManager;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mReceiver = new BlockingQueueIntentReceiver();
        mContext.registerReceiver(mReceiver, new IntentFilter(TEST_ACTION));
        mDependency.get(KeyguardDismissUtil.class).setDismissHandler(action -> action.onDismiss());
        mDependency.injectMockDependency(ShadeController.class);
        mDependency.injectTestDependency(ActivityStarter.class, mActivityStarter);

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
        mEntry = new NotificationEntry(sbn);

        mActionIcon = Icon.createWithResource(mContext, R.drawable.ic_person);
    }

    @After
    public void tearDown() {
        mContext.unregisterReceiver(mReceiver);
    }

    @Test
    public void testSendSmartReply_intentContainsResultsAndSource() throws InterruptedException {
        setSmartReplies(TEST_CHOICES);

        mView.getChildAt(2).performClick();

        Intent resultIntent = mReceiver.waitForIntent();
        assertEquals(TEST_CHOICES[2],
                RemoteInput.getResultsFromIntent(resultIntent).get(TEST_RESULT_KEY));
        assertEquals(RemoteInput.SOURCE_CHOICE, RemoteInput.getResultsSource(resultIntent));
    }

    @Test
    public void testSendSmartReply_keyguardCancelled() throws InterruptedException {
        mDependency.get(KeyguardDismissUtil.class).setDismissHandler(action -> {});
        setSmartReplies(TEST_CHOICES);

        mView.getChildAt(2).performClick();

        assertNull(mReceiver.waitForIntent());
    }

    @Test
    public void testSendSmartReply_waitsForKeyguard() throws InterruptedException {
        AtomicReference<OnDismissAction> actionRef = new AtomicReference<>();
        mDependency.get(KeyguardDismissUtil.class).setDismissHandler(actionRef::set);
        setSmartReplies(TEST_CHOICES);

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
        setSmartReplies(TEST_CHOICES);
        mView.getChildAt(2).performClick();
        verify(mLogger).smartReplySent(mEntry, 2, TEST_CHOICES[2],
                false /* generatedByAsssitant */);
    }

    @Test
    public void testSendSmartReply_controllerCalled_generatedByAssistant() {
        setSmartReplies(TEST_CHOICES, true);
        mView.getChildAt(2).performClick();
        verify(mLogger).smartReplySent(mEntry, 2, TEST_CHOICES[2],
                true /* generatedByAsssitant */);
    }

    @Test
    public void testSendSmartReply_hidesContainer() {
        mContainer.setVisibility(View.VISIBLE);
        setSmartReplies(TEST_CHOICES);
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

        setSmartReplies(choices);
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

        setSmartReplies(choices);
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

        setSmartReplies(choices);
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

        setSmartReplies(choices);
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

        setSmartReplies(choices);
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

        setSmartReplies(choices);
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

        setSmartReplies(choices);
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

        setSmartReplies(choices);
        mView.measure(
                MeasureSpec.makeMeasureSpec(expectedView.getMeasuredWidth(), MeasureSpec.AT_MOST),
                MeasureSpec.UNSPECIFIED);
        mView.layout(10, 10, 10 + mView.getMeasuredWidth(), 10 + mView.getMeasuredHeight());

        assertEqualLayouts(expectedView, mView);
        assertEqualLayouts(expectedView.getChildAt(0), mView.getChildAt(0));
        assertEqualLayouts(expectedView.getChildAt(1), mView.getChildAt(1));
        assertEqualLayouts(expectedView.getChildAt(2), mView.getChildAt(2));
    }

    @Test
    public void testMeasure_dropLongest() {
        final CharSequence[] choices = new CharSequence[]{"Short", "Short",
                "LooooooongUnbreakableReplyyyyy"};

        // Short choices should be shown as single line views
        ViewGroup expectedView = buildExpectedView(
                new CharSequence[]{"Short", "Short"}, 1);
        expectedView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
        expectedView.layout(10, 10, 10 + expectedView.getMeasuredWidth(),
                10 + expectedView.getMeasuredHeight());

        setSmartReplies(choices);
        mView.measure(
                MeasureSpec.makeMeasureSpec(expectedView.getMeasuredWidth(), MeasureSpec.AT_MOST),
                MeasureSpec.UNSPECIFIED);
        mView.layout(10, 10, 10 + mView.getMeasuredWidth(), 10 + mView.getMeasuredHeight());

        assertEqualLayouts(expectedView, mView);
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(0), mView.getChildAt(0));
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(1), mView.getChildAt(1));
        assertReplyButtonHidden(mView.getChildAt(2));
    }

    private void setSmartReplies(CharSequence[] choices) {
        setSmartReplies(choices, false);
    }

    private void setSmartReplies(CharSequence[] choices, boolean fromAssistant) {
        PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, 0,
                new Intent(TEST_ACTION), 0);
        RemoteInput input = new RemoteInput.Builder(TEST_RESULT_KEY).setChoices(choices).build();
        SmartReplyView.SmartReplies smartReplies =
                new SmartReplyView.SmartReplies(choices, input, pendingIntent, fromAssistant);
        mView.resetSmartSuggestions(mContainer);
        mView.addRepliesFromRemoteInput(smartReplies, mLogger, mEntry);
    }

    private Notification.Action createAction(String actionTitle) {
        PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, 0,
                new Intent(TEST_ACTION), 0);
        return new Notification.Action.Builder(mActionIcon, actionTitle, pendingIntent).build();
    }

    private List<Notification.Action> createActions(String[] actionTitles) {
        List<Notification.Action> actions = new ArrayList<>();
        for (String title : actionTitles) {
            actions.add(createAction(title));
        }
        return actions;
    }

    private void setSmartActions(String[] actionTitles) {
        mView.resetSmartSuggestions(mContainer);
        mView.addSmartActions(
                new SmartReplyView.SmartActions(createActions(actionTitles), false),
                mLogger,
                mEntry,
                mHeadsUpManager);
    }

    private void setSmartRepliesAndActions(CharSequence[] choices, String[] actionTitles) {
        setSmartReplies(choices);
        mView.addSmartActions(
                new SmartReplyView.SmartActions(createActions(actionTitles), false),
                mLogger,
                mEntry,
                mHeadsUpManager);
    }

    private ViewGroup buildExpectedView(CharSequence[] choices, int lineCount) {
        return buildExpectedView(choices, lineCount, new ArrayList<>());
    }

    /** Builds a {@link ViewGroup} whose measures and layout mirror a {@link SmartReplyView}. */
    private ViewGroup buildExpectedView(
            CharSequence[] choices, int lineCount, List<Notification.Action> actions) {
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

        // Add smart replies
        Button previous = null;
        SmartReplyView.SmartReplies smartReplies =
                new SmartReplyView.SmartReplies(choices, null, null, false);
        for (int i = 0; i < choices.length; ++i) {
            Button current = mView.inflateReplyButton(mContext, mView, i, smartReplies,
                    null, null);
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

        // Add smart actions
        for (int i = 0; i < actions.size(); ++i) {
            Button current = inflateActionButton(actions.get(i));
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
        assertEqualPadding(expected, actual);
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

    private static void assertEqualPadding(View expected, View actual) {
        assertEquals(expected.getPaddingLeft(), actual.getPaddingLeft());
        assertEquals(expected.getPaddingTop(), actual.getPaddingTop());
        assertEquals(expected.getPaddingRight(), actual.getPaddingRight());
        assertEquals(expected.getPaddingBottom(), actual.getPaddingBottom());
    }


    // =============================================================================================
    // ============================= Smart Action tests ============================================
    // =============================================================================================

    @Test
    public void testTapSmartAction_waitsForKeyguard() throws InterruptedException {
        setSmartActions(TEST_ACTION_TITLES);

        mView.getChildAt(2).performClick();

        verify(mActivityStarter, times(1)).startPendingIntentDismissingKeyguard(any(), any());
    }

    @Test
    public void testMeasure_shortSmartActions() {
        String[] actions = new String[] {"Hi", "Hello", "Bye"};
        // All choices should be displayed as SINGLE-line smart action buttons.
        ViewGroup expectedView = buildExpectedView(new CharSequence[0], 1, createActions(actions));
        expectedView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);

        setSmartActions(actions);
        mView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);

        assertEqualMeasures(expectedView, mView);
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(0), mView.getChildAt(0));
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(1), mView.getChildAt(1));
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(2), mView.getChildAt(2));
    }

    @Test
    public void testLayout_shortSmartActions() {
        String[] actions = new String[] {"Hi", "Hello", "Bye"};
        // All choices should be displayed as SINGLE-line smart action buttons.
        ViewGroup expectedView = buildExpectedView(new CharSequence[0], 1, createActions(actions));
        expectedView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
        expectedView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
        expectedView.layout(10, 10, 10 + expectedView.getMeasuredWidth(),
                10 + expectedView.getMeasuredHeight());

        setSmartActions(actions);
        mView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
        mView.layout(10, 10, 10 + mView.getMeasuredWidth(), 10 + mView.getMeasuredHeight());

        assertEqualLayouts(expectedView, mView);
        assertEqualLayouts(expectedView.getChildAt(0), mView.getChildAt(0));
        assertEqualLayouts(expectedView.getChildAt(1), mView.getChildAt(1));
        assertEqualLayouts(expectedView.getChildAt(2), mView.getChildAt(2));
    }

    @Test
    public void testMeasure_smartActionWithTwoLines() {
        String[] actions = new String[] {"Hi", "Hello\neveryone", "Bye"};

        // All actions should be displayed as DOUBLE-line smart action buttons.
        ViewGroup expectedView = buildExpectedView(new CharSequence[0], 2, createActions(actions));
        expectedView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);

        setSmartActions(actions);
        mView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);

        assertEqualMeasures(expectedView, mView);
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(0), mView.getChildAt(0));
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(1), mView.getChildAt(1));
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(2), mView.getChildAt(2));
    }

    @Test
    public void testLayout_smartActionWithTwoLines() {
        String[] actions = new String[] {"Hi", "Hello\neveryone", "Bye"};

        // All actions should be displayed as DOUBLE-line smart action buttons.
        ViewGroup expectedView = buildExpectedView(new CharSequence[0], 2, createActions(actions));
        expectedView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
        expectedView.layout(10, 10, 10 + expectedView.getMeasuredWidth(),
                10 + expectedView.getMeasuredHeight());

        setSmartActions(actions);
        mView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
        mView.layout(10, 10, 10 + mView.getMeasuredWidth(), 10 + mView.getMeasuredHeight());

        assertEqualLayouts(expectedView, mView);
        assertEqualLayouts(expectedView.getChildAt(0), mView.getChildAt(0));
        assertEqualLayouts(expectedView.getChildAt(1), mView.getChildAt(1));
        assertEqualLayouts(expectedView.getChildAt(2), mView.getChildAt(2));
    }

    @Test
    public void testMeasure_smartActionWithThreeLines() {
        String[] actions = new String[] {"Hi", "Hello\nevery\nbody", "Bye"};

        // The action with three lines should NOT be displayed. All other actions should be
        // displayed as SINGLE-line smart action buttons.
        ViewGroup expectedView = buildExpectedView(new CharSequence[0], 1,
                createActions(new String[]{"Hi", "Bye"}));
        expectedView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);

        setSmartActions(actions);
        mView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);

        assertEqualMeasures(expectedView, mView);
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(0), mView.getChildAt(0));
        assertReplyButtonHidden(mView.getChildAt(1));
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(1), mView.getChildAt(2));
    }

    @Test
    public void testLayout_smartActionWithThreeLines() {
        String[] actions = new String[] {"Hi", "Hello\nevery\nbody", "Bye"};

        // The action with three lines should NOT be displayed. All other actions should be
        // displayed as SINGLE-line smart action buttons.
        ViewGroup expectedView = buildExpectedView(new CharSequence[0], 1,
                createActions(new String[]{"Hi", "Bye"}));
        expectedView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
        expectedView.layout(10, 10, 10 + expectedView.getMeasuredWidth(),
                10 + expectedView.getMeasuredHeight());

        setSmartActions(actions);
        mView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
        mView.layout(10, 10, 10 + mView.getMeasuredWidth(), 10 + mView.getMeasuredHeight());

        assertEqualLayouts(expectedView, mView);
        assertEqualLayouts(expectedView.getChildAt(0), mView.getChildAt(0));
        // We don't care about mView.getChildAt(1)'s layout because it's hidden (see
        // testMeasure_smartActionWithThreeLines).
        assertEqualLayouts(expectedView.getChildAt(1), mView.getChildAt(2));
    }

    @Test
    public void testMeasure_squeezeLongestSmartAction() {
        String[] actions = new String[] {"Short", "Short", "Looooooong replyyyyy"};

        // All actions should be displayed as DOUBLE-line smart action buttons.
        ViewGroup expectedView = buildExpectedView(new CharSequence[0], 2,
                createActions(new String[] {"Short", "Short", "Looooooong \nreplyyyyy"}));
        expectedView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);

        setSmartActions(actions);
        mView.measure(
                MeasureSpec.makeMeasureSpec(expectedView.getMeasuredWidth(), MeasureSpec.AT_MOST),
                MeasureSpec.UNSPECIFIED);

        assertEqualMeasures(expectedView, mView);
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(0), mView.getChildAt(0));
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(1), mView.getChildAt(1));
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(2), mView.getChildAt(2));
    }

    @Test
    public void testLayout_squeezeLongestSmartAction() {
        String[] actions = new String[] {"Short", "Short", "Looooooong replyyyyy"};

        // All actions should be displayed as DOUBLE-line smart action buttons.
        ViewGroup expectedView = buildExpectedView(new CharSequence[0], 2,
                createActions(new String[] {"Short", "Short", "Looooooong \nreplyyyyy"}));
        expectedView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
        expectedView.layout(10, 10, 10 + expectedView.getMeasuredWidth(),
                10 + expectedView.getMeasuredHeight());

        setSmartActions(actions);
        mView.measure(
                MeasureSpec.makeMeasureSpec(expectedView.getMeasuredWidth(), MeasureSpec.AT_MOST),
                MeasureSpec.UNSPECIFIED);
        mView.layout(10, 10, 10 + mView.getMeasuredWidth(), 10 + mView.getMeasuredHeight());

        assertEqualLayouts(expectedView, mView);
        assertEqualLayouts(expectedView.getChildAt(0), mView.getChildAt(0));
        assertEqualLayouts(expectedView.getChildAt(1), mView.getChildAt(1));
        assertEqualLayouts(expectedView.getChildAt(2), mView.getChildAt(2));
    }

    @Test
    public void testMeasure_dropLongestSmartAction() {
        String[] actions = new String[] {"Short", "Short", "LooooooongUnbreakableReplyyyyy"};

        // Short actions should be shown as single line views
        ViewGroup expectedView = buildExpectedView(
                new CharSequence[0], 1, createActions(new String[] {"Short", "Short"}));
        expectedView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
        expectedView.layout(10, 10, 10 + expectedView.getMeasuredWidth(),
                10 + expectedView.getMeasuredHeight());

        setSmartActions(actions);
        mView.measure(
                MeasureSpec.makeMeasureSpec(expectedView.getMeasuredWidth(), MeasureSpec.AT_MOST),
                MeasureSpec.UNSPECIFIED);
        mView.layout(10, 10, 10 + mView.getMeasuredWidth(), 10 + mView.getMeasuredHeight());

        assertEqualLayouts(expectedView, mView);
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(0), mView.getChildAt(0));
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(1), mView.getChildAt(1));
        assertReplyButtonHidden(mView.getChildAt(2));
    }

    private Button inflateActionButton(Notification.Action action) {
        return mView.inflateActionButton(getContext(), mView, 0,
                new SmartReplyView.SmartActions(Collections.singletonList(action), false),
                mLogger, mEntry, mHeadsUpManager);
    }

    @Test
    public void testInflateActionButton_smartActionIconSingleLineSizeForTwoLineButton() {
        // Ensure smart action icons are the same size regardless of the number of text rows in the
        // button.
        Button singleLineButton = inflateActionButton(createAction("One line"));
        Button doubleLineButton = inflateActionButton(createAction("Two\nlines"));
        Drawable singleLineDrawable = singleLineButton.getCompoundDrawables()[0]; // left drawable
        Drawable doubleLineDrawable = doubleLineButton.getCompoundDrawables()[0]; // left drawable
        assertEquals(singleLineDrawable.getBounds().width(),
                     doubleLineDrawable.getBounds().width());
        assertEquals(singleLineDrawable.getBounds().height(),
                     doubleLineDrawable.getBounds().height());
    }

    @Test
    public void testMeasure_shortChoicesAndActions() {
        CharSequence[] choices = new String[] {"Hi", "Hello"};
        String[] actions = new String[] {"Bye"};
        // All choices should be displayed as SINGLE-line smart action buttons.
        ViewGroup expectedView = buildExpectedView(choices, 1, createActions(actions));
        expectedView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);

        setSmartRepliesAndActions(choices, actions);
        mView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);

        assertEqualMeasures(expectedView, mView);
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(0), mView.getChildAt(0));
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(1), mView.getChildAt(1));
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(2), mView.getChildAt(2));
    }

    @Test
    public void testMeasure_choicesAndActionsSqueezeLongestAction() {
        CharSequence[] choices = new String[] {"Short", "Short"};
        String[] actions = new String[] {"Looooooong replyyyyy"};

        // All actions should be displayed as DOUBLE-line smart action buttons.
        ViewGroup expectedView = buildExpectedView(choices, 2,
                createActions(new String[] {"Looooooong \nreplyyyyy"}));
        expectedView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);

        setSmartRepliesAndActions(choices, actions);
        mView.measure(
                MeasureSpec.makeMeasureSpec(expectedView.getMeasuredWidth(), MeasureSpec.AT_MOST),
                MeasureSpec.UNSPECIFIED);

        assertEqualMeasures(expectedView, mView);
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(0), mView.getChildAt(0));
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(1), mView.getChildAt(1));
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(2), mView.getChildAt(2));
    }

    @Test
    public void testMeasure_choicesAndActionsPrioritizeActionsOnlyActions() {
        String[] choices = new String[] {"Reply"};
        String[] actions = new String[] {"Looooooong actioooon", "second action", "third action"};

        // All actions should be displayed as DOUBLE-line smart action buttons.
        ViewGroup expectedView = buildExpectedView(new String[0], 2,
                createActions(new String[] {
                        "Looooooong \nactioooon", "second \naction", "third \naction"}));
        expectedView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);

        setSmartRepliesAndActions(choices, actions);
        mView.measure(
                MeasureSpec.makeMeasureSpec(expectedView.getMeasuredWidth(), MeasureSpec.AT_MOST),
                MeasureSpec.UNSPECIFIED);

        assertEqualMeasures(expectedView, mView);
        // smart replies
        assertReplyButtonHidden(mView.getChildAt(0));
        // smart actions
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(0), mView.getChildAt(1));
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(1), mView.getChildAt(2));
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(2), mView.getChildAt(3));
    }

    @Test
    public void testMeasure_choicesAndActionsPrioritizeActions() {
        String[] choices = new String[] {"Short", "longer reply"};
        String[] actions = new String[] {"Looooooong actioooon", "second action"};

        // All actions should be displayed as DOUBLE-line smart action buttons.
        ViewGroup expectedView = buildExpectedView(new String[] {"Short"}, 2,
                createActions(new String[] {"Looooooong \nactioooon", "second \naction"}));
        expectedView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);

        setSmartRepliesAndActions(choices, actions);
        mView.measure(
                MeasureSpec.makeMeasureSpec(expectedView.getMeasuredWidth(), MeasureSpec.AT_MOST),
                MeasureSpec.UNSPECIFIED);

        Button firstAction = ((Button) mView.getChildAt(1));

        assertEqualMeasures(expectedView, mView);
        // smart replies
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(0), mView.getChildAt(0));
        assertReplyButtonHidden(mView.getChildAt(1));
        // smart actions
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(1), mView.getChildAt(2));
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(2), mView.getChildAt(3));
    }

    /**
     * Test to ensure that we try to add all possible actions - if we find one action that's too
     * long we just skip that one rather than quitting altogether.
     */
    @Test
    public void testMeasure_skipTooLongActions() {
        String[] choices = new String[] {};
        String[] actions = new String[] {
                "a1", "a2", "this action is soooooooo long it's ridiculous", "a4"};

        // All actions should be displayed as DOUBLE-line smart action buttons.
        ViewGroup expectedView = buildExpectedView(new String[] {}, 1 /* lineCount */,
                createActions(new String[] {"a1", "a2", "a4"}));
        expectedView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);

        setSmartRepliesAndActions(choices, actions);
        mView.measure(
                MeasureSpec.makeMeasureSpec(expectedView.getMeasuredWidth(), MeasureSpec.AT_MOST),
                MeasureSpec.UNSPECIFIED);

        assertEqualMeasures(expectedView, mView);
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(0), mView.getChildAt(0));
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(1), mView.getChildAt(1));
        assertReplyButtonHidden(mView.getChildAt(2));
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(2), mView.getChildAt(3));
    }

    /**
     * Test to ensure that we try to add all possible replies - if we find one reply that's too
     * long we just skip that one rather than quitting altogether.
     */
    @Test
    public void testMeasure_skipTooLongReplies() {
        String[] choices = new String[] {
                "r1", "r2", "this reply is soooooooo long it's ridiculous", "r4"};
        String[] actions = new String[] {};

        // All replies should be displayed as single-line smart reply buttons.
        ViewGroup expectedView = buildExpectedView(new String[] {"r1", "r2", "r4"},
                1 /* lineCount */, Collections.emptyList());
        expectedView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);

        setSmartRepliesAndActions(choices, actions);
        mView.measure(
                MeasureSpec.makeMeasureSpec(expectedView.getMeasuredWidth(), MeasureSpec.AT_MOST),
                MeasureSpec.UNSPECIFIED);

        assertEqualMeasures(expectedView, mView);
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(0), mView.getChildAt(0));
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(1), mView.getChildAt(1));
        assertReplyButtonHidden(mView.getChildAt(2));
        assertReplyButtonShownWithEqualMeasures(expectedView.getChildAt(2), mView.getChildAt(3));
    }

    /**
     * Test to ensure that we try to add all possible replies and actions - if we find a reply or
     * action that's too long we just skip that one rather than quitting altogether.
     */
    @Test
    public void testMeasure_skipTooLongRepliesAndActions() {
        String[] choices = new String[] {
                "r1", "r2", "this reply is soooooooo long it's ridiculous", "r4"};
        String[] actions = new String[] {
                "a1", "ThisActionIsSooooooooLongItsRidiculousIPromise"};

        // All replies should be displayed as single-line smart reply buttons.
        ViewGroup expectedView = buildExpectedView(new String[] {"r1", "r2", "r4"},
                1 /* lineCount */, createActions(new String[] {"a1"}));
        expectedView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);

        setSmartRepliesAndActions(choices, actions);
        mView.measure(
                MeasureSpec.makeMeasureSpec(expectedView.getMeasuredWidth(), MeasureSpec.AT_MOST),
                MeasureSpec.UNSPECIFIED);

        assertEqualMeasures(expectedView, mView);
        assertReplyButtonShownWithEqualMeasures(
                expectedView.getChildAt(0), mView.getChildAt(0)); // r1
        assertReplyButtonShownWithEqualMeasures(
                expectedView.getChildAt(1), mView.getChildAt(1)); // r2
        assertReplyButtonHidden(mView.getChildAt(2)); // long reply
        assertReplyButtonShownWithEqualMeasures(
                expectedView.getChildAt(2), mView.getChildAt(3)); // r4
        assertReplyButtonShownWithEqualMeasures(
                expectedView.getChildAt(3), mView.getChildAt(4)); // a1
        assertReplyButtonHidden(mView.getChildAt(5)); // long action
    }
}
