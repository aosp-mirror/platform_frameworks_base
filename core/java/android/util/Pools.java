/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.util;

/**
 * Helper class for crating pools of objects. An example use looks like this:
 * <pre>
 * public class MyPooledClass {
 *
 *     private static final SynchronizedPool<MyPooledClass> sPool =
 *             new SynchronizedPool<MyPooledClass>(10);
 *
 *     public static MyPooledClass obtain() {
 *         MyPooledClass instance = sPool.acquire();
 *         return (instance != null) ? instance : new MyPooledClass();
 *     }
 *
 *     public void recycle() {
 *          // Clear state if needed.
 *          sPool.release(this);
 *     }
 *
 *     . . .
 * }
 * </pre>
 *
 * @hide
 */
public final class Pools {

    /**
     * Interface for managing a pool of objects.
     *
     * @param <T> The pooled type.
     */
    public static interface Pool<T> {

        /**
         * @return An instance from the pool if such, null otherwise.
         */
        public T acquire();

        /**
         * Release an instance to the pool.
         *
         * @param instance The instance to release.
         * @return Whether the instance was put in the pool.
         *
         * @throws IllegalStateException If the instance is already in the pool.
         */
        public boolean release(T instance);
    }

    private Pools() {
        /* do nothing - hiding constructor */
    }

    private static class PoolableHolder<T> {
        T mPoolable;
        PoolableHolder<T> mNext;
    }

    /**
     * Simple (non-synchronized) pool of objects.
     *
     * @param <T> The pooled type.
     */
    public static class SimplePool<T> implements Pool<T> {
        private final int mMaxPoolSize;

        private int mPoolSize;

        private PoolableHolder<T> mEmptyHolders;
        private PoolableHolder<T> mPool;

        /**
         * Creates a new instance.
         *
         * @param maxPoolSize The max pool size.
         *
         * @throws IllegalArgumentException If the max pool size is less than zero.
         */
        public SimplePool(int maxPoolSize) {
            if (maxPoolSize <= 0) {
                throw new IllegalArgumentException("The max pool size must be > 0");
            }
            mMaxPoolSize = maxPoolSize;
        }

        @Override
        public T acquire() {
            if (mPool != null) {
                PoolableHolder<T> holder = mPool;
                mPool = holder.mNext;
                T poolable = holder.mPoolable;
                holder.mPoolable = null;
                holder.mNext = mEmptyHolders;
                mEmptyHolders = holder;
                mPoolSize--;
                return poolable;
            }
            return null;
        }

        @Override
        public boolean release(T instance) {
            if (isInPool(instance)) {
                throw new IllegalStateException("Already in the pool!");
            }
            if (mPoolSize < mMaxPoolSize) {
                PoolableHolder<T> holder = mEmptyHolders;
                if (holder == null) {
                    holder = new PoolableHolder<T>();
                } else {
                    mEmptyHolders = holder.mNext;
                }
                holder.mPoolable = instance;
                holder.mNext = mPool;
                mPool = holder;
                mPoolSize++;
                return true;
            }
            return false;
        }

        private boolean isInPool(T instance) {
            PoolableHolder<T> current = mPool;
            while (current != null) {
                if (current.mPoolable == instance) {
                    return true;
                }
                current = current.mNext;
            }
            return false;
        }
    }

    /**
     * Synchronized) pool of objects.
     *
     * @param <T> The pooled type.
     */
    public static class SynchronizedPool<T> extends SimplePool<T> {
        private final Object mLock = new Object();

        /**
         * Creates a new instance.
         *
         * @param maxPoolSize The max pool size.
         *
         * @throws IllegalArgumentException If the max pool size is less than zero.
         */
        public SynchronizedPool(int maxPoolSize) {
            super(maxPoolSize);
        }

        @Override
        public T acquire() {
            synchronized (mLock) {
                return super.acquire();
            }
        }

        @Override
        public boolean release(T element) {
            synchronized (mLock) {
                return super.release(element);
            }
        }
    }
}
