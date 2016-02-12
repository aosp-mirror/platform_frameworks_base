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

package com.android.mtp;

import android.test.mock.MockResources;

class TestResources extends MockResources {
    @Override
    public String getString(int id) throws NotFoundException {
        switch (id) {
            case R.string.root_name:
                return "%1$s %2$s";
            case R.string.error_busy_device:
                return "error_busy_device";
            case R.string.error_locked_device:
                return "error_locked_device";
        }
        throw new NotFoundException();
    }

    @Override
    public String getString(int id, Object... formatArgs) throws NotFoundException {
        return String.format(getString(id), formatArgs);
    }
}
