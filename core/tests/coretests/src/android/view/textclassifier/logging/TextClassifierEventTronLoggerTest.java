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

package android.view.textclassifier.logging;

import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.ACTION_TEXT_SELECTION_SMART_SHARE;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.CONVERSATION_ACTIONS;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_TEXT_CLASSIFIER_EVENT_TIME;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_TEXT_CLASSIFIER_FIRST_ENTITY_TYPE;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_TEXT_CLASSIFIER_SCORE;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_TEXT_CLASSIFIER_WIDGET_TYPE;

import static com.google.common.truth.Truth.assertThat;

import android.metrics.LogMaker;
import android.view.textclassifier.ConversationAction;
import android.view.textclassifier.TextClassificationContext;
import android.view.textclassifier.TextClassifierEvent;
import android.view.textclassifier.TextClassifierEventTronLogger;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.logging.MetricsLogger;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class TextClassifierEventTronLoggerTest {
    private static final String WIDGET_TYPE = "notification";
    private static final String PACKAGE_NAME = "pkg";

    @Mock
    private MetricsLogger mMetricsLogger;
    private TextClassifierEventTronLogger mTextClassifierEventTronLogger;


    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mTextClassifierEventTronLogger = new TextClassifierEventTronLogger(mMetricsLogger);
    }

    @Test
    public void testWriteEvent() {
        TextClassificationContext textClassificationContext =
                new TextClassificationContext.Builder(PACKAGE_NAME, WIDGET_TYPE)
                        .build();
        TextClassifierEvent.ConversationActionsEvent textClassifierEvent =
                new TextClassifierEvent.ConversationActionsEvent.Builder(
                        TextClassifierEvent.TYPE_SMART_ACTION)
                        .setEntityTypes(ConversationAction.TYPE_CALL_PHONE)
                        .setScores(0.5f)
                        .setEventContext(textClassificationContext)
                        .build();

        mTextClassifierEventTronLogger.writeEvent(textClassifierEvent);

        ArgumentCaptor<LogMaker> captor = ArgumentCaptor.forClass(LogMaker.class);
        Mockito.verify(mMetricsLogger).write(captor.capture());
        LogMaker logMaker = captor.getValue();
        assertThat(logMaker.getCategory()).isEqualTo(CONVERSATION_ACTIONS);
        assertThat(logMaker.getSubtype()).isEqualTo(ACTION_TEXT_SELECTION_SMART_SHARE);
        assertThat(logMaker.getTaggedData(FIELD_TEXT_CLASSIFIER_FIRST_ENTITY_TYPE))
                .isEqualTo(ConversationAction.TYPE_CALL_PHONE);
        assertThat((float) logMaker.getTaggedData(FIELD_TEXT_CLASSIFIER_SCORE))
                .isWithin(0.00001f).of(0.5f);
        // Never write event time.
        assertThat(logMaker.getTaggedData(FIELD_TEXT_CLASSIFIER_EVENT_TIME)).isNull();
        assertThat(logMaker.getPackageName()).isEqualTo(PACKAGE_NAME);
        assertThat(logMaker.getTaggedData(FIELD_TEXT_CLASSIFIER_WIDGET_TYPE))
                .isEqualTo(WIDGET_TYPE);

    }

    @Test
    public void testWriteEvent_unsupportedCategory() {
        TextClassifierEvent.TextSelectionEvent textClassifierEvent =
                new TextClassifierEvent.TextSelectionEvent.Builder(
                        TextClassifierEvent.TYPE_SMART_ACTION)
                        .build();

        mTextClassifierEventTronLogger.writeEvent(textClassifierEvent);

        Mockito.verify(mMetricsLogger, Mockito.never()).write(Mockito.any(LogMaker.class));
    }
}
