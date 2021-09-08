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

package com.android.server.biometrics.sensors;

/**
 * Interface for {@link BaseClientMonitor} subclasses that affect the state of enrollment.
 */
public interface EnrollmentModifier {

    /**
     * Callers should typically check this after
     * {@link BaseClientMonitor.Callback#onClientFinished(BaseClientMonitor, boolean)}
     *
     * @return true if the user has gone from:
     *      1) none-enrolled --> enrolled
     *      2) enrolled --> none-enrolled
     *      but NOT any-enrolled --> more-enrolled
     */
    boolean hasEnrollmentStateChanged();

    /**
     * @return true if the user has any enrollments
     */
    boolean hasEnrollments();
}
