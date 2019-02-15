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
package android.view.textclassifier;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.Preconditions;

import com.google.android.textclassifier.AnnotatorModel;
import com.google.android.textclassifier.RemoteActionTemplate;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * Creates intents based on {@link RemoteActionTemplate} objects for a ClassificationResult.
 *
 * @hide
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public final class TemplateClassificationIntentFactory implements IntentFactory {
    private static final String TAG = TextClassifier.DEFAULT_LOG_TAG;
    private final TemplateIntentFactory mTemplateIntentFactory;
    private final IntentFactory mFallback;

    public TemplateClassificationIntentFactory(TemplateIntentFactory templateIntentFactory,
            IntentFactory fallback) {
        mTemplateIntentFactory = Preconditions.checkNotNull(templateIntentFactory);
        mFallback = Preconditions.checkNotNull(fallback);
    }

    /**
     * Returns a list of {@link android.view.textclassifier.LabeledIntent}
     * that are constructed from the classification result.
     */
    @NonNull
    @Override
    public List<LabeledIntent> create(
            Context context,
            String text,
            boolean foreignText,
            @Nullable Instant referenceTime,
            @Nullable AnnotatorModel.ClassificationResult classification) {
        if (classification == null) {
            return Collections.emptyList();
        }
        RemoteActionTemplate[] remoteActionTemplates = classification.getRemoteActionTemplates();
        if (ArrayUtils.isEmpty(remoteActionTemplates)) {
            // RemoteActionTemplate is missing, fallback.
            Log.w(TAG, "RemoteActionTemplate is missing, fallback to LegacyIntentFactory.");
            return mFallback.create(context, text, foreignText, referenceTime, classification);
        }
        final List<LabeledIntent> labeledIntents =
                mTemplateIntentFactory.create(remoteActionTemplates);
        if (foreignText) {
            IntentFactory.insertTranslateAction(labeledIntents, context, text.trim());
        }
        return labeledIntents;
    }
}
