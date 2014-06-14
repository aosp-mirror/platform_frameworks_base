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
import dalvik.system.CloseGuard;

import java.lang.ref.WeakReference;

/** @hide */
public class ActivityView extends ViewGroup {
    private static final String TAG = "ActivityView";
    private static final boolean DEBUG = false;

    DisplayMetrics mMetrics;
    private final TextureView mTextureView;
    private ActivityContainerWrapper mActivityContainer;
    private Activity mActivity;
    private int mWidth;
    private int mHeight;
    private Surface mSurface;
    private int mLastVisibility;
    private ActivityViewCallback mActivityViewCallback;

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

        try {
            mActivityContainer = new ActivityContainerWrapper(
                    ActivityManagerNative.getDefault().createActivityContainer(
                            mActivity.getActivityToken(), new ActivityContainerCallback(this)));
        } catch (RemoteException e) {
            throw new RuntimeException("ActivityView: Unable to create ActivityContainer. "
                    + e);
        }

        mTextureView = new TextureView(context);
        mTextureView.setSurfaceTextureListener(new ActivityViewSurfaceTextureListener());
        addView(mTextureView);

        WindowManager wm = (WindowManager)mActivity.getSystemService(Context.WINDOW_SERVICE);
        mMetrics = new DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(mMetrics);

        mLastVisibility = getVisibility();

        if (DEBUG) Log.v(TAG, "ctor()");
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        mTextureView.layout(0, 0, r - l, b - t);
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);

        if (mSurface != null) {
            try {
                if (visibility == View.GONE) {
                    mActivityContainer.setSurface(null, mWidth, mHeight, mMetrics.densityDpi);
                } else if (mLastVisibility == View.GONE) {
                    // Don't change surface when going between View.VISIBLE and View.INVISIBLE.
                    mActivityContainer.setSurface(mSurface, mWidth, mHeight, mMetrics.densityDpi);
                }
            } catch (RemoteException e) {
                throw new RuntimeException(
                        "ActivityView: Unable to set surface of ActivityContainer. " + e);
            }
        }
        mLastVisibility = visibility;
    }

    private boolean injectInputEvent(InputEvent event) {
        return mActivityContainer != null && mActivityContainer.injectEvent(event);
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

    @Override
    public void onAttachedToWindow() {
        if (DEBUG) Log.v(TAG, "onAttachedToWindow(): mActivityContainer=" + mActivityContainer +
                " mSurface=" + mSurface);
    }

    @Override
    public void onDetachedFromWindow() {
        if (DEBUG) Log.v(TAG, "onDetachedFromWindow(): mActivityContainer=" + mActivityContainer +
                " mSurface=" + mSurface);
    }

    public boolean isAttachedToDisplay() {
        return mSurface != null;
    }

    public void startActivity(Intent intent) {
        if (mActivityContainer == null) {
            throw new IllegalStateException("Attempt to call startActivity after release");
        }
        if (DEBUG) Log.v(TAG, "startActivity(): intent=" + intent + " " +
                (isAttachedToDisplay() ? "" : "not") + " attached");
        if (mSurface != null) {
            mActivityContainer.startActivity(intent);
        } else {
            mActivityContainer.checkEmbeddedAllowed(intent);
            mQueuedIntent = intent;
            mQueuedPendingIntent = null;
        }
    }

    public void startActivity(IntentSender intentSender) {
        if (mActivityContainer == null) {
            throw new IllegalStateException("Attempt to call startActivity after release");
        }
        if (DEBUG) Log.v(TAG, "startActivityIntentSender(): intentSender=" + intentSender + " " +
                (isAttachedToDisplay() ? "" : "not") + " attached");
        final IIntentSender iIntentSender = intentSender.getTarget();
        if (mSurface != null) {
            mActivityContainer.startActivityIntentSender(iIntentSender);
        } else {
            mActivityContainer.checkEmbeddedAllowedIntentSender(iIntentSender);
            mQueuedPendingIntent = iIntentSender;
            mQueuedIntent = null;
        }
    }

    public void startActivity(PendingIntent pendingIntent) {
        if (mActivityContainer == null) {
            throw new IllegalStateException("Attempt to call startActivity after release");
        }
        if (DEBUG) Log.v(TAG, "startActivityPendingIntent(): PendingIntent=" + pendingIntent + " "
                + (isAttachedToDisplay() ? "" : "not") + " attached");
        final IIntentSender iIntentSender = pendingIntent.getTarget();
        if (mSurface != null) {
            mActivityContainer.startActivityIntentSender(iIntentSender);
        } else {
            mActivityContainer.checkEmbeddedAllowedIntentSender(iIntentSender);
            mQueuedPendingIntent = iIntentSender;
            mQueuedIntent = null;
        }
    }

    public void release() {
        if (DEBUG) Log.v(TAG, "release() mActivityContainer=" + mActivityContainer +
                " mSurface=" + mSurface);
        if (mActivityContainer == null) {
            Log.e(TAG, "Duplicate call to release");
            return;
        }
        mActivityContainer.release();
        mActivityContainer = null;

        if (mSurface != null) {
            mSurface.release();
            mSurface = null;
        }

        mTextureView.setSurfaceTextureListener(null);
    }

    private void attachToSurfaceWhenReady() {
        final SurfaceTexture surfaceTexture = mTextureView.getSurfaceTexture();
        if (surfaceTexture == null || mSurface != null) {
            // Either not ready to attach, or already attached.
            return;
        }

        mSurface = new Surface(surfaceTexture);
        try {
            mActivityContainer.setSurface(mSurface, mWidth, mHeight, mMetrics.densityDpi);
        } catch (RemoteException e) {
            mSurface.release();
            mSurface = null;
            throw new RuntimeException("ActivityView: Unable to create ActivityContainer. " + e);
        }

        if (DEBUG) Log.v(TAG, "attachToSurfaceWhenReady: " + (mQueuedIntent != null ||
                mQueuedPendingIntent != null ? "" : "no") + " queued intent");
        if (mQueuedIntent != null) {
            mActivityContainer.startActivity(mQueuedIntent);
            mQueuedIntent = null;
        } else if (mQueuedPendingIntent != null) {
            mActivityContainer.startActivityIntentSender(mQueuedPendingIntent);
            mQueuedPendingIntent = null;
        }
    }

    /**
     * Set the callback to use to report certain state changes.
     * @param callback The callback to report events to.
     *
     * @see ActivityViewCallback
     */
    public void setCallback(ActivityViewCallback callback) {
        mActivityViewCallback = callback;
    }

    public static abstract class ActivityViewCallback {
        /**
         * Called when all activities in the ActivityView have completed and been removed. Register
         * using {@link ActivityView#setCallback(ActivityViewCallback)}. Each ActivityView may
         * have at most one callback registered.
         */
        public abstract void onAllActivitiesComplete(ActivityView view);
    }

    private class ActivityViewSurfaceTextureListener implements SurfaceTextureListener {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width,
                int height) {
            if (mActivityContainer == null) {
                return;
            }
            if (DEBUG) Log.d(TAG, "onSurfaceTextureAvailable: width=" + width + " height="
                    + height);
            mWidth = width;
            mHeight = height;
            attachToSurfaceWhenReady();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width,
                int height) {
            if (mActivityContainer == null) {
                return;
            }
            if (DEBUG) Log.d(TAG, "onSurfaceTextureSizeChanged: w=" + width + " h=" + height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            if (mActivityContainer == null) {
                return true;
            }
            if (DEBUG) Log.d(TAG, "onSurfaceTextureDestroyed");
            mSurface.release();
            mSurface = null;
            try {
                mActivityContainer.setSurface(null, mWidth, mHeight, mMetrics.densityDpi);
            } catch (RemoteException e) {
                throw new RuntimeException(
                        "ActivityView: Unable to set surface of ActivityContainer. " + e);
            }
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
//            Log.d(TAG, "onSurfaceTextureUpdated");
        }

    }

    private static class ActivityContainerCallback extends IActivityContainerCallback.Stub {
        private final WeakReference<ActivityView> mActivityViewWeakReference;

        ActivityContainerCallback(ActivityView activityView) {
            mActivityViewWeakReference = new WeakReference<ActivityView>(activityView);
        }

        @Override
        public void setVisible(IBinder container, boolean visible) {
            if (DEBUG) Log.v(TAG, "setVisible(): container=" + container + " visible=" + visible +
                    " ActivityView=" + mActivityViewWeakReference.get());
        }

        @Override
        public void onAllActivitiesComplete(IBinder container) {
            final ActivityView activityView = mActivityViewWeakReference.get();
            if (activityView != null) {
                final ActivityViewCallback callback = activityView.mActivityViewCallback;
                if (callback != null) {
                    activityView.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onAllActivitiesComplete(activityView);
                        }
                    });
                }
            }
        }
    }

    private static class ActivityContainerWrapper {
        private final IActivityContainer mIActivityContainer;
        private final CloseGuard mGuard = CloseGuard.get();
        boolean mOpened; // Protected by mGuard.

        ActivityContainerWrapper(IActivityContainer container) {
            mIActivityContainer = container;
            mOpened = true;
            mGuard.open("release");
        }

        void attachToDisplay(int displayId) {
            try {
                mIActivityContainer.attachToDisplay(displayId);
            } catch (RemoteException e) {
            }
        }

        void setSurface(Surface surface, int width, int height, int density)
                throws RemoteException {
            mIActivityContainer.setSurface(surface, width, height, density);
        }

        int startActivity(Intent intent) {
            try {
                return mIActivityContainer.startActivity(intent);
            } catch (RemoteException e) {
                throw new RuntimeException("ActivityView: Unable to startActivity. " + e);
            }
        }

        int startActivityIntentSender(IIntentSender intentSender) {
            try {
                return mIActivityContainer.startActivityIntentSender(intentSender);
            } catch (RemoteException e) {
                throw new RuntimeException(
                        "ActivityView: Unable to startActivity from IntentSender. " + e);
            }
        }

        void checkEmbeddedAllowed(Intent intent) {
            try {
                mIActivityContainer.checkEmbeddedAllowed(intent);
            } catch (RemoteException e) {
                throw new RuntimeException(
                        "ActivityView: Unable to startActivity from Intent. " + e);
            }
        }

        void checkEmbeddedAllowedIntentSender(IIntentSender intentSender) {
            try {
                mIActivityContainer.checkEmbeddedAllowedIntentSender(intentSender);
            } catch (RemoteException e) {
                throw new RuntimeException(
                        "ActivityView: Unable to startActivity from IntentSender. " + e);
            }
        }

        int getDisplayId() {
            try {
                return mIActivityContainer.getDisplayId();
            } catch (RemoteException e) {
                return -1;
            }
        }

        boolean injectEvent(InputEvent event) {
            try {
                return mIActivityContainer.injectEvent(event);
            } catch (RemoteException e) {
                return false;
            }
        }

        void release() {
            synchronized (mGuard) {
                if (mOpened) {
                    if (DEBUG) Log.v(TAG, "ActivityContainerWrapper: release called");
                    try {
                        mIActivityContainer.release();
                        mGuard.close();
                    } catch (RemoteException e) {
                    }
                    mOpened = false;
                }
            }
        }

        @Override
        protected void finalize() throws Throwable {
            if (DEBUG) Log.v(TAG, "ActivityContainerWrapper: finalize called");
            try {
                if (mGuard != null) {
                    mGuard.warnIfOpen();
                    release();
                }
            } finally {
                super.finalize();
            }
        }

    }
}
