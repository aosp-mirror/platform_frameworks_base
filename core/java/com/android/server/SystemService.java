/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server;

import android.content.Context;
import android.os.IBinder;
import android.os.ServiceManager;

/**
 * The base class for services running in the system process. Override and implement
 * the lifecycle event callback methods as needed.
 * <p>
 * The lifecycle of a SystemService:
 * </p><ul>
 * <li>The constructor is called and provided with the system {@link Context}
 * to initialize the system service.
 * <li>{@link #onStart()} is called to get the service running.  The service should
 * publish its binder interface at this point using
 * {@link #publishBinderService(String, IBinder)}.  It may also publish additional
 * local interfaces that other services within the system server may use to access
 * privileged internal functions.
 * <li>Then {@link #onBootPhase(int)} is called as many times as there are boot phases
 * until {@link #PHASE_BOOT_COMPLETE} is sent, which is the last boot phase. Each phase
 * is an opportunity to do special work, like acquiring optional service dependencies,
 * waiting to see if SafeMode is enabled, or registering with a service that gets
 * started after this one.
 * </ul><p>
 * NOTE: All lifecycle methods are called from the system server's main looper thread.
 * </p>
 *
 * {@hide}
 */
public abstract class SystemService {
    /*
     * Boot Phases
     */
    public static final int PHASE_WAIT_FOR_DEFAULT_DISPLAY = 100; // maybe should be a dependency?

    /**
     * After receiving this boot phase, services can obtain lock settings data.
     */
    public static final int PHASE_LOCK_SETTINGS_READY = 480;

    /**
     * After receiving this boot phase, services can safely call into core system services
     * such as the PowerManager or PackageManager.
     */
    public static final int PHASE_SYSTEM_SERVICES_READY = 500;

    /**
     * After receiving this boot phase, services can broadcast Intents.
     */
    public static final int PHASE_ACTIVITY_MANAGER_READY = 550;

    /**
     * After receiving this boot phase, services can start/bind to third party apps.
     * Apps will be able to make Binder calls into services at this point.
     */
    public static final int PHASE_THIRD_PARTY_APPS_CAN_START = 600;

    /**
     * After receiving this boot phase, services must have finished all boot-related work.
     */
    public static final int PHASE_BOOT_COMPLETE = 1000;

    private final Context mContext;

    /**
     * Initializes the system service.
     * <p>
     * Subclasses must define a single argument constructor that accepts the context
     * and passes it to super.
     * </p>
     *
     * @param context The system server context.
     */
    public SystemService(Context context) {
        mContext = context;
    }

    /**
     * Gets the system context.
     */
    public final Context getContext() {
        return mContext;
    }

    /**
     * Returns true if the system is running in safe mode.
     * TODO: we should define in which phase this becomes valid
     */
    public final boolean isSafeMode() {
        return getManager().isSafeMode();
    }

    /**
     * Called when the dependencies listed in the @Service class-annotation are available
     * and after the chosen start phase.
     * When this method returns, the service should be published.
     */
    public abstract void onStart();

    /**
     * Called on each phase of the boot process. Phases before the service's start phase
     * (as defined in the @Service annotation) are never received.
     *
     * @param phase The current boot phase.
     */
    public void onBootPhase(int phase) {}

    /**
     * Publish the service so it is accessible to other services and apps.
     */
    protected final void publishBinderService(String name, IBinder service) {
        publishBinderService(name, service, false);
    }

    /**
     * Publish the service so it is accessible to other services and apps.
     */
    protected final void publishBinderService(String name, IBinder service,
            boolean allowIsolated) {
        ServiceManager.addService(name, service, allowIsolated);
    }

    /**
     * Get a binder service by its name.
     */
    protected final IBinder getBinderService(String name) {
        return ServiceManager.getService(name);
    }

    /**
     * Publish the service so it is only accessible to the system process.
     */
    protected final <T> void publishLocalService(Class<T> type, T service) {
        LocalServices.addService(type, service);
    }

    /**
     * Get a local service by interface.
     */
    protected final <T> T getLocalService(Class<T> type) {
        return LocalServices.getService(type);
    }

    private SystemServiceManager getManager() {
        return LocalServices.getService(SystemServiceManager.class);
    }

//    /**
//     * Called when a new user has been created. If your service deals with multiple users, this
//     * method should be overridden.
//     *
//     * @param userHandle The user that was created.
//     */
//    public void onUserCreated(int userHandle) {
//    }
//
//    /**
//     * Called when an existing user has started a new session. If your service deals with multiple
//     * users, this method should be overridden.
//     *
//     * @param userHandle The user who started a new session.
//     */
//    public void onUserStarted(int userHandle) {
//    }
//
//    /**
//     * Called when a background user session has entered the foreground. If your service deals with
//     * multiple users, this method should be overridden.
//     *
//     * @param userHandle The user who's session entered the foreground.
//     */
//    public void onUserForeground(int userHandle) {
//    }
//
//    /**
//     * Called when a foreground user session has entered the background. If your service deals with
//     * multiple users, this method should be overridden;
//     *
//     * @param userHandle The user who's session entered the background.
//     */
//    public void onUserBackground(int userHandle) {
//    }
//
//    /**
//     * Called when a user's active session has stopped. If your service deals with multiple users,
//     * this method should be overridden.
//     *
//     * @param userHandle The user who's session has stopped.
//     */
//    public void onUserStopped(int userHandle) {
//    }
//
//    /**
//     * Called when a user has been removed from the system. If your service deals with multiple
//     * users, this method should be overridden.
//     *
//     * @param userHandle The user who has been removed.
//     */
//    public void onUserRemoved(int userHandle) {
//    }
}
