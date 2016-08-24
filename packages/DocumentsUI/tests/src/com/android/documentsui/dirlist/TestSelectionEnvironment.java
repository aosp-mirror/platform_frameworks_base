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
 * limitations under the License.
 */

package com.android.documentsui.dirlist;

import android.graphics.Point;
import android.graphics.Rect;
import android.support.v7.widget.RecyclerView.OnScrollListener;

import com.android.documentsui.dirlist.MultiSelectManager.SelectionEnvironment;

import java.util.List;

public class TestSelectionEnvironment implements SelectionEnvironment {

    public TestSelectionEnvironment(List<String> items) {
    }

    @Override
    public void showBand(Rect rect) {
    }

    @Override
    public void hideBand() {
    }

    @Override
    public void addOnScrollListener(OnScrollListener listener) {
    }

    @Override
    public void removeOnScrollListener(OnScrollListener listener) {
    }

    @Override
    public void scrollBy(int dy) {
    }

    @Override
    public int getHeight() {
        return 0;
    }

    @Override
    public void invalidateView() {
    }

    @Override
    public void runAtNextFrame(Runnable r) {
    }

    @Override
    public void removeCallback(Runnable r) {
    }

    @Override
    public Point createAbsolutePoint(Point relativePoint) {
        return null;
    }

    @Override
    public Rect getAbsoluteRectForChildViewAt(int index) {
        return null;
    }

    @Override
    public int getAdapterPositionAt(int index) {
        return 0;
    }

    @Override
    public int getColumnCount() {
        return 0;
    }

    @Override
    public int getChildCount() {
        return 0;
    }

    @Override
    public int getVisibleChildCount() {
        return 0;
    }

    @Override
    public boolean isLayoutItem(int adapterPosition) {
        return false;
    }

    @Override
    public boolean hasView(int adapterPosition) {
        return true;
    }
}
