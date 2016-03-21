/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.layoutlib.bridge.intensive;

import com.android.ide.common.rendering.api.RenderSession;
import com.android.ide.common.rendering.api.Result;
import com.android.ide.common.rendering.api.ViewInfo;

import android.annotation.NonNull;
import android.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

class RenderResult {
    private final List<ViewInfo> mRootViews;
    private final List<ViewInfo> mSystemViews;
    private final Result mRenderResult;

    private RenderResult(@Nullable Result result, @Nullable List<ViewInfo> systemViewInfoList,
            @Nullable List<ViewInfo> rootViewInfoList) {
        mSystemViews = systemViewInfoList == null ? Collections.emptyList() : systemViewInfoList;
        mRootViews = rootViewInfoList == null ? Collections.emptyList() : rootViewInfoList;
        mRenderResult = result;
    }

    @NonNull
    static RenderResult getFromSession(@NonNull RenderSession session) {
        return new RenderResult(session.getResult(),
                new ArrayList<>(session.getSystemRootViews()),
                new ArrayList<>(session.getRootViews()));
    }

    @Nullable
    Result getResult() {
        return mRenderResult;
    }

    @NonNull
    public List<ViewInfo> getRootViews() {
        return mRootViews;
    }

    @NonNull
    public List<ViewInfo> getSystemViews() {
        return mSystemViews;
    }
}
