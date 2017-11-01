/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.plugins.qs;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

import com.android.systemui.plugins.annotations.DependsOn;
import com.android.systemui.plugins.annotations.ProvidesInterface;
import com.android.systemui.plugins.qs.QSTile.State;

@ProvidesInterface(version = QSTileView.VERSION)
@DependsOn(target = QSIconView.class)
@DependsOn(target = QSTile.class)
public abstract class QSTileView extends LinearLayout {
    public static final int VERSION = 1;

    public QSTileView(Context context) {
        super(context);
    }

    public abstract View updateAccessibilityOrder(View previousView);
    public abstract QSIconView getIcon();
    public abstract void init(QSTile tile);
    public abstract void onStateChanged(State state);

    public abstract int getDetailY();
}
