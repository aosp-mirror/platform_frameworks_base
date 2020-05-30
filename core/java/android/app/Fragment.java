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

import android.animation.Animator;
import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.os.Build;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.transition.Transition;
import android.transition.TransitionInflater;
import android.transition.TransitionSet;
import android.util.AndroidRuntimeException;
import android.util.ArrayMap;
import android.util.AttributeSet;
import android.util.DebugUtils;
import android.util.SparseArray;
import android.util.SuperNotCalledException;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnCreateContextMenuListener;
import android.view.ViewGroup;
import android.widget.AdapterView;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;

/**
 * A Fragment is a piece of an application's user interface or behavior
 * that can be placed in an {@link Activity}.  Interaction with fragments
 * is done through {@link FragmentManager}, which can be obtained via
 * {@link Activity#getFragmentManager() Activity.getFragmentManager()} and
 * {@link Fragment#getFragmentManager() Fragment.getFragmentManager()}.
 *
 * <p>The Fragment class can be used many ways to achieve a wide variety of
 * results. In its core, it represents a particular operation or interface
 * that is running within a larger {@link Activity}.  A Fragment is closely
 * tied to the Activity it is in, and can not be used apart from one.  Though
 * Fragment defines its own lifecycle, that lifecycle is dependent on its
 * activity: if the activity is stopped, no fragments inside of it can be
 * started; when the activity is destroyed, all fragments will be destroyed.
 *
 * <p>All subclasses of Fragment must include a public no-argument constructor.
 * The framework will often re-instantiate a fragment class when needed,
 * in particular during state restore, and needs to be able to find this
 * constructor to instantiate it.  If the no-argument constructor is not
 * available, a runtime exception will occur in some cases during state
 * restore.
 *
 * <p>Topics covered here:
 * <ol>
 * <li><a href="#OlderPlatforms">Older Platforms</a>
 * <li><a href="#Lifecycle">Lifecycle</a>
 * <li><a href="#Layout">Layout</a>
 * <li><a href="#BackStack">Back Stack</a>
 * </ol>
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about using fragments, read the
 * <a href="{@docRoot}guide/components/fragments.html">Fragments</a> developer guide.</p>
 * </div>
 *
 * <a name="OlderPlatforms"></a>
 * <h3>Older Platforms</h3>
 *
 * While the Fragment API was introduced in
 * {@link android.os.Build.VERSION_CODES#HONEYCOMB}, a version of the API
 * at is also available for use on older platforms through
 * {@link android.support.v4.app.FragmentActivity}.  See the blog post
 * <a href="http://android-developers.blogspot.com/2011/03/fragments-for-all.html">
 * Fragments For All</a> for more details.
 *
 * <a name="Lifecycle"></a>
 * <h3>Lifecycle</h3>
 *
 * <p>Though a Fragment's lifecycle is tied to its owning activity, it has
 * its own wrinkle on the standard activity lifecycle.  It includes basic
 * activity lifecycle methods such as {@link #onResume}, but also important
 * are methods related to interactions with the activity and UI generation.
 *
 * <p>The core series of lifecycle methods that are called to bring a fragment
 * up to resumed state (interacting with the user) are:
 *
 * <ol>
 * <li> {@link #onAttach} called once the fragment is associated with its activity.
 * <li> {@link #onCreate} called to do initial creation of the fragment.
 * <li> {@link #onCreateView} creates and returns the view hierarchy associated
 * with the fragment.
 * <li> {@link #onActivityCreated} tells the fragment that its activity has
 * completed its own {@link Activity#onCreate Activity.onCreate()}.
 * <li> {@link #onViewStateRestored} tells the fragment that all of the saved
 * state of its view hierarchy has been restored.
 * <li> {@link #onStart} makes the fragment visible to the user (based on its
 * containing activity being started).
 * <li> {@link #onResume} makes the fragment begin interacting with the user
 * (based on its containing activity being resumed).
 * </ol>
 *
 * <p>As a fragment is no longer being used, it goes through a reverse
 * series of callbacks:
 *
 * <ol>
 * <li> {@link #onPause} fragment is no longer interacting with the user either
 * because its activity is being paused or a fragment operation is modifying it
 * in the activity.
 * <li> {@link #onStop} fragment is no longer visible to the user either
 * because its activity is being stopped or a fragment operation is modifying it
 * in the activity.
 * <li> {@link #onDestroyView} allows the fragment to clean up resources
 * associated with its View.
 * <li> {@link #onDestroy} called to do final cleanup of the fragment's state.
 * <li> {@link #onDetach} called immediately prior to the fragment no longer
 * being associated with its activity.
 * </ol>
 *
 * <a name="Layout"></a>
 * <h3>Layout</h3>
 *
 * <p>Fragments can be used as part of your application's layout, allowing
 * you to better modularize your code and more easily adjust your user
 * interface to the screen it is running on.  As an example, we can look
 * at a simple program consisting of a list of items, and display of the
 * details of each item.</p>
 *
 * <p>An activity's layout XML can include <code>&lt;fragment&gt;</code> tags
 * to embed fragment instances inside of the layout.  For example, here is
 * a simple layout that embeds one fragment:</p>
 *
 * {@sample development/samples/ApiDemos/res/layout/fragment_layout.xml layout}
 *
 * <p>The layout is installed in the activity in the normal way:</p>
 *
 * {@sample development/samples/ApiDemos/src/com/example/android/apis/app/FragmentLayout.java
 *      main}
 *
 * <p>The titles fragment, showing a list of titles, is fairly simple, relying
 * on {@link ListFragment} for most of its work.  Note the implementation of
 * clicking an item: depending on the current activity's layout, it can either
 * create and display a new fragment to show the details in-place (more about
 * this later), or start a new activity to show the details.</p>
 *
 * {@sample development/samples/ApiDemos/src/com/example/android/apis/app/FragmentLayout.java
 *      titles}
 *
 * <p>The details fragment showing the contents of a selected item just
 * displays a string of text based on an index of a string array built in to
 * the app:</p>
 *
 * {@sample development/samples/ApiDemos/src/com/example/android/apis/app/FragmentLayout.java
 *      details}
 *
 * <p>In this case when the user clicks on a title, there is no details
 * container in the current activity, so the titles fragment's click code will
 * launch a new activity to display the details fragment:</p>
 *
 * {@sample development/samples/ApiDemos/src/com/example/android/apis/app/FragmentLayout.java
 *      details_activity}
 *
 * <p>However the screen may be large enough to show both the list of titles
 * and details about the currently selected title.  To use such a layout on
 * a landscape screen, this alternative layout can be placed under layout-land:</p>
 *
 * {@sample development/samples/ApiDemos/res/layout-land/fragment_layout.xml layout}
 *
 * <p>Note how the prior code will adjust to this alternative UI flow: the titles
 * fragment will now embed the details fragment inside of this activity, and the
 * details activity will finish itself if it is running in a configuration
 * where the details can be shown in-place.
 *
 * <p>When a configuration change causes the activity hosting these fragments
 * to restart, its new instance may use a different layout that doesn't
 * include the same fragments as the previous layout.  In this case all of
 * the previous fragments will still be instantiated and running in the new
 * instance.  However, any that are no longer associated with a &lt;fragment&gt;
 * tag in the view hierarchy will not have their content view created
 * and will return false from {@link #isInLayout}.  (The code here also shows
 * how you can determine if a fragment placed in a container is no longer
 * running in a layout with that container and avoid creating its view hierarchy
 * in that case.)
 *
 * <p>The attributes of the &lt;fragment&gt; tag are used to control the
 * LayoutParams provided when attaching the fragment's view to the parent
 * container.  They can also be parsed by the fragment in {@link #onInflate}
 * as parameters.
 *
 * <p>The fragment being instantiated must have some kind of unique identifier
 * so that it can be re-associated with a previous instance if the parent
 * activity needs to be destroyed and recreated.  This can be provided these
 * ways:
 *
 * <ul>
 * <li>If nothing is explicitly supplied, the view ID of the container will
 * be used.
 * <li><code>android:tag</code> can be used in &lt;fragment&gt; to provide
 * a specific tag name for the fragment.
 * <li><code>android:id</code> can be used in &lt;fragment&gt; to provide
 * a specific identifier for the fragment.
 * </ul>
 *
 * <a name="BackStack"></a>
 * <h3>Back Stack</h3>
 *
 * <p>The transaction in which fragments are modified can be placed on an
 * internal back-stack of the owning activity.  When the user presses back
 * in the activity, any transactions on the back stack are popped off before
 * the activity itself is finished.
 *
 * <p>For example, consider this simple fragment that is instantiated with
 * an integer argument and displays that in a TextView in its UI:</p>
 *
 * {@sample development/samples/ApiDemos/src/com/example/android/apis/app/FragmentStack.java
 *      fragment}
 *
 * <p>A function that creates a new instance of the fragment, replacing
 * whatever current fragment instance is being shown and pushing that change
 * on to the back stack could be written as:
 *
 * {@sample development/samples/ApiDemos/src/com/example/android/apis/app/FragmentStack.java
 *      add_stack}
 *
 * <p>After each call to this function, a new entry is on the stack, and
 * pressing back will pop it to return the user to whatever previous state
 * the activity UI was in.
 *
 * @deprecated Use the <a href="{@docRoot}tools/extras/support-library.html">Support Library</a>
 *      {@link android.support.v4.app.Fragment} for consistent behavior across all devices
 *      and access to <a href="{@docRoot}topic/libraries/architecture/lifecycle.html">Lifecycle</a>.
 */
@Deprecated
public class Fragment implements ComponentCallbacks2, OnCreateContextMenuListener {
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private static final ArrayMap<String, Class<?>> sClassMap =
            new ArrayMap<String, Class<?>>();

    static final int INVALID_STATE = -1;   // Invalid state used as a null value.
    static final int INITIALIZING = 0;     // Not yet created.
    static final int CREATED = 1;          // Created.
    static final int ACTIVITY_CREATED = 2; // The activity has finished its creation.
    static final int STOPPED = 3;          // Fully created, not started.
    static final int STARTED = 4;          // Created and started, not resumed.
    static final int RESUMED = 5;          // Created started and resumed.

    private static final Transition USE_DEFAULT_TRANSITION = new TransitionSet();

    int mState = INITIALIZING;

    // When instantiated from saved state, this is the saved state.
    @UnsupportedAppUsage
    Bundle mSavedFragmentState;
    SparseArray<Parcelable> mSavedViewState;

    // Index into active fragment array.
    @UnsupportedAppUsage
    int mIndex = -1;

    // Internal unique name for this fragment;
    @UnsupportedAppUsage
    String mWho;

    // Construction arguments;
    Bundle mArguments;

    // Target fragment.
    Fragment mTarget;

    // For use when retaining a fragment: this is the index of the last mTarget.
    int mTargetIndex = -1;

    // Target request code.
    int mTargetRequestCode;

    // True if the fragment is in the list of added fragments.
    @UnsupportedAppUsage
    boolean mAdded;

    // If set this fragment is being removed from its activity.
    boolean mRemoving;

    // Set to true if this fragment was instantiated from a layout file.
    boolean mFromLayout;

    // Set to true when the view has actually been inflated in its layout.
    boolean mInLayout;

    // True if this fragment has been restored from previously saved state.
    boolean mRestored;

    // True if performCreateView has been called and a matching call to performDestroyView
    // has not yet happened.
    boolean mPerformedCreateView;

    // Number of active back stack entries this fragment is in.
    int mBackStackNesting;

    // The fragment manager we are associated with.  Set as soon as the
    // fragment is used in a transaction; cleared after it has been removed
    // from all transactions.
    @UnsupportedAppUsage
    FragmentManagerImpl mFragmentManager;

    // Activity this fragment is attached to.
    @UnsupportedAppUsage
    FragmentHostCallback mHost;

    // Private fragment manager for child fragments inside of this one.
    @UnsupportedAppUsage
    FragmentManagerImpl mChildFragmentManager;

    // For use when restoring fragment state and descendant fragments are retained.
    // This state is set by FragmentState.instantiate and cleared in onCreate.
    FragmentManagerNonConfig mChildNonConfig;

    // If this Fragment is contained in another Fragment, this is that container.
    Fragment mParentFragment;

    // The optional identifier for this fragment -- either the container ID if it
    // was dynamically added to the view hierarchy, or the ID supplied in
    // layout.
    @UnsupportedAppUsage
    int mFragmentId;

    // When a fragment is being dynamically added to the view hierarchy, this
    // is the identifier of the parent container it is being added to.
    int mContainerId;

    // The optional named tag for this fragment -- usually used to find
    // fragments that are not part of the layout.
    String mTag;

    // Set to true when the app has requested that this fragment be hidden
    // from the user.
    boolean mHidden;

    // Set to true when the app has requested that this fragment be detached.
    boolean mDetached;

    // If set this fragment would like its instance retained across
    // configuration changes.
    boolean mRetainInstance;

    // If set this fragment is being retained across the current config change.
    boolean mRetaining;

    // If set this fragment has menu items to contribute.
    boolean mHasMenu;

    // Set to true to allow the fragment's menu to be shown.
    boolean mMenuVisible = true;

    // Used to verify that subclasses call through to super class.
    boolean mCalled;

    // The parent container of the fragment after dynamically added to UI.
    ViewGroup mContainer;

    // The View generated for this fragment.
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    View mView;

    // Whether this fragment should defer starting until after other fragments
    // have been started and their loaders are finished.
    boolean mDeferStart;

    // Hint provided by the app that this fragment is currently visible to the user.
    boolean mUserVisibleHint = true;

    LoaderManagerImpl mLoaderManager;
    @UnsupportedAppUsage
    boolean mLoadersStarted;
    boolean mCheckedForLoaderManager;

    // The animation and transition information for the fragment. This will be null
    // unless the elements are explicitly accessed and should remain null for Fragments
    // without Views.
    AnimationInfo mAnimationInfo;

    // True if the View was added, and its animation has yet to be run. This could
    // also indicate that the fragment view hasn't been made visible, even if there is no
    // animation for this fragment.
    boolean mIsNewlyAdded;

    // True if mHidden has been changed and the animation should be scheduled.
    boolean mHiddenChanged;

    // The cached value from onGetLayoutInflater(Bundle) that will be returned from
    // getLayoutInflater()
    LayoutInflater mLayoutInflater;

    // Keep track of whether or not this Fragment has run performCreate(). Retained instance
    // fragments can have mRetaining set to true without going through creation, so we must
    // track it separately.
    boolean mIsCreated;

    /**
     * State information that has been retrieved from a fragment instance
     * through {@link FragmentManager#saveFragmentInstanceState(Fragment)
     * FragmentManager.saveFragmentInstanceState}.
     *
     * @deprecated Use {@link android.support.v4.app.Fragment.SavedState}
     */
    @Deprecated
    public static class SavedState implements Parcelable {
        final Bundle mState;

        SavedState(Bundle state) {
            mState = state;
        }

        SavedState(Parcel in, ClassLoader loader) {
            mState = in.readBundle();
            if (loader != null && mState != null) {
                mState.setClassLoader(loader);
            }
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeBundle(mState);
        }

        public static final Parcelable.ClassLoaderCreator<SavedState> CREATOR
                = new Parcelable.ClassLoaderCreator<SavedState>() {
            public SavedState createFromParcel(Parcel in) {
                return new SavedState(in, null);
            }

            public SavedState createFromParcel(Parcel in, ClassLoader loader) {
                return new SavedState(in, loader);
            }

            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }
        };
    }

    /**
     * Thrown by {@link Fragment#instantiate(Context, String, Bundle)} when
     * there is an instantiation failure.
     *
     * @deprecated Use {@link android.support.v4.app.Fragment.InstantiationException}
     */
    @Deprecated
    static public class InstantiationException extends AndroidRuntimeException {
        public InstantiationException(String msg, Exception cause) {
            super(msg, cause);
        }
    }

    /**
     * Default constructor.  <strong>Every</strong> fragment must have an
     * empty constructor, so it can be instantiated when restoring its
     * activity's state.  It is strongly recommended that subclasses do not
     * have other constructors with parameters, since these constructors
     * will not be called when the fragment is re-instantiated; instead,
     * arguments can be supplied by the caller with {@link #setArguments}
     * and later retrieved by the Fragment with {@link #getArguments}.
     *
     * <p>Applications should generally not implement a constructor. Prefer
     * {@link #onAttach(Context)} instead. It is the first place application code can run where
     * the fragment is ready to be used - the point where the fragment is actually associated with
     * its context. Some applications may also want to implement {@link #onInflate} to retrieve
     * attributes from a layout resource, although note this happens when the fragment is attached.
     */
    public Fragment() {
    }

    /**
     * Like {@link #instantiate(Context, String, Bundle)} but with a null
     * argument Bundle.
     */
    public static Fragment instantiate(Context context, String fname) {
        return instantiate(context, fname, null);
    }

    /**
     * Create a new instance of a Fragment with the given class name.  This is
     * the same as calling its empty constructor.
     *
     * @param context The calling context being used to instantiate the fragment.
     * This is currently just used to get its ClassLoader.
     * @param fname The class name of the fragment to instantiate.
     * @param args Bundle of arguments to supply to the fragment, which it
     * can retrieve with {@link #getArguments()}.  May be null.
     * @return Returns a new fragment instance.
     * @throws InstantiationException If there is a failure in instantiating
     * the given fragment class.  This is a runtime exception; it is not
     * normally expected to happen.
     */
    public static Fragment instantiate(Context context, String fname, @Nullable Bundle args) {
        try {
            Class<?> clazz = sClassMap.get(fname);
            if (clazz == null) {
                // Class not found in the cache, see if it's real, and try to add it
                clazz = context.getClassLoader().loadClass(fname);
                if (!Fragment.class.isAssignableFrom(clazz)) {
                    throw new InstantiationException("Trying to instantiate a class " + fname
                            + " that is not a Fragment", new ClassCastException());
                }
                sClassMap.put(fname, clazz);
            }
            Fragment f = (Fragment) clazz.getConstructor().newInstance();
            if (args != null) {
                args.setClassLoader(f.getClass().getClassLoader());
                f.setArguments(args);
            }
            return f;
        } catch (ClassNotFoundException e) {
            throw new InstantiationException("Unable to instantiate fragment " + fname
                    + ": make sure class name exists, is public, and has an"
                    + " empty constructor that is public", e);
        } catch (java.lang.InstantiationException e) {
            throw new InstantiationException("Unable to instantiate fragment " + fname
                    + ": make sure class name exists, is public, and has an"
                    + " empty constructor that is public", e);
        } catch (IllegalAccessException e) {
            throw new InstantiationException("Unable to instantiate fragment " + fname
                    + ": make sure class name exists, is public, and has an"
                    + " empty constructor that is public", e);
        } catch (NoSuchMethodException e) {
            throw new InstantiationException("Unable to instantiate fragment " + fname
                    + ": could not find Fragment constructor", e);
        } catch (InvocationTargetException e) {
            throw new InstantiationException("Unable to instantiate fragment " + fname
                    + ": calling Fragment constructor caused an exception", e);
        }
    }

    final void restoreViewState(Bundle savedInstanceState) {
        if (mSavedViewState != null) {
            mView.restoreHierarchyState(mSavedViewState);
            mSavedViewState = null;
        }
        mCalled = false;
        onViewStateRestored(savedInstanceState);
        if (!mCalled) {
            throw new SuperNotCalledException("Fragment " + this
                    + " did not call through to super.onViewStateRestored()");
        }
    }

    final void setIndex(int index, Fragment parent) {
        mIndex = index;
        if (parent != null) {
            mWho = parent.mWho + ":" + mIndex;
        } else {
            mWho = "android:fragment:" + mIndex;
        }
    }

    final boolean isInBackStack() {
        return mBackStackNesting > 0;
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

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(128);
        DebugUtils.buildShortClassTag(this, sb);
        if (mIndex >= 0) {
            sb.append(" #");
            sb.append(mIndex);
        }
        if (mFragmentId != 0) {
            sb.append(" id=0x");
            sb.append(Integer.toHexString(mFragmentId));
        }
        if (mTag != null) {
            sb.append(" ");
            sb.append(mTag);
        }
        sb.append('}');
        return sb.toString();
    }

    /**
     * Return the identifier this fragment is known by.  This is either
     * the android:id value supplied in a layout or the container view ID
     * supplied when adding the fragment.
     */
    final public int getId() {
        return mFragmentId;
    }

    /**
     * Get the tag name of the fragment, if specified.
     */
    final public String getTag() {
        return mTag;
    }

    /**
     * Supply the construction arguments for this fragment.
     * The arguments supplied here will be retained across fragment destroy and
     * creation.
     *
     * <p>This method cannot be called if the fragment is added to a FragmentManager and
     * if {@link #isStateSaved()} would return true. Prior to {@link Build.VERSION_CODES#O},
     * this method may only be called if the fragment has not yet been added to a FragmentManager.
     * </p>
     */
    public void setArguments(Bundle args) {
        // The isStateSaved requirement below was only added in Android O and is compatible
        // because it loosens previous requirements rather than making them more strict.
        // See method javadoc.
        if (mIndex >= 0 && isStateSaved()) {
            throw new IllegalStateException("Fragment already active");
        }
        mArguments = args;
    }

    /**
     * Return the arguments supplied to {@link #setArguments}, if any.
     */
    final public Bundle getArguments() {
        return mArguments;
    }

    /**
     * Returns true if this fragment is added and its state has already been saved
     * by its host. Any operations that would change saved state should not be performed
     * if this method returns true, and some operations such as {@link #setArguments(Bundle)}
     * will fail.
     *
     * @return true if this fragment's state has already been saved by its host
     */
    public final boolean isStateSaved() {
        if (mFragmentManager == null) {
            return false;
        }
        return mFragmentManager.isStateSaved();
    }

    /**
     * Set the initial saved state that this Fragment should restore itself
     * from when first being constructed, as returned by
     * {@link FragmentManager#saveFragmentInstanceState(Fragment)
     * FragmentManager.saveFragmentInstanceState}.
     *
     * @param state The state the fragment should be restored from.
     */
    public void setInitialSavedState(SavedState state) {
        if (mIndex >= 0) {
            throw new IllegalStateException("Fragment already active");
        }
        mSavedFragmentState = state != null && state.mState != null
                ? state.mState : null;
    }

    /**
     * Optional target for this fragment.  This may be used, for example,
     * if this fragment is being started by another, and when done wants to
     * give a result back to the first.  The target set here is retained
     * across instances via {@link FragmentManager#putFragment
     * FragmentManager.putFragment()}.
     *
     * @param fragment The fragment that is the target of this one.
     * @param requestCode Optional request code, for convenience if you
     * are going to call back with {@link #onActivityResult(int, int, Intent)}.
     */
    public void setTargetFragment(Fragment fragment, int requestCode) {
        // Don't allow a caller to set a target fragment in another FragmentManager,
        // but there's a snag: people do set target fragments before fragments get added.
        // We'll have the FragmentManager check that for validity when we move
        // the fragments to a valid state.
        final FragmentManager mine = getFragmentManager();
        final FragmentManager theirs = fragment != null ? fragment.getFragmentManager() : null;
        if (mine != null && theirs != null && mine != theirs) {
            throw new IllegalArgumentException("Fragment " + fragment
                    + " must share the same FragmentManager to be set as a target fragment");
        }

        // Don't let someone create a cycle.
        for (Fragment check = fragment; check != null; check = check.getTargetFragment()) {
            if (check == this) {
                throw new IllegalArgumentException("Setting " + fragment + " as the target of "
                        + this + " would create a target cycle");
            }
        }
        mTarget = fragment;
        mTargetRequestCode = requestCode;
    }

    /**
     * Return the target fragment set by {@link #setTargetFragment}.
     */
    final public Fragment getTargetFragment() {
        return mTarget;
    }

    /**
     * Return the target request code set by {@link #setTargetFragment}.
     */
    final public int getTargetRequestCode() {
        return mTargetRequestCode;
    }

    /**
     * Return the {@link Context} this fragment is currently associated with.
     */
    public Context getContext() {
        return mHost == null ? null : mHost.getContext();
    }

    /**
     * Return the Activity this fragment is currently associated with.
     */
    final public Activity getActivity() {
        return mHost == null ? null : mHost.getActivity();
    }

    /**
     * Return the host object of this fragment. May return {@code null} if the fragment
     * isn't currently being hosted.
     */
    @Nullable
    final public Object getHost() {
        return mHost == null ? null : mHost.onGetHost();
    }

    /**
     * Return <code>getActivity().getResources()</code>.
     */
    final public Resources getResources() {
        if (mHost == null) {
            throw new IllegalStateException("Fragment " + this + " not attached to Activity");
        }
        return mHost.getContext().getResources();
    }

    /**
     * Return a localized, styled CharSequence from the application's package's
     * default string table.
     *
     * @param resId Resource id for the CharSequence text
     */
    public final CharSequence getText(@StringRes int resId) {
        return getResources().getText(resId);
    }

    /**
     * Return a localized string from the application's package's
     * default string table.
     *
     * @param resId Resource id for the string
     */
    public final String getString(@StringRes int resId) {
        return getResources().getString(resId);
    }

    /**
     * Return a localized formatted string from the application's package's
     * default string table, substituting the format arguments as defined in
     * {@link java.util.Formatter} and {@link java.lang.String#format}.
     *
     * @param resId Resource id for the format string
     * @param formatArgs The format arguments that will be used for substitution.
     */

    public final String getString(@StringRes int resId, Object... formatArgs) {
        return getResources().getString(resId, formatArgs);
    }

    /**
     * Return the FragmentManager for interacting with fragments associated
     * with this fragment's activity.  Note that this will be non-null slightly
     * before {@link #getActivity()}, during the time from when the fragment is
     * placed in a {@link FragmentTransaction} until it is committed and
     * attached to its activity.
     *
     * <p>If this Fragment is a child of another Fragment, the FragmentManager
     * returned here will be the parent's {@link #getChildFragmentManager()}.
     */
    final public FragmentManager getFragmentManager() {
        return mFragmentManager;
    }

    /**
     * Return a private FragmentManager for placing and managing Fragments
     * inside of this Fragment.
     */
    final public FragmentManager getChildFragmentManager() {
        if (mChildFragmentManager == null) {
            instantiateChildFragmentManager();
            if (mState >= RESUMED) {
                mChildFragmentManager.dispatchResume();
            } else if (mState >= STARTED) {
                mChildFragmentManager.dispatchStart();
            } else if (mState >= ACTIVITY_CREATED) {
                mChildFragmentManager.dispatchActivityCreated();
            } else if (mState >= CREATED) {
                mChildFragmentManager.dispatchCreate();
            }
        }
        return mChildFragmentManager;
    }

    /**
     * Returns the parent Fragment containing this Fragment.  If this Fragment
     * is attached directly to an Activity, returns null.
     */
    final public Fragment getParentFragment() {
        return mParentFragment;
    }

    /**
     * Return true if the fragment is currently added to its activity.
     */
    final public boolean isAdded() {
        return mHost != null && mAdded;
    }

    /**
     * Return true if the fragment has been explicitly detached from the UI.
     * That is, {@link FragmentTransaction#detach(Fragment)
     * FragmentTransaction.detach(Fragment)} has been used on it.
     */
    final public boolean isDetached() {
        return mDetached;
    }

    /**
     * Return true if this fragment is currently being removed from its
     * activity.  This is  <em>not</em> whether its activity is finishing, but
     * rather whether it is in the process of being removed from its activity.
     */
    final public boolean isRemoving() {
        return mRemoving;
    }

    /**
     * Return true if the layout is included as part of an activity view
     * hierarchy via the &lt;fragment&gt; tag.  This will always be true when
     * fragments are created through the &lt;fragment&gt; tag, <em>except</em>
     * in the case where an old fragment is restored from a previous state and
     * it does not appear in the layout of the current state.
     */
    final public boolean isInLayout() {
        return mInLayout;
    }

    /**
     * Return true if the fragment is in the resumed state.  This is true
     * for the duration of {@link #onResume()} and {@link #onPause()} as well.
     */
    final public boolean isResumed() {
        return mState >= RESUMED;
    }

    /**
     * Return true if the fragment is currently visible to the user.  This means
     * it: (1) has been added, (2) has its view attached to the window, and
     * (3) is not hidden.
     */
    final public boolean isVisible() {
        return isAdded() && !isHidden() && mView != null
                && mView.getWindowToken() != null && mView.getVisibility() == View.VISIBLE;
    }

    /**
     * Return true if the fragment has been hidden.  By default fragments
     * are shown.  You can find out about changes to this state with
     * {@link #onHiddenChanged}.  Note that the hidden state is orthogonal
     * to other states -- that is, to be visible to the user, a fragment
     * must be both started and not hidden.
     */
    final public boolean isHidden() {
        return mHidden;
    }

    /**
     * Called when the hidden state (as returned by {@link #isHidden()} of
     * the fragment has changed.  Fragments start out not hidden; this will
     * be called whenever the fragment changes state from that.
     * @param hidden True if the fragment is now hidden, false otherwise.
     */
    public void onHiddenChanged(boolean hidden) {
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
     * <li> {@link #onAttach(Activity)} and {@link #onActivityCreated(Bundle)} <b>will</b>
     * still be called.
     * </ul>
     */
    public void setRetainInstance(boolean retain) {
        mRetainInstance = retain;
    }

    final public boolean getRetainInstance() {
        return mRetainInstance;
    }

    /**
     * Report that this fragment would like to participate in populating
     * the options menu by receiving a call to {@link #onCreateOptionsMenu}
     * and related methods.
     *
     * @param hasMenu If true, the fragment has menu items to contribute.
     */
    public void setHasOptionsMenu(boolean hasMenu) {
        if (mHasMenu != hasMenu) {
            mHasMenu = hasMenu;
            if (isAdded() && !isHidden()) {
                mFragmentManager.invalidateOptionsMenu();
            }
        }
    }

    /**
     * Set a hint for whether this fragment's menu should be visible.  This
     * is useful if you know that a fragment has been placed in your view
     * hierarchy so that the user can not currently seen it, so any menu items
     * it has should also not be shown.
     *
     * @param menuVisible The default is true, meaning the fragment's menu will
     * be shown as usual.  If false, the user will not see the menu.
     */
    public void setMenuVisibility(boolean menuVisible) {
        if (mMenuVisible != menuVisible) {
            mMenuVisible = menuVisible;
            if (mHasMenu && isAdded() && !isHidden()) {
                mFragmentManager.invalidateOptionsMenu();
            }
        }
    }

    /**
     * Set a hint to the system about whether this fragment's UI is currently visible
     * to the user. This hint defaults to true and is persistent across fragment instance
     * state save and restore.
     *
     * <p>An app may set this to false to indicate that the fragment's UI is
     * scrolled out of visibility or is otherwise not directly visible to the user.
     * This may be used by the system to prioritize operations such as fragment lifecycle updates
     * or loader ordering behavior.</p>
     *
     * <p><strong>Note:</strong> This method may be called outside of the fragment lifecycle
     * and thus has no ordering guarantees with regard to fragment lifecycle method calls.</p>
     *
     * <p><strong>Note:</strong> Prior to Android N there was a platform bug that could cause
     * <code>setUserVisibleHint</code> to bring a fragment up to the started state before its
     * <code>FragmentTransaction</code> had been committed. As some apps relied on this behavior,
     * it is preserved for apps that declare a <code>targetSdkVersion</code> of 23 or lower.</p>
     *
     * @param isVisibleToUser true if this fragment's UI is currently visible to the user (default),
     *                        false if it is not.
     */
    public void setUserVisibleHint(boolean isVisibleToUser) {
        // Prior to Android N we were simply checking if this fragment had a FragmentManager
        // set before we would trigger a deferred start. Unfortunately this also gets set before
        // a fragment transaction is committed, so if setUserVisibleHint was called before a
        // transaction commit, we would start the fragment way too early. FragmentPagerAdapter
        // triggers this situation.
        // Unfortunately some apps relied on this timing in overrides of setUserVisibleHint
        // on their own fragments, and expected, however erroneously, that after a call to
        // super.setUserVisibleHint their onStart methods had been run.
        // We preserve this behavior for apps targeting old platform versions below.
        boolean useBrokenAddedCheck = false;
        Context context = getContext();
        if (mFragmentManager != null && mFragmentManager.mHost != null) {
            context = mFragmentManager.mHost.getContext();
        }
        if (context != null) {
            useBrokenAddedCheck = context.getApplicationInfo().targetSdkVersion <= VERSION_CODES.M;
        }

        final boolean performDeferredStart;
        if (useBrokenAddedCheck) {
            performDeferredStart = !mUserVisibleHint && isVisibleToUser && mState < STARTED
                    && mFragmentManager != null;
        } else {
            performDeferredStart = !mUserVisibleHint && isVisibleToUser && mState < STARTED
                    && mFragmentManager != null && isAdded();
        }

        if (performDeferredStart) {
            mFragmentManager.performPendingDeferredStart(this);
        }

        mUserVisibleHint = isVisibleToUser;
        mDeferStart = mState < STARTED && !isVisibleToUser;
    }

    /**
     * @return The current value of the user-visible hint on this fragment.
     * @see #setUserVisibleHint(boolean)
     */
    public boolean getUserVisibleHint() {
        return mUserVisibleHint;
    }

    /**
     * Return the LoaderManager for this fragment, creating it if needed.
     *
     * @deprecated Use {@link android.support.v4.app.Fragment#getLoaderManager()}
     */
    @Deprecated
    public LoaderManager getLoaderManager() {
        if (mLoaderManager != null) {
            return mLoaderManager;
        }
        if (mHost == null) {
            throw new IllegalStateException("Fragment " + this + " not attached to Activity");
        }
        mCheckedForLoaderManager = true;
        mLoaderManager = mHost.getLoaderManager(mWho, mLoadersStarted, true);
        return mLoaderManager;
    }

    /**
     * Call {@link Activity#startActivity(Intent)} from the fragment's
     * containing Activity.
     *
     * @param intent The intent to start.
     */
    public void startActivity(Intent intent) {
        startActivity(intent, null);
    }

    /**
     * Call {@link Activity#startActivity(Intent, Bundle)} from the fragment's
     * containing Activity.
     *
     * @param intent The intent to start.
     * @param options Additional options for how the Activity should be started.
     * See {@link android.content.Context#startActivity(Intent, Bundle)}
     * Context.startActivity(Intent, Bundle)} for more details.
     */
    public void startActivity(Intent intent, Bundle options) {
        if (mHost == null) {
            throw new IllegalStateException("Fragment " + this + " not attached to Activity");
        }
        if (options != null) {
            mHost.onStartActivityFromFragment(this, intent, -1, options);
        } else {
            // Note we want to go through this call for compatibility with
            // applications that may have overridden the method.
            mHost.onStartActivityFromFragment(this, intent, -1, null /*options*/);
        }
    }

    /**
     * Call {@link Activity#startActivityForResult(Intent, int)} from the fragment's
     * containing Activity.
     */
    public void startActivityForResult(Intent intent, int requestCode) {
        startActivityForResult(intent, requestCode, null);
    }

    /**
     * Call {@link Activity#startActivityForResult(Intent, int, Bundle)} from the fragment's
     * containing Activity.
     */
    public void startActivityForResult(Intent intent, int requestCode, Bundle options) {
        if (mHost == null) {
            throw new IllegalStateException("Fragment " + this + " not attached to Activity");
        }
        mHost.onStartActivityFromFragment(this, intent, requestCode, options);
    }

    /**
     * @hide
     * Call {@link Activity#startActivityForResultAsUser(Intent, int, UserHandle)} from the
     * fragment's containing Activity.
     */
    public void startActivityForResultAsUser(
            Intent intent, int requestCode, Bundle options, UserHandle user) {
        if (mHost == null) {
            throw new IllegalStateException("Fragment " + this + " not attached to Activity");
        }
        mHost.onStartActivityAsUserFromFragment(this, intent, requestCode, options, user);
    }

    /**
     * Call {@link Activity#startIntentSenderForResult(IntentSender, int, Intent, int, int, int,
     * Bundle)} from the fragment's containing Activity.
     */
    public void startIntentSenderForResult(IntentSender intent, int requestCode,
            @Nullable Intent fillInIntent, int flagsMask, int flagsValues, int extraFlags,
            Bundle options) throws IntentSender.SendIntentException {
        if (mHost == null) {
            throw new IllegalStateException("Fragment " + this + " not attached to Activity");
        }
        mHost.onStartIntentSenderFromFragment(this, intent, requestCode, fillInIntent, flagsMask,
                flagsValues, extraFlags, options);
    }

    /**
     * Receive the result from a previous call to
     * {@link #startActivityForResult(Intent, int)}.  This follows the
     * related Activity API as described there in
     * {@link Activity#onActivityResult(int, int, Intent)}.
     *
     * @param requestCode The integer request code originally supplied to
     *                    startActivityForResult(), allowing you to identify who this
     *                    result came from.
     * @param resultCode The integer result code returned by the child activity
     *                   through its setResult().
     * @param data An Intent, which can return result data to the caller
     *               (various data can be attached to Intent "extras").
     */
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
    }

    /**
     * Requests permissions to be granted to this application. These permissions
     * must be requested in your manifest, they should not be granted to your app,
     * and they should have protection level {@link android.content.pm.PermissionInfo
     * #PROTECTION_DANGEROUS dangerous}, regardless whether they are declared by
     * the platform or a third-party app.
     * <p>
     * Normal permissions {@link android.content.pm.PermissionInfo#PROTECTION_NORMAL}
     * are granted at install time if requested in the manifest. Signature permissions
     * {@link android.content.pm.PermissionInfo#PROTECTION_SIGNATURE} are granted at
     * install time if requested in the manifest and the signature of your app matches
     * the signature of the app declaring the permissions.
     * </p>
     * <p>
     * Call {@link #shouldShowRequestPermissionRationale(String)} before calling this API
     * to check if the system recommends to show a rationale UI before asking for a permission.
     * </p>
     * <p>
     * If your app does not have the requested permissions the user will be presented
     * with UI for accepting them. After the user has accepted or rejected the
     * requested permissions you will receive a callback on {@link
     * #onRequestPermissionsResult(int, String[], int[])} reporting whether the
     * permissions were granted or not.
     * </p>
     * <p>
     * Note that requesting a permission does not guarantee it will be granted and
     * your app should be able to run without having this permission.
     * </p>
     * <p>
     * This method may start an activity allowing the user to choose which permissions
     * to grant and which to reject. Hence, you should be prepared that your activity
     * may be paused and resumed. Further, granting some permissions may require
     * a restart of you application. In such a case, the system will recreate the
     * activity stack before delivering the result to {@link
     * #onRequestPermissionsResult(int, String[], int[])}.
     * </p>
     * <p>
     * When checking whether you have a permission you should use {@link
     * android.content.Context#checkSelfPermission(String)}.
     * </p>
     * <p>
     * Calling this API for permissions already granted to your app would show UI
     * to the user to decide whether the app can still hold these permissions. This
     * can be useful if the way your app uses data guarded by the permissions
     * changes significantly.
     * </p>
     * <p>
     * You cannot request a permission if your activity sets {@link
     * android.R.styleable#AndroidManifestActivity_noHistory noHistory} to
     * <code>true</code> because in this case the activity would not receive
     * result callbacks including {@link #onRequestPermissionsResult(int, String[], int[])}.
     * </p>
     *
     * @param permissions The requested permissions. Must me non-null and not empty.
     * @param requestCode Application specific request code to match with a result
     *    reported to {@link #onRequestPermissionsResult(int, String[], int[])}.
     *    Should be >= 0.
     *
     * @see #onRequestPermissionsResult(int, String[], int[])
     * @see android.content.Context#checkSelfPermission(String)
     */
    public final void requestPermissions(@NonNull String[] permissions, int requestCode) {
        if (mHost == null) {
            throw new IllegalStateException("Fragment " + this + " not attached to Activity");
        }
        mHost.onRequestPermissionsFromFragment(this, permissions,requestCode);
    }

    /**
     * Callback for the result from requesting permissions. This method
     * is invoked for every call on {@link #requestPermissions(String[], int)}.
     * <p>
     * <strong>Note:</strong> It is possible that the permissions request interaction
     * with the user is interrupted. In this case you will receive empty permissions
     * and results arrays which should be treated as a cancellation.
     * </p>
     *
     * @param requestCode The request code passed in {@link #requestPermissions(String[], int)}.
     * @param permissions The requested permissions. Never null.
     * @param grantResults The grant results for the corresponding permissions
     *     which is either {@link android.content.pm.PackageManager#PERMISSION_GRANTED}
     *     or {@link android.content.pm.PackageManager#PERMISSION_DENIED}. Never null.
     *
     * @see #requestPermissions(String[], int)
     */
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        /* callback - do nothing */
    }

    /**
     * Gets whether you should show UI with rationale before requesting a permission.
     *
     * @param permission A permission your app wants to request.
     * @return Whether you should show permission rationale UI.
     *
     * @see Context#checkSelfPermission(String)
     * @see #requestPermissions(String[], int)
     * @see #onRequestPermissionsResult(int, String[], int[])
     */
    public boolean shouldShowRequestPermissionRationale(@NonNull String permission) {
        if (mHost != null) {
            return mHost.getContext().getPackageManager()
                    .shouldShowRequestPermissionRationale(permission);
        }
        return false;
    }

    /**
     * Returns the LayoutInflater used to inflate Views of this Fragment. The default
     * implementation will throw an exception if the Fragment is not attached.
     *
     * @return The LayoutInflater used to inflate Views of this Fragment.
     */
    public LayoutInflater onGetLayoutInflater(Bundle savedInstanceState) {
        if (mHost == null) {
            throw new IllegalStateException("onGetLayoutInflater() cannot be executed until the "
                    + "Fragment is attached to the FragmentManager.");
        }
        final LayoutInflater result = mHost.onGetLayoutInflater();
        if (mHost.onUseFragmentManagerInflaterFactory()) {
            getChildFragmentManager(); // Init if needed; use raw implementation below.
            result.setPrivateFactory(mChildFragmentManager.getLayoutInflaterFactory());
        }
        return result;
    }

    /**
     * Returns the cached LayoutInflater used to inflate Views of this Fragment. If
     * {@link #onGetLayoutInflater(Bundle)} has not been called {@link #onGetLayoutInflater(Bundle)}
     * will be called with a {@code null} argument and that value will be cached.
     * <p>
     * The cached LayoutInflater will be replaced immediately prior to
     * {@link #onCreateView(LayoutInflater, ViewGroup, Bundle)} and cleared immediately after
     * {@link #onDetach()}.
     *
     * @return The LayoutInflater used to inflate Views of this Fragment.
     */
    public final LayoutInflater getLayoutInflater() {
        if (mLayoutInflater == null) {
            return performGetLayoutInflater(null);
        }
        return mLayoutInflater;
    }

    /**
     * Calls {@link #onGetLayoutInflater(Bundle)} and caches the result for use by
     * {@link #getLayoutInflater()}.
     *
     * @param savedInstanceState If the fragment is being re-created from
     * a previous saved state, this is the state.
     * @return The LayoutInflater used to inflate Views of this Fragment.
     */
    LayoutInflater performGetLayoutInflater(Bundle savedInstanceState) {
        LayoutInflater layoutInflater = onGetLayoutInflater(savedInstanceState);
        mLayoutInflater = layoutInflater;
        return mLayoutInflater;
    }

    /**
     * @deprecated Use {@link #onInflate(Context, AttributeSet, Bundle)} instead.
     */
    @Deprecated
    @CallSuper
    public void onInflate(AttributeSet attrs, Bundle savedInstanceState) {
        mCalled = true;
    }

    /**
     * Called when a fragment is being created as part of a view layout
     * inflation, typically from setting the content view of an activity.  This
     * may be called immediately after the fragment is created from a <fragment>
     * tag in a layout file.  Note this is <em>before</em> the fragment's
     * {@link #onAttach(Activity)} has been called; all you should do here is
     * parse the attributes and save them away.
     *
     * <p>This is called every time the fragment is inflated, even if it is
     * being inflated into a new instance with saved state.  It typically makes
     * sense to re-parse the parameters each time, to allow them to change with
     * different configurations.</p>
     *
     * <p>Here is a typical implementation of a fragment that can take parameters
     * both through attributes supplied here as well from {@link #getArguments()}:</p>
     *
     * {@sample development/samples/ApiDemos/src/com/example/android/apis/app/FragmentArguments.java
     *      fragment}
     *
     * <p>Note that parsing the XML attributes uses a "styleable" resource.  The
     * declaration for the styleable used here is:</p>
     *
     * {@sample development/samples/ApiDemos/res/values/attrs.xml fragment_arguments}
     *
     * <p>The fragment can then be declared within its activity's content layout
     * through a tag like this:</p>
     *
     * {@sample development/samples/ApiDemos/res/layout/fragment_arguments.xml from_attributes}
     *
     * <p>This fragment can also be created dynamically from arguments given
     * at runtime in the arguments Bundle; here is an example of doing so at
     * creation of the containing activity:</p>
     *
     * {@sample development/samples/ApiDemos/src/com/example/android/apis/app/FragmentArguments.java
     *      create}
     *
     * @param context The Context that is inflating this fragment.
     * @param attrs The attributes at the tag where the fragment is
     * being created.
     * @param savedInstanceState If the fragment is being re-created from
     * a previous saved state, this is the state.
     */
    @CallSuper
    public void onInflate(Context context, AttributeSet attrs, Bundle savedInstanceState) {
        onInflate(attrs, savedInstanceState);
        mCalled = true;

        TypedArray a = context.obtainStyledAttributes(attrs,
                com.android.internal.R.styleable.Fragment);
        setEnterTransition(loadTransition(context, a, getEnterTransition(), null,
                com.android.internal.R.styleable.Fragment_fragmentEnterTransition));
        setReturnTransition(loadTransition(context, a, getReturnTransition(),
                USE_DEFAULT_TRANSITION,
                com.android.internal.R.styleable.Fragment_fragmentReturnTransition));
        setExitTransition(loadTransition(context, a, getExitTransition(), null,
                com.android.internal.R.styleable.Fragment_fragmentExitTransition));

        setReenterTransition(loadTransition(context, a, getReenterTransition(),
                USE_DEFAULT_TRANSITION,
                com.android.internal.R.styleable.Fragment_fragmentReenterTransition));
        setSharedElementEnterTransition(loadTransition(context, a,
                getSharedElementEnterTransition(), null,
                com.android.internal.R.styleable.Fragment_fragmentSharedElementEnterTransition));
        setSharedElementReturnTransition(loadTransition(context, a,
                getSharedElementReturnTransition(), USE_DEFAULT_TRANSITION,
                com.android.internal.R.styleable.Fragment_fragmentSharedElementReturnTransition));
        boolean isEnterSet;
        boolean isReturnSet;
        if (mAnimationInfo == null) {
            isEnterSet = false;
            isReturnSet = false;
        } else {
            isEnterSet = mAnimationInfo.mAllowEnterTransitionOverlap != null;
            isReturnSet = mAnimationInfo.mAllowReturnTransitionOverlap != null;
        }
        if (!isEnterSet) {
            setAllowEnterTransitionOverlap(a.getBoolean(
                    com.android.internal.R.styleable.Fragment_fragmentAllowEnterTransitionOverlap,
                    true));
        }
        if (!isReturnSet) {
            setAllowReturnTransitionOverlap(a.getBoolean(
                    com.android.internal.R.styleable.Fragment_fragmentAllowReturnTransitionOverlap,
                    true));
        }
        a.recycle();

        final Activity hostActivity = mHost == null ? null : mHost.getActivity();
        if (hostActivity != null) {
            mCalled = false;
            onInflate(hostActivity, attrs, savedInstanceState);
        }
    }

    /**
     * @deprecated Use {@link #onInflate(Context, AttributeSet, Bundle)} instead.
     */
    @Deprecated
    @CallSuper
    public void onInflate(Activity activity, AttributeSet attrs, Bundle savedInstanceState) {
        mCalled = true;
    }

    /**
     * Called when a fragment is attached as a child of this fragment.
     *
     * <p>This is called after the attached fragment's <code>onAttach</code> and before
     * the attached fragment's <code>onCreate</code> if the fragment has not yet had a previous
     * call to <code>onCreate</code>.</p>
     *
     * @param childFragment child fragment being attached
     */
    public void onAttachFragment(Fragment childFragment) {
    }

    /**
     * Called when a fragment is first attached to its context.
     * {@link #onCreate(Bundle)} will be called after this.
     */
    @CallSuper
    public void onAttach(Context context) {
        mCalled = true;
        final Activity hostActivity = mHost == null ? null : mHost.getActivity();
        if (hostActivity != null) {
            mCalled = false;
            onAttach(hostActivity);
        }
    }

    /**
     * @deprecated Use {@link #onAttach(Context)} instead.
     */
    @Deprecated
    @CallSuper
    public void onAttach(Activity activity) {
        mCalled = true;
    }

    /**
     * Called when a fragment loads an animation.
     */
    public Animator onCreateAnimator(int transit, boolean enter, int nextAnim) {
        return null;
    }

    /**
     * Called to do initial creation of a fragment.  This is called after
     * {@link #onAttach(Activity)} and before
     * {@link #onCreateView(LayoutInflater, ViewGroup, Bundle)}, but is not called if the fragment
     * instance is retained across Activity re-creation (see {@link #setRetainInstance(boolean)}).
     *
     * <p>Note that this can be called while the fragment's activity is
     * still in the process of being created.  As such, you can not rely
     * on things like the activity's content view hierarchy being initialized
     * at this point.  If you want to do work once the activity itself is
     * created, see {@link #onActivityCreated(Bundle)}.
     *
     * <p>If your app's <code>targetSdkVersion</code> is {@link android.os.Build.VERSION_CODES#M}
     * or lower, child fragments being restored from the savedInstanceState are restored after
     * <code>onCreate</code> returns. When targeting {@link android.os.Build.VERSION_CODES#N} or
     * above and running on an N or newer platform version
     * they are restored by <code>Fragment.onCreate</code>.</p>
     *
     * @param savedInstanceState If the fragment is being re-created from
     * a previous saved state, this is the state.
     */
    @CallSuper
    public void onCreate(@Nullable Bundle savedInstanceState) {
        mCalled = true;
        final Context context = getContext();
        final int version = context != null ? context.getApplicationInfo().targetSdkVersion : 0;
        if (version >= Build.VERSION_CODES.N) {
            restoreChildFragmentState(savedInstanceState, true);
            if (mChildFragmentManager != null
                    && !mChildFragmentManager.isStateAtLeast(Fragment.CREATED)) {
                mChildFragmentManager.dispatchCreate();
            }
        }
    }

    void restoreChildFragmentState(@Nullable Bundle savedInstanceState, boolean provideNonConfig) {
        if (savedInstanceState != null) {
            Parcelable p = savedInstanceState.getParcelable(Activity.FRAGMENTS_TAG);
            if (p != null) {
                if (mChildFragmentManager == null) {
                    instantiateChildFragmentManager();
                }
                mChildFragmentManager.restoreAllState(p, provideNonConfig ? mChildNonConfig : null);
                mChildNonConfig = null;
                mChildFragmentManager.dispatchCreate();
            }
        }
    }

    /**
     * Called to have the fragment instantiate its user interface view.
     * This is optional, and non-graphical fragments can return null (which
     * is the default implementation).  This will be called between
     * {@link #onCreate(Bundle)} and {@link #onActivityCreated(Bundle)}.
     *
     * <p>If you return a View from here, you will later be called in
     * {@link #onDestroyView} when the view is being released.
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
    @Nullable
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
            Bundle savedInstanceState) {
        return null;
    }

    /**
     * Called immediately after {@link #onCreateView(LayoutInflater, ViewGroup, Bundle)}
     * has returned, but before any saved state has been restored in to the view.
     * This gives subclasses a chance to initialize themselves once
     * they know their view hierarchy has been completely created.  The fragment's
     * view hierarchy is not however attached to its parent at this point.
     * @param view The View returned by {@link #onCreateView(LayoutInflater, ViewGroup, Bundle)}.
     * @param savedInstanceState If non-null, this fragment is being re-constructed
     * from a previous saved state as given here.
     */
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
    }

    /**
     * Get the root view for the fragment's layout (the one returned by {@link #onCreateView}),
     * if provided.
     *
     * @return The fragment's root view, or null if it has no layout.
     */
    @Nullable
    public View getView() {
        return mView;
    }

    /**
     * Called when the fragment's activity has been created and this
     * fragment's view hierarchy instantiated.  It can be used to do final
     * initialization once these pieces are in place, such as retrieving
     * views or restoring state.  It is also useful for fragments that use
     * {@link #setRetainInstance(boolean)} to retain their instance,
     * as this callback tells the fragment when it is fully associated with
     * the new activity instance.  This is called after {@link #onCreateView}
     * and before {@link #onViewStateRestored(Bundle)}.
     *
     * @param savedInstanceState If the fragment is being re-created from
     * a previous saved state, this is the state.
     */
    @CallSuper
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        mCalled = true;
    }

    /**
     * Called when all saved state has been restored into the view hierarchy
     * of the fragment.  This can be used to do initialization based on saved
     * state that you are letting the view hierarchy track itself, such as
     * whether check box widgets are currently checked.  This is called
     * after {@link #onActivityCreated(Bundle)} and before
     * {@link #onStart()}.
     *
     * @param savedInstanceState If the fragment is being re-created from
     * a previous saved state, this is the state.
     */
    @CallSuper
    public void onViewStateRestored(Bundle savedInstanceState) {
        mCalled = true;
    }

    /**
     * Called when the Fragment is visible to the user.  This is generally
     * tied to {@link Activity#onStart() Activity.onStart} of the containing
     * Activity's lifecycle.
     */
    @CallSuper
    public void onStart() {
        mCalled = true;

        if (!mLoadersStarted) {
            mLoadersStarted = true;
            if (!mCheckedForLoaderManager) {
                mCheckedForLoaderManager = true;
                mLoaderManager = mHost.getLoaderManager(mWho, mLoadersStarted, false);
            } else if (mLoaderManager != null) {
                mLoaderManager.doStart();
            }
        }
    }

    /**
     * Called when the fragment is visible to the user and actively running.
     * This is generally
     * tied to {@link Activity#onResume() Activity.onResume} of the containing
     * Activity's lifecycle.
     */
    @CallSuper
    public void onResume() {
        mCalled = true;
    }

    /**
     * Called to ask the fragment to save its current dynamic state, so it
     * can later be reconstructed in a new instance of its process is
     * restarted.  If a new instance of the fragment later needs to be
     * created, the data you place in the Bundle here will be available
     * in the Bundle given to {@link #onCreate(Bundle)},
     * {@link #onCreateView(LayoutInflater, ViewGroup, Bundle)}, and
     * {@link #onActivityCreated(Bundle)}.
     *
     * <p>This corresponds to {@link Activity#onSaveInstanceState(Bundle)
     * Activity.onSaveInstanceState(Bundle)} and most of the discussion there
     * applies here as well.  Note however: <em>this method may be called
     * at any time before {@link #onDestroy()}</em>.  There are many situations
     * where a fragment may be mostly torn down (such as when placed on the
     * back stack with no UI showing), but its state will not be saved until
     * its owning activity actually needs to save its state.
     *
     * @param outState Bundle in which to place your saved state.
     */
    public void onSaveInstanceState(Bundle outState) {
    }

    /**
     * Called when the Fragment's activity changes from fullscreen mode to multi-window mode and
     * visa-versa. This is generally tied to {@link Activity#onMultiWindowModeChanged} of the
     * containing Activity. This method provides the same configuration that will be sent in the
     * following {@link #onConfigurationChanged(Configuration)} call after the activity enters this
     * mode.
     *
     * @param isInMultiWindowMode True if the activity is in multi-window mode.
     * @param newConfig The new configuration of the activity with the state
     *                  {@param isInMultiWindowMode}.
     */
    public void onMultiWindowModeChanged(boolean isInMultiWindowMode, Configuration newConfig) {
        onMultiWindowModeChanged(isInMultiWindowMode);
    }

    /**
     * Called when the Fragment's activity changes from fullscreen mode to multi-window mode and
     * visa-versa. This is generally tied to {@link Activity#onMultiWindowModeChanged} of the
     * containing Activity.
     *
     * @param isInMultiWindowMode True if the activity is in multi-window mode.
     *
     * @deprecated Use {@link #onMultiWindowModeChanged(boolean, Configuration)} instead.
     */
    @Deprecated
    public void onMultiWindowModeChanged(boolean isInMultiWindowMode) {
    }

    /**
     * Called by the system when the activity changes to and from picture-in-picture mode. This is
     * generally tied to {@link Activity#onPictureInPictureModeChanged} of the containing Activity.
     * This method provides the same configuration that will be sent in the following
     * {@link #onConfigurationChanged(Configuration)} call after the activity enters this mode.
     *
     * @param isInPictureInPictureMode True if the activity is in picture-in-picture mode.
     * @param newConfig The new configuration of the activity with the state
     *                  {@param isInPictureInPictureMode}.
     */
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode,
            Configuration newConfig) {
        onPictureInPictureModeChanged(isInPictureInPictureMode);
    }

    /**
     * Called by the system when the activity changes to and from picture-in-picture mode. This is
     * generally tied to {@link Activity#onPictureInPictureModeChanged} of the containing Activity.
     *
     * @param isInPictureInPictureMode True if the activity is in picture-in-picture mode.
     *
     * @deprecated Use {@link #onPictureInPictureModeChanged(boolean, Configuration)} instead.
     */
    @Deprecated
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
    }

    @CallSuper
    public void onConfigurationChanged(Configuration newConfig) {
        mCalled = true;
    }

    /**
     * Called when the Fragment is no longer resumed.  This is generally
     * tied to {@link Activity#onPause() Activity.onPause} of the containing
     * Activity's lifecycle.
     */
    @CallSuper
    public void onPause() {
        mCalled = true;
    }

    /**
     * Called when the Fragment is no longer started.  This is generally
     * tied to {@link Activity#onStop() Activity.onStop} of the containing
     * Activity's lifecycle.
     */
    @CallSuper
    public void onStop() {
        mCalled = true;
    }

    @CallSuper
    public void onLowMemory() {
        mCalled = true;
    }

    @CallSuper
    public void onTrimMemory(int level) {
        mCalled = true;
    }

    /**
     * Called when the view previously created by {@link #onCreateView} has
     * been detached from the fragment.  The next time the fragment needs
     * to be displayed, a new view will be created.  This is called
     * after {@link #onStop()} and before {@link #onDestroy()}.  It is called
     * <em>regardless</em> of whether {@link #onCreateView} returned a
     * non-null view.  Internally it is called after the view's state has
     * been saved but before it has been removed from its parent.
     */
    @CallSuper
    public void onDestroyView() {
        mCalled = true;
    }

    /**
     * Called when the fragment is no longer in use.  This is called
     * after {@link #onStop()} and before {@link #onDetach()}.
     */
    @CallSuper
    public void onDestroy() {
        mCalled = true;
        //Log.v("foo", "onDestroy: mCheckedForLoaderManager=" + mCheckedForLoaderManager
        //        + " mLoaderManager=" + mLoaderManager);
        if (!mCheckedForLoaderManager) {
            mCheckedForLoaderManager = true;
            mLoaderManager = mHost.getLoaderManager(mWho, mLoadersStarted, false);
        }
        if (mLoaderManager != null) {
            mLoaderManager.doDestroy();
        }
    }

    /**
     * Called by the fragment manager once this fragment has been removed,
     * so that we don't have any left-over state if the application decides
     * to re-use the instance.  This only clears state that the framework
     * internally manages, not things the application sets.
     */
    void initState() {
        mIndex = -1;
        mWho = null;
        mAdded = false;
        mRemoving = false;
        mFromLayout = false;
        mInLayout = false;
        mRestored = false;
        mBackStackNesting = 0;
        mFragmentManager = null;
        mChildFragmentManager = null;
        mHost = null;
        mFragmentId = 0;
        mContainerId = 0;
        mTag = null;
        mHidden = false;
        mDetached = false;
        mRetaining = false;
        mLoaderManager = null;
        mLoadersStarted = false;
        mCheckedForLoaderManager = false;
    }

    /**
     * Called when the fragment is no longer attached to its activity.  This is called after
     * {@link #onDestroy()}, except in the cases where the fragment instance is retained across
     * Activity re-creation (see {@link #setRetainInstance(boolean)}), in which case it is called
     * after {@link #onStop()}.
     */
    @CallSuper
    public void onDetach() {
        mCalled = true;
    }

    /**
     * Initialize the contents of the Activity's standard options menu.  You
     * should place your menu items in to <var>menu</var>.  For this method
     * to be called, you must have first called {@link #setHasOptionsMenu}.  See
     * {@link Activity#onCreateOptionsMenu(Menu) Activity.onCreateOptionsMenu}
     * for more information.
     *
     * @param menu The options menu in which you place your items.
     *
     * @see #setHasOptionsMenu
     * @see #onPrepareOptionsMenu
     * @see #onOptionsItemSelected
     */
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
    }

    /**
     * Prepare the Screen's standard options menu to be displayed.  This is
     * called right before the menu is shown, every time it is shown.  You can
     * use this method to efficiently enable/disable items or otherwise
     * dynamically modify the contents.  See
     * {@link Activity#onPrepareOptionsMenu(Menu) Activity.onPrepareOptionsMenu}
     * for more information.
     *
     * @param menu The options menu as last shown or first initialized by
     *             onCreateOptionsMenu().
     *
     * @see #setHasOptionsMenu
     * @see #onCreateOptionsMenu
     */
    public void onPrepareOptionsMenu(Menu menu) {
    }

    /**
     * Called when this fragment's option menu items are no longer being
     * included in the overall options menu.  Receiving this call means that
     * the menu needed to be rebuilt, but this fragment's items were not
     * included in the newly built menu (its {@link #onCreateOptionsMenu(Menu, MenuInflater)}
     * was not called).
     */
    public void onDestroyOptionsMenu() {
    }

    /**
     * This hook is called whenever an item in your options menu is selected.
     * The default implementation simply returns false to have the normal
     * processing happen (calling the item's Runnable or sending a message to
     * its Handler as appropriate).  You can use this method for any items
     * for which you would like to do processing without those other
     * facilities.
     *
     * <p>Derived classes should call through to the base class for it to
     * perform the default menu handling.
     *
     * @param item The menu item that was selected.
     *
     * @return boolean Return false to allow normal menu processing to
     *         proceed, true to consume it here.
     *
     * @see #onCreateOptionsMenu
     */
    public boolean onOptionsItemSelected(MenuItem item) {
        return false;
    }

    /**
     * This hook is called whenever the options menu is being closed (either by the user canceling
     * the menu with the back/menu button, or when an item is selected).
     *
     * @param menu The options menu as last shown or first initialized by
     *             onCreateOptionsMenu().
     */
    public void onOptionsMenuClosed(Menu menu) {
    }

    /**
     * Called when a context menu for the {@code view} is about to be shown.
     * Unlike {@link #onCreateOptionsMenu}, this will be called every
     * time the context menu is about to be shown and should be populated for
     * the view (or item inside the view for {@link AdapterView} subclasses,
     * this can be found in the {@code menuInfo})).
     * <p>
     * Use {@link #onContextItemSelected(android.view.MenuItem)} to know when an
     * item has been selected.
     * <p>
     * The default implementation calls up to
     * {@link Activity#onCreateContextMenu Activity.onCreateContextMenu}, though
     * you can not call this implementation if you don't want that behavior.
     * <p>
     * It is not safe to hold onto the context menu after this method returns.
     * {@inheritDoc}
     */
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        getActivity().onCreateContextMenu(menu, v, menuInfo);
    }

    /**
     * Registers a context menu to be shown for the given view (multiple views
     * can show the context menu). This method will set the
     * {@link OnCreateContextMenuListener} on the view to this fragment, so
     * {@link #onCreateContextMenu(ContextMenu, View, ContextMenuInfo)} will be
     * called when it is time to show the context menu.
     *
     * @see #unregisterForContextMenu(View)
     * @param view The view that should show a context menu.
     */
    public void registerForContextMenu(View view) {
        view.setOnCreateContextMenuListener(this);
    }

    /**
     * Prevents a context menu to be shown for the given view. This method will
     * remove the {@link OnCreateContextMenuListener} on the view.
     *
     * @see #registerForContextMenu(View)
     * @param view The view that should stop showing a context menu.
     */
    public void unregisterForContextMenu(View view) {
        view.setOnCreateContextMenuListener(null);
    }

    /**
     * This hook is called whenever an item in a context menu is selected. The
     * default implementation simply returns false to have the normal processing
     * happen (calling the item's Runnable or sending a message to its Handler
     * as appropriate). You can use this method for any items for which you
     * would like to do processing without those other facilities.
     * <p>
     * Use {@link MenuItem#getMenuInfo()} to get extra information set by the
     * View that added this menu item.
     * <p>
     * Derived classes should call through to the base class for it to perform
     * the default menu handling.
     *
     * @param item The context menu item that was selected.
     * @return boolean Return false to allow normal context menu processing to
     *         proceed, true to consume it here.
     */
    public boolean onContextItemSelected(MenuItem item) {
        return false;
    }

    /**
     * When custom transitions are used with Fragments, the enter transition callback
     * is called when this Fragment is attached or detached when not popping the back stack.
     *
     * @param callback Used to manipulate the shared element transitions on this Fragment
     *                 when added not as a pop from the back stack.
     */
    public void setEnterSharedElementCallback(SharedElementCallback callback) {
        if (callback == null) {
            if (mAnimationInfo == null) {
                return; // already a null callback
            }
            callback = SharedElementCallback.NULL_CALLBACK;
        }
        ensureAnimationInfo().mEnterTransitionCallback = callback;
    }

    /**
     * When custom transitions are used with Fragments, the exit transition callback
     * is called when this Fragment is attached or detached when popping the back stack.
     *
     * @param callback Used to manipulate the shared element transitions on this Fragment
     *                 when added as a pop from the back stack.
     */
    public void setExitSharedElementCallback(SharedElementCallback callback) {
        if (callback == null) {
            if (mAnimationInfo == null) {
                return; // already a null callback
            }
            callback = SharedElementCallback.NULL_CALLBACK;
        }
        ensureAnimationInfo().mExitTransitionCallback = callback;
    }

    /**
     * Sets the Transition that will be used to move Views into the initial scene. The entering
     * Views will be those that are regular Views or ViewGroups that have
     * {@link ViewGroup#isTransitionGroup} return true. Typical Transitions will extend
     * {@link android.transition.Visibility} as entering is governed by changing visibility from
     * {@link View#INVISIBLE} to {@link View#VISIBLE}. If <code>transition</code> is null,
     * entering Views will remain unaffected.
     *
     * @param transition The Transition to use to move Views into the initial Scene.
     * @attr ref android.R.styleable#Fragment_fragmentEnterTransition
     */
    public void setEnterTransition(Transition transition) {
        if (shouldChangeTransition(transition, null)) {
            ensureAnimationInfo().mEnterTransition = transition;
        }
    }

    /**
     * Returns the Transition that will be used to move Views into the initial scene. The entering
     * Views will be those that are regular Views or ViewGroups that have
     * {@link ViewGroup#isTransitionGroup} return true. Typical Transitions will extend
     * {@link android.transition.Visibility} as entering is governed by changing visibility from
     * {@link View#INVISIBLE} to {@link View#VISIBLE}.
     *
     * @return the Transition to use to move Views into the initial Scene.
     * @attr ref android.R.styleable#Fragment_fragmentEnterTransition
     */
    public Transition getEnterTransition() {
        if (mAnimationInfo == null) {
            return null;
        }
        return mAnimationInfo.mEnterTransition;
    }

    /**
     * Sets the Transition that will be used to move Views out of the scene when the Fragment is
     * preparing to be removed, hidden, or detached because of popping the back stack. The exiting
     * Views will be those that are regular Views or ViewGroups that have
     * {@link ViewGroup#isTransitionGroup} return true. Typical Transitions will extend
     * {@link android.transition.Visibility} as entering is governed by changing visibility from
     * {@link View#VISIBLE} to {@link View#INVISIBLE}. If <code>transition</code> is null,
     * entering Views will remain unaffected. If nothing is set, the default will be to
     * use the same value as set in {@link #setEnterTransition(android.transition.Transition)}.
     *
     * @param transition The Transition to use to move Views out of the Scene when the Fragment
     *                   is preparing to close.
     * @attr ref android.R.styleable#Fragment_fragmentExitTransition
     */
    public void setReturnTransition(Transition transition) {
        if (shouldChangeTransition(transition, USE_DEFAULT_TRANSITION)) {
            ensureAnimationInfo().mReturnTransition = transition;
        }
    }

    /**
     * Returns the Transition that will be used to move Views out of the scene when the Fragment is
     * preparing to be removed, hidden, or detached because of popping the back stack. The exiting
     * Views will be those that are regular Views or ViewGroups that have
     * {@link ViewGroup#isTransitionGroup} return true. Typical Transitions will extend
     * {@link android.transition.Visibility} as entering is governed by changing visibility from
     * {@link View#VISIBLE} to {@link View#INVISIBLE}. If <code>transition</code> is null,
     * entering Views will remain unaffected.
     *
     * @return the Transition to use to move Views out of the Scene when the Fragment
     *         is preparing to close.
     * @attr ref android.R.styleable#Fragment_fragmentExitTransition
     */
    public Transition getReturnTransition() {
        if (mAnimationInfo == null) {
            return null;
        }
        return mAnimationInfo.mReturnTransition == USE_DEFAULT_TRANSITION ? getEnterTransition()
                : mAnimationInfo.mReturnTransition;
    }

    /**
     * Sets the Transition that will be used to move Views out of the scene when the
     * fragment is removed, hidden, or detached when not popping the back stack.
     * The exiting Views will be those that are regular Views or ViewGroups that
     * have {@link ViewGroup#isTransitionGroup} return true. Typical Transitions will extend
     * {@link android.transition.Visibility} as exiting is governed by changing visibility
     * from {@link View#VISIBLE} to {@link View#INVISIBLE}. If transition is null, the views will
     * remain unaffected.
     *
     * @param transition The Transition to use to move Views out of the Scene when the Fragment
     *                   is being closed not due to popping the back stack.
     * @attr ref android.R.styleable#Fragment_fragmentExitTransition
     */
    public void setExitTransition(Transition transition) {
        if (shouldChangeTransition(transition, null)) {
            ensureAnimationInfo().mExitTransition = transition;
        }
    }

    /**
     * Returns the Transition that will be used to move Views out of the scene when the
     * fragment is removed, hidden, or detached when not popping the back stack.
     * The exiting Views will be those that are regular Views or ViewGroups that
     * have {@link ViewGroup#isTransitionGroup} return true. Typical Transitions will extend
     * {@link android.transition.Visibility} as exiting is governed by changing visibility
     * from {@link View#VISIBLE} to {@link View#INVISIBLE}. If transition is null, the views will
     * remain unaffected.
     *
     * @return the Transition to use to move Views out of the Scene when the Fragment
     *         is being closed not due to popping the back stack.
     * @attr ref android.R.styleable#Fragment_fragmentExitTransition
     */
    public Transition getExitTransition() {
        if (mAnimationInfo == null) {
            return null;
        }
        return mAnimationInfo.mExitTransition;
    }

    /**
     * Sets the Transition that will be used to move Views in to the scene when returning due
     * to popping a back stack. The entering Views will be those that are regular Views
     * or ViewGroups that have {@link ViewGroup#isTransitionGroup} return true. Typical Transitions
     * will extend {@link android.transition.Visibility} as exiting is governed by changing
     * visibility from {@link View#VISIBLE} to {@link View#INVISIBLE}. If transition is null,
     * the views will remain unaffected. If nothing is set, the default will be to use the same
     * transition as {@link #setExitTransition(android.transition.Transition)}.
     *
     * @param transition The Transition to use to move Views into the scene when reentering from a
     *                   previously-started Activity.
     * @attr ref android.R.styleable#Fragment_fragmentReenterTransition
     */
    public void setReenterTransition(Transition transition) {
        if (shouldChangeTransition(transition, USE_DEFAULT_TRANSITION)) {
            ensureAnimationInfo().mReenterTransition = transition;
        }
    }

    /**
     * Returns the Transition that will be used to move Views in to the scene when returning due
     * to popping a back stack. The entering Views will be those that are regular Views
     * or ViewGroups that have {@link ViewGroup#isTransitionGroup} return true. Typical Transitions
     * will extend {@link android.transition.Visibility} as exiting is governed by changing
     * visibility from {@link View#VISIBLE} to {@link View#INVISIBLE}. If transition is null,
     * the views will remain unaffected. If nothing is set, the default will be to use the same
     * transition as {@link #setExitTransition(android.transition.Transition)}.
     *
     * @return the Transition to use to move Views into the scene when reentering from a
     *                   previously-started Activity.
     * @attr ref android.R.styleable#Fragment_fragmentReenterTransition
     */
    public Transition getReenterTransition() {
        if (mAnimationInfo == null) {
            return null;
        }
        return mAnimationInfo.mReenterTransition == USE_DEFAULT_TRANSITION ? getExitTransition()
                : mAnimationInfo.mReenterTransition;
    }

    /**
     * Sets the Transition that will be used for shared elements transferred into the content
     * Scene. Typical Transitions will affect size and location, such as
     * {@link android.transition.ChangeBounds}. A null
     * value will cause transferred shared elements to blink to the final position.
     *
     * @param transition The Transition to use for shared elements transferred into the content
     *                   Scene.
     * @attr ref android.R.styleable#Fragment_fragmentSharedElementEnterTransition
     */
    public void setSharedElementEnterTransition(Transition transition) {
        if (shouldChangeTransition(transition, null)) {
            ensureAnimationInfo().mSharedElementEnterTransition = transition;
        }
    }

    /**
     * Returns the Transition that will be used for shared elements transferred into the content
     * Scene. Typical Transitions will affect size and location, such as
     * {@link android.transition.ChangeBounds}. A null
     * value will cause transferred shared elements to blink to the final position.
     *
     * @return The Transition to use for shared elements transferred into the content
     *                   Scene.
     * @attr ref android.R.styleable#Fragment_fragmentSharedElementEnterTransition
     */
    public Transition getSharedElementEnterTransition() {
        if (mAnimationInfo == null) {
            return null;
        }
        return mAnimationInfo.mSharedElementEnterTransition;
    }

    /**
     * Sets the Transition that will be used for shared elements transferred back during a
     * pop of the back stack. This Transition acts in the leaving Fragment.
     * Typical Transitions will affect size and location, such as
     * {@link android.transition.ChangeBounds}. A null
     * value will cause transferred shared elements to blink to the final position.
     * If no value is set, the default will be to use the same value as
     * {@link #setSharedElementEnterTransition(android.transition.Transition)}.
     *
     * @param transition The Transition to use for shared elements transferred out of the content
     *                   Scene.
     * @attr ref android.R.styleable#Fragment_fragmentSharedElementReturnTransition
     */
    public void setSharedElementReturnTransition(Transition transition) {
        if (shouldChangeTransition(transition, USE_DEFAULT_TRANSITION)) {
            ensureAnimationInfo().mSharedElementReturnTransition = transition;
        }
    }

    /**
     * Return the Transition that will be used for shared elements transferred back during a
     * pop of the back stack. This Transition acts in the leaving Fragment.
     * Typical Transitions will affect size and location, such as
     * {@link android.transition.ChangeBounds}. A null
     * value will cause transferred shared elements to blink to the final position.
     * If no value is set, the default will be to use the same value as
     * {@link #setSharedElementEnterTransition(android.transition.Transition)}.
     *
     * @return The Transition to use for shared elements transferred out of the content
     *                   Scene.
     * @attr ref android.R.styleable#Fragment_fragmentSharedElementReturnTransition
     */
    public Transition getSharedElementReturnTransition() {
        if (mAnimationInfo == null) {
            return null;
        }
        return mAnimationInfo.mSharedElementReturnTransition == USE_DEFAULT_TRANSITION
                ? getSharedElementEnterTransition()
                : mAnimationInfo.mSharedElementReturnTransition;
    }

    /**
     * Sets whether the exit transition and enter transition overlap or not.
     * When true, the enter transition will start as soon as possible. When false, the
     * enter transition will wait until the exit transition completes before starting.
     *
     * @param allow true to start the enter transition when possible or false to
     *              wait until the exiting transition completes.
     * @attr ref android.R.styleable#Fragment_fragmentAllowEnterTransitionOverlap
     */
    public void setAllowEnterTransitionOverlap(boolean allow) {
        ensureAnimationInfo().mAllowEnterTransitionOverlap = allow;
    }

    /**
     * Returns whether the exit transition and enter transition overlap or not.
     * When true, the enter transition will start as soon as possible. When false, the
     * enter transition will wait until the exit transition completes before starting.
     *
     * @return true when the enter transition should start as soon as possible or false to
     * when it should wait until the exiting transition completes.
     * @attr ref android.R.styleable#Fragment_fragmentAllowEnterTransitionOverlap
     */
    public boolean getAllowEnterTransitionOverlap() {
        return (mAnimationInfo == null || mAnimationInfo.mAllowEnterTransitionOverlap == null)
                ? true : mAnimationInfo.mAllowEnterTransitionOverlap;
    }

    /**
     * Sets whether the return transition and reenter transition overlap or not.
     * When true, the reenter transition will start as soon as possible. When false, the
     * reenter transition will wait until the return transition completes before starting.
     *
     * @param allow true to start the reenter transition when possible or false to wait until the
     *              return transition completes.
     * @attr ref android.R.styleable#Fragment_fragmentAllowReturnTransitionOverlap
     */
    public void setAllowReturnTransitionOverlap(boolean allow) {
        ensureAnimationInfo().mAllowReturnTransitionOverlap = allow;
    }

    /**
     * Returns whether the return transition and reenter transition overlap or not.
     * When true, the reenter transition will start as soon as possible. When false, the
     * reenter transition will wait until the return transition completes before starting.
     *
     * @return true to start the reenter transition when possible or false to wait until the
     *         return transition completes.
     * @attr ref android.R.styleable#Fragment_fragmentAllowReturnTransitionOverlap
     */
    public boolean getAllowReturnTransitionOverlap() {
        return (mAnimationInfo == null || mAnimationInfo.mAllowReturnTransitionOverlap == null)
                ? true : mAnimationInfo.mAllowReturnTransitionOverlap;
    }

    /**
     * Postpone the entering Fragment transition until {@link #startPostponedEnterTransition()}
     * or {@link FragmentManager#executePendingTransactions()} has been called.
     * <p>
     * This method gives the Fragment the ability to delay Fragment animations
     * until all data is loaded. Until then, the added, shown, and
     * attached Fragments will be INVISIBLE and removed, hidden, and detached Fragments won't
     * be have their Views removed. The transaction runs when all postponed added Fragments in the
     * transaction have called {@link #startPostponedEnterTransition()}.
     * <p>
     * This method should be called before being added to the FragmentTransaction or
     * in {@link #onCreate(Bundle)}, {@link #onAttach(Context)}, or
     * {@link #onCreateView(LayoutInflater, ViewGroup, Bundle)}}.
     * {@link #startPostponedEnterTransition()} must be called to allow the Fragment to
     * start the transitions.
     * <p>
     * When a FragmentTransaction is started that may affect a postponed FragmentTransaction,
     * based on which containers are in their operations, the postponed FragmentTransaction
     * will have its start triggered. The early triggering may result in faulty or nonexistent
     * animations in the postponed transaction. FragmentTransactions that operate only on
     * independent containers will not interfere with each other's postponement.
     * <p>
     * Calling postponeEnterTransition on Fragments with a null View will not postpone the
     * transition. Likewise, postponement only works if FragmentTransaction optimizations are
     * enabled.
     *
     * @see Activity#postponeEnterTransition()
     * @see FragmentTransaction#setReorderingAllowed(boolean)
     */
    public void postponeEnterTransition() {
        ensureAnimationInfo().mEnterTransitionPostponed = true;
    }

    /**
     * Begin postponed transitions after {@link #postponeEnterTransition()} was called.
     * If postponeEnterTransition() was called, you must call startPostponedEnterTransition()
     * or {@link FragmentManager#executePendingTransactions()} to complete the FragmentTransaction.
     * If postponement was interrupted with {@link FragmentManager#executePendingTransactions()},
     * before {@code startPostponedEnterTransition()}, animations may not run or may execute
     * improperly.
     *
     * @see Activity#startPostponedEnterTransition()
     */
    public void startPostponedEnterTransition() {
        if (mFragmentManager == null || mFragmentManager.mHost == null) {
                ensureAnimationInfo().mEnterTransitionPostponed = false;
        } else if (Looper.myLooper() != mFragmentManager.mHost.getHandler().getLooper()) {
            mFragmentManager.mHost.getHandler().
                    postAtFrontOfQueue(this::callStartTransitionListener);
        } else {
            callStartTransitionListener();
        }
    }

    /**
     * Calls the start transition listener. This must be called on the UI thread.
     */
    private void callStartTransitionListener() {
        final OnStartEnterTransitionListener listener;
        if (mAnimationInfo == null) {
            listener = null;
        } else {
            mAnimationInfo.mEnterTransitionPostponed = false;
            listener = mAnimationInfo.mStartEnterTransitionListener;
            mAnimationInfo.mStartEnterTransitionListener = null;
        }
        if (listener != null) {
            listener.onStartEnterTransition();
        }
    }

    /**
     * Returns true if mAnimationInfo is not null or the transition differs from the default value.
     * This is broken out to ensure mAnimationInfo is properly locked when checking.
     */
    private boolean shouldChangeTransition(Transition transition, Transition defaultValue) {
        if (transition == defaultValue) {
            return mAnimationInfo != null;
        }
        return true;
    }

    /**
     * Print the Fragments's state into the given stream.
     *
     * @param prefix Text to print at the front of each line.
     * @param fd The raw file descriptor that the dump is being sent to.
     * @param writer The PrintWriter to which you should dump your state.  This will be
     * closed for you after you return.
     * @param args additional arguments to the dump request.
     */
    public void dump(String prefix, FileDescriptor fd, PrintWriter writer, String[] args) {
        writer.print(prefix); writer.print("mFragmentId=#");
        writer.print(Integer.toHexString(mFragmentId));
        writer.print(" mContainerId=#");
        writer.print(Integer.toHexString(mContainerId));
        writer.print(" mTag="); writer.println(mTag);
        writer.print(prefix); writer.print("mState="); writer.print(mState);
        writer.print(" mIndex="); writer.print(mIndex);
        writer.print(" mWho="); writer.print(mWho);
        writer.print(" mBackStackNesting="); writer.println(mBackStackNesting);
        writer.print(prefix); writer.print("mAdded="); writer.print(mAdded);
        writer.print(" mRemoving="); writer.print(mRemoving);
        writer.print(" mFromLayout="); writer.print(mFromLayout);
        writer.print(" mInLayout="); writer.println(mInLayout);
        writer.print(prefix); writer.print("mHidden="); writer.print(mHidden);
        writer.print(" mDetached="); writer.print(mDetached);
        writer.print(" mMenuVisible="); writer.print(mMenuVisible);
        writer.print(" mHasMenu="); writer.println(mHasMenu);
        writer.print(prefix); writer.print("mRetainInstance="); writer.print(mRetainInstance);
        writer.print(" mRetaining="); writer.print(mRetaining);
        writer.print(" mUserVisibleHint="); writer.println(mUserVisibleHint);
        if (mFragmentManager != null) {
            writer.print(prefix); writer.print("mFragmentManager=");
            writer.println(mFragmentManager);
        }
        if (mHost != null) {
            writer.print(prefix); writer.print("mHost=");
            writer.println(mHost);
        }
        if (mParentFragment != null) {
            writer.print(prefix); writer.print("mParentFragment=");
            writer.println(mParentFragment);
        }
        if (mArguments != null) {
            writer.print(prefix); writer.print("mArguments="); writer.println(mArguments);
        }
        if (mSavedFragmentState != null) {
            writer.print(prefix); writer.print("mSavedFragmentState=");
            writer.println(mSavedFragmentState);
        }
        if (mSavedViewState != null) {
            writer.print(prefix); writer.print("mSavedViewState=");
            writer.println(mSavedViewState);
        }
        if (mTarget != null) {
            writer.print(prefix); writer.print("mTarget="); writer.print(mTarget);
            writer.print(" mTargetRequestCode=");
            writer.println(mTargetRequestCode);
        }
        if (getNextAnim() != 0) {
            writer.print(prefix); writer.print("mNextAnim="); writer.println(getNextAnim());
        }
        if (mContainer != null) {
            writer.print(prefix); writer.print("mContainer="); writer.println(mContainer);
        }
        if (mView != null) {
            writer.print(prefix); writer.print("mView="); writer.println(mView);
        }
        if (getAnimatingAway() != null) {
            writer.print(prefix); writer.print("mAnimatingAway=");
            writer.println(getAnimatingAway());
            writer.print(prefix); writer.print("mStateAfterAnimating=");
            writer.println(getStateAfterAnimating());
        }
        if (mLoaderManager != null) {
            writer.print(prefix); writer.println("Loader Manager:");
            mLoaderManager.dump(prefix + "  ", fd, writer, args);
        }
        if (mChildFragmentManager != null) {
            writer.print(prefix); writer.println("Child " + mChildFragmentManager + ":");
            mChildFragmentManager.dump(prefix + "  ", fd, writer, args);
        }
    }

    Fragment findFragmentByWho(String who) {
        if (who.equals(mWho)) {
            return this;
        }
        if (mChildFragmentManager != null) {
            return mChildFragmentManager.findFragmentByWho(who);
        }
        return null;
    }

    void instantiateChildFragmentManager() {
        mChildFragmentManager = new FragmentManagerImpl();
        mChildFragmentManager.attachController(mHost, new FragmentContainer() {
            @Override
            @Nullable
            public <T extends View> T onFindViewById(int id) {
                if (mView == null) {
                    throw new IllegalStateException("Fragment does not have a view");
                }
                return mView.findViewById(id);
            }

            @Override
            public boolean onHasView() {
                return (mView != null);
            }
        }, this);
    }

    void performCreate(Bundle savedInstanceState) {
        if (mChildFragmentManager != null) {
            mChildFragmentManager.noteStateNotSaved();
        }
        mState = CREATED;
        mCalled = false;
        onCreate(savedInstanceState);
        mIsCreated = true;
        if (!mCalled) {
            throw new SuperNotCalledException("Fragment " + this
                    + " did not call through to super.onCreate()");
        }
        final Context context = getContext();
        final int version = context != null ? context.getApplicationInfo().targetSdkVersion : 0;
        if (version < Build.VERSION_CODES.N) {
            restoreChildFragmentState(savedInstanceState, false);
        }
    }

    View performCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        if (mChildFragmentManager != null) {
            mChildFragmentManager.noteStateNotSaved();
        }
        mPerformedCreateView = true;
        return onCreateView(inflater, container, savedInstanceState);
    }

    void performActivityCreated(Bundle savedInstanceState) {
        if (mChildFragmentManager != null) {
            mChildFragmentManager.noteStateNotSaved();
        }
        mState = ACTIVITY_CREATED;
        mCalled = false;
        onActivityCreated(savedInstanceState);
        if (!mCalled) {
            throw new SuperNotCalledException("Fragment " + this
                    + " did not call through to super.onActivityCreated()");
        }
        if (mChildFragmentManager != null) {
            mChildFragmentManager.dispatchActivityCreated();
        }
    }

    void performStart() {
        if (mChildFragmentManager != null) {
            mChildFragmentManager.noteStateNotSaved();
            mChildFragmentManager.execPendingActions();
        }
        mState = STARTED;
        mCalled = false;
        onStart();
        if (!mCalled) {
            throw new SuperNotCalledException("Fragment " + this
                    + " did not call through to super.onStart()");
        }
        if (mChildFragmentManager != null) {
            mChildFragmentManager.dispatchStart();
        }
        if (mLoaderManager != null) {
            mLoaderManager.doReportStart();
        }
    }

    void performResume() {
        if (mChildFragmentManager != null) {
            mChildFragmentManager.noteStateNotSaved();
            mChildFragmentManager.execPendingActions();
        }
        mState = RESUMED;
        mCalled = false;
        onResume();
        if (!mCalled) {
            throw new SuperNotCalledException("Fragment " + this
                    + " did not call through to super.onResume()");
        }
        if (mChildFragmentManager != null) {
            mChildFragmentManager.dispatchResume();
            mChildFragmentManager.execPendingActions();
        }
    }

    void noteStateNotSaved() {
        if (mChildFragmentManager != null) {
            mChildFragmentManager.noteStateNotSaved();
        }
    }

    @Deprecated
    void performMultiWindowModeChanged(boolean isInMultiWindowMode) {
        onMultiWindowModeChanged(isInMultiWindowMode);
        if (mChildFragmentManager != null) {
            mChildFragmentManager.dispatchMultiWindowModeChanged(isInMultiWindowMode);
        }
    }

    void performMultiWindowModeChanged(boolean isInMultiWindowMode, Configuration newConfig) {
        onMultiWindowModeChanged(isInMultiWindowMode, newConfig);
        if (mChildFragmentManager != null) {
            mChildFragmentManager.dispatchMultiWindowModeChanged(isInMultiWindowMode, newConfig);
        }
    }

    @Deprecated
    void performPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        onPictureInPictureModeChanged(isInPictureInPictureMode);
        if (mChildFragmentManager != null) {
            mChildFragmentManager.dispatchPictureInPictureModeChanged(isInPictureInPictureMode);
        }
    }

    void performPictureInPictureModeChanged(boolean isInPictureInPictureMode,
            Configuration newConfig) {
        onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        if (mChildFragmentManager != null) {
            mChildFragmentManager.dispatchPictureInPictureModeChanged(isInPictureInPictureMode,
                    newConfig);
        }
    }

    void performConfigurationChanged(Configuration newConfig) {
        onConfigurationChanged(newConfig);
        if (mChildFragmentManager != null) {
            mChildFragmentManager.dispatchConfigurationChanged(newConfig);
        }
    }

    void performLowMemory() {
        onLowMemory();
        if (mChildFragmentManager != null) {
            mChildFragmentManager.dispatchLowMemory();
        }
    }

    void performTrimMemory(int level) {
        onTrimMemory(level);
        if (mChildFragmentManager != null) {
            mChildFragmentManager.dispatchTrimMemory(level);
        }
    }

    boolean performCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        boolean show = false;
        if (!mHidden) {
            if (mHasMenu && mMenuVisible) {
                show = true;
                onCreateOptionsMenu(menu, inflater);
            }
            if (mChildFragmentManager != null) {
                show |= mChildFragmentManager.dispatchCreateOptionsMenu(menu, inflater);
            }
        }
        return show;
    }

    boolean performPrepareOptionsMenu(Menu menu) {
        boolean show = false;
        if (!mHidden) {
            if (mHasMenu && mMenuVisible) {
                show = true;
                onPrepareOptionsMenu(menu);
            }
            if (mChildFragmentManager != null) {
                show |= mChildFragmentManager.dispatchPrepareOptionsMenu(menu);
            }
        }
        return show;
    }

    boolean performOptionsItemSelected(MenuItem item) {
        if (!mHidden) {
            if (mHasMenu && mMenuVisible) {
                if (onOptionsItemSelected(item)) {
                    return true;
                }
            }
            if (mChildFragmentManager != null) {
                if (mChildFragmentManager.dispatchOptionsItemSelected(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    boolean performContextItemSelected(MenuItem item) {
        if (!mHidden) {
            if (onContextItemSelected(item)) {
                return true;
            }
            if (mChildFragmentManager != null) {
                if (mChildFragmentManager.dispatchContextItemSelected(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    void performOptionsMenuClosed(Menu menu) {
        if (!mHidden) {
            if (mHasMenu && mMenuVisible) {
                onOptionsMenuClosed(menu);
            }
            if (mChildFragmentManager != null) {
                mChildFragmentManager.dispatchOptionsMenuClosed(menu);
            }
        }
    }

    void performSaveInstanceState(Bundle outState) {
        onSaveInstanceState(outState);
        if (mChildFragmentManager != null) {
            Parcelable p = mChildFragmentManager.saveAllState();
            if (p != null) {
                outState.putParcelable(Activity.FRAGMENTS_TAG, p);
            }
        }
    }

    void performPause() {
        if (mChildFragmentManager != null) {
            mChildFragmentManager.dispatchPause();
        }
        mState = STARTED;
        mCalled = false;
        onPause();
        if (!mCalled) {
            throw new SuperNotCalledException("Fragment " + this
                    + " did not call through to super.onPause()");
        }
    }

    void performStop() {
        if (mChildFragmentManager != null) {
            mChildFragmentManager.dispatchStop();
        }
        mState = STOPPED;
        mCalled = false;
        onStop();
        if (!mCalled) {
            throw new SuperNotCalledException("Fragment " + this
                    + " did not call through to super.onStop()");
        }

        if (mLoadersStarted) {
            mLoadersStarted = false;
            if (!mCheckedForLoaderManager) {
                mCheckedForLoaderManager = true;
                mLoaderManager = mHost.getLoaderManager(mWho, mLoadersStarted, false);
            }
            if (mLoaderManager != null) {
                if (mHost.getRetainLoaders()) {
                    mLoaderManager.doRetain();
                } else {
                    mLoaderManager.doStop();
                }
            }
        }
    }

    void performDestroyView() {
        if (mChildFragmentManager != null) {
            mChildFragmentManager.dispatchDestroyView();
        }
        mState = CREATED;
        mCalled = false;
        onDestroyView();
        if (!mCalled) {
            throw new SuperNotCalledException("Fragment " + this
                    + " did not call through to super.onDestroyView()");
        }
        if (mLoaderManager != null) {
            mLoaderManager.doReportNextStart();
        }
        mPerformedCreateView = false;
    }

    void performDestroy() {
        if (mChildFragmentManager != null) {
            mChildFragmentManager.dispatchDestroy();
        }
        mState = INITIALIZING;
        mCalled = false;
        mIsCreated = false;
        onDestroy();
        if (!mCalled) {
            throw new SuperNotCalledException("Fragment " + this
                    + " did not call through to super.onDestroy()");
        }
        mChildFragmentManager = null;
    }

    void performDetach() {
        mCalled = false;
        onDetach();
        mLayoutInflater = null;
        if (!mCalled) {
            throw new SuperNotCalledException("Fragment " + this
                    + " did not call through to super.onDetach()");
        }

        // Destroy the child FragmentManager if we still have it here.
        // We won't unless we're retaining our instance and if we do,
        // our child FragmentManager instance state will have already been saved.
        if (mChildFragmentManager != null) {
            if (!mRetaining) {
                throw new IllegalStateException("Child FragmentManager of " + this + " was not "
                        + " destroyed and this fragment is not retaining instance");
            }
            mChildFragmentManager.dispatchDestroy();
            mChildFragmentManager = null;
        }
    }

    void setOnStartEnterTransitionListener(OnStartEnterTransitionListener listener) {
        ensureAnimationInfo();
        if (listener == mAnimationInfo.mStartEnterTransitionListener) {
            return;
        }
        if (listener != null && mAnimationInfo.mStartEnterTransitionListener != null) {
            throw new IllegalStateException("Trying to set a replacement " +
                    "startPostponedEnterTransition on " + this);
        }
        if (mAnimationInfo.mEnterTransitionPostponed) {
            mAnimationInfo.mStartEnterTransitionListener = listener;
        }
        if (listener != null) {
            listener.startListening();
        }
    }

    private static Transition loadTransition(Context context, TypedArray typedArray,
            Transition currentValue, Transition defaultValue, int id) {
        if (currentValue != defaultValue) {
            return currentValue;
        }
        int transitionId = typedArray.getResourceId(id, 0);
        Transition transition = defaultValue;
        if (transitionId != 0 && transitionId != com.android.internal.R.transition.no_transition) {
            TransitionInflater inflater = TransitionInflater.from(context);
            transition = inflater.inflateTransition(transitionId);
            if (transition instanceof TransitionSet &&
                    ((TransitionSet)transition).getTransitionCount() == 0) {
                transition = null;
            }
        }
        return transition;
    }

    private AnimationInfo ensureAnimationInfo() {
        if (mAnimationInfo == null) {
            mAnimationInfo = new AnimationInfo();
        }
        return mAnimationInfo;
    }

    int getNextAnim() {
        if (mAnimationInfo == null) {
            return 0;
        }
        return mAnimationInfo.mNextAnim;
    }

    void setNextAnim(int animResourceId) {
        if (mAnimationInfo == null && animResourceId == 0) {
            return; // no change!
        }
        ensureAnimationInfo().mNextAnim = animResourceId;
    }

    int getNextTransition() {
        if (mAnimationInfo == null) {
            return 0;
        }
        return mAnimationInfo.mNextTransition;
    }

    void setNextTransition(int nextTransition, int nextTransitionStyle) {
        if (mAnimationInfo == null && nextTransition == 0 && nextTransitionStyle == 0) {
            return; // no change!
        }
        ensureAnimationInfo();
        mAnimationInfo.mNextTransition = nextTransition;
        mAnimationInfo.mNextTransitionStyle = nextTransitionStyle;
    }

    int getNextTransitionStyle() {
        if (mAnimationInfo == null) {
            return 0;
        }
        return mAnimationInfo.mNextTransitionStyle;
    }

    SharedElementCallback getEnterTransitionCallback() {
        if (mAnimationInfo == null) {
            return SharedElementCallback.NULL_CALLBACK;
        }
        return mAnimationInfo.mEnterTransitionCallback;
    }

    SharedElementCallback getExitTransitionCallback() {
        if (mAnimationInfo == null) {
            return SharedElementCallback.NULL_CALLBACK;
        }
        return mAnimationInfo.mExitTransitionCallback;
    }

    Animator getAnimatingAway() {
        if (mAnimationInfo == null) {
            return null;
        }
        return mAnimationInfo.mAnimatingAway;
    }

    void setAnimatingAway(Animator animator) {
        ensureAnimationInfo().mAnimatingAway = animator;
    }

    int getStateAfterAnimating() {
        if (mAnimationInfo == null) {
            return 0;
        }
        return mAnimationInfo.mStateAfterAnimating;
    }

    void setStateAfterAnimating(int state) {
        ensureAnimationInfo().mStateAfterAnimating = state;
    }

    boolean isPostponed() {
        if (mAnimationInfo == null) {
            return false;
        }
        return mAnimationInfo.mEnterTransitionPostponed;
    }

    boolean isHideReplaced() {
        if (mAnimationInfo == null) {
            return false;
        }
        return mAnimationInfo.mIsHideReplaced;
    }

    void setHideReplaced(boolean replaced) {
        ensureAnimationInfo().mIsHideReplaced = replaced;
    }

    /**
     * Used internally to be notified when {@link #startPostponedEnterTransition()} has
     * been called. This listener will only be called once and then be removed from the
     * listeners.
     */
    interface OnStartEnterTransitionListener {
        void onStartEnterTransition();
        void startListening();
    }

    /**
     * Contains all the animation and transition information for a fragment. This will only
     * be instantiated for Fragments that have Views.
     */
    static class AnimationInfo {
        // Non-null if the fragment's view hierarchy is currently animating away,
        // meaning we need to wait a bit on completely destroying it.  This is the
        // animation that is running.
        Animator mAnimatingAway;

        // If mAnimatingAway != null, this is the state we should move to once the
        // animation is done.
        int mStateAfterAnimating;

        // If app has requested a specific animation, this is the one to use.
        int mNextAnim;

        // If app has requested a specific transition, this is the one to use.
        int mNextTransition;

        // If app has requested a specific transition style, this is the one to use.
        int mNextTransitionStyle;

        private Transition mEnterTransition = null;
        private Transition mReturnTransition = USE_DEFAULT_TRANSITION;
        private Transition mExitTransition = null;
        private Transition mReenterTransition = USE_DEFAULT_TRANSITION;
        private Transition mSharedElementEnterTransition = null;
        private Transition mSharedElementReturnTransition = USE_DEFAULT_TRANSITION;
        private Boolean mAllowReturnTransitionOverlap;
        private Boolean mAllowEnterTransitionOverlap;

        SharedElementCallback mEnterTransitionCallback = SharedElementCallback.NULL_CALLBACK;
        SharedElementCallback mExitTransitionCallback = SharedElementCallback.NULL_CALLBACK;

        // True when postponeEnterTransition has been called and startPostponeEnterTransition
        // hasn't been called yet.
        boolean mEnterTransitionPostponed;

        // Listener to wait for startPostponeEnterTransition. After being called, it will
        // be set to null
        OnStartEnterTransitionListener mStartEnterTransitionListener;

        // True if the View was hidden, but the transition is handling the hide
        boolean mIsHideReplaced;
    }
}
