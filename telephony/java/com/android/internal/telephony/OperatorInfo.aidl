/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.internal.telephony;

/**
 * Used to indicate that the OperatorInfo object is parcelable to aidl.
 * This is a simple effort to make OperatorInfo parcelable rather than
 * trying to make the conventional containing object (AsyncResult),
 * implement parcelable.  This functionality is needed for the
 * NetworkQueryService to fix 1128695
 */
parcelable OperatorInfo;
