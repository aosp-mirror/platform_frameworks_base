/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.service.autofill.augmented;

import static android.service.autofill.augmented.AugmentedAutofillService.sDebug;
import static android.service.autofill.augmented.AugmentedAutofillService.sVerbose;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.service.autofill.augmented.AugmentedAutofillService.AutofillProxy;
import android.service.autofill.augmented.PresentationParams.Area;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.autofill.IAutofillWindowPresenter;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import dalvik.system.CloseGuard;

import java.io.PrintWriter;

/**
 * Handle to a window used to display the augmented autofill UI.
 *
 * <p>The steps to create an augmented autofill UI are:
 *
 * <ol>
 *   <li>Gets the {@link PresentationParams} from the {@link FillRequest}.
 *   <li>Gets the {@link Area} to display the UI (for example, through
 *   {@link PresentationParams#getSuggestionArea()}.
 *   <li>Creates a {@link View} that must fit in the {@link Area#getBounds() area boundaries}.
 *   <li>Set the proper listeners to the view (for example, a click listener that
 *   triggers {@link FillController#autofill(java.util.List)}
 *   <li>Call {@link #update(Area, View, long)} with these arguments.
 *   <li>Create a {@link FillResponse} with the {@link FillWindow}.
 *   <li>Pass such {@link FillResponse} to {@link FillCallback#onSuccess(FillResponse)}.
 * </ol>
 *
 * @hide
 */
@SystemApi
@TestApi
public final class FillWindow implements AutoCloseable {
    private static final String TAG = FillWindow.class.getSimpleName();

    private final Object mLock = new Object();
    private final CloseGuard mCloseGuard = CloseGuard.get();

    private final @NonNull Handler mUiThreadHandler = new Handler(Looper.getMainLooper());
    private final @NonNull FillWindowPresenter mFillWindowPresenter = new FillWindowPresenter();

    @GuardedBy("mLock")
    private WindowManager mWm;
    @GuardedBy("mLock")
    private View mFillView;
    @GuardedBy("mLock")
    private boolean mShowing;
    @GuardedBy("mLock")
    private Rect mBounds;

    @GuardedBy("mLock")
    private boolean mUpdateCalled;
    @GuardedBy("mLock")
    private boolean mDestroyed;

    private AutofillProxy mProxy;

    /**
     * Updates the content of the window.
     *
     * @param rootView new root view
     * @param area coordinates to render the view.
     * @param flags currently not used.
     *
     * @return boolean whether the window was updated or not.
     *
     * @throws IllegalArgumentException if the area is not compatible with this window
     */
    public boolean update(@NonNull Area area, @NonNull View rootView, long flags) {
        if (sDebug) {
            Log.d(TAG, "Updating " + area + " + with " + rootView);
        }
        // TODO(b/123100712): add test case for null
        Preconditions.checkNotNull(area);
        Preconditions.checkNotNull(area.proxy);
        Preconditions.checkNotNull(rootView);
        // TODO(b/123100712): must check the area is a valid object returned by
        // SmartSuggestionParams, throw IAE if not

        final PresentationParams smartSuggestion = area.proxy.getSmartSuggestionParams();
        if (smartSuggestion == null) {
            Log.w(TAG, "No SmartSuggestionParams");
            return false;
        }

        final Rect rect = area.getBounds();
        if (rect == null) {
            Log.wtf(TAG, "No Rect on SmartSuggestionParams");
            return false;
        }

        synchronized (mLock) {
            checkNotDestroyedLocked();

            mProxy = area.proxy;

            // TODO(b/123227534): once we have the SurfaceControl approach, we should update the
            // window instead of destroying. In fact, it might be better to allocate a full window
            // initially, which is transparent (and let touches get through) everywhere but in the
            // rect boundaries.

            // TODO(b/123099468): make sure all touch events are handled, window is always closed,
            // etc.

            mWm = rootView.getContext().getSystemService(WindowManager.class);
            mFillView = rootView;
            // Listen to the touch outside to destroy the window when typing is detected.
            mFillView.setOnTouchListener(
                    (view, motionEvent) -> {
                        if (motionEvent.getAction() == MotionEvent.ACTION_OUTSIDE) {
                            if (sVerbose) Log.v(TAG, "Outside touch detected, hiding the window");
                            hide();
                        }
                        return false;
                    }
            );
            mShowing = false;
            mBounds = new Rect(area.getBounds());
            if (sDebug) {
                Log.d(TAG, "Created FillWindow: params= " + smartSuggestion + " view=" + rootView);
            }
            mUpdateCalled = true;
            mDestroyed = false;
            mProxy.setFillWindow(this);
            return true;
        }
    }

    /** @hide */
    void show() {
        // TODO(b/123100712): check if updated first / throw exception
        if (sDebug) Log.d(TAG, "show()");
        synchronized (mLock) {
            checkNotDestroyedLocked();
            if (mWm == null || mFillView == null) {
                throw new IllegalStateException("update() not called yet, or already destroyed()");
            }
            if (mProxy != null) {
                try {
                    mProxy.requestShowFillUi(mBounds.right - mBounds.left,
                            mBounds.bottom - mBounds.top,
                            /*anchorBounds=*/ null, mFillWindowPresenter);
                } catch (RemoteException e) {
                    Log.w(TAG, "Error requesting to show fill window", e);
                }
                mProxy.report(AutofillProxy.REPORT_EVENT_UI_SHOWN);
            }
        }
    }

    /**
     * Hides the window.
     *
     * <p>The window is not destroyed and can be shown again
     */
    private void hide() {
        if (sDebug) Log.d(TAG, "hide()");
        synchronized (mLock) {
            checkNotDestroyedLocked();
            if (mWm == null || mFillView == null) {
                throw new IllegalStateException("update() not called yet, or already destroyed()");
            }
            if (mProxy != null && mShowing) {
                try {
                    mProxy.requestHideFillUi();
                } catch (RemoteException e) {
                    Log.w(TAG, "Error requesting to hide fill window", e);
                }
            }
        }
    }

    private void handleShow(WindowManager.LayoutParams p) {
        if (sDebug) Log.d(TAG, "handleShow()");
        synchronized (mLock) {
            if (mWm != null && mFillView != null) {
                p.flags |= WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
                if (!mShowing) {
                    mWm.addView(mFillView, p);
                    mShowing = true;
                } else {
                    mWm.updateViewLayout(mFillView, p);
                }
            }
        }
    }

    private void handleHide() {
        if (sDebug) Log.d(TAG, "handleHide()");
        synchronized (mLock) {
            if (mWm != null && mFillView != null && mShowing) {
                mWm.removeView(mFillView);
                mShowing = false;
            }
        }
    }

    /**
     * Destroys the window.
     *
     * <p>Once destroyed, this window cannot be used anymore
     */
    public void destroy() {
        if (sDebug) {
            Log.d(TAG,
                    "destroy(): mDestroyed=" + mDestroyed + " mShowing=" + mShowing + " mFillView="
                            + mFillView);
        }
        synchronized (mLock) {
            if (mDestroyed) return;
            if (mUpdateCalled) {
                hide();
                mProxy.report(AutofillProxy.REPORT_EVENT_UI_DESTROYED);
            }
            mDestroyed = true;
            mCloseGuard.close();
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }
            destroy();
        } finally {
            super.finalize();
        }
    }

    private void checkNotDestroyedLocked() {
        if (mDestroyed) {
            throw new IllegalStateException("already destroyed()");
        }
    }

    /** @hide */
    public void dump(@NonNull String prefix, @NonNull PrintWriter pw) {
        synchronized (this) {
            pw.print(prefix); pw.print("destroyed: "); pw.println(mDestroyed);
            pw.print(prefix); pw.print("updateCalled: "); pw.println(mUpdateCalled);
            if (mFillView != null) {
                pw.print(prefix); pw.print("fill window: ");
                pw.println(mShowing ? "shown" : "hidden");
                pw.print(prefix); pw.print("fill view: ");
                pw.println(mFillView);
                pw.print(prefix); pw.print("mBounds: ");
                pw.println(mBounds);
                pw.print(prefix); pw.print("mWm: ");
                pw.println(mWm);
            }
        }
    }

    /** @hide */
    @Override
    public void close() throws Exception {
        destroy();
    }

    private final class FillWindowPresenter extends IAutofillWindowPresenter.Stub {
        @Override
        public void show(WindowManager.LayoutParams p, Rect transitionEpicenter,
                boolean fitsSystemWindows, int layoutDirection) {
            if (sDebug) Log.d(TAG, "FillWindowPresenter.show()");
            mUiThreadHandler.sendMessage(obtainMessage(FillWindow::handleShow, FillWindow.this, p));
        }

        @Override
        public void hide(Rect transitionEpicenter) {
            if (sDebug) Log.d(TAG, "FillWindowPresenter.hide()");
            mUiThreadHandler.sendMessage(obtainMessage(FillWindow::handleHide, FillWindow.this));
        }
    }
}
