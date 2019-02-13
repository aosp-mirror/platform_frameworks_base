/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.keyguard.clock;

import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;

import androidx.annotation.VisibleForTesting;

import com.android.keyguard.R;
import com.android.systemui.dock.DockManager;
import com.android.systemui.dock.DockManager.DockEventListener;
import com.android.systemui.plugins.ClockPlugin;
import com.android.systemui.statusbar.policy.ExtensionController;
import com.android.systemui.statusbar.policy.ExtensionController.Extension;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Manages custom clock faces.
 */
@Singleton
public final class ClockManager {

    private final ContentResolver mContentResolver;

    private final List<ClockInfo> mClockInfos = new ArrayList<>();
    /**
     * Observe settings changes to know when to switch the clock face.
     */
    private final ContentObserver mContentObserver =
            new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    super.onChange(selfChange);
                    if (mClockExtension != null) {
                        mClockExtension.reload();
                    }
                }
            };
    private final ExtensionController mExtensionController;
    /**
     * Used to select between plugin or default implementations of ClockPlugin interface.
     */
    private Extension<ClockPlugin> mClockExtension;
    /**
     * Consumer that accepts the a new ClockPlugin implementation when the Extension reloads.
     */
    private final Consumer<ClockPlugin> mClockPluginConsumer = this::setClockPlugin;
    /**
     * Supplier of default ClockPlugin implementation.
     */
    private final DefaultClockSupplier mDefaultClockSupplier;
    /**
     * Observe changes to dock state to know when to switch the clock face.
     */
    private final DockEventListener mDockEventListener =
            new DockEventListener() {
                @Override
                public void onEvent(int event) {
                    final boolean isDocked = (event == DockManager.STATE_DOCKED
                            || event == DockManager.STATE_DOCKED_HIDE);
                    mDefaultClockSupplier.setDocked(isDocked);
                    if (mClockExtension != null) {
                        mClockExtension.reload();
                    }
                }
            };
    @Nullable
    private final DockManager mDockManager;

    private final List<ClockChangedListener> mListeners = new ArrayList<>();

    @Inject
    public ClockManager(Context context, ExtensionController extensionController,
            @Nullable DockManager dockManager) {
        mExtensionController = extensionController;
        mDockManager = dockManager;
        mContentResolver = context.getContentResolver();

        Resources res = context.getResources();
        mClockInfos.add(ClockInfo.builder()
                .setName("default")
                .setTitle(res.getString(R.string.clock_title_default))
                .setId("default")
                .setThumbnail(() -> BitmapFactory.decodeResource(res, R.drawable.default_thumbnail))
                .setPreview(() -> BitmapFactory.decodeResource(res, R.drawable.default_preview))
                .build());
        mClockInfos.add(ClockInfo.builder()
                .setName("bubble")
                .setTitle(res.getString(R.string.clock_title_bubble))
                .setId(BubbleClockController.class.getName())
                .setThumbnail(() -> BitmapFactory.decodeResource(res, R.drawable.bubble_thumbnail))
                .setPreview(() -> BitmapFactory.decodeResource(res, R.drawable.bubble_preview))
                .build());
        mClockInfos.add(ClockInfo.builder()
                .setName("stretch")
                .setTitle(res.getString(R.string.clock_title_stretch))
                .setId(StretchAnalogClockController.class.getName())
                .setThumbnail(() -> BitmapFactory.decodeResource(res, R.drawable.stretch_thumbnail))
                .setPreview(() -> BitmapFactory.decodeResource(res, R.drawable.stretch_preview))
                .build());
        mClockInfos.add(ClockInfo.builder()
                .setName("type")
                .setTitle(res.getString(R.string.clock_title_type))
                .setId(TypeClockController.class.getName())
                .setThumbnail(() -> BitmapFactory.decodeResource(res, R.drawable.type_thumbnail))
                .setPreview(() -> BitmapFactory.decodeResource(res, R.drawable.type_preview))
                .build());

        mDefaultClockSupplier = new DefaultClockSupplier(new SettingsWrapper(mContentResolver),
                LayoutInflater.from(context));
    }

    /**
     * Add listener to be notified when clock implementation should change.
     */
    public void addOnClockChangedListener(ClockChangedListener listener) {
        if (mListeners.isEmpty()) {
            register();
        }
        mListeners.add(listener);
        if (mClockExtension != null) {
            mClockExtension.reload();
        }
    }

    /**
     * Remove listener added with {@link addOnClockChangedListener}.
     */
    public void removeOnClockChangedListener(ClockChangedListener listener) {
        mListeners.remove(listener);
        if (mListeners.isEmpty()) {
            unregister();
        }
    }

    /**
     * Get information about available clock faces.
     */
    List<ClockInfo> getClockInfos() {
        return mClockInfos;
    }

    private void setClockPlugin(ClockPlugin plugin) {
        for (int i = 0; i < mListeners.size(); i++) {
            // It probably doesn't make sense to supply the same plugin instances to multiple
            // listeners. This should be fine for now since there is only a single listener.
            mListeners.get(i).onClockChanged(plugin);
        }
    }

    private void register() {
        mContentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE),
                false, mContentObserver);
        mContentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.DOCKED_CLOCK_FACE),
                false, mContentObserver);
        if (mDockManager != null) {
            mDockManager.addListener(mDockEventListener);
        }
        mClockExtension = mExtensionController.newExtension(ClockPlugin.class)
            .withPlugin(ClockPlugin.class)
            .withCallback(mClockPluginConsumer)
            .withDefault(mDefaultClockSupplier)
            .build();
    }

    private void unregister() {
        mContentResolver.unregisterContentObserver(mContentObserver);
        if (mDockManager != null) {
            mDockManager.removeListener(mDockEventListener);
        }
        mClockExtension.destroy();
    }

    @VisibleForTesting
    boolean isDocked() {
        return mDefaultClockSupplier.isDocked();
    }

    /**
     * Listener for events that should cause the custom clock face to change.
     */
    public interface ClockChangedListener {
        /**
         * Called when custom clock should change.
         *
         * @param clock Custom clock face to use. A null value indicates the default clock face.
         */
        void onClockChanged(ClockPlugin clock);
    }
}
