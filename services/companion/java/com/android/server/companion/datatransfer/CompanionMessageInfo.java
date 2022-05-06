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

package com.android.server.companion.datatransfer;

class CompanionMessageInfo {

    private final long mId;
    private final int mPage;
    private final int mTotal;
    private final int mType;
    private final byte[] mData;

    CompanionMessageInfo(long id, int page, int total, int type, byte[] data) {
        mId = id;
        mPage = page;
        mTotal = total;
        mType = type;
        mData = data;
    }

    public long getId() {
        return mId;
    }

    public int getPage() {
        return mPage;
    }

    public int getType() {
        return mType;
    }

    public byte[] getData() {
        return mData;
    }
}
