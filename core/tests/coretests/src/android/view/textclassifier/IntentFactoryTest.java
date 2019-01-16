/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import android.content.Intent;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.google.android.textclassifier.AnnotatorModel;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class IntentFactoryTest {

    private static final String TEXT = "text";

    @Test
    public void create_typeDictionary() {
        AnnotatorModel.ClassificationResult classificationResult =
                new AnnotatorModel.ClassificationResult(
                        TextClassifier.TYPE_DICTIONARY,
                        1.0f,
                        null,
                        null);

        List<TextClassifierImpl.LabeledIntent> intents = TextClassifierImpl.IntentFactory.create(
                InstrumentationRegistry.getContext(),
                TEXT,
                false,
                null,
                classificationResult);

        assertThat(intents).hasSize(1);
        TextClassifierImpl.LabeledIntent labeledIntent = intents.get(0);
        Intent intent = labeledIntent.getIntent();
        assertThat(intent.getAction()).isEqualTo(Intent.ACTION_DEFINE);
        assertThat(intent.getStringExtra(Intent.EXTRA_TEXT)).isEqualTo(TEXT);
        assertThat(
                intent.getBooleanExtra(TextClassifier.EXTRA_FROM_TEXT_CLASSIFIER, false)).isTrue();
    }
}
