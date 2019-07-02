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
 * limitations under the License
 */

package com.android.server.wm;

import static android.view.SurfaceControl.METADATA_OWNER_UID;
import static android.view.SurfaceControl.METADATA_WINDOW_TYPE;

import static com.android.server.wm.AppWindowThumbnailProto.HEIGHT;
import static com.android.server.wm.AppWindowThumbnailProto.SURFACE_ANIMATOR;
import static com.android.server.wm.AppWindowThumbnailProto.WIDTH;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.MAX_ANIMATION_DURATION;

import android.graphics.GraphicBuffer;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Binder;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Builder;
import android.view.SurfaceControl.Transaction;
import android.view.animation.Animation;

import com.android.server.wm.SurfaceAnimator.Animatable;

/**
 * Represents a surface that is displayed over an {@link AppWindowToken}
 */
class AppWindowThumbnail implements Animatable {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "AppWindowThumbnail" : TAG_WM;

    private final AppWindowToken mAppToken;
    private SurfaceControl mSurfaceControl;
    private final SurfaceAnimator mSurfaceAnimator;
    private final int mWidth;
    private final int mHeight;
    private final boolean mRelative;

    AppWindowThumbnail(Transaction t, AppWindowToken appToken, GraphicBuffer thumbnailHeader) {
        this(t, appToken, thumbnailHeader, false /* relative */);
    }

    /**
     * @param t Transaction to create the thumbnail in.
     * @param appToken {@link AppWindowToken} to associate this thumbnail with.
     * @param thumbnailHeader A thumbnail or placeholder for thumbnail to initialize with.
     * @param relative Whether this thumbnail will be a child of appToken (and thus positioned
     *                 relative to it) or not.
     */
    AppWindowThumbnail(Transaction t, AppWindowToken appToken, GraphicBuffer thumbnailHeader,
            boolean relative) {
        this(t, appToken, thumbnailHeader, relative, new Surface(), null);
    }

    AppWindowThumbnail(Transaction t, AppWindowToken appToken, GraphicBuffer thumbnailHeader,
            boolean relative, Surface drawSurface, SurfaceAnimator animator) {
        mAppToken = appToken;
        mRelative = relative;
        if (animator != null) {
            mSurfaceAnimator = animator;
        } else {
            // We can't use a delegating constructor since we need to
            // reference this::onAnimationFinished
            mSurfaceAnimator =
                new SurfaceAnimator(this, this::onAnimationFinished, appToken.mWmService);
        }
        mWidth = thumbnailHeader.getWidth();
        mHeight = thumbnailHeader.getHeight();

        // Create a new surface for the thumbnail
        WindowState window = appToken.findMainWindow();

        // TODO: This should be attached as a child to the app token, once the thumbnail animations
        // use relative coordinates. Once we start animating task we can also consider attaching
        // this to the task.
        mSurfaceControl = appToken.makeSurface()
                .setName("thumbnail anim: " + appToken.toString())
                .setBufferSize(mWidth, mHeight)
                .setFormat(PixelFormat.TRANSLUCENT)
                .setMetadata(METADATA_WINDOW_TYPE, appToken.windowType)
                .setMetadata(METADATA_OWNER_UID,
                        window != null ? window.mOwnerUid : Binder.getCallingUid())
                .build();

        if (SHOW_TRANSACTIONS) {
            Slog.i(TAG, "  THUMBNAIL " + mSurfaceControl + ": CREATE");
        }

        // Transfer the thumbnail to the surface
        drawSurface.copyFrom(mSurfaceControl);
        drawSurface.attachAndQueueBuffer(thumbnailHeader);
        drawSurface.release();
        t.show(mSurfaceControl);

        // We parent the thumbnail to the task, and just place it on top of anything else in the
        // task.
        t.setLayer(mSurfaceControl, Integer.MAX_VALUE);
        if (relative) {
            t.reparent(mSurfaceControl, appToken.getSurfaceControl());
        }
    }

    void startAnimation(Transaction t, Animation anim) {
        startAnimation(t, anim, null /* position */);
    }

    void startAnimation(Transaction t, Animation anim, Point position) {
        anim.restrictDuration(MAX_ANIMATION_DURATION);
        anim.scaleCurrentDuration(mAppToken.mWmService.getTransitionAnimationScaleLocked());
        mSurfaceAnimator.startAnimation(t, new LocalAnimationAdapter(
                new WindowAnimationSpec(anim, position,
                        mAppToken.getDisplayContent().mAppTransition.canSkipFirstFrame(),
                        mAppToken.getDisplayContent().getWindowCornerRadius()),
                mAppToken.mWmService.mSurfaceAnimationRunner), false /* hidden */);
    }

    /**
     * Start animation with existing adapter.
     */
    void startAnimation(Transaction t, AnimationAdapter anim, boolean hidden) {
        mSurfaceAnimator.startAnimation(t, anim, hidden);
    }

    private void onAnimationFinished() {
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
     * com.android.server.wm.AppWindowThumbnailProto}.
     *
     * @param proto Stream to write the AppWindowThumbnail object to.
     * @param fieldId Field Id of the AppWindowThumbnail as defined in the parent message.
     * @hide
     */
    void writeToProto(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(WIDTH, mWidth);
        proto.write(HEIGHT, mHeight);
        if (mSurfaceAnimator.isAnimating()) {
            mSurfaceAnimator.writeToProto(proto, SURFACE_ANIMATOR);
        }
        proto.end(token);
    }

    @Override
    public Transaction getPendingTransaction() {
        return mAppToken.getPendingTransaction();
    }

    @Override
    public void commitPendingTransaction() {
        mAppToken.commitPendingTransaction();
    }

    @Override
    public void onAnimationLeashCreated(Transaction t, SurfaceControl leash) {
        t.setLayer(leash, Integer.MAX_VALUE);
        if (mRelative) {
            t.reparent(leash, mAppToken.getSurfaceControl());
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
        return mAppToken.makeSurface();
    }

    @Override
    public SurfaceControl getSurfaceControl() {
        return mSurfaceControl;
    }

    @Override
    public SurfaceControl getAnimationLeashParent() {
        return mAppToken.getAppAnimationLayer();
    }

    @Override
    public SurfaceControl getParentSurfaceControl() {
        return mAppToken.getParentSurfaceControl();
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
