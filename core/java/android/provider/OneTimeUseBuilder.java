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


package android.provider;

/**
 * A builder that facilitates prohibiting its use after an instance was created with it.
 *
 * Suggested usage:
 * call {@link #checkNotUsed} in each setter, and {@link #markUsed} in {@link #build}
 *
 * @param <T> Type of object being built
 * @hide
 */
public abstract class OneTimeUseBuilder<T> {
    private boolean used = false;

    protected void markUsed() {
        checkNotUsed();
        used = true;
    }

    protected void checkNotUsed() {
        if (used) {
            throw new IllegalStateException(
                    "This Builder should not be reused. Use a new Builder instance instead");
        }
    }

    /**
     * Builds the instance
     *
     * Once this method is called, this builder should no longer be used. Any subsequent calls to a
     * setter or {@code build()} will throw an exception
     */
    public abstract T build();
}
