/*
 * Copyright (c) 2016 The Android Open Source Project
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

package com.android.ims.internal.uce.uceservice;

import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;

/**
 * ImsUceManager Declaration
 * @hide
 */
public class ImsUceManager {

    private static final String LOG_TAG = "ImsUceManager";
    /**
     * Uce Service name Internal Uce only
     * @hide
     */
    private static final String UCE_SERVICE = "uce";

    /**
     * IUceService object
     * @hide
     */
    private IUceService mUceService = null;
    private UceServiceDeathRecipient mDeathReceipient = new UceServiceDeathRecipient();
    private Context mContext;
    private static final Object sLock = new Object();
    private static ImsUceManager sUceManager;

    public static final String ACTION_UCE_SERVICE_UP =
                                       "com.android.ims.internal.uce.UCE_SERVICE_UP";
    public static final String ACTION_UCE_SERVICE_DOWN =
                                        "com.android.ims.internal.uce.UCE_SERVICE_DOWN";

    /**
     * Uce Service status received in IUceListener.setStatus() callback
     */
    public static final int UCE_SERVICE_STATUS_FAILURE = 0;
    /** indicate UI to call Presence/Options API.   */
    public static final int UCE_SERVICE_STATUS_ON = 1;
    /** Indicate UI destroy Presence/Options   */
    public static final int UCE_SERVICE_STATUS_CLOSED = 2;
    /** Service up and trying to register for network events  */
    public static final int UCE_SERVICE_STATUS_READY = 3;

    /**
     * Gets the instance of UCE Manager
     * @hide
     */
    public static ImsUceManager getInstance(Context context) {
        synchronized (sLock) {
            if (sUceManager == null && context != null) {
                sUceManager =  new ImsUceManager(context);
            }
            return sUceManager;
        }
    }

    /**
     * Constructor
     * @hide
     */
    private ImsUceManager(Context context) {
        //if (DBG) Log.d (LOG_TAG, "Constructor");
        mContext = context;
        createUceService(true);
    }

    /**
     * Gets the Uce service Instance
     *
     * client should call this API only after  createUceService()
     * this instance is deleted when ACTION_UCE_SERVICE_DOWN event
     * is received.
     * @hide
     */
    public IUceService getUceServiceInstance() {
        //if (DBG) Log.d (LOG_TAG, "GetUceServiceInstance Called");
        return mUceService;
    }

    /**
     * Gets the UCE service name
     * @hide
     */
    private String getUceServiceName() {
        return UCE_SERVICE;
    }

    /**
     * Gets the IBinder to UCE service
     *
     * Client should call this after receving ACTION_UCE_SERVICE_UP
     * event.
     * @hide
     */
    public void createUceService(boolean checkService) {
        //if (DBG) Log.d (LOG_TAG, "CreateUceService Called");
        if (checkService) {
            IBinder binder = ServiceManager.checkService(getUceServiceName());

            if (binder == null) {
                //if (DBG)Log.d (LOG_TAG, "Unable to find IBinder");
                return;
            }
        }
        IBinder b = ServiceManager.getService(getUceServiceName());

        if (b != null) {
            try {
                b.linkToDeath(mDeathReceipient, 0);
            } catch (RemoteException e) {
            }
        }

        this.mUceService = IUceService.Stub.asInterface(b);
    }


    /**
     * Death recipient class for monitoring IMS service.
     *
     * After receiving ACTION_UCE_SERVICE_DOWN event, the client
     * should wait to receive ACTION_UCE_SERVICE_UP and call
     * createUceService inorder to create mUceService instance.
     * @hide
     */
    private class UceServiceDeathRecipient implements IBinder.DeathRecipient {
        @Override
        public void binderDied() {
            mUceService = null;

            if (mContext != null) {
                Intent intent = new Intent(ACTION_UCE_SERVICE_DOWN);
                mContext.sendBroadcast(new Intent(intent));
            }
        }
    }
}
