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

package com.android.server.wm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.view.SurfaceControl;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * An implementation of {@link SurfaceAnimator.Animatable} that is instantiated
 * using a builder pattern for more convenience over reimplementing the whole interface.
 * <p>
 * Use {@link SimpleSurfaceAnimatable.Builder} to create a new instance of this class.
 *
 * @see com.android.server.wm.SurfaceAnimator.Animatable
 */
public class SimpleSurfaceAnimatable implements SurfaceAnimator.Animatable {
    private final int mWidth;
    private final int mHeight;
    private final boolean mShouldDeferAnimationFinish;
    private final SurfaceControl mAnimationLeashParent;
    private final SurfaceControl mSurfaceControl;
    private final SurfaceControl mParentSurfaceControl;
    private final Runnable mCommitTransactionRunnable;
    private final Supplier<SurfaceControl.Builder> mAnimationLeashFactory;
    private final Supplier<SurfaceControl.Transaction> mSyncTransaction;
    private final Supplier<SurfaceControl.Transaction> mPendingTransaction;
    private final BiConsumer<SurfaceControl.Transaction, SurfaceControl> mOnAnimationLeashCreated;
    private final Consumer<SurfaceControl.Transaction> mOnAnimationLeashLost;
    private final Consumer<Runnable> mOnAnimationFinished;

    /**
     * Use {@link SimpleSurfaceAnimatable.Builder} to create a new instance.
     */
    private SimpleSurfaceAnimatable(Builder builder) {
        mWidth = builder.mWidth;
        mHeight = builder.mHeight;
        mShouldDeferAnimationFinish = builder.mShouldDeferAnimationFinish;
        mAnimationLeashParent = builder.mAnimationLeashParent;
        mSurfaceControl = builder.mSurfaceControl;
        mParentSurfaceControl = builder.mParentSurfaceControl;
        mCommitTransactionRunnable = builder.mCommitTransactionRunnable;
        mAnimationLeashFactory = builder.mAnimationLeashFactory;
        mOnAnimationLeashCreated = builder.mOnAnimationLeashCreated;
        mOnAnimationLeashLost = builder.mOnAnimationLeashLost;
        mSyncTransaction = builder.mSyncTransactionSupplier;
        mPendingTransaction = builder.mPendingTransactionSupplier;
        mOnAnimationFinished = builder.mOnAnimationFinished;
    }

    @Override
    public SurfaceControl.Transaction getSyncTransaction() {
        return mSyncTransaction.get();
    }

    @NonNull
    @Override
    public SurfaceControl.Transaction getPendingTransaction() {
        return mPendingTransaction.get();
    }

    @Override
    public void commitPendingTransaction() {
        mCommitTransactionRunnable.run();
    }

    @Override
    public void onAnimationLeashCreated(SurfaceControl.Transaction t, SurfaceControl leash) {
        if (mOnAnimationLeashCreated != null) {
            mOnAnimationLeashCreated.accept(t, leash);
        }

    }

    @Override
    public void onAnimationLeashLost(SurfaceControl.Transaction t) {
        if (mOnAnimationLeashLost != null) {
            mOnAnimationLeashLost.accept(t);
        }
    }

    @Override
    @NonNull
    public SurfaceControl.Builder makeAnimationLeash() {
        return mAnimationLeashFactory.get();
    }

    @Override
    public SurfaceControl getAnimationLeashParent() {
        return mAnimationLeashParent;
    }

    @Override
    @Nullable
    public SurfaceControl getSurfaceControl() {
        return mSurfaceControl;
    }

    @Override
    public SurfaceControl getParentSurfaceControl() {
        return mParentSurfaceControl;
    }

    @Override
    public int getSurfaceWidth() {
        return mWidth;
    }

    @Override
    public int getSurfaceHeight() {
        return mHeight;
    }

    @Override
    public boolean shouldDeferAnimationFinish(Runnable endDeferFinishCallback) {
        if (mOnAnimationFinished != null) {
            mOnAnimationFinished.accept(endDeferFinishCallback);
        }
        return mShouldDeferAnimationFinish;
    }

    /**
     * Builder class to create a {@link SurfaceAnimator.Animatable} without having to
     * create a new class that implements the interface.
     */
    static class Builder {
        private int mWidth = -1;
        private int mHeight = -1;
        private boolean mShouldDeferAnimationFinish = false;

        @Nullable
        private SurfaceControl mAnimationLeashParent = null;

        @Nullable
        private SurfaceControl mSurfaceControl = null;

        @Nullable
        private SurfaceControl mParentSurfaceControl = null;
        private Runnable mCommitTransactionRunnable;

        @Nullable
        private BiConsumer<SurfaceControl.Transaction, SurfaceControl> mOnAnimationLeashCreated =
                null;

        @Nullable
        private Consumer<SurfaceControl.Transaction> mOnAnimationLeashLost = null;

        @Nullable
        private Consumer<Runnable> mOnAnimationFinished = null;

        @NonNull
        private Supplier<SurfaceControl.Transaction> mSyncTransactionSupplier;

        @NonNull
        private Supplier<SurfaceControl.Transaction> mPendingTransactionSupplier;

        @NonNull
        private Supplier<SurfaceControl.Builder> mAnimationLeashFactory;

        /**
         * Set the runnable to be called when
         * {@link SurfaceAnimator.Animatable#commitPendingTransaction()}
         * is called.
         *
         * @see SurfaceAnimator.Animatable#commitPendingTransaction()
         */
        public SimpleSurfaceAnimatable.Builder setCommitTransactionRunnable(
                @NonNull Runnable commitTransactionRunnable) {
            mCommitTransactionRunnable = commitTransactionRunnable;
            return this;
        }

        /**
         * Set the callback called when
         * {@link SurfaceAnimator.Animatable#onAnimationLeashCreated(SurfaceControl.Transaction,
         * SurfaceControl)} is called
         *
         * @see SurfaceAnimator.Animatable#onAnimationLeashCreated(SurfaceControl.Transaction,
         * SurfaceControl)
         */
        public SimpleSurfaceAnimatable.Builder setOnAnimationLeashCreated(
                @Nullable BiConsumer<SurfaceControl.Transaction, SurfaceControl>
                        onAnimationLeashCreated) {
            mOnAnimationLeashCreated = onAnimationLeashCreated;
            return this;
        }

        /**
         * Set the callback called when
         * {@link SurfaceAnimator.Animatable#onAnimationLeashLost(SurfaceControl.Transaction)}
         * (SurfaceControl.Transaction, SurfaceControl)} is called
         *
         * @see SurfaceAnimator.Animatable#onAnimationLeashLost(SurfaceControl.Transaction)
         */
        public SimpleSurfaceAnimatable.Builder setOnAnimationLeashLost(
                @Nullable Consumer<SurfaceControl.Transaction> onAnimationLeashLost) {
            mOnAnimationLeashLost = onAnimationLeashLost;
            return this;
        }

        /**
         * @see SurfaceAnimator.Animatable#getSyncTransaction()
         */
        public Builder setSyncTransactionSupplier(
                @NonNull Supplier<SurfaceControl.Transaction> syncTransactionSupplier) {
            mSyncTransactionSupplier = syncTransactionSupplier;
            return this;
        }

        /**
         * @see SurfaceAnimator.Animatable#getPendingTransaction()
         */
        public Builder setPendingTransactionSupplier(
                @NonNull Supplier<SurfaceControl.Transaction> pendingTransactionSupplier) {
            mPendingTransactionSupplier = pendingTransactionSupplier;
            return this;
        }

        /**
         * Set the {@link Supplier} responsible for creating a new animation leash.
         *
         * @see SurfaceAnimator.Animatable#makeAnimationLeash()
         */
        public SimpleSurfaceAnimatable.Builder setAnimationLeashSupplier(
                @NonNull Supplier<SurfaceControl.Builder> animationLeashFactory) {
            mAnimationLeashFactory = animationLeashFactory;
            return this;
        }

        /**
         * @see SurfaceAnimator.Animatable#getAnimationLeashParent()
         */
        public SimpleSurfaceAnimatable.Builder setAnimationLeashParent(
                SurfaceControl animationLeashParent) {
            mAnimationLeashParent = animationLeashParent;
            return this;
        }

        /**
         * @see SurfaceAnimator.Animatable#getSurfaceControl()
         */
        public SimpleSurfaceAnimatable.Builder setSurfaceControl(
                @NonNull SurfaceControl surfaceControl) {
            mSurfaceControl = surfaceControl;
            return this;
        }

        /**
         * @see SurfaceAnimator.Animatable#getParentSurfaceControl()
         */
        public SimpleSurfaceAnimatable.Builder setParentSurfaceControl(
                SurfaceControl parentSurfaceControl) {
            mParentSurfaceControl = parentSurfaceControl;
            return this;
        }

        /**
         * Default to -1.
         *
         * @see SurfaceAnimator.Animatable#getSurfaceWidth()
         */
        public SimpleSurfaceAnimatable.Builder setWidth(int width) {
            mWidth = width;
            return this;
        }

        /**
         * Default to -1.
         *
         * @see SurfaceAnimator.Animatable#getSurfaceHeight()
         */
        public SimpleSurfaceAnimatable.Builder setHeight(int height) {
            mHeight = height;
            return this;
        }

        /**
         * Set the value returned by
         * {@link SurfaceAnimator.Animatable#shouldDeferAnimationFinish(Runnable)}.
         *
         * @param onAnimationFinish will be called with the runnable to execute when the animation
         *                          needs to be finished.
         * @see SurfaceAnimator.Animatable#shouldDeferAnimationFinish(Runnable)
         */
        public SimpleSurfaceAnimatable.Builder setShouldDeferAnimationFinish(
                boolean shouldDeferAnimationFinish,
                @Nullable Consumer<Runnable> onAnimationFinish) {
            mShouldDeferAnimationFinish = shouldDeferAnimationFinish;
            mOnAnimationFinished = onAnimationFinish;
            return this;
        }

        public SurfaceAnimator.Animatable build() {
            if (mSyncTransactionSupplier == null) {
                throw new IllegalArgumentException("mSyncTransactionSupplier cannot be null");
            }
            if (mPendingTransactionSupplier == null) {
                throw new IllegalArgumentException("mPendingTransactionSupplier cannot be null");
            }
            if (mAnimationLeashFactory == null) {
                throw new IllegalArgumentException("mAnimationLeashFactory cannot be null");
            }
            if (mCommitTransactionRunnable == null) {
                throw new IllegalArgumentException("mCommitTransactionRunnable cannot be null");
            }
            if (mSurfaceControl == null) {
                throw new IllegalArgumentException("mSurfaceControl cannot be null");
            }
            return new SimpleSurfaceAnimatable(this);
        }
    }
}
