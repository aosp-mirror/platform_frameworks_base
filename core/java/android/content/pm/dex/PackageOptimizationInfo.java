/**
 * Copyright 2018 The Android Open Source Project
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

package android.content.pm.dex;

/**
 * Encapsulates information about the optimizations performed on a package.
 *
 * @hide
 */
public class PackageOptimizationInfo {
    private final int mCompilationFilter;
    private final int mCompilationReason;

    public PackageOptimizationInfo(int compilerFilter, int compilationReason) {
        this.mCompilationReason = compilationReason;
        this.mCompilationFilter = compilerFilter;
    }

    public int getCompilationReason() {
        return mCompilationReason;
    }

    public int getCompilationFilter() {
        return mCompilationFilter;
    }

    /**
     * Create a default optimization info object for the case when we have no information.
     */
    public static PackageOptimizationInfo createWithNoInfo() {
        return new PackageOptimizationInfo(-1, -1);
    }
}
