package android.net;

import android.Manifest.permission;
import android.annotation.SystemApi;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The base class for implementing a network recommendation provider.
 * <p>
 * A network recommendation provider is any application which:
 * <ul>
 * <li>Is granted the {@link permission#SCORE_NETWORKS} permission.
 * <li>Includes a Service for the {@link NetworkScoreManager#ACTION_RECOMMEND_NETWORKS} intent
 *     which is protected by the {@link permission#BIND_NETWORK_RECOMMENDATION_SERVICE} permission.
 * </ul>
 * <p>
 * Implementations are required to implement the abstract methods in this class and return the
 * result of {@link #getBinder()} from the <code>onBind()</code> method in their Service.
 * <p>
 * The default network recommendation provider is controlled via the
 * <code>config_defaultNetworkRecommendationProviderPackage</code> config key.
 * @hide
 */
@SystemApi
public abstract class NetworkRecommendationProvider {
    private static final String TAG = "NetworkRecProvider";
    private static final boolean VERBOSE = Build.IS_DEBUGGABLE && Log.isLoggable(TAG, Log.VERBOSE);
    /** The key into the callback Bundle where the RecommendationResult will be found.
     * @deprecated to be removed.
     */
    public static final String EXTRA_RECOMMENDATION_RESULT =
            "android.net.extra.RECOMMENDATION_RESULT";
    /** The key into the callback Bundle where the sequence will be found.
     * @deprecated to be removed.
     */
    public static final String EXTRA_SEQUENCE = "android.net.extra.SEQUENCE";
    private final IBinder mService;

    /**
     * Constructs a new instance.
     * @param handler indicates which thread to use when handling requests. Cannot be {@code null}.
     * @deprecated use {@link #NetworkRecommendationProvider(Context, Executor)}
     */
    public NetworkRecommendationProvider(Handler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("The provided handler cannot be null.");
        }
        mService = new ServiceWrapper(handler);
    }

    /**
     * Constructs a new instance.
     * @param context the current context instance. Cannot be {@code null}.
     * @param executor used to execute the incoming requests. Cannot be {@code null}.
     */
    public NetworkRecommendationProvider(Context context, Executor executor) {
        Preconditions.checkNotNull(context);
        Preconditions.checkNotNull(executor);
        mService = new ServiceWrapper(context, executor);
    }

    /**
     * Invoked when a recommendation has been requested.
     *
     * @param request a {@link RecommendationRequest} instance containing additional
     *                request details
     * @param callback a {@link ResultCallback} instance. When a {@link RecommendationResult} is
     *                 available it must be passed into
     *                 {@link ResultCallback#onResult(RecommendationResult)}.
     * @deprecated to be removed.
     */
    public void onRequestRecommendation(RecommendationRequest request, ResultCallback callback) {}

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
     *
     * @deprecated to be removed.
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
            if (VERBOSE) Log.v(TAG, "onResult(seq=" + mSequence + ")");
            if (!mCallbackRun.compareAndSet(false, true)) {
                throw new IllegalStateException("The callback cannot be run more than once. "
                        + "seq=" + mSequence);
            }
            final Bundle data = new Bundle();
            data.putInt(EXTRA_SEQUENCE, mSequence);
            data.putParcelable(EXTRA_RECOMMENDATION_RESULT, result);
            try {
                mCallback.sendResult(data);
            } catch (RemoteException e) {
                Log.w(TAG, "Callback failed for seq: " + mSequence, e);
            }
            if (VERBOSE) Log.v(TAG, "onResult() complete. seq=" + mSequence);
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
        private final Context mContext;
        private final Executor mExecutor;
        private final Handler mHandler;

        ServiceWrapper(Handler handler) {
            mHandler = handler;
            mExecutor = null;
            mContext = null;
        }

        ServiceWrapper(Context context, Executor executor) {
            mContext = context;
            mExecutor = executor;
            mHandler = null;
        }

        @Override
        public void requestScores(final NetworkKey[] networks) throws RemoteException {
            enforceCallingPermission();
            if (networks != null && networks.length > 0) {
                execute(new Runnable() {
                    @Override
                    public void run() {
                        onRequestScores(networks);
                    }
                });
            }
        }

        private void execute(Runnable command) {
            if (mExecutor != null) {
                mExecutor.execute(command);
            } else {
                mHandler.post(command);
            }
        }

        private void enforceCallingPermission() {
            if (mContext != null) {
                mContext.enforceCallingOrSelfPermission(permission.REQUEST_NETWORK_SCORES,
                        "Permission denied.");
            }
        }
    }
}
