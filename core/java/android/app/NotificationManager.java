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
import android.os.Binder;
import android.os.RemoteException;
import android.os.Handler;
import android.os.IBinder;
import android.os.ServiceManager;
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
 * Each of the notify methods takes an int id parameter.  This id identifies
 * this notification from your app to the system, so that id should be unique
 * within your app.  If you call one of the notify methods with an id that is
 * currently active and a new set of notification parameters, it will be
 * updated.  For example, if you pass a new status bar icon, the old icon in
 * the status bar will be replaced with the new one.  This is also the same
 * id you pass to the {@link #cancel} method to clear this notification.
 *
 * <p>
 * You do not instantiate this class directly; instead, retrieve it through
 * {@link android.content.Context#getSystemService}.
 *
 * @see android.app.Notification
 * @see android.content.Context#getSystemService
 */
public class NotificationManager
{
    private static String TAG = "NotificationManager";
    private static boolean DEBUG = false;
    private static boolean localLOGV = DEBUG || android.util.Config.LOGV;

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

    /**
     * Persistent notification on the status bar, 
     *
     * @param id An identifier for this notification unique within your
     *        application.
     * @param notification A {@link Notification} object describing how to
     *        notify the user, other than the view you're providing. Must not be null.
     */
    public void notify(int id, Notification notification)
    {
        notify(null, id, notification);
    }

    /**
     * Persistent notification on the status bar,
     *
     * @param tag An string identifier for this notification unique within your
     *        application.
     * @param notification A {@link Notification} object describing how to
     *        notify the user, other than the view you're providing. Must not be null.
     * @return the id of the notification that is associated with the string identifier that
     * can be used to cancel the notification
     */
    public void notify(String tag, int id, Notification notification)
    {
        int[] idOut = new int[1];
        INotificationManager service = getService();
        String pkg = mContext.getPackageName();
        if (localLOGV) Log.v(TAG, pkg + ": notify(" + id + ", " + notification + ")");
        try {
            service.enqueueNotificationWithTag(pkg, tag, id, notification, idOut);
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
            service.cancelNotificationWithTag(pkg, tag, id);
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
            service.cancelAllNotifications(pkg);
        } catch (RemoteException e) {
        }
    }

    private Context mContext;
}
