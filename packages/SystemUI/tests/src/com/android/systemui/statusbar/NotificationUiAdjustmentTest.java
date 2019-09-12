/*
 * Copyright 2018 The Android Open Source Project
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
package com.android.systemui.statusbar;

import static com.google.common.truth.Truth.assertThat;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Intent;
import android.graphics.drawable.Icon;

import androidx.test.filters.SmallTest;

import com.android.internal.R;
import com.android.systemui.SysuiTestCase;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@SmallTest
public class NotificationUiAdjustmentTest extends SysuiTestCase {

    @Test
    public void needReinflate_differentLength() {
        PendingIntent pendingIntent =
                PendingIntent.getActivity(mContext, 0, new Intent(), 0);
        Notification.Action action =
                createActionBuilder("first", R.drawable.ic_corp_icon, pendingIntent).build();
        assertThat(NotificationUiAdjustment.needReinflate(
                createUiAdjustmentFromSmartActions("first", Collections.emptyList()),
                createUiAdjustmentFromSmartActions("second", Collections.singletonList(action))))
                .isTrue();
    }

    @Test
    public void needReinflate_differentLabels() {
        PendingIntent pendingIntent =
                PendingIntent.getActivity(mContext, 0, new Intent(), 0);
        Notification.Action firstAction =
                createActionBuilder("first", R.drawable.ic_corp_icon, pendingIntent).build();
        Notification.Action secondAction =
                createActionBuilder("second", R.drawable.ic_corp_icon, pendingIntent).build();

        assertThat(NotificationUiAdjustment.needReinflate(
                createUiAdjustmentFromSmartActions("first", Collections.singletonList(firstAction)),
                createUiAdjustmentFromSmartActions("second", Collections.singletonList(secondAction))))
                .isTrue();
    }

    @Test
    public void needReinflate_differentIcons() {
        PendingIntent pendingIntent =
                PendingIntent.getActivity(mContext, 0, new Intent(), 0);
        Notification.Action firstAction =
                createActionBuilder("same", R.drawable.ic_corp_icon, pendingIntent).build();
        Notification.Action secondAction =
                createActionBuilder("same", R.drawable.ic_account_circle, pendingIntent)
                        .build();

        assertThat(NotificationUiAdjustment.needReinflate(
                createUiAdjustmentFromSmartActions("first", Collections.singletonList(firstAction)),
                createUiAdjustmentFromSmartActions("second", Collections.singletonList(secondAction))))
                .isTrue();
    }

    @Test
    public void needReinflate_differentPendingIntent() {
        PendingIntent firstPendingIntent =
                PendingIntent.getActivity(mContext, 0, new Intent(Intent.ACTION_VIEW), 0);
        PendingIntent secondPendingIntent =
                PendingIntent.getActivity(mContext, 0, new Intent(Intent.ACTION_PROCESS_TEXT), 0);
        Notification.Action firstAction =
                createActionBuilder("same", R.drawable.ic_corp_icon, firstPendingIntent)
                        .build();
        Notification.Action secondAction =
                createActionBuilder("same", R.drawable.ic_corp_icon, secondPendingIntent)
                        .build();

        assertThat(NotificationUiAdjustment.needReinflate(
                createUiAdjustmentFromSmartActions("first", Collections.singletonList(firstAction)),
                createUiAdjustmentFromSmartActions("second", Collections.singletonList(secondAction))))
                .isTrue();
    }

    @Test
    public void needReinflate_differentChoices() {
        PendingIntent pendingIntent =
                PendingIntent.getActivity(mContext, 0, new Intent(), 0);

        RemoteInput firstRemoteInput =
                createRemoteInput("same", "same", new CharSequence[] {"first"});
        RemoteInput secondRemoteInput =
                createRemoteInput("same", "same", new CharSequence[] {"second"});

        Notification.Action firstAction =
                createActionBuilder("same", R.drawable.ic_corp_icon, pendingIntent)
                        .addRemoteInput(firstRemoteInput)
                        .build();
        Notification.Action secondAction =
                createActionBuilder("same", R.drawable.ic_corp_icon, pendingIntent)
                        .addRemoteInput(secondRemoteInput)
                        .build();

        assertThat(NotificationUiAdjustment.needReinflate(
                createUiAdjustmentFromSmartActions("first", Collections.singletonList(firstAction)),
                createUiAdjustmentFromSmartActions("second", Collections.singletonList(secondAction))))
                .isTrue();
    }

    @Test
    public void needReinflate_differentRemoteInputLabel() {
        PendingIntent pendingIntent =
                PendingIntent.getActivity(mContext, 0, new Intent(), 0);

        RemoteInput firstRemoteInput =
                createRemoteInput("same", "first", new CharSequence[] {"same"});
        RemoteInput secondRemoteInput =
                createRemoteInput("same", "second", new CharSequence[] {"same"});

        Notification.Action firstAction =
                createActionBuilder("same", R.drawable.ic_corp_icon, pendingIntent)
                        .addRemoteInput(firstRemoteInput)
                        .build();
        Notification.Action secondAction =
                createActionBuilder("same", R.drawable.ic_corp_icon, pendingIntent)
                        .addRemoteInput(secondRemoteInput)
                        .build();

        assertThat(NotificationUiAdjustment.needReinflate(
                createUiAdjustmentFromSmartActions("first", Collections.singletonList(firstAction)),
                createUiAdjustmentFromSmartActions("second", Collections.singletonList(secondAction))))
                .isTrue();
    }

    @Test
    public void needReinflate_negative() {
        PendingIntent pendingIntent =
                PendingIntent.getActivity(mContext, 0, new Intent(), 0);
        RemoteInput firstRemoteInput =
                createRemoteInput("same", "same", new CharSequence[] {"same"});
        RemoteInput secondRemoteInput =
                createRemoteInput("same", "same", new CharSequence[] {"same"});

        Notification.Action firstAction =
                createActionBuilder("same", R.drawable.ic_corp_icon, pendingIntent)
                        .addRemoteInput(firstRemoteInput).build();
        Notification.Action secondAction =
                createActionBuilder("same", R.drawable.ic_corp_icon, pendingIntent)
                        .addRemoteInput(secondRemoteInput).build();

        assertThat(NotificationUiAdjustment.needReinflate(
                createUiAdjustmentFromSmartActions("first", Collections.singletonList(firstAction)),
                createUiAdjustmentFromSmartActions(
                        "second", Collections.singletonList(secondAction))))
                .isFalse();
    }

    @Test
    public void needReinflate_differentSmartReplies() {
        assertThat(NotificationUiAdjustment.needReinflate(
                createUiAdjustmentFromSmartReplies("first", new CharSequence[]{"a", "b"}),
                createUiAdjustmentFromSmartReplies("first", new CharSequence[] {"b", "a"})))
                .isTrue();
    }

    @Test
    public void needReinflate_sameSmartReplies() {
        assertThat(NotificationUiAdjustment.needReinflate(
                createUiAdjustmentFromSmartReplies("first", new CharSequence[] {"a", "b"}),
                createUiAdjustmentFromSmartReplies("first", new CharSequence[] {"a", "b"})))
                .isFalse();
    }

    private Notification.Action.Builder createActionBuilder(
            String title, int drawableRes, PendingIntent pendingIntent) {
        return new Notification.Action.Builder(
                Icon.createWithResource(mContext, drawableRes), title, pendingIntent);
    }

    private RemoteInput createRemoteInput(String resultKey, String label, CharSequence[] choices) {
        return new RemoteInput.Builder(resultKey).setLabel(label).setChoices(choices).build();
    }

    private NotificationUiAdjustment createUiAdjustmentFromSmartActions(
            String key, List<Notification.Action> actions) {
        return new NotificationUiAdjustment(key, actions, null);
    }

    private NotificationUiAdjustment createUiAdjustmentFromSmartReplies(
            String key, CharSequence[] replies) {
        return new NotificationUiAdjustment(key, null, Arrays.asList(replies));
    }
}
