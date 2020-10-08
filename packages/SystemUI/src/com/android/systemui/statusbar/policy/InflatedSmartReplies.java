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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Notification;
import android.widget.Button;

import java.util.Collections;
import java.util.List;

/**
 * Holder for inflated smart replies and actions. These objects should be inflated on a background
 * thread, to later be accessed and modified on the (performance critical) UI thread.
 */
public class InflatedSmartReplies {
    @Nullable private final SmartReplyView mSmartReplyView;
    @Nullable private final List<Button> mSmartSuggestionButtons;
    @NonNull private final SmartRepliesAndActions mSmartRepliesAndActions;

    public InflatedSmartReplies(
            @Nullable SmartReplyView smartReplyView,
            @Nullable List<Button> smartSuggestionButtons,
            @NonNull SmartRepliesAndActions smartRepliesAndActions) {
        mSmartReplyView = smartReplyView;
        mSmartSuggestionButtons = smartSuggestionButtons;
        mSmartRepliesAndActions = smartRepliesAndActions;
    }

    @Nullable public SmartReplyView getSmartReplyView() {
        return mSmartReplyView;
    }

    @Nullable public List<Button> getSmartSuggestionButtons() {
        return mSmartSuggestionButtons;
    }

    @NonNull public SmartRepliesAndActions getSmartRepliesAndActions() {
        return mSmartRepliesAndActions;
    }

    /**
     * A storage for smart replies and smart action.
     */
    public static class SmartRepliesAndActions {
        @Nullable public final SmartReplyView.SmartReplies smartReplies;
        @Nullable public final SmartReplyView.SmartActions smartActions;

        SmartRepliesAndActions(
                @Nullable SmartReplyView.SmartReplies smartReplies,
                @Nullable SmartReplyView.SmartActions smartActions) {
            this.smartReplies = smartReplies;
            this.smartActions = smartActions;
        }

        @NonNull public List<CharSequence> getSmartReplies() {
            return smartReplies == null ? Collections.emptyList() : smartReplies.choices;
        }

        @NonNull public List<Notification.Action> getSmartActions() {
            return smartActions == null ? Collections.emptyList() : smartActions.actions;
        }
    }
}
