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
package android.view.textclassifier.intent;

import static com.google.common.truth.Truth.assertThat;

import android.content.Intent;
import android.net.Uri;
import android.view.textclassifier.TextClassifier;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.google.android.textclassifier.NamedVariant;
import com.google.android.textclassifier.RemoteActionTemplate;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class TemplateIntentFactoryTest {

    private static final String TEXT = "text";
    private static final String TITLE_WITHOUT_ENTITY = "Map";
    private static final String TITLE_WITH_ENTITY = "Map NW14D1";
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

    private TemplateIntentFactory mTemplateIntentFactory;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mTemplateIntentFactory = new TemplateIntentFactory();
    }

    @Test
    public void create_full() {
        RemoteActionTemplate remoteActionTemplate = new RemoteActionTemplate(
                TITLE_WITHOUT_ENTITY,
                TITLE_WITH_ENTITY,
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

        List<LabeledIntent> intents =
                mTemplateIntentFactory.create(new RemoteActionTemplate[]{remoteActionTemplate});

        assertThat(intents).hasSize(1);
        LabeledIntent labeledIntent = intents.get(0);
        assertThat(labeledIntent.titleWithoutEntity).isEqualTo(TITLE_WITHOUT_ENTITY);
        assertThat(labeledIntent.titleWithEntity).isEqualTo(TITLE_WITH_ENTITY);
        assertThat(labeledIntent.description).isEqualTo(DESCRIPTION);
        assertThat(labeledIntent.requestCode).isEqualTo(REQUEST_CODE);
        Intent intent = labeledIntent.intent;
        assertThat(intent.getAction()).isEqualTo(ACTION);
        assertThat(intent.getData().toString()).isEqualTo(DATA);
        assertThat(intent.getType()).isEqualTo(TYPE);
        assertThat(intent.getFlags()).isEqualTo(FLAG);
        assertThat(intent.getCategories()).containsExactly((Object[]) CATEGORY);
        assertThat(intent.getPackage()).isNull();
        assertThat(intent.getStringExtra(KEY_ONE)).isEqualTo(VALUE_ONE);
        assertThat(intent.getIntExtra(KEY_TWO, 0)).isEqualTo(VALUE_TWO);
        assertThat(intent.hasExtra(TextClassifier.EXTRA_FROM_TEXT_CLASSIFIER)).isTrue();
    }

    @Test
    public void normalizesScheme() {
        RemoteActionTemplate remoteActionTemplate = new RemoteActionTemplate(
                TITLE_WITHOUT_ENTITY,
                TITLE_WITH_ENTITY,
                DESCRIPTION,
                ACTION,
                "HTTp://www.android.com",
                TYPE,
                FLAG,
                CATEGORY,
                /* packageName */ null,
                NAMED_VARIANTS,
                REQUEST_CODE
        );

        List<LabeledIntent> intents =
                mTemplateIntentFactory.create(new RemoteActionTemplate[] {remoteActionTemplate});

        String data = intents.get(0).intent.getData().toString();
        assertThat(data).isEqualTo("http://www.android.com");
    }

    @Test
    public void create_minimal() {
        RemoteActionTemplate remoteActionTemplate = new RemoteActionTemplate(
                TITLE_WITHOUT_ENTITY,
                null,
                DESCRIPTION,
                ACTION,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        List<LabeledIntent> intents =
                mTemplateIntentFactory.create(new RemoteActionTemplate[]{remoteActionTemplate});

        assertThat(intents).hasSize(1);
        LabeledIntent labeledIntent = intents.get(0);
        assertThat(labeledIntent.titleWithoutEntity).isEqualTo(TITLE_WITHOUT_ENTITY);
        assertThat(labeledIntent.titleWithEntity).isNull();
        assertThat(labeledIntent.description).isEqualTo(DESCRIPTION);
        assertThat(labeledIntent.requestCode).isEqualTo(
                LabeledIntent.DEFAULT_REQUEST_CODE);
        Intent intent = labeledIntent.intent;
        assertThat(intent.getAction()).isEqualTo(ACTION);
        assertThat(intent.getData()).isNull();
        assertThat(intent.getType()).isNull();
        assertThat(intent.getFlags()).isEqualTo(0);
        assertThat(intent.getCategories()).isNull();
        assertThat(intent.getPackage()).isNull();
        assertThat(intent.hasExtra(TextClassifier.EXTRA_FROM_TEXT_CLASSIFIER)).isTrue();
    }

    @Test
    public void invalidTemplate_nullTemplate() {
        RemoteActionTemplate remoteActionTemplate = null;

        List<LabeledIntent> intents =
                mTemplateIntentFactory.create(new RemoteActionTemplate[] {remoteActionTemplate});

        assertThat(intents).isEmpty();
    }

    @Test
    public void invalidTemplate_nonEmptyPackageName() {
        RemoteActionTemplate remoteActionTemplate = new RemoteActionTemplate(
                TITLE_WITHOUT_ENTITY,
                TITLE_WITH_ENTITY,
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

        List<LabeledIntent> intents =
                mTemplateIntentFactory.create(new RemoteActionTemplate[] {remoteActionTemplate});

        assertThat(intents).isEmpty();
    }

    @Test
    public void invalidTemplate_emptyTitle() {
        RemoteActionTemplate remoteActionTemplate = new RemoteActionTemplate(
                null,
                null,
                DESCRIPTION,
                ACTION,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        List<LabeledIntent> intents =
                mTemplateIntentFactory.create(new RemoteActionTemplate[] {remoteActionTemplate});

        assertThat(intents).isEmpty();
    }

    @Test
    public void invalidTemplate_emptyDescription() {
        RemoteActionTemplate remoteActionTemplate = new RemoteActionTemplate(
                TITLE_WITHOUT_ENTITY,
                TITLE_WITH_ENTITY,
                null,
                ACTION,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        List<LabeledIntent> intents =
                mTemplateIntentFactory.create(new RemoteActionTemplate[] {remoteActionTemplate});

        assertThat(intents).isEmpty();
    }

    @Test
    public void invalidTemplate_emptyIntentAction() {
        RemoteActionTemplate remoteActionTemplate = new RemoteActionTemplate(
                TITLE_WITHOUT_ENTITY,
                TITLE_WITH_ENTITY,
                DESCRIPTION,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        List<LabeledIntent> intents =
                mTemplateIntentFactory.create(new RemoteActionTemplate[] {remoteActionTemplate});

        assertThat(intents).isEmpty();
    }
}
