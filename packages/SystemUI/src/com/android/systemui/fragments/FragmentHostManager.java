/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.fragments;

import android.annotation.Nullable;
import android.app.Fragment;
import android.app.FragmentController;
import android.app.FragmentHostCallback;
import android.app.FragmentManager;
import android.app.FragmentManager.FragmentLifecycleCallbacks;
import android.app.FragmentManagerNonConfig;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.os.Trace;
import android.util.ArrayMap;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.NonNull;

import com.android.settingslib.applications.InterestingConfigChanges;
import com.android.systemui.plugins.Plugin;
import com.android.systemui.util.leak.LeakDetector;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;

import javax.inject.Provider;

public class FragmentHostManager {

    private static final String TAG = "FragmentHostManager";

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Context mContext;
    private final HashMap<String, ArrayList<FragmentListener>> mListeners = new HashMap<>();
    private final View mRootView;
    private final LeakDetector mLeakDetector;
    private final InterestingConfigChanges mConfigChanges = new InterestingConfigChanges(
            ActivityInfo.CONFIG_FONT_SCALE | ActivityInfo.CONFIG_LOCALE
                    | ActivityInfo.CONFIG_ASSETS_PATHS);
    private final FragmentService mManager;
    private final ExtensionFragmentManager mPlugins = new ExtensionFragmentManager();

    private FragmentController mFragments;
    private FragmentLifecycleCallbacks mLifecycleCallbacks;

    @AssistedInject
    FragmentHostManager(
            @Assisted View rootView,
            FragmentService manager,
            LeakDetector leakDetector) {
        mContext = rootView.getContext();
        mManager = manager;
        mRootView = rootView;
        mLeakDetector = leakDetector;
        mConfigChanges.applyNewConfig(mContext.getResources());
        createFragmentHost(null);
    }

    @AssistedFactory
    public interface Factory {
        FragmentHostManager create(View rootView);
    }

    private void createFragmentHost(@Nullable Parcelable savedState) {
        mFragments = FragmentController.createController(new HostCallbacks());
        mFragments.attachHost(null);
        mLifecycleCallbacks = new FragmentLifecycleCallbacks() {
            @Override
            public void onFragmentViewCreated(FragmentManager fm, Fragment f, View v,
                    Bundle savedInstanceState) {
                FragmentHostManager.this.onFragmentViewCreated(f);
            }

            @Override
            public void onFragmentViewDestroyed(FragmentManager fm, Fragment f) {
                FragmentHostManager.this.onFragmentViewDestroyed(f);
            }

            @Override
            public void onFragmentDestroyed(FragmentManager fm, Fragment f) {
                mLeakDetector.trackGarbage(f);
            }
        };
        mFragments.getFragmentManager().registerFragmentLifecycleCallbacks(mLifecycleCallbacks,
                true);
        if (savedState != null) {
            mFragments.restoreAllState(savedState, (FragmentManagerNonConfig) null);
        }
        // For now just keep all fragments in the resumed state.
        mFragments.dispatchCreate();
        mFragments.dispatchStart();
        mFragments.dispatchResume();
    }

    @Nullable
    private Parcelable destroyFragmentHost() {
        Parcelable p = null;
        try {
            mFragments.dispatchPause();
            p = mFragments.saveAllState();
            mFragments.dispatchStop();
            mFragments.dispatchDestroy();
            mFragments
                    .getFragmentManager()
                    .unregisterFragmentLifecycleCallbacks(mLifecycleCallbacks);
        } catch (IllegalStateException e) {
            Log.e(TAG, "Failed to destroy fragment host. This is expected to happen only in "
                    + "tests when displays are added and removed quickly");
        }
        return p;
    }

    /**
     * Add a {@link FragmentListener} for a given tag
     *
     * @param tag string identifier for the fragment
     * @param listener the listener to register
     *
     * @return this
     */
    public FragmentHostManager addTagListener(
            @NonNull String tag,
            @NonNull FragmentListener listener
    ) {
        ArrayList<FragmentListener> listeners = mListeners.get(tag);
        if (listeners == null) {
            listeners = new ArrayList<>();
            mListeners.put(tag, listeners);
        }
        listeners.add(listener);
        Fragment current = getFragmentManager().findFragmentByTag(tag);
        if (current != null && current.getView() != null) {
            listener.onFragmentViewCreated(tag, current);
        }
        return this;
    }

    // Shouldn't generally be needed, included for completeness sake.
    public void removeTagListener(String tag, FragmentListener listener) {
        ArrayList<FragmentListener> listeners = mListeners.get(tag);
        if (listeners != null && listeners.remove(listener) && listeners.size() == 0) {
            mListeners.remove(tag);
        }
    }

    private void onFragmentViewCreated(Fragment fragment) {
        String tag = fragment.getTag();

        ArrayList<FragmentListener> listeners = mListeners.get(tag);
        if (listeners != null) {
            listeners.forEach((listener) -> listener.onFragmentViewCreated(tag, fragment));
        }
    }

    private void onFragmentViewDestroyed(Fragment fragment) {
        String tag = fragment.getTag();

        ArrayList<FragmentListener> listeners = mListeners.get(tag);
        if (listeners != null) {
            listeners.forEach((listener) -> listener.onFragmentViewDestroyed(tag, fragment));
        }
    }

    /**
     * Called when the configuration changed, return true if the fragments
     * should be recreated.
     */
    protected void onConfigurationChanged(Configuration newConfig) {
        if (mConfigChanges.applyNewConfig(mContext.getResources())) {
            reloadFragments();
        } else {
            mFragments.dispatchConfigurationChanged(newConfig);
        }
    }

    private void dump(String prefix, FileDescriptor fd, PrintWriter writer, String[] args) {
        // TODO: Do something?
    }

    private <T extends View> T findViewById(int id) {
        return mRootView.findViewById(id);
    }

    /**
     * Note: Values from this shouldn't be cached as they can change after config changes.
     */
    public FragmentManager getFragmentManager() {
        return mFragments.getFragmentManager();
    }

    ExtensionFragmentManager getExtensionManager() {
        return mPlugins;
    }

    void destroy() {
        mFragments.dispatchDestroy();
    }

    /**
     * Creates a fragment that requires injection.
     */
    public <T> T create(Class<T> fragmentCls) {
        return (T) mPlugins.instantiate(mContext, fragmentCls.getName(), null);
    }

    public interface FragmentListener {
        void onFragmentViewCreated(String tag, Fragment fragment);

        // The facts of lifecycle
        // When a fragment is destroyed, you should not talk to it any longer.
        default void onFragmentViewDestroyed(String tag, Fragment fragment) {
        }
    }

    public void reloadFragments() {
        Trace.beginSection("FrargmentHostManager#reloadFragments");
        // Save the old state.
        Parcelable p = destroyFragmentHost();
        // Generate a new fragment host and restore its state.
        createFragmentHost(p);
        Trace.endSection();
    }

    class HostCallbacks extends FragmentHostCallback<FragmentHostManager> {
        public HostCallbacks() {
            super(mContext, FragmentHostManager.this.mHandler, 0);
        }

        @Override
        public FragmentHostManager onGetHost() {
            return FragmentHostManager.this;
        }

        @Override
        public void onDump(String prefix, FileDescriptor fd, PrintWriter writer, String[] args) {
            FragmentHostManager.this.dump(prefix, fd, writer, args);
        }

        @Override
        public Fragment instantiate(Context context, String className, Bundle arguments) {
            return mPlugins.instantiate(context, className, arguments);
        }

        @Override
        public boolean onShouldSaveFragmentState(Fragment fragment) {
            return true; // True for now.
        }

        @Override
        public LayoutInflater onGetLayoutInflater() {
            return LayoutInflater.from(mContext);
        }

        @Override
        public boolean onUseFragmentManagerInflaterFactory() {
            return true;
        }

        @Override
        public boolean onHasWindowAnimations() {
            return false;
        }

        @Override
        public int onGetWindowAnimations() {
            return 0;
        }

        @Override
        public void onAttachFragment(Fragment fragment) {
        }

        @Override
        @Nullable
        public <T extends View> T onFindViewById(int id) {
            return FragmentHostManager.this.findViewById(id);
        }

        @Override
        public boolean onHasView() {
            return true;
        }
    }

    class ExtensionFragmentManager {
        private final ArrayMap<String, Context> mExtensionLookup = new ArrayMap<>();

        public void setCurrentExtension(int id, @NonNull  String tag, @Nullable String oldClass,
                @NonNull String currentClass, @Nullable Context context) {
            if (oldClass != null) {
                mExtensionLookup.remove(oldClass);
            }
            mExtensionLookup.put(currentClass, context);
            getFragmentManager().beginTransaction()
                    .replace(id, instantiate(context, currentClass, null), tag)
                    .commit();
            reloadFragments();
        }

        Fragment instantiate(Context context, String className, Bundle arguments) {
            Context extensionContext = mExtensionLookup.get(className);
            if (extensionContext != null) {
                Fragment f = instantiateWithInjections(extensionContext, className, arguments);
                if (f instanceof Plugin) {
                    ((Plugin) f).onCreate(mContext, extensionContext);
                }
                return f;
            }
            return instantiateWithInjections(context, className, arguments);
        }

        private Fragment instantiateWithInjections(Context context, String className, Bundle args) {
            Provider<? extends Fragment> fragmentProvider =
                    mManager.getInjectionMap().get(className);
            if (fragmentProvider != null) {
                Fragment f = fragmentProvider.get();
                // Setup the args, taken from Fragment#instantiate.
                if (args != null) {
                    args.setClassLoader(f.getClass().getClassLoader());
                    f.setArguments(args);
                }
                return f;
            }
            return Fragment.instantiate(context, className, args);
        }
    }

    private static class PluginState {
        Context mContext;
        String mCls;

        private PluginState(String cls, Context context) {
            mCls = cls;
            mContext = context;
        }
    }
}
