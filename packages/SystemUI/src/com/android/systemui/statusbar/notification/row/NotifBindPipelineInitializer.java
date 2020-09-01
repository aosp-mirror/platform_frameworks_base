/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.row;

import javax.inject.Inject;

/**
 * Initialize {@link NotifBindPipeline} with all its mandatory stages and dynamically added stages.
 *
 * In the future, coordinators should be able to register their own {@link BindStage} to the
 * {@link NotifBindPipeline}.
 */
public class NotifBindPipelineInitializer {
    NotifBindPipeline mNotifBindPipeline;
    RowContentBindStage mRowContentBindStage;

    @Inject
    NotifBindPipelineInitializer(
            NotifBindPipeline pipeline,
            RowContentBindStage stage) {
        mNotifBindPipeline = pipeline;
        mRowContentBindStage = stage;
        // TODO: Inject coordinators and allow them to add BindStages in initialize
    }

    /**
     * Hooks up stages to the pipeline.
     */
    public void initialize() {
        // Mandatory bind stages
        mNotifBindPipeline.setStage(mRowContentBindStage);
    }
}
