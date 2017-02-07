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
import android.content.Intent;
import android.content.IntentSender;
import android.os.IBinder;
import android.service.autofill.IAutoFillAppCallback;
import android.util.Log;
import android.view.View;

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
        public void enableSession() {
            if (DEBUG) Log.d(TAG, "enableSession()");

            mEnabled = true;
        }

        @Override
        public void autoFill(Dataset dataset) {
            final Activity activity = mActivity.get();
            if (activity == null) {
                if (DEBUG) Log.d(TAG, "autoFill(): activity already GCed");
                return;
            }
            // TODO(b/33197203): must keep the dataset so subsequent calls pass the same
            // dataset.extras to service
            activity.runOnUiThread(() -> {
                final View root = activity.getWindow().getDecorView().getRootView();
                final int itemCount = dataset.getFieldIds().size();
                for (int i = 0; i < itemCount; i++) {
                    final AutoFillId id = dataset.getFieldIds().get(i);
                    final AutoFillValue value = dataset.getFieldValues().get(i);
                    final int viewId = id.getViewId();
                    final View view = root.findViewByAccessibilityIdTraversal(viewId);
                    if (view == null) {
                        Log.w(TAG, "autoFill(): no View with id " + viewId);
                        continue;
                    }

                    if (id.isVirtual()) {
                        view.autoFillVirtual(id.getVirtualChildId(), value);
                    } else {
                        view.autoFill(value);
                    }
                }
            });
        }

        @Override
        public void startIntentSender(IntentSender intent, Intent fillInIntent) {
            final Activity activity = mActivity.get();
            if (activity != null) {
                activity.runOnUiThread(() -> {
                    try {
                        activity.startIntentSender(intent, fillInIntent, 0, 0, 0);
                    } catch (IntentSender.SendIntentException e) {
                        Log.e(TAG, "startIntentSender() failed for intent:" + intent, e);
                    }
                });
            }
        }
    };

    private final AutoFillManager mAfm;
    private WeakReference<Activity> mActivity;

    // Reference to the token, which is used by the server.
    final WeakReference<IBinder> mToken;

    private boolean mEnabled;

    public AutoFillSession(AutoFillManager afm, IBinder token) {
        mToken = new WeakReference<>(token);
        mAfm = afm;
    }

    /**
     * Called by the {@link Activity} when it was asked to provider auto-fill data.
     */
    public void attachActivity(Activity activity) {
        if (mActivity != null) {
            Log.w(TAG, "attachActivity(): already attached");
            return;
        }
        mActivity = new WeakReference<>(activity);
    }

    /**
     * Checks whether auto-fill is enabled for this session, as decided by the
     * {@code AutoFillManagerService}.
     */
    public boolean isEnabled() {
        return mEnabled;
    }

    /**
     * Notifies the manager that a session finished.
     */
    // TODO(b/33197203): hook it to other lifecycle events like fragments transition
    public void finishSession() {
        if (mAfm != null) {
            try {
                mAfm.reset();
            } catch (RuntimeException e) {
                Log.w(TAG, "Failed to finish session for " + mToken.get() + ": " + e);
            }
        }
    }

    public IAutoFillAppCallback getCallback() {
        return mCallback;
    }

    @Override
    public String toString() {
        if (!DEBUG) return super.toString();

        return "AutoFillSession[activityoken=" + mToken.get() + "]";
    }
}
