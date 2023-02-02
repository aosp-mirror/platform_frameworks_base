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

import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentCallbacks2;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.pm.ServiceInfo.ForegroundServiceType;
import android.content.res.Configuration;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;
import android.view.contentcapture.ContentCaptureManager;

import com.android.internal.annotations.GuardedBy;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A Service is an application component representing either an application's desire
 * to perform a longer-running operation while not interacting with the user
 * or to supply functionality for other applications to use.  Each service
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
 * <a href="{@docRoot}guide/topics/fundamentals/processes-and-threads.html">Processes and
 * Threads</a>.  The {@link androidx.core.app.JobIntentService} class is available
 * as a standard implementation of Service that has its own thread where it
 * schedules its work to be done.</p>
 * 
 * <p>Topics covered here:
 * <ol>
 * <li><a href="#WhatIsAService">What is a Service?</a>
 * <li><a href="#ServiceLifecycle">Service Lifecycle</a>
 * <li><a href="#Permissions">Permissions</a>
 * <li><a href="#ProcessLifecycle">Process Lifecycle</a>
 * <li><a href="#LocalServiceSample">Local Service Sample</a>
 * <li><a href="#RemoteMessengerServiceSample">Remote Messenger Service Sample</a>
 * </ol>
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For a detailed discussion about how to create services, read the
 * <a href="{@docRoot}guide/topics/fundamentals/services.html">Services</a> developer guide.</p>
 * </div>
 *
 * <a name="WhatIsAService"></a>
 * <h3>What is a Service?</h3>
 * 
 * <p>Most confusion about the Service class actually revolves around what
 * it is <em>not</em>:</p>
 * 
 * <ul>
 * <li> A Service is <b>not</b> a separate process.  The Service object itself
 * does not imply it is running in its own process; unless otherwise specified,
 * it runs in the same process as the application it is part of.
 * <li> A Service is <b>not</b> a thread.  It is not a means itself to do work off
 * of the main thread (to avoid Application Not Responding errors).
 * </ul>
 * 
 * <p>Thus a Service itself is actually very simple, providing two main features:</p>
 * 
 * <ul>
 * <li>A facility for the application to tell the system <em>about</em>
 * something it wants to be doing in the background (even when the user is not
 * directly interacting with the application).  This corresponds to calls to
 * {@link android.content.Context#startService Context.startService()}, which
 * ask the system to schedule work for the service, to be run until the service
 * or someone else explicitly stop it.
 * <li>A facility for an application to expose some of its functionality to
 * other applications.  This corresponds to calls to
 * {@link android.content.Context#bindService Context.bindService()}, which
 * allows a long-standing connection to be made to the service in order to
 * interact with it.
 * </ul>
 * 
 * <p>When a Service component is actually created, for either of these reasons,
 * all that the system actually does is instantiate the component
 * and call its {@link #onCreate} and any other appropriate callbacks on the
 * main thread.  It is up to the Service to implement these with the appropriate
 * behavior, such as creating a secondary thread in which it does its work.</p>
 * 
 * <p>Note that because Service itself is so simple, you can make your
 * interaction with it as simple or complicated as you want: from treating it
 * as a local Java object that you make direct method calls on (as illustrated
 * by <a href="#LocalServiceSample">Local Service Sample</a>), to providing
 * a full remoteable interface using AIDL.</p>
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
 * interface that has been <a href="{@docRoot}guide/components/aidl.html">written
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
 * <p>As of {@link android.os.Build.VERSION_CODES#GINGERBREAD}, when using
 * {@link Context#startService(Intent) Context.startService(Intent)}, you can
 * also set {@link Intent#FLAG_GRANT_READ_URI_PERMISSION
 * Intent.FLAG_GRANT_READ_URI_PERMISSION} and/or {@link Intent#FLAG_GRANT_WRITE_URI_PERMISSION
 * Intent.FLAG_GRANT_WRITE_URI_PERMISSION} on the Intent.  This will grant the
 * Service temporary access to the specific URIs in the Intent.  Access will
 * remain until the Service has called {@link #stopSelf(int)} for that start
 * command or a later one, or until the Service has been completely stopped.
 * This works for granting access to the other apps that have not requested
 * the permission protecting the Service, or even when the Service is not
 * exported at all.
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
 * the service should not be killed except in low memory conditions.  However, since
 * the user is not directly aware of a background service, in that state it <em>is</em>
 * considered a valid candidate to kill, and you should be prepared for this to
 * happen.  In particular, long-running services will be increasingly likely to
 * kill and are guaranteed to be killed (and restarted if appropriate) if they
 * remain started long enough.
 * <li><p>If there are clients bound to the service, then the service's hosting
 * process is never less important than the most important client.  That is,
 * if one of its clients is visible to the user, then the service itself is
 * considered to be visible.  The way a client's importance impacts the service's
 * importance can be adjusted through {@link Context#BIND_ABOVE_CLIENT},
 * {@link Context#BIND_ALLOW_OOM_MANAGEMENT}, {@link Context#BIND_WAIVE_PRIORITY},
 * {@link Context#BIND_IMPORTANT}, and {@link Context#BIND_ADJUST_WITH_ACTIVITY}.
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
 * 
 * <a name="LocalServiceSample"></a>
 * <h3>Local Service Sample</h3>
 * 
 * <p>One of the most common uses of a Service is as a secondary component
 * running alongside other parts of an application, in the same process as
 * the rest of the components.  All components of an .apk run in the same
 * process unless explicitly stated otherwise, so this is a typical situation.
 * 
 * <p>When used in this way, by assuming the
 * components are in the same process, you can greatly simplify the interaction
 * between them: clients of the service can simply cast the IBinder they
 * receive from it to a concrete class published by the service.
 * 
 * <p>An example of this use of a Service is shown here.  First is the Service
 * itself, publishing a custom class when bound:
 * 
 * {@sample development/samples/ApiDemos/src/com/example/android/apis/app/LocalService.java
 *      service}
 * 
 * <p>With that done, one can now write client code that directly accesses the
 * running service, such as:
 * 
 * {@sample development/samples/ApiDemos/src/com/example/android/apis/app/LocalServiceActivities.java
 *      bind}
 * 
 * <a name="RemoteMessengerServiceSample"></a>
 * <h3>Remote Messenger Service Sample</h3>
 * 
 * <p>If you need to be able to write a Service that can perform complicated
 * communication with clients in remote processes (beyond simply the use of
 * {@link Context#startService(Intent) Context.startService} to send
 * commands to it), then you can use the {@link android.os.Messenger} class
 * instead of writing full AIDL files.
 * 
 * <p>An example of a Service that uses Messenger as its client interface
 * is shown here.  First is the Service itself, publishing a Messenger to
 * an internal Handler when bound:
 * 
 * {@sample development/samples/ApiDemos/src/com/example/android/apis/app/MessengerService.java
 *      service}
 * 
 * <p>If we want to make this service run in a remote process (instead of the
 * standard one for its .apk), we can use <code>android:process</code> in its
 * manifest tag to specify one:
 * 
 * {@sample development/samples/ApiDemos/AndroidManifest.xml remote_service_declaration}
 * 
 * <p>Note that the name "remote" chosen here is arbitrary, and you can use
 * other names if you want additional processes.  The ':' prefix appends the
 * name to your package's standard process name.
 * 
 * <p>With that done, clients can now bind to the service and send messages
 * to it.  Note that this allows clients to register with it to receive
 * messages back as well:
 * 
 * {@sample development/samples/ApiDemos/src/com/example/android/apis/app/MessengerServiceActivities.java
 *      bind}
 */
public abstract class Service extends ContextWrapper implements ComponentCallbacks2,
        ContentCaptureManager.ContentCaptureClient {
    private static final String TAG = "Service";

    /**
     * Selector for {@link #stopForeground(int)}:  equivalent to passing {@code false}
     * to the legacy API {@link #stopForeground(boolean)}.
     *
     * @deprecated Use {@link #STOP_FOREGROUND_DETACH} instead.  The legacy
     * behavior was inconsistent, leading to bugs around unpredictable results.
     */
    @Deprecated
    public static final int STOP_FOREGROUND_LEGACY = 0;

    /**
     * Selector for {@link #stopForeground(int)}: if supplied, the notification previously
     * supplied to {@link #startForeground} will be cancelled and removed from display.
     */
    public static final int STOP_FOREGROUND_REMOVE = 1<<0;

    /**
     * Selector for {@link #stopForeground(int)}: if set, the notification previously supplied
     * to {@link #startForeground} will be detached from the service's lifecycle.  The notification
     * will remain shown even after the service is stopped and destroyed.
     */
    public static final int STOP_FOREGROUND_DETACH = 1<<1;

    /** @hide */
    @IntDef(flag = false, prefix = { "STOP_FOREGROUND_" }, value = {
            STOP_FOREGROUND_LEGACY,
            STOP_FOREGROUND_REMOVE,
            STOP_FOREGROUND_DETACH
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StopForegroundSelector {}

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
     *
     * <p>Since Android version {@link Build.VERSION_CODES#S}, apps
     * targeting {@link Build.VERSION_CODES#S} or above are disallowed
     * to start a foreground service from the background, but the restriction
     * doesn't impact <em>restarts</em> of a sticky foreground service. However,
     * when apps start a sticky foreground service from the background,
     * the same restriction still applies.
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
     * call with a null Intent because it will not be restarted if there
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
     * call with a null Intent because it will only be restarted if
     * it is not finished processing all Intents sent to it (and any such
     * pending events will be delivered at the point of restart).
     */
    public static final int START_REDELIVER_INTENT = 3;

    /** @hide */
    @IntDef(flag = false, prefix = { "START_" }, value = {
            START_STICKY_COMPATIBILITY,
            START_STICKY,
            START_NOT_STICKY,
            START_REDELIVER_INTENT,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StartResult {}

    /**
     * Special constant for reporting that we are done processing
     * {@link #onTaskRemoved(Intent)}.
     * @hide
     */
    public static final int START_TASK_REMOVED_COMPLETE = 1000;

    /**
     * This flag is set in {@link #onStartCommand} if the Intent is a
     * re-delivery of a previously delivered intent, because the service
     * had previously returned {@link #START_REDELIVER_INTENT} but had been
     * killed before calling {@link #stopSelf(int)} for that Intent.
     */
    public static final int START_FLAG_REDELIVERY = 0x0001;
    
    /**
     * This flag is set in {@link #onStartCommand} if the Intent is a
     * retry because the original attempt never got to or returned from
     * {@link #onStartCommand(Intent, int, int)}.
     */
    public static final int START_FLAG_RETRY = 0x0002;

    /** @hide */
    @IntDef(flag = true, prefix = { "START_FLAG_" }, value = {
            START_FLAG_REDELIVERY,
            START_FLAG_RETRY,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StartArgFlags {}


    /**
     * Called by the system every time a client explicitly starts the service by calling 
     * {@link android.content.Context#startService}, providing the arguments it supplied and a 
     * unique integer token representing the start request.  Do not call this method directly.
     * 
     * <p>For backwards compatibility, the default implementation calls
     * {@link #onStart} and returns either {@link #START_STICKY}
     * or {@link #START_STICKY_COMPATIBILITY}.
     * 
     * <p class="caution">Note that the system calls this on your
     * service's main thread.  A service's main thread is the same
     * thread where UI operations take place for Activities running in the
     * same process.  You should always avoid stalling the main
     * thread's event loop.  When doing long-running operations,
     * network calls, or heavy disk I/O, you should kick off a new
     * thread, or use {@link android.os.AsyncTask}.</p>
     *
     * @param intent The Intent supplied to {@link android.content.Context#startService}, 
     * as given.  This may be null if the service is being restarted after
     * its process has gone away, and it had previously returned anything
     * except {@link #START_STICKY_COMPATIBILITY}.
     * @param flags Additional data about this start request.
     * @param startId A unique integer representing this specific request to 
     * start.  Use with {@link #stopSelfResult(int)}.
     * 
     * @return The return value indicates what semantics the system should
     * use for the service's current started state.  It may be one of the
     * constants associated with the {@link #START_CONTINUATION_MASK} bits.
     * 
     * @see #stopSelfResult(int)
     */
    public @StartResult int onStartCommand(Intent intent, @StartArgFlags int flags, int startId) {
        onStart(intent, startId);
        return mStartCompatibility ? START_STICKY_COMPATIBILITY : START_STICKY;
    }
    
    /**
     * Called by the system to notify a Service that it is no longer used and is being removed.  The
     * service should clean up any resources it holds (threads, registered
     * receivers, etc) at this point.  Upon return, there will be no more calls
     * in to this Service object and it is effectively dead.  Do not call this method directly.
     */
    public void onDestroy() {
    }

    public void onConfigurationChanged(Configuration newConfig) {
    }
    
    public void onLowMemory() {
    }

    public void onTrimMemory(int level) {
    }

    /**
     * Return the communication channel to the service.  May return null if 
     * clients can not bind to the service.  The returned
     * {@link android.os.IBinder} is usually for a complex interface
     * that has been <a href="{@docRoot}guide/components/aidl.html">described using
     * aidl</a>.
     * 
     * <p><em>Note that unlike other application components, calls on to the
     * IBinder interface returned here may not happen on the main thread
     * of the process</em>.  More information about the main thread can be found in
     * <a href="{@docRoot}guide/topics/fundamentals/processes-and-threads.html">Processes and
     * Threads</a>.</p>
     * 
     * @param intent The Intent that was used to bind to this service,
     * as given to {@link android.content.Context#bindService
     * Context.bindService}.  Note that any extras that were included with
     * the Intent at that point will <em>not</em> be seen here.
     * 
     * @return Return an IBinder through which clients can call on to the 
     *         service.
     */
    @Nullable
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
     * This is called if the service is currently running and the user has
     * removed a task that comes from the service's application.  If you have
     * set {@link android.content.pm.ServiceInfo#FLAG_STOP_WITH_TASK ServiceInfo.FLAG_STOP_WITH_TASK}
     * then you will not receive this callback; instead, the service will simply
     * be stopped.
     *
     * @param rootIntent The original root Intent that was used to launch
     * the task that is being removed.
     */
    public void onTaskRemoved(Intent rootIntent) {
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
     * 
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage
    public final void setForeground(boolean isForeground) {
        Log.w(TAG, "setForeground: ignoring old API call on " + getClass().getName());
    }
    
    /**
     * If your service is started (running through {@link Context#startService(Intent)}), then
     * also make this service run in the foreground, supplying the ongoing
     * notification to be shown to the user while in this state.
     * By default started services are background, meaning that their process won't be given
     * foreground CPU scheduling (unless something else in that process is foreground) and,
     * if the system needs to kill them to reclaim more memory (such as to display a large page in a
     * web browser), they can be killed without too much harm.  You use
     * {@link #startForeground} if killing your service would be disruptive to the user, such as
     * if your service is performing background music playback, so the user
     * would notice if their music stopped playing.
     *
     * <p>Note that calling this method does <em>not</em> put the service in the started state
     * itself, even though the name sounds like it.  You must always call
     * {@link #startService(Intent)} first to tell the system it should keep the service running,
     * and then use this method to tell it to keep it running harder.</p>
     *
     * <p>Apps targeting API {@link android.os.Build.VERSION_CODES#P} or later must request
     * the permission {@link android.Manifest.permission#FOREGROUND_SERVICE} in order to use
     * this API.</p>
     *
     * <p>Apps built with SDK version {@link android.os.Build.VERSION_CODES#Q} or later can specify
     * the foreground service types using attribute {@link android.R.attr#foregroundServiceType} in
     * service element of manifest file. The value of attribute
     * {@link android.R.attr#foregroundServiceType} can be multiple flags ORed together.</p>
     *
     * <div class="caution">
     * <p><strong>Note:</strong>
     * Beginning with SDK Version {@link android.os.Build.VERSION_CODES#S},
     * apps targeting SDK Version {@link android.os.Build.VERSION_CODES#S}
     * or higher are not allowed to start foreground services from the background.
     * See
     * <a href="{@docRoot}about/versions/12/behavior-changes-12">
     * Behavior changes: Apps targeting Android 12
     * </a>
     * for more details.
     * </div>
     *
     * @throws ForegroundServiceStartNotAllowedException
     * If the app targeting API is
     * {@link android.os.Build.VERSION_CODES#S} or later, and the service is restricted from
     * becoming foreground service due to background restriction.
     *
     * @param id The identifier for this notification as per
     * {@link NotificationManager#notify(int, Notification)
     * NotificationManager.notify(int, Notification)}; must not be 0.
     * @param notification The Notification to be displayed.
     * 
     * @see #stopForeground(boolean)
     */
    public final void startForeground(int id, Notification notification) {
        try {
            mActivityManager.setServiceForeground(
                    new ComponentName(this, mClassName), mToken, id,
                    notification, 0, FOREGROUND_SERVICE_TYPE_MANIFEST);
            clearStartForegroundServiceStackTrace();
        } catch (RemoteException ex) {
        }
    }

  /**
   * An overloaded version of {@link #startForeground(int, Notification)} with additional
   * foregroundServiceType parameter.
   *
   * <p>Apps built with SDK version {@link android.os.Build.VERSION_CODES#Q} or later can specify
   * the foreground service types using attribute {@link android.R.attr#foregroundServiceType} in
   * service element of manifest file. The value of attribute
   * {@link android.R.attr#foregroundServiceType} can be multiple flags ORed together.</p>
   *
   * <p>The foregroundServiceType parameter must be a subset flags of what is specified in manifest
   * attribute {@link android.R.attr#foregroundServiceType}, if not, an IllegalArgumentException is
   * thrown. Specify foregroundServiceType parameter as
   * {@link android.content.pm.ServiceInfo#FOREGROUND_SERVICE_TYPE_MANIFEST} to use all flags that
   * is specified in manifest attribute foregroundServiceType.</p>
   *
   * <div class="caution">
   * <p><strong>Note:</strong>
   * Beginning with SDK Version {@link android.os.Build.VERSION_CODES#S},
   * apps targeting SDK Version {@link android.os.Build.VERSION_CODES#S}
   * or higher are not allowed to start foreground services from the background.
   * See
   * <a href="{@docRoot}about/versions/12/behavior-changes-12">
   * Behavior changes: Apps targeting Android 12
   * </a>
   * for more details.
   * </div>
   *
   * @param id The identifier for this notification as per
   * {@link NotificationManager#notify(int, Notification)
   * NotificationManager.notify(int, Notification)}; must not be 0.
   * @param notification The Notification to be displayed.
   * @param foregroundServiceType must be a subset flags of manifest attribute
   * {@link android.R.attr#foregroundServiceType} flags.
   *
   * @throws IllegalArgumentException if param foregroundServiceType is not subset of manifest
   *     attribute {@link android.R.attr#foregroundServiceType}.
   * @throws ForegroundServiceStartNotAllowedException
   * If the app targeting API is
   * {@link android.os.Build.VERSION_CODES#S} or later, and the service is restricted from
   * becoming foreground service due to background restriction.
   *
   * @see android.content.pm.ServiceInfo#FOREGROUND_SERVICE_TYPE_MANIFEST
   */
    public final void startForeground(int id, @NonNull Notification notification,
            @ForegroundServiceType int foregroundServiceType) {
        try {
            mActivityManager.setServiceForeground(
                    new ComponentName(this, mClassName), mToken, id,
                    notification, 0, foregroundServiceType);
            clearStartForegroundServiceStackTrace();
        } catch (RemoteException ex) {
        }
    }

    /**
     * Legacy version of {@link #stopForeground(int)}.
     * @param removeNotification If true, the {@link #STOP_FOREGROUND_REMOVE}
     * selector will be passed to {@link #stopForeground(int)}; otherwise
     * {@link #STOP_FOREGROUND_LEGACY} will be passed.
     * @see #stopForeground(int)
     * @see #startForeground(int, Notification)
     *
     * @deprecated call {@link #stopForeground(int)} and pass either
     * {@link #STOP_FOREGROUND_REMOVE} or {@link #STOP_FOREGROUND_DETACH}
     * explicitly instead.
     */
    @Deprecated
    public final void stopForeground(boolean removeNotification) {
        stopForeground(removeNotification ? STOP_FOREGROUND_REMOVE : STOP_FOREGROUND_LEGACY);
    }

    /**
     * Remove this service from foreground state, allowing it to be killed if
     * more memory is needed.  This does not stop the service from running (for that
     * you use {@link #stopSelf()} or related methods), just takes it out of the
     * foreground state.
     *
     * <p>If {@link #STOP_FOREGROUND_REMOVE} is supplied, the service's associated
     * notification will be cancelled immediately.</p>
     * <p>If {@link #STOP_FOREGROUND_DETACH} is supplied, the service's association
     * with the notification will be severed.  If the notification had not yet been
     * shown, due to foreground-service notification deferral policy, it is
     * immediately posted when {@code stopForeground(STOP_FOREGROUND_DETACH)}
     * is called.  In all cases, the notification remains shown
     * even after this service is stopped fully and destroyed.</p>
     * <p>If {@code zero} is passed as the argument, the result will be the legacy
     * behavior as defined prior to Android L: the notification will remain posted until
     * the service is fully stopped, at which time it will automatically be cancelled.</p>
     *
     * @param notificationBehavior the intended behavior for the service's associated
     * notification
     * @see #startForeground(int, Notification)
     * @see #STOP_FOREGROUND_DETACH
     * @see #STOP_FOREGROUND_REMOVE
     */
    public final void stopForeground(@StopForegroundSelector int notificationBehavior) {
        try {
            mActivityManager.setServiceForeground(
                    new ComponentName(this, mClassName), mToken, 0, null,
                    notificationBehavior, 0);
        } catch (RemoteException ex) {
        }
    }

    /**
     * If the service has become a foreground service by calling
     * {@link #startForeground(int, Notification)}
     * or {@link #startForeground(int, Notification, int)}, {@link #getForegroundServiceType()}
     * returns the current foreground service type.
     *
     * <p>If there is no foregroundServiceType specified
     * in manifest, {@link ServiceInfo#FOREGROUND_SERVICE_TYPE_NONE} is returned. </p>
     *
     * <p>If the service is not a foreground service,
     * {@link ServiceInfo#FOREGROUND_SERVICE_TYPE_NONE} is returned.</p>
     *
     * @return current foreground service type flags.
     */
    public final @ForegroundServiceType int getForegroundServiceType() {
        int ret = ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE;
        try {
            ret = mActivityManager.getForegroundServiceType(
                    new ComponentName(this, mClassName), mToken);
        } catch (RemoteException ex) {
        }
        return ret;
    }

    /**
     * Print the Service's state into the given stream.  This gets invoked if
     * you run "adb shell dumpsys activity service &lt;yourservicename&gt;"
     * (note that for this command to work, the service must be running, and
     * you must specify a fully-qualified service name).
     * This is distinct from "dumpsys &lt;servicename&gt;", which only works for
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
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(newBase);
        if (newBase != null) {
            newBase.setContentCaptureOptions(getContentCaptureOptions());
        }
    }

    // ------------------ Internal API ------------------
    
    /**
     * @hide
     */
    @UnsupportedAppUsage
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

        setContentCaptureOptions(application.getContentCaptureOptions());
    }

    /**
     * Creates the base {@link Context} of this {@link Service}.
     * Users may override this API to create customized base context.
     *
     * @see android.window.WindowProviderService WindowProviderService class for example
     * @see ContextWrapper#attachBaseContext(Context)
     *
     * @hide
     */
    public Context createServiceBaseContext(ActivityThread mainThread, LoadedApk packageInfo) {
        return ContextImpl.createAppContext(mainThread, packageInfo);
    }

    /**
     * @hide
     * Clean up any references to avoid leaks.
     */
    public final void detachAndCleanUp() {
        mToken = null;
    }

    final String getClassName() {
        return mClassName;
    }

    /** @hide */
    @Override
    public final ContentCaptureManager.ContentCaptureClient getContentCaptureClient() {
        return this;
    }

    /** @hide */
    @Override
    public final ComponentName contentCaptureClientGetComponentName() {
        return new ComponentName(this, mClassName);
    }

    // set by the thread after the constructor and before onCreate(Bundle icicle) is called.
    @UnsupportedAppUsage
    private ActivityThread mThread = null;
    @UnsupportedAppUsage
    private String mClassName = null;
    @UnsupportedAppUsage
    private IBinder mToken = null;
    @UnsupportedAppUsage
    private Application mApplication = null;
    @UnsupportedAppUsage
    private IActivityManager mActivityManager = null;
    @UnsupportedAppUsage
    private boolean mStartCompatibility = false;

    /**
     * This keeps track of the stacktrace where Context.startForegroundService() was called
     * for each service class. We use that when we crash the app for not calling
     * {@link #startForeground} in time, in {@link ActivityThread#throwRemoteServiceException}.
     */
    @GuardedBy("sStartForegroundServiceStackTraces")
    private static final ArrayMap<String, StackTrace> sStartForegroundServiceStackTraces =
            new ArrayMap<>();

    /** @hide */
    public static void setStartForegroundServiceStackTrace(
            @NonNull String className, @NonNull StackTrace stacktrace) {
        synchronized (sStartForegroundServiceStackTraces) {
            sStartForegroundServiceStackTraces.put(className, stacktrace);
        }
    }

    private void clearStartForegroundServiceStackTrace() {
        synchronized (sStartForegroundServiceStackTraces) {
            sStartForegroundServiceStackTraces.remove(this.getClassName());
        }
    }

    /** @hide */
    public static StackTrace getStartForegroundServiceStackTrace(@NonNull String className) {
        synchronized (sStartForegroundServiceStackTraces) {
            return sStartForegroundServiceStackTraces.get(className);
        }
    }
}
