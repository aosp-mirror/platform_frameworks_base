/*
 * Copyright 2018 The Android Open Source Project
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
package android.hardware.location;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;

/**
 * A BroadcastReceiver that can be used with the Context Hub Service notifications.
 *
 * @hide
 */
public class ContextHubBroadcastReceiver extends BroadcastReceiver {
    // The context at which this receiver operates in
    private Context mContext;

    // The handler to post callbacks to when receiving Context Hub Service intents
    private Handler mHandler;

    // The callback to be invoked when receiving Context Hub Service intents
    private ContextHubClientCallback mCallback;

    // The string to use as the broadcast action for this receiver
    private String mAction;

    // True when this receiver is registered to receive Intents, false otherwise
    private boolean mRegistered = false;

    public ContextHubBroadcastReceiver(Context context, Handler handler,
                                       ContextHubClientCallback callback, String tag) {
        mContext = context;
        mHandler = handler;
        mCallback = callback;
        mAction = tag;
    }

    /**
     * Registers this receiver to receive Intents from the Context Hub Service. This method must
     * only be invoked when the receiver is not registered.
     *
     * @throws IllegalStateException if the receiver is already registered
     */
    public void register() throws IllegalStateException {
        if (mRegistered) {
            throw new IllegalStateException(
                "Cannot register ContextHubBroadcastReceiver multiple times");
        }
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(mAction);
        mContext.registerReceiver(this, intentFilter, null /* broadcastPermission */, mHandler);
        mRegistered = true;
    }

    /**
     * Unregisters this receiver. This method must only be invoked if {@link #register()} is
     * previously invoked.
     *
     * @throws IllegalStateException if the receiver is not yet registered
     */
    public void unregister() throws IllegalStateException {
        if (!mRegistered) {
            throw new IllegalStateException(
                "Cannot unregister ContextHubBroadcastReceiver when not registered");
        }
        mContext.unregisterReceiver(this);
        mRegistered = false;
    }

    /**
     * Creates a new PendingIntent associated with this receiver.
     *
     * @param flags the flags {@link PendingIntent.Flags} to use for the PendingIntent
     *
     * @return a PendingIntent to receive notifications for this receiver
     */
    public PendingIntent getPendingIntent(@PendingIntent.Flags int flags) {
        return PendingIntent.getBroadcast(
            mContext, 0 /* requestCode */, new Intent(mAction), flags);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        // TODO: Implement this
    }
}
