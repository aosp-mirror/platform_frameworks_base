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

package android.view.autofill;

import java.util.List;

import android.graphics.Rect;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.view.autofill.IAutofillWindowPresenter;

/**
 * Object running in the application process and responsible to provide the functionalities
 * required by an Augmented Autofill service.
 *
 * @hide
 */
interface IAugmentedAutofillManagerClient {
    /**
      * Gets the coordinates of the input field view.
      */
    Rect getViewCoordinates(in AutofillId id);

    /**
     * Autofills the activity with the contents of the values.
     */
    void autofill(int sessionId, in List<AutofillId> ids, in List<AutofillValue> values,
            boolean hideHighlight);

    /**
      * Requests showing the fill UI.
      */
    void requestShowFillUi(int sessionId, in AutofillId id, int width, int height,
            in Rect anchorBounds, in IAutofillWindowPresenter presenter);

    /**
      * Requests hiding the fill UI.
      */
    void requestHideFillUi(int sessionId, in AutofillId id);

    /**
      * Requests to start a new autofill flow. Returns true if the autofill request is made to
      * {@link AutofillManager#requestAutofill(View)}.
      */
    boolean requestAutofill(int sessionId, in AutofillId id);
}
