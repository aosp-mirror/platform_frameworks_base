/*
 * Copyright (C) 2014 The Android Open Source Project
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

import com.android.internal.telephony.ISub;
import com.android.internal.telephony.ISubscriptionListener;

import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telephony.Rlog;

import java.util.List;

/**
 * A listener class for monitoring changes to Subscription state
 * changes on the device.
 * <p>
 * Override the onXxxx methods in this class and passing to the listen method
 * bitwise-or of the corresponding LISTEN_Xxxx bit flags below.
 * <p>
 * Note that access to some of the information is permission-protected. Your
 * application won't receive updates for protected information unless it has
 * the appropriate permissions declared in its manifest file. Where permissions
 * apply, they are noted in the appropriate LISTEN_ flags.
 */
public class SubscriptionListener {
    private static final String LOG_TAG = "SubscriptionListener";
    private static final boolean DBG = false; // STOPSHIP if true

    /**
     * Permission for LISTEN_SUBSCRIPTION_INFO_LIST_CHANGED
     *
     * @hide
     */
    public static final String PERMISSION_LISTEN_SUBSCRIPTION_INFO_LIST_CHANGED =
            android.Manifest.permission.READ_PHONE_STATE;

    /**
     *  Listen for changes to the SubscriptionInoList when listening for this event
     *  it is guaranteed that on #onSubscriptionInfoChanged will be invoked. This initial
     *  invocation should be used to call SubscriptionManager.getActiveSubscriptionInfoList()
     *  to get the initial list.
     *
     *  Permissions: android.Manifest.permission.READ_PHONE_STATE
     *  @see #onSubscriptionInfoChanged
     */
    public static final int LISTEN_SUBSCRIPTION_INFO_LIST_CHANGED = 0x00000001;

    private final Handler mHandler;

    /**
     * Create a SubscriptionLitener for the device.
     *
     * This class requires Looper.myLooper() not return null. To supply your
     * own non-null looper use PhoneStateListener(Looper looper) below.
     */
    public SubscriptionListener() {
        this(Looper.myLooper());
    }

    /**
     * Create a PhoneStateListener for the Phone using the specified subscription
     * and non-null Looper.
     */
    public SubscriptionListener(Looper looper) {
        if (DBG) log("ctor:  looper=" + looper);

        ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
        mHandler = new Handler(looper) {
            @Override
            public void handleMessage(Message msg) {
                if (DBG) {
                    log("what=0x" + Integer.toHexString(msg.what) + " msg=" + msg);
                }
                switch (msg.what) {
                    case LISTEN_SUBSCRIPTION_INFO_LIST_CHANGED:
                        SubscriptionListener.this.onSubscriptionInfoChanged();
                        break;
                }
            }
        };
    }

    /**
     * Callback invoked when there is any change to any SubscriptionInfo.
     */
    public void onSubscriptionInfoChanged() {
        // default implementation empty
    }

    /**
     * The callback methods need to be called on the handler thread where
     * this object was created.  If the binder did that for us it'd be nice.
     */
    ISubscriptionListener callback = new ISubscriptionListener.Stub() {
        @Override
        public void onSubscriptionInfoChanged() {
            Message msg = Message.obtain(mHandler, LISTEN_SUBSCRIPTION_INFO_LIST_CHANGED);
            msg.sendToTarget();
        }
    };

    private void log(String s) {
        Rlog.d(LOG_TAG, s);
    }
}
