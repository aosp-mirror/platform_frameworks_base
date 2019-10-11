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

package com.android.systemui.tuner;

import android.util.DisplayMetrics;
import android.view.View;
import android.view.WindowManager;

import com.android.systemui.Dependency;
import com.android.systemui.tuner.TunerService.Tunable;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Version of Space that can be resized by a tunable setting.
 */
public class TunablePadding implements Tunable {

    public static final int FLAG_START = 1;
    public static final int FLAG_END = 2;
    public static final int FLAG_TOP = 4;
    public static final int FLAG_BOTTOM = 8;

    private final int mFlags;
    private final View mView;
    private final int mDefaultSize;
    private final float mDensity;
    private final TunerService mTunerService;

    private TunablePadding(String key, int def, int flags, View view, TunerService tunerService) {
        mDefaultSize = def;
        mFlags = flags;
        mView = view;
        DisplayMetrics metrics = new DisplayMetrics();
        view.getContext().getSystemService(WindowManager.class)
                .getDefaultDisplay().getMetrics(metrics);
        mDensity = metrics.density;
        mTunerService = tunerService;
        mTunerService.addTunable(this, key);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        int dimen = mDefaultSize;
        if (newValue != null) {
            try {
                dimen = (int) (Integer.parseInt(newValue) * mDensity);
            } catch (NumberFormatException ex) {}
        }
        int left = mView.isLayoutRtl() ? FLAG_END : FLAG_START;
        int right = mView.isLayoutRtl() ? FLAG_START : FLAG_END;
        mView.setPadding(getPadding(dimen, left), getPadding(dimen, FLAG_TOP),
                getPadding(dimen, right), getPadding(dimen, FLAG_BOTTOM));
    }

    private int getPadding(int dimen, int flag) {
        return ((mFlags & flag) != 0) ? dimen : 0;
    }

    public void destroy() {
        mTunerService.removeTunable(this);
    }

    /**
     * Exists for easy injecting in tests.
     */
    @Singleton
    public static class TunablePaddingService {

        private final TunerService mTunerService;

        /**
         */
        @Inject
        public TunablePaddingService(TunerService tunerService) {
            mTunerService = tunerService;
        }

        public TunablePadding add(View view, String key, int defaultSize, int flags) {
            if (view == null) {
                throw new IllegalArgumentException();
            }
            return new TunablePadding(key, defaultSize, flags, view, mTunerService);
        }
    }

    public static TunablePadding addTunablePadding(View view, String key, int defaultSize,
            int flags) {
        return Dependency.get(TunablePaddingService.class).add(view, key, defaultSize, flags);
    }
}
