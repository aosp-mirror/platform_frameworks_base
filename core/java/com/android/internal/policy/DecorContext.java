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

package com.android.internal.policy;

import android.content.AutofillOptions;
import android.content.ContentCaptureOptions;
import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.view.ContextThemeWrapper;
import android.view.WindowManager;
import android.view.WindowManagerImpl;
import android.view.contentcapture.ContentCaptureManager;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.ref.WeakReference;

/**
 * Context for decor views which can be seeded with pure application context and not depend on the
 * activity, but still provide some of the facilities that Activity has,
 * e.g. themes, activity-based resources, etc.
 *
 * @hide
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public class DecorContext extends ContextThemeWrapper {
    private PhoneWindow mPhoneWindow;
    private WindowManager mWindowManager;
    private Resources mResources;
    private ContentCaptureManager mContentCaptureManager;

    private WeakReference<Context> mContext;

    // TODO(b/149928768): Non-activity context can be passed.
    @VisibleForTesting
    public DecorContext(Context baseContext, Context context) {
        super(baseContext.createDisplayContext(context.getDisplayNoVerify()), null);
        mContext = new WeakReference<>(context);
        mResources = context.getResources();
    }

    void setPhoneWindow(PhoneWindow phoneWindow) {
        mPhoneWindow = phoneWindow;
        mWindowManager = null;
    }

    @Override
    public Object getSystemService(String name) {
        final Context context = mContext.get();
        if (Context.WINDOW_SERVICE.equals(name)) {
            if (context != null && mWindowManager == null) {
                WindowManagerImpl wm = (WindowManagerImpl) context.getSystemService(name);
                mWindowManager = wm.createLocalWindowManager(mPhoneWindow);
            }
            return mWindowManager;
        }
        if (Context.CONTENT_CAPTURE_MANAGER_SERVICE.equals(name)) {
            if (context != null && mContentCaptureManager == null) {
                mContentCaptureManager = (ContentCaptureManager) context.getSystemService(name);
            }
            return mContentCaptureManager;
        }
        // LayoutInflater and WallpaperManagerService should also be obtained from context
        // instead of application context.
        return (context != null) ? context.getSystemService(name) : super.getSystemService(name);
    }

    @Override
    public Resources getResources() {
        Context context = mContext.get();
        // Attempt to update the local cached Resources from the activity context. If the activity
        // is no longer around, return the old cached values.
        if (context != null) {
            mResources = context.getResources();
        }

        return mResources;
    }

    @Override
    public AssetManager getAssets() {
        return mResources.getAssets();
    }

    @Override
    public AutofillOptions getAutofillOptions() {
        Context context = mContext.get();
        if (context != null) {
            return context.getAutofillOptions();
        }
        return null;
    }

    @Override
    public ContentCaptureOptions getContentCaptureOptions() {
        Context context = mContext.get();
        if (context != null) {
            return context.getContentCaptureOptions();
        }
        return null;
    }
}
