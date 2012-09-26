/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.app;

import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.Log;

/**
 * Class to notify the user of events that happen.  This is how you tell
 * the user that something has happened in the background. {@more}
 *
 * Notifications can take different forms:
 * <ul>
 *      <li>A persistent icon that goes in the status bar and is accessible
 *          through the launcher, (when the user selects it, a designated Intent
 *          can be launched),</li>
 *      <li>Turning on or flashing LEDs on the device, or</li>
 *      <li>Alerting the user by flashing the backlight, playing a sound,
 *          or vibrating.</li>
 * </ul>
 *
 * <p>
 * Each of the notify methods takes an int id parameter and optionally a
 * {@link String} tag parameter, which may be {@code null}.  These parameters
 * are used to form a pair (tag, id), or ({@code null}, id) if tag is
 * unspecified.  This pair identifies this notification from your app to the
 * system, so that pair should be unique within your app.  If you call one
 * of the notify methods with a (tag, id) pair that is currently active and
 * a new set of notification parameters, it will be updated.  For example,
 * if you pass a new status bar icon, the old icon in the status bar will
 * be replaced with the new one.  This is also the same tag and id you pass
 * to the {@link #cancel(int)} or {@link #cancel(String, int)} method to clear
 * this notification.
 *
 * <p>
 * You do not instantiate this class directly; instead, retrieve it through
 * {@link android.content.Context#getSystemService}.
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For a guide to creating notifications, read the
 * <a href="{@docRoot}guide/topics/ui/notifiers/notifications.html">Status Bar Notifications</a>
 * developer guide.</p>
 * </div>
 *
 * @see android.app.Notification
 * @see android.content.Context#getSystemService
 */
public class NotificationManager
{
    private static String TAG = "NotificationManager";
    private static boolean localLOGV = false;

    private static INotificationManager sService;

    /** @hide */
    static public INotificationManager getService()
    {
        if (sService != null) {
            return sService;
        }
        IBinder b = ServiceManager.getService("notification");
        sService = INotificationManager.Stub.asInterface(b);
        return sService;
    }

    /*package*/ NotificationManager(Context context, Handler handler)
    {
        mContext = context;
    }

    /** {@hide} */
    public static NotificationManager from(Context context) {
        return (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    /**
     * Post a notification to be shown in the status bar. If a notification with
     * the same id has already been posted by your application and has not yet been canceled, it
     * will be replaced by the updated information.
     *
     * @param id An identifier for this notification unique within your
     *        application.
     * @param notification A {@link Notification} object describing what to show the user. Must not
     *        be null.
     */
    public void notify(int id, Notification notification)
    {
        notify(null, id, notification);
    }

    /**
     * Post a notification to be shown in the status bar. If a notification with
     * the same tag and id has already been posted by your application and has not yet been
     * canceled, it will be replaced by the updated information.
     *
     * @param tag A string identifier for this notification.  May be {@code null}.
     * @param id An identifier for this notification.  The pair (tag, id) must be unique
     *        within your application.
     * @param notification A {@link Notification} object describing what to
     *        show the user. Must not be null.
     */
    public void notify(String tag, int id, Notification notification)
    {
        int[] idOut = new int[1];
        INotificationManager service = getService();
        String pkg = mContext.getPackageName();
        if (notification.sound != null) {
            notification.sound = notification.sound.getCanonicalUri();
        }
        if (localLOGV) Log.v(TAG, pkg + ": notify(" + id + ", " + notification + ")");
        try {
            service.enqueueNotificationWithTag(pkg, tag, id, notification, idOut,
                    UserHandle.myUserId());
            if (id != idOut[0]) {
                Log.w(TAG, "notify: id corrupted: sent " + id + ", got back " + idOut[0]);
            }
        } catch (RemoteException e) {
        }
    }

    /**
     * @hide
     */
    public void notifyAsUser(String tag, int id, Notification notification, UserHandle user)
    {
        int[] idOut = new int[1];
        INotificationManager service = getService();
        String pkg = mContext.getPackageName();
        if (notification.sound != null) {
            notification.sound = notification.sound.getCanonicalUri();
        }
        if (localLOGV) Log.v(TAG, pkg + ": notify(" + id + ", " + notification + ")");
        try {
            service.enqueueNotificationWithTag(pkg, tag, id, notification, idOut,
                    user.getIdentifier());
            if (id != idOut[0]) {
                Log.w(TAG, "notify: id corrupted: sent " + id + ", got back " + idOut[0]);
            }
        } catch (RemoteException e) {
        }
    }

    /**
     * Cancel a previously shown notification.  If it's transient, the view
     * will be hidden.  If it's persistent, it will be removed from the status
     * bar.
     */
    public void cancel(int id)
    {
        cancel(null, id);
    }

    /**
     * Cancel a previously shown notification.  If it's transient, the view
     * will be hidden.  If it's persistent, it will be removed from the status
     * bar.
     */
    public void cancel(String tag, int id)
    {
        INotificationManager service = getService();
        String pkg = mContext.getPackageName();
        if (localLOGV) Log.v(TAG, pkg + ": cancel(" + id + ")");
        try {
            service.cancelNotificationWithTag(pkg, tag, id, UserHandle.myUserId());
        } catch (RemoteException e) {
        }
    }

    /**
     * @hide
     */
    public void cancelAsUser(String tag, int id, UserHandle user)
    {
        INotificationManager service = getService();
        String pkg = mContext.getPackageName();
        if (localLOGV) Log.v(TAG, pkg + ": cancel(" + id + ")");
        try {
            service.cancelNotificationWithTag(pkg, tag, id, user.getIdentifier());
        } catch (RemoteException e) {
        }
    }

    /**
     * Cancel all previously shown notifications. See {@link #cancel} for the
     * detailed behavior.
     */
    public void cancelAll()
    {
        INotificationManager service = getService();
        String pkg = mContext.getPackageName();
        if (localLOGV) Log.v(TAG, pkg + ": cancelAll()");
        try {
            service.cancelAllNotifications(pkg, UserHandle.myUserId());
        } catch (RemoteException e) {
        }
    }

    private Context mContext;
}
