#
# Copyright (C) 2024 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Create a directory for the results and a nested temporary directory.
mkdir -p $ANDROID_BUILD_TOP/out/soong/exempt_aidl_interfaces_generator_output/tmp

# Create a copy of `AndroidGlobalLintChecker.jar` to restore it afterwards.
cp $ANDROID_BUILD_TOP/prebuilts/cmdline-tools/AndroidGlobalLintChecker.jar \
    $ANDROID_BUILD_TOP/out/soong/exempt_aidl_interfaces_generator_output/AndroidGlobalLintChecker.jar

# Configure the environment variable required for running the lint check on the entire source tree.
export ANDROID_LINT_CHECK=PermissionAnnotationExemptAidlInterfaces

# Build the target corresponding to the lint checks present in the `utils` directory.
m AndroidUtilsLintChecker

# Replace `AndroidGlobalLintChecker.jar` with the newly built `jar` file.
cp $ANDROID_BUILD_TOP/out/host/linux-x86/framework/AndroidUtilsLintChecker.jar \
    $ANDROID_BUILD_TOP/prebuilts/cmdline-tools/AndroidGlobalLintChecker.jar;

# Run the lint check on the entire source tree.
m lint-check

# Copy the archive containing the results of `lint-check` into the temporary directory.
cp $ANDROID_BUILD_TOP/out/soong/lint-report-text.zip \
    $ANDROID_BUILD_TOP/out/soong/exempt_aidl_interfaces_generator_output/tmp

cd $ANDROID_BUILD_TOP/out/soong/exempt_aidl_interfaces_generator_output/tmp

# Unzip the archive containing the results of `lint-check`.
unzip lint-report-text.zip

# Concatenate the results of `lint-check` into a single string.
concatenated_reports=$(find . -type f | xargs cat)

# Extract the fully qualified names of the AIDL Interfaces from the concatenated results. Output
# this list into `out/soong/exempt_aidl_interfaces_generator_output/exempt_aidl_interfaces`.
echo $concatenated_reports | grep -Eo '\"([a-zA-Z0-9_]*\.)+[a-zA-Z0-9_]*\",' | sort | uniq > ../exempt_aidl_interfaces

# Remove the temporary directory.
rm -rf $ANDROID_BUILD_TOP/out/soong/exempt_aidl_interfaces_generator_output/tmp

# Restore the original copy of `AndroidGlobalLintChecker.jar` and delete the copy.
cp $ANDROID_BUILD_TOP/out/soong/exempt_aidl_interfaces_generator_output/AndroidGlobalLintChecker.jar \
    $ANDROID_BUILD_TOP/prebuilts/cmdline-tools/AndroidGlobalLintChecker.jar
rm $ANDROID_BUILD_TOP/out/soong/exempt_aidl_interfaces_generator_output/AndroidGlobalLintChecker.jar
