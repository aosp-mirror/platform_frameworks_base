/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.service.notification;

import android.annotation.SdkConstant;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import com.android.internal.os.SomeArgs;

import java.util.List;

/**
 * A service that helps the user manage notifications.
 */
public abstract class NotificationAssistantService extends NotificationListenerService {
    private static final String TAG = "NotificationAssistants";

    /**
     * The {@link Intent} that must be declared as handled by the service.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE
            = "android.service.notification.NotificationAssistantService";

    private Handler mHandler;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        mHandler = new MyHandler(getContext().getMainLooper());
    }

    @Override
    public final IBinder onBind(Intent intent) {
        if (mWrapper == null) {
            mWrapper = new NotificationAssistantServiceWrapper();
        }
        return mWrapper;
    }

    /**
     * A notification was posted by an app. Called before alert.
     *
     * @param sbn the new notification
     * @param importance the initial importance of the notification.
     * @param user true if the initial importance reflects an explicit user preference.
     * @return an adjustment or null to take no action, within 100ms.
     */
    abstract public Adjustment onNotificationEnqueued(StatusBarNotification sbn,
          int importance, boolean user);

    /**
     * Updates a notification.  N.B. this won’t cause
     * an existing notification to alert, but might allow a future update to
     * this notification to alert.
     *
     * @param adjustment the adjustment with an explanation
     */
    public final void adjustNotification(Adjustment adjustment) {
        if (!isBound()) return;
        try {
            getNotificationInterface().applyAdjustmentFromAssistantService(mWrapper, adjustment);
        } catch (android.os.RemoteException ex) {
            Log.v(TAG, "Unable to contact notification manager", ex);
        }
    }

    /**
     * Updates existing notifications. Re-ranking won't occur until all adjustments are applied.
     * N.B. this won’t cause an existing notification to alert, but might allow a future update to
     * these notifications to alert.
     *
     * @param adjustments a list of adjustments with explanations
     */
    public final void adjustNotifications(List<Adjustment> adjustments) {
        if (!isBound()) return;
        try {
            getNotificationInterface().applyAdjustmentsFromAssistantService(mWrapper, adjustments);
        } catch (android.os.RemoteException ex) {
            Log.v(TAG, "Unable to contact notification manager", ex);
        }
    }

    private class NotificationAssistantServiceWrapper extends NotificationListenerWrapper {
        @Override
        public void onNotificationEnqueued(IStatusBarNotificationHolder sbnHolder,
                int importance, boolean user) {
            StatusBarNotification sbn;
            try {
                sbn = sbnHolder.get();
            } catch (RemoteException e) {
                Log.w(TAG, "onNotificationEnqueued: Error receiving StatusBarNotification", e);
                return;
            }

            SomeArgs args = SomeArgs.obtain();
            args.arg1 = sbn;
            args.argi1 = importance;
            args.argi2 = user ? 1 : 0;
            mHandler.obtainMessage(MyHandler.MSG_ON_NOTIFICATION_ENQUEUED,
                    args).sendToTarget();
        }
    }

    private final class MyHandler extends Handler {
        public static final int MSG_ON_NOTIFICATION_ENQUEUED = 1;

        public MyHandler(Looper looper) {
            super(looper, null, false);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_ON_NOTIFICATION_ENQUEUED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    StatusBarNotification sbn = (StatusBarNotification) args.arg1;
                    final int importance = args.argi1;
                    final boolean user = args.argi2 == 1;
                    args.recycle();
                    Adjustment adjustment = onNotificationEnqueued(sbn, importance, user);
                    if (adjustment != null) {
                        adjustNotification(adjustment);
                    }
                } break;
            }
        }
    }
}
