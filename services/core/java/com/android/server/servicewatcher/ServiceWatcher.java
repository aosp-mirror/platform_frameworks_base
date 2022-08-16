/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.servicewatcher;

import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;

import com.android.server.FgThread;

import java.io.PrintWriter;
import java.util.Objects;

/**
 * A ServiceWatcher is responsible for continuously maintaining an active binding to a service
 * selected by it's {@link ServiceSupplier}. The {@link ServiceSupplier} may change the service it
 * selects over time, and the currently bound service may crash, restart, have a user change, have
 * changes made to its package, and so on and so forth. The ServiceWatcher is responsible for
 * maintaining the binding across all these changes.
 *
 * <p>Clients may invoke {@link BinderOperation}s on the ServiceWatcher, and it will make a best
 * effort to run these on the currently bound service, but individual operations may fail (if there
 * is no service currently bound for instance). In order to help clients maintain the correct state,
 * clients may supply a {@link ServiceListener}, which is informed when the ServiceWatcher connects
 * and disconnects from a service. This allows clients to bring a bound service back into a known
 * state on connection, and then run binder operations from there. In order to help clients
 * accomplish this, ServiceWatcher guarantees that {@link BinderOperation}s and the
 * {@link ServiceListener} will always be run on the same thread, so that strong ordering guarantees
 * can be established between them.
 *
 * There is never any guarantee of whether a ServiceWatcher is currently connected to a service, and
 * whether any particular {@link BinderOperation} will succeed. Clients must ensure they do not rely
 * on this, and instead use {@link ServiceListener} notifications as necessary to recover from
 * failures.
 */
public interface ServiceWatcher {

    /**
     * Operation to run on a binder interface. All operations will be run on the thread used by the
     * ServiceWatcher this is run with.
     */
    interface BinderOperation {
        /** Invoked to run the operation. Run on the ServiceWatcher thread. */
        void run(IBinder binder) throws RemoteException;

        /**
         * Invoked if {@link #run(IBinder)} could not be invoked because there was no current
         * binding, or if {@link #run(IBinder)} threw an exception ({@link RemoteException} or
         * {@link RuntimeException}). This callback is only intended for resource deallocation and
         * cleanup in response to a single binder operation, it should not be used to propagate
         * errors further. Run on the ServiceWatcher thread.
         */
        default void onError(Throwable t) {}
    }

    /**
     * Listener for bind and unbind events. All operations will be run on the thread used by the
     * ServiceWatcher this is run with.
     *
     * @param <TBoundServiceInfo> type of bound service
     */
    interface ServiceListener<TBoundServiceInfo extends BoundServiceInfo> {
        /** Invoked when a service is bound. Run on the ServiceWatcher thread. */
        void onBind(IBinder binder, TBoundServiceInfo service) throws RemoteException;

        /** Invoked when a service is unbound. Run on the ServiceWatcher thread. */
        void onUnbind();
    }

    /**
     * A listener for when a {@link ServiceSupplier} decides that the current service has changed.
     */
    interface ServiceChangedListener {
        /**
         * Should be invoked when the current service may have changed.
         */
        void onServiceChanged();
    }

    /**
     * This supplier encapsulates the logic of deciding what service a {@link ServiceWatcher} should
     * be bound to at any given moment.
     *
     * @param <TBoundServiceInfo> type of bound service
     */
    interface ServiceSupplier<TBoundServiceInfo extends BoundServiceInfo> {
        /**
         * Should return true if there exists at least one service capable of meeting the criteria
         * of this supplier. This does not imply that {@link #getServiceInfo()} will always return a
         * non-null result, as any service may be disqualified for various reasons at any point in
         * time. May be invoked at any time from any thread and thus should generally not have any
         * dependency on the other methods in this interface.
         */
        boolean hasMatchingService();

        /**
         * Invoked when the supplier should start monitoring for any changes that could result in a
         * different service selection, and should invoke
         * {@link ServiceChangedListener#onServiceChanged()} in that case. {@link #getServiceInfo()}
         * may be invoked after this method is called.
         */
        void register(ServiceChangedListener listener);

        /**
         * Invoked when the supplier should stop monitoring for any changes that could result in a
         * different service selection, should no longer invoke
         * {@link ServiceChangedListener#onServiceChanged()}. {@link #getServiceInfo()} will not be
         * invoked after this method is called.
         */
        void unregister();

        /**
         * Must be implemented to return the current service selected by this supplier. May return
         * null if no service currently meets the criteria. Only invoked while registered.
         */
        @Nullable TBoundServiceInfo getServiceInfo();
    }

    /**
     * Information on the service selected as the best option for binding.
     */
    class BoundServiceInfo {

        protected final @Nullable String mAction;
        protected final int mUid;
        protected final ComponentName mComponentName;

        protected BoundServiceInfo(String action, ResolveInfo resolveInfo) {
            this(action, resolveInfo.serviceInfo.applicationInfo.uid,
                    resolveInfo.serviceInfo.getComponentName());
        }

        protected BoundServiceInfo(String action, int uid, ComponentName componentName) {
            mAction = action;
            mUid = uid;
            mComponentName = Objects.requireNonNull(componentName);
        }

        /** Returns the action associated with this bound service. */
        public @Nullable String getAction() {
            return mAction;
        }

        /** Returns the component of this bound service. */
        public ComponentName getComponentName() {
            return mComponentName;
        }

        /** Returns the user id for this bound service. */
        public @UserIdInt int getUserId() {
            return UserHandle.getUserId(mUid);
        }

        @Override
        public final boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof BoundServiceInfo)) {
                return false;
            }

            BoundServiceInfo that = (BoundServiceInfo) o;
            return mUid == that.mUid
                    && Objects.equals(mAction, that.mAction)
                    && mComponentName.equals(that.mComponentName);
        }

        @Override
        public final int hashCode() {
            return Objects.hash(mAction, mUid, mComponentName);
        }

        @Override
        public String toString() {
            if (mComponentName == null) {
                return "none";
            } else {
                return mUid + "/" + mComponentName.flattenToShortString();
            }
        }
    }

    /**
     * Creates a new ServiceWatcher instance.
     */
    static <TBoundServiceInfo extends BoundServiceInfo> ServiceWatcher create(
            Context context,
            String tag,
            ServiceSupplier<TBoundServiceInfo> serviceSupplier,
            @Nullable ServiceListener<? super TBoundServiceInfo> serviceListener) {
        return create(context, FgThread.getHandler(), tag, serviceSupplier, serviceListener);
    }

    /**
     * Creates a new ServiceWatcher instance that runs on the given handler.
     */
    static <TBoundServiceInfo extends BoundServiceInfo> ServiceWatcher create(
            Context context,
            Handler handler,
            String tag,
            ServiceSupplier<TBoundServiceInfo> serviceSupplier,
            @Nullable ServiceListener<? super TBoundServiceInfo> serviceListener) {
        return new ServiceWatcherImpl<>(context, handler, tag, serviceSupplier, serviceListener);
    }

    /**
     * Returns true if there is at least one service that the ServiceWatcher could hypothetically
     * bind to, as selected by the {@link ServiceSupplier}.
     */
    boolean checkServiceResolves();

    /**
     * Registers the ServiceWatcher, so that it will begin maintaining an active binding to the
     * service selected by {@link ServiceSupplier}, until {@link #unregister()} is called.
     */
    void register();

    /**
     * Unregisters the ServiceWatcher, so that it will release any active bindings. If the
     * ServiceWatcher is currently bound, this will result in one final
     * {@link ServiceListener#onUnbind()} invocation, which may happen after this method completes
     * (but which is guaranteed to occur before any further
     * {@link ServiceListener#onBind(IBinder, BoundServiceInfo)} invocation in response to a later
     * call to {@link #register()}).
     */
    void unregister();

    /**
     * Runs the given binder operation on the currently bound service (if available). The operation
     * will always fail if the ServiceWatcher is not currently registered.
     */
    void runOnBinder(BinderOperation operation);

    /**
     * Dumps ServiceWatcher information.
     */
    void dump(PrintWriter pw);
}