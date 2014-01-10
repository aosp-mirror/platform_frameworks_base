/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.TextureView;
import android.view.TextureView.SurfaceTextureListener;
import android.view.ViewGroup;
import android.view.WindowManager;

public class ActivityView extends ViewGroup {
    private final TextureView mTextureView;
    private IActivityContainer mActivityContainer;
    private Activity mActivity;
    private boolean mAttached;
    private int mWidth;
    private int mHeight;

    public ActivityView(Context context) {
        this(context, null);
    }

    public ActivityView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ActivityView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                mActivity = (Activity)context;
                break;
            }
            context = ((ContextWrapper)context).getBaseContext();
        }
        if (mActivity == null) {
            throw new IllegalStateException("The ActivityView's Context is not an Activity.");
        }

        mTextureView = new TextureView(context);
        mTextureView.setSurfaceTextureListener(new ActivityViewSurfaceTextureListener());
        addView(mTextureView);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        mTextureView.layout(l, t, r, b);
    }

    @Override
    protected void onAttachedToWindow() {
        try {
            final IBinder token = mActivity.getActivityToken();
            mActivityContainer =
                    ActivityManagerNative.getDefault().createActivityContainer(token, null);
        } catch (RemoteException e) {
            throw new IllegalStateException("ActivityView: Unable to create ActivityContainer. "
                    + e);
        }

        final SurfaceTexture surfaceTexture = mTextureView.getSurfaceTexture();
        if (surfaceTexture != null) {
            createActivityView(surfaceTexture);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mActivityContainer != null) {
            try {
                mActivityContainer.deleteActivityView();
            } catch (RemoteException e) {
            }
            mActivityContainer = null;
        }
        mAttached = false;
    }

    public void startActivity(Intent intent) {
        if (mActivityContainer != null && mAttached) {
            try {
                mActivityContainer.startActivity(intent);
            } catch (RemoteException e) {
                throw new IllegalStateException("ActivityView: Unable to startActivity. " + e);
            }
        }
    }

    /** Call when both mActivityContainer and mTextureView's SurfaceTexture are not null */
    private void createActivityView(SurfaceTexture surfaceTexture) {
        WindowManager wm = (WindowManager)mActivity.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(metrics);

        try {
            mActivityContainer.createActivityView(new Surface(surfaceTexture), mWidth, mHeight,
                    metrics.densityDpi);
        } catch (RemoteException e) {
            mActivityContainer = null;
            throw new IllegalStateException(
                    "ActivityView: Unable to create ActivityContainer. " + e);
        }
        mAttached = true;
    }

    private class ActivityViewSurfaceTextureListener implements SurfaceTextureListener {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width,
                int height) {
            mWidth = width;
            mHeight = height;
            if (mActivityContainer != null) {
                createActivityView(surfaceTexture);
            }
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width,
                int height) {
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            try {
                mActivityContainer.deleteActivityView();
                // TODO: Add binderDied to handle this nullification.
                mActivityContainer = null;
            } catch (RemoteException r) {
            }
            mAttached = false;
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {

        }

    }
}
