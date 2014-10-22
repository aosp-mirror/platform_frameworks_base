/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server.wm;

import android.content.res.CompatibilityInfo;

final class StartingData {
    final String pkg;
    final int theme;
    final CompatibilityInfo compatInfo;
    final CharSequence nonLocalizedLabel;
    final int labelRes;
    final int icon;
    final int logo;
    final int windowFlags;

    StartingData(String _pkg, int _theme, CompatibilityInfo _compatInfo,
            CharSequence _nonLocalizedLabel,
            int _labelRes, int _icon, int _logo, int _windowFlags) {
        pkg = _pkg;
        theme = _theme;
        compatInfo = _compatInfo;
        nonLocalizedLabel = _nonLocalizedLabel;
        labelRes = _labelRes;
        icon = _icon;
        logo = _logo;
        windowFlags = _windowFlags;
    }
}