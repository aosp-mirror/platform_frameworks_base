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

package com.android.server.pm.verify.domain;

import android.os.Handler;

import com.android.server.pm.PackageManagerService;
import com.android.server.pm.verify.domain.proxy.DomainVerificationProxy;

/**
 * Codes that are sent through the {@link PackageManagerService} {@link Handler} and eventually
 * delegated to {@link DomainVerificationService} and {@link DomainVerificationProxy}.
 *
 * These codes are wrapped and thus exclusive to the domain verification APIs. They do not have be
 * distinct from any of the codes inside {@link PackageManagerService}.
 */
public final class DomainVerificationMessageCodes {

    public static final int SEND_REQUEST = 1;
    public static final int LEGACY_SEND_REQUEST = 2;
    public static final int LEGACY_ON_INTENT_FILTER_VERIFIED = 3;
}
