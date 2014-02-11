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
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.graphics.SurfaceTexture;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.TextureView.SurfaceTextureListener;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

public class ActivityView extends ViewGroup {
    private final String TAG = "ActivityView";

    private final TextureView mTextureView;
    private IActivityContainer mActivityContainer;
    private Activity mActivity;
    private int mWidth;
    private int mHeight;
    private Surface mSurface;

    // Only one IIntentSender or Intent may be queued at a time. Most recent one wins.
    IIntentSender mQueuedPendingIntent;
    Intent mQueuedIntent;

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
        mTextureView.layout(0, 0, r - l, b - t);
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

        attachToSurfaceWhenReady();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mActivityContainer != null) {
            detach();
            mActivityContainer = null;
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == View.VISIBLE) {
            attachToSurfaceWhenReady();
        } else {
            detach();
        }
    }

    private boolean injectInputEvent(InputEvent event) {
        try {
            return mActivityContainer != null && mActivityContainer.injectEvent(event);
        } catch (RemoteException e) {
            return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return injectInputEvent(event) || super.onTouchEvent(event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            if (injectInputEvent(event)) {
                return true;
            }
        }
        return super.onGenericMotionEvent(event);
    }

    public boolean isAttachedToDisplay() {
        return mSurface != null;
    }

    public void startActivity(Intent intent) {
        if (mSurface != null) {
            try {
                mActivityContainer.startActivity(intent);
            } catch (RemoteException e) {
                throw new IllegalStateException("ActivityView: Unable to startActivity. " + e);
            }
        } else {
            mQueuedIntent = intent;
            mQueuedPendingIntent = null;
        }
    }

    private void startActivityIntentSender(IIntentSender iIntentSender) {
        try {
            mActivityContainer.startActivityIntentSender(iIntentSender);
        } catch (RemoteException e) {
            throw new IllegalStateException(
                    "ActivityView: Unable to startActivity from IntentSender. " + e);
        }
    }

    public void startActivity(IntentSender intentSender) {
        final IIntentSender iIntentSender = intentSender.getTarget();
        if (mSurface != null) {
            startActivityIntentSender(iIntentSender);
        } else {
            mQueuedPendingIntent = iIntentSender;
            mQueuedIntent = null;
        }
    }

    public void startActivity(PendingIntent pendingIntent) {
        final IIntentSender iIntentSender = pendingIntent.getTarget();
        if (mSurface != null) {
            startActivityIntentSender(iIntentSender);
        } else {
            mQueuedPendingIntent = iIntentSender;
            mQueuedIntent = null;
        }
    }

    private void attachToSurfaceWhenReady() {
        final SurfaceTexture surfaceTexture = mTextureView.getSurfaceTexture();
        if (mActivityContainer == null || surfaceTexture == null || mSurface != null) {
            // Either not ready to attach, or already attached.
            return;
        }

        WindowManager wm = (WindowManager)mActivity.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(metrics);

        mSurface = new Surface(surfaceTexture);
        try {
            mActivityContainer.attachToSurface(mSurface, mWidth, mHeight, metrics.densityDpi);
        } catch (RemoteException e) {
            mSurface.release();
            mSurface = null;
            throw new IllegalStateException(
                    "ActivityView: Unable to create ActivityContainer. " + e);
        }

        if (mQueuedIntent != null) {
            startActivity(mQueuedIntent);
            mQueuedIntent = null;
        } else if (mQueuedPendingIntent != null) {
            startActivityIntentSender(mQueuedPendingIntent);
            mQueuedPendingIntent = null;
        }
    }

    private void detach() {
        if (mSurface != null) {
            try {
                mActivityContainer.detachFromDisplay();
            } catch (RemoteException e) {
            }
            mSurface.release();
            mSurface = null;
        }
    }

    private class ActivityViewSurfaceTextureListener implements SurfaceTextureListener {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width,
                int height) {
            mWidth = width;
            mHeight = height;
            if (mActivityContainer != null) {
                attachToSurfaceWhenReady();
            }
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width,
                int height) {
            Log.d(TAG, "onSurfaceTextureSizeChanged: w=" + width + " h=" + height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            Log.d(TAG, "onSurfaceTextureDestroyed");
            detach();
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
//            Log.d(TAG, "onSurfaceTextureUpdated");
        }

    }
}
