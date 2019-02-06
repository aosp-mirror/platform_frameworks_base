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

import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.view.ContextThemeWrapper;
import android.view.WindowManager;
import android.view.WindowManagerImpl;
import android.view.contentcapture.ContentCaptureManager;

import java.lang.ref.WeakReference;

/**
 * Context for decor views which can be seeded with pure application context and not depend on the
 * activity, but still provide some of the facilities that Activity has,
 * e.g. themes, activity-based resources, etc.
 *
 * @hide
 */
class DecorContext extends ContextThemeWrapper {
    private PhoneWindow mPhoneWindow;
    private WindowManager mWindowManager;
    private Resources mActivityResources;
    private ContentCaptureManager mContentCaptureManager;

    private WeakReference<Context> mActivityContext;

    public DecorContext(Context context, Context activityContext) {
        super(context, null);
        mActivityContext = new WeakReference<>(activityContext);
        mActivityResources = activityContext.getResources();
    }

    void setPhoneWindow(PhoneWindow phoneWindow) {
        mPhoneWindow = phoneWindow;
        mWindowManager = null;
    }

    @Override
    public Object getSystemService(String name) {
        if (Context.WINDOW_SERVICE.equals(name)) {
            if (mWindowManager == null) {
                WindowManagerImpl wm =
                        (WindowManagerImpl) super.getSystemService(Context.WINDOW_SERVICE);
                mWindowManager = wm.createLocalWindowManager(mPhoneWindow);
            }
            return mWindowManager;
        }
        if (Context.CONTENT_CAPTURE_MANAGER_SERVICE.equals(name)) {
            if (mContentCaptureManager == null) {
                Context activityContext = mActivityContext.get();
                if (activityContext != null) {
                    mContentCaptureManager = (ContentCaptureManager) activityContext
                            .getSystemService(name);
                }
            }
            return mContentCaptureManager;
        }
        return super.getSystemService(name);
    }

    @Override
    public Resources getResources() {
        Context activityContext = mActivityContext.get();
        // Attempt to update the local cached Resources from the activity context. If the activity
        // is no longer around, return the old cached values.
        if (activityContext != null) {
            mActivityResources = activityContext.getResources();
        }

        return mActivityResources;
    }

    @Override
    public AssetManager getAssets() {
        return mActivityResources.getAssets();
    }

    @Override
    public boolean isContentCaptureSupported() {
        return true;
    }
}
