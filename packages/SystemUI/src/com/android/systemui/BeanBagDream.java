/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.systemui;

import android.service.dreams.DreamService;

import com.android.systemui.BeanBag.Board;

public class BeanBagDream extends DreamService {

    private Board mBoard;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(true);
        setFullscreen(true);
        mBoard = new Board(this, null);
        setContentView(mBoard);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        mBoard.startAnimation();
    }

    @Override
    public void onDreamingStopped() {
        mBoard.stopAnimation();
        super.onDreamingStopped();
    }
}
