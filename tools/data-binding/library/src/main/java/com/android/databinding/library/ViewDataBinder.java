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

import android.view.View;

import java.lang.Override;
import java.lang.Runnable;
import java.lang.ref.WeakReference;

abstract public class ViewDataBinder {
    WeakReferencedListener[] mLocalFieldObservers;
    protected abstract boolean onFieldChange(int mLocalFieldId, Object object, int fieldId);
    public abstract boolean setVariable(int variableId, Object variable);
    public abstract void rebindDirty();
    private final View mRoot;

    private boolean mPendingRebind = false;
    private Runnable mRebindRunnable = new Runnable() {
        @Override
        public void run() {
            rebindDirty();
            mPendingRebind = false;
        }
    };

    public ViewDataBinder(View root, int localFieldCount) {
        mLocalFieldObservers = new WeakReferencedListener[localFieldCount];
        mRoot = root;
    }

    public View getRoot() {
        return mRoot;
    }

    private void handleFieldChange(int mLocalFieldId, Object object, int fieldId) {
        boolean result = onFieldChange(mLocalFieldId, object, fieldId);
        if (result) {
            requestRebind();
        }
    }

    protected boolean unregisterFrom(int localFieldId) {
        WeakReferencedListener listener = mLocalFieldObservers[localFieldId];
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
        mRoot.postOnAnimation(mRebindRunnable);
    }

    protected Object getObservedField(int localFieldId) {
        WeakReferencedListener listener = mLocalFieldObservers[localFieldId];
        if (listener == null) {
            return null;
        }
        return listener.getTarget();
    }

    protected boolean updateRegistration(int localFieldId, Observable observable) {
        if (observable == null) {
            return unregisterFrom(localFieldId);
        }
        WeakReferencedListener listener = mLocalFieldObservers[localFieldId];
        if (listener == null) {
            registerTo(localFieldId, observable);
            return true;
        }
        if (listener.getTarget() == observable) {
            return false;//nothing to do, same object
        }
        unregisterFrom(localFieldId);
        registerTo(localFieldId, observable);
        return true;
    }

    protected void registerTo(int localFieldId, Observable observable) {
        if (observable == null) {
            return;
        }
        WeakReferencedListener listener = mLocalFieldObservers[localFieldId];
        if (listener == null) {
            listener = new ObservableFieldListener(localFieldId);
            mLocalFieldObservers[localFieldId] = listener;
        }
        listener.setTarget(observable);
    }

    protected abstract class WeakReferencedListener implements OnPropertyChangedListener {
        WeakReference<Observable> mTarget;

        public WeakReferencedListener() {
        }

        public void setTarget(Observable observable) {
            if (observable != null) {
                mTarget = new WeakReference<>(observable);
                observable.addOnPropertyChangedListener(this);
            } else {
                mTarget = null;
            }
        }

        public boolean unregister() {
            Observable oldTarget = getTarget();
            if (oldTarget != null) {
                oldTarget.removeOnPropertyChangedListener(this);
            }
            mTarget = null;
            return oldTarget != null;
        }

        Observable getTarget() {
            return mTarget == null ? null : mTarget.get();
        }
    }

    protected class ObservableFieldListener extends WeakReferencedListener {
        final int mLocalFieldId;
        public ObservableFieldListener(int localFieldId) {
            mLocalFieldId = localFieldId;
        }

        @Override
        public void onPropertyChanged(Observable sender, int fieldId) {
            Observable obj = getTarget();
            if (obj == null) {
                return;//how come i live if it died ?
            }
            ViewDataBinder.this.handleFieldChange(mLocalFieldId, obj, fieldId);
        }
    }
}
