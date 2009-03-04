/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony.gsm.stk;


/**
 * Presentation types for SELECT TYPE proactive command.
 *
 * {@hide}
 */
public enum PresentationType {
    /** Presentation type is not specified */
    NOT_SPECIFIED,
    /** Presentation as a choice of data values */
    DATA_VALUES,
    /** Presentation as a choice of navigation options */
    NAVIGATION_OPTIONS;
}
