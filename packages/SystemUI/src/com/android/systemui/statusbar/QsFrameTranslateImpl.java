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

package com.android.systemui.statusbar;

import android.view.View;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.plugins.qs.QS;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.phone.CentralSurfaces;

import javax.inject.Inject;

/**
 * Default implementation of QS Translation. This by default does not do much.
 */
@SysUISingleton
public class QsFrameTranslateImpl extends QsFrameTranslateController {

    @Inject
    public QsFrameTranslateImpl(CentralSurfaces centralSurfaces) {
        super(centralSurfaces);
    }

    @Override
    public void translateQsFrame(View qsFrame, QS qs, float overExpansion,
            float qsTranslationForFullShadeTransition) {
    }

    @Override
    public float getNotificationsTopPadding(float expansionHeight,
            NotificationStackScrollLayoutController controller) {

        return expansionHeight;
    }
}
