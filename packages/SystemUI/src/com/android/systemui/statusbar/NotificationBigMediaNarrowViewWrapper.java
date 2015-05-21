/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar;

import android.content.Context;
import android.view.View;

/**
 * Wraps a big media narrow notification template layout.
 */
public class NotificationBigMediaNarrowViewWrapper extends NotificationMediaViewWrapper {

    protected NotificationBigMediaNarrowViewWrapper(Context ctx,
            View view) {
        super(ctx, view);
    }

    @Override
    public boolean needsRoundRectClipping() {
        return true;
    }
}
