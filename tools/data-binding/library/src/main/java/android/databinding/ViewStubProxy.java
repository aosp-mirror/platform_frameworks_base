/*
 * Copyright (C) 2015 The Android Open Source Project
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
package android.databinding;

import android.view.View;
import android.view.ViewStub;
import android.view.ViewStub.OnInflateListener;

/**
 * This class represents a ViewStub before and after inflation. Before inflation,
 * the ViewStub is accessible. After inflation, the ViewDataBinding is accessible
 * if the inflated View has bindings. If not, the root View will be accessible.
 */
public class ViewStubProxy {
    private ViewStub mViewStub;
    private ViewDataBinding mViewDataBinding;
    private View mRoot;
    private OnInflateListener mOnInflateListener;
    private ViewDataBinding mContainingBinding;

    private OnInflateListener mProxyListener = new OnInflateListener() {
        @Override
        public void onInflate(ViewStub stub, View inflated) {
            mRoot = inflated;
            mViewDataBinding = DataBindingUtil.bindTo(inflated, stub.getLayoutResource());
            mViewStub = null;

            if (mOnInflateListener != null) {
                mOnInflateListener.onInflate(stub, inflated);
                mOnInflateListener = null;
            }
            mContainingBinding.invalidateAll();
            mContainingBinding.executePendingBindings();
        }
    };

    public ViewStubProxy(ViewStub viewStub) {
        mViewStub = viewStub;
        mViewStub.setOnInflateListener(mProxyListener);
    }

    public void setContainingBinding(ViewDataBinding containingBinding) {
        mContainingBinding = containingBinding;
    }

    /**
     * @return <code>true</code> if the ViewStub has replaced itself with the inflated layout
     * or <code>false</code> if not.
     */
    public boolean isInflated() {
        return mRoot != null;
    }

    /**
     * @return The root View of the layout replacing the ViewStub once it has been inflated.
     * <code>null</code> is returned prior to inflation.
     */
    public View getRoot() {
        return mRoot;
    }

    /**
     * @return The data binding associated with the inflated layout once it has been inflated.
     * <code>null</code> prior to inflation or if there is no binding associated with the layout.
     */
    public ViewDataBinding getBinding() {
        return mViewDataBinding;
    }

    /**
     * @return The ViewStub in the layout or <code>null</code> if the ViewStub has been inflated.
     */
    public ViewStub getViewStub() {
        return mViewStub;
    }

    /**
     * Sets the {@link OnInflateListener} to be called when the ViewStub inflates. The proxy must
     * have an OnInflateListener, so <code>listener</code> will be called immediately after
     * the proxy's listener is called.
     *
     * @param listener The OnInflateListener to notify of successful inflation
     */
    public void setOnInflateListener(OnInflateListener listener) {
        if (mViewStub != null) {
            mOnInflateListener = listener;
        }
    }
}
