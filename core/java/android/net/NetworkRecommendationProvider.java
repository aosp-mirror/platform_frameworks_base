package android.net;

import android.annotation.SystemApi;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The base class for implementing a network recommendation provider.
 * @hide
 */
@SystemApi
public abstract class NetworkRecommendationProvider {
    private static final String TAG = "NetworkRecProvider";
    /** The key into the callback Bundle where the RecommendationResult will be found. */
    public static final String EXTRA_RECOMMENDATION_RESULT =
            "android.net.extra.RECOMMENDATION_RESULT";
    /** The key into the callback Bundle where the sequence will be found. */
    public static final String EXTRA_SEQUENCE = "android.net.extra.SEQUENCE";
    private final IBinder mService;

    /**
     * Constructs a new instance.
     * @param handler indicates which thread to use when handling requests. Cannot be {@code null}.
     */
    public NetworkRecommendationProvider(Handler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("The provided handler cannot be null.");
        }
        mService = new ServiceWrapper(handler);
    }

    /**
     * Invoked when a recommendation has been requested.
     *
     * @param request a {@link RecommendationRequest} instance containing additional
     *                request details
     * @param callback a {@link ResultCallback} instance. When a {@link RecommendationResult} is
     *                 available it must be passed into
     *                 {@link ResultCallback#onResult(RecommendationResult)}.
     */
    public abstract void onRequestRecommendation(RecommendationRequest request,
            ResultCallback callback);

    /**
     * Invoked when network scores have been requested.
     * <p>
     * Use {@link NetworkScoreManager#updateScores(ScoredNetwork[])} to respond to score requests.
     *
     * @param networks a non-empty array of {@link NetworkKey}s to score.
     */
    public abstract void onRequestScores(NetworkKey[] networks);

    /**
     * Services that can handle {@link NetworkScoreManager#ACTION_RECOMMEND_NETWORKS} should
     * return this Binder from their <code>onBind()</code> method.
     */
    public final IBinder getBinder() {
        return mService;
    }

    /**
     * A callback implementing applications should invoke when a {@link RecommendationResult}
     * is available.
     */
    public static class ResultCallback {
        private final IRemoteCallback mCallback;
        private final int mSequence;
        private final AtomicBoolean mCallbackRun;

        /**
         * @hide
         */
        @VisibleForTesting
        public ResultCallback(IRemoteCallback callback, int sequence) {
            mCallback = callback;
            mSequence = sequence;
            mCallbackRun = new AtomicBoolean(false);
        }

        /**
         * Run the callback with the available {@link RecommendationResult}.
         * @param result a {@link RecommendationResult} instance.
         */
        public void onResult(RecommendationResult result) {
            if (!mCallbackRun.compareAndSet(false, true)) {
                throw new IllegalStateException("The callback cannot be run more than once.");
            }
            final Bundle data = new Bundle();
            data.putInt(EXTRA_SEQUENCE, mSequence);
            data.putParcelable(EXTRA_RECOMMENDATION_RESULT, result);
            try {
                mCallback.sendResult(data);
            } catch (RemoteException e) {
                Log.w(TAG, "Callback failed for seq: " + mSequence, e);
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            ResultCallback that = (ResultCallback) o;

            return mSequence == that.mSequence
                    && Objects.equals(mCallback, that.mCallback);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mCallback, mSequence);
        }
    }

    /**
     * A wrapper around INetworkRecommendationProvider that dispatches to the provided Handler.
     */
    private final class ServiceWrapper extends INetworkRecommendationProvider.Stub {
        private final Handler mHandler;

        ServiceWrapper(Handler handler) {
            mHandler = handler;
        }

        @Override
        public void requestRecommendation(final RecommendationRequest request,
                final IRemoteCallback callback, final int sequence) throws RemoteException {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    ResultCallback resultCallback = new ResultCallback(callback, sequence);
                    onRequestRecommendation(request, resultCallback);
                }
            });
        }

        @Override
        public void requestScores(final NetworkKey[] networks) throws RemoteException {
            if (networks != null && networks.length > 0) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        onRequestScores(networks);
                    }
                });
            }
        }
    }
}
