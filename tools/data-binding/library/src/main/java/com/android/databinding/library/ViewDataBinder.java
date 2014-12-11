package com.android.databinding.library;

import android.util.SparseIntArray;
import android.view.View;

import java.lang.Override;
import java.lang.Runnable;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    protected abstract class WeakReferencedListener implements ObservableListener {
        WeakReference<Observable> mTarget;

        public WeakReferencedListener() {
        }

        public void setTarget(Observable observable) {
            if (observable != null) {
                mTarget = new WeakReference<>(observable);
                observable.register(this);
            } else {
                mTarget = null;
            }
        }

        public boolean unregister() {
            Observable oldTarget = getTarget();
            if (oldTarget != null) {
                oldTarget.unRegister(this);
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
        public void onChange() {
            Observable obj = getTarget();
            if (obj == null) {
                return;//how come i live if it died ?
            }
            ViewDataBinder.this.handleFieldChange(mLocalFieldId, obj, 0);
        }

        @Override
        public void onChange(int fieldId) {
            Observable obj = getTarget();
            if (obj == null) {
                return;//how come i live if it died ?
            }
            ViewDataBinder.this.handleFieldChange(mLocalFieldId, obj, fieldId);
        }
    }
}
