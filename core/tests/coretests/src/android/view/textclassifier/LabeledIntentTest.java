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

import static org.testng.Assert.assertThrows;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public final class LabeledIntentTest {
    private static final String TITLE_WITHOUT_ENTITY = "Map";
    private static final String TITLE_WITH_ENTITY = "Map NW14D1";
    private static final String DESCRIPTION = "Check the map";
    private static final Intent INTENT =
            new Intent(Intent.ACTION_VIEW).setDataAndNormalize(Uri.parse("http://www.android.com"));
    private static final int REQUEST_CODE = 42;
    private Context mContext;

    @Before
    public void setup() {
        mContext = InstrumentationRegistry.getTargetContext();
    }

    @Test
    public void resolve_preferTitleWithEntity() {
        LabeledIntent labeledIntent = new LabeledIntent(
                TITLE_WITHOUT_ENTITY,
                TITLE_WITH_ENTITY,
                DESCRIPTION,
                INTENT,
                REQUEST_CODE
        );

        LabeledIntent.Result result =
                labeledIntent.resolve(mContext, /*titleChooser*/ null);

        assertThat(result).isNotNull();
        assertThat(result.remoteAction.getTitle()).isEqualTo(TITLE_WITH_ENTITY);
        assertThat(result.remoteAction.getContentDescription()).isEqualTo(DESCRIPTION);
        Intent intent = result.resolvedIntent;
        assertThat(intent.getAction()).isEqualTo(intent.getAction());
        assertThat(intent.getComponent()).isNotNull();
    }

    @Test
    public void resolve_useAvailableTitle() {
        LabeledIntent labeledIntent = new LabeledIntent(
                TITLE_WITHOUT_ENTITY,
                null,
                DESCRIPTION,
                INTENT,
                REQUEST_CODE
        );

        LabeledIntent.Result result =
                labeledIntent.resolve(mContext, /*titleChooser*/ null);

        assertThat(result).isNotNull();
        assertThat(result.remoteAction.getTitle()).isEqualTo(TITLE_WITHOUT_ENTITY);
        assertThat(result.remoteAction.getContentDescription()).isEqualTo(DESCRIPTION);
        Intent intent = result.resolvedIntent;
        assertThat(intent.getAction()).isEqualTo(intent.getAction());
        assertThat(intent.getComponent()).isNotNull();
    }

    @Test
    public void resolve_titleChooser() {
        LabeledIntent labeledIntent = new LabeledIntent(
                TITLE_WITHOUT_ENTITY,
                null,
                DESCRIPTION,
                INTENT,
                REQUEST_CODE
        );

        LabeledIntent.Result result =
                labeledIntent.resolve(mContext, (labeledIntent1, resolveInfo) -> "chooser");

        assertThat(result).isNotNull();
        assertThat(result.remoteAction.getTitle()).isEqualTo("chooser");
        assertThat(result.remoteAction.getContentDescription()).isEqualTo(DESCRIPTION);
        Intent intent = result.resolvedIntent;
        assertThat(intent.getAction()).isEqualTo(intent.getAction());
        assertThat(intent.getComponent()).isNotNull();
    }

    @Test
    public void resolve_titleChooserReturnsNull() {
        LabeledIntent labeledIntent = new LabeledIntent(
                TITLE_WITHOUT_ENTITY,
                null,
                DESCRIPTION,
                INTENT,
                REQUEST_CODE
        );

        LabeledIntent.Result result =
                labeledIntent.resolve(mContext, (labeledIntent1, resolveInfo) -> null);

        assertThat(result).isNotNull();
        assertThat(result.remoteAction.getTitle()).isEqualTo(TITLE_WITHOUT_ENTITY);
        assertThat(result.remoteAction.getContentDescription()).isEqualTo(DESCRIPTION);
        Intent intent = result.resolvedIntent;
        assertThat(intent.getAction()).isEqualTo(intent.getAction());
        assertThat(intent.getComponent()).isNotNull();
    }


    @Test
    public void resolve_missingTitle() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new LabeledIntent(
                                null,
                                null,
                                DESCRIPTION,
                                INTENT,
                                REQUEST_CODE
                        ));
    }
}
