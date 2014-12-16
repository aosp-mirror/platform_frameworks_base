/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.databinding.library;

import android.annotation.TargetApi;
import android.os.Build;
import android.view.View;

import java.lang.Override;
import java.lang.Runnable;
import java.lang.ref.WeakReference;

/**
 * BindingContext is the base class used for data binding to an XML layout. Each
 * layout will generate a subclass of BindingContext in the form LayoutNameBindingContext,
 * where LayoutName is layout/layout_name.xml. All layout files with the same name
 * will share the same subclass of BindingContext. For example, if your application
 * contains:
 * <pre>
 * layout/my_layout.xml
 * layout-land/my_layout.xml
 * layout-port/my_layout.xml
 * </pre>
 * a class with the name MyLayoutBindingContext will be generated that handles binding
 * for all files.
 *
 * <p>Use the variable setters and getters in the BindingContext subclass to assign values to
 * the bound Views.</p>
 */
public abstract class BindingContext {

    /**
     * The root View as returned from the layout inflation.
     */
    public final View root;

    private final WeakReferencedListener[] mChangeListeners;
    private boolean mPendingRebind = false;

    private Runnable mRebindRunnable = new Runnable() {
        @Override
        public void run() {
            rebindDirty();
            mPendingRebind = false;
        }
    };

    public BindingContext(View root, int localFieldCount) {
        mChangeListeners = new WeakReferencedListener[localFieldCount];
        this.root = root;

        if (root != null) {
            // TODO: set a resource tag in 11+
            root.setTag(this);
        }
    }

    protected abstract boolean onFieldChange(int mLocalFieldId, Object object, int fieldId);
    protected abstract void rebindDirty();

    private void handleFieldChange(int mLocalFieldId, Object object, int fieldId) {
        boolean result = onFieldChange(mLocalFieldId, object, fieldId);
        if (result) {
            requestRebind();
        }
    }

    protected final boolean unregisterFrom(int localFieldId) {
        WeakReferencedListener listener = mChangeListeners[localFieldId];
        if (listener != null) {
            return listener.unregister();
        }
        return false;
    }

    protected void requestRebind() {
        if (mPendingRebind) {
            return;
        }
        mPendingRebind = true;
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.JELLY_BEAN) {
            postJellyBean();
        } else {
            postIceCreamSandwich();
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private void postJellyBean() {
        root.postOnAnimation(mRebindRunnable);
    }

    private void postIceCreamSandwich() {
        root.post(mRebindRunnable);
    }

    protected Object getObservedField(int localFieldId) {
        WeakReferencedListener listener = mChangeListeners[localFieldId];
        if (listener == null) {
            return null;
        }
        return listener.getTarget();
    }

    protected boolean updateRegistration(int localFieldId, Observable observable) {
        WeakReferencedListener listener = mChangeListeners[localFieldId];
        if (listener == null) {
            if (observable == null) {
                return false;
            } else {
                listener = new WeakReferencedListener(this, localFieldId);
                mChangeListeners[localFieldId] = listener;
            }
        }
        return listener.register(observable);
    }

    protected void registerTo(int localFieldId, Observable observable) {
        if (observable == null) {
            return;
        }
        WeakReferencedListener listener = mChangeListeners[localFieldId];
        if (listener == null) {
            listener = new WeakReferencedListener(this, localFieldId);
            mChangeListeners[localFieldId] = listener;
        }
        listener.register(observable);
    }

    private static class WeakReferencedListener implements OnPropertyChangedListener {
        private final WeakReference<BindingContext> mBindingContext;
        private final int mFieldId;
        private Observable mTarget;

        public WeakReferencedListener(BindingContext bindingContext, int fieldId) {
            mBindingContext = new WeakReference<>(bindingContext);
            mFieldId = fieldId;
        }

        @Override
        public void onPropertyChanged(int propertyId) {
            BindingContext bindingContext = mBindingContext.get();
            if (bindingContext != null) {
                bindingContext.handleFieldChange(mFieldId, mTarget, propertyId);
            }
        }

        public boolean unregister() {
            if (mTarget != null) {
                mTarget.removeListener(this);
                mTarget = null;
                return true;
            } else {
                return false;
            }
        }

        public boolean register(Observable target) {
            if (mTarget == target) {
                return false;
            } else if (mTarget != null) {
                mTarget.removeListener(this);
            }
            mTarget = target;
            if (target != null) {
                target.addListener(this);
            }
            return true;
        }

        public Observable getTarget() {
            return mTarget;
        }
    }

}
