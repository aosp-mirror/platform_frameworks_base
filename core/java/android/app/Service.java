/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.content.ComponentCallbacks;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ContextWrapper;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.RemoteException;
import android.os.IBinder;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * A Service is an application component that runs in the background, not
 * interacting with the user, for an indefinite period of time.  Each service
 * class must have a corresponding
 * {@link android.R.styleable#AndroidManifestService &lt;service&gt;}
 * declaration in its package's <code>AndroidManifest.xml</code>.  Services
 * can be started with
 * {@link android.content.Context#startService Context.startService()} and
 * {@link android.content.Context#bindService Context.bindService()}.
 * 
 * <p>Note that services, like other application objects, run in the main
 * thread of their hosting process.  This means that, if your service is going
 * to do any CPU intensive (such as MP3 playback) or blocking (such as
 * networking) operations, it should spawn its own thread in which to do that
 * work.  More information on this can be found in
 * <a href="{@docRoot}guide/topics/fundamentals.html#procthread">Application Fundamentals:
 * Processes and Threads</a>.</p>
 * 
 * <p>The Service class is an important part of an
 * <a href="{@docRoot}guide/topics/fundamentals.html#lcycles">application's overall lifecycle</a>.</p>
 * 
 * <p>Topics covered here:
 * <ol>
 * <li><a href="#ServiceLifecycle">Service Lifecycle</a>
 * <li><a href="#Permissions">Permissions</a>
 * <li><a href="#ProcessLifecycle">Process Lifecycle</a>
 * </ol>
 * 
 * <a name="ServiceLifecycle"></a>
 * <h3>Service Lifecycle</h3>
 * 
 * <p>There are two reasons that a service can be run by the system.  If someone
 * calls {@link android.content.Context#startService Context.startService()} then the system will
 * retrieve the service (creating it and calling its {@link #onCreate} method
 * if needed) and then call its {@link #onStartCommand} method with the
 * arguments supplied by the client.  The service will at this point continue
 * running until {@link android.content.Context#stopService Context.stopService()} or
 * {@link #stopSelf()} is called.  Note that multiple calls to
 * Context.startService() do not nest (though they do result in multiple corresponding
 * calls to onStartCommand()), so no matter how many times it is started a service
 * will be stopped once Context.stopService() or stopSelf() is called; however,
 * services can use their {@link #stopSelf(int)} method to ensure the service is
 * not stopped until started intents have been processed.
 * 
 * <p>For started services, there are two additional major modes of operation
 * they can decide to run in, depending on the value they return from
 * onStartCommand(): {@link #START_STICKY} is used for services that are
 * explicitly started and stopped as needed, while {@link #START_NOT_STICKY}
 * or {@link #START_REDELIVER_INTENT} are used for services that should only
 * remain running while processing any commands sent to them.  See the linked
 * documentation for more detail on the semantics.
 * 
 * <p>Clients can also use {@link android.content.Context#bindService Context.bindService()} to
 * obtain a persistent connection to a service.  This likewise creates the
 * service if it is not already running (calling {@link #onCreate} while
 * doing so), but does not call onStartCommand().  The client will receive the
 * {@link android.os.IBinder} object that the service returns from its
 * {@link #onBind} method, allowing the client to then make calls back
 * to the service.  The service will remain running as long as the connection
 * is established (whether or not the client retains a reference on the
 * service's IBinder).  Usually the IBinder returned is for a complex
 * interface that has been <a href="{@docRoot}guide/developing/tools/aidl.html">written
 * in aidl</a>.
 * 
 * <p>A service can be both started and have connections bound to it.  In such
 * a case, the system will keep the service running as long as either it is
 * started <em>or</em> there are one or more connections to it with the
 * {@link android.content.Context#BIND_AUTO_CREATE Context.BIND_AUTO_CREATE}
 * flag.  Once neither
 * of these situations hold, the service's {@link #onDestroy} method is called
 * and the service is effectively terminated.  All cleanup (stopping threads,
 * unregistering receivers) should be complete upon returning from onDestroy().
 * 
 * <a name="Permissions"></a>
 * <h3>Permissions</h3>
 * 
 * <p>Global access to a service can be enforced when it is declared in its
 * manifest's {@link android.R.styleable#AndroidManifestService &lt;service&gt;}
 * tag.  By doing so, other applications will need to declare a corresponding
 * {@link android.R.styleable#AndroidManifestUsesPermission &lt;uses-permission&gt;}
 * element in their own manifest to be able to start, stop, or bind to
 * the service.
 * 
 * <p>In addition, a service can protect individual IPC calls into it with
 * permissions, by calling the
 * {@link #checkCallingPermission}
 * method before executing the implementation of that call.
 * 
 * <p>See the <a href="{@docRoot}guide/topics/security/security.html">Security and Permissions</a>
 * document for more information on permissions and security in general.
 * 
 * <a name="ProcessLifecycle"></a>
 * <h3>Process Lifecycle</h3>
 * 
 * <p>The Android system will attempt to keep the process hosting a service
 * around as long as the service has been started or has clients bound to it.
 * When running low on memory and needing to kill existing processes, the
 * priority of a process hosting the service will be the higher of the
 * following possibilities:
 * 
 * <ul>
 * <li><p>If the service is currently executing code in its
 * {@link #onCreate onCreate()}, {@link #onStartCommand onStartCommand()},
 * or {@link #onDestroy onDestroy()} methods, then the hosting process will
 * be a foreground process to ensure this code can execute without
 * being killed.
 * <li><p>If the service has been started, then its hosting process is considered
 * to be less important than any processes that are currently visible to the
 * user on-screen, but more important than any process not visible.  Because
 * only a few processes are generally visible to the user, this means that
 * the service should not be killed except in extreme low memory conditions.
 * <li><p>If there are clients bound to the service, then the service's hosting
 * process is never less important than the most important client.  That is,
 * if one of its clients is visible to the user, then the service itself is
 * considered to be visible.
 * <li><p>A started service can use the {@link #startForeground(int, Notification)}
 * API to put the service in a foreground state, where the system considers
 * it to be something the user is actively aware of and thus not a candidate
 * for killing when low on memory.  (It is still theoretically possible for
 * the service to be killed under extreme memory pressure from the current
 * foreground application, but in practice this should not be a concern.)
 * </ul>
 * 
 * <p>Note this means that most of the time your service is running, it may
 * be killed by the system if it is under heavy memory pressure.  If this
 * happens, the system will later try to restart the service.  An important
 * consequence of this is that if you implement {@link #onStartCommand onStartCommand()}
 * to schedule work to be done asynchronously or in another thread, then you
 * may want to use {@link #START_FLAG_REDELIVERY} to have the system
 * re-deliver an Intent for you so that it does not get lost if your service
 * is killed while processing it.
 * 
 * <p>Other application components running in the same process as the service
 * (such as an {@link android.app.Activity}) can, of course, increase the
 * importance of the overall
 * process beyond just the importance of the service itself.
 */
public abstract class Service extends ContextWrapper implements ComponentCallbacks {
    private static final String TAG = "Service";

    public Service() {
        super(null);
    }

    /** Return the application that owns this service. */
    public final Application getApplication() {
        return mApplication;
    }

    /**
     * Called by the system when the service is first created.  Do not call this method directly.
     */
    public void onCreate() {
    }

    /**
     * @deprecated Implement {@link #onStartCommand(Intent, int, int)} instead.
     */
    @Deprecated
    public void onStart(Intent intent, int startId) {
    }

    /**
     * Bits returned by {@link #onStartCommand} describing how to continue
     * the service if it is killed.  May be {@link #START_STICKY},
     * {@link #START_NOT_STICKY}, {@link #START_REDELIVER_INTENT},
     * or {@link #START_STICKY_COMPATIBILITY}.
     */
    public static final int START_CONTINUATION_MASK = 0xf;
    
    /**
     * Constant to return from {@link #onStartCommand}: compatibility
     * version of {@link #START_STICKY} that does not guarantee that
     * {@link #onStartCommand} will be called again after being killed.
     */
    public static final int START_STICKY_COMPATIBILITY = 0;
    
    /**
     * Constant to return from {@link #onStartCommand}: if this service's
     * process is killed while it is started (after returning from
     * {@link #onStartCommand}), then leave it in the started state but
     * don't retain this delivered intent.  Later the system will try to
     * re-create the service.  Because it is in the started state, it will
     * guarantee to call {@link #onStartCommand} after creating the new
     * service instance; if there are not any pending start commands to be
     * delivered to the service, it will be called with a null intent
     * object, so you must take care to check for this.
     * 
     * <p>This mode makes sense for things that will be explicitly started
     * and stopped to run for arbitrary periods of time, such as a service
     * performing background music playback.
     */
    public static final int START_STICKY = 1;
    
    /**
     * Constant to return from {@link #onStartCommand}: if this service's
     * process is killed while it is started (after returning from
     * {@link #onStartCommand}), and there are no new start intents to
     * deliver to it, then take the service out of the started state and
     * don't recreate until a future explicit call to
     * {@link Context#startService Context.startService(Intent)}.  The
     * service will not receive a {@link #onStartCommand(Intent, int, int)}
     * call with a null Intent because it will not be re-started if there
     * are no pending Intents to deliver.
     * 
     * <p>This mode makes sense for things that want to do some work as a
     * result of being started, but can be stopped when under memory pressure
     * and will explicit start themselves again later to do more work.  An
     * example of such a service would be one that polls for data from
     * a server: it could schedule an alarm to poll every N minutes by having
     * the alarm start its service.  When its {@link #onStartCommand} is
     * called from the alarm, it schedules a new alarm for N minutes later,
     * and spawns a thread to do its networking.  If its process is killed
     * while doing that check, the service will not be restarted until the
     * alarm goes off.
     */
    public static final int START_NOT_STICKY = 2;
    
    /**
     * Constant to return from {@link #onStartCommand}: if this service's
     * process is killed while it is started (after returning from
     * {@link #onStartCommand}), then it will be scheduled for a restart
     * and the last delivered Intent re-delivered to it again via
     * {@link #onStartCommand}.  This Intent will remain scheduled for
     * redelivery until the service calls {@link #stopSelf(int)} with the
     * start ID provided to {@link #onStartCommand}.  The
     * service will not receive a {@link #onStartCommand(Intent, int, int)}
     * call with a null Intent because it will will only be re-started if
     * it is not finished processing all Intents sent to it (and any such
     * pending events will be delivered at the point of restart).
     */
    public static final int START_REDELIVER_INTENT = 3;
    
    /**
     * This flag is set in {@link #onStartCommand} if the Intent is a
     * re-delivery of a previously delivered intent, because the service
     * had previously returned {@link #START_REDELIVER_INTENT} but had been
     * killed before calling {@link #stopSelf(int)} for that Intent.
     */
    public static final int START_FLAG_REDELIVERY = 0x0001;
    
    /**
     * This flag is set in {@link #onStartCommand} if the Intent is a
     * a retry because the original attempt never got to or returned from
     * {@link #onStartCommand(Intent, int, int)}.
     */
    public static final int START_FLAG_RETRY = 0x0002;
    
    /**
     * Called by the system every time a client explicitly starts the service by calling 
     * {@link android.content.Context#startService}, providing the arguments it supplied and a 
     * unique integer token representing the start request.  Do not call this method directly.
     * 
     * <p>For backwards compatibility, the default implementation calls
     * {@link #onStart} and returns either {@link #START_STICKY}
     * or {@link #START_STICKY_COMPATIBILITY}.
     * 
     * @param intent The Intent supplied to {@link android.content.Context#startService}, 
     * as given.  This may be null if the service is being restarted after
     * its process has gone away, and it had previously returned anything
     * except {@link #START_STICKY_COMPATIBILITY}.
     * @param flags Additional data about this start request.  Currently either
     * 0, {@link #START_FLAG_REDELIVERY}, or {@link #START_FLAG_RETRY}.
     * @param startId A unique integer representing this specific request to 
     * start.  Use with {@link #stopSelfResult(int)}.
     * 
     * @return The return value indicates what semantics the system should
     * use for the service's current started state.  It may be one of the
     * constants associated with the {@link #START_CONTINUATION_MASK} bits.
     * 
     * @see #stopSelfResult(int)
     */
    public int onStartCommand(Intent intent, int flags, int startId) {
        onStart(intent, startId);
        return mStartCompatibility ? START_STICKY_COMPATIBILITY : START_STICKY;
    }
    
    /**
     * Called by the system to notify a Service that it is no longer used and is being removed.  The
     * service should clean up an resources it holds (threads, registered
     * receivers, etc) at this point.  Upon return, there will be no more calls
     * in to this Service object and it is effectively dead.  Do not call this method directly.
     */
    public void onDestroy() {
    }

    public void onConfigurationChanged(Configuration newConfig) {
    }
    
    public void onLowMemory() {
    }
    
    /**
     * Return the communication channel to the service.  May return null if 
     * clients can not bind to the service.  The returned
     * {@link android.os.IBinder} is usually for a complex interface
     * that has been <a href="{@docRoot}guide/developing/tools/aidl.html">described using
     * aidl</a>.
     * 
     * <p><em>Note that unlike other application components, calls on to the
     * IBinder interface returned here may not happen on the main thread
     * of the process</em>.  More information about this can be found
     * in <a href="{@docRoot}guide/topics/fundamentals.html#procthread">Application Fundamentals:
     * Processes and Threads</a>.</p>
     * 
     * @param intent The Intent that was used to bind to this service,
     * as given to {@link android.content.Context#bindService
     * Context.bindService}.  Note that any extras that were included with
     * the Intent at that point will <em>not</em> be seen here.
     * 
     * @return Return an IBinder through which clients can call on to the 
     *         service.
     */
    public abstract IBinder onBind(Intent intent);

    /**
     * Called when all clients have disconnected from a particular interface
     * published by the service.  The default implementation does nothing and
     * returns false.
     * 
     * @param intent The Intent that was used to bind to this service,
     * as given to {@link android.content.Context#bindService
     * Context.bindService}.  Note that any extras that were included with
     * the Intent at that point will <em>not</em> be seen here.
     * 
     * @return Return true if you would like to have the service's
     * {@link #onRebind} method later called when new clients bind to it.
     */
    public boolean onUnbind(Intent intent) {
        return false;
    }
    
    /**
     * Called when new clients have connected to the service, after it had
     * previously been notified that all had disconnected in its
     * {@link #onUnbind}.  This will only be called if the implementation
     * of {@link #onUnbind} was overridden to return true.
     * 
     * @param intent The Intent that was used to bind to this service,
     * as given to {@link android.content.Context#bindService
     * Context.bindService}.  Note that any extras that were included with
     * the Intent at that point will <em>not</em> be seen here.
     */
    public void onRebind(Intent intent) {
    }
    
    /**
     * Stop the service, if it was previously started.  This is the same as
     * calling {@link android.content.Context#stopService} for this particular service.
     *  
     * @see #stopSelfResult(int)
     */
    public final void stopSelf() {
        stopSelf(-1);
    }

    /**
     * Old version of {@link #stopSelfResult} that doesn't return a result.
     *  
     * @see #stopSelfResult
     */
    public final void stopSelf(int startId) {
        if (mActivityManager == null) {
            return;
        }
        try {
            mActivityManager.stopServiceToken(
                    new ComponentName(this, mClassName), mToken, startId);
        } catch (RemoteException ex) {
        }
    }
    
    /**
     * Stop the service if the most recent time it was started was 
     * <var>startId</var>.  This is the same as calling {@link 
     * android.content.Context#stopService} for this particular service but allows you to 
     * safely avoid stopping if there is a start request from a client that you 
     * haven't yet seen in {@link #onStart}. 
     * 
     * <p><em>Be careful about ordering of your calls to this function.</em>.
     * If you call this function with the most-recently received ID before
     * you have called it for previously received IDs, the service will be
     * immediately stopped anyway.  If you may end up processing IDs out
     * of order (such as by dispatching them on separate threads), then you
     * are responsible for stopping them in the same order you received them.</p>
     * 
     * @param startId The most recent start identifier received in {@link 
     *                #onStart}.
     * @return Returns true if the startId matches the last start request
     * and the service will be stopped, else false.
     *  
     * @see #stopSelf()
     */
    public final boolean stopSelfResult(int startId) {
        if (mActivityManager == null) {
            return false;
        }
        try {
            return mActivityManager.stopServiceToken(
                    new ComponentName(this, mClassName), mToken, startId);
        } catch (RemoteException ex) {
        }
        return false;
    }
    
    /**
     * @deprecated This is a now a no-op, use
     * {@link #startForeground(int, Notification)} instead.  This method
     * has been turned into a no-op rather than simply being deprecated
     * because analysis of numerous poorly behaving devices has shown that
     * increasingly often the trouble is being caused in part by applications
     * that are abusing it.  Thus, given a choice between introducing
     * problems in existing applications using this API (by allowing them to
     * be killed when they would like to avoid it), vs allowing the performance
     * of the entire system to be decreased, this method was deemed less
     * important.
     */
    @Deprecated
    public final void setForeground(boolean isForeground) {
        Log.w(TAG, "setForeground: ignoring old API call on " + getClass().getName());
    }
    
    /**
     * Make this service run in the foreground, supplying the ongoing
     * notification to be shown to the user while in this state.
     * By default services are background, meaning that if the system needs to
     * kill them to reclaim more memory (such as to display a large page in a
     * web browser), they can be killed without too much harm.  You can set this
     * flag if killing your service would be disruptive to the user, such as
     * if your service is performing background music playback, so the user
     * would notice if their music stopped playing.
     * 
     * @param id The identifier for this notification as per
     * {@link NotificationManager#notify(int, Notification)
     * NotificationManager.notify(int, Notification)}.
     * @param notification The Notification to be displayed.
     * 
     * @see #stopForeground(boolean)
     */
    public final void startForeground(int id, Notification notification) {
        try {
            mActivityManager.setServiceForeground(
                    new ComponentName(this, mClassName), mToken, id,
                    notification, true);
        } catch (RemoteException ex) {
        }
    }
    
    /**
     * Remove this service from foreground state, allowing it to be killed if
     * more memory is needed.
     * @param removeNotification If true, the notification previously provided
     * to {@link #startForeground} will be removed.  Otherwise it will remain
     * until a later call removes it (or the service is destroyed).
     * @see #startForeground(int, Notification)
     */
    public final void stopForeground(boolean removeNotification) {
        try {
            mActivityManager.setServiceForeground(
                    new ComponentName(this, mClassName), mToken, 0, null,
                    removeNotification);
        } catch (RemoteException ex) {
        }
    }
    
    /**
     * Print the Service's state into the given stream.  This gets invoked if
     * you run "adb shell dumpsys activity service <yourservicename>".
     * This is distinct from "dumpsys <servicename>", which only works for
     * named system services and which invokes the {@link IBinder#dump} method
     * on the {@link IBinder} interface registered with ServiceManager.
     *
     * @param fd The raw file descriptor that the dump is being sent to.
     * @param writer The PrintWriter to which you should dump your state.  This will be
     * closed for you after you return.
     * @param args additional arguments to the dump request.
     */
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        writer.println("nothing to dump");
    }
    
    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        //Log.i("Service", "Finalizing Service: " + this);
    }

    // ------------------ Internal API ------------------
    
    /**
     * @hide
     */
    public final void attach(
            Context context,
            ActivityThread thread, String className, IBinder token,
            Application application, Object activityManager) {
        attachBaseContext(context);
        mThread = thread;           // NOTE:  unused - remove?
        mClassName = className;
        mToken = token;
        mApplication = application;
        mActivityManager = (IActivityManager)activityManager;
        mStartCompatibility = getApplicationInfo().targetSdkVersion
                < Build.VERSION_CODES.ECLAIR;
    }
    
    final String getClassName() {
        return mClassName;
    }

    // set by the thread after the constructor and before onCreate(Bundle icicle) is called.
    private ActivityThread mThread = null;
    private String mClassName = null;
    private IBinder mToken = null;
    private Application mApplication = null;
    private IActivityManager mActivityManager = null;
    private boolean mStartCompatibility = false;
}
