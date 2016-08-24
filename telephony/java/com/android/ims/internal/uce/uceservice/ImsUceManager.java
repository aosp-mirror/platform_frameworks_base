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

import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.ServiceManager;
import android.os.RemoteException;

import java.util.HashMap;
import android.util.Log;

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
    private int mPhoneId;
    /**
     * Stores the UceManager instaces of Clients identified by
     * phoneId
     * @hide
     */
    private static HashMap<Integer, ImsUceManager> sUceManagerInstances =
                                                   new HashMap<Integer, ImsUceManager>();

    public static final String ACTION_UCE_SERVICE_UP =
                                       "com.android.ims.internal.uce.UCE_SERVICE_UP";
    public static final String ACTION_UCE_SERVICE_DOWN =
                                        "com.android.ims.internal.uce.UCE_SERVICE_DOWN";

    /** Uce Service status received in IUceListener.setStatus()
     *  callback
     *  @hide
     */
    public static final int UCE_SERVICE_STATUS_FAILURE = 0;
    /** indicate UI to call Presence/Options API.   */
    public static final int UCE_SERVICE_STATUS_ON = 1;
    /** Indicate UI destroy Presence/Options   */
    public static final int UCE_SERVICE_STATUS_CLOSED = 2;
    /** Service up and trying to register for network events  */
    public static final int UCE_SERVICE_STATUS_READY = 3;

    /**
     * Part of the ACTION_UCE_SERVICE_UP or _DOWN intents. A long
     * value; the phone ID corresponding to the IMS service coming up or down.
     * Internal use only.
     * @hide
     */
    public static final String EXTRA_PHONE_ID = "android:phone_id";


    /**
     * Gets the instance of UCE Manager
     * @hide
     */
    public static ImsUceManager getInstance(Context context, int phoneId) {
        //if (DBG) Log.d (LOG_TAG, "GetInstance Called");
        synchronized (sUceManagerInstances) {
            if (sUceManagerInstances.containsKey(phoneId)) {
                return sUceManagerInstances.get(phoneId);
            } else {
                ImsUceManager uceMgr =  new ImsUceManager(context, phoneId);
                sUceManagerInstances.put(phoneId, uceMgr);
                return uceMgr;
            }
        }
    }

    /**
     * Constructor
     * @hide
     */
    private ImsUceManager(Context context, int phoneId) {
        //if (DBG) Log.d (LOG_TAG, "Constructor");
        mContext = context;
        mPhoneId = phoneId;
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
    private String getUceServiceName(int phoneId) {
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
            IBinder binder = ServiceManager.checkService(getUceServiceName(mPhoneId));

            if (binder == null) {
                //if (DBG)Log.d (LOG_TAG, "Unable to find IBinder");
                return;
            }
        }
        IBinder b = ServiceManager.getService(getUceServiceName(mPhoneId));

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
            //if (DBG) Log.d (LOG_TAG, "found IBinder/IUceService Service Died");
            mUceService = null;

            if (mContext != null) {
                Intent intent = new Intent(ACTION_UCE_SERVICE_DOWN);
                intent.putExtra(EXTRA_PHONE_ID, mPhoneId);
                mContext.sendBroadcast(new Intent(intent));
            }
        }
    }
}
