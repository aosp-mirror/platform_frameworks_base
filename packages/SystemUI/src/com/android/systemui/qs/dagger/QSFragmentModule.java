/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.qs.dagger;

import static com.android.systemui.util.Utils.useCollapsedMediaInLandscape;
import static com.android.systemui.util.Utils.useQsMediaPlayer;

import android.content.Context;

import dagger.Module;
import dagger.Provides;

import javax.inject.Named;

/**
 * Dagger Module for {@link QSFragmentComponent}.
 */
@Module(includes = {QSScopeModule.class})
public  interface QSFragmentModule {
    /** */
    @Provides
    @Named(QSScopeModule.QS_USING_MEDIA_PLAYER)
    static boolean providesQSUsingMediaPlayer(Context context) {
        return useQsMediaPlayer(context);
    }



    /** */
    @Provides
    @Named(QSScopeModule.QS_USING_COLLAPSED_LANDSCAPE_MEDIA)
    static boolean providesQSUsingCollapsedLandscapeMedia(Context context) {
        return useCollapsedMediaInLandscape(context.getResources());
    }
}