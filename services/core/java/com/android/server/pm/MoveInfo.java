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

package com.android.server.pm;

final class MoveInfo {
    final int mMoveId;
    final String mFromUuid;
    final String mToUuid;
    final String mPackageName;
    final int mAppId;
    final String mSeInfo;
    final int mTargetSdkVersion;
    final String mFromCodePath;

    MoveInfo(int moveId, String fromUuid, String toUuid, String packageName,
            int appId, String seInfo, int targetSdkVersion,
            String fromCodePath) {
        mMoveId = moveId;
        mFromUuid = fromUuid;
        mToUuid = toUuid;
        mPackageName = packageName;
        mAppId = appId;
        mSeInfo = seInfo;
        mTargetSdkVersion = targetSdkVersion;
        mFromCodePath = fromCodePath;
    }
}
