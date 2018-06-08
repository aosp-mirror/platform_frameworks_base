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

/**
 * Mediator between apps being auto-filled and auto-fill service implementations.
 *
 * {@hide}
 */
interface IAutoFillManager {
    // Returns flags: FLAG_ADD_CLIENT_ENABLED | FLAG_ADD_CLIENT_DEBUG | FLAG_ADD_CLIENT_VERBOSE
    int addClient(in IAutoFillManagerClient client, int userId);
    void removeClient(in IAutoFillManagerClient client, int userId);
    int startSession(IBinder activityToken, in IBinder appCallback, in AutofillId autoFillId,
            in Rect bounds, in AutofillValue value, int userId, boolean hasCallback, int flags,
            in ComponentName componentName, boolean compatMode);
    FillEventHistory getFillEventHistory();
    boolean restoreSession(int sessionId, in IBinder activityToken, in IBinder appCallback);
    void updateSession(int sessionId, in AutofillId id, in Rect bounds,
            in AutofillValue value, int action, int flags, int userId);
    int updateOrRestartSession(IBinder activityToken, in IBinder appCallback,
            in AutofillId autoFillId, in Rect bounds, in AutofillValue value, int userId,
            boolean hasCallback, int flags, in ComponentName componentName, int sessionId,
            int action, boolean compatMode);
    void setAutofillFailure(int sessionId, in List<AutofillId> ids, int userId);
    void finishSession(int sessionId, int userId);
    void cancelSession(int sessionId, int userId);
    void setAuthenticationResult(in Bundle data, int sessionId, int authenticationId, int userId);
    void setHasCallback(int sessionId, int userId, boolean hasIt);
    void disableOwnedAutofillServices(int userId);
    boolean isServiceSupported(int userId);
    boolean isServiceEnabled(int userId, String packageName);
    void onPendingSaveUi(int operation, IBinder token);
    UserData getUserData();
    String getUserDataId();
    void setUserData(in UserData userData);
    boolean isFieldClassificationEnabled();
    ComponentName getAutofillServiceComponentName();
    String[] getAvailableFieldClassificationAlgorithms();
    String getDefaultFieldClassificationAlgorithm();
}
