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
import android.support.annotation.NonNull;
import android.util.ArrayMap;
import android.view.LayoutInflater;
import android.view.View;

import com.android.settingslib.applications.InterestingConfigChanges;
import com.android.systemui.Dependency;
import com.android.systemui.plugins.Plugin;
import com.android.systemui.util.leak.LeakDetector;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;

public class FragmentHostManager {

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Context mContext;
    private final HashMap<String, ArrayList<FragmentListener>> mListeners = new HashMap<>();
    private final View mRootView;
    private final InterestingConfigChanges mConfigChanges = new InterestingConfigChanges(
            ActivityInfo.CONFIG_FONT_SCALE | ActivityInfo.CONFIG_LOCALE
                | ActivityInfo.CONFIG_SCREEN_LAYOUT | ActivityInfo.CONFIG_ASSETS_PATHS);
    private final FragmentService mManager;
    private final ExtensionFragmentManager mPlugins = new ExtensionFragmentManager();

    private FragmentController mFragments;
    private FragmentLifecycleCallbacks mLifecycleCallbacks;

    FragmentHostManager(Context context, FragmentService manager, View rootView) {
        mContext = context;
        mManager = manager;
        mRootView = rootView;
        mConfigChanges.applyNewConfig(context.getResources());
        createFragmentHost(null);
    }

    private void createFragmentHost(Parcelable savedState) {
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
                Dependency.get(LeakDetector.class).trackGarbage(f);
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

    private Parcelable destroyFragmentHost() {
        mFragments.dispatchPause();
        Parcelable p = mFragments.saveAllState();
        mFragments.dispatchStop();
        mFragments.dispatchDestroy();
        mFragments.getFragmentManager().unregisterFragmentLifecycleCallbacks(mLifecycleCallbacks);
        return p;
    }

    public FragmentHostManager addTagListener(String tag, FragmentListener listener) {
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
            // Save the old state.
            Parcelable p = destroyFragmentHost();
            // Generate a new fragment host and restore its state.
            createFragmentHost(p);
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

    public interface FragmentListener {
        void onFragmentViewCreated(String tag, Fragment fragment);

        // The facts of lifecycle
        // When a fragment is destroyed, you should not talk to it any longer.
        default void onFragmentViewDestroyed(String tag, Fragment fragment) {
        }
    }

    public static FragmentHostManager get(View view) {
        try {
            return Dependency.get(FragmentService.class).getFragmentHostManager(view);
        } catch (ClassCastException e) {
            // TODO: Some auto handling here?
            throw e;
        }
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

        private void reloadFragments() {
            // Save the old state.
            Parcelable p = destroyFragmentHost();
            // Generate a new fragment host and restore its state.
            createFragmentHost(p);
        }

        Fragment instantiate(Context context, String className, Bundle arguments) {
            Context extensionContext = mExtensionLookup.get(className);
            if (extensionContext != null) {
                Fragment f = Fragment.instantiate(extensionContext, className, arguments);
                if (f instanceof Plugin) {
                    ((Plugin) f).onCreate(mContext, extensionContext);
                }
                return f;
            }
            return Fragment.instantiate(context, className, arguments);
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
