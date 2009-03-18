/*
** Copyright 2006, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package com.android.providers.subscribedfeeds;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Handles the XMPP_CONNECTED_ACTION intent by updating all the
 * subscribed feeds with the new jabber id and initiating a sync
 * for all subscriptions.
 *
 * Handles the TICKLE_ACTION intent by finding the matching
 * subscribed feed and intiating a sync for it.
 */
public class SubscribedFeedsBroadcastReceiver extends BroadcastReceiver {

    private static final String TAG = "Sync";

    public void onReceive(Context context, Intent intent) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "Received intent " + intent);
            intent.setClass(context, SubscribedFeedsIntentService.class);
        context.startService(intent);
    }
}
