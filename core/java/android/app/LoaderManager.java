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

import android.content.Loader;
import android.os.Bundle;
import android.util.DebugUtils;
import android.util.Log;
import android.util.SparseArray;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.reflect.Modifier;

/**
 * Interface associated with an {@link Activity} or {@link Fragment} for managing
 * one or more {@link android.content.Loader} instances associated with it.  This
 * helps an application manage longer-running operations in conjunction with the
 * Activity or Fragment lifecycle; the most common use of this is with a
 * {@link android.content.CursorLoader}, however applications are free to write
 * their own loaders for loading other types of data.
 *
 * While the LoaderManager API was introduced in
 * {@link android.os.Build.VERSION_CODES#HONEYCOMB}, a version of the API
 * at is also available for use on older platforms through
 * {@link android.support.v4.app.FragmentActivity}.  See the blog post
 * <a href="http://android-developers.blogspot.com/2011/03/fragments-for-all.html">
 * Fragments For All</a> for more details.
 *
 * <p>As an example, here is the full implementation of a {@link Fragment}
 * that displays a {@link android.widget.ListView} containing the results of
 * a query against the contacts content provider.  It uses a
 * {@link android.content.CursorLoader} to manage the query on the provider.
 *
 * {@sample development/samples/ApiDemos/src/com/example/android/apis/app/LoaderCursor.java
 *      fragment_cursor}
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about using loaders, read the
 * <a href="{@docRoot}guide/topics/fundamentals/loaders.html">Loaders</a> developer guide.</p>
 * </div>
 */
public abstract class LoaderManager {
    /**
     * Callback interface for a client to interact with the manager.
     */
    public interface LoaderCallbacks<D> {
        /**
         * Instantiate and return a new Loader for the given ID.
         *
         * @param id The ID whose loader is to be created.
         * @param args Any arguments supplied by the caller.
         * @return Return a new Loader instance that is ready to start loading.
         */
        public Loader<D> onCreateLoader(int id, Bundle args);

        /**
         * Called when a previously created loader has finished its load.  Note
         * that normally an application is <em>not</em> allowed to commit fragment
         * transactions while in this call, since it can happen after an
         * activity's state is saved.  See {@link FragmentManager#beginTransaction()
         * FragmentManager.openTransaction()} for further discussion on this.
         * 
         * <p>This function is guaranteed to be called prior to the release of
         * the last data that was supplied for this Loader.  At this point
         * you should remove all use of the old data (since it will be released
         * soon), but should not do your own release of the data since its Loader
         * owns it and will take care of that.  The Loader will take care of
         * management of its data so you don't have to.  In particular:
         *
         * <ul>
         * <li> <p>The Loader will monitor for changes to the data, and report
         * them to you through new calls here.  You should not monitor the
         * data yourself.  For example, if the data is a {@link android.database.Cursor}
         * and you place it in a {@link android.widget.CursorAdapter}, use
         * the {@link android.widget.CursorAdapter#CursorAdapter(android.content.Context,
         * android.database.Cursor, int)} constructor <em>without</em> passing
         * in either {@link android.widget.CursorAdapter#FLAG_AUTO_REQUERY}
         * or {@link android.widget.CursorAdapter#FLAG_REGISTER_CONTENT_OBSERVER}
         * (that is, use 0 for the flags argument).  This prevents the CursorAdapter
         * from doing its own observing of the Cursor, which is not needed since
         * when a change happens you will get a new Cursor throw another call
         * here.
         * <li> The Loader will release the data once it knows the application
         * is no longer using it.  For example, if the data is
         * a {@link android.database.Cursor} from a {@link android.content.CursorLoader},
         * you should not call close() on it yourself.  If the Cursor is being placed in a
         * {@link android.widget.CursorAdapter}, you should use the
         * {@link android.widget.CursorAdapter#swapCursor(android.database.Cursor)}
         * method so that the old Cursor is not closed.
         * </ul>
         *
         * @param loader The Loader that has finished.
         * @param data The data generated by the Loader.
         */
        public void onLoadFinished(Loader<D> loader, D data);

        /**
         * Called when a previously created loader is being reset, and thus
         * making its data unavailable.  The application should at this point
         * remove any references it has to the Loader's data.
         *
         * @param loader The Loader that is being reset.
         */
        public void onLoaderReset(Loader<D> loader);
    }
    
    /**
     * Ensures a loader is initialized and active.  If the loader doesn't
     * already exist, one is created and (if the activity/fragment is currently
     * started) starts the loader.  Otherwise the last created
     * loader is re-used.
     *
     * <p>In either case, the given callback is associated with the loader, and
     * will be called as the loader state changes.  If at the point of call
     * the caller is in its started state, and the requested loader
     * already exists and has generated its data, then
     * callback {@link LoaderCallbacks#onLoadFinished} will
     * be called immediately (inside of this function), so you must be prepared
     * for this to happen.
     *
     * @param id A unique identifier for this loader.  Can be whatever you want.
     * Identifiers are scoped to a particular LoaderManager instance.
     * @param args Optional arguments to supply to the loader at construction.
     * If a loader already exists (a new one does not need to be created), this
     * parameter will be ignored and the last arguments continue to be used.
     * @param callback Interface the LoaderManager will call to report about
     * changes in the state of the loader.  Required.
     */
    public abstract <D> Loader<D> initLoader(int id, Bundle args,
            LoaderManager.LoaderCallbacks<D> callback);

    /**
     * Starts a new or restarts an existing {@link android.content.Loader} in
     * this manager, registers the callbacks to it,
     * and (if the activity/fragment is currently started) starts loading it.
     * If a loader with the same id has previously been
     * started it will automatically be destroyed when the new loader completes
     * its work. The callback will be delivered before the old loader
     * is destroyed.
     *
     * @param id A unique identifier for this loader.  Can be whatever you want.
     * Identifiers are scoped to a particular LoaderManager instance.
     * @param args Optional arguments to supply to the loader at construction.
     * @param callback Interface the LoaderManager will call to report about
     * changes in the state of the loader.  Required.
     */
    public abstract <D> Loader<D> restartLoader(int id, Bundle args,
            LoaderManager.LoaderCallbacks<D> callback);

    /**
     * Stops and removes the loader with the given ID.  If this loader
     * had previously reported data to the client through
     * {@link LoaderCallbacks#onLoadFinished(Loader, Object)}, a call
     * will be made to {@link LoaderCallbacks#onLoaderReset(Loader)}.
     */
    public abstract void destroyLoader(int id);

    /**
     * Return the Loader with the given id or null if no matching Loader
     * is found.
     */
    public abstract <D> Loader<D> getLoader(int id);

    /**
     * Print the LoaderManager's state into the given stream.
     *
     * @param prefix Text to print at the front of each line.
     * @param fd The raw file descriptor that the dump is being sent to.
     * @param writer A PrintWriter to which the dump is to be set.
     * @param args Additional arguments to the dump request.
     */
    public abstract void dump(String prefix, FileDescriptor fd, PrintWriter writer, String[] args);

    /**
     * Control whether the framework's internal loader manager debugging
     * logs are turned on.  If enabled, you will see output in logcat as
     * the framework performs loader operations.
     */
    public static void enableDebugLogging(boolean enabled) {
        LoaderManagerImpl.DEBUG = enabled;
    }
}

class LoaderManagerImpl extends LoaderManager {
    static final String TAG = "LoaderManager";
    static boolean DEBUG = false;

    // These are the currently active loaders.  A loader is here
    // from the time its load is started until it has been explicitly
    // stopped or restarted by the application.
    final SparseArray<LoaderInfo> mLoaders = new SparseArray<LoaderInfo>(0);

    // These are previously run loaders.  This list is maintained internally
    // to avoid destroying a loader while an application is still using it.
    // It allows an application to restart a loader, but continue using its
    // previously run loader until the new loader's data is available.
    final SparseArray<LoaderInfo> mInactiveLoaders = new SparseArray<LoaderInfo>(0);

    final String mWho;

    boolean mStarted;
    boolean mRetaining;
    boolean mRetainingStarted;
    
    boolean mCreatingLoader;
    private FragmentHostCallback mHost;

    final class LoaderInfo implements Loader.OnLoadCompleteListener<Object>,
            Loader.OnLoadCanceledListener<Object> {
        final int mId;
        final Bundle mArgs;
        LoaderManager.LoaderCallbacks<Object> mCallbacks;
        Loader<Object> mLoader;
        boolean mHaveData;
        boolean mDeliveredData;
        Object mData;
        boolean mStarted;
        boolean mRetaining;
        boolean mRetainingStarted;
        boolean mReportNextStart;
        boolean mDestroyed;
        boolean mListenerRegistered;

        LoaderInfo mPendingLoader;
        
        public LoaderInfo(int id, Bundle args, LoaderManager.LoaderCallbacks<Object> callbacks) {
            mId = id;
            mArgs = args;
            mCallbacks = callbacks;
        }
        
        void start() {
            if (mRetaining && mRetainingStarted) {
                // Our owner is started, but we were being retained from a
                // previous instance in the started state...  so there is really
                // nothing to do here, since the loaders are still started.
                mStarted = true;
                return;
            }

            if (mStarted) {
                // If loader already started, don't restart.
                return;
            }

            mStarted = true;
            
            if (DEBUG) Log.v(TAG, "  Starting: " + this);
            if (mLoader == null && mCallbacks != null) {
               mLoader = mCallbacks.onCreateLoader(mId, mArgs);
            }
            if (mLoader != null) {
                if (mLoader.getClass().isMemberClass()
                        && !Modifier.isStatic(mLoader.getClass().getModifiers())) {
                    throw new IllegalArgumentException(
                            "Object returned from onCreateLoader must not be a non-static inner member class: "
                            + mLoader);
                }
                if (!mListenerRegistered) {
                    mLoader.registerListener(mId, this);
                    mLoader.registerOnLoadCanceledListener(this);
                    mListenerRegistered = true;
                }
                mLoader.startLoading();
            }
        }
        
        void retain() {
            if (DEBUG) Log.v(TAG, "  Retaining: " + this);
            mRetaining = true;
            mRetainingStarted = mStarted;
            mStarted = false;
            mCallbacks = null;
        }
        
        void finishRetain() {
            if (mRetaining) {
                if (DEBUG) Log.v(TAG, "  Finished Retaining: " + this);
                mRetaining = false;
                if (mStarted != mRetainingStarted) {
                    if (!mStarted) {
                        // This loader was retained in a started state, but
                        // at the end of retaining everything our owner is
                        // no longer started...  so make it stop.
                        stop();
                    }
                }
            }

            if (mStarted && mHaveData && !mReportNextStart) {
                // This loader has retained its data, either completely across
                // a configuration change or just whatever the last data set
                // was after being restarted from a stop, and now at the point of
                // finishing the retain we find we remain started, have
                // our data, and the owner has a new callback...  so
                // let's deliver the data now.
                callOnLoadFinished(mLoader, mData);
            }
        }
        
        void reportStart() {
            if (mStarted) {
                if (mReportNextStart) {
                    mReportNextStart = false;
                    if (mHaveData && !mRetaining) {
                        callOnLoadFinished(mLoader, mData);
                    }
                }
            }
        }

        void stop() {
            if (DEBUG) Log.v(TAG, "  Stopping: " + this);
            mStarted = false;
            if (!mRetaining) {
                if (mLoader != null && mListenerRegistered) {
                    // Let the loader know we're done with it
                    mListenerRegistered = false;
                    mLoader.unregisterListener(this);
                    mLoader.unregisterOnLoadCanceledListener(this);
                    mLoader.stopLoading();
                }
            }
        }

        boolean cancel() {
            if (DEBUG) Log.v(TAG, "  Canceling: " + this);
            if (mStarted && mLoader != null && mListenerRegistered) {
                final boolean cancelLoadResult = mLoader.cancelLoad();
                if (!cancelLoadResult) {
                    onLoadCanceled(mLoader);
                }
                return cancelLoadResult;
            }
            return false;
        }

        void destroy() {
            if (DEBUG) Log.v(TAG, "  Destroying: " + this);
            mDestroyed = true;
            boolean needReset = mDeliveredData;
            mDeliveredData = false;
            if (mCallbacks != null && mLoader != null && mHaveData && needReset) {
                if (DEBUG) Log.v(TAG, "  Reseting: " + this);
                String lastBecause = null;
                if (mHost != null) {
                    lastBecause = mHost.mFragmentManager.mNoTransactionsBecause;
                    mHost.mFragmentManager.mNoTransactionsBecause = "onLoaderReset";
                }
                try {
                    mCallbacks.onLoaderReset(mLoader);
                } finally {
                    if (mHost != null) {
                        mHost.mFragmentManager.mNoTransactionsBecause = lastBecause;
                    }
                }
            }
            mCallbacks = null;
            mData = null;
            mHaveData = false;
            if (mLoader != null) {
                if (mListenerRegistered) {
                    mListenerRegistered = false;
                    mLoader.unregisterListener(this);
                    mLoader.unregisterOnLoadCanceledListener(this);
                }
                mLoader.reset();
            }
            if (mPendingLoader != null) {
                mPendingLoader.destroy();
            }
        }

        @Override
        public void onLoadCanceled(Loader<Object> loader) {
            if (DEBUG) Log.v(TAG, "onLoadCanceled: " + this);

            if (mDestroyed) {
                if (DEBUG) Log.v(TAG, "  Ignoring load canceled -- destroyed");
                return;
            }

            if (mLoaders.get(mId) != this) {
                // This cancellation message is not coming from the current active loader.
                // We don't care about it.
                if (DEBUG) Log.v(TAG, "  Ignoring load canceled -- not active");
                return;
            }

            LoaderInfo pending = mPendingLoader;
            if (pending != null) {
                // There is a new request pending and we were just
                // waiting for the old one to cancel or complete before starting
                // it.  So now it is time, switch over to the new loader.
                if (DEBUG) Log.v(TAG, "  Switching to pending loader: " + pending);
                mPendingLoader = null;
                mLoaders.put(mId, null);
                destroy();
                installLoader(pending);
            }
        }

        @Override
        public void onLoadComplete(Loader<Object> loader, Object data) {
            if (DEBUG) Log.v(TAG, "onLoadComplete: " + this);
            
            if (mDestroyed) {
                if (DEBUG) Log.v(TAG, "  Ignoring load complete -- destroyed");
                return;
            }

            if (mLoaders.get(mId) != this) {
                // This data is not coming from the current active loader.
                // We don't care about it.
                if (DEBUG) Log.v(TAG, "  Ignoring load complete -- not active");
                return;
            }
            
            LoaderInfo pending = mPendingLoader;
            if (pending != null) {
                // There is a new request pending and we were just
                // waiting for the old one to complete before starting
                // it.  So now it is time, switch over to the new loader.
                if (DEBUG) Log.v(TAG, "  Switching to pending loader: " + pending);
                mPendingLoader = null;
                mLoaders.put(mId, null);
                destroy();
                installLoader(pending);
                return;
            }
            
            // Notify of the new data so the app can switch out the old data before
            // we try to destroy it.
            if (mData != data || !mHaveData) {
                mData = data;
                mHaveData = true;
                if (mStarted) {
                    callOnLoadFinished(loader, data);
                }
            }

            //if (DEBUG) Log.v(TAG, "  onLoadFinished returned: " + this);

            // We have now given the application the new loader with its
            // loaded data, so it should have stopped using the previous
            // loader.  If there is a previous loader on the inactive list,
            // clean it up.
            LoaderInfo info = mInactiveLoaders.get(mId);
            if (info != null && info != this) {
                info.mDeliveredData = false;
                info.destroy();
                mInactiveLoaders.remove(mId);
            }

            if (mHost != null && !hasRunningLoaders()) {
                mHost.mFragmentManager.startPendingDeferredFragments();
            }
        }

        void callOnLoadFinished(Loader<Object> loader, Object data) {
            if (mCallbacks != null) {
                String lastBecause = null;
                if (mHost != null) {
                    lastBecause = mHost.mFragmentManager.mNoTransactionsBecause;
                    mHost.mFragmentManager.mNoTransactionsBecause = "onLoadFinished";
                }
                try {
                    if (DEBUG) Log.v(TAG, "  onLoadFinished in " + loader + ": "
                            + loader.dataToString(data));
                    mCallbacks.onLoadFinished(loader, data);
                } finally {
                    if (mHost != null) {
                        mHost.mFragmentManager.mNoTransactionsBecause = lastBecause;
                    }
                }
                mDeliveredData = true;
            }
        }
        
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(64);
            sb.append("LoaderInfo{");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(" #");
            sb.append(mId);
            sb.append(" : ");
            DebugUtils.buildShortClassTag(mLoader, sb);
            sb.append("}}");
            return sb.toString();
        }

        public void dump(String prefix, FileDescriptor fd, PrintWriter writer, String[] args) {
            writer.print(prefix); writer.print("mId="); writer.print(mId);
                    writer.print(" mArgs="); writer.println(mArgs);
            writer.print(prefix); writer.print("mCallbacks="); writer.println(mCallbacks);
            writer.print(prefix); writer.print("mLoader="); writer.println(mLoader);
            if (mLoader != null) {
                mLoader.dump(prefix + "  ", fd, writer, args);
            }
            if (mHaveData || mDeliveredData) {
                writer.print(prefix); writer.print("mHaveData="); writer.print(mHaveData);
                        writer.print("  mDeliveredData="); writer.println(mDeliveredData);
                writer.print(prefix); writer.print("mData="); writer.println(mData);
            }
            writer.print(prefix); writer.print("mStarted="); writer.print(mStarted);
                    writer.print(" mReportNextStart="); writer.print(mReportNextStart);
                    writer.print(" mDestroyed="); writer.println(mDestroyed);
            writer.print(prefix); writer.print("mRetaining="); writer.print(mRetaining);
                    writer.print(" mRetainingStarted="); writer.print(mRetainingStarted);
                    writer.print(" mListenerRegistered="); writer.println(mListenerRegistered);
            if (mPendingLoader != null) {
                writer.print(prefix); writer.println("Pending Loader ");
                        writer.print(mPendingLoader); writer.println(":");
                mPendingLoader.dump(prefix + "  ", fd, writer, args);
            }
        }
    }
    
    LoaderManagerImpl(String who, FragmentHostCallback host, boolean started) {
        mWho = who;
        mHost = host;
        mStarted = started;
    }
    
    void updateHostController(FragmentHostCallback host) {
        mHost = host;
    }
    
    private LoaderInfo createLoader(int id, Bundle args,
            LoaderManager.LoaderCallbacks<Object> callback) {
        LoaderInfo info = new LoaderInfo(id, args,  (LoaderManager.LoaderCallbacks<Object>)callback);
        Loader<Object> loader = callback.onCreateLoader(id, args);
        info.mLoader = (Loader<Object>)loader;
        return info;
    }
    
    private LoaderInfo createAndInstallLoader(int id, Bundle args,
            LoaderManager.LoaderCallbacks<Object> callback) {
        try {
            mCreatingLoader = true;
            LoaderInfo info = createLoader(id, args, callback);
            installLoader(info);
            return info;
        } finally {
            mCreatingLoader = false;
        }
    }
    
    void installLoader(LoaderInfo info) {
        mLoaders.put(info.mId, info);
        if (mStarted) {
            // The activity will start all existing loaders in it's onStart(),
            // so only start them here if we're past that point of the activitiy's
            // life cycle
            info.start();
        }
    }
    
    /**
     * Call to initialize a particular ID with a Loader.  If this ID already
     * has a Loader associated with it, it is left unchanged and any previous
     * callbacks replaced with the newly provided ones.  If there is not currently
     * a Loader for the ID, a new one is created and started.
     * 
     * <p>This function should generally be used when a component is initializing,
     * to ensure that a Loader it relies on is created.  This allows it to re-use
     * an existing Loader's data if there already is one, so that for example
     * when an {@link Activity} is re-created after a configuration change it
     * does not need to re-create its loaders.
     * 
     * <p>Note that in the case where an existing Loader is re-used, the
     * <var>args</var> given here <em>will be ignored</em> because you will
     * continue using the previous Loader.
     * 
     * @param id A unique (to this LoaderManager instance) identifier under
     * which to manage the new Loader.
     * @param args Optional arguments that will be propagated to
     * {@link LoaderCallbacks#onCreateLoader(int, Bundle) LoaderCallbacks.onCreateLoader()}.
     * @param callback Interface implementing management of this Loader.  Required.
     * Its onCreateLoader() method will be called while inside of the function to
     * instantiate the Loader object.
     */
    @SuppressWarnings("unchecked")
    public <D> Loader<D> initLoader(int id, Bundle args, LoaderManager.LoaderCallbacks<D> callback) {
        if (mCreatingLoader) {
            throw new IllegalStateException("Called while creating a loader");
        }
        
        LoaderInfo info = mLoaders.get(id);
        
        if (DEBUG) Log.v(TAG, "initLoader in " + this + ": args=" + args);

        if (info == null) {
            // Loader doesn't already exist; create.
            info = createAndInstallLoader(id, args,  (LoaderManager.LoaderCallbacks<Object>)callback);
            if (DEBUG) Log.v(TAG, "  Created new loader " + info);
        } else {
            if (DEBUG) Log.v(TAG, "  Re-using existing loader " + info);
            info.mCallbacks = (LoaderManager.LoaderCallbacks<Object>)callback;
        }
        
        if (info.mHaveData && mStarted) {
            // If the loader has already generated its data, report it now.
            info.callOnLoadFinished(info.mLoader, info.mData);
        }
        
        return (Loader<D>)info.mLoader;
    }
    
    /**
     * Call to re-create the Loader associated with a particular ID.  If there
     * is currently a Loader associated with this ID, it will be
     * canceled/stopped/destroyed as appropriate.  A new Loader with the given
     * arguments will be created and its data delivered to you once available.
     * 
     * <p>This function does some throttling of Loaders.  If too many Loaders
     * have been created for the given ID but not yet generated their data,
     * new calls to this function will create and return a new Loader but not
     * actually start it until some previous loaders have completed.
     * 
     * <p>After calling this function, any previous Loaders associated with
     * this ID will be considered invalid, and you will receive no further
     * data updates from them.
     * 
     * @param id A unique (to this LoaderManager instance) identifier under
     * which to manage the new Loader.
     * @param args Optional arguments that will be propagated to
     * {@link LoaderCallbacks#onCreateLoader(int, Bundle) LoaderCallbacks.onCreateLoader()}.
     * @param callback Interface implementing management of this Loader.  Required.
     * Its onCreateLoader() method will be called while inside of the function to
     * instantiate the Loader object.
     */
    @SuppressWarnings("unchecked")
    public <D> Loader<D> restartLoader(int id, Bundle args, LoaderManager.LoaderCallbacks<D> callback) {
        if (mCreatingLoader) {
            throw new IllegalStateException("Called while creating a loader");
        }
        
        LoaderInfo info = mLoaders.get(id);
        if (DEBUG) Log.v(TAG, "restartLoader in " + this + ": args=" + args);
        if (info != null) {
            LoaderInfo inactive = mInactiveLoaders.get(id);
            if (inactive != null) {
                if (info.mHaveData) {
                    // This loader now has data...  we are probably being
                    // called from within onLoadComplete, where we haven't
                    // yet destroyed the last inactive loader.  So just do
                    // that now.
                    if (DEBUG) Log.v(TAG, "  Removing last inactive loader: " + info);
                    inactive.mDeliveredData = false;
                    inactive.destroy();
                    info.mLoader.abandon();
                    mInactiveLoaders.put(id, info);
                } else {
                    // We already have an inactive loader for this ID that we are
                    // waiting for! Try to cancel; if this returns true then the task is still
                    // running and we have more work to do.
                    if (!info.cancel()) {
                        // The current Loader has not been started or was successfully canceled,
                        // we thus have no reason to keep it around. Remove it and a new
                        // LoaderInfo will be created below.
                        if (DEBUG) Log.v(TAG, "  Current loader is stopped; replacing");
                        mLoaders.put(id, null);
                        info.destroy();
                    } else {
                        // Now we have three active loaders... we'll queue
                        // up this request to be processed once one of the other loaders
                        // finishes.
                        if (DEBUG) Log.v(TAG,
                                "  Current loader is running; configuring pending loader");
                        if (info.mPendingLoader != null) {
                            if (DEBUG) Log.v(TAG, "  Removing pending loader: " + info.mPendingLoader);
                            info.mPendingLoader.destroy();
                            info.mPendingLoader = null;
                        }
                        if (DEBUG) Log.v(TAG, "  Enqueuing as new pending loader");
                        info.mPendingLoader = createLoader(id, args, 
                                (LoaderManager.LoaderCallbacks<Object>)callback);
                        return (Loader<D>)info.mPendingLoader.mLoader;
                    }
                }
            } else {
                // Keep track of the previous instance of this loader so we can destroy
                // it when the new one completes.
                if (DEBUG) Log.v(TAG, "  Making last loader inactive: " + info);
                info.mLoader.abandon();
                mInactiveLoaders.put(id, info);
            }
        }
        
        info = createAndInstallLoader(id, args,  (LoaderManager.LoaderCallbacks<Object>)callback);
        return (Loader<D>)info.mLoader;
    }
    
    /**
     * Rip down, tear apart, shred to pieces a current Loader ID.  After returning
     * from this function, any Loader objects associated with this ID are
     * destroyed.  Any data associated with them is destroyed.  You better not
     * be using it when you do this.
     * @param id Identifier of the Loader to be destroyed.
     */
    public void destroyLoader(int id) {
        if (mCreatingLoader) {
            throw new IllegalStateException("Called while creating a loader");
        }
        
        if (DEBUG) Log.v(TAG, "destroyLoader in " + this + " of " + id);
        int idx = mLoaders.indexOfKey(id);
        if (idx >= 0) {
            LoaderInfo info = mLoaders.valueAt(idx);
            mLoaders.removeAt(idx);
            info.destroy();
        }
        idx = mInactiveLoaders.indexOfKey(id);
        if (idx >= 0) {
            LoaderInfo info = mInactiveLoaders.valueAt(idx);
            mInactiveLoaders.removeAt(idx);
            info.destroy();
        }
        if (mHost != null && !hasRunningLoaders()) {
            mHost.mFragmentManager.startPendingDeferredFragments();
        }
    }

    /**
     * Return the most recent Loader object associated with the
     * given ID.
     */
    @SuppressWarnings("unchecked")
    public <D> Loader<D> getLoader(int id) {
        if (mCreatingLoader) {
            throw new IllegalStateException("Called while creating a loader");
        }
        
        LoaderInfo loaderInfo = mLoaders.get(id);
        if (loaderInfo != null) {
            if (loaderInfo.mPendingLoader != null) {
                return (Loader<D>)loaderInfo.mPendingLoader.mLoader;
            }
            return (Loader<D>)loaderInfo.mLoader;
        }
        return null;
    }
 
    void doStart() {
        if (DEBUG) Log.v(TAG, "Starting in " + this);
        if (mStarted) {
            RuntimeException e = new RuntimeException("here");
            e.fillInStackTrace();
            Log.w(TAG, "Called doStart when already started: " + this, e);
            return;
        }
        
        mStarted = true;

        // Call out to sub classes so they can start their loaders
        // Let the existing loaders know that we want to be notified when a load is complete
        for (int i = mLoaders.size()-1; i >= 0; i--) {
            mLoaders.valueAt(i).start();
        }
    }
    
    void doStop() {
        if (DEBUG) Log.v(TAG, "Stopping in " + this);
        if (!mStarted) {
            RuntimeException e = new RuntimeException("here");
            e.fillInStackTrace();
            Log.w(TAG, "Called doStop when not started: " + this, e);
            return;
        }

        for (int i = mLoaders.size()-1; i >= 0; i--) {
            mLoaders.valueAt(i).stop();
        }
        mStarted = false;
    }
    
    void doRetain() {
        if (DEBUG) Log.v(TAG, "Retaining in " + this);
        if (!mStarted) {
            RuntimeException e = new RuntimeException("here");
            e.fillInStackTrace();
            Log.w(TAG, "Called doRetain when not started: " + this, e);
            return;
        }

        mRetaining = true;
        mStarted = false;
        for (int i = mLoaders.size()-1; i >= 0; i--) {
            mLoaders.valueAt(i).retain();
        }
    }
    
    void finishRetain() {
        if (mRetaining) {
            if (DEBUG) Log.v(TAG, "Finished Retaining in " + this);

            mRetaining = false;
            for (int i = mLoaders.size()-1; i >= 0; i--) {
                mLoaders.valueAt(i).finishRetain();
            }
        }
    }
    
    void doReportNextStart() {
        for (int i = mLoaders.size()-1; i >= 0; i--) {
            mLoaders.valueAt(i).mReportNextStart = true;
        }
    }

    void doReportStart() {
        for (int i = mLoaders.size()-1; i >= 0; i--) {
            mLoaders.valueAt(i).reportStart();
        }
    }

    void doDestroy() {
        if (!mRetaining) {
            if (DEBUG) Log.v(TAG, "Destroying Active in " + this);
            for (int i = mLoaders.size()-1; i >= 0; i--) {
                mLoaders.valueAt(i).destroy();
            }
            mLoaders.clear();
        }
        
        if (DEBUG) Log.v(TAG, "Destroying Inactive in " + this);
        for (int i = mInactiveLoaders.size()-1; i >= 0; i--) {
            mInactiveLoaders.valueAt(i).destroy();
        }
        mInactiveLoaders.clear();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(128);
        sb.append("LoaderManager{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(" in ");
        DebugUtils.buildShortClassTag(mHost, sb);
        sb.append("}}");
        return sb.toString();
    }

    @Override
    public void dump(String prefix, FileDescriptor fd, PrintWriter writer, String[] args) {
        if (mLoaders.size() > 0) {
            writer.print(prefix); writer.println("Active Loaders:");
            String innerPrefix = prefix + "    ";
            for (int i=0; i < mLoaders.size(); i++) {
                LoaderInfo li = mLoaders.valueAt(i);
                writer.print(prefix); writer.print("  #"); writer.print(mLoaders.keyAt(i));
                        writer.print(": "); writer.println(li.toString());
                li.dump(innerPrefix, fd, writer, args);
            }
        }
        if (mInactiveLoaders.size() > 0) {
            writer.print(prefix); writer.println("Inactive Loaders:");
            String innerPrefix = prefix + "    ";
            for (int i=0; i < mInactiveLoaders.size(); i++) {
                LoaderInfo li = mInactiveLoaders.valueAt(i);
                writer.print(prefix); writer.print("  #"); writer.print(mInactiveLoaders.keyAt(i));
                        writer.print(": "); writer.println(li.toString());
                li.dump(innerPrefix, fd, writer, args);
            }
        }
    }

    public boolean hasRunningLoaders() {
        boolean loadersRunning = false;
        final int count = mLoaders.size();
        for (int i = 0; i < count; i++) {
            final LoaderInfo li = mLoaders.valueAt(i);
            loadersRunning |= li.mStarted && !li.mDeliveredData;
        }
        return loadersRunning;
    }
}
