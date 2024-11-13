/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.server.autofill;


import static com.google.common.truth.Truth.assertThat;

import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class PresentationEventLoggerTest {

    @Test
    public void testViewEntered() {
        PresentationStatsEventLogger pEventLogger =
                PresentationStatsEventLogger.createPresentationLog(1, 1, 1);

        AutofillId id = new AutofillId(13);
        AutofillValue initialValue = AutofillValue.forText("hello");
        AutofillValue lastValue = AutofillValue.forText("hello world");
        ViewState vState = new ViewState(id, null, 0, false);

        pEventLogger.startNewEvent();
        pEventLogger.maybeSetFocusedId(id);
        pEventLogger.onFieldTextUpdated(vState, initialValue);
        pEventLogger.onFieldTextUpdated(vState, lastValue);

        PresentationStatsEventLogger.PresentationStatsEventInternal event =
                pEventLogger.getInternalEvent().get();
        assertThat(event).isNotNull();
        assertThat(event.mFieldFirstLength).isEqualTo(initialValue.getTextValue().length());
        assertThat(event.mFieldLastLength).isEqualTo(lastValue.getTextValue().length());
        assertThat(event.mFieldModifiedFirstTimestampMs).isNotEqualTo(-1);
        assertThat(event.mFieldModifiedLastTimestampMs).isNotEqualTo(-1);
    }

    @Test
    public void testViewAutofilled() {
        PresentationStatsEventLogger pEventLogger =
                PresentationStatsEventLogger.createPresentationLog(1, 1, 1);

        String newTextValue = "hello";
        AutofillValue value = AutofillValue.forText(newTextValue);
        AutofillId id = new AutofillId(13);
        ViewState vState = new ViewState(id, null, ViewState.STATE_AUTOFILLED, false);

        pEventLogger.startNewEvent();
        pEventLogger.maybeSetFocusedId(id);
        pEventLogger.onFieldTextUpdated(vState, value);

        PresentationStatsEventLogger.PresentationStatsEventInternal event =
                pEventLogger.getInternalEvent().get();
        assertThat(event).isNotNull();
        assertThat(event.mFieldFirstLength).isEqualTo(newTextValue.length());
        assertThat(event.mFieldLastLength).isEqualTo(newTextValue.length());
        assertThat(event.mAutofilledTimestampMs).isNotEqualTo(-1);
        assertThat(event.mFieldModifiedFirstTimestampMs).isEqualTo(-1);
        assertThat(event.mFieldModifiedLastTimestampMs).isEqualTo(-1);
    }

    @Test
    public void testModifiedOnDifferentView() {
        PresentationStatsEventLogger pEventLogger =
                PresentationStatsEventLogger.createPresentationLog(1, 1, 1);

        String newTextValue = "hello";
        AutofillValue value = AutofillValue.forText(newTextValue);
        AutofillId id = new AutofillId(13);
        ViewState vState = new ViewState(id, null, ViewState.STATE_AUTOFILLED, false);

        pEventLogger.startNewEvent();
        pEventLogger.onFieldTextUpdated(vState, value);

        PresentationStatsEventLogger.PresentationStatsEventInternal event =
                pEventLogger.getInternalEvent().get();
        assertThat(event).isNotNull();
        assertThat(event.mFieldFirstLength).isEqualTo(-1);
        assertThat(event.mFieldLastLength).isEqualTo(-1);
        assertThat(event.mFieldModifiedFirstTimestampMs).isEqualTo(-1);
        assertThat(event.mFieldModifiedLastTimestampMs).isEqualTo(-1);
        assertThat(event.mAutofilledTimestampMs).isEqualTo(-1);
    }

    @Test
    public void testSetCountShown() {
        PresentationStatsEventLogger pEventLogger =
                PresentationStatsEventLogger.createPresentationLog(1, 1, 1);

        pEventLogger.startNewEvent();
        pEventLogger.logWhenDatasetShown(7);

        PresentationStatsEventLogger.PresentationStatsEventInternal event =
                pEventLogger.getInternalEvent().get();
        assertThat(event).isNotNull();
        assertThat(event.mCountShown).isEqualTo(7);
        assertThat(event.mNoPresentationReason)
                .isEqualTo(PresentationStatsEventLogger.NOT_SHOWN_REASON_ANY_SHOWN);
    }

    @Test
    public void testFillDialogShownThenInline() {
        PresentationStatsEventLogger pEventLogger =
                PresentationStatsEventLogger.createPresentationLog(1, 1, 1);

        pEventLogger.startNewEvent();
        pEventLogger.maybeSetDisplayPresentationType(3);
        pEventLogger.maybeSetDisplayPresentationType(2);

        PresentationStatsEventLogger.PresentationStatsEventInternal event =
                pEventLogger.getInternalEvent().get();
        assertThat(event).isNotNull();
        assertThat(event.mDisplayPresentationType).isEqualTo(3);
    }
}
