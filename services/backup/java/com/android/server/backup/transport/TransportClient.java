/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.backup.transport;

import static com.android.server.backup.transport.TransportUtils.formatMessage;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.annotation.WorkerThread;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.format.DateFormat;
import android.util.ArrayMap;
import android.util.EventLog;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.backup.IBackupTransport;
import com.android.internal.util.Preconditions;
import com.android.server.EventLogTags;
import com.android.server.backup.TransportManager;
import com.android.server.backup.transport.TransportUtils.Priority;

import dalvik.system.CloseGuard;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * A {@link TransportClient} manages the connection to an {@link IBackupTransport} service, obtained
 * via the {@param bindIntent} parameter provided in the constructor. A {@link TransportClient} is
 * responsible for only one connection to the transport service, not more.
 *
 * <p>After retrieved using {@link TransportManager#getTransportClient(String, String)}, you can
 * call either {@link #connect(String)}, if you can block your thread, or {@link
 * #connectAsync(TransportConnectionListener, String)}, otherwise, to obtain a {@link
 * IBackupTransport} instance. It's meant to be passed around as a token to a connected transport.
 * When the connection is not needed anymore you should call {@link #unbind(String)} or indirectly
 * via {@link TransportManager#disposeOfTransportClient(TransportClient, String)}.
 *
 * <p>DO NOT forget to unbind otherwise there will be dangling connections floating around.
 *
 * <p>This class is thread-safe.
 *
 * @see TransportManager
 */
public class TransportClient {
    @VisibleForTesting static final String TAG = "TransportClient";
    private static final int LOG_BUFFER_SIZE = 5;

    private final Context mContext;
    private final TransportStats mTransportStats;
    private final Intent mBindIntent;
    private final ServiceConnection mConnection;
    private final String mIdentifier;
    private final String mCreatorLogString;
    private final ComponentName mTransportComponent;
    private final Handler mListenerHandler;
    private final String mPrefixForLog;
    private final Object mStateLock = new Object();
    private final Object mLogBufferLock = new Object();
    private final CloseGuard mCloseGuard = CloseGuard.get();

    @GuardedBy("mLogBufferLock")
    private final List<String> mLogBuffer = new LinkedList<>();

    @GuardedBy("mStateLock")
    private final Map<TransportConnectionListener, String> mListeners = new ArrayMap<>();

    @GuardedBy("mStateLock")
    @State
    private int mState = State.IDLE;

    @GuardedBy("mStateLock")
    private volatile IBackupTransport mTransport;

    TransportClient(
            Context context,
            TransportStats transportStats,
            Intent bindIntent,
            ComponentName transportComponent,
            String identifier,
            String caller) {
        this(
                context,
                transportStats,
                bindIntent,
                transportComponent,
                identifier,
                caller,
                new Handler(Looper.getMainLooper()));
    }

    @VisibleForTesting
    TransportClient(
            Context context,
            TransportStats transportStats,
            Intent bindIntent,
            ComponentName transportComponent,
            String identifier,
            String caller,
            Handler listenerHandler) {
        mContext = context;
        mTransportStats = transportStats;
        mTransportComponent = transportComponent;
        mBindIntent = bindIntent;
        mIdentifier = identifier;
        mCreatorLogString = caller;
        mListenerHandler = listenerHandler;
        mConnection = new TransportConnection(context, this);

        // For logging
        String classNameForLog = mTransportComponent.getShortClassName().replaceFirst(".*\\.", "");
        mPrefixForLog = classNameForLog + "#" + mIdentifier + ":";

        mCloseGuard.open("markAsDisposed");
    }

    public ComponentName getTransportComponent() {
        return mTransportComponent;
    }

    /**
     * Attempts to connect to the transport (if needed).
     *
     * <p>Note that being bound is not the same as connected. To be connected you also need to be
     * bound. You go from nothing to bound, then to bound and connected. To have a usable transport
     * binder instance you need to be connected. This method will attempt to connect and return an
     * usable transport binder regardless of the state of the object, it may already be connected,
     * or bound but not connected, not bound at all or even unusable.
     *
     * <p>So, a {@link Context#bindServiceAsUser(Intent, ServiceConnection, int, UserHandle)} (or
     * one of its variants) can be called or not depending on the inner state. However, it won't be
     * called again if we're already bound. For example, if one was already requested but the
     * framework has not yet returned (meaning we're bound but still trying to connect) it won't
     * trigger another one, just piggyback on the original request.
     *
     * <p>It's guaranteed that you are going to get a call back to {@param listener} after this
     * call. However, the {@param IBackupTransport} parameter, the transport binder, is not
     * guaranteed to be non-null, or if it's non-null it's not guaranteed to be usable - i.e. it can
     * throw {@link DeadObjectException}s on method calls. You should check for both in your code.
     * The reasons for a null transport binder are:
     *
     * <ul>
     *   <li>Some code called {@link #unbind(String)} before you got a callback.
     *   <li>The framework had already called {@link
     *       ServiceConnection#onServiceDisconnected(ComponentName)} or {@link
     *       ServiceConnection#onBindingDied(ComponentName)} on this object's connection before.
     *       Check the documentation of those methods for when that happens.
     *   <li>The framework returns false for {@link Context#bindServiceAsUser(Intent,
     *       ServiceConnection, int, UserHandle)} (or one of its variants). Check documentation for
     *       when this happens.
     * </ul>
     *
     * For unusable transport binders check {@link DeadObjectException}.
     *
     * @param listener The listener that will be called with the (possibly null or unusable) {@link
     *     IBackupTransport} instance and this {@link TransportClient} object.
     * @param caller A {@link String} identifying the caller for logging/debugging purposes. This
     *     should be a human-readable short string that is easily identifiable in the logs. Ideally
     *     TAG.methodName(), where TAG is the one used in logcat. In cases where this is is not very
     *     descriptive like MyHandler.handleMessage() you should put something that someone reading
     *     the code would understand, like MyHandler/MSG_FOO.
     * @see #connect(String)
     * @see DeadObjectException
     * @see ServiceConnection#onServiceConnected(ComponentName, IBinder)
     * @see ServiceConnection#onServiceDisconnected(ComponentName)
     * @see Context#bindServiceAsUser(Intent, ServiceConnection, int, UserHandle)
     */
    public void connectAsync(TransportConnectionListener listener, String caller) {
        synchronized (mStateLock) {
            checkStateIntegrityLocked();

            switch (mState) {
                case State.UNUSABLE:
                    log(Priority.WARN, caller, "Async connect: UNUSABLE client");
                    notifyListener(listener, null, caller);
                    break;
                case State.IDLE:
                    boolean hasBound =
                            mContext.bindServiceAsUser(
                                    mBindIntent,
                                    mConnection,
                                    Context.BIND_AUTO_CREATE,
                                    UserHandle.SYSTEM);
                    if (hasBound) {
                        // We don't need to set a time-out because we are guaranteed to get a call
                        // back in ServiceConnection, either an onServiceConnected() or
                        // onBindingDied().
                        log(Priority.DEBUG, caller, "Async connect: service bound, connecting");
                        setStateLocked(State.BOUND_AND_CONNECTING, null);
                        mListeners.put(listener, caller);
                    } else {
                        log(Priority.ERROR, "Async connect: bindService returned false");
                        // mState remains State.IDLE
                        mContext.unbindService(mConnection);
                        notifyListener(listener, null, caller);
                    }
                    break;
                case State.BOUND_AND_CONNECTING:
                    log(
                            Priority.DEBUG,
                            caller,
                            "Async connect: already connecting, adding listener");
                    mListeners.put(listener, caller);
                    break;
                case State.CONNECTED:
                    log(Priority.DEBUG, caller, "Async connect: reusing transport");
                    notifyListener(listener, mTransport, caller);
                    break;
            }
        }
    }

    /**
     * Removes the transport binding.
     *
     * @param caller A {@link String} identifying the caller for logging/debugging purposes. Check
     *     {@link #connectAsync(TransportConnectionListener, String)} for more details.
     */
    public void unbind(String caller) {
        synchronized (mStateLock) {
            checkStateIntegrityLocked();

            log(Priority.DEBUG, caller, "Unbind requested (was " + stateToString(mState) + ")");
            switch (mState) {
                case State.UNUSABLE:
                case State.IDLE:
                    break;
                case State.BOUND_AND_CONNECTING:
                    setStateLocked(State.IDLE, null);
                    // After unbindService() no calls back to mConnection
                    mContext.unbindService(mConnection);
                    notifyListenersAndClearLocked(null);
                    break;
                case State.CONNECTED:
                    setStateLocked(State.IDLE, null);
                    mContext.unbindService(mConnection);
                    break;
            }
        }
    }

    /** Marks this TransportClient as disposed, allowing it to be GC'ed without warnings. */
    public void markAsDisposed() {
        synchronized (mStateLock) {
            Preconditions.checkState(
                    mState < State.BOUND_AND_CONNECTING, "Can't mark as disposed if still bound");
            mCloseGuard.close();
        }
    }

    /**
     * Attempts to connect to the transport (if needed) and returns it.
     *
     * <p>Synchronous version of {@link #connectAsync(TransportConnectionListener, String)}. The
     * same observations about state are valid here. Also, what was said about the {@link
     * IBackupTransport} parameter of {@link TransportConnectionListener} now apply to the return
     * value of this method.
     *
     * <p>This is a potentially blocking operation, so be sure to call this carefully on the correct
     * threads. You can't call this from the process main-thread (it throws an exception if you do
     * so).
     *
     * <p>In most cases only the first call to this method will block, the following calls should
     * return instantly. However, this is not guaranteed.
     *
     * @param caller A {@link String} identifying the caller for logging/debugging purposes. Check
     *     {@link #connectAsync(TransportConnectionListener, String)} for more details.
     * @return A {@link IBackupTransport} transport binder instance or null. If it's non-null it can
     *     still be unusable - throws {@link DeadObjectException} on method calls
     */
    @WorkerThread
    @Nullable
    public IBackupTransport connect(String caller) {
        // If called on the main-thread this could deadlock waiting because calls to
        // ServiceConnection are on the main-thread as well
        Preconditions.checkState(
                !Looper.getMainLooper().isCurrentThread(), "Can't call connect() on main thread");

        IBackupTransport transport = mTransport;
        if (transport != null) {
            log(Priority.DEBUG, caller, "Sync connect: reusing transport");
            return transport;
        }

        // If it's already UNUSABLE we return straight away, no need to go to main-thread
        synchronized (mStateLock) {
            if (mState == State.UNUSABLE) {
                log(Priority.WARN, caller, "Sync connect: UNUSABLE client");
                return null;
            }
        }

        CompletableFuture<IBackupTransport> transportFuture = new CompletableFuture<>();
        TransportConnectionListener requestListener =
                (requestedTransport, transportClient) ->
                        transportFuture.complete(requestedTransport);

        long requestTime = SystemClock.elapsedRealtime();
        log(Priority.DEBUG, caller, "Sync connect: calling async");
        connectAsync(requestListener, caller);

        try {
            transport = transportFuture.get();
            long time = SystemClock.elapsedRealtime() - requestTime;
            mTransportStats.registerConnectionTime(mTransportComponent, time);
            log(Priority.DEBUG, caller, String.format(Locale.US, "Connect took %d ms", time));
            return transport;
        } catch (InterruptedException | ExecutionException e) {
            String error = e.getClass().getSimpleName();
            log(Priority.ERROR, caller, error + " while waiting for transport: " + e.getMessage());
            return null;
        }
    }

    /**
     * Tries to connect to the transport, if it fails throws {@link TransportNotAvailableException}.
     *
     * <p>Same as {@link #connect(String)} except it throws instead of returning null.
     *
     * @param caller A {@link String} identifying the caller for logging/debugging purposes. Check
     *     {@link #connectAsync(TransportConnectionListener, String)} for more details.
     * @return A {@link IBackupTransport} transport binder instance.
     * @see #connect(String)
     * @throws TransportNotAvailableException if connection attempt fails.
     */
    @WorkerThread
    public IBackupTransport connectOrThrow(String caller) throws TransportNotAvailableException {
        IBackupTransport transport = connect(caller);
        if (transport == null) {
            log(Priority.ERROR, caller, "Transport connection failed");
            throw new TransportNotAvailableException();
        }
        return transport;
    }

    /**
     * If the {@link TransportClient} is already connected to the transport, returns the transport,
     * otherwise throws {@link TransportNotAvailableException}.
     *
     * @param caller A {@link String} identifying the caller for logging/debugging purposes. Check
     *     {@link #connectAsync(TransportConnectionListener, String)} for more details.
     * @return A {@link IBackupTransport} transport binder instance.
     * @throws TransportNotAvailableException if not connected.
     */
    public IBackupTransport getConnectedTransport(String caller)
            throws TransportNotAvailableException {
        IBackupTransport transport = mTransport;
        if (transport == null) {
            log(Priority.ERROR, caller, "Transport not connected");
            throw new TransportNotAvailableException();
        }
        return transport;
    }

    @Override
    public String toString() {
        return "TransportClient{"
                + mTransportComponent.flattenToShortString()
                + "#"
                + mIdentifier
                + "}";
    }

    @Override
    protected void finalize() throws Throwable {
        synchronized (mStateLock) {
            mCloseGuard.warnIfOpen();
            if (mState >= State.BOUND_AND_CONNECTING) {
                String callerLogString = "TransportClient.finalize()";
                log(
                        Priority.ERROR,
                        callerLogString,
                        "Dangling TransportClient created in [" + mCreatorLogString + "] being "
                                + "GC'ed. Left bound, unbinding...");
                try {
                    unbind(callerLogString);
                } catch (IllegalStateException e) {
                    // May throw because there may be a race between this method being called and
                    // the framework calling any method on the connection with the weak reference
                    // there already cleared. In this case the connection will unbind before this
                    // is called. This is fine.
                }
            }
        }
    }

    private void onServiceConnected(IBinder binder) {
        IBackupTransport transport = IBackupTransport.Stub.asInterface(binder);
        synchronized (mStateLock) {
            checkStateIntegrityLocked();

            if (mState != State.UNUSABLE) {
                log(Priority.DEBUG, "Transport connected");
                setStateLocked(State.CONNECTED, transport);
                notifyListenersAndClearLocked(transport);
            }
        }
    }

    /**
     * If we are called here the TransportClient becomes UNUSABLE. After one of these calls, if a
     * binding happen again the new service can be a different instance. Since transports are
     * stateful, we don't want a new instance responding for an old instance's state.
     */
    private void onServiceDisconnected() {
        synchronized (mStateLock) {
            log(Priority.ERROR, "Service disconnected: client UNUSABLE");
            setStateLocked(State.UNUSABLE, null);
            try {
                // After unbindService() no calls back to mConnection
                mContext.unbindService(mConnection);
            } catch (IllegalArgumentException e) {
                // TODO: Investigate why this is happening
                // We're UNUSABLE, so any calls to mConnection will be no-op, so it's safe to
                // swallow this one
                log(
                        Priority.WARN,
                        "Exception trying to unbind onServiceDisconnected(): " + e.getMessage());
            }
        }
    }

    /**
     * If we are called here the TransportClient becomes UNUSABLE for the same reason as in {@link
     * #onServiceDisconnected()}.
     */
    private void onBindingDied() {
        synchronized (mStateLock) {
            checkStateIntegrityLocked();

            log(Priority.ERROR, "Binding died: client UNUSABLE");
            // After unbindService() no calls back to mConnection
            switch (mState) {
                case State.UNUSABLE:
                    break;
                case State.IDLE:
                    log(Priority.ERROR, "Unexpected state transition IDLE => UNUSABLE");
                    setStateLocked(State.UNUSABLE, null);
                    break;
                case State.BOUND_AND_CONNECTING:
                    setStateLocked(State.UNUSABLE, null);
                    mContext.unbindService(mConnection);
                    notifyListenersAndClearLocked(null);
                    break;
                case State.CONNECTED:
                    setStateLocked(State.UNUSABLE, null);
                    mContext.unbindService(mConnection);
                    break;
            }
        }
    }

    private void notifyListener(
            TransportConnectionListener listener,
            @Nullable IBackupTransport transport,
            String caller) {
        String transportString = (transport != null) ? "IBackupTransport" : "null";
        log(Priority.INFO, "Notifying [" + caller + "] transport = " + transportString);
        mListenerHandler.post(() -> listener.onTransportConnectionResult(transport, this));
    }

    @GuardedBy("mStateLock")
    private void notifyListenersAndClearLocked(@Nullable IBackupTransport transport) {
        for (Map.Entry<TransportConnectionListener, String> entry : mListeners.entrySet()) {
            TransportConnectionListener listener = entry.getKey();
            String caller = entry.getValue();
            notifyListener(listener, transport, caller);
        }
        mListeners.clear();
    }

    @GuardedBy("mStateLock")
    private void setStateLocked(@State int state, @Nullable IBackupTransport transport) {
        log(Priority.VERBOSE, "State: " + stateToString(mState) + " => " + stateToString(state));
        onStateTransition(mState, state);
        mState = state;
        mTransport = transport;
    }

    private void onStateTransition(int oldState, int newState) {
        String transport = mTransportComponent.flattenToShortString();
        int bound = transitionThroughState(oldState, newState, State.BOUND_AND_CONNECTING);
        int connected = transitionThroughState(oldState, newState, State.CONNECTED);
        if (bound != Transition.NO_TRANSITION) {
            int value = (bound == Transition.UP) ? 1 : 0; // 1 is bound, 0 is not bound
            EventLog.writeEvent(EventLogTags.BACKUP_TRANSPORT_LIFECYCLE, transport, value);
        }
        if (connected != Transition.NO_TRANSITION) {
            int value = (connected == Transition.UP) ? 1 : 0; // 1 is connected, 0 is not connected
            EventLog.writeEvent(EventLogTags.BACKUP_TRANSPORT_CONNECTION, transport, value);
        }
    }

    /**
     * Returns:
     *
     * <ul>
     *   <li>{@link Transition#UP}, if oldState < stateReference <= newState
     *   <li>{@link Transition#DOWN}, if oldState >= stateReference > newState
     *   <li>{@link Transition#NO_TRANSITION}, otherwise
     */
    @Transition
    private int transitionThroughState(
            @State int oldState, @State int newState, @State int stateReference) {
        if (oldState < stateReference && stateReference <= newState) {
            return Transition.UP;
        }
        if (oldState >= stateReference && stateReference > newState) {
            return Transition.DOWN;
        }
        return Transition.NO_TRANSITION;
    }

    @GuardedBy("mStateLock")
    private void checkStateIntegrityLocked() {
        switch (mState) {
            case State.UNUSABLE:
                checkState(mListeners.isEmpty(), "Unexpected listeners when state = UNUSABLE");
                checkState(
                        mTransport == null, "Transport expected to be null when state = UNUSABLE");
            case State.IDLE:
                checkState(mListeners.isEmpty(), "Unexpected listeners when state = IDLE");
                checkState(mTransport == null, "Transport expected to be null when state = IDLE");
                break;
            case State.BOUND_AND_CONNECTING:
                checkState(
                        mTransport == null,
                        "Transport expected to be null when state = BOUND_AND_CONNECTING");
                break;
            case State.CONNECTED:
                checkState(mListeners.isEmpty(), "Unexpected listeners when state = CONNECTED");
                checkState(
                        mTransport != null,
                        "Transport expected to be non-null when state = CONNECTED");
                break;
            default:
                checkState(false, "Unexpected state = " + stateToString(mState));
        }
    }

    private void checkState(boolean assertion, String message) {
        if (!assertion) {
            log(Priority.ERROR, message);
        }
    }

    private String stateToString(@State int state) {
        switch (state) {
            case State.UNUSABLE:
                return "UNUSABLE";
            case State.IDLE:
                return "IDLE";
            case State.BOUND_AND_CONNECTING:
                return "BOUND_AND_CONNECTING";
            case State.CONNECTED:
                return "CONNECTED";
            default:
                return "<UNKNOWN = " + state + ">";
        }
    }

    private void log(int priority, String message) {
        TransportUtils.log(priority, TAG, formatMessage(mPrefixForLog, null, message));
        saveLogEntry(formatMessage(null, null, message));
    }

    private void log(int priority, String caller, String message) {
        TransportUtils.log(priority, TAG, formatMessage(mPrefixForLog, caller, message));
        saveLogEntry(formatMessage(null, caller, message));
    }

    private void saveLogEntry(String message) {
        CharSequence time = DateFormat.format("yyyy-MM-dd HH:mm:ss", System.currentTimeMillis());
        message = time + " " + message;
        synchronized (mLogBufferLock) {
            if (mLogBuffer.size() == LOG_BUFFER_SIZE) {
                mLogBuffer.remove(mLogBuffer.size() - 1);
            }
            mLogBuffer.add(0, message);
        }
    }

    List<String> getLogBuffer() {
        synchronized (mLogBufferLock) {
            return Collections.unmodifiableList(mLogBuffer);
        }
    }

    @IntDef({Transition.DOWN, Transition.NO_TRANSITION, Transition.UP})
    @Retention(RetentionPolicy.SOURCE)
    private @interface Transition {
        int DOWN = -1;
        int NO_TRANSITION = 0;
        int UP = 1;
    }

    @IntDef({State.UNUSABLE, State.IDLE, State.BOUND_AND_CONNECTING, State.CONNECTED})
    @Retention(RetentionPolicy.SOURCE)
    private @interface State {
        // Constant values MUST be in order
        int UNUSABLE = 0;
        int IDLE = 1;
        int BOUND_AND_CONNECTING = 2;
        int CONNECTED = 3;
    }

    /**
     * This class is a proxy to TransportClient methods that doesn't hold a strong reference to the
     * TransportClient, allowing it to be GC'ed. If the reference was lost it logs a message.
     */
    private static class TransportConnection implements ServiceConnection {
        private final Context mContext;
        private final WeakReference<TransportClient> mTransportClientRef;

        private TransportConnection(Context context, TransportClient transportClient) {
            mContext = context;
            mTransportClientRef = new WeakReference<>(transportClient);
        }

        @Override
        public void onServiceConnected(ComponentName transportComponent, IBinder binder) {
            TransportClient transportClient = mTransportClientRef.get();
            if (transportClient == null) {
                referenceLost("TransportConnection.onServiceConnected()");
                return;
            }
            transportClient.onServiceConnected(binder);
        }

        @Override
        public void onServiceDisconnected(ComponentName transportComponent) {
            TransportClient transportClient = mTransportClientRef.get();
            if (transportClient == null) {
                referenceLost("TransportConnection.onServiceDisconnected()");
                return;
            }
            transportClient.onServiceDisconnected();
        }

        @Override
        public void onBindingDied(ComponentName transportComponent) {
            TransportClient transportClient = mTransportClientRef.get();
            if (transportClient == null) {
                referenceLost("TransportConnection.onBindingDied()");
                return;
            }
            transportClient.onBindingDied();
        }

        /** @see TransportClient#finalize() */
        private void referenceLost(String caller) {
            mContext.unbindService(this);
            TransportUtils.log(
                    Priority.INFO,
                    TAG,
                    caller + " called but TransportClient reference has been GC'ed");
        }
    }
}
