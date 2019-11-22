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

package android.telephony;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import android.util.Log;

/**
 * A static helper class used to send Intents with prepopulated flags.
 * <p>
 * This is intended to be used by the CellBroadcastService and will throw a security exception if
 * used from a UID besides the network stack UID.
 *
 * @hide
 */
@SystemApi
public class CellBroadcastIntents {
    private static final String LOG_TAG = "CellBroadcastIntents";

    /**
     * @hide
     */
    private CellBroadcastIntents() {
    }

    /**
     * Returns an intent which can be received by background BroadcastReceivers. This is only
     * intended to be used by the CellBroadcastService and will throw a security exception if called
     * from another UID.
     *
     * @param context            The context from which to send the broadcast
     * @param user               The user from which to send the broadcast
     * @param intent             The Intent to broadcast; all receivers matching this Intent will
     *                           receive the broadcast.
     * @param receiverPermission String naming a permissions that a receiver must hold in order to
     *                           receive your broadcast. If null, no permission is required.
     * @param receiverAppOp      The app op associated with the broadcast. If null, no appOp is
     *                           required. If both receiverAppOp and receiverPermission are
     *                           non-null, a receiver must have both of them to receive the
     *                           broadcast
     * @param resultReceiver     Your own BroadcastReceiver to treat as the final receiver of the
     *                           broadcast.
     * @param scheduler          A custom Handler with which to schedule the resultReceiver
     *                           callback; if null it will be scheduled in the Context's main
     *                           thread.
     * @param initialCode        An initial value for the result code.  Often Activity.RESULT_OK.
     * @param initialData        An initial value for the result data.  Often null.
     * @param initialExtras      An initial value for the result extras.  Often null.
     */
    public static void sendOrderedBroadcastForBackgroundReceivers(@NonNull Context context,
            @Nullable UserHandle user, @NonNull Intent intent, @Nullable String receiverPermission,
            @Nullable String receiverAppOp, @Nullable BroadcastReceiver resultReceiver,
            @Nullable Handler scheduler, int initialCode, @Nullable String initialData,
            @Nullable Bundle initialExtras) {
        Log.d(LOG_TAG, "sendOrderedBroadcastForBackgroundReceivers intent=" + intent.getAction());
        int status = context.checkCallingOrSelfPermission(
                "android.permission.GRANT_RUNTIME_PERMISSIONS_TO_TELEPHONY_DEFAULTS");
        if (status == PackageManager.PERMISSION_DENIED) {
            throw new SecurityException(
                    "Caller does not have permission to send broadcast for background receivers");
        }
        intent.setFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        if (user != null) {
            context.createContextAsUser(user, 0).sendOrderedBroadcast(intent, receiverPermission,
                    receiverAppOp, resultReceiver, scheduler, initialCode, initialData,
                    initialExtras);
        } else {
            context.sendOrderedBroadcast(intent, receiverPermission,
                    receiverAppOp, resultReceiver, scheduler, initialCode, initialData,
                    initialExtras);
        }
    }
}
