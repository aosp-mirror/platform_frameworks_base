/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.app;

import android.content.ComponentCallbacks;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;

final class FragmentState implements Parcelable {
    static final String VIEW_STATE_TAG = "android:view_state";
    
    final String mClassName;
    final boolean mFromLayout;
    final int mSavedStateId;
    final int mFragmentId;
    final int mContainerId;
    final String mTag;
    final boolean mRetainInstance;
    
    Bundle mSavedFragmentState;
    
    Fragment mInstance;
    
    public FragmentState(Fragment frag) {
        mClassName = frag.getClass().getName();
        mFromLayout = frag.mFromLayout;
        mSavedStateId = frag.mSavedStateId;
        mFragmentId = frag.mFragmentId;
        mContainerId = frag.mContainerId;
        mTag = frag.mTag;
        mRetainInstance = frag.mRetainInstance;
    }
    
    public FragmentState(Parcel in) {
        mClassName = in.readString();
        mFromLayout = in.readInt() != 0;
        mSavedStateId = in.readInt();
        mFragmentId = in.readInt();
        mContainerId = in.readInt();
        mTag = in.readString();
        mRetainInstance = in.readInt() != 0;
        mSavedFragmentState = in.readBundle();
    }
    
    public Fragment instantiate(Activity activity) {
        if (mFromLayout) {
            return null;
        }
        
        if (mInstance != null) {
            return mInstance;
        }
        
        try {
            mInstance = Fragment.instantiate(activity, mClassName);
        } catch (Exception e) {
            throw new RuntimeException("Unable to restore fragment " + mClassName, e);
        }
        
        if (mSavedFragmentState != null) {
            mSavedFragmentState.setClassLoader(activity.getClassLoader());
            mInstance.mSavedFragmentState = mSavedFragmentState;
            mInstance.mSavedViewState
                    = mSavedFragmentState.getSparseParcelableArray(VIEW_STATE_TAG);
        }
        mInstance.mSavedStateId = mSavedStateId;
        mInstance.mFragmentId = mFragmentId;
        mInstance.mContainerId = mContainerId;
        mInstance.mTag = mTag;
        mInstance.mRetainInstance = mRetainInstance;
        
        return mInstance;
    }
    
    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mClassName);
        dest.writeInt(mFromLayout ? 1 : 0);
        dest.writeInt(mSavedStateId);
        dest.writeInt(mFragmentId);
        dest.writeInt(mContainerId);
        dest.writeString(mTag);
        dest.writeInt(mRetainInstance ? 1 : 0);
        dest.writeBundle(mSavedFragmentState);
    }
    
    public static final Parcelable.Creator<FragmentState> CREATOR
            = new Parcelable.Creator<FragmentState>() {
        public FragmentState createFromParcel(Parcel in) {
            return new FragmentState(in);
        }
        
        public FragmentState[] newArray(int size) {
            return new FragmentState[size];
        }
    };
}

/**
 * A Fragment is a piece of an application's user interface or behavior
 * that can be placed in an {@link Activity}.
 */
public class Fragment implements ComponentCallbacks {
    private static final Object[] sConstructorArgs = new Object[0];

    private static final Class[] sConstructorSignature = new Class[] { };

    private static final HashMap<String, Constructor> sConstructorMap =
            new HashMap<String, Constructor>();
    
    static final int INITIALIZING = 0;  // Not yet created.
    static final int CREATED = 1;       // Created.
    static final int CONTENT = 2;       // View hierarchy content available.
    static final int STARTED = 3;       // Created and started, not resumed.
    static final int RESUMED = 4;       // Created started and resumed.
    
    int mState = INITIALIZING;
    
    // When instantiated from saved state, this is the saved state.
    Bundle mSavedFragmentState;
    SparseArray<Parcelable> mSavedViewState;
    
    // Set to true if this fragment was instantiated from a layout file.
    boolean mFromLayout;
    
    // Number of active back stack entries this fragment is in.
    int mBackStackNesting;
    
    // Activity this fragment is attached to.
    Activity mActivity;
    
    // The optional identifier for this fragment -- either the container ID if it
    // was dynamically added to the view hierarchy, or the ID supplied in
    // layout.
    int mFragmentId;
    
    // When a fragment is being dynamically added to the view hierarchy, this
    // is the identifier of the parent container it is being added to.
    int mContainerId;
    
    // The optional named tag for this fragment -- usually used to find
    // fragments that are not part of the layout.
    String mTag;
    
    // If set this fragment would like its instance retained across
    // configuration changes.
    boolean mRetainInstance;
    
    // If set this fragment is being retained across the current config change.
    boolean mRetaining;
    
    // Used to verify that subclasses call through to super class.
    boolean mCalled;
    
    // The parent container of the fragment after dynamically added to UI.
    ViewGroup mContainer;
    
    // The View generated for this fragment.
    View mView;
    
    // Used for performing save state of fragments.
    int mSavedStateSeq = 0;
    int mSavedStateId;
    
    public Fragment() {
    }

    static Fragment instantiate(Activity activity, String fname)
            throws NoSuchMethodException, ClassNotFoundException,
            IllegalArgumentException, InstantiationException,
            IllegalAccessException, InvocationTargetException {
        Constructor constructor = sConstructorMap.get(fname);
        Class clazz = null;

        if (constructor == null) {
            // Class not found in the cache, see if it's real, and try to add it
            clazz = activity.getClassLoader().loadClass(fname);
            constructor = clazz.getConstructor(sConstructorSignature);
            sConstructorMap.put(fname, constructor);
        }
        return (Fragment)constructor.newInstance(sConstructorArgs);
    }
    
    void restoreViewState() {
        if (mSavedViewState != null) {
            mView.restoreHierarchyState(mSavedViewState);
            mSavedViewState = null;
        }
    }
    
    /**
     * Subclasses can not override equals().
     */
    @Override final public boolean equals(Object o) {
        return super.equals(o);
    }

    /**
     * Subclasses can not override hashCode().
     */
    @Override final public int hashCode() {
        return super.hashCode();
    }
    
    /**
     * Return the identifier this fragment is known by.  This is either
     * the android:id value supplied in a layout or the container view ID
     * supplied when adding the fragment.
     */
    public int getId() {
        return mFragmentId;
    }
    
    /**
     * Get the tag name of the fragment, if specified.
     */
    public String getTag() {
        return mTag;
    }
    
    /**
     * Return the Activity this fragment is currently associated with.
     */
    public Activity getActivity() {
        return mActivity;
    }
    
    /**
     * Control whether a fragment instance is retained across Activity
     * re-creation (such as from a configuration change).  This can only
     * be used with fragments not in the back stack.  If set, the fragment
     * lifecycle will be slightly different when an activity is recreated:
     * <ul>
     * <li> {@link #onDestroy()} will not be called (but {@link #onDetach()} still
     * will be, because the fragment is being detached from its current activity).
     * <li> {@link #onCreate(Bundle)} will not be called since the fragment
     * is not being re-created.
     * <li> {@link #onAttach(Activity)} and {@link #onReady(Bundle)} <b>will</b>
     * still be called.
     * </ul>
     */
    public void setRetainInstance(boolean retain) {
        mRetainInstance = retain;
    }
    
    public boolean getRetainInstance() {
        return mRetainInstance;
    }
    
    /**
     * Called when a fragment is being created as part of a view layout
     * inflation, typically from setting the content view of an activity.
     * 
     * @param activity The Activity that is inflating the fragment.
     * @param attrs The attributes at the tag where the fragment is
     * being created.
     */
    public void onInflate(Activity activity, AttributeSet attrs) {
        mCalled = true;
    }
    
    /**
     * Called when a fragment is first attached to its activity.
     * {@link #onCreate(Bundle)} will be called after this.
     */
    public void onAttach(Activity activity) {
        mCalled = true;
    }
    
    public Animation onCreateAnimation(int transit, boolean enter) {
        return null;
    }
    
    /**
     * Called to do initial creation of a fragment.  This is called after
     * {@link #onAttach(Activity)} and before {@link #onReady(Bundle)}.
     * @param savedInstanceState If the fragment is being re-created from
     * a previous saved state, this is the state.
     */
    public void onCreate(Bundle savedInstanceState) {
        mCalled = true;
    }
    
    /**
     * Called to have the fragment instantiate its user interface view.
     * This is optional, and non-graphical fragments can return null (which
     * is the default implementation).  This will be called between
     * {@link #onCreate(Bundle)} and {@link #onReady(Bundle)}.
     * 
     * @param inflater The LayoutInflater object that can be used to inflate
     * any views in the fragment,
     * @param container If non-null, this is the parent view that the fragment's
     * UI should be attached to.  The fragment should not add the view itself,
     * but this can be used to generate the LayoutParams of the view.
     * @param savedInstanceState If non-null, this fragment is being re-constructed
     * from a previous saved state as given here.
     * 
     * @return Return the View for the fragment's UI, or null.
     */
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        return null;
    }
    
    public View getView() {
        return mView;
    }
    
    /**
     * Called when the activity is ready for the fragment to run.  This is
     * most useful for fragments that use {@link #setRetainInstance(boolean)}
     * instance, as this tells the fragment when it is fully associated with
     * the new activity instance.  This is called after {@link #onCreate(Bundle)}
     * and before {@link #onStart()}.
     * 
     * @param savedInstanceState If the fragment is being re-created from
     * a previous saved state, this is the state.
     */
    public void onReady(Bundle savedInstanceState) {
        mCalled = true;
    }
    
    /**
     * Called when the Fragment is visible to the user.  This is generally
     * tied to {@link Activity#onStart() Activity.onStart} of the containing
     * Activity's lifecycle.
     */
    public void onStart() {
        mCalled = true;
    }
    
    /**
     * Called when the fragment is visible to the user and actively running.
     * This is generally
     * tied to {@link Activity#onResume() Activity.onResume} of the containing
     * Activity's lifecycle.
     */
    public void onResume() {
        mCalled = true;
    }
    
    public void onSaveInstanceState(Bundle outState) {
    }
    
    public void onConfigurationChanged(Configuration newConfig) {
        mCalled = true;
    }
    
    /**
     * Called when the Fragment is no longer resumed.  This is generally
     * tied to {@link Activity#onPause() Activity.onPause} of the containing
     * Activity's lifecycle.
     */
    public void onPause() {
        mCalled = true;
    }
    
    /**
     * Called when the Fragment is no longer started.  This is generally
     * tied to {@link Activity#onStop() Activity.onStop} of the containing
     * Activity's lifecycle.
     */
    public void onStop() {
        mCalled = true;
    }
    
    public void onLowMemory() {
        mCalled = true;
    }
    
    /**
     * Called when the fragment is no longer in use.  This is called
     * after {@link #onStop()} and before {@link #onDetach()}.
     */
    public void onDestroy() {
        mCalled = true;
    }
    
    /**
     * Called when the fragment is no longer attached to its activity.  This
     * is called after {@link #onDestroy()}.
     */
    public void onDetach() {
        mCalled = true;
    }
}
