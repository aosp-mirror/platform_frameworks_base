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

import android.app.Fragment;
import android.util.Log;
import android.view.View;

import com.android.systemui.plugins.FragmentBase;
import com.android.systemui.statusbar.policy.ExtensionController.Extension;

import java.util.function.Consumer;

/**
 * Wires up an Extension to a Fragment tag/id so that it always contains the class
 * selected by the extension.
 */
public class ExtensionFragmentListener<T extends FragmentBase> implements Consumer<T> {

    private static final String TAG = "ExtensionFragmentListener";

    private final FragmentHostManager mFragmentHostManager;
    private final String mTag;
    private final Extension<T> mExtension;
    private final int mId;
    private String mOldClass;

    private ExtensionFragmentListener(View view, String tag, int id, Extension<T> extension) {
        mTag = tag;
        mFragmentHostManager = FragmentHostManager.get(view);
        mExtension = extension;
        mId = id;
        mFragmentHostManager.getFragmentManager().beginTransaction()
                .replace(id, (Fragment) mExtension.get(), mTag)
                .commit();
        mExtension.clearItem(false);
    }

    @Override
    public void accept(T extension) {
        try {
            Fragment.class.cast(extension);
            mFragmentHostManager.getExtensionManager().setCurrentExtension(mId, mTag,
                    mOldClass, extension.getClass().getName(), mExtension.getContext());
            mOldClass = extension.getClass().getName();
        } catch (ClassCastException e) {
            Log.e(TAG, extension.getClass().getName() + " must be a Fragment", e);
        }
        mExtension.clearItem(true);
    }

    public static <T> void attachExtensonToFragment(View view, String tag, int id,
            Extension<T> extension) {
        extension.addCallback(new ExtensionFragmentListener(view, tag, id, extension));
    }
}
