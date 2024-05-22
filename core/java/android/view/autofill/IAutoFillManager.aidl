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

import android.content.ComponentName;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteCallback;
import android.service.autofill.FillEventHistory;
import android.service.autofill.UserData;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.view.autofill.IAutoFillManagerClient;
import com.android.internal.os.IResultReceiver;

/**
 * Mediator between apps being auto-filled and auto-fill service implementations.
 *
 * {@hide}
 */
oneway interface IAutoFillManager {
    // Returns flags: FLAG_ADD_CLIENT_ENABLED | FLAG_ADD_CLIENT_DEBUG | FLAG_ADD_CLIENT_VERBOSE
    void addClient(in IAutoFillManagerClient client, in ComponentName componentName, int userId,
        in IResultReceiver result, boolean credmanRequested);
    void removeClient(in IAutoFillManagerClient client, int userId);
    void startSession(IBinder activityToken, in IBinder appCallback, in AutofillId autoFillId,
        in Rect bounds, in AutofillValue value, int userId, boolean hasCallback, int flags,
        in ComponentName componentName, boolean compatMode, in IResultReceiver result);
    void getFillEventHistory(in IResultReceiver result);
    void restoreSession(int sessionId, in IBinder activityToken, in IBinder appCallback,
        in IResultReceiver result);
    void updateSession(int sessionId, in AutofillId id, in Rect bounds,
        in AutofillValue value, int action, int flags, int userId);
    void setAutofillFailure(int sessionId, in List<AutofillId> ids, int userId);
    void setViewAutofilled(int sessionId, in AutofillId id, int userId);
    void finishSession(int sessionId, int userId, int commitReason);
    void cancelSession(int sessionId, int userId);
    void setAuthenticationResult(in Bundle data, int sessionId, int authenticationId, int userId);
    void setHasCallback(int sessionId, int userId, boolean hasIt);
    void disableOwnedAutofillServices(int userId);
    void isServiceSupported(int userId, in IResultReceiver result);
    void isServiceEnabled(int userId, String packageName, in IResultReceiver result);
    void onPendingSaveUi(int operation, IBinder token);
    void getUserData(in IResultReceiver result);
    void getUserDataId(in IResultReceiver result);
    void setUserData(in UserData userData);
    void isFieldClassificationEnabled(in IResultReceiver result);
    void getAutofillServiceComponentName(in IResultReceiver result);
    void getAvailableFieldClassificationAlgorithms(in IResultReceiver result);
    void getDefaultFieldClassificationAlgorithm(in IResultReceiver result);
    void setAugmentedAutofillWhitelist(in List<String> packages, in List<ComponentName> activities,
        in IResultReceiver result);
}
