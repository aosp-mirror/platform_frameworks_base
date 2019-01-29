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

import static com.google.common.truth.Truth.assertThat;

import android.content.Intent;
import android.net.Uri;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.google.android.textclassifier.AnnotatorModel;
import com.google.android.textclassifier.NamedVariant;
import com.google.android.textclassifier.RemoteActionTemplate;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class TemplateIntentFactoryTest {

    private static final String TEXT = "text";
    private static final String TITLE = "Map";
    private static final String DESCRIPTION = "Check the map";
    private static final String ACTION = Intent.ACTION_VIEW;
    private static final String DATA = Uri.parse("http://www.android.com").toString();
    private static final String TYPE = "text/html";
    private static final Integer FLAG = Intent.FLAG_ACTIVITY_NEW_TASK;
    private static final String[] CATEGORY =
            new String[]{Intent.CATEGORY_DEFAULT, Intent.CATEGORY_APP_BROWSER};
    private static final String PACKAGE_NAME = "pkg.name";
    private static final String KEY_ONE = "key1";
    private static final String VALUE_ONE = "value1";
    private static final String KEY_TWO = "key2";
    private static final int VALUE_TWO = 42;

    private static final NamedVariant[] NAMED_VARIANTS = new NamedVariant[]{
            new NamedVariant(KEY_ONE, VALUE_ONE),
            new NamedVariant(KEY_TWO, VALUE_TWO)
    };
    private static final Integer REQUEST_CODE = 10;

    @Mock
    private IntentFactory mFallback;
    private TemplateIntentFactory mTemplateIntentFactory;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mTemplateIntentFactory = new TemplateIntentFactory(mFallback);
    }

    @Test
    public void create_full() {
        RemoteActionTemplate remoteActionTemplate = new RemoteActionTemplate(
                TITLE,
                DESCRIPTION,
                ACTION,
                DATA,
                TYPE,
                FLAG,
                CATEGORY,
                /* packageName */ null,
                NAMED_VARIANTS,
                REQUEST_CODE
        );

        AnnotatorModel.ClassificationResult classificationResult =
                new AnnotatorModel.ClassificationResult(
                        TextClassifier.TYPE_ADDRESS,
                        1.0f,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new RemoteActionTemplate[]{remoteActionTemplate});

        List<TextClassifierImpl.LabeledIntent> intents = mTemplateIntentFactory.create(
                InstrumentationRegistry.getContext(),
                TEXT,
                false,
                null,
                classificationResult);

        assertThat(intents).hasSize(1);
        TextClassifierImpl.LabeledIntent labeledIntent = intents.get(0);
        assertThat(labeledIntent.getTitle()).isEqualTo(TITLE);
        assertThat(labeledIntent.getDescription()).isEqualTo(DESCRIPTION);
        assertThat(labeledIntent.getRequestCode()).isEqualTo(REQUEST_CODE);
        Intent intent = labeledIntent.getIntent();
        assertThat(intent.getAction()).isEqualTo(ACTION);
        assertThat(intent.getData().toString()).isEqualTo(DATA);
        assertThat(intent.getType()).isEqualTo(TYPE);
        assertThat(intent.getFlags()).isEqualTo(FLAG);
        assertThat(intent.getCategories()).containsExactly((Object[]) CATEGORY);
        assertThat(intent.getPackage()).isNull();
        assertThat(
                intent.getStringExtra(KEY_ONE)).isEqualTo(VALUE_ONE);
        assertThat(intent.getIntExtra(KEY_TWO, 0)).isEqualTo(VALUE_TWO);
        assertThat(
                intent.getBooleanExtra(TextClassifier.EXTRA_FROM_TEXT_CLASSIFIER, false)).isTrue();
    }

    @Test
    public void create_pacakgeIsNotNull() {
        RemoteActionTemplate remoteActionTemplate = new RemoteActionTemplate(
                TITLE,
                DESCRIPTION,
                ACTION,
                DATA,
                TYPE,
                FLAG,
                CATEGORY,
                PACKAGE_NAME,
                NAMED_VARIANTS,
                REQUEST_CODE
        );

        AnnotatorModel.ClassificationResult classificationResult =
                new AnnotatorModel.ClassificationResult(
                        TextClassifier.TYPE_ADDRESS,
                        1.0f,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new RemoteActionTemplate[]{remoteActionTemplate});

        List<TextClassifierImpl.LabeledIntent> intents = mTemplateIntentFactory.create(
                InstrumentationRegistry.getContext(),
                TEXT,
                false,
                null,
                classificationResult);

        assertThat(intents).hasSize(0);
    }

    @Test
    public void create_minimal() {
        RemoteActionTemplate remoteActionTemplate = new RemoteActionTemplate(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        AnnotatorModel.ClassificationResult classificationResult =
                new AnnotatorModel.ClassificationResult(
                        TextClassifier.TYPE_ADDRESS,
                        1.0f,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new RemoteActionTemplate[]{remoteActionTemplate});

        List<TextClassifierImpl.LabeledIntent> intents = mTemplateIntentFactory.create(
                InstrumentationRegistry.getContext(),
                TEXT,
                false,
                null,
                classificationResult);

        assertThat(intents).hasSize(1);
        TextClassifierImpl.LabeledIntent labeledIntent = intents.get(0);
        assertThat(labeledIntent.getTitle()).isNull();
        assertThat(labeledIntent.getDescription()).isNull();
        assertThat(labeledIntent.getRequestCode()).isEqualTo(
                TextClassifierImpl.LabeledIntent.DEFAULT_REQUEST_CODE);
        Intent intent = labeledIntent.getIntent();
        assertThat(intent.getAction()).isNull();
        assertThat(intent.getData()).isNull();
        assertThat(intent.getType()).isNull();
        assertThat(intent.getFlags()).isEqualTo(0);
        assertThat(intent.getCategories()).isNull();
        assertThat(intent.getPackage()).isNull();
        assertThat(
                intent.getBooleanExtra(TextClassifier.EXTRA_FROM_TEXT_CLASSIFIER, false)).isTrue();
    }
}
