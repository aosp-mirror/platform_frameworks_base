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

import static android.service.autofill.augmented.AugmentedAutofillService.DEBUG;

import android.annotation.LongDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.Dialog;
import android.graphics.Rect;
import android.service.autofill.augmented.AugmentedAutofillService.AutofillProxy;
import android.service.autofill.augmented.PresentationParams.Area;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import dalvik.system.CloseGuard;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

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
//TODO(b/122654591): @TestApi is needed because CtsAutoFillServiceTestCases hosts the service
//in the same package as the test, and that module is compiled with SDK=test_current
public final class FillWindow implements AutoCloseable {
    private static final String TAG = "FillWindow";

    /** Indicates the data being shown is a physical address */
    public static final long FLAG_METADATA_ADDRESS = 0x1;

    // TODO(b/111330312): add moar flags

    /** @hide */
    @LongDef(prefix = { "FLAG" }, value = {
            FLAG_METADATA_ADDRESS,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface Flags{}

    private final Object mLock = new Object();
    private final CloseGuard mCloseGuard = CloseGuard.get();

    @GuardedBy("mLock")
    private Dialog mDialog;

    @GuardedBy("mLock")
    private boolean mDestroyed;

    private AutofillProxy mProxy;

    /**
     * Updates the content of the window.
     *
     * @param rootView new root view
     * @param area coordinates to render the view.
     * @param flags optional flags such as metadata of what will be rendered in the window. The
     * Smart Suggestion host might decide whether or not to render the UI based on them.
     *
     * @return boolean whether the window was updated or not.
     *
     * @throws IllegalArgumentException if the area is not compatible with this window
     */
    public boolean update(@NonNull Area area, @NonNull View rootView, @Flags long flags) {
        if (DEBUG) {
            Log.d(TAG, "Updating " + area + " + with " + rootView);
        }
        // TODO(b/111330312): add test case for null
        Preconditions.checkNotNull(area);
        Preconditions.checkNotNull(rootView);
        // TODO(b/111330312): must check the area is a valid object returned by
        // SmartSuggestionParams, throw IAE if not

        // TODO(b/111330312): must some how pass metadata to the SmartSuggestiongs provider


        // TODO(b/111330312): use a SurfaceControl approach; for now, we're manually creating
        // the window underneath the existing view.

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

            // TODO(b/111330312): once we have the SurfaceControl approach, we should update the
            // window instead of destroying. In fact, it might be better to allocate a full window
            // initially, which is transparent (and let touches get through) everywhere but in the
            // rect boundaries.
            destroy();

            // TODO(b/111330312): make sure all touch events are handled, window is always closed,
            // etc.

            mDialog = new Dialog(rootView.getContext()) {
                @Override
                public boolean onTouchEvent(MotionEvent event) {
                    if (event.getAction() == MotionEvent.ACTION_OUTSIDE) {
                        FillWindow.this.destroy();
                    }
                    return false;
                }
            };
            mCloseGuard.open("destroy");
            final Window window = mDialog.getWindow();
            window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            // Makes sure touch outside the dialog is received by the window behind the dialog.
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
            // Makes sure the touch outside the dialog is received by the dialog to dismiss it.
            window.addFlags(WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);
            // Makes sure keyboard shows up.
            window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);

            final int height = rect.bottom - rect.top;
            final int width = rect.right - rect.left;
            final WindowManager.LayoutParams windowParams = window.getAttributes();
            windowParams.gravity = Gravity.TOP | Gravity.LEFT;
            windowParams.y = rect.top + height;
            windowParams.height = height;
            windowParams.x = rect.left;
            windowParams.width = width;

            window.setAttributes(windowParams);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setBackgroundDrawableResource(android.R.color.transparent);

            mDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            final ViewGroup.LayoutParams diagParams = new ViewGroup.LayoutParams(width, height);
            mDialog.setContentView(rootView, diagParams);

            if (DEBUG) {
                Log.d(TAG, "Created FillWindow: params= " + smartSuggestion + " view=" + rootView);
            }

            mProxy.setFillWindow(this);
            return true;
        }
    }

    /** @hide */
    void show() {
        // TODO(b/111330312): check if updated first / throw exception
        if (DEBUG) Log.d(TAG, "show()");

        synchronized (mLock) {
            checkNotDestroyedLocked();
            if (mDialog == null) {
                throw new IllegalStateException("update() not called yet, or already destroyed()");
            }

            mDialog.show();
            if (mProxy != null) {
                mProxy.report(AutofillProxy.REPORT_EVENT_UI_SHOWN);
            }
        }
    }

    /**
     * Destroys the window.
     *
     * <p>Once destroyed, this window cannot be used anymore
     */
    public void destroy() {
        if (DEBUG) Log.d(TAG, "destroy(): mDestroyed=" + mDestroyed + " mDialog=" + mDialog);

        synchronized (this) {
            if (mDestroyed || mDialog == null) return;

            mDialog.dismiss();
            mDialog = null;
            if (mProxy != null) {
                mProxy.report(AutofillProxy.REPORT_EVENT_UI_DESTROYED);
            }
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
            if (mDialog != null) {
                pw.print(prefix); pw.print("dialog: ");
                pw.println(mDialog.isShowing() ? "shown" : "hidden");
                pw.print(prefix); pw.print("window: ");
                pw.println(mDialog.getWindow().getAttributes());
            }
        }
    }

    /** @hide */
    @Override
    public void close() throws Exception {
        destroy();
    }
}
