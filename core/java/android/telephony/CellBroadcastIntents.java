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

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SystemApi;
import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Telephony;

/**
 * A static helper class used to send Intents with prepopulated flags.
 * <p>
 * This is intended to be used by the CellBroadcastService and does nothing if the caller does not
 * have permission to broadcast {@link Telephony.Sms.Intents.SMS_CB_RECEIVED_ACTION}.
 *
 * @hide
 */
@SystemApi
public class CellBroadcastIntents {
    private static final String LOG_TAG = "CellBroadcastIntents";

    private static final String EXTRA_MESSAGE = "message";

    /**
     * Broadcast intent action for notifying area information has been updated. The information
     * can be retrieved by {@link CellBroadcastService#getCellBroadcastAreaInfo(int)}
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_AREA_INFO_UPDATED =
            "android.telephony.action.AREA_INFO_UPDATED";

    /**
     * @hide
     */
    private CellBroadcastIntents() {
    }

    /**
     * Broadcasts an SMS_CB_RECEIVED_ACTION intent which can be received by background
     * BroadcastReceivers. This is only intended to be used by the CellBroadcastService and will
     * do nothing if the caller does not have permission to broadcast
     * {@link Telephony.Sms.Intents.SMS_CB_RECEIVED_ACTION}.
     *
     * @param context            The context from which to send the broadcast
     * @param user               The user from which to send the broadcast
     * @param smsCbMessage       The SmsCbMessage to include with the intent
     * @param resultReceiver     Your own BroadcastReceiver to treat as the final receiver of the
     *                           broadcast.
     * @param scheduler          A custom Handler with which to schedule the resultReceiver
     *                           callback; if null it will be scheduled in the Context's main
     *                           thread.
     * @param initialCode        An initial value for the result code.  Often Activity.RESULT_OK.
     * @param slotIndex          The slot index to include in the intent
     */
    public static void sendSmsCbReceivedBroadcast(@NonNull Context context,
            @Nullable UserHandle user, @NonNull SmsCbMessage smsCbMessage,
            @Nullable BroadcastReceiver resultReceiver, @Nullable Handler scheduler,
            int initialCode, int slotIndex) {
        Intent backgroundIntent = new Intent(Telephony.Sms.Intents.SMS_CB_RECEIVED_ACTION);
        backgroundIntent.putExtra(EXTRA_MESSAGE, smsCbMessage);
        putPhoneIdAndSubIdExtra(context, backgroundIntent, slotIndex);

        String receiverPermission = Manifest.permission.RECEIVE_SMS;
        String receiverAppOp = AppOpsManager.OPSTR_RECEIVE_SMS;
        if (user != null) {
            context.createContextAsUser(user, 0).sendOrderedBroadcast(backgroundIntent,
                    receiverPermission, receiverAppOp, resultReceiver, scheduler, initialCode,
                    null, null);
        } else {
            context.sendOrderedBroadcast(backgroundIntent, receiverPermission,
                    receiverAppOp, resultReceiver, scheduler, initialCode, null, null);
        }
    }

    /**
     * Put the phone ID and sub ID into an intent as extras.
     */
    private static void putPhoneIdAndSubIdExtra(Context context, Intent intent, int phoneId) {
        int subId = getSubIdForPhone(context, phoneId);
        if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            intent.putExtra("subscription", subId);
            intent.putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, subId);
        }
        intent.putExtra("phone", phoneId);
        intent.putExtra(SubscriptionManager.EXTRA_SLOT_INDEX, phoneId);
    }

    /**
     * Get the subscription ID for a phone ID, or INVALID_SUBSCRIPTION_ID if the phone does not
     * have an active sub
     * @param phoneId the phoneId to use
     * @return the associated sub id
     */
    private static int getSubIdForPhone(Context context, int phoneId) {
        SubscriptionManager subMan =
                (SubscriptionManager) context.getSystemService(
                        Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        int[] subIds = subMan.getSubscriptionIds(phoneId);
        if (subIds != null) {
            return subIds[0];
        } else {
            return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        }
    }
}
