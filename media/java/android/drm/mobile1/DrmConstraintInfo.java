/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.drm.mobile1;

import java.util.Date;

/**
 * This class provides interfaces to access the DRM constraint.
 */
public class DrmConstraintInfo {
    /**
     * The constraint of count.
     */
    private int count;

    /**
     * The constraint of start date.
     */
    private long startDate;

    /**
     * The constraint of end date.
     */
    private long endDate;

    /**
     * The constraint of interval.
     */
    private long interval;

    /**
     * Construct the DrmConstraint.
     */
    DrmConstraintInfo() {
        count = -1;
        startDate = -1;
        endDate = -1;
        interval = -1;
    }

    /**
     * Get the count constraint.
     *
     * @return the count or -1 if no limit.
     */
    public int getCount() {
        return count;
    }

    /**
     * Get the start date constraint.
     *
     * @return the start date or null if no limit.
     */
    public Date getStartDate() {
        if (startDate == -1)
            return null;

        return new Date(startDate);
    }

    /**
     * Get the end date constraint.
     *
     * @return the end date or null if no limit.
     */
    public Date getEndDate() {
        if (endDate == -1)
            return null;

        return new Date(endDate);
    }

    /**
     * Get the Interval constraint.
     *
     * @return the interval or -1 if no limit.
     */
    public long getInterval() {
        return interval;
    }
}
