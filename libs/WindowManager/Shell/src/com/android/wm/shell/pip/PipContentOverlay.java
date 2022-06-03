/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.wm.shell.pip;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.window.TaskSnapshot;

/**
 * Represents the content overlay used during the entering PiP animation.
 */
public abstract class PipContentOverlay {
    protected SurfaceControl mLeash;

    /** Attaches the internal {@link #mLeash} to the given parent leash. */
    public abstract void attach(SurfaceControl.Transaction tx, SurfaceControl parentLeash);

    /** Detaches the internal {@link #mLeash} from its parent by removing itself. */
    public void detach(SurfaceControl.Transaction tx) {
        if (mLeash != null && mLeash.isValid()) {
            tx.remove(mLeash);
            tx.apply();
        }
    }

    /**
     * Animates the internal {@link #mLeash} by a given fraction.
     * @param atomicTx {@link SurfaceControl.Transaction} to operate, you should not explicitly
     *                 call apply on this transaction, it should be applied on the caller side.
     * @param fraction progress of the animation ranged from 0f to 1f.
     */
    public abstract void onAnimationUpdate(SurfaceControl.Transaction atomicTx, float fraction);

    /**
     * Callback when reaches the end of animation on the internal {@link #mLeash}.
     * @param atomicTx {@link SurfaceControl.Transaction} to operate, you should not explicitly
     *                 call apply on this transaction, it should be applied on the caller side.
     * @param destinationBounds {@link Rect} of the final bounds.
     */
    public abstract void onAnimationEnd(SurfaceControl.Transaction atomicTx,
            Rect destinationBounds);

    /** A {@link PipContentOverlay} uses solid color. */
    public static final class PipColorOverlay extends PipContentOverlay {
        private final Context mContext;

        public PipColorOverlay(Context context) {
            mContext = context;
            mLeash = new SurfaceControl.Builder(new SurfaceSession())
                    .setCallsite("PipAnimation")
                    .setName(PipColorOverlay.class.getSimpleName())
                    .setColorLayer()
                    .build();
        }

        @Override
        public void attach(SurfaceControl.Transaction tx, SurfaceControl parentLeash) {
            tx.show(mLeash);
            tx.setLayer(mLeash, Integer.MAX_VALUE);
            tx.setColor(mLeash, getContentOverlayColor(mContext));
            tx.setAlpha(mLeash, 0f);
            tx.reparent(mLeash, parentLeash);
            tx.apply();
        }

        @Override
        public void onAnimationUpdate(SurfaceControl.Transaction atomicTx, float fraction) {
            atomicTx.setAlpha(mLeash, fraction < 0.5f ? 0 : (fraction - 0.5f) * 2);
        }

        @Override
        public void onAnimationEnd(SurfaceControl.Transaction atomicTx, Rect destinationBounds) {
            // Do nothing. Color overlay should be fully opaque by now.
        }

        private float[] getContentOverlayColor(Context context) {
            final TypedArray ta = context.obtainStyledAttributes(new int[] {
                    android.R.attr.colorBackground });
            try {
                int colorAccent = ta.getColor(0, 0);
                return new float[] {
                        Color.red(colorAccent) / 255f,
                        Color.green(colorAccent) / 255f,
                        Color.blue(colorAccent) / 255f };
            } finally {
                ta.recycle();
            }
        }
    }

    /** A {@link PipContentOverlay} uses {@link TaskSnapshot}. */
    public static final class PipSnapshotOverlay extends PipContentOverlay {
        private final TaskSnapshot mSnapshot;
        private final Rect mSourceRectHint;

        private float mTaskSnapshotScaleX;
        private float mTaskSnapshotScaleY;

        public PipSnapshotOverlay(TaskSnapshot snapshot, Rect sourceRectHint) {
            mSnapshot = snapshot;
            mSourceRectHint = new Rect(sourceRectHint);
            mLeash = new SurfaceControl.Builder(new SurfaceSession())
                    .setCallsite("PipAnimation")
                    .setName(PipSnapshotOverlay.class.getSimpleName())
                    .build();
        }

        @Override
        public void attach(SurfaceControl.Transaction tx, SurfaceControl parentLeash) {
            mTaskSnapshotScaleX = (float) mSnapshot.getTaskSize().x
                    / mSnapshot.getHardwareBuffer().getWidth();
            mTaskSnapshotScaleY = (float) mSnapshot.getTaskSize().y
                    / mSnapshot.getHardwareBuffer().getHeight();
            tx.show(mLeash);
            tx.setLayer(mLeash, Integer.MAX_VALUE);
            tx.setBuffer(mLeash, mSnapshot.getHardwareBuffer());
            // Relocate the content to parentLeash's coordinates.
            tx.setPosition(mLeash, -mSourceRectHint.left, -mSourceRectHint.top);
            tx.setScale(mLeash, mTaskSnapshotScaleX, mTaskSnapshotScaleY);
            tx.reparent(mLeash, parentLeash);
            tx.apply();
        }

        @Override
        public void onAnimationUpdate(SurfaceControl.Transaction atomicTx, float fraction) {
            // Do nothing. Keep the snapshot till animation ends.
        }

        @Override
        public void onAnimationEnd(SurfaceControl.Transaction atomicTx, Rect destinationBounds) {
            // Work around to make sure the snapshot overlay is aligned with PiP window before
            // the atomicTx is committed along with the final WindowContainerTransaction.
            final SurfaceControl.Transaction nonAtomicTx = new SurfaceControl.Transaction();
            final float scaleX = (float) destinationBounds.width()
                    / mSourceRectHint.width();
            final float scaleY = (float) destinationBounds.height()
                    / mSourceRectHint.height();
            final float scale = Math.max(
                    scaleX * mTaskSnapshotScaleX, scaleY * mTaskSnapshotScaleY);
            nonAtomicTx.setScale(mLeash, scale, scale);
            nonAtomicTx.setPosition(mLeash,
                    -scale * mSourceRectHint.left / mTaskSnapshotScaleX,
                    -scale * mSourceRectHint.top / mTaskSnapshotScaleY);
            nonAtomicTx.apply();
            atomicTx.remove(mLeash);
        }
    }
}
