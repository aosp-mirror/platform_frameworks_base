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

package android.databinding;

import android.annotation.TargetApi;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.View;
import android.view.View.OnAttachStateChangeListener;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;

public abstract class ViewDataBinding {

    /**
     * Instead of directly accessing Build.VERSION.SDK_INT, generated code uses this value so that
     * we can test API dependent behavior.
     */
    static int SDK_INT = VERSION.SDK_INT;

    /**
     * Prefix for android:tag on Views with binding. The root View and include tags will not have
     * android:tag attributes and will use ids instead.
     */
    public static final String BINDING_TAG_PREFIX = "bindingTag";

    // The length of BINDING_TAG_PREFIX prevents calling length repeatedly.
    private static final int BINDING_NUMBER_START = BINDING_TAG_PREFIX.length();

    /**
     * Method object extracted out to attach a listener to a bound Observable object.
     */
    private static final CreateWeakListener CREATE_PROPERTY_LISTENER = new CreateWeakListener() {
        @Override
        public WeakListener create(ViewDataBinding viewDataBinding, int localFieldId) {
            return new WeakPropertyListener(viewDataBinding, localFieldId);
        }
    };

    /**
     * Method object extracted out to attach a listener to a bound ObservableList object.
     */
    private static final CreateWeakListener CREATE_LIST_LISTENER = new CreateWeakListener() {
        @Override
        public WeakListener create(ViewDataBinding viewDataBinding, int localFieldId) {
            return new WeakListListener(viewDataBinding, localFieldId);
        }
    };

    /**
     * Method object extracted out to attach a listener to a bound ObservableMap object.
     */
    private static final CreateWeakListener CREATE_MAP_LISTENER = new CreateWeakListener() {
        @Override
        public WeakListener create(ViewDataBinding viewDataBinding, int localFieldId) {
            return new WeakMapListener(viewDataBinding, localFieldId);
        }
    };

    private static final OnAttachStateChangeListener ROOT_REATTACHED_LISTENER;

    static {
        if (VERSION.SDK_INT < VERSION_CODES.KITKAT) {
            ROOT_REATTACHED_LISTENER = null;
        } else {
            ROOT_REATTACHED_LISTENER = new OnAttachStateChangeListener() {
                @TargetApi(VERSION_CODES.KITKAT)
                @Override
                public void onViewAttachedToWindow(View v) {
                    // execute the pending bindings.
                    ViewDataBinding binding = (ViewDataBinding) v.getTag();
                    v.post(binding.mRebindRunnable);
                    v.removeOnAttachStateChangeListener(this);
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                }
            };
        }
    }

    /**
     * Runnable executed on animation heartbeat to rebind the dirty Views.
     */
    private Runnable mRebindRunnable = new Runnable() {
        @Override
        public void run() {
            if (mPendingRebind) {
                boolean rebind = true;
                if (VERSION.SDK_INT >= VERSION_CODES.KITKAT) {
                    rebind = mRoot.isAttachedToWindow();
                    if (!rebind) {
                        // Don't execute the pending bindings until the View
                        // is attached again.
                        mRoot.addOnAttachStateChangeListener(ROOT_REATTACHED_LISTENER);
                    }
                }
                if (rebind) {
                    mPendingRebind = false;
                    executePendingBindings();
                }
            }
        }
    };

    /**
     * Flag indicates that there are pending bindings that need to be reevaluated.
     */
    private boolean mPendingRebind = false;

    /**
     * The observed expressions.
     */
    private WeakListener[] mLocalFieldObservers;

    /**
     * The root View that this Binding is associated with.
     */
    private final View mRoot;

    protected ViewDataBinding(View root, int localFieldCount) {
        mLocalFieldObservers = new WeakListener[localFieldCount];
        this.mRoot = root;
        // TODO: When targeting ICS and above, use setTag(id, this) instead
        this.mRoot.setTag(this);
    }

    public static int getBuildSdkInt() {
        return SDK_INT;
    }

    /**
     * Called when an observed object changes. Sets the appropriate dirty flag if applicable.
     * @param localFieldId The index into mLocalFieldObservers that this Object resides in.
     * @param object The object that has changed.
     * @param fieldId The BR ID of the field being changed or _all if
     *                no specific field is being notified.
     * @return true if this change should cause a change to the UI.
     */
    protected abstract boolean onFieldChange(int localFieldId, Object object, int fieldId);

    public abstract boolean setVariable(int variableId, Object variable);

    /**
     * Evaluates the pending bindings, updating any Views that have expressions bound to
     * modified variables. This <b>must</b> be run on the UI thread.
     */
    public abstract void executePendingBindings();

    /**
     * Used internally to invalidate flags of included layouts.
     */
    public abstract void invalidateAll();

    /**
     * Removes binding listeners to expression variables.
     */
    public void unbind() {
        for (WeakListener weakListener : mLocalFieldObservers) {
            if (weakListener != null) {
                weakListener.unregister();
            }
        }
    }

    @Override
    protected void finalize() throws Throwable {
        unbind();
    }

    /**
     * Returns the outermost View in the layout file associated with the Binding.
     * @return the outermost View in the layout file associated with the Binding.
     */
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
        WeakListener listener = mLocalFieldObservers[localFieldId];
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
        if (VERSION.SDK_INT >= VERSION_CODES.JELLY_BEAN) {
            mRoot.postOnAnimation(mRebindRunnable);
        } else {
            mRoot.post(mRebindRunnable);
        }
    }

    protected Object getObservedField(int localFieldId) {
        WeakListener listener = mLocalFieldObservers[localFieldId];
        if (listener == null) {
            return null;
        }
        return listener.getTarget();
    }

    private boolean updateRegistration(int localFieldId, Object observable,
            CreateWeakListener listenerCreator) {
        if (observable == null) {
            return unregisterFrom(localFieldId);
        }
        WeakListener listener = mLocalFieldObservers[localFieldId];
        if (listener == null) {
            registerTo(localFieldId, observable, listenerCreator);
            return true;
        }
        if (listener.getTarget() == observable) {
            return false;//nothing to do, same object
        }
        unregisterFrom(localFieldId);
        registerTo(localFieldId, observable, listenerCreator);
        return true;
    }

    protected boolean updateRegistration(int localFieldId, Observable observable) {
        return updateRegistration(localFieldId, observable, CREATE_PROPERTY_LISTENER);
    }

    protected boolean updateRegistration(int localFieldId, ObservableList observable) {
        return updateRegistration(localFieldId, observable, CREATE_LIST_LISTENER);
    }

    protected boolean updateRegistration(int localFieldId, ObservableMap observable) {
        return updateRegistration(localFieldId, observable, CREATE_MAP_LISTENER);
    }

    protected void registerTo(int localFieldId, Object observable,
            CreateWeakListener listenerCreator) {
        if (observable == null) {
            return;
        }
        WeakListener listener = mLocalFieldObservers[localFieldId];
        if (listener == null) {
            listener = listenerCreator.create(this, localFieldId);
            mLocalFieldObservers[localFieldId] = listener;
        }
        listener.setTarget(observable);
    }

    /**
     * Walk all children of root and assign tagged Views to associated indices in views.
     *
     * @param root The base of the View hierarchy to walk.
     * @param views An array of all Views with binding expressions and all Views with IDs. This
     *              will start empty and will contain the found Views when this method completes.
     * @param includes A mapping of include tag IDs to the index into the views array.
     * @param viewsWithIds A mapping of views with IDs but without expressions to the index
     *                     into the views array.
     */
    protected static void mapTaggedChildViews(View root, View[] views, SparseIntArray includes,
            SparseIntArray viewsWithIds) {
        if (root instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup) root;
            for (int i = viewGroup.getChildCount() - 1; i >= 0; i--) {
                mapTaggedViews(viewGroup.getChildAt(i), views, includes, viewsWithIds);
            }
        }
    }

    private static void mapTaggedViews(View view, View[] views, SparseIntArray includes,
            SparseIntArray viewsWithIds) {
        boolean visitChildren = true;
        String tag = (String) view.getTag();
        int id;
        if (tag != null && tag.startsWith(BINDING_TAG_PREFIX)) {
            int tagIndex = parseTagInt(tag);
            views[tagIndex] = view;
        } else if ((id = view.getId()) > 0) {
            int index;
            if (viewsWithIds != null && (index = viewsWithIds.get(id, -1)) >= 0) {
                views[index] = view;
            } else if (includes != null && (index = includes.get(id, -1)) >= 0) {
                views[index] = view;
                visitChildren = false;
            }
        }
        if (visitChildren) {
            mapTaggedChildViews(view, views, includes, viewsWithIds);
        }
    }

    /**
     * Parse the tag without creating a new String object. This is fast and assumes the
     * tag is in the correct format.
     * @param str The tag string.
     * @return The binding tag number parsed from the tag string.
     */
    private static int parseTagInt(String str) {
        final int end = str.length();
        int val = 0;
        for (int i = BINDING_NUMBER_START; i < end; i++) {
            val *= 10;
            char c = str.charAt(i);
            val += (c - '0');
        }
        return val;
    }

    private static abstract class WeakListener<T> {
        private final WeakReference<ViewDataBinding> mBinder;
        protected final int mLocalFieldId;
        private T mTarget;

        public WeakListener(ViewDataBinding binder, int localFieldId) {
            mBinder = new WeakReference<ViewDataBinding>(binder);
            mLocalFieldId = localFieldId;
        }

        public void setTarget(T object) {
            unregister();
            mTarget = object;
            if (mTarget != null) {
                addListener(mTarget);
            }
        }

        public boolean unregister() {
            boolean unregistered = false;
            if (mTarget != null) {
                removeListener(mTarget);
                unregistered = true;
            }
            mTarget = null;
            return unregistered;
        }

        public T getTarget() {
            return mTarget;
        }

        protected ViewDataBinding getBinder() {
            ViewDataBinding binder = mBinder.get();
            if (binder == null) {
                unregister(); // The binder is dead
            }
            return binder;
        }

        protected abstract void addListener(T target);
        protected abstract void removeListener(T target);
    }

    private static class WeakPropertyListener extends WeakListener<Observable>
            implements OnPropertyChangedListener {
        public WeakPropertyListener(ViewDataBinding binder, int localFieldId) {
            super(binder, localFieldId);
        }

        @Override
        protected void addListener(Observable target) {
            target.addOnPropertyChangedListener(this);
        }

        @Override
        protected void removeListener(Observable target) {
            target.removeOnPropertyChangedListener(this);
        }

        @Override
        public void onPropertyChanged(Observable sender, int fieldId) {
            ViewDataBinding binder = getBinder();
            if (binder == null) {
                return;
            }
            Observable obj = getTarget();
            if (obj != sender) {
                return; // notification from the wrong object?
            }
            binder.handleFieldChange(mLocalFieldId, sender, fieldId);
        }
    }

    private static class WeakListListener extends WeakListener<ObservableList>
            implements OnListChangedListener {

        public WeakListListener(ViewDataBinding binder, int localFieldId) {
            super(binder, localFieldId);
        }

        @Override
        public void onChanged() {
            ViewDataBinding binder = getBinder();
            if (binder == null) {
                return;
            }
            ObservableList target = getTarget();
            if (target == null) {
                return; // We don't expect any notifications from null targets
            }
            binder.handleFieldChange(mLocalFieldId, target, 0);
        }

        @Override
        public void onItemRangeChanged(int positionStart, int itemCount) {
            onChanged();
        }

        @Override
        public void onItemRangeInserted(int positionStart, int itemCount) {
            onChanged();
        }

        @Override
        public void onItemRangeMoved(int fromPosition, int toPosition, int itemCount) {
            onChanged();
        }

        @Override
        public void onItemRangeRemoved(int positionStart, int itemCount) {
            onChanged();
        }

        @Override
        protected void addListener(ObservableList target) {
            target.addOnListChangedListener(this);
        }

        @Override
        protected void removeListener(ObservableList target) {
            target.removeOnListChangedListener(this);
        }
    }

    private static class WeakMapListener extends WeakListener<ObservableMap>
            implements OnMapChangedListener {
        public WeakMapListener(ViewDataBinding binder, int localFieldId) {
            super(binder, localFieldId);
        }

        @Override
        protected void addListener(ObservableMap target) {
            target.addOnMapChangedListener(this);
        }

        @Override
        protected void removeListener(ObservableMap target) {
            target.removeOnMapChangedListener(this);
        }

        @Override
        public void onMapChanged(ObservableMap sender, Object key) {
            ViewDataBinding binder = getBinder();
            if (binder == null || sender != getTarget()) {
                return;
            }
            binder.handleFieldChange(mLocalFieldId, sender, 0);
        }
    }

    private interface CreateWeakListener {
        WeakListener create(ViewDataBinding viewDataBinding, int localFieldId);
    }
}
