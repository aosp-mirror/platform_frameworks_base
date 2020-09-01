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
 * limitations under the License.  */

package com.android.systemui.statusbar.policy;

import static com.android.systemui.statusbar.NotificationEntryHelper.modifyRanking;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Icon;
import android.util.Pair;

import androidx.test.annotation.UiThreadTest;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.DevicePolicyManagerWrapper;
import com.android.systemui.shared.system.PackageManagerWrapper;
import com.android.systemui.statusbar.NotificationEntryHelper;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.policy.InflatedSmartReplies.SmartRepliesAndActions;
import com.android.systemui.statusbar.policy.SmartReplyView.SmartActions;
import com.android.systemui.statusbar.policy.SmartReplyView.SmartReplies;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class InflatedSmartRepliesTest extends SysuiTestCase {

    private static final Intent TEST_INTENT = new Intent("com.android.SMART_REPLY_VIEW_ACTION");
    private static final Intent WHITELISTED_TEST_INTENT =
            new Intent("com.android.WHITELISTED_TEST_ACTION");

    @Mock SmartReplyConstants mSmartReplyConstants;
    @Mock Notification mNotification;
    NotificationEntry mEntry;
    @Mock RemoteInput mRemoteInput;
    @Mock RemoteInput mFreeFormRemoteInput;
    @Mock ActivityManagerWrapper mActivityManagerWrapper;
    @Mock PackageManagerWrapper mPackageManagerWrapper;
    @Mock DevicePolicyManagerWrapper mDevicePolicyManagerWrapper;

    private Icon mActionIcon;

    @Before
    @UiThreadTest
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mDependency.injectTestDependency(ActivityManagerWrapper.class, mActivityManagerWrapper);
        mDependency.injectTestDependency(
                DevicePolicyManagerWrapper.class, mDevicePolicyManagerWrapper);
        mDependency.injectTestDependency(PackageManagerWrapper.class, mPackageManagerWrapper);

        when(mNotification.getAllowSystemGeneratedContextualActions()).thenReturn(true);
        mEntry = new NotificationEntryBuilder()
                .setNotification(mNotification)
                .build();
        when(mSmartReplyConstants.isEnabled()).thenReturn(true);
        mActionIcon = Icon.createWithResource(mContext, R.drawable.ic_person);

        when(mActivityManagerWrapper.isLockTaskKioskModeActive()).thenReturn(false);
    }

    @Test
    public void chooseSmartRepliesAndActions_smartRepliesOff_noAppGeneratedSmartSuggestions() {
        CharSequence[] smartReplies = new String[] {"Reply1", "Reply2"};
        List<Notification.Action> smartActions =
                createActions("Test Action 1", "Test Action 2");
        setupAppGeneratedSuggestions(smartReplies, smartActions);
        when(mSmartReplyConstants.isEnabled()).thenReturn(false);

        SmartRepliesAndActions repliesAndActions =
                InflatedSmartReplies.chooseSmartRepliesAndActions(mSmartReplyConstants, mEntry);

        assertThat(repliesAndActions.smartReplies).isNull();
        assertThat(repliesAndActions.smartActions).isNull();
    }

    @Test
    public void chooseSmartRepliesAndActions_smartRepliesOff_noSystemGeneratedSmartSuggestions() {
        modifyRanking(mEntry)
                .setSmartReplies(createReplies("Sys Smart Reply 1", "Sys Smart Reply 2"))
                .setSmartActions(createActions("Sys Smart Action 1", "Sys Smart Action 2"))
                .build();

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

        assertThat(repliesAndActions.smartReplies.choices).isEqualTo(Arrays.asList(smartReplies));
        assertThat(repliesAndActions.smartReplies.fromAssistant).isFalse();
        assertThat(repliesAndActions.smartActions).isNull();
    }

    @Test
    public void chooseSmartRepliesAndActions_appGeneratedSmartRepliesAndActions() {
        CharSequence[] smartReplies = new String[] {"Reply1", "Reply2"};
        List<Notification.Action> smartActions =
                createActions("Test Action 1", "Test Action 2");
        setupAppGeneratedSuggestions(smartReplies, smartActions);

        SmartRepliesAndActions repliesAndActions =
                InflatedSmartReplies.chooseSmartRepliesAndActions(mSmartReplyConstants, mEntry);

        assertThat(repliesAndActions.smartReplies.choices).isEqualTo(Arrays.asList(smartReplies));
        assertThat(repliesAndActions.smartReplies.fromAssistant).isFalse();
        assertThat(repliesAndActions.smartActions.actions).isEqualTo(smartActions);
        assertThat(repliesAndActions.smartActions.fromAssistant).isFalse();
    }

    @Test
    public void chooseSmartRepliesAndActions_sysGeneratedSmartReplies() {
        // Pass a null-array as app-generated smart replies, so that we use NAS-generated smart
        // replies.
        setupAppGeneratedReplies(null /* smartReplies */);

        modifyRanking(mEntry)
                .setSmartReplies(createReplies("Sys Smart Reply 1", "Sys Smart Reply 2"))
                .build();

        SmartRepliesAndActions repliesAndActions =
                InflatedSmartReplies.chooseSmartRepliesAndActions(mSmartReplyConstants, mEntry);

        assertThat(repliesAndActions.smartReplies.choices).isEqualTo(
                mEntry.getSmartReplies());
        assertThat(repliesAndActions.smartReplies.fromAssistant).isTrue();
        assertThat(repliesAndActions.smartActions).isNull();
    }

    @Test
    public void chooseSmartRepliesAndActions_noSysGeneratedSmartRepliesIfNotAllowed() {
        // Pass a null-array as app-generated smart replies, so that we use NAS-generated smart
        // replies.
        setupAppGeneratedReplies(null /* smartReplies */, false /* allowSystemGeneratedReplies */);

        NotificationEntryHelper.modifyRanking(mEntry)
                .setSmartReplies(createReplies("Sys Smart Reply 1", "Sys Smart Reply 2"))
                .build();
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

        modifyRanking(mEntry)
                .setSmartActions(createActions("Sys Smart Action 1", "Sys Smart Action 2"))
                .build();
        SmartRepliesAndActions repliesAndActions =
                InflatedSmartReplies.chooseSmartRepliesAndActions(mSmartReplyConstants, mEntry);

        assertThat(repliesAndActions.smartReplies).isNull();
        assertThat(repliesAndActions.smartActions.actions)
                .isEqualTo(mEntry.getSmartActions());
        assertThat(repliesAndActions.smartActions.fromAssistant).isTrue();
    }

    @Test
    public void chooseSmartRepliesAndActions_appGenPreferredOverSysGen() {
        CharSequence[] appGenSmartReplies = new String[] {"Reply1", "Reply2"};
        // Pass a null-array as app-generated smart replies, so that we use NAS-generated smart
        // replies.
        List<Notification.Action> appGenSmartActions =
                createActions("Test Action 1", "Test Action 2");
        setupAppGeneratedSuggestions(appGenSmartReplies, appGenSmartActions);

        modifyRanking(mEntry)
                .setSmartReplies(createReplies("Sys Smart Reply 1", "Sys Smart Reply 2"))
                .setSmartActions(createActions("Sys Smart Action 1", "Sys Smart Action 2"))
                .build();

        SmartRepliesAndActions repliesAndActions =
                InflatedSmartReplies.chooseSmartRepliesAndActions(mSmartReplyConstants, mEntry);

        assertThat(repliesAndActions.smartReplies.choices)
                .isEqualTo(Arrays.asList(appGenSmartReplies));
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

        modifyRanking(mEntry)
                .setSmartReplies(createReplies("Sys Smart Reply 1", "Sys Smart Reply 2"))
                .setSmartActions(createActions("Sys Smart Action 1", "Sys Smart Action 2"))
                .build();

        SmartRepliesAndActions repliesAndActions =
                InflatedSmartReplies.chooseSmartRepliesAndActions(mSmartReplyConstants, mEntry);

        assertThat(repliesAndActions.smartActions).isNull();
        assertThat(repliesAndActions.smartReplies).isNull();
    }

    @Test
    public void chooseSmartRepliesAndActions_lockTaskKioskModeEnabled_smartRepliesUnaffected() {
        when(mActivityManagerWrapper.isLockTaskKioskModeActive()).thenReturn(true);
        // No apps are white-listed
        when(mDevicePolicyManagerWrapper.isLockTaskPermitted(anyString())).thenReturn(false);

        // Pass a null-array as app-generated smart replies, so that we use NAS-generated smart
        // suggestions.
        setupAppGeneratedReplies(null /* smartReplies */);

        modifyRanking(mEntry)
                .setSmartReplies(createReplies("Sys Smart Reply 1", "Sys Smart Reply 2"))
                .setSmartActions(createActions("Sys Smart Action 1", "Sys Smart Action 2"))
                .build();

        SmartRepliesAndActions repliesAndActions =
                InflatedSmartReplies.chooseSmartRepliesAndActions(mSmartReplyConstants, mEntry);

        assertThat(repliesAndActions.smartReplies.choices).isEqualTo(
                mEntry.getSmartReplies());
        // Since no apps are whitelisted no actions should be shown.
        assertThat(repliesAndActions.smartActions.actions).isEmpty();
    }

    @Test
    public void chooseSmartRepliesAndActions_lockTaskKioskModeEnabled_smartActionsAffected() {
        when(mActivityManagerWrapper.isLockTaskKioskModeActive()).thenReturn(true);
        String allowedPackage = "allowedPackage";
        ResolveInfo allowedResolveInfo = new ResolveInfo();
        allowedResolveInfo.activityInfo = new ActivityInfo();
        allowedResolveInfo.activityInfo.packageName = allowedPackage;
        when(mPackageManagerWrapper
                .resolveActivity(
                        argThat(intent -> WHITELISTED_TEST_INTENT.getAction().equals(
                                intent.getAction())),
                        anyInt() /* flags */))
                .thenReturn(allowedResolveInfo);
        when(mDevicePolicyManagerWrapper.isLockTaskPermitted(allowedPackage)).thenReturn(true);

        // Pass a null-array as app-generated smart replies, so that we use NAS-generated smart
        // suggestions.
        setupAppGeneratedReplies(null /* smartReplies */);
        ArrayList<Notification.Action> actions = new ArrayList<>();
        actions.add(createAction("allowed action", WHITELISTED_TEST_INTENT));
        actions.add(createAction("non-allowed action", TEST_INTENT));

        modifyRanking(mEntry)
                .setSmartReplies(createReplies("Sys Smart Reply 1", "Sys Smart Reply 2"))
                .setSmartActions(actions)
                .build();

        SmartRepliesAndActions repliesAndActions =
                InflatedSmartReplies.chooseSmartRepliesAndActions(mSmartReplyConstants, mEntry);

        // Only the action for the whitelisted package should be allowed.
        assertThat(repliesAndActions.smartActions.actions.size()).isEqualTo(1);
        assertThat(repliesAndActions.smartActions.actions.get(0)).isEqualTo(
                mEntry.getSmartActions().get(0));
    }

    @Test
    public void chooseSmartRepliesAndActions_screenPinningModeEnabled_suggestionsUnaffected() {
        when(mActivityManagerWrapper.isLockToAppActive()).thenReturn(true);
        // No apps are white-listed
        when(mDevicePolicyManagerWrapper.isLockTaskPermitted(anyString())).thenReturn(false);

        // Pass a null-array as app-generated smart replies, so that we use NAS-generated smart
        // suggestions.
        setupAppGeneratedReplies(null /* smartReplies */);
        modifyRanking(mEntry)
                .setSmartReplies(createReplies("Sys Smart Reply 1", "Sys Smart Reply 2"))
                .setSmartActions(createActions("Sys Smart Action 1", "Sys Smart Action 2"))
                .build();

        SmartRepliesAndActions repliesAndActions =
                InflatedSmartReplies.chooseSmartRepliesAndActions(mSmartReplyConstants, mEntry);

        // We don't restrict replies or actions in screen pinning mode.
        assertThat(repliesAndActions.smartReplies.choices).isEqualTo(
                mEntry.getSmartReplies());
        assertThat(repliesAndActions.smartActions.actions).isEqualTo(
                mEntry.getSmartActions());
    }

    @Test
    public void areSuggestionsSimilar_trueForSimilar() {
        List<CharSequence> leftReplies = createReplies("first reply", "second reply");
        List<CharSequence> rightReplies = createReplies("first reply", "second reply");
        List<Notification.Action> leftActions = Arrays.asList(
                createAction("firstAction"),
                createAction("secondAction"));
        List<Notification.Action> rightActions = Arrays.asList(
                createAction("firstAction"),
                createAction("secondAction"));

        SmartRepliesAndActions leftRepliesAndActions = new SmartRepliesAndActions(
                new SmartReplies(leftReplies, null, null, false /* fromAssistant */),
                new SmartActions(leftActions, false /* fromAssistant */));
        SmartRepliesAndActions rightRepliesAndActions = new SmartRepliesAndActions(
                new SmartReplies(rightReplies, null, null, false /* fromAssistant */),
                new SmartActions(rightActions, false /* fromAssistant */));

        assertThat(InflatedSmartReplies.areSuggestionsSimilar(
                leftRepliesAndActions, rightRepliesAndActions)).isTrue();
    }

    @Test
    public void areSuggestionsSimilar_falseForDifferentReplies() {
        List<CharSequence> leftReplies = createReplies("first reply");
        List<CharSequence> rightReplies = createReplies("first reply", "second reply");
        List<Notification.Action> leftActions = Arrays.asList(
                createAction("firstAction"),
                createAction("secondAction"));
        List<Notification.Action> rightActions = Arrays.asList(
                createAction("firstAction"),
                createAction("secondAction"));

        SmartRepliesAndActions leftRepliesAndActions = new SmartRepliesAndActions(
                new SmartReplies(leftReplies, null, null, false /* fromAssistant */),
                new SmartActions(leftActions, false /* fromAssistant */));
        SmartRepliesAndActions rightRepliesAndActions = new SmartRepliesAndActions(
                new SmartReplies(rightReplies, null, null, false /* fromAssistant */),
                new SmartActions(rightActions, false /* fromAssistant */));

        assertThat(InflatedSmartReplies.areSuggestionsSimilar(
                leftRepliesAndActions, rightRepliesAndActions)).isFalse();
    }

    @Test
    public void areSuggestionsSimilar_falseForDifferentActions() {
        List<CharSequence> leftReplies = createReplies("first reply", "second reply");
        List<CharSequence> rightReplies = createReplies("first reply", "second reply");
        List<Notification.Action> leftActions = Arrays.asList(
                createAction("firstAction"),
                createAction("secondAction"));
        List<Notification.Action> rightActions = Arrays.asList(
                createAction("firstAction"),
                createAction("not secondAction"));

        SmartRepliesAndActions leftRepliesAndActions = new SmartRepliesAndActions(
                new SmartReplies(leftReplies, null, null, false /* fromAssistant */),
                new SmartActions(leftActions, false /* fromAssistant */));
        SmartRepliesAndActions rightRepliesAndActions = new SmartRepliesAndActions(
                new SmartReplies(rightReplies, null, null, false /* fromAssistant */),
                new SmartActions(rightActions, false /* fromAssistant */));

        assertThat(InflatedSmartReplies.areSuggestionsSimilar(
                leftRepliesAndActions, rightRepliesAndActions)).isFalse();
    }

    private void setupAppGeneratedReplies(CharSequence[] smartReplies) {
        setupAppGeneratedReplies(smartReplies, true /* allowSystemGeneratedReplies */);
    }

    private void setupAppGeneratedReplies(
            CharSequence[] smartReplies, boolean allowSystemGeneratedReplies) {
        PendingIntent pendingIntent =
                PendingIntent.getBroadcast(mContext, 0, TEST_INTENT, 0);
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
        return createActionBuilder(actionTitle, TEST_INTENT);
    }

    private Notification.Action.Builder createActionBuilder(String actionTitle, Intent intent) {
        PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, 0, intent, 0);
        return new Notification.Action.Builder(mActionIcon, actionTitle, pendingIntent);
    }

    private Notification.Action createAction(String actionTitle) {
        return createActionBuilder(actionTitle).build();
    }

    private Notification.Action createAction(String actionTitle, Intent intent) {
        return createActionBuilder(actionTitle, intent).build();
    }

    private ArrayList<Notification.Action> createActions(String... actionTitles) {
        ArrayList<Notification.Action> actions = new ArrayList<>();
        for (String title : actionTitles) {
            actions.add(createAction(title));
        }
        return actions;
    }

    private ArrayList<CharSequence> createReplies(CharSequence... replies) {
        return new ArrayList<>(Arrays.asList(replies));
    }
}
