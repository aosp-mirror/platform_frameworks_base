/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentSender;
import android.credentials.GetCredentialResponse;
import android.graphics.Rect;
import android.os.IBinder;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.view.autofill.IAutofillWindowPresenter;
import android.view.KeyEvent;

import com.android.internal.os.IResultReceiver;

/**
 * Object running in the application process and responsible for autofilling it.
 *
 * @hide
 */
oneway interface IAutoFillManagerClient {
    /**
     * Notifies the client when the autofill enabled state changed.
     */
    void setState(int flags);

    /**
      * Autofills the activity with the contents of a dataset.
      */
    void autofill(int sessionId, in List<AutofillId> ids, in List<AutofillValue> values,
            boolean hideHighlight);

    void onGetCredentialResponse(int sessionId, in AutofillId id,
                 in GetCredentialResponse response);

    void onGetCredentialException(int sessionId, in AutofillId id,
                     in String errorType, in String errorMsg);

    /**
     * Autofills the activity with rich content data (e.g. an image) from a dataset.
     */
    void autofillContent(int sessionId, in AutofillId id, in ClipData content);

    /**
      * Authenticates a fill response or a data set.
      */
    void authenticate(int sessionId, int authenticationId, in IntentSender intent,
            in Intent fillInIntent, boolean authenticateInline);

    /**
      * Sets the views to track. If saveOnAllViewsInvisible is set and all these view are invisible
      * the session is finished automatically.
      */
    void setTrackedViews(int sessionId, in @nullable AutofillId[] savableIds,
            boolean saveOnAllViewsInvisible, boolean saveOnFinish,
            in @nullable AutofillId[] fillableIds, in AutofillId saveTriggerId);

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
     * Requests hiding the fill UI when it's destroyed
     */
    void requestHideFillUiWhenDestroyed(int sessionId, in AutofillId id);

    /**
     * Notifies no fill UI will be shown, and also mark the state as finished if necessary (if
     * sessionFinishedState != 0).
     */
    void notifyNoFillUi(int sessionId, in AutofillId id, int sessionFinishedState);

    /**
     * Notifies that the fill UI was shown by the system (e.g. as inline chips in the keyboard).
     */
    void notifyFillUiShown(int sessionId, in AutofillId id);

    /**
     * Notifies that the fill UI previously shown by the system has been hidden by the system.
     *
     * @see #notifyFillUiShown
     */
    void notifyFillUiHidden(int sessionId, in AutofillId id);

    /**
     * Dispatches unhandled keyevent from autofill ui. Autofill ui handles DPAD and ENTER events,
     * other unhandled keyevents are dispatched to app's window to filter autofill result.
     * Note this method is not called when autofill ui is in fullscreen mode (TV only).
     */
    void dispatchUnhandledKey(int sessionId, in AutofillId id, in KeyEvent keyEvent);

    /**
     * Starts the provided intent sender.
     */
    void startIntentSender(in IntentSender intentSender, in Intent intent);

   /**
     * Sets the state of the Autofill Save UI for a given session.
     */
   void setSaveUiState(int sessionId, boolean shown);

   /**
     * Marks the state of the session as finished.
     *
     * @param newState STATE_FINISHED (because the autofill service returned a null
     * FillResponse) or STATE_UNKNOWN (because the session was removed).
     * @param autofillableIds list of ids that could trigger autofill, use to not handle a new
     * session when they're entered.
     */
   void setSessionFinished(int newState, in List<AutofillId> autofillableIds);

   /**
    * Gets a reference to the binder object that can be used by the Augmented Autofill service.
    *
    * @param receiver, whose AutofillManager.EXTRA_AUGMENTED_AUTOFILL_CLIENT extra will contain
    * the reference.
    */
   void getAugmentedAutofillClient(in IResultReceiver result);

   /**
    * Notifies disables autofill for the app or activity.
    */
   void notifyDisableAutofill(long disableDuration, in ComponentName componentName);

   /**
    * Requests to show the soft input method if the focus is on the given id.
    */
   void requestShowSoftInput(in AutofillId id);

    /**
     * Notifies autofill ids that require to show the fill dialog.
     */
    void notifyFillDialogTriggerIds(in List<AutofillId> ids);
}
