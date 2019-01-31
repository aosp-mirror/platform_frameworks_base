/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.row;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AppOpsManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.service.notification.StatusBarNotification;
import android.support.test.annotation.UiThreadTest;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.util.ArraySet;
import android.util.Pair;
import android.view.NotificationHeaderView;
import android.view.View;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.policy.SmartReplyConstants;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class NotificationContentViewTest extends SysuiTestCase {

    private static final String TEST_ACTION = "com.android.SMART_REPLY_VIEW_ACTION";

    NotificationContentView mView;

    @Mock
    SmartReplyConstants mSmartReplyConstants;
    @Mock
    StatusBarNotification mStatusBarNotification;
    @Mock
    Notification mNotification;
    NotificationEntry mEntry;
    @Mock
    RemoteInput mRemoteInput;
    @Mock
    RemoteInput mFreeFormRemoteInput;

    private Icon mActionIcon;


    @Before
    @UiThreadTest
    public void setup() {
        MockitoAnnotations.initMocks(this);

        mView = new NotificationContentView(mContext, null);
        ExpandableNotificationRow row = new ExpandableNotificationRow(mContext, null);
        ExpandableNotificationRow mockRow = spy(row);
        doNothing().when(mockRow).updateBackgroundAlpha(anyFloat());
        doReturn(10).when(mockRow).getIntrinsicHeight();

        mView.setContainingNotification(mockRow);
        mView.setHeights(10, 20, 30, 40);

        mView.setContractedChild(createViewWithHeight(10));
        mView.setExpandedChild(createViewWithHeight(20));
        mView.setHeadsUpChild(createViewWithHeight(30));
        mView.setAmbientChild(createViewWithHeight(40));

        mView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        mView.layout(0, 0, mView.getMeasuredWidth(), mView.getMeasuredHeight());

        // Smart replies and actions
        when(mNotification.getAllowSystemGeneratedContextualActions()).thenReturn(true);
        when(mStatusBarNotification.getNotification()).thenReturn(mNotification);
        mEntry = new NotificationEntry(mStatusBarNotification);
        when(mSmartReplyConstants.isEnabled()).thenReturn(true);
        mActionIcon = Icon.createWithResource(mContext, R.drawable.ic_person);
    }

    private View createViewWithHeight(int height) {
        View view = new View(mContext, null);
        view.setMinimumHeight(height);
        return view;
    }

    @Test
    @UiThreadTest
    public void animationStartType_getsClearedAfterUpdatingVisibilitiesWithoutAnimation() {
        mView.setHeadsUp(true);
        mView.setDark(true, false, 0);
        mView.setDark(false, true, 0);
        mView.setHeadsUpAnimatingAway(true);
        assertFalse(mView.isAnimatingVisibleType());
    }

    @Test
    @UiThreadTest
    public void testShowAppOpsIcons() {
        NotificationHeaderView mockContracted = mock(NotificationHeaderView.class);
        when(mockContracted.findViewById(com.android.internal.R.id.notification_header))
                .thenReturn(mockContracted);
        NotificationHeaderView mockExpanded = mock(NotificationHeaderView.class);
        when(mockExpanded.findViewById(com.android.internal.R.id.notification_header))
                .thenReturn(mockExpanded);
        NotificationHeaderView mockHeadsUp = mock(NotificationHeaderView.class);
        when(mockHeadsUp.findViewById(com.android.internal.R.id.notification_header))
                .thenReturn(mockHeadsUp);
        NotificationHeaderView mockAmbient = mock(NotificationHeaderView.class);
        when(mockAmbient.findViewById(com.android.internal.R.id.notification_header))
                .thenReturn(mockAmbient);

        mView.setContractedChild(mockContracted);
        mView.setExpandedChild(mockExpanded);
        mView.setHeadsUpChild(mockHeadsUp);
        mView.setAmbientChild(mockAmbient);

        ArraySet<Integer> ops = new ArraySet<>();
        ops.add(AppOpsManager.OP_ANSWER_PHONE_CALLS);
        mView.showAppOpsIcons(ops);

        verify(mockContracted, times(1)).showAppOpsIcons(ops);
        verify(mockExpanded, times(1)).showAppOpsIcons(ops);
        verify(mockAmbient, never()).showAppOpsIcons(ops);
        verify(mockHeadsUp, times(1)).showAppOpsIcons(any());
    }

    private void setupAppGeneratedReplies(CharSequence[] smartReplies) {
        setupAppGeneratedReplies(smartReplies, true /* allowSystemGeneratedReplies */);
    }

    private void setupAppGeneratedReplies(
            CharSequence[] smartReplies, boolean allowSystemGeneratedReplies) {
        PendingIntent pendingIntent =
                PendingIntent.getBroadcast(mContext, 0, new Intent(TEST_ACTION), 0);
        Notification.Action action =
                new Notification.Action.Builder(null, "Test Action", pendingIntent).build();
        when(mRemoteInput.getChoices()).thenReturn(smartReplies);
        Pair<RemoteInput, Notification.Action> remoteInputActionPair =
                Pair.create(mRemoteInput, action);
        when(mNotification.findRemoteInputActionPair(false)).thenReturn(remoteInputActionPair);

        Notification.Action freeFormRemoteInputAction =
                createActionBuilder("Freeform Test Action")
                .setAllowGeneratedReplies(allowSystemGeneratedReplies)
                .build();
        Pair<RemoteInput, Notification.Action> freeFormRemoteInputActionPair =
                Pair.create(mFreeFormRemoteInput, freeFormRemoteInputAction);
        when(mNotification.findRemoteInputActionPair(true)).thenReturn(
                freeFormRemoteInputActionPair);

        when(mSmartReplyConstants.requiresTargetingP()).thenReturn(false);
    }

    private void setupAppGeneratedSuggestions(
            CharSequence[] smartReplies, List<Notification.Action> smartActions) {
        setupAppGeneratedReplies(smartReplies);
        when(mNotification.getContextualActions()).thenReturn(smartActions);
    }

    @Test
    public void chooseSmartRepliesAndActions_smartRepliesOff_noAppGeneratedSmartSuggestions() {
        CharSequence[] smartReplies = new String[] {"Reply1", "Reply2"};
        List<Notification.Action> smartActions =
                createActions(new String[] {"Test Action 1", "Test Action 2"});
        setupAppGeneratedSuggestions(smartReplies, smartActions);
        when(mSmartReplyConstants.isEnabled()).thenReturn(false);

        NotificationContentView.SmartRepliesAndActions repliesAndActions =
                NotificationContentView.chooseSmartRepliesAndActions(mSmartReplyConstants, mEntry);

        assertThat(repliesAndActions.smartReplies).isNull();
        assertThat(repliesAndActions.smartActions).isNull();
    }

    @Test
    public void chooseSmartRepliesAndActions_smartRepliesOff_noSystemGeneratedSmartSuggestions() {
        mEntry.systemGeneratedSmartReplies =
                new String[] {"Sys Smart Reply 1", "Sys Smart Reply 2"};
        mEntry.systemGeneratedSmartActions =
                createActions(new String[] {"Sys Smart Action 1", "Sys Smart Action 2"});
        when(mSmartReplyConstants.isEnabled()).thenReturn(false);

        NotificationContentView.SmartRepliesAndActions repliesAndActions =
                NotificationContentView.chooseSmartRepliesAndActions(mSmartReplyConstants, mEntry);

        assertThat(repliesAndActions.smartReplies).isNull();
        assertThat(repliesAndActions.smartActions).isNull();
    }

    @Test
    public void chooseSmartRepliesAndActions_appGeneratedSmartReplies() {
        CharSequence[] smartReplies = new String[] {"Reply1", "Reply2"};
        setupAppGeneratedReplies(smartReplies);

        NotificationContentView.SmartRepliesAndActions repliesAndActions =
                NotificationContentView.chooseSmartRepliesAndActions(mSmartReplyConstants, mEntry);

        assertThat(repliesAndActions.smartReplies.choices).isEqualTo(smartReplies);
        assertThat(repliesAndActions.smartReplies.fromAssistant).isFalse();
        assertThat(repliesAndActions.smartActions).isNull();
    }

    @Test
    public void chooseSmartRepliesAndActions_appGeneratedSmartRepliesAndActions() {
        CharSequence[] smartReplies = new String[] {"Reply1", "Reply2"};
        List<Notification.Action> smartActions =
                createActions(new String[] {"Test Action 1", "Test Action 2"});
        setupAppGeneratedSuggestions(smartReplies, smartActions);

        NotificationContentView.SmartRepliesAndActions repliesAndActions =
                NotificationContentView.chooseSmartRepliesAndActions(mSmartReplyConstants, mEntry);

        assertThat(repliesAndActions.smartReplies.choices).isEqualTo(smartReplies);
        assertThat(repliesAndActions.smartReplies.fromAssistant).isFalse();
        assertThat(repliesAndActions.smartActions.actions).isEqualTo(smartActions);
        assertThat(repliesAndActions.smartActions.fromAssistant).isFalse();
    }

    @Test
    public void chooseSmartRepliesAndActions_sysGeneratedSmartReplies() {
        // Pass a null-array as app-generated smart replies, so that we use NAS-generated smart
        // replies.
        setupAppGeneratedReplies(null /* smartReplies */);

        mEntry.systemGeneratedSmartReplies =
                new String[] {"Sys Smart Reply 1", "Sys Smart Reply 2"};
        NotificationContentView.SmartRepliesAndActions repliesAndActions =
                NotificationContentView.chooseSmartRepliesAndActions(mSmartReplyConstants, mEntry);

        assertThat(repliesAndActions.smartReplies.choices).isEqualTo(
                mEntry.systemGeneratedSmartReplies);
        assertThat(repliesAndActions.smartReplies.fromAssistant).isTrue();
        assertThat(repliesAndActions.smartActions).isNull();
    }

    @Test
    public void chooseSmartRepliesAndActions_noSysGeneratedSmartRepliesIfNotAllowed() {
        // Pass a null-array as app-generated smart replies, so that we use NAS-generated smart
        // replies.
        setupAppGeneratedReplies(null /* smartReplies */, false /* allowSystemGeneratedReplies */);

        mEntry.systemGeneratedSmartReplies =
                new String[] {"Sys Smart Reply 1", "Sys Smart Reply 2"};
        NotificationContentView.SmartRepliesAndActions repliesAndActions =
                NotificationContentView.chooseSmartRepliesAndActions(mSmartReplyConstants, mEntry);

        assertThat(repliesAndActions.smartReplies).isNull();
        assertThat(repliesAndActions.smartActions).isNull();
    }

    @Test
    public void chooseSmartRepliesAndActions_sysGeneratedSmartActions() {
        // Pass a null-array as app-generated smart replies, so that we use NAS-generated smart
        // actions.
        setupAppGeneratedReplies(null /* smartReplies */);

        mEntry.systemGeneratedSmartActions =
                createActions(new String[] {"Sys Smart Action 1", "Sys Smart Action 2"});
        NotificationContentView.SmartRepliesAndActions repliesAndActions =
                NotificationContentView.chooseSmartRepliesAndActions(mSmartReplyConstants, mEntry);

        assertThat(repliesAndActions.smartReplies).isNull();
        assertThat(repliesAndActions.smartActions.actions)
                .isEqualTo(mEntry.systemGeneratedSmartActions);
        assertThat(repliesAndActions.smartActions.fromAssistant).isTrue();
    }

    @Test
    public void chooseSmartRepliesAndActions_appGenPreferredOverSysGen() {
        CharSequence[] appGenSmartReplies = new String[] {"Reply1", "Reply2"};
        // Pass a null-array as app-generated smart replies, so that we use NAS-generated smart
        // replies.
        List<Notification.Action> appGenSmartActions =
                createActions(new String[] {"Test Action 1", "Test Action 2"});
        setupAppGeneratedSuggestions(appGenSmartReplies, appGenSmartActions);

        mEntry.systemGeneratedSmartReplies =
                new String[] {"Sys Smart Reply 1", "Sys Smart Reply 2"};
        mEntry.systemGeneratedSmartActions =
                createActions(new String[] {"Sys Smart Action 1", "Sys Smart Action 2"});

        NotificationContentView.SmartRepliesAndActions repliesAndActions =
                NotificationContentView.chooseSmartRepliesAndActions(mSmartReplyConstants, mEntry);

        assertThat(repliesAndActions.smartReplies.choices).isEqualTo(appGenSmartReplies);
        assertThat(repliesAndActions.smartReplies.fromAssistant).isFalse();
        assertThat(repliesAndActions.smartActions.actions).isEqualTo(appGenSmartActions);
        assertThat(repliesAndActions.smartActions.fromAssistant).isFalse();
    }

    @Test
    public void chooseSmartRepliesAndActions_disallowSysGenSmartActions() {
        // Pass a null-array as app-generated smart replies, so that we use NAS-generated smart
        // actions.
        setupAppGeneratedReplies(null /* smartReplies */, false /* allowSystemGeneratedReplies */);
        when(mNotification.getAllowSystemGeneratedContextualActions()).thenReturn(false);
        mEntry.systemGeneratedSmartReplies =
                new String[] {"Sys Smart Reply 1", "Sys Smart Reply 2"};
        mEntry.systemGeneratedSmartActions =
                createActions(new String[] {"Sys Smart Action 1", "Sys Smart Action 2"});

        NotificationContentView.SmartRepliesAndActions repliesAndActions =
                NotificationContentView.chooseSmartRepliesAndActions(mSmartReplyConstants, mEntry);

        assertThat(repliesAndActions.smartActions).isNull();
        assertThat(repliesAndActions.smartReplies).isNull();
    }

    private Notification.Action.Builder createActionBuilder(String actionTitle) {
        PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, 0,
                new Intent(TEST_ACTION), 0);
        return new Notification.Action.Builder(mActionIcon, actionTitle, pendingIntent);
    }

    private Notification.Action createAction(String actionTitle) {
        return createActionBuilder(actionTitle).build();
    }

    private List<Notification.Action> createActions(String[] actionTitles) {
        List<Notification.Action> actions = new ArrayList<>();
        for (String title : actionTitles) {
            actions.add(createAction(title));
        }
        return actions;
    }
}
