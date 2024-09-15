/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.server.audio;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.IBinder;
import android.os.IInterface;
import android.os.IServiceCallback;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Manages a remote service which can start and stop. Allows clients to add tasks to run when the
 * remote service starts or dies.
 *
 * <p>Example usage should look something like:
 *
 * <pre>
 *      var service = mServiceHolder.checkService();
 *      if (service == null) handleFailure();
 *      try {
 *          service.foo();
 *      } catch (RemoteException e) {
 *          mServiceHolder.attemptClear(service.asBinder());
 *          handleFailure();
 *      }
 * </pre>
 */
public class ServiceHolder<I extends IInterface> implements IBinder.DeathRecipient {

    private final String mTag;
    private final String mServiceName;
    private final Function<? super IBinder, ? extends I> mCastFunction;
    private final Executor mExecutor;
    private final ServiceProviderFacade mServiceProvider;

    private final AtomicReference<I> mService = new AtomicReference();
    private final Set<Consumer<I>> mOnStartTasks = ConcurrentHashMap.newKeySet();
    private final Set<Consumer<I>> mOnDeathTasks = ConcurrentHashMap.newKeySet();

    private final IServiceCallback mServiceListener =
            new IServiceCallback.Stub() {
                @Override
                public void onRegistration(String name, IBinder binder) {
                    onServiceInited(binder);
                }
            };

    // For test purposes
    public static interface ServiceProviderFacade {
        public void registerForNotifications(String name, IServiceCallback listener);

        public IBinder checkService(String name);

        public IBinder waitForService(String name);
    }

    public ServiceHolder(
            @NonNull String serviceName,
            @NonNull Function<? super IBinder, ? extends I> castFunction,
            @NonNull Executor executor) {
        this(
                serviceName,
                castFunction,
                executor,
                new ServiceProviderFacade() {
                    @Override
                    public void registerForNotifications(String name, IServiceCallback listener) {
                        try {
                            ServiceManager.registerForNotifications(name, listener);
                        } catch (RemoteException e) {
                            throw new IllegalStateException("ServiceManager died!!", e);
                        }
                    }

                    @Override
                    public IBinder checkService(String name) {
                        return ServiceManager.checkService(name);
                    }

                    @Override
                    public IBinder waitForService(String name) {
                        return ServiceManager.waitForService(name);
                    }
                });
    }

    public ServiceHolder(
            @NonNull String serviceName,
            @NonNull Function<? super IBinder, ? extends I> castFunction,
            @NonNull Executor executor,
            @NonNull ServiceProviderFacade provider) {
        mServiceName = Objects.requireNonNull(serviceName);
        mCastFunction = Objects.requireNonNull(castFunction);
        mExecutor = Objects.requireNonNull(executor);
        mServiceProvider = Objects.requireNonNull(provider);
        mTag = "ServiceHolder: " + serviceName;
        mServiceProvider.registerForNotifications(mServiceName, mServiceListener);
    }

    /**
     * Add tasks to run when service becomes available. Ran on the executor provided at
     * construction. Note, for convenience, if the service is already connected, the task is
     * immediately run.
     */
    public void registerOnStartTask(Consumer<I> task) {
        mOnStartTasks.add(task);
        I i;
        if ((i = mService.get()) != null) {
            mExecutor.execute(() -> task.accept(i));
        }
    }

    public void unregisterOnStartTask(Consumer<I> task) {
        mOnStartTasks.remove(task);
    }

    /**
     * Add tasks to run when service goes down. Ran on the executor provided at construction. Should
     * be called before getService to avoid dropping a death notification.
     */
    public void registerOnDeathTask(Consumer<I> task) {
        mOnDeathTasks.add(task);
    }

    public void unregisterOnDeathTask(Consumer<I> task) {
        mOnDeathTasks.remove(task);
    }

    @Override
    public void binderDied(@NonNull IBinder who) {
        attemptClear(who);
    }

    @Override
    public void binderDied() {
        throw new AssertionError("Wrong binderDied called, this should never happen");
    }

    /**
     * Notify the holder that the service has gone done, usually in response to a RemoteException.
     * Equivalent to receiving a binder death notification.
     */
    public void attemptClear(IBinder who) {
        // Possibly prone to weird races, resulting in spurious dead/revive,
        // but that should be fine.
        var current = mService.get();
        if (current != null
                && Objects.equals(current.asBinder(), who)
                && mService.compareAndSet(current, null)) {
            who.unlinkToDeath(this, 0);
            for (var r : mOnDeathTasks) {
                mExecutor.execute(() -> r.accept(current));
            }
        }
    }

    /** Get the service, without blocking. Can trigger start tasks, on the provided executor. */
    public @Nullable I checkService() {
        var s = mService.get();
        if (s != null) return s;
        IBinder registered = mServiceProvider.checkService(mServiceName);
        if (registered == null) return null;
        return onServiceInited(registered);
    }

    /** Get the service, but block. Can trigger start tasks, on the provided executor. */
    public @NonNull I waitForService() {
        var s = mService.get();
        return (s != null) ? s : onServiceInited(mServiceProvider.waitForService(mServiceName));
    }

    /*
     * Called when the native service is initialized.
     */
    private @NonNull I onServiceInited(@NonNull IBinder who) {
        var service = mCastFunction.apply(who);
        Objects.requireNonNull(service);
        if (!mService.compareAndSet(null, service)) {
            return service;
        }
        // Even if the service has immediately died, we should perform these tasks for consistency
        for (var r : mOnStartTasks) {
            mExecutor.execute(() -> r.accept(service));
        }
        try {
            who.linkToDeath(this, 0);
        } catch (RemoteException e) {
            Log.e(mTag, "Immediate service death. Service crash-looping");
            attemptClear(who);
        }
        // This interface is non-null, but could represent a dead object
        return service;
    }
}
