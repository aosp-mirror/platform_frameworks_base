/*
 * Copyright (C) 2020 The Android Open Source Project
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
package android.service.autofill;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.content.IntentSender;
import android.graphics.PixelFormat;
import android.os.BaseBundle;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.util.Log;
import android.util.LruCache;
import android.util.Size;
import android.view.Display;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.window.InputTransferToken;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;

/**
 * A service that renders an inline presentation view given the {@link InlinePresentation}.
 *
 * {@hide}
 */
@SystemApi
public abstract class InlineSuggestionRenderService extends Service {

    private static final String TAG = "InlineSuggestionRenderService";

    /**
     * The {@link Intent} that must be declared as handled by the service.
     *
     * <p>To be supported, the service must also require the
     * {@link android.Manifest.permission#BIND_INLINE_SUGGESTION_RENDER_SERVICE} permission so that
     * other applications can not abuse it.
     */
    public static final String SERVICE_INTERFACE =
            "android.service.autofill.InlineSuggestionRenderService";

    private final Handler mMainHandler = new Handler(Looper.getMainLooper(), null, true);

    private IInlineSuggestionUiCallback mCallback;


    /**
     * A local LRU cache keeping references to the inflated {@link SurfaceControlViewHost}s, so
     * they can be released properly when no longer used. Each view needs to be tracked separately,
     * therefore for simplicity we use the hash code of the value object as key in the cache.
     */
    private final LruCache<InlineSuggestionUiImpl, Boolean> mActiveInlineSuggestions =
            new LruCache<InlineSuggestionUiImpl, Boolean>(30) {
                @Override
                public void entryRemoved(boolean evicted, InlineSuggestionUiImpl key,
                        Boolean oldValue,
                        Boolean newValue) {
                    if (evicted) {
                        Log.w(TAG,
                                "Hit max=30 entries in the cache. Releasing oldest one to make "
                                        + "space.");
                        key.releaseSurfaceControlViewHost();
                    }
                }
            };

    /**
     * If the specified {@code width}/{@code height} is an exact value, then it will be returned as
     * is, otherwise the method tries to measure a size that is just large enough to fit the view
     * content, within constraints posed by {@code minSize} and {@code maxSize}.
     *
     * @param view    the view for which we measure the size
     * @param width   the expected width of the view, either an exact value or {@link
     *                ViewGroup.LayoutParams#WRAP_CONTENT}
     * @param height  the expected width of the view, either an exact value or {@link
     *                ViewGroup.LayoutParams#WRAP_CONTENT}
     * @param minSize the lower bound of the size to be returned
     * @param maxSize the upper bound of the size to be returned
     * @return the measured size of the view based on the given size constraints.
     */
    private Size measuredSize(@NonNull View view, int width, int height, @NonNull Size minSize,
            @NonNull Size maxSize) {
        if (width != ViewGroup.LayoutParams.WRAP_CONTENT
                && height != ViewGroup.LayoutParams.WRAP_CONTENT) {
            return new Size(width, height);
        }
        int widthMeasureSpec;
        if (width == ViewGroup.LayoutParams.WRAP_CONTENT) {
            widthMeasureSpec = View.MeasureSpec.makeMeasureSpec(maxSize.getWidth(),
                    View.MeasureSpec.AT_MOST);
        } else {
            widthMeasureSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
        }
        int heightMeasureSpec;
        if (height == ViewGroup.LayoutParams.WRAP_CONTENT) {
            heightMeasureSpec = View.MeasureSpec.makeMeasureSpec(maxSize.getHeight(),
                    View.MeasureSpec.AT_MOST);
        } else {
            heightMeasureSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY);
        }
        view.measure(widthMeasureSpec, heightMeasureSpec);
        return new Size(Math.max(view.getMeasuredWidth(), minSize.getWidth()),
                Math.max(view.getMeasuredHeight(), minSize.getHeight()));
    }

    private void handleRenderSuggestion(IInlineSuggestionUiCallback callback,
            InlinePresentation presentation, int width, int height, IBinder hostInputToken,
            int displayId, int userId, int sessionId) {
        if (hostInputToken == null) {
            try {
                callback.onError();
            } catch (RemoteException e) {
                Log.w(TAG, "RemoteException calling onError()");
            }
            return;
        }

        // When we create the UI it should be for the IME display
        updateDisplay(displayId);
        try {
            final View suggestionView = onRenderSuggestion(presentation, width, height);
            if (suggestionView == null) {
                Log.w(TAG, "ExtServices failed to render the inline suggestion view.");
                try {
                    callback.onError();
                } catch (RemoteException e) {
                    Log.w(TAG, "Null suggestion view returned by renderer");
                }
                return;
            }
            mCallback = callback;
            final Size measuredSize = measuredSize(suggestionView, width, height,
                    presentation.getInlinePresentationSpec().getMinSize(),
                    presentation.getInlinePresentationSpec().getMaxSize());
            Log.v(TAG, "width=" + width + ", height=" + height + ", measuredSize=" + measuredSize);

            final InlineSuggestionRoot suggestionRoot = new InlineSuggestionRoot(this, callback);
            suggestionRoot.addView(suggestionView);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(measuredSize.getWidth(),
                    measuredSize.getHeight(), WindowManager.LayoutParams.TYPE_APPLICATION, 0,
                    PixelFormat.TRANSPARENT);

            final SurfaceControlViewHost host = new SurfaceControlViewHost(this, getDisplay(),
                    new InputTransferToken(hostInputToken), "InlineSuggestionRenderService");
            host.setView(suggestionRoot, lp);

            // Set the suggestion view to be non-focusable so that if its background is set to a
            // ripple drawable, the ripple won't be shown initially.
            suggestionView.setFocusable(false);
            suggestionView.setOnClickListener((v) -> {
                try {
                    callback.onClick();
                } catch (RemoteException e) {
                    Log.w(TAG, "RemoteException calling onClick()");
                }
            });
            final View.OnLongClickListener onLongClickListener =
                    suggestionView.getOnLongClickListener();
            suggestionView.setOnLongClickListener((v) -> {
                if (onLongClickListener != null) {
                    onLongClickListener.onLongClick(v);
                }
                try {
                    callback.onLongClick();
                } catch (RemoteException e) {
                    Log.w(TAG, "RemoteException calling onLongClick()");
                }
                return true;
            });
            final InlineSuggestionUiImpl uiImpl = new InlineSuggestionUiImpl(host, mMainHandler,
                    userId, sessionId);
            mActiveInlineSuggestions.put(uiImpl, true);

            // We post the callback invocation to the end of the main thread handler queue, to make
            // sure the callback happens after the views are drawn. This is needed because calling
            // {@link SurfaceControlViewHost#setView()} will post a task to the main thread
            // to draw the view asynchronously.
            mMainHandler.post(() -> {
                try {
                    callback.onContent(new InlineSuggestionUiWrapper(uiImpl),
                            host.getSurfacePackage(),
                            measuredSize.getWidth(), measuredSize.getHeight());
                } catch (RemoteException e) {
                    Log.w(TAG, "RemoteException calling onContent()");
                }
            });
        } finally {
            updateDisplay(Display.DEFAULT_DISPLAY);
        }
    }

    private void handleGetInlineSuggestionsRendererInfo(@NonNull RemoteCallback callback) {
        final Bundle rendererInfo = onGetInlineSuggestionsRendererInfo();
        callback.sendResult(rendererInfo);
    }

    private void handleDestroySuggestionViews(int userId, int sessionId) {
        Log.v(TAG, "handleDestroySuggestionViews called for " + userId + ":" + sessionId);
        for (final InlineSuggestionUiImpl inlineSuggestionUi :
                mActiveInlineSuggestions.snapshot().keySet()) {
            if (inlineSuggestionUi.mUserId == userId
                    && inlineSuggestionUi.mSessionId == sessionId) {
                Log.v(TAG, "Destroy " + inlineSuggestionUi);
                inlineSuggestionUi.releaseSurfaceControlViewHost();
            }
        }
    }

    /**
     * A wrapper class around the {@link InlineSuggestionUiImpl} to ensure it's not strongly
     * reference by the remote system server process.
     */
    private static final class InlineSuggestionUiWrapper extends
            android.service.autofill.IInlineSuggestionUi.Stub {

        private final WeakReference<InlineSuggestionUiImpl> mUiImpl;

        InlineSuggestionUiWrapper(InlineSuggestionUiImpl uiImpl) {
            mUiImpl = new WeakReference<>(uiImpl);
        }

        @Override
        public void releaseSurfaceControlViewHost() {
            final InlineSuggestionUiImpl uiImpl = mUiImpl.get();
            if (uiImpl != null) {
                uiImpl.releaseSurfaceControlViewHost();
            }
        }

        @Override
        public void getSurfacePackage(ISurfacePackageResultCallback callback) {
            final InlineSuggestionUiImpl uiImpl = mUiImpl.get();
            if (uiImpl != null) {
                uiImpl.getSurfacePackage(callback);
            }
        }
    }

    /**
     * Keeps track of a SurfaceControlViewHost to ensure it's released when its lifecycle ends.
     *
     * <p>This class is thread safe, because all the outside calls are piped into a single
     *  handler thread to be processed.
     */
    private final class InlineSuggestionUiImpl {

        @Nullable
        private SurfaceControlViewHost mViewHost;
        @NonNull
        private final Handler mHandler;
        private final int mUserId;
        private final int mSessionId;

        InlineSuggestionUiImpl(SurfaceControlViewHost viewHost, Handler handler, int userId,
                int sessionId) {
            this.mViewHost = viewHost;
            this.mHandler = handler;
            this.mUserId = userId;
            this.mSessionId = sessionId;
        }

        /**
         * Call {@link SurfaceControlViewHost#release()} to release it. After this, this view is
         * not usable, and any further calls to the
         * {@link #getSurfacePackage(ISurfacePackageResultCallback)} will get {@code null} result.
         */
        public void releaseSurfaceControlViewHost() {
            mHandler.post(() -> {
                if (mViewHost == null) {
                    return;
                }
                Log.v(TAG, "Releasing inline suggestion view host");
                mViewHost.release();
                mViewHost = null;
                InlineSuggestionRenderService.this.mActiveInlineSuggestions.remove(
                        InlineSuggestionUiImpl.this);
                Log.v(TAG, "Removed the inline suggestion from the cache, current size="
                        + InlineSuggestionRenderService.this.mActiveInlineSuggestions.size());
            });
        }

        /**
         * Sends back a new {@link android.view.SurfaceControlViewHost.SurfacePackage} if the view
         * is not released, {@code null} otherwise.
         */
        public void getSurfacePackage(ISurfacePackageResultCallback callback) {
            Log.d(TAG, "getSurfacePackage");
            mHandler.post(() -> {
                try {
                    callback.onResult(mViewHost == null ? null : mViewHost.getSurfacePackage());
                } catch (RemoteException e) {
                    Log.w(TAG, "RemoteException calling onSurfacePackage");
                }
            });
        }
    }

    /** @hide */
    @Override
    protected final void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw,
            @NonNull String[] args) {
        pw.println("mActiveInlineSuggestions: " + mActiveInlineSuggestions.size());
        for (InlineSuggestionUiImpl impl : mActiveInlineSuggestions.snapshot().keySet()) {
            pw.printf("ui: [%s] - [%d]  [%d]\n", impl, impl.mUserId, impl.mSessionId);
        }
    }

    @Override
    @Nullable
    public final IBinder onBind(@NonNull Intent intent) {
        BaseBundle.setShouldDefuse(true);
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return new IInlineSuggestionRenderService.Stub() {
                @Override
                public void renderSuggestion(@NonNull IInlineSuggestionUiCallback callback,
                        @NonNull InlinePresentation presentation, int width, int height,
                        @Nullable IBinder hostInputToken, int displayId, int userId,
                        int sessionId) {
                    mMainHandler.sendMessage(
                            obtainMessage(InlineSuggestionRenderService::handleRenderSuggestion,
                                    InlineSuggestionRenderService.this, callback, presentation,
                                    width, height, hostInputToken, displayId, userId, sessionId));
                }

                @Override
                public void getInlineSuggestionsRendererInfo(@NonNull RemoteCallback callback) {
                    mMainHandler.sendMessage(obtainMessage(
                            InlineSuggestionRenderService::handleGetInlineSuggestionsRendererInfo,
                            InlineSuggestionRenderService.this, callback));
                }
                @Override
                public void destroySuggestionViews(int userId, int sessionId) {
                    mMainHandler.sendMessage(obtainMessage(
                            InlineSuggestionRenderService::handleDestroySuggestionViews,
                            InlineSuggestionRenderService.this, userId, sessionId));
                }
            }.asBinder();
        }

        Log.w(TAG, "Tried to bind to wrong intent (should be " + SERVICE_INTERFACE + ": " + intent);
        return null;
    }

    /**
     * Starts the {@link IntentSender} from the client app.
     *
     * @param intentSender the {@link IntentSender} to start the attribution UI from the client
     *                     app.
     */
    public final void startIntentSender(@NonNull IntentSender intentSender) {
        if (mCallback == null) return;
        try {
            mCallback.onStartIntentSender(intentSender);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the metadata about the renderer. Returns {@code Bundle.Empty} if no metadata is
     * provided.
     */
    @NonNull
    public Bundle onGetInlineSuggestionsRendererInfo() {
        return Bundle.EMPTY;
    }

    /**
     * Renders the slice into a view.
     */
    @Nullable
    public View onRenderSuggestion(@NonNull InlinePresentation presentation, int width,
            int height) {
        Log.e(TAG, "service implementation (" + getClass() + " does not implement "
                + "onRenderSuggestion()");
        return null;
    }
}
