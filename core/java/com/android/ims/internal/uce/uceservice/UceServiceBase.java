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

package com.android.ims.internal.uce;

import com.android.ims.internal.uce.uceservice.IUceService;
import com.android.ims.internal.uce.uceservice.IUceListener;
import com.android.ims.internal.uce.common.StatusCode;
import com.android.ims.internal.uce.common.UceLong;
import com.android.ims.internal.uce.options.IOptionsListener;
import com.android.ims.internal.uce.options.IOptionsService;
import com.android.ims.internal.uce.presence.IPresenceService;
import com.android.ims.internal.uce.presence.IPresenceListener;

/**
 * Sub IUceService interface. To enable forward compatability
 * during developlemt
 * @hide
 */
public abstract class UceServiceBase {
    /**
     * IUceService Stub Implementation
     */
    private final class UceServiceBinder extends IUceService.Stub {
        @Override
        public boolean startService(IUceListener uceListener) {
            return onServiceStart(uceListener);
        }

        @Override
        public boolean stopService() {
            return onStopService();
        }

        @Override
        public boolean isServiceStarted() {
            return onIsServiceStarted();
        }

        @Override
        public int createOptionsService(IOptionsListener optionsListener,
                                        UceLong optionsServiceListenerHdl) {
            return onCreateOptionsService(optionsListener, optionsServiceListenerHdl);
        }

        @Override
        public int createOptionsServiceForSubscription(IOptionsListener optionsListener,
                                      UceLong optionsServiceListenerHdl,
                                      String iccId) {
            return onCreateOptionsService(optionsListener, optionsServiceListenerHdl,
                                          iccId);
        }


        @Override
        public void destroyOptionsService(int optionsServiceHandle) {
            onDestroyOptionsService(optionsServiceHandle);
        }

        @Override
        public int createPresenceService(
            IPresenceListener presServiceListener,
            UceLong presServiceListenerHdl) {
            return onCreatePresService(presServiceListener, presServiceListenerHdl);
        }

        @Override
        public int createPresenceServiceForSubscription(IPresenceListener presServiceListener,
                                         UceLong presServiceListenerHdl,
                                         String iccId) {
            return onCreatePresService(presServiceListener, presServiceListenerHdl,
                                       iccId);
        }

        @Override
        public void destroyPresenceService(int presServiceHdl) {
            onDestroyPresService(presServiceHdl);
        }

        @Override
        public boolean getServiceStatus() {
            return onGetServiceStatus();
        }

        @Override
        public IPresenceService getPresenceService() {
            return onGetPresenceService();
        }

        @Override
        public IPresenceService getPresenceServiceForSubscription(String iccId) {
            return onGetPresenceService(iccId);
        }

        @Override
        public IOptionsService getOptionsService() {
            return onGetOptionsService();
        }

        @Override
        public IOptionsService getOptionsServiceForSubscription(String iccId) {
            return onGetOptionsService(iccId);
        }
    }

    private UceServiceBinder mBinder;

    public UceServiceBinder getBinder() {
        if (mBinder == null) {
            mBinder = new UceServiceBinder();
        }
        return mBinder;
    }

    protected boolean onServiceStart(IUceListener uceListener) {
        //no-op
        return false;
    }

    protected boolean onStopService() {
        //no-op
        return false;
    }

    protected boolean onIsServiceStarted() {
        //no-op
        return false;
    }

    protected int onCreateOptionsService(IOptionsListener optionsListener,
                                                UceLong optionsServiceListenerHdl) {
        //no-op
        return 0;
    }

    protected int onCreateOptionsService(IOptionsListener optionsListener,
                                         UceLong optionsServiceListenerHdl,
                                         String iccId) {
        //no-op
        return 0;
    }

    protected void onDestroyOptionsService(int cdServiceHandle) {
        //no-op
        return;
    }

    protected int onCreatePresService(IPresenceListener presServiceListener,
            UceLong presServiceListenerHdl) {
        //no-op
        return 0;
    }

    protected int onCreatePresService(IPresenceListener presServiceListener,
                                      UceLong presServiceListenerHdl,
                                      String iccId) {
        //no-op
        return 0;
    }

    protected void onDestroyPresService(int presServiceHdl) {
        //no-op
        return;
    }

    protected boolean onGetServiceStatus() {
        //no-op
        return false;
    }

    protected IPresenceService onGetPresenceService() {
        //no-op
        return null;
    }

    protected IPresenceService onGetPresenceService(String iccId) {
        //no-op
        return null;
    }

    protected IOptionsService onGetOptionsService () {
        //no-op
        return null;
    }

    protected IOptionsService onGetOptionsService (String iccId) {
        //no-op
        return null;
    }
}
