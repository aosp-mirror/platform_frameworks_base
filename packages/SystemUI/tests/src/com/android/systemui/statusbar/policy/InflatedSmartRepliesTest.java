/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.service.notification.StatusBarNotification;
import android.support.test.annotation.UiThreadTest;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.util.Pair;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.policy.InflatedSmartReplies.SmartRepliesAndActions;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class InflatedSmartRepliesTest extends SysuiTestCase {

    private static final String TEST_ACTION = "com.android.SMART_REPLY_VIEW_ACTION";

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
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mNotification.getAllowSystemGeneratedContextualActions()).thenReturn(true);
        when(mStatusBarNotification.getNotification()).thenReturn(mNotification);
        mEntry = new NotificationEntry(mStatusBarNotification);
        when(mSmartReplyConstants.isEnabled()).thenReturn(true);
        mActionIcon = Icon.createWithResource(mContext, R.drawable.ic_person);
    }

    @Test
    public void chooseSmartRepliesAndActions_smartRepliesOff_noAppGeneratedSmartSuggestions() {
        CharSequence[] smartReplies = new String[] {"Reply1", "Reply2"};
        List<Notification.Action> smartActions =
                createActions(new String[] {"Test Action 1", "Test Action 2"});
        setupAppGeneratedSuggestions(smartReplies, smartActions);
        when(mSmartReplyConstants.isEnabled()).thenReturn(false);

        SmartRepliesAndActions repliesAndActions =
                InflatedSmartReplies.chooseSmartRepliesAndActions(mSmartReplyConstants, mEntry);

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

        SmartRepliesAndActions repliesAndActions =
                InflatedSmartReplies.chooseSmartRepliesAndActions(mSmartReplyConstants, mEntry);

        assertThat(repliesAndActions.smartReplies).isNull();
        assertThat(repliesAndActions.smartActions).isNull();
    }

    @Test
    public void chooseSmartRepliesAndActions_appGeneratedSmartReplies() {
        CharSequence[] smartReplies = new String[] {"Reply1", "Reply2"};
        setupAppGeneratedReplies(smartReplies);

        SmartRepliesAndActions repliesAndActions =
                InflatedSmartReplies.chooseSmartRepliesAndActions(mSmartReplyConstants, mEntry);

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

        SmartRepliesAndActions repliesAndActions =
                InflatedSmartReplies.chooseSmartRepliesAndActions(mSmartReplyConstants, mEntry);

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
        SmartRepliesAndActions repliesAndActions =
                InflatedSmartReplies.chooseSmartRepliesAndActions(mSmartReplyConstants, mEntry);

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
        SmartRepliesAndActions repliesAndActions =
                InflatedSmartReplies.chooseSmartRepliesAndActions(mSmartReplyConstants, mEntry);

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
        SmartRepliesAndActions repliesAndActions =
                InflatedSmartReplies.chooseSmartRepliesAndActions(mSmartReplyConstants, mEntry);

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

        SmartRepliesAndActions repliesAndActions =
                InflatedSmartReplies.chooseSmartRepliesAndActions(mSmartReplyConstants, mEntry);

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

        SmartRepliesAndActions repliesAndActions =
                InflatedSmartReplies.chooseSmartRepliesAndActions(mSmartReplyConstants, mEntry);

        assertThat(repliesAndActions.smartActions).isNull();
        assertThat(repliesAndActions.smartReplies).isNull();
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
