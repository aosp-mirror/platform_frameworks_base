package android.net.wifi.rtt;

import static android.Manifest.permission.ACCESS_COARSE_LOCATION;
import static android.Manifest.permission.ACCESS_WIFI_STATE;
import static android.Manifest.permission.CHANGE_WIFI_STATE;

import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemService;
import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import java.util.List;

/**
 * This class provides the primary API for measuring distance (range) to other devices using the
 * IEEE 802.11mc Wi-Fi Round Trip Time (RTT) technology.
 * <p>
 * The devices which can be ranged include:
 * <li>Access Points (APs)
 * <p>
 * Ranging requests are triggered using
 * {@link #startRanging(RangingRequest, RangingResultCallback, Handler)}. Results (in case of
 * successful operation) are returned in the {@link RangingResultCallback#onRangingResults(List)}
 * callback.
 *
 * @hide RTT_API
 */
@SystemService(Context.WIFI_RTT2_SERVICE)
public class WifiRttManager {
    private static final String TAG = "WifiRttManager";
    private static final boolean VDBG = true;

    private final Context mContext;
    private final IWifiRttManager mService;

    /** @hide */
    public WifiRttManager(Context context, IWifiRttManager service) {
        mContext = context;
        mService = service;
    }

    /**
     * Initiate a request to range to a set of devices specified in the {@link RangingRequest}.
     * Results will be returned in the {@link RangingResultCallback} set of callbacks.
     *
     * @param request  A request specifying a set of devices whose distance measurements are
     *                 requested.
     * @param callback A callback for the result of the ranging request.
     * @param handler  The Handler on whose thread to execute the callbacks of the {@code
     *                 callback} object. If a null is provided then the application's main thread
     *                 will be used.
     */
    @RequiresPermission(allOf = {ACCESS_COARSE_LOCATION, CHANGE_WIFI_STATE, ACCESS_WIFI_STATE})
    public void startRanging(RangingRequest request, RangingResultCallback callback,
            @Nullable Handler handler) {
        if (VDBG) {
            Log.v(TAG, "startRanging: request=" + request + ", callback=" + callback + ", handler="
                    + handler);
        }

        Looper looper = (handler == null) ? Looper.getMainLooper() : handler.getLooper();
        Binder binder = new Binder();
        try {
            mService.startRanging(binder, mContext.getOpPackageName(), request,
                    new RttCallbackProxy(looper, callback));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private static class RttCallbackProxy extends IRttCallback.Stub {
        private final Handler mHandler;
        private final RangingResultCallback mCallback;

        RttCallbackProxy(Looper looper, RangingResultCallback callback) {
            mHandler = new Handler(looper);
            mCallback = callback;
        }

        @Override
        public void onRangingResults(int status, List<RangingResult> results) throws RemoteException {
            if (VDBG) {
                Log.v(TAG, "RttCallbackProxy: onRanginResults: status=" + status + ", results="
                        + results);
            }
            mHandler.post(() -> {
               if (status == RangingResultCallback.STATUS_SUCCESS) {
                   mCallback.onRangingResults(results);
               } else {
                   mCallback.onRangingFailure();
               }
            });
        }
    }
}
