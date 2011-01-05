/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef __FWDLOCKENGINECONST_H__
#define __FWDLOCKENGINECONST_H__

namespace android {

/**
 * Constants for forward Lock Engine used for exposing engine's capabilities.
 */
#define FWDLOCK_EXTENSION_FL           ("FL")
#define FWDLOCK_DOTEXTENSION_FL        (".fl")
#define FWDLOCK_MIMETYPE_FL            ("application/x-android-drm-fl")

#define FWDLOCK_EXTENSION_DM           ("DM")
#define FWDLOCK_DOTEXTENSION_DM        (".dm")
#define FWDLOCK_MIMETYPE_DM            ("application/vnd.oma.drm.message")

#define FWDLOCK_DESCRIPTION            ("OMA V1 Forward Lock")

};

#endif /* __FWDLOCKENGINECONST_H__ */
