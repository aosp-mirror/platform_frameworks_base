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
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.accessibility.IAccessibilityEmbeddedConnection;

import java.util.Objects;

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
    private ViewRootImpl mViewRoot;
    private WindowlessWindowManager mWm;

    private SurfaceControl mSurfaceControl;
    private IAccessibilityEmbeddedConnection mAccessibilityEmbeddedConnection;

    /**
     * Package encapsulating a Surface hierarchy which contains interactive view
     * elements. It's expected to get this object from
     * {@link SurfaceControlViewHost#getSurfacePackage} afterwards it can be embedded within
     * a SurfaceView by calling {@link SurfaceView#setChildSurfacePackage}.
     */
    public static final class SurfacePackage implements Parcelable {
        private SurfaceControl mSurfaceControl;
        private final IAccessibilityEmbeddedConnection mAccessibilityEmbeddedConnection;

        SurfacePackage(SurfaceControl sc, IAccessibilityEmbeddedConnection connection) {
            mSurfaceControl = sc;
            mAccessibilityEmbeddedConnection = connection;
        }

        private SurfacePackage(Parcel in) {
            mSurfaceControl = new SurfaceControl();
            mSurfaceControl.readFromParcel(in);
            mAccessibilityEmbeddedConnection = IAccessibilityEmbeddedConnection.Stub.asInterface(
                    in.readStrongBinder());
        }

        /**
         * Use {@link SurfaceView#setChildSurfacePackage} or manually fix
         * accessibility (see SurfaceView implementation).
         * @hide
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

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel out, int flags) {
            mSurfaceControl.writeToParcel(out, flags);
            out.writeStrongBinder(mAccessibilityEmbeddedConnection.asBinder());
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
            @NonNull WindowlessWindowManager wwm) {
        this(c, d, wwm, false /* useSfChoreographer */);
    }

    /** @hide */
    public SurfaceControlViewHost(@NonNull Context c, @NonNull Display d,
            @NonNull WindowlessWindowManager wwm, boolean useSfChoreographer) {
        mWm = wwm;
        mViewRoot = new ViewRootImpl(c, d, mWm, useSfChoreographer);
        mViewRoot.forceDisableBLAST();
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
        mSurfaceControl = new SurfaceControl.Builder()
            .setContainerLayer()
            .setName("SurfaceControlViewHost")
            .build();
        mWm = new WindowlessWindowManager(context.getResources().getConfiguration(),
                mSurfaceControl, hostToken);
        mViewRoot = new ViewRootImpl(context, display, mWm);
        mViewRoot.forceDisableBLAST();
        mAccessibilityEmbeddedConnection = mViewRoot.getAccessibilityEmbeddedConnection();
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
            return new SurfacePackage(mSurfaceControl, mAccessibilityEmbeddedConnection);
        } else {
            return null;
        }
    }

    /**
     * @hide
     */
    @TestApi
    public void setView(@NonNull View view, @NonNull WindowManager.LayoutParams attrs) {
        Objects.requireNonNull(view);
        mViewRoot.setView(view, attrs, null);
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
        lp.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        setView(view, lp);
    }

    /**
     * @return The view passed to setView, or null if none has been passed.
     */
    public @Nullable View getView() {
        return mViewRoot.getView();
    }

    /**
     * @hide
     */
    @TestApi
    public void relayout(WindowManager.LayoutParams attrs) {
        mViewRoot.setLayoutParams(attrs, false);
        mViewRoot.setReportNextDraw();
        mWm.setCompletionCallback(mViewRoot.mWindow.asBinder(), (SurfaceControl.Transaction t) -> {
            t.apply();
        });
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
        mViewRoot.die(false /* immediate */);
        mSurfaceControl.release();
    }

    /**
     * Tell this viewroot to clean itself up.
     * @hide
     */
    public void die() {
        mViewRoot.die(false /* immediate */);
    }
}
