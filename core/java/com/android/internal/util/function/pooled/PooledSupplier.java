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

package com.android.internal.util.function.pooled;

import com.android.internal.util.FunctionalUtils.ThrowingSupplier;

import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * {@link Supplier} + {@link PooledLambda}
 *
 * @see PooledLambda
 * @hide
 */
public interface PooledSupplier<T> extends PooledLambda, Supplier<T>, ThrowingSupplier<T> {

    /**
     * Ignores the result
     */
    PooledRunnable asRunnable();

    /** @inheritDoc */
    PooledSupplier<T> recycleOnUse();

    /** {@link PooledLambda} + {@link IntSupplier} */
    interface OfInt extends IntSupplier, PooledLambda {
        /** @inheritDoc */
        PooledSupplier.OfInt recycleOnUse();
    }

    /** {@link PooledLambda} + {@link LongSupplier} */
    interface OfLong extends LongSupplier, PooledLambda {
        /** @inheritDoc */
        PooledSupplier.OfLong recycleOnUse();
    }

    /** {@link PooledLambda} + {@link DoubleSupplier} */
    interface OfDouble extends DoubleSupplier, PooledLambda {
        /** @inheritDoc */
        PooledSupplier.OfDouble recycleOnUse();
    }
}
