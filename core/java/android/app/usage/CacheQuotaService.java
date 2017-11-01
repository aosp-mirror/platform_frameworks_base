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
 * limitations under the License.
 */

package android.app.usage;

import android.annotation.SystemApi;
import android.app.Service;
import android.app.usage.ICacheQuotaService;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteCallback;
import android.util.Log;
import android.util.Pair;

import java.util.List;

/**
 * CacheQuoteService defines a service which accepts cache quota requests and processes them,
 * thereby filling out how much quota each request deserves.
 * {@hide}
 */
@SystemApi
public abstract class CacheQuotaService extends Service {
    private static final String TAG = "CacheQuotaService";

    /**
     * The {@link Intent} action that must be declared as handled by a service
     * in its manifest for the system to recognize it as a quota providing service.
     */
    public static final String SERVICE_INTERFACE = "android.app.usage.CacheQuotaService";

    /** {@hide} **/
    public static final String REQUEST_LIST_KEY = "requests";

    private CacheQuotaServiceWrapper mWrapper;
    private Handler mHandler;

    @Override
    public void onCreate() {
        super.onCreate();
        mWrapper = new CacheQuotaServiceWrapper();
        mHandler = new ServiceHandler(getMainLooper());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mWrapper;
    }

    /**
     * Processes the cache quota list upon receiving a list of requests.
     * @param requests A list of cache quotas to fulfill.
     * @return A completed list of cache quota requests.
     */
    public abstract List<CacheQuotaHint> onComputeCacheQuotaHints(
            List<CacheQuotaHint> requests);

    private final class CacheQuotaServiceWrapper extends ICacheQuotaService.Stub {
        @Override
        public void computeCacheQuotaHints(
                RemoteCallback callback, List<CacheQuotaHint> requests) {
            final Pair<RemoteCallback, List<CacheQuotaHint>> pair =
                    Pair.create(callback, requests);
            Message msg = mHandler.obtainMessage(ServiceHandler.MSG_SEND_LIST, pair);
            mHandler.sendMessage(msg);
        }
    }

    private final class ServiceHandler extends Handler {
        public static final int MSG_SEND_LIST = 1;

        public ServiceHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override
        public void handleMessage(Message msg) {
            final int action = msg.what;
            switch (action) {
                case MSG_SEND_LIST:
                    final Pair<RemoteCallback, List<CacheQuotaHint>> pair =
                            (Pair<RemoteCallback, List<CacheQuotaHint>>) msg.obj;
                    List<CacheQuotaHint> processed = onComputeCacheQuotaHints(pair.second);
                    final Bundle data = new Bundle();
                    data.putParcelableList(REQUEST_LIST_KEY, processed);

                    final RemoteCallback callback = pair.first;
                    callback.sendResult(data);
                    break;
                default:
                    Log.w(TAG, "Handling unknown message: " + action);
            }
        }
    }
}
