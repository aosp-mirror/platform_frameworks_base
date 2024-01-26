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

package android.credentials.ui;

import android.annotation.NonNull;
import android.content.Intent;
import android.os.Bundle;
import android.os.ResultReceiver;

/**
 * Utilities for sending the UI results back to the system service.
 *
 * @hide
 */
public final class ResultHelper {
    /**
     * Sends the {@code failureResult} that caused the UI to stop back to the CredentialManager
     * service.
     *
     * The {code resultReceiver} for a UI flow can be extracted from the UI launch intent via
     * {@link IntentHelper#extractResultReceiver(Intent)}.
     */
    public static void sendFailureResult(@NonNull ResultReceiver resultReceiver,
            @NonNull FailureResult failureResult) {
        FailureDialogResult result = failureResult.toFailureDialogResult();
        Bundle resultData = new Bundle();
        FailureDialogResult.addToBundle(result, resultData);
        resultReceiver.send(failureResult.errorCodeToResultCode(),
                resultData);
    }

    /**
     * Sends the completed {@code userSelectionResult} back to the CredentialManager service.
     *
     * The {code resultReceiver} for a UI flow can be extracted from the UI launch intent via
     * {@link IntentHelper#extractResultReceiver(Intent)}.
     */
    public static void sendUserSelectionResult(@NonNull ResultReceiver resultReceiver,
            @NonNull UserSelectionResult userSelectionResult) {
        UserSelectionDialogResult result = userSelectionResult.toUserSelectionDialogResult();
        Bundle resultData = new Bundle();
        UserSelectionDialogResult.addToBundle(result, resultData);
        resultReceiver.send(BaseDialogResult.RESULT_CODE_DIALOG_COMPLETE_WITH_SELECTION,
                resultData);
    }

    private ResultHelper() {}
}
