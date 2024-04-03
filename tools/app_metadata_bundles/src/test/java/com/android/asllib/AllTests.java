/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.asllib;

import com.android.asllib.marshallable.AndroidSafetyLabelTest;
import com.android.asllib.marshallable.AppInfoTest;
import com.android.asllib.marshallable.DataCategoryTest;
import com.android.asllib.marshallable.DataLabelsTest;
import com.android.asllib.marshallable.DeveloperInfoTest;
import com.android.asllib.marshallable.SafetyLabelsTest;
import com.android.asllib.marshallable.SecurityLabelsTest;
import com.android.asllib.marshallable.SystemAppSafetyLabelTest;
import com.android.asllib.marshallable.ThirdPartyVerificationTest;
import com.android.asllib.marshallable.TransparencyInfoTest;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses({
    AslgenTests.class,
    AndroidSafetyLabelTest.class,
    AppInfoTest.class,
    DataCategoryTest.class,
    DataLabelsTest.class,
    DeveloperInfoTest.class,
    SafetyLabelsTest.class,
    SecurityLabelsTest.class,
    SystemAppSafetyLabelTest.class,
    ThirdPartyVerificationTest.class,
    TransparencyInfoTest.class
})
public class AllTests {}
