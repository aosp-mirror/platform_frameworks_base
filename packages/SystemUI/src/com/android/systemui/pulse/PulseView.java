/**
 * Copyright (C) 2019 The AquariOS Project
 *
 * @author: Randall Rushing <randall.rushing@gmail.com>
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
 *
 * This is were we draw Pulse. Attach to a ViewGroup and let the
 * eye candy happen
 *
 */
package com.android.systemui.pulse;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.FrameLayout.LayoutParams;

public class PulseView extends View {
    public static final String TAG = "PulseView";

    private PulseControllerImpl mPulse;

    public PulseView(Context context, PulseControllerImpl controller) {
        super(context);
        mPulse = controller;
        setLayoutParams(new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        setWillNotDraw(false);
        setTag(TAG);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        mPulse.onSizeChanged(w, h, oldw, oldh);
        super.onSizeChanged(w, h, oldw, oldh);
    }

    @Override
    public void onDraw(Canvas canvas) {
        mPulse.onDraw(canvas);
        super.onDraw(canvas);
    }

}
