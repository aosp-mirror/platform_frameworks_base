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

package android.view.autofill;

import static android.view.autofill.Helper.DEBUG;

import android.app.Activity;
import android.os.RemoteException;
import android.service.autofill.IAutoFillAppCallback;
import android.util.Log;
import android.view.View;

import com.android.internal.annotations.GuardedBy;

import java.lang.ref.WeakReference;

/**
 * An auto-fill session associated with an activity.
 *
 * @hide
 */
public final class AutoFillSession {

    private static final String TAG = "AutoFillSession";

    private final IAutoFillAppCallback mCallback = new IAutoFillAppCallback.Stub() {
        @Override
        public void autoFill(Dataset dataset) throws RemoteException {
            final Activity activity = mActivity.get();
            if (activity == null) {
                if (DEBUG) Log.d(TAG, "autoFill(): activity already GCed");
                return;
            }
            // TODO(b/33197203): must keep the dataset so subsequent calls pass the same
            // dataset.extras to service
            activity.runOnUiThread(() -> {
                final View root = activity.getWindow().getDecorView().getRootView();
                for (DatasetField field : dataset.getFields()) {
                    final AutoFillId id = field.getId();
                    if (id == null) {
                        Log.w(TAG, "autoFill(): null id on " + field);
                        continue;
                    }
                    final int viewId = id.getViewId();
                    final View view = root.findViewByAccessibilityIdTraversal(viewId);
                    if (view == null) {
                        Log.w(TAG, "autoFill(): no View with id " + viewId);
                        continue;
                    }

                    // TODO(b/33197203): handle protected value (like credit card)
                    if (id.isVirtual()) {
                        // Delegate virtual fields.
                        setAutoFillDelegateCallback();
                        final VirtualViewDelegate delegate = view
                                .getAutoFillVirtualViewDelegate(
                                        mAutoFillDelegateCallback);
                        if (delegate == null) {
                            Log.w(TAG, "autoFill(): cannot fill virtual " + id
                                    + "; no VirtualViewDelegate for view "
                                    + view.getClass());
                            continue;
                        }
                        if (DEBUG) {
                            Log.d(TAG, "autoFill(): delegating " + id
                                    + " to VirtualViewDelegate  " + delegate);
                        }
                        delegate.autoFill(id.getVirtualChildId(), field.getValue());
                    } else {
                        // Handle non-virtual fields itself.
                        view.autoFill(field.getValue());
                    }
                }
            });
        }
    };

    private final WeakReference<Activity> mActivity;

    @GuardedBy("this")
    private VirtualViewDelegate.Callback mAutoFillDelegateCallback;

    public AutoFillSession(Activity activity) {
        mActivity = new WeakReference<>(activity);
    }

    public IAutoFillAppCallback getCallback() {
        return mCallback;
    }

    /**
     * Lazily sets the {@link #mAutoFillDelegateCallback}.
     */
    private void setAutoFillDelegateCallback() {
        synchronized (this) {
            if (mAutoFillDelegateCallback == null) {
                mAutoFillDelegateCallback = new VirtualViewDelegate.Callback() {
                    // TODO(b/33197203): implement
                };
            }
        }
    }

}
