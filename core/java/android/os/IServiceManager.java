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

package android.os;

import android.annotation.UnsupportedAppUsage;

/**
 * Basic interface for finding and publishing system services.
 *
 * An implementation of this interface is usually published as the
 * global context object, which can be retrieved via
 * BinderNative.getContextObject().  An easy way to retrieve this
 * is with the static method BnServiceManager.getDefault().
 *
 * @hide
 */
public interface IServiceManager extends IInterface
{
    /**
     * Retrieve an existing service called @a name from the
     * service manager.  Blocks for a few seconds waiting for it to be
     * published if it does not already exist.
     */
    @UnsupportedAppUsage
    IBinder getService(String name) throws RemoteException;

    /**
     * Retrieve an existing service called @a name from the
     * service manager.  Non-blocking.
     */
    @UnsupportedAppUsage
    IBinder checkService(String name) throws RemoteException;

    /**
     * Place a new @a service called @a name into the service
     * manager.
     */
    void addService(String name, IBinder service, boolean allowIsolated, int dumpFlags)
            throws RemoteException;

    /**
     * Return a list of all currently running services.
     */
    String[] listServices(int dumpFlags) throws RemoteException;

    /**
     * Assign a permission controller to the service manager.  After set, this
     * interface is checked before any services are added.
     */
    void setPermissionController(IPermissionController controller)
            throws RemoteException;

    static final String descriptor = "android.os.IServiceManager";

    int GET_SERVICE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION;
    int CHECK_SERVICE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+1;
    int ADD_SERVICE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+2;
    int LIST_SERVICES_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+3;
    int CHECK_SERVICES_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+4;
    int SET_PERMISSION_CONTROLLER_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+5;

    /*
     * Must update values in IServiceManager.h
     */
    /* Allows services to dump sections according to priorities. */
    int DUMP_FLAG_PRIORITY_CRITICAL = 1 << 0;
    int DUMP_FLAG_PRIORITY_HIGH = 1 << 1;
    int DUMP_FLAG_PRIORITY_NORMAL = 1 << 2;
    /**
     * Services are by default registered with a DEFAULT dump priority. DEFAULT priority has the
     * same priority as NORMAL priority but the services are not called with dump priority
     * arguments.
     */
    int DUMP_FLAG_PRIORITY_DEFAULT = 1 << 3;
    int DUMP_FLAG_PRIORITY_ALL = DUMP_FLAG_PRIORITY_CRITICAL | DUMP_FLAG_PRIORITY_HIGH
            | DUMP_FLAG_PRIORITY_NORMAL | DUMP_FLAG_PRIORITY_DEFAULT;
    /* Allows services to dump sections in protobuf format. */
    int DUMP_FLAG_PROTO = 1 << 4;

}
