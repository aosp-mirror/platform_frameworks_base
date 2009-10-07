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

package android.content;

import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

/**
 * Base class for code that will receive intents sent by sendBroadcast().
 * You can either dynamically register an instance of this class with
 * {@link Context#registerReceiver Context.registerReceiver()}
 * or statically publish an implementation through the
 * {@link android.R.styleable#AndroidManifestReceiver &lt;receiver&gt;}
 * tag in your <code>AndroidManifest.xml</code>. <em><strong>Note:</strong></em>
 * &nbsp;&nbsp;&nbsp;If registering a receiver in your
 * {@link android.app.Activity#onResume() Activity.onResume()}
 * implementation, you should unregister it in 
 * {@link android.app.Activity#onPause() Activity.onPause()}.
 * (You won't receive intents when paused, 
 * and this will cut down on unnecessary system overhead). Do not unregister in 
 * {@link android.app.Activity#onSaveInstanceState(android.os.Bundle) Activity.onSaveInstanceState()},
 * because this won't be called if the user moves back in the history
 * stack.
 * 
 * <p>There are two major classes of broadcasts that can be received:</p>
 * <ul>
 * <li> <b>Normal broadcasts</b> (sent with {@link Context#sendBroadcast(Intent)
 * Context.sendBroadcast}) are completely asynchronous.  All receivers of the
 * broadcast are run in an undefined order, often at the same time.  This is
 * more efficient, but means that receivers cannot use the result or abort
 * APIs included here.
 * <li> <b>Ordered broadcasts</b> (sent with {@link Context#sendOrderedBroadcast(Intent, String)
 * Context.sendOrderedBroadcast}) are delivered to one receiver at a time.
 * As each receiver executes in turn, it can propagate a result to the next
 * receiver, or it can completely abort the broadcast so that it won't be passed
 * to other receivers.  The order receivers run in can be controlled with the
 * {@link android.R.styleable#AndroidManifestIntentFilter_priority
 * android:priority} attribute of the matching intent-filter; receivers with
 * the same priority will be run in an arbitrary order.
 * </ul>
 * 
 * <p>Even in the case of normal broadcasts, the system may in some
 * situations revert to delivering the broadcast one receiver at a time.  In
 * particular, for receivers that may require the creation of a process, only
 * one will be run at a time to avoid overloading the system with new processes.
 * In this situation, however, the non-ordered semantics hold: these receivers still
 * cannot return results or abort their broadcast.</p>
 * 
 * <p>Note that, although the Intent class is used for sending and receiving
 * these broadcasts, the Intent broadcast mechanism here is completely separate
 * from Intents that are used to start Activities with
 * {@link Context#startActivity Context.startActivity()}.
 * There is no way for a BroadcastReceiver
 * to see or capture Intents used with startActivity(); likewise, when
 * you broadcast an Intent, you will never find or start an Activity.
 * These two operations are semantically very different: starting an
 * Activity with an Intent is a foreground operation that modifies what the
 * user is currently interacting with; broadcasting an Intent is a background
 * operation that the user is not normally aware of.
 * 
 * <p>The BroadcastReceiver class (when launched as a component through
 * a manifest's {@link android.R.styleable#AndroidManifestReceiver &lt;receiver&gt;}
 * tag) is an important part of an
 * <a href="{@docRoot}guide/topics/fundamentals.html#lcycles">application's overall lifecycle</a>.</p>
 * 
 * <p>Topics covered here:
 * <ol>
 * <li><a href="#ReceiverLifecycle">Receiver Lifecycle</a>
 * <li><a href="#Permissions">Permissions</a>
 * <li><a href="#ProcessLifecycle">Process Lifecycle</a>
 * </ol>
 * 
 * <a name="ReceiverLifecycle"></a>
 * <h3>Receiver Lifecycle</h3>
 * 
 * <p>A BroadcastReceiver object is only valid for the duration of the call
 * to {@link #onReceive}.  Once your code returns from this function,
 * the system considers the object to be finished and no longer active.
 * 
 * <p>This has important repercussions to what you can do in an
 * {@link #onReceive} implementation: anything that requires asynchronous
 * operation is not available, because you will need to return from the
 * function to handle the asynchronous operation, but at that point the
 * BroadcastReceiver is no longer active and thus the system is free to kill
 * its process before the asynchronous operation completes.
 * 
 * <p>In particular, you may <i>not</i> show a dialog or bind to a service from
 * within a BroadcastReceiver.  For the former, you should instead use the
 * {@link android.app.NotificationManager} API.  For the latter, you can
 * use {@link android.content.Context#startService Context.startService()} to
 * send a command to the service.
 * 
 * <a name="Permissions"></a>
 * <h3>Permissions</h3>
 * 
 * <p>Access permissions can be enforced by either the sender or receiver
 * of an Intent.
 * 
 * <p>To enforce a permission when sending, you supply a non-null
 * <var>permission</var> argument to
 * {@link Context#sendBroadcast(Intent, String)} or
 * {@link Context#sendOrderedBroadcast(Intent, String, BroadcastReceiver, android.os.Handler, int, String, Bundle)}.
 * Only receivers who have been granted this permission
 * (by requesting it with the
 * {@link android.R.styleable#AndroidManifestUsesPermission &lt;uses-permission&gt;}
 * tag in their <code>AndroidManifest.xml</code>) will be able to receive
 * the broadcast.
 * 
 * <p>To enforce a permission when receiving, you supply a non-null
 * <var>permission</var> when registering your receiver -- either when calling
 * {@link Context#registerReceiver(BroadcastReceiver, IntentFilter, String, android.os.Handler)}
 * or in the static
 * {@link android.R.styleable#AndroidManifestReceiver &lt;receiver&gt;}
 * tag in your <code>AndroidManifest.xml</code>.  Only broadcasters who have
 * been granted this permission (by requesting it with the
 * {@link android.R.styleable#AndroidManifestUsesPermission &lt;uses-permission&gt;}
 * tag in their <code>AndroidManifest.xml</code>) will be able to send an
 * Intent to the receiver.
 * 
 * <p>See the <a href="{@docRoot}guide/topics/security/security.html">Security and Permissions</a>
 * document for more information on permissions and security in general.
 * 
 * <a name="ProcessLifecycle"></a>
 * <h3>Process Lifecycle</h3>
 * 
 * <p>A process that is currently executing a BroadcastReceiver (that is,
 * currently running the code in its {@link #onReceive} method) is
 * considered to be a foreground process and will be kept running by the
 * system except under cases of extreme memory pressure.
 * 
 * <p>Once you return from onReceive(), the BroadcastReceiver is no longer
 * active, and its hosting process is only as important as any other application
 * components that are running in it.  This is especially important because if
 * that process was only hosting the BroadcastReceiver (a common case for
 * applications that the user has never or not recently interacted with), then
 * upon returning from onReceive() the system will consider its process
 * to be empty and aggressively kill it so that resources are available for other
 * more important processes.
 * 
 * <p>This means that for longer-running operations you will often use
 * a {@link android.app.Service} in conjunction with a BroadcastReceiver to keep
 * the containing process active for the entire time of your operation.
 */
public abstract class BroadcastReceiver {
    public BroadcastReceiver() {
    }

    /**
     * This method is called when the BroadcastReceiver is receiving an Intent
     * broadcast.  During this time you can use the other methods on
     * BroadcastReceiver to view/modify the current result values.  The function
     * is normally called within the main thread of its process, so you should
     * never perform long-running operations in it (there is a timeout of
     * 10 seconds that the system allows before considering the receiver to
     * be blocked and a candidate to be killed). You cannot launch a popup dialog
     * in your implementation of onReceive().
     * 
     * <p><b>If this BroadcastReceiver was launched through a &lt;receiver&gt; tag,
     * then the object is no longer alive after returning from this
     * function.</b>  This means you should not perform any operations that
     * return a result to you asynchronously -- in particular, for interacting
     * with services, you should use
     * {@link Context#startService(Intent)} instead of
     * {@link Context#bindService(Intent, ServiceConnection, int)}.  If you wish
     * to interact with a service that is already running, you can use
     * {@link #peekService}.
     * 
     * <p>The Intent filters used in {@link android.content.Context#registerReceiver}
     * and in application manifests are <em>not</em> guaranteed to be exclusive. They
     * are hints to the operating system about how to find suitable recipients. It is
     * possible for senders to force delivery to specific recipients, bypassing filter
     * resolution.  For this reason, {@link #onReceive(Context, Intent) onReceive()}
     * implementations should respond only to known actions, ignoring any unexpected
     * Intents that they may receive.
     * 
     * @param context The Context in which the receiver is running.
     * @param intent The Intent being received.
     */
    public abstract void onReceive(Context context, Intent intent);

    /**
     * Provide a binder to an already-running service.  This method is synchronous
     * and will not start the target service if it is not present, so it is safe
     * to call from {@link #onReceive}.
     * 
     * @param myContext The Context that had been passed to {@link #onReceive(Context, Intent)}
     * @param service The Intent indicating the service you wish to use.  See {@link
     * Context#startService(Intent)} for more information.
     */
    public IBinder peekService(Context myContext, Intent service) {
        IActivityManager am = ActivityManagerNative.getDefault();
        IBinder binder = null;
        try {
            binder = am.peekService(service, service.resolveTypeIfNeeded(
                    myContext.getContentResolver()));
        } catch (RemoteException e) {
        }
        return binder;
    }

    /**
     * Change the current result code of this broadcast; only works with
     * broadcasts sent through
     * {@link Context#sendOrderedBroadcast(Intent, String)
     * Context.sendOrderedBroadcast}.  Often uses the
     * Activity {@link android.app.Activity#RESULT_CANCELED} and
     * {@link android.app.Activity#RESULT_OK} constants, though the
     * actual meaning of this value is ultimately up to the broadcaster.
     * 
     * <p><strong>This method does not work with non-ordered broadcasts such
     * as those sent with {@link Context#sendBroadcast(Intent)
     * Context.sendBroadcast}</strong></p>
     * 
     * @param code The new result code.
     * 
     * @see #setResult(int, String, Bundle)
     */
    public final void setResultCode(int code) {
        checkSynchronousHint();
        mResultCode = code;
    }

    /**
     * Retrieve the current result code, as set by the previous receiver.
     * 
     * @return int The current result code.
     */
    public final int getResultCode() {
        return mResultCode;
    }

    /**
     * Change the current result data of this broadcast; only works with
     * broadcasts sent through
     * {@link Context#sendOrderedBroadcast(Intent, String)
     * Context.sendOrderedBroadcast}.  This is an arbitrary
     * string whose interpretation is up to the broadcaster.
     * 
     * <p><strong>This method does not work with non-ordered broadcasts such
     * as those sent with {@link Context#sendBroadcast(Intent)
     * Context.sendBroadcast}</strong></p>
     * 
     * @param data The new result data; may be null.
     * 
     * @see #setResult(int, String, Bundle)
     */
    public final void setResultData(String data) {
        checkSynchronousHint();
        mResultData = data;
    }

    /**
     * Retrieve the current result data, as set by the previous receiver.
     * Often this is null.
     * 
     * @return String The current result data; may be null.
     */
    public final String getResultData() {
        return mResultData;
    }

    /**
     * Change the current result extras of this broadcast; only works with
     * broadcasts sent through
     * {@link Context#sendOrderedBroadcast(Intent, String)
     * Context.sendOrderedBroadcast}.  This is a Bundle
     * holding arbitrary data, whose interpretation is up to the
     * broadcaster.  Can be set to null.  Calling this method completely
     * replaces the current map (if any).
     * 
     * <p><strong>This method does not work with non-ordered broadcasts such
     * as those sent with {@link Context#sendBroadcast(Intent)
     * Context.sendBroadcast}</strong></p>
     * 
     * @param extras The new extra data map; may be null.
     * 
     * @see #setResult(int, String, Bundle)
     */
    public final void setResultExtras(Bundle extras) {
        checkSynchronousHint();
        mResultExtras = extras;
    }

    /**
     * Retrieve the current result extra data, as set by the previous receiver.
     * Any changes you make to the returned Map will be propagated to the next
     * receiver.
     * 
     * @param makeMap If true then a new empty Map will be made for you if the
     *                current Map is null; if false you should be prepared to
     *                receive a null Map.
     * 
     * @return Map The current extras map.
     */
    public final Bundle getResultExtras(boolean makeMap) {
        Bundle e = mResultExtras;
        if (!makeMap) return e;
        if (e == null) mResultExtras = e = new Bundle();
        return e;
    }

    /**
     * Change all of the result data returned from this broadcasts; only works
     * with broadcasts sent through
     * {@link Context#sendOrderedBroadcast(Intent, String)
     * Context.sendOrderedBroadcast}.  All current result data is replaced
     * by the value given to this method.
     * 
     * <p><strong>This method does not work with non-ordered broadcasts such
     * as those sent with {@link Context#sendBroadcast(Intent)
     * Context.sendBroadcast}</strong></p>
     * 
     * @param code The new result code.  Often uses the
     * Activity {@link android.app.Activity#RESULT_CANCELED} and
     * {@link android.app.Activity#RESULT_OK} constants, though the
     * actual meaning of this value is ultimately up to the broadcaster.
     * @param data The new result data.  This is an arbitrary
     * string whose interpretation is up to the broadcaster; may be null.
     * @param extras The new extra data map.  This is a Bundle
     * holding arbitrary data, whose interpretation is up to the
     * broadcaster.  Can be set to null.  This completely
     * replaces the current map (if any).
     */
    public final void setResult(int code, String data, Bundle extras) {
        checkSynchronousHint();
        mResultCode = code;
        mResultData = data;
        mResultExtras = extras;
    }
 
    /**
     * Returns the flag indicating whether or not this receiver should
     * abort the current broadcast.
     * 
     * @return True if the broadcast should be aborted.
     */
    public final boolean getAbortBroadcast() {
        return mAbortBroadcast;
    }

    /**
     * Sets the flag indicating that this receiver should abort the
     * current broadcast; only works with broadcasts sent through
     * {@link Context#sendOrderedBroadcast(Intent, String)
     * Context.sendOrderedBroadcast}.  This will prevent
     * any other broadcast receivers from receiving the broadcast. It will still
     * call {@link #onReceive} of the BroadcastReceiver that the caller of 
     * {@link Context#sendOrderedBroadcast(Intent, String)
     * Context.sendOrderedBroadcast} passed in.
     * 
     * <p><strong>This method does not work with non-ordered broadcasts such
     * as those sent with {@link Context#sendBroadcast(Intent)
     * Context.sendBroadcast}</strong></p>
     */
    public final void abortBroadcast() {
        checkSynchronousHint();
        mAbortBroadcast = true;
    }
    
    /**
     * Clears the flag indicating that this receiver should abort the current
     * broadcast.
     */
    public final void clearAbortBroadcast() {
        mAbortBroadcast = false;
    }
    
    /**
     * Returns true if the receiver is currently processing an ordered
     * broadcast.
     */
    public final boolean isOrderedBroadcast() {
        return mOrderedHint;
    }
    
    /**
     * Returns true if the receiver is currently processing the initial
     * value of a sticky broadcast -- that is, the value that was last
     * broadcast and is currently held in the sticky cache, so this is
     * not directly the result of a broadcast right now.
     */
    public final boolean isInitialStickyBroadcast() {
        return mInitialStickyHint;
    }
    
    /**
     * For internal use, sets the hint about whether this BroadcastReceiver is
     * running in ordered mode.
     */
    public final void setOrderedHint(boolean isOrdered) {
        mOrderedHint = isOrdered;
    }
    
    /**
     * For internal use, sets the hint about whether this BroadcastReceiver is
     * receiving the initial sticky broadcast value. @hide
     */
    public final void setInitialStickyHint(boolean isInitialSticky) {
        mInitialStickyHint = isInitialSticky;
    }
    
    /**
     * Control inclusion of debugging help for mismatched
     * calls to {@ Context#registerReceiver(BroadcastReceiver, IntentFilter)
     * Context.registerReceiver()}.
     * If called with true, before given to registerReceiver(), then the
     * callstack of the following {@link Context#unregisterReceiver(BroadcastReceiver)
     * Context.unregisterReceiver()} call is retained, to be printed if a later
     * incorrect unregister call is made.  Note that doing this requires retaining
     * information about the BroadcastReceiver for the lifetime of the app,
     * resulting in a leak -- this should only be used for debugging.
     */
    public final void setDebugUnregister(boolean debug) {
        mDebugUnregister = debug;
    }
    
    /**
     * Return the last value given to {@link #setDebugUnregister}.
     */
    public final boolean getDebugUnregister() {
        return mDebugUnregister;
    }
    
    void checkSynchronousHint() {
        // Note that we don't assert when receiving the initial sticky value,
        // since that may have come from an ordered broadcast.  We'll catch
        // them later when the real broadcast happens again.
        if (mOrderedHint || mInitialStickyHint) {
            return;
        }
        RuntimeException e = new RuntimeException(
                "BroadcastReceiver trying to return result during a non-ordered broadcast");
        e.fillInStackTrace();
        Log.e("BroadcastReceiver", e.getMessage(), e);
    }
    
    private int mResultCode;
    private String mResultData;
    private Bundle mResultExtras;
    private boolean mAbortBroadcast;
    private boolean mDebugUnregister;
    private boolean mOrderedHint;
    private boolean mInitialStickyHint;
}

