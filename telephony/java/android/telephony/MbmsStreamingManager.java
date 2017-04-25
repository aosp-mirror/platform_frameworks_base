/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.telephony;

import android.content.Context;
import android.telephony.mbms.IMbmsStreamingManagerListener;
import android.telephony.mbms.IStreamingServiceListener;
import android.telephony.mbms.StreamingService;
import android.telephony.mbms.StreamingServiceInfo;
import android.util.Log;

import java.util.List;

import static android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID;

/** @hide */
public class MbmsStreamingManager {
    private static final String LOG_TAG = "MbmsStreamingManager";
    private static final boolean DEBUG = true;

    private final Context mContext;
    private int mSubId = INVALID_SUBSCRIPTION_ID;

    /**
     * Create a new MbmsStreamingManager using the system default data subscription ID.
     *
     * Note that this call will bind a remote service and that may take a bit.  This
     * may throw an IllegalArgumentException or RemoteException.
     */
    public MbmsStreamingManager(Context context, IMbmsStreamingManagerListener listener,
            String streamingAppName) {
        mContext = context;
    }

    /**
     * Create a new MbmsStreamingManager using the given subscription ID.
     *
     * Note that this call will bind a remote service and that may take a bit.  This
     * may throw an IllegalArgumentException or RemoteException.
     */
    public MbmsStreamingManager(Context context, IMbmsStreamingManagerListener listener,
                    String streamingAppName, int subId) {
        mContext = context;
    }

    /**
     * Terminates this instance, ending calls to the registered listener.  Also terminates
     * any streaming services spawned from this instance.
     */
    public void dispose() {
        // service.dispose(streamingAppName);
    }

    /**
     * An inspection API to retrieve the list of streaming media currently be advertised.
     * The results are returned asynchronously through the previously registered callback.
     * serviceClasses lets the app filter on types of programming and is opaque data between
     * the app and the carrier.
     *
     * Multiple calls replace the list of serviceClasses of interest.
     *
     * May throw an IllegalArgumentException or RemoteException.
     *
     * Synchronous responses include
     * <li>SUCCESS</li>
     * <li>ERROR_MSDC_CONCURRENT_SERVICE_LIMIT_REACHED</li>
     *
     * Asynchronous errors through the listener include any of the errors except
     * <li>ERROR_MSDC_UNABLE_TO_)START_SERVICE</li>
     * <li>ERROR_MSDC_INVALID_SERVICE_ID</li>
     * <li>ERROR_MSDC_END_OF_SESSION</li>
     */
    public int getStreamingServices(List<String> classList) {
        return 0;
    }

    /**
     * Starts streaming a requested service, reporting status to the indicated listener.
     * Returns an object used to control that stream.
     *
     * May throw an IllegalArgumentException or RemoteException.
     *
     * Asynchronous errors through the listener include any of the errors
     */
    public StreamingService startStreaming(StreamingServiceInfo serviceInfo,
            IStreamingServiceListener listener) {
        return null;
    }

    /**
     * Lists all the services currently being streamed to the device by this application
     * on this given subId.  Results are returned asynchronously through the previously
     * registered callback.
     *
     * May throw a RemoteException.
     *
     * The return value is a success/error-code with the following possible values:
     * <li>SUCCESS</li>
     * <li>ERROR_MSDC_CONCURRENT_SERVICE_LIMIT_REACHED</li>
     *
     * Asynchronous errors through the listener include any of the errors except
     * <li>ERROR_UNABLED_TO_START_SERVICE</li>
     * <li>ERROR_MSDC_INVALID_SERVICE_ID</li>
     * <li>ERROR_MSDC_END_OF_SESSION</li>
     *
     */
    public int getActiveStreamingServices() {
        return 0;
    }

    private void logd(String str) {
        Log.d(LOG_TAG, str);
    }
}
