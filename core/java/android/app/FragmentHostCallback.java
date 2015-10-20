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

package android.app;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.util.ArrayMap;
import android.view.LayoutInflater;
import android.view.View;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Integration points with the Fragment host.
 * <p>
 * Fragments may be hosted by any object; such as an {@link Activity}. In order to
 * host fragments, implement {@link FragmentHostCallback}, overriding the methods
 * applicable to the host.
 */
public abstract class FragmentHostCallback<E> extends FragmentContainer {
    private final Activity mActivity;
    final Context mContext;
    private final Handler mHandler;
    final int mWindowAnimations;
    final FragmentManagerImpl mFragmentManager = new FragmentManagerImpl();
    /** The loader managers for individual fragments [i.e. Fragment#getLoaderManager()] */
    private ArrayMap<String, LoaderManager> mAllLoaderManagers;
    /** Whether or not fragment loaders should retain their state */
    private boolean mRetainLoaders;
    /** The loader manger for the fragment host [i.e. Activity#getLoaderManager()] */
    private LoaderManagerImpl mLoaderManager;
    private boolean mCheckedForLoaderManager;
    /** Whether or not the fragment host loader manager was started */
    private boolean mLoadersStarted;

    public FragmentHostCallback(Context context, Handler handler, int windowAnimations) {
        this(null /*activity*/, context, handler, windowAnimations);
    }

    FragmentHostCallback(Activity activity) {
        this(activity, activity /*context*/, activity.mHandler, 0 /*windowAnimations*/);
    }

    FragmentHostCallback(Activity activity, Context context, Handler handler,
            int windowAnimations) {
        mActivity = activity;
        mContext = context;
        mHandler = handler;
        mWindowAnimations = windowAnimations;
    }

    /**
     * Print internal state into the given stream.
     *
     * @param prefix Desired prefix to prepend at each line of output.
     * @param fd The raw file descriptor that the dump is being sent to.
     * @param writer The PrintWriter to which you should dump your state. This will be closed
     *                  for you after you return.
     * @param args additional arguments to the dump request.
     */
    public void onDump(String prefix, FileDescriptor fd, PrintWriter writer, String[] args) {
    }

    /**
     * Return {@code true} if the fragment's state needs to be saved.
     */
    public boolean onShouldSaveFragmentState(Fragment fragment) {
        return true;
    }

    /**
     * Return a {@link LayoutInflater}.
     * See {@link Activity#getLayoutInflater()}.
     */
    public LayoutInflater onGetLayoutInflater() {
        return (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
    }

    /**
     * Return {@code true} if the FragmentManager's LayoutInflaterFactory should be used.
     */
    public boolean onUseFragmentManagerInflaterFactory() {
        return false;
    }

    /**
     * Return the object that's currently hosting the fragment. If a {@link Fragment}
     * is hosted by a {@link Activity}, the object returned here should be the same
     * object returned from {@link Fragment#getActivity()}.
     */
    @Nullable
    public abstract E onGetHost();

    /**
     * Invalidates the activity's options menu.
     * See {@link Activity#invalidateOptionsMenu()}
     */
    public void onInvalidateOptionsMenu() {
    }

    /**
     * Starts a new {@link Activity} from the given fragment.
     * See {@link Activity#startActivityForResult(Intent, int)}.
     */
    public void onStartActivityFromFragment(Fragment fragment, Intent intent, int requestCode,
            Bundle options) {
        if (requestCode != -1) {
            throw new IllegalStateException(
                    "Starting activity with a requestCode requires a FragmentActivity host");
        }
        mContext.startActivity(intent);
    }

    /**
     * Requests permissions from the given fragment.
     * See {@link Activity#requestPermissions(String[], int)}
     */
    public void onRequestPermissionsFromFragment(@NonNull Fragment fragment,
            @NonNull String[] permissions, int requestCode) {
    }

    /**
     * Return {@code true} if there are window animations.
     */
    public boolean onHasWindowAnimations() {
        return true;
    }

    /**
     * Return the window animations.
     */
    public int onGetWindowAnimations() {
        return mWindowAnimations;
    }

    /**
     * Called when a {@link Fragment} is being attached to this host, immediately
     * after the call to its {@link Fragment#onAttach(Context)} method and before
     * {@link Fragment#onCreate(Bundle)}.
     */
    public void onAttachFragment(Fragment fragment) {
    }

    @Nullable
    @Override
    public View onFindViewById(int id) {
        return null;
    }

    @Override
    public boolean onHasView() {
        return true;
    }

    boolean getRetainLoaders() {
        return mRetainLoaders;
    }

    Activity getActivity() {
        return mActivity;
    }

    Context getContext() {
        return mContext;
    }

    Handler getHandler() {
        return mHandler;
    }

    FragmentManagerImpl getFragmentManagerImpl() {
        return mFragmentManager;
    }

    LoaderManagerImpl getLoaderManagerImpl() {
        if (mLoaderManager != null) {
            return mLoaderManager;
        }
        mCheckedForLoaderManager = true;
        mLoaderManager = getLoaderManager("(root)", mLoadersStarted, true /*create*/);
        return mLoaderManager;
    }

    void inactivateFragment(String who) {
        //Log.v(TAG, "invalidateSupportFragment: who=" + who);
        if (mAllLoaderManagers != null) {
            LoaderManagerImpl lm = (LoaderManagerImpl) mAllLoaderManagers.get(who);
            if (lm != null && !lm.mRetaining) {
                lm.doDestroy();
                mAllLoaderManagers.remove(who);
            }
        }
    }

    void doLoaderStart() {
        if (mLoadersStarted) {
            return;
        }
        mLoadersStarted = true;

        if (mLoaderManager != null) {
            mLoaderManager.doStart();
        } else if (!mCheckedForLoaderManager) {
            mLoaderManager = getLoaderManager("(root)", mLoadersStarted, false);
        }
        mCheckedForLoaderManager = true;
    }

    void doLoaderStop(boolean retain) {
        mRetainLoaders = retain;

        if (mLoaderManager == null) {
            return;
        }

        if (!mLoadersStarted) {
            return;
        }
        mLoadersStarted = false;

        if (retain) {
            mLoaderManager.doRetain();
        } else {
            mLoaderManager.doStop();
        }
    }

    void doLoaderRetain() {
        if (mLoaderManager == null) {
            return;
        }
        mLoaderManager.doRetain();
    }

    void doLoaderDestroy() {
        if (mLoaderManager == null) {
            return;
        }
        mLoaderManager.doDestroy();
    }

    void reportLoaderStart() {
        if (mAllLoaderManagers != null) {
            final int N = mAllLoaderManagers.size();
            LoaderManagerImpl loaders[] = new LoaderManagerImpl[N];
            for (int i=N-1; i>=0; i--) {
                loaders[i] = (LoaderManagerImpl) mAllLoaderManagers.valueAt(i);
            }
            for (int i=0; i<N; i++) {
                LoaderManagerImpl lm = loaders[i];
                lm.finishRetain();
                lm.doReportStart();
            }
        }
    }

    LoaderManagerImpl getLoaderManager(String who, boolean started, boolean create) {
        if (mAllLoaderManagers == null) {
            mAllLoaderManagers = new ArrayMap<String, LoaderManager>();
        }
        LoaderManagerImpl lm = (LoaderManagerImpl) mAllLoaderManagers.get(who);
        if (lm == null) {
            if (create) {
                lm = new LoaderManagerImpl(who, this, started);
                mAllLoaderManagers.put(who, lm);
            }
        } else {
            lm.updateHostController(this);
        }
        return lm;
    }

    ArrayMap<String, LoaderManager> retainLoaderNonConfig() {
        boolean retainLoaders = false;
        if (mAllLoaderManagers != null) {
            // prune out any loader managers that were already stopped and so
            // have nothing useful to retain.
            final int N = mAllLoaderManagers.size();
            LoaderManagerImpl loaders[] = new LoaderManagerImpl[N];
            for (int i=N-1; i>=0; i--) {
                loaders[i] = (LoaderManagerImpl) mAllLoaderManagers.valueAt(i);
            }
            for (int i=0; i<N; i++) {
                LoaderManagerImpl lm = loaders[i];
                if (lm.mRetaining) {
                    retainLoaders = true;
                } else {
                    lm.doDestroy();
                    mAllLoaderManagers.remove(lm.mWho);
                }
            }
        }

        if (retainLoaders) {
            return mAllLoaderManagers;
        }
        return null;
    }

    void restoreLoaderNonConfig(ArrayMap<String, LoaderManager> loaderManagers) {
        mAllLoaderManagers = loaderManagers;
    }

    void dumpLoaders(String prefix, FileDescriptor fd, PrintWriter writer, String[] args) {
        writer.print(prefix); writer.print("mLoadersStarted=");
        writer.println(mLoadersStarted);
        if (mLoaderManager != null) {
            writer.print(prefix); writer.print("Loader Manager ");
            writer.print(Integer.toHexString(System.identityHashCode(mLoaderManager)));
            writer.println(":");
            mLoaderManager.dump(prefix + "  ", fd, writer, args);
        }
    }
}
