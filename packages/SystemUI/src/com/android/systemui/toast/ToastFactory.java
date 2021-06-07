/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.toast;

import android.content.Context;
import android.view.LayoutInflater;

import androidx.annotation.NonNull;

import com.android.systemui.Dumpable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.plugins.ToastPlugin;
import com.android.systemui.shared.plugins.PluginManager;

import java.io.FileDescriptor;
import java.io.PrintWriter;

import javax.inject.Inject;

/**
 * Factory for creating toasts to be shown by ToastUI.
 * These toasts can be customized by {@link ToastPlugin}.
 */
@SysUISingleton
public class ToastFactory implements Dumpable {
    // only one ToastPlugin can be connected at a time.
    private ToastPlugin mPlugin;
    private final LayoutInflater mLayoutInflater;

    @Inject
    public ToastFactory(
            LayoutInflater layoutInflater,
            PluginManager pluginManager,
            DumpManager dumpManager) {
        mLayoutInflater = layoutInflater;
        dumpManager.registerDumpable("ToastFactory", this);
        pluginManager.addPluginListener(
                new PluginListener<ToastPlugin>() {
                    @Override
                    public void onPluginConnected(ToastPlugin plugin, Context pluginContext) {
                        mPlugin = plugin;
                    }

                    @Override
                    public void onPluginDisconnected(ToastPlugin plugin) {
                        if (plugin.equals(mPlugin)) {
                            mPlugin = null;
                        }
                    }
                }, ToastPlugin.class, false /* Allow multiple plugins */);
    }

    /**
     * Create a toast to be shown by ToastUI.
     */
    public SystemUIToast createToast(Context context, CharSequence text, String packageName,
            int userId, int orientation) {
        if (isPluginAvailable()) {
            return new SystemUIToast(mLayoutInflater, context, text, mPlugin.createToast(text,
                    packageName, userId), packageName, userId, orientation);
        }
        return new SystemUIToast(mLayoutInflater, context, text, packageName, userId,
                orientation);
    }

    private boolean isPluginAvailable() {
        return mPlugin != null;
    }

    @Override
    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("ToastFactory:");
        pw.println("    mAttachedPlugin=" + mPlugin);
    }
}
