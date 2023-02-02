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

import com.android.internal.app.AssistUtils;
import com.android.internal.app.IVisualQueryDetectionAttentionListener;
import com.android.systemui.dreams.DreamOverlayStateController;
import com.android.systemui.shared.condition.Condition;

import javax.inject.Inject;

/**
 * {@link AssistantAttentionCondition} provides a signal when assistant has the user's attention.
 */
public class AssistantAttentionCondition extends Condition {
    private final DreamOverlayStateController mDreamOverlayStateController;
    private final AssistUtils mAssistUtils;
    private boolean mEnabled;

    private final IVisualQueryDetectionAttentionListener mVisualQueryDetectionAttentionListener =
            new IVisualQueryDetectionAttentionListener.Stub() {
        @Override
        public void onAttentionGained() {
            updateCondition(true);
        }

        @Override
        public void onAttentionLost() {
            updateCondition(false);
        }
    };

    private final DreamOverlayStateController.Callback mCallback =
            new DreamOverlayStateController.Callback() {
        @Override
        public void onStateChanged() {
            if (mDreamOverlayStateController.isDreamOverlayStatusBarVisible()) {
                enableVisualQueryDetection();
            } else {
                disableVisualQueryDetection();
            }
        }
    };

    @Inject
    public AssistantAttentionCondition(
            DreamOverlayStateController dreamOverlayStateController,
            AssistUtils assistUtils) {
        mDreamOverlayStateController = dreamOverlayStateController;
        mAssistUtils = assistUtils;
    }

    @Override
    protected void start() {
        mDreamOverlayStateController.addCallback(mCallback);
    }

    @Override
    protected void stop() {
        disableVisualQueryDetection();
        mDreamOverlayStateController.removeCallback(mCallback);
    }

    private void enableVisualQueryDetection() {
        if (mEnabled) {
            return;
        }
        mEnabled = true;
        mAssistUtils.enableVisualQueryDetection(mVisualQueryDetectionAttentionListener);
    }

    private void disableVisualQueryDetection() {
        if (!mEnabled) {
            return;
        }
        mEnabled = false;
        mAssistUtils.disableVisualQueryDetection();
        // Make sure the condition is set to false as well.
        updateCondition(false);
    }
}
