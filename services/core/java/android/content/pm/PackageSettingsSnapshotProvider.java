/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.content.pm;

import android.annotation.NonNull;

import com.android.internal.util.FunctionalUtils;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.PackageSetting;

import java.util.function.Consumer;
import java.util.function.Function;

/** @hide */
public interface PackageSettingsSnapshotProvider {

    /**
     * Run a function block that requires access to {@link PackageSetting} data. This will
     * ensure the {@link PackageManagerService} lock is taken before any caller's internal lock
     * to avoid deadlock. Note that this method may or may not lock. If a snapshot is available
     * and valid, it will iterate the snapshot set of data.
     */
    void withPackageSettingsSnapshot(
            @NonNull Consumer<Function<String, PackageSetting>> block);

    /**
     * Variant which returns a value to the caller.
     * @see #withPackageSettingsSnapshot(Consumer)
     */
    <Output> Output withPackageSettingsSnapshotReturning(
            @NonNull FunctionalUtils.ThrowingFunction<Function<String, PackageSetting>, Output>
                    block);

    /**
     * Variant which throws.
     * @see #withPackageSettingsSnapshot(Consumer)
     */
    <ExceptionType extends Exception> void withPackageSettingsSnapshotThrowing(
            @NonNull FunctionalUtils.ThrowingCheckedConsumer<Function<String, PackageSetting>,
                    ExceptionType> block) throws ExceptionType;

    /**
     * Variant which throws 2 exceptions.
     * @see #withPackageSettingsSnapshot(Consumer)
     */
    <ExceptionOne extends Exception, ExceptionTwo extends Exception> void
            withPackageSettingsSnapshotThrowing2(
                    @NonNull FunctionalUtils.ThrowingChecked2Consumer<
                            Function<String, PackageSetting>, ExceptionOne, ExceptionTwo> block)
            throws ExceptionOne, ExceptionTwo;

    /**
     * Variant which returns a value to the caller and throws.
     * @see #withPackageSettingsSnapshot(Consumer)
     */
    <Output, ExceptionType extends Exception> Output
            withPackageSettingsSnapshotReturningThrowing(
                    @NonNull FunctionalUtils.ThrowingCheckedFunction<
                            Function<String, PackageSetting>, Output, ExceptionType> block)
            throws ExceptionType;
}
