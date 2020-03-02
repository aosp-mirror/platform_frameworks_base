/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.wm;

import static android.view.SurfaceControl.METADATA_OWNER_UID;
import static android.view.SurfaceControl.METADATA_WINDOW_TYPE;

import static com.android.server.wm.ProtoLogGroup.WM_SHOW_TRANSACTIONS;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_RECENTS;
import static com.android.server.wm.WindowContainerThumbnailProto.HEIGHT;
import static com.android.server.wm.WindowContainerThumbnailProto.SURFACE_ANIMATOR;
import static com.android.server.wm.WindowContainerThumbnailProto.WIDTH;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.MAX_ANIMATION_DURATION;

import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.HardwareBuffer;
import android.os.Process;
import android.util.proto.ProtoOutputStream;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Builder;
import android.view.SurfaceControl.Transaction;
import android.view.animation.Animation;

import com.android.server.protolog.common.ProtoLog;
import com.android.server.wm.SurfaceAnimator.Animatable;
import com.android.server.wm.SurfaceAnimator.AnimationType;

import java.util.function.Supplier;

/**
 * Represents a surface that is displayed over a subclass of {@link WindowContainer}
 */
class WindowContainerThumbnail implements Animatable {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "WindowContainerThumbnail" : TAG_WM;

    private final WindowContainer mWindowContainer;
    private SurfaceControl mSurfaceControl;
    private final SurfaceAnimator mSurfaceAnimator;
    private final int mWidth;
    private final int mHeight;
    private final boolean mRelative;

    WindowContainerThumbnail(Supplier<Surface> surfaceFactory, Transaction t,
            WindowContainer container, HardwareBuffer thumbnailHeader) {
        this(surfaceFactory, t, container, thumbnailHeader, false /* relative */);
    }

    /**
     * @param t Transaction to create the thumbnail in.
     * @param container The sub-class of {@link WindowContainer} to associate this thumbnail with.
     * @param thumbnailHeader A thumbnail or placeholder for thumbnail to initialize with.
     * @param relative Whether this thumbnail will be a child of the container (and thus positioned
     *                 relative to it) or not.
     */
    WindowContainerThumbnail(Supplier<Surface> surfaceFactory, Transaction t,
            WindowContainer container, HardwareBuffer thumbnailHeader, boolean relative) {
        this(t, container, thumbnailHeader, relative, surfaceFactory.get(), null);
    }

    WindowContainerThumbnail(Transaction t, WindowContainer container,
            HardwareBuffer thumbnailHeader, boolean relative, Surface drawSurface,
            SurfaceAnimator animator) {
        mWindowContainer = container;
        mRelative = relative;
        if (animator != null) {
            mSurfaceAnimator = animator;
        } else {
            // We can't use a delegating constructor since we need to
            // reference this::onAnimationFinished
            mSurfaceAnimator =
                new SurfaceAnimator(this, this::onAnimationFinished /* animationFinishedCallback */,
                        container.mWmService);
        }
        mWidth = thumbnailHeader.getWidth();
        mHeight = thumbnailHeader.getHeight();

        // Create a new surface for the thumbnail
        // TODO: This should be attached as a child to the app token, once the thumbnail animations
        // use relative coordinates. Once we start animating task we can also consider attaching
        // this to the task.
        mSurfaceControl = mWindowContainer.makeSurface()
                .setName("thumbnail anim: " + mWindowContainer.toString())
                .setBufferSize(mWidth, mHeight)
                .setFormat(PixelFormat.TRANSLUCENT)
                .setMetadata(METADATA_WINDOW_TYPE, mWindowContainer.getWindowingMode())
                .setMetadata(METADATA_OWNER_UID, Process.myUid())
                .build();

        ProtoLog.i(WM_SHOW_TRANSACTIONS, "  THUMBNAIL %s: CREATE", mSurfaceControl);

        // Transfer the thumbnail to the surface
        drawSurface.copyFrom(mSurfaceControl);
        drawSurface.attachAndQueueBufferWithColorSpace(thumbnailHeader, null);
        drawSurface.release();
        t.show(mSurfaceControl);

        // We parent the thumbnail to the container, and just place it on top of anything else in
        // the container.
        t.setLayer(mSurfaceControl, Integer.MAX_VALUE);
        if (relative) {
            t.reparent(mSurfaceControl, mWindowContainer.getSurfaceControl());
        }
    }

    void startAnimation(Transaction t, Animation anim) {
        startAnimation(t, anim, null /* position */);
    }

    void startAnimation(Transaction t, Animation anim, Point position) {
        anim.restrictDuration(MAX_ANIMATION_DURATION);
        anim.scaleCurrentDuration(mWindowContainer.mWmService.getTransitionAnimationScaleLocked());
        mSurfaceAnimator.startAnimation(t, new LocalAnimationAdapter(
                new WindowAnimationSpec(anim, position,
                        mWindowContainer.getDisplayContent().mAppTransition.canSkipFirstFrame(),
                        mWindowContainer.getDisplayContent().getWindowCornerRadius()),
                mWindowContainer.mWmService.mSurfaceAnimationRunner), false /* hidden */,
                ANIMATION_TYPE_RECENTS, null /* animationFinishedCallback */);
    }

    /**
     * Start animation with existing adapter.
     */
    void startAnimation(Transaction t, AnimationAdapter anim, boolean hidden) {
        mSurfaceAnimator.startAnimation(t, anim, hidden, ANIMATION_TYPE_RECENTS,
                null /* animationFinishedCallback */);
    }

    private void onAnimationFinished(@AnimationType int type, AnimationAdapter anim) {
    }

    void setShowing(Transaction pendingTransaction, boolean show) {
        // TODO: Not needed anymore once thumbnail is attached to the app.
        if (show) {
            pendingTransaction.show(mSurfaceControl);
        } else {
            pendingTransaction.hide(mSurfaceControl);
        }
    }

    void destroy() {
        mSurfaceAnimator.cancelAnimation();
        getPendingTransaction().remove(mSurfaceControl);
        mSurfaceControl = null;
    }

    /**
     * Write to a protocol buffer output stream. Protocol buffer message definition is at {@link
     * com.android.server.wm.WindowContainerThumbnailProto}.
     *
     * @param proto Stream to write the WindowContainerThumbnailProto object to.
     * @param fieldId Field Id of the WindowContainerThumbnailProto as defined in the parent
     *                message.
     * @hide
     */
    void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(WIDTH, mWidth);
        proto.write(HEIGHT, mHeight);
        if (mSurfaceAnimator.isAnimating()) {
            mSurfaceAnimator.dumpDebug(proto, SURFACE_ANIMATOR);
        }
        proto.end(token);
    }

    @Override
    public Transaction getPendingTransaction() {
        return mWindowContainer.getPendingTransaction();
    }

    @Override
    public void commitPendingTransaction() {
        mWindowContainer.commitPendingTransaction();
    }

    @Override
    public void onAnimationLeashCreated(Transaction t, SurfaceControl leash) {
        t.setLayer(leash, Integer.MAX_VALUE);
        if (mRelative) {
            t.reparent(leash, mWindowContainer.getSurfaceControl());
        }
    }

    @Override
    public void onAnimationLeashLost(Transaction t) {

        // TODO: Once attached to app token, we don't need to hide it immediately if thumbnail
        // became visible.
        t.hide(mSurfaceControl);
    }

    @Override
    public Builder makeAnimationLeash() {
        return mWindowContainer.makeSurface();
    }

    @Override
    public SurfaceControl getSurfaceControl() {
        return mSurfaceControl;
    }

    @Override
    public SurfaceControl getAnimationLeashParent() {
        return mWindowContainer.getAnimationLeashParent();
    }

    @Override
    public SurfaceControl getParentSurfaceControl() {
        return mWindowContainer.getParentSurfaceControl();
    }

    @Override
    public int getSurfaceWidth() {
        return mWidth;
    }

    @Override
    public int getSurfaceHeight() {
        return mHeight;
    }
}
