/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.dreams.conditions;

import com.android.systemui.assist.AssistManager;
import com.android.systemui.assist.AssistManager.VisualQueryAttentionListener;
import com.android.systemui.dagger.qualifiers.Application;
import com.android.systemui.shared.condition.Condition;

import kotlinx.coroutines.CoroutineScope;

import javax.inject.Inject;

/**
 * {@link AssistantAttentionCondition} provides a signal when assistant has the user's attention.
 */
public class AssistantAttentionCondition extends Condition {
    private final AssistManager mAssistManager;

    private final VisualQueryAttentionListener mVisualQueryAttentionListener =
            new VisualQueryAttentionListener() {
        @Override
        public void onAttentionGained() {
            updateCondition(true);
        }

        @Override
        public void onAttentionLost() {
            updateCondition(false);
        }
    };

    @Inject
    public AssistantAttentionCondition(
            @Application CoroutineScope scope,
            AssistManager assistManager) {
        super(scope);
        mAssistManager = assistManager;
    }

    @Override
    protected void start() {
        mAssistManager.addVisualQueryAttentionListener(mVisualQueryAttentionListener);
    }

    @Override
    protected void stop() {
        mAssistManager.removeVisualQueryAttentionListener(mVisualQueryAttentionListener);
    }

    @Override
    protected int getStartStrategy() {
        return START_EAGERLY;
    }
}
