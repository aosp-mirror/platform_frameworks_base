/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.view;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Matrix;
import android.os.IBinder;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.IAccessibilityEmbeddedConnection;

import java.lang.ref.WeakReference;

/**
 * This class is an interface this ViewRootImpl provides to the host view to the latter
 * can interact with the view hierarchy in SurfaceControlViewHost.
 *
 * @hide
 */
final class AccessibilityEmbeddedConnection extends IAccessibilityEmbeddedConnection.Stub {
    private final WeakReference<ViewRootImpl> mViewRootImpl;
    private final Matrix mTmpWindowMatrix = new Matrix();

    AccessibilityEmbeddedConnection(ViewRootImpl viewRootImpl) {
        mViewRootImpl = new WeakReference<>(viewRootImpl);
    }

    @Override
    public @Nullable IBinder associateEmbeddedHierarchy(@NonNull IBinder host, int hostViewId) {
        final ViewRootImpl viewRootImpl = mViewRootImpl.get();
        if (viewRootImpl != null) {
            final AccessibilityManager accessibilityManager = AccessibilityManager.getInstance(
                    viewRootImpl.mContext);
            viewRootImpl.mAttachInfo.mLeashedParentToken = host;
            viewRootImpl.mAttachInfo.mLeashedParentAccessibilityViewId = hostViewId;
            if (accessibilityManager.isEnabled()) {
                accessibilityManager.associateEmbeddedHierarchy(host, viewRootImpl.mLeashToken);
            }
            return viewRootImpl.mLeashToken;
        }
        return null;
    }

    @Override
    public void disassociateEmbeddedHierarchy() {
        final ViewRootImpl viewRootImpl = mViewRootImpl.get();
        if (viewRootImpl != null) {
            final AccessibilityManager accessibilityManager = AccessibilityManager.getInstance(
                    viewRootImpl.mContext);
            viewRootImpl.mAttachInfo.mLeashedParentToken = null;
            viewRootImpl.mAttachInfo.mLeashedParentAccessibilityViewId = View.NO_ID;
            if (accessibilityManager.isEnabled()) {
                accessibilityManager.disassociateEmbeddedHierarchy(viewRootImpl.mLeashToken);
            }
        }
    }

    @Override
    public void setWindowMatrix(float[] matrixValues) {
        final ViewRootImpl viewRootImpl = mViewRootImpl.get();
        if (viewRootImpl != null) {
            mTmpWindowMatrix.setValues(matrixValues);
            if (viewRootImpl.mAttachInfo.mWindowMatrixInEmbeddedHierarchy == null) {
                viewRootImpl.mAttachInfo.mWindowMatrixInEmbeddedHierarchy = new Matrix();
            }
            viewRootImpl.mAttachInfo.mWindowMatrixInEmbeddedHierarchy.set(mTmpWindowMatrix);
        }
    }
}
