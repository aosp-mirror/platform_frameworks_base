/*
 * Copyright (C) 2021 The Android Open Source Project
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

package androidx.window.extensions.organizer;

import androidx.annotation.NonNull;
import androidx.window.extensions.embedding.ActivityEmbeddingComponent;
import androidx.window.extensions.embedding.EmbeddingRule;
import androidx.window.extensions.embedding.SplitInfo;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Reference implementation of the activity embedding interface defined in WM Jetpack.
 */
public class EmbeddingExtensionImpl implements ActivityEmbeddingComponent {

    private final SplitController mSplitController;

    public EmbeddingExtensionImpl() {
        mSplitController = new SplitController();
    }

    @Override
    public void setEmbeddingRules(@NonNull Set<EmbeddingRule> rules) {
        mSplitController.setEmbeddingRules(rules);
    }

    @Override
    public void setEmbeddingCallback(@NonNull Consumer<List<SplitInfo>> consumer) {
        mSplitController.setEmbeddingCallback(consumer);
    }
}
