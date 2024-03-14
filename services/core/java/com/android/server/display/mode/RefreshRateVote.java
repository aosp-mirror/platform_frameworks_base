/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.display.mode;

import android.annotation.NonNull;

import java.util.Objects;


/**
 * Information about the refresh rate frame rate ranges DM would like to set the display to.
 */
abstract class RefreshRateVote implements Vote {
    final float mMinRefreshRate;

    final float mMaxRefreshRate;

    RefreshRateVote(float minRefreshRate, float maxRefreshRate) {
        mMinRefreshRate = minRefreshRate;
        mMaxRefreshRate = maxRefreshRate;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RefreshRateVote that)) return false;
        return Float.compare(that.mMinRefreshRate, mMinRefreshRate) == 0
                && Float.compare(that.mMaxRefreshRate, mMaxRefreshRate) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mMinRefreshRate, mMaxRefreshRate);
    }

    @Override
    public String toString() {
        return "RefreshRateVote{  mMinRefreshRate=" + mMinRefreshRate
                + ", mMaxRefreshRate=" + mMaxRefreshRate + " }";
    }

    static class RenderVote extends RefreshRateVote {
        RenderVote(float minRefreshRate, float maxRefreshRate) {
            super(minRefreshRate, maxRefreshRate);
        }

        /**
         * Summary:        minRender            minPhysical                    maxRender
         *                    v                     v                             v
         * -------------------|---------------------"-----------------------------|---------
         *             ^                ^                   ^*             ^           ^
         *  Vote: min(ignored)     min(applied)  min(applied+physical)  max(applied)  max(ignored)
         */
        @Override
        public void updateSummary(@NonNull VoteSummary summary) {
            summary.minRenderFrameRate = Math.max(summary.minRenderFrameRate, mMinRefreshRate);
            summary.maxRenderFrameRate = Math.min(summary.maxRenderFrameRate, mMaxRefreshRate);
            // Physical refresh rate cannot be lower than the minimal render frame rate.
            summary.minPhysicalRefreshRate = Math.max(summary.minPhysicalRefreshRate,
                    mMinRefreshRate);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof RefreshRateVote.RenderVote)) return false;
            return super.equals(o);
        }

        @Override
        public String toString() {
            return "RenderVote{ " + super.toString() + " }";
        }
    }

    static class PhysicalVote extends RefreshRateVote {
        PhysicalVote(float minRefreshRate, float maxRefreshRate) {
            super(minRefreshRate, maxRefreshRate);
        }

        /**
         * Summary:        minPhysical                   maxRender               maxPhysical
         *                    v                             v                        v
         * -------------------"-----------------------------|----------------------"----------
         *             ^             ^             ^*                 ^                   ^
         *  Vote: min(ignored) min(applied)  max(applied+render)     max(applied)  max(ignored)
         */
        @Override
        public void updateSummary(@NonNull VoteSummary summary) {
            summary.minPhysicalRefreshRate = Math.max(summary.minPhysicalRefreshRate,
                    mMinRefreshRate);
            summary.maxPhysicalRefreshRate = Math.min(summary.maxPhysicalRefreshRate,
                    mMaxRefreshRate);
            // Render frame rate cannot exceed the max physical refresh rate
            summary.maxRenderFrameRate = Math.min(summary.maxRenderFrameRate, mMaxRefreshRate);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof RefreshRateVote.PhysicalVote)) return false;
            return super.equals(o);
        }

        @Override
        public String toString() {
            return "PhysicalVote{ " + super.toString() + " }";
        }
    }
}
