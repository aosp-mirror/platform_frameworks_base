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

package android.view.accessibility;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.content.Context;
import android.os.Bundle;
import android.os.Message;
import android.view.View;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;

/**
 * Object responsible to ensuring that a {@link View} is prepared to meet a synchronous request for
 * accessibility data.
 * <p>
 * Because accessibility requests arrive to {@link View}s synchronously on the UI thread, a View
 * that requires information from other processes can struggle to meet those requests. Registering
 * an instance of this class with {@link AccessibilityManager} allows a View to be notified when
 * a request is about to be made, and to asynchronously inform the accessibility system when it is
 * ready to meet the request.
 * <p>
 * <strong>Note:</strong> This class should only be needed in exceptional situations where a
 * {@link View} cannot otherwise synchronously meet the request for accessibility data.
 */
public abstract class AccessibilityRequestPreparer {
    public static final int REQUEST_TYPE_EXTRA_DATA = 0x00000001;

    /** @hide */
    @IntDef(flag = true, prefix = { "REQUEST_TYPE_" }, value = {
            REQUEST_TYPE_EXTRA_DATA
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RequestTypes {}

    private final WeakReference<View> mViewRef;
    private final int mAccessibilityViewId;
    private final int mRequestTypes;

    /**
     * @param view The view whose requests need preparation. It must be attached to a
     * window. This object will retain a weak reference to this view, and will unregister itself
     * from AccessibilityManager if the view is detached from a window. It will not re-register
     * itself.
     * @param requestTypes The types of requests that require preparation. Different types may
     * be ORed together.
     *
     * @throws IllegalStateException if the view is not attached to a window.
     */
    public AccessibilityRequestPreparer(View view, @RequestTypes int requestTypes) {
        if (!view.isAttachedToWindow()) {
            throw new IllegalStateException("View must be attached to a window");
        }
        mViewRef = new WeakReference<>(view);
        mAccessibilityViewId = view.getAccessibilityViewId();
        mRequestTypes = requestTypes;
        view.addOnAttachStateChangeListener(new ViewAttachStateListener());
    }

    /**
     * Callback to allow preparation for filling extra data. Only called back if
     * REQUEST_TYPE_EXTRA_DATA is requested.
     *
     * @param virtualViewId The ID of a virtual child node, if the {@link View} for this preparer
     * supports virtual descendents, or {@link AccessibilityNodeProvider#HOST_VIEW_ID}
     * if the request is for the view itself.
     * @param extraDataKey The extra data key for the request
     * @param args The arguments for the request
     * @param preparationFinishedMessage A message that must be sent to its target when preparations
     * are complete.
     *
     * @see View#addExtraDataToAccessibilityNodeInfo(AccessibilityNodeInfo, String, Bundle)
     * @see View.AccessibilityDelegate#addExtraDataToAccessibilityNodeInfo(
     * View, AccessibilityNodeInfo, String, Bundle)
     * @see AccessibilityNodeProvider#addExtraDataToAccessibilityNodeInfo(
     * int, AccessibilityNodeInfo, String, Bundle)
     */
    public abstract void onPrepareExtraData(int virtualViewId, String extraDataKey,
            Bundle args, Message preparationFinishedMessage);

    /**
     * Get the view this object was created with.
     *
     * @return The view this object was created with, or {@code null} if the weak reference held
     * to the view is no longer valid.
     */
    public @Nullable View getView() {
        return mViewRef.get();
    }

    private class ViewAttachStateListener implements View.OnAttachStateChangeListener {
        @Override
        public void onViewAttachedToWindow(View v) {
        }

        @Override
        public void onViewDetachedFromWindow(View v) {
            Context context = v.getContext();
            if (context != null) {
                context.getSystemService(AccessibilityManager.class)
                        .removeAccessibilityRequestPreparer(AccessibilityRequestPreparer.this);
            }
            v.removeOnAttachStateChangeListener(this);
        }
    }

    int getAccessibilityViewId() {
        return mAccessibilityViewId;
    }
}
