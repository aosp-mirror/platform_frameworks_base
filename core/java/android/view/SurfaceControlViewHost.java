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

package android.view;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.Log;
import android.view.accessibility.IAccessibilityEmbeddedConnection;
import android.window.ISurfaceSyncGroup;
import android.window.WindowTokenClient;

import dalvik.system.CloseGuard;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Utility class for adding a View hierarchy to a {@link SurfaceControl}. The View hierarchy
 * will render in to a root SurfaceControl, and receive input based on the SurfaceControl's
 * placement on-screen. The primary usage of this class is to embed a View hierarchy from
 * one process in to another. After the SurfaceControlViewHost has been set up in the embedded
 * content provider, we can send the {@link SurfaceControlViewHost.SurfacePackage}
 * to the host process. The host process can then attach the hierarchy to a SurfaceView within
 * its own by calling
 * {@link SurfaceView#setChildSurfacePackage}.
 */
public class SurfaceControlViewHost {
    private final static String TAG = "SurfaceControlViewHost";
    private final ViewRootImpl mViewRoot;
    private final CloseGuard mCloseGuard = CloseGuard.get();
    private final WindowlessWindowManager mWm;

    private SurfaceControl mSurfaceControl;
    private IAccessibilityEmbeddedConnection mAccessibilityEmbeddedConnection;
    private boolean mReleased = false;

    private final class ISurfaceControlViewHostImpl extends ISurfaceControlViewHost.Stub {
        @Override
        public void onConfigurationChanged(Configuration configuration) {
            if (mViewRoot == null) {
                return;
            }
            mViewRoot.mHandler.post(() -> {
                mWm.setConfiguration(configuration);
                if (mViewRoot != null) {
                    mViewRoot.forceWmRelayout();
                }
            });
        }

        @Override
        public void onDispatchDetachedFromWindow() {
            if (mViewRoot == null) {
                return;
            }
            mViewRoot.mHandler.post(() -> {
                release();
            });
        }

        @Override
        public void onInsetsChanged(InsetsState state, Rect frame) {
            if (mViewRoot != null) {
                mViewRoot.mHandler.post(() -> {
                    mViewRoot.setOverrideInsetsFrame(frame);
                });
            }
            mWm.setInsetsState(state);
        }

        @Override
        public ISurfaceSyncGroup getSurfaceSyncGroup() {
            CompletableFuture<ISurfaceSyncGroup> surfaceSyncGroup = new CompletableFuture<>();
            // If the call came from in process and it's already running on the UI thread, return
            // results immediately instead of posting to the main thread. If we post to the main
            // thread, it will block itself and the return value will always be null.
            if (Thread.currentThread() == mViewRoot.mThread) {
                return mViewRoot.getOrCreateSurfaceSyncGroup().mISurfaceSyncGroup;
            } else {
                mViewRoot.mHandler.post(
                        () -> surfaceSyncGroup.complete(
                                mViewRoot.getOrCreateSurfaceSyncGroup().mISurfaceSyncGroup));
            }
            try {
                return surfaceSyncGroup.get(1, TimeUnit.SECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                Log.e(TAG, "Failed to get SurfaceSyncGroup for SCVH", e);
            }
            return null;
        }

        @Override
        public void attachParentInterface(@Nullable ISurfaceControlViewHostParent parentInterface) {
            mViewRoot.mHandler.post(() -> mWm.setParentInterface(parentInterface));
        }
    }

    private ISurfaceControlViewHost mRemoteInterface = new ISurfaceControlViewHostImpl();

    private ViewRootImpl.ConfigChangedCallback mConfigChangedCallback;

    /**
     * Package encapsulating a Surface hierarchy which contains interactive view
     * elements. It's expected to get this object from
     * {@link SurfaceControlViewHost#getSurfacePackage} afterwards it can be embedded within
     * a SurfaceView by calling {@link SurfaceView#setChildSurfacePackage}.
     *
     * Note that each {@link SurfacePackage} must be released by calling
     * {@link SurfacePackage#release}. However, if you use the recommended flow,
     *  the framework will automatically handle the lifetime for you.
     *
     * 1. When sending the package to the remote process, return it from an AIDL method
     * or manually use FLAG_WRITE_RETURN_VALUE in writeToParcel. This will automatically
     * release the package in the local process.
     * 2. In the remote process, consume the package using SurfaceView. This way the
     * SurfaceView will take over the lifetime and call {@link SurfacePackage#release}
     * for the user.
     *
     * One final note: The {@link SurfacePackage} lifetime is totally de-coupled
     * from the lifetime of the underlying {@link SurfaceControlViewHost}. Regardless
     * of the lifetime of the package the user should still call
     * {@link SurfaceControlViewHost#release} when finished.
     */
    public static final class SurfacePackage implements Parcelable {
        private SurfaceControl mSurfaceControl;
        private final IAccessibilityEmbeddedConnection mAccessibilityEmbeddedConnection;
        private final IBinder mInputToken;
        @NonNull
        private final ISurfaceControlViewHost mRemoteInterface;

        SurfacePackage(SurfaceControl sc, IAccessibilityEmbeddedConnection connection,
                IBinder inputToken, @NonNull ISurfaceControlViewHost ri) {
            mSurfaceControl = sc;
            mAccessibilityEmbeddedConnection = connection;
            mInputToken = inputToken;
            mRemoteInterface = ri;
        }

        /**
         * Constructs a copy of {@code SurfacePackage} with an independent lifetime.
         *
         * The caller can use this to create an independent copy in situations where ownership of
         * the {@code SurfacePackage} would be transferred elsewhere, such as attaching to a
         * {@code SurfaceView}, returning as {@code Binder} result value, etc. The caller is
         * responsible for releasing this copy when its done.
         *
         * @param other {@code SurfacePackage} to create a copy of.
         */
        public SurfacePackage(@NonNull SurfacePackage other) {
            SurfaceControl otherSurfaceControl = other.mSurfaceControl;
            if (otherSurfaceControl != null && otherSurfaceControl.isValid()) {
                mSurfaceControl = new SurfaceControl(otherSurfaceControl, "SurfacePackage");
            }
            mAccessibilityEmbeddedConnection = other.mAccessibilityEmbeddedConnection;
            mInputToken = other.mInputToken;
            mRemoteInterface = other.mRemoteInterface;
        }

        private SurfacePackage(Parcel in) {
            mSurfaceControl = new SurfaceControl();
            mSurfaceControl.readFromParcel(in);
            mSurfaceControl.setUnreleasedWarningCallSite("SurfacePackage(Parcel)");
            mAccessibilityEmbeddedConnection = IAccessibilityEmbeddedConnection.Stub.asInterface(
                    in.readStrongBinder());
            mInputToken = in.readStrongBinder();
            mRemoteInterface = ISurfaceControlViewHost.Stub.asInterface(
                in.readStrongBinder());
        }

        /**
         * Returns the {@link android.view.SurfaceControl} associated with this SurfacePackage for
         * cases where more control is required.
         *
         * @return the SurfaceControl associated with this SurfacePackage and its containing
         *     SurfaceControlViewHost
         */
        public @NonNull SurfaceControl getSurfaceControl() {
            return mSurfaceControl;
        }

        /**
         * Gets an accessibility embedded connection interface for this SurfaceControlViewHost.
         *
         * @return {@link IAccessibilityEmbeddedConnection} interface.
         * @hide
         */
        public IAccessibilityEmbeddedConnection getAccessibilityEmbeddedConnection() {
            return mAccessibilityEmbeddedConnection;
        }

        /**
         * @hide
         */
        @NonNull
        public ISurfaceControlViewHost getRemoteInterface() {
            return mRemoteInterface;
        }

        /**
         * Forward a configuration to the remote SurfaceControlViewHost.
         * This will cause View#onConfigurationChanged to be invoked on the remote
         * end. This does not automatically cause the SurfaceControlViewHost
         * to be resized. The root View of a SurfaceControlViewHost
         * is more akin to a PopupWindow in that the size is user specified
         * independent of configuration width and height.
         *
         * In order to receive the configuration change via
         * {@link View#onConfigurationChanged}, the context used with the
         * SurfaceControlViewHost and it's embedded view hierarchy must
         * be a WindowContext obtained from {@link Context#createWindowContext}.
         *
         * If a regular service context is used, then your embedded view hierarchy
         * will always perceive the global configuration.
         *
         * @param c The configuration to forward
         */
        public void notifyConfigurationChanged(@NonNull Configuration c) {
            try {
                getRemoteInterface().onConfigurationChanged(c);
            } catch (RemoteException e) {
                e.rethrowAsRuntimeException();
            }
        }

        /**
         * Tear down the remote SurfaceControlViewHost and cause
         * View#onDetachedFromWindow to be invoked on the other side.
         */
        public void notifyDetachedFromWindow() {
            try {
                getRemoteInterface().onDispatchDetachedFromWindow();
            } catch (RemoteException e) {
                e.rethrowAsRuntimeException();
            }
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel out, int flags) {
            mSurfaceControl.writeToParcel(out, flags);
            out.writeStrongBinder(mAccessibilityEmbeddedConnection.asBinder());
            out.writeStrongBinder(mInputToken);
            out.writeStrongBinder(mRemoteInterface.asBinder());
        }

        /**
         * Release the {@link SurfaceControl} associated with this package.
         * It's not necessary to call this if you pass the package to
         * {@link SurfaceView#setChildSurfacePackage} as {@link SurfaceView} will
         * take ownership in that case.
         */
        public void release() {
            if (mSurfaceControl != null) {
                mSurfaceControl.release();
             }
             mSurfaceControl = null;
        }

        /**
         * Returns an input token used which can be used to request focus on the embedded surface
         * or to transfer touch gesture to the embedded surface.
         *
         * @hide
         */
        public IBinder getInputToken() {
            return mInputToken;
        }

        public static final @NonNull Creator<SurfacePackage> CREATOR
             = new Creator<SurfacePackage>() {
                     public SurfacePackage createFromParcel(Parcel in) {
                         return new SurfacePackage(in);
                     }
                     public SurfacePackage[] newArray(int size) {
                         return new SurfacePackage[size];
                     }
             };
    }

    /** @hide */
    public SurfaceControlViewHost(@NonNull Context c, @NonNull Display d,
            @NonNull WindowlessWindowManager wwm, @NonNull String callsite) {
        mSurfaceControl = wwm.mRootSurface;
        mWm = wwm;
        mViewRoot = new ViewRootImpl(c, d, mWm, new WindowlessWindowLayout());
        mCloseGuard.openWithCallSite("release", callsite);
        setConfigCallback(c, d);

        WindowManagerGlobal.getInstance().addWindowlessRoot(mViewRoot);

        mAccessibilityEmbeddedConnection = mViewRoot.getAccessibilityEmbeddedConnection();
    }

    /**
     * Construct a new SurfaceControlViewHost. The root Surface will be
     * allocated internally and is accessible via getSurfacePackage().
     *
     * The {@param hostToken} parameter, primarily used for ANR reporting,
     * must be obtained from whomever will be hosting the embedded hierarchy.
     * It's accessible from {@link SurfaceView#getHostToken}.
     *
     * @param context The Context object for your activity or application.
     * @param display The Display the hierarchy will be placed on.
     * @param hostToken The host token, as discussed above.
     */
    public SurfaceControlViewHost(@NonNull Context context, @NonNull Display display,
            @Nullable IBinder hostToken) {
        this(context, display, hostToken, "untracked");
    }

    /**
     * Construct a new SurfaceControlViewHost. The root Surface will be
     * allocated internally and is accessible via getSurfacePackage().
     *
     * The {@param hostToken} parameter, primarily used for ANR reporting,
     * must be obtained from whomever will be hosting the embedded hierarchy.
     * It's accessible from {@link SurfaceView#getHostToken}.
     *
     * @param context The Context object for your activity or application.
     * @param display The Display the hierarchy will be placed on.
     * @param hostToken The host token, as discussed above.
     * @param callsite The call site, used for tracking leakage of the host
     * @hide
     */
    public SurfaceControlViewHost(@NonNull Context context, @NonNull Display display,
            @Nullable IBinder hostToken, @NonNull String callsite) {
        mSurfaceControl = new SurfaceControl.Builder()
                .setContainerLayer()
                .setName("SurfaceControlViewHost")
                .setCallsite("SurfaceControlViewHost[" + callsite + "]")
                .build();
        mWm = new WindowlessWindowManager(context.getResources().getConfiguration(),
                mSurfaceControl, hostToken);

        mViewRoot = new ViewRootImpl(context, display, mWm, new WindowlessWindowLayout());
        mCloseGuard.openWithCallSite("release", callsite);
        setConfigCallback(context, display);

        WindowManagerGlobal.getInstance().addWindowlessRoot(mViewRoot);

        mAccessibilityEmbeddedConnection = mViewRoot.getAccessibilityEmbeddedConnection();
    }

    private void setConfigCallback(Context c, Display d) {
        final IBinder token = c.getWindowContextToken();
        mConfigChangedCallback = conf -> {
            if (token instanceof WindowTokenClient) {
                final WindowTokenClient w = (WindowTokenClient)  token;
                w.onConfigurationChanged(conf, d.getDisplayId(), true);
            }
        };

        ViewRootImpl.addConfigCallback(mConfigChangedCallback);
    }

    /**
     * @hide
     */
    @Override
    protected void finalize() throws Throwable {
        if (mReleased) {
            return;
        }
        if (mCloseGuard != null) {
            mCloseGuard.warnIfOpen();
        }
        // We aren't on the UI thread here so we need to pass false to doDie
        doRelease(false /* immediate */);
    }

    /**
     * Return a SurfacePackage for the root SurfaceControl of the embedded hierarchy.
     * Rather than be directly reparented using {@link SurfaceControl.Transaction} this
     * SurfacePackage should be passed to {@link SurfaceView#setChildSurfacePackage}
     * which will not only reparent the Surface, but ensure the accessibility hierarchies
     * are linked.
     */
    public @Nullable SurfacePackage getSurfacePackage() {
        if (mSurfaceControl != null && mAccessibilityEmbeddedConnection != null) {
            return new SurfacePackage(new SurfaceControl(mSurfaceControl, "getSurfacePackage"),
                mAccessibilityEmbeddedConnection, getInputTransferToken(), mRemoteInterface);
        } else {
            return null;
        }
    }

    /**
     * @hide
     */
    public @NonNull AttachedSurfaceControl getRootSurfaceControl() {
        return mViewRoot;
    }

    /**
     * Set the root view of the SurfaceControlViewHost. This view will render in to
     * the SurfaceControl, and receive input based on the SurfaceControls positioning on
     * screen. It will be laid as if it were in a window of the passed in width and height.
     *
     * @param view The View to add
     * @param width The width to layout the View within, in pixels.
     * @param height The height to layout the View within, in pixels.
     */
    public void setView(@NonNull View view, int width, int height) {
        final WindowManager.LayoutParams lp =
                new WindowManager.LayoutParams(width, height,
                        WindowManager.LayoutParams.TYPE_APPLICATION, 0, PixelFormat.TRANSPARENT);
        setView(view, lp);
    }

    /**
     * @hide
     */
    @TestApi
    public void setView(@NonNull View view, @NonNull WindowManager.LayoutParams attrs) {
        Objects.requireNonNull(view);
        attrs.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        addWindowToken(attrs);
        view.setLayoutParams(attrs);
        mViewRoot.setView(view, attrs, null);
        mViewRoot.setBackKeyCallbackForWindowlessWindow(mWm::forwardBackKeyToParent);
    }

    /**
     * @return The view passed to setView, or null if none has been passed.
     */
    public @Nullable View getView() {
        return mViewRoot.getView();
    }

    /**
     * @return the ViewRootImpl wrapped by this host.
     * @hide
     */
    public IWindow getWindowToken() {
        return mViewRoot.mWindow;
    }

    /**
     * @return the WindowlessWindowManager instance that this host is attached to.
     * @hide
     */
    public @NonNull WindowlessWindowManager getWindowlessWM() {
        return mWm;
    }

    /**
     * Forces relayout and draw and allows to set a custom callback when it is finished
     * @hide
     */
    public void relayout(WindowManager.LayoutParams attrs,
            WindowlessWindowManager.ResizeCompleteCallback callback) {
        mViewRoot.setLayoutParams(attrs, false);
        mViewRoot.setReportNextDraw(true /* syncBuffer */, "scvh_relayout");
        mWm.setCompletionCallback(mViewRoot.mWindow.asBinder(), callback);
    }

    /**
     * @hide
     */
    @TestApi
    public void relayout(WindowManager.LayoutParams attrs) {
        mViewRoot.setLayoutParams(attrs, false);
    }

    /**
     * Modify the size of the root view.
     *
     * @param width Width in pixels
     * @param height Height in pixels
     */
    public void relayout(int width, int height) {
        final WindowManager.LayoutParams lp =
                new WindowManager.LayoutParams(width, height,
                        WindowManager.LayoutParams.TYPE_APPLICATION, 0, PixelFormat.TRANSPARENT);
        relayout(lp);
    }

    /**
     * Trigger the tear down of the embedded view hierarchy and release the SurfaceControl.
     * This will result in onDispatchedFromWindow being dispatched to the embedded view hierarchy
     * and render the object unusable.
     */
    public void release() {
        // ViewRoot will release mSurfaceControl for us.
        doRelease(true /* immediate */);
    }

    private void doRelease(boolean immediate) {
        if (mConfigChangedCallback != null) {
            ViewRootImpl.removeConfigCallback(mConfigChangedCallback);
            mConfigChangedCallback = null;
        }

        mViewRoot.die(immediate);
        WindowManagerGlobal.getInstance().removeWindowlessRoot(mViewRoot);
        mReleased = true;
        mCloseGuard.close();
    }

    /**
     * Returns an input token used which can be used to request focus on the embedded surface
     * or to transfer touch gesture to the embedded surface.
     *
     * @hide
     */
    public IBinder getInputTransferToken() {
        return mWm.getInputTransferToken(getWindowToken().asBinder());
    }

    private void addWindowToken(WindowManager.LayoutParams attrs) {
        final WindowManagerImpl wm =
                (WindowManagerImpl) mViewRoot.mContext.getSystemService(Context.WINDOW_SERVICE);
        attrs.token = wm.getDefaultToken();
    }

    /**
     * Transfer the currently in progress touch gesture to the parent
     * (if any) of this SurfaceControlViewHost. This requires that the
     * SurfaceControlViewHost was created with an associated hostInputToken.
     *
     * @return Whether the touch stream was transferred.
     */
    public boolean transferTouchGestureToHost() {
        if (mViewRoot == null) {
            return false;
        }

        final IWindowSession realWm = WindowManagerGlobal.getWindowSession();
        try {
            return realWm.transferEmbeddedTouchFocusToHost(mViewRoot.mWindow);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
        return false;
    }
}
