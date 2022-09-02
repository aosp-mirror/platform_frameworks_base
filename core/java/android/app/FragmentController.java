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

import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.ArrayMap;
import android.util.AttributeSet;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;

/**
 * Provides integration points with a {@link FragmentManager} for a fragment host.
 * <p>
 * It is the responsibility of the host to take care of the Fragment's lifecycle.
 * The methods provided by {@link FragmentController} are for that purpose.
 *
 * @deprecated Use the <a href="{@docRoot}tools/extras/support-library.html">Support Library</a>
 *      {@link androidx.fragment.app.FragmentController}
 */
@Deprecated
public class FragmentController {
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private final FragmentHostCallback<?> mHost;

    /**
     * Returns a {@link FragmentController}.
     */
    public static final FragmentController createController(FragmentHostCallback<?> callbacks) {
        return new FragmentController(callbacks);
    }

    private FragmentController(FragmentHostCallback<?> callbacks) {
        mHost = callbacks;
    }

    /**
     * Returns a {@link FragmentManager} for this controller.
     */
    public FragmentManager getFragmentManager() {
        return mHost.getFragmentManagerImpl();
    }

    /**
     * Returns a {@link LoaderManager}.
     */
    public LoaderManager getLoaderManager() {
        return mHost.getLoaderManagerImpl();
    }

    /**
     * Returns a fragment with the given identifier.
     */
    @Nullable
    public Fragment findFragmentByWho(String who) {
        return mHost.mFragmentManager.findFragmentByWho(who);
    }

    /**
     * Attaches the host to the FragmentManager for this controller. The host must be
     * attached before the FragmentManager can be used to manage Fragments.
     * */
    public void attachHost(Fragment parent) {
        mHost.mFragmentManager.attachController(
                mHost, mHost /*container*/, parent);
    }

    /**
     * Instantiates a Fragment's view.
     *
     * @param parent The parent that the created view will be placed
     * in; <em>note that this may be null</em>.
     * @param name Tag name to be inflated.
     * @param context The context the view is being created in.
     * @param attrs Inflation attributes as specified in XML file.
     *
     * @return view the newly created view
     */
    public View onCreateView(View parent, String name, Context context, AttributeSet attrs) {
        return mHost.mFragmentManager.onCreateView(parent, name, context, attrs);
    }

    /**
     * Marks the fragment state as unsaved. This allows for "state loss" detection.
     */
    public void noteStateNotSaved() {
        mHost.mFragmentManager.noteStateNotSaved();
    }

    /**
     * Saves the state for all Fragments.
     */
    public Parcelable saveAllState() {
        return mHost.mFragmentManager.saveAllState();
    }

    /**
     * Restores the saved state for all Fragments. The given Fragment list are Fragment
     * instances retained across configuration changes.
     *
     * @see #retainNonConfig()
     *
     * @deprecated use {@link #restoreAllState(Parcelable, FragmentManagerNonConfig)}
     */
    @Deprecated
    public void restoreAllState(Parcelable state, List<Fragment> nonConfigList) {
        mHost.mFragmentManager.restoreAllState(state,
                new FragmentManagerNonConfig(nonConfigList, null));
    }

    /**
     * Restores the saved state for all Fragments. The given FragmentManagerNonConfig are Fragment
     * instances retained across configuration changes, including nested fragments
     *
     * @see #retainNestedNonConfig()
     */
    public void restoreAllState(Parcelable state, FragmentManagerNonConfig nonConfig) {
        mHost.mFragmentManager.restoreAllState(state, nonConfig);
    }

    /**
     * Returns a list of Fragments that have opted to retain their instance across
     * configuration changes.
     *
     * @deprecated use {@link #retainNestedNonConfig()} to also track retained
     *             nested child fragments
     */
    @Deprecated
    public List<Fragment> retainNonConfig() {
        return mHost.mFragmentManager.retainNonConfig().getFragments();
    }

    /**
     * Returns a nested tree of Fragments that have opted to retain their instance across
     * configuration changes.
     */
    public FragmentManagerNonConfig retainNestedNonConfig() {
        return mHost.mFragmentManager.retainNonConfig();
    }

    /**
     * Moves all Fragments managed by the controller's FragmentManager
     * into the create state.
     * <p>Call when Fragments should be created.
     *
     * @see Fragment#onCreate(Bundle)
     */
    public void dispatchCreate() {
        mHost.mFragmentManager.dispatchCreate();
    }

    /**
     * Moves all Fragments managed by the controller's FragmentManager
     * into the activity created state.
     * <p>Call when Fragments should be informed their host has been created.
     *
     * @see Fragment#onActivityCreated(Bundle)
     */
    public void dispatchActivityCreated() {
        mHost.mFragmentManager.dispatchActivityCreated();
    }

    /**
     * Moves all Fragments managed by the controller's FragmentManager
     * into the start state.
     * <p>Call when Fragments should be started.
     *
     * @see Fragment#onStart()
     */
    public void dispatchStart() {
        mHost.mFragmentManager.dispatchStart();
    }

    /**
     * Moves all Fragments managed by the controller's FragmentManager
     * into the resume state.
     * <p>Call when Fragments should be resumed.
     *
     * @see Fragment#onResume()
     */
    public void dispatchResume() {
        mHost.mFragmentManager.dispatchResume();
    }

    /**
     * Moves all Fragments managed by the controller's FragmentManager
     * into the pause state.
     * <p>Call when Fragments should be paused.
     *
     * @see Fragment#onPause()
     */
    public void dispatchPause() {
        mHost.mFragmentManager.dispatchPause();
    }

    /**
     * Moves all Fragments managed by the controller's FragmentManager
     * into the stop state.
     * <p>Call when Fragments should be stopped.
     *
     * @see Fragment#onStop()
     */
    public void dispatchStop() {
        mHost.mFragmentManager.dispatchStop();
    }

    /**
     * Moves all Fragments managed by the controller's FragmentManager
     * into the destroy view state.
     * <p>Call when the Fragment's views should be destroyed.
     *
     * @see Fragment#onDestroyView()
     */
    public void dispatchDestroyView() {
        mHost.mFragmentManager.dispatchDestroyView();
    }

    /**
     * Moves all Fragments managed by the controller's FragmentManager
     * into the destroy state.
     * <p>Call when Fragments should be destroyed.
     *
     * @see Fragment#onDestroy()
     */
    public void dispatchDestroy() {
        mHost.mFragmentManager.dispatchDestroy();
    }

    /**
     * Lets all Fragments managed by the controller's FragmentManager know the multi-window mode of
     * the activity changed.
     * <p>Call when the multi-window mode of the activity changed.
     *
     * @see Fragment#onMultiWindowModeChanged
     * @deprecated use {@link #dispatchMultiWindowModeChanged(boolean, Configuration)}
     */
    @Deprecated
    public void dispatchMultiWindowModeChanged(boolean isInMultiWindowMode) {
        mHost.mFragmentManager.dispatchMultiWindowModeChanged(isInMultiWindowMode);
    }

    /**
     * Lets all Fragments managed by the controller's FragmentManager know the multi-window mode of
     * the activity changed.
     * <p>Call when the multi-window mode of the activity changed.
     *
     * @see Fragment#onMultiWindowModeChanged
     */
    public void dispatchMultiWindowModeChanged(boolean isInMultiWindowMode,
            Configuration newConfig) {
        mHost.mFragmentManager.dispatchMultiWindowModeChanged(isInMultiWindowMode, newConfig);
    }

    /**
     * Lets all Fragments managed by the controller's FragmentManager know the picture-in-picture
     * mode of the activity changed.
     * <p>Call when the picture-in-picture mode of the activity changed.
     *
     * @see Fragment#onPictureInPictureModeChanged
     * @deprecated use {@link #dispatchPictureInPictureModeChanged(boolean, Configuration)}
     */
    @Deprecated
    public void dispatchPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        mHost.mFragmentManager.dispatchPictureInPictureModeChanged(isInPictureInPictureMode);
    }

    /**
     * Lets all Fragments managed by the controller's FragmentManager know the picture-in-picture
     * mode of the activity changed.
     * <p>Call when the picture-in-picture mode of the activity changed.
     *
     * @see Fragment#onPictureInPictureModeChanged
     */
    public void dispatchPictureInPictureModeChanged(boolean isInPictureInPictureMode,
            Configuration newConfig) {
        mHost.mFragmentManager.dispatchPictureInPictureModeChanged(isInPictureInPictureMode,
                newConfig);
    }

    /**
     * Lets all Fragments managed by the controller's FragmentManager
     * know a configuration change occurred.
     * <p>Call when there is a configuration change.
     *
     * @see Fragment#onConfigurationChanged(Configuration)
     */
    public void dispatchConfigurationChanged(Configuration newConfig) {
        mHost.mFragmentManager.dispatchConfigurationChanged(newConfig);
    }

    /**
     * Lets all Fragments managed by the controller's FragmentManager
     * know the device is in a low memory condition.
     * <p>Call when the device is low on memory and Fragment's should trim
     * their memory usage.
     *
     * @see Fragment#onLowMemory()
     */
    public void dispatchLowMemory() {
        mHost.mFragmentManager.dispatchLowMemory();
    }

    /**
     * Lets all Fragments managed by the controller's FragmentManager
     * know they should trim their memory usage.
     * <p>Call when the Fragment can release allocated memory [such as if
     * the Fragment is in the background].
     *
     * @see Fragment#onTrimMemory(int)
     */
    public void dispatchTrimMemory(int level) {
        mHost.mFragmentManager.dispatchTrimMemory(level);
    }

    /**
     * Lets all Fragments managed by the controller's FragmentManager
     * know they should create an options menu.
     * <p>Call when the Fragment should create an options menu.
     *
     * @return {@code true} if the options menu contains items to display
     * @see Fragment#onCreateOptionsMenu(Menu, MenuInflater)
     */
    public boolean dispatchCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        return mHost.mFragmentManager.dispatchCreateOptionsMenu(menu, inflater);
    }

    /**
     * Lets all Fragments managed by the controller's FragmentManager
     * know they should prepare their options menu for display.
     * <p>Call immediately before displaying the Fragment's options menu.
     *
     * @return {@code true} if the options menu contains items to display
     * @see Fragment#onPrepareOptionsMenu(Menu)
     */
    public boolean dispatchPrepareOptionsMenu(Menu menu) {
        return mHost.mFragmentManager.dispatchPrepareOptionsMenu(menu);
    }

    /**
     * Sends an option item selection event to the Fragments managed by the
     * controller's FragmentManager. Once the event has been consumed,
     * no additional handling will be performed.
     * <p>Call immediately after an options menu item has been selected
     *
     * @return {@code true} if the options menu selection event was consumed
     * @see Fragment#onOptionsItemSelected(MenuItem)
     */
    public boolean dispatchOptionsItemSelected(MenuItem item) {
        return mHost.mFragmentManager.dispatchOptionsItemSelected(item);
    }

    /**
     * Sends a context item selection event to the Fragments managed by the
     * controller's FragmentManager. Once the event has been consumed,
     * no additional handling will be performed.
     * <p>Call immediately after an options menu item has been selected
     *
     * @return {@code true} if the context menu selection event was consumed
     * @see Fragment#onContextItemSelected(MenuItem)
     */
    public boolean dispatchContextItemSelected(MenuItem item) {
        return mHost.mFragmentManager.dispatchContextItemSelected(item);
    }

    /**
     * Lets all Fragments managed by the controller's FragmentManager
     * know their options menu has closed.
     * <p>Call immediately after closing the Fragment's options menu.
     *
     * @see Fragment#onOptionsMenuClosed(Menu)
     */
    public void dispatchOptionsMenuClosed(Menu menu) {
        mHost.mFragmentManager.dispatchOptionsMenuClosed(menu);
    }

    /**
     * Execute any pending actions for the Fragments managed by the
     * controller's FragmentManager.
     * <p>Call when queued actions can be performed [eg when the
     * Fragment moves into a start or resume state].
     * @return {@code true} if queued actions were performed
     */
    public boolean execPendingActions() {
        return mHost.mFragmentManager.execPendingActions();
    }

    /**
     * Starts the loaders.
     */
    public void doLoaderStart() {
        mHost.doLoaderStart();
    }

    /**
     * Stops the loaders, optionally retaining their state. This is useful for keeping the
     * loader state across configuration changes.
     *
     * @param retain When {@code true}, the loaders aren't stopped, but, their instances
     * are retained in a started state
     */
    public void doLoaderStop(boolean retain) {
        mHost.doLoaderStop(retain);
    }

    /**
     * Destroys the loaders and, if their state is not being retained, removes them.
     */
    public void doLoaderDestroy() {
        mHost.doLoaderDestroy();
    }

    /**
     * Lets the loaders know the host is ready to receive notifications.
     */
    public void reportLoaderStart() {
        mHost.reportLoaderStart();
    }

    /**
     * Returns a list of LoaderManagers that have opted to retain their instance across
     * configuration changes.
     */
    public ArrayMap<String, LoaderManager> retainLoaderNonConfig() {
        return mHost.retainLoaderNonConfig();
    }

    /**
     * Restores the saved state for all LoaderManagers. The given LoaderManager list are
     * LoaderManager instances retained across configuration changes.
     *
     * @see #retainLoaderNonConfig()
     */
    public void restoreLoaderNonConfig(ArrayMap<String, LoaderManager> loaderManagers) {
        mHost.restoreLoaderNonConfig(loaderManagers);
    }

    /**
     * Dumps the current state of the loaders.
     */
    public void dumpLoaders(String prefix, FileDescriptor fd, PrintWriter writer, String[] args) {
        mHost.dumpLoaders(prefix, fd, writer, args);
    }
}
