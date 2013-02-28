/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.keyguard;

public interface SecurityMessageDisplay {
    public void setMessage(CharSequence msg, boolean important);

    public void setMessage(int resId, boolean important);

    public void setMessage(int resId, boolean important, Object... formatArgs);

    public void setTimeout(int timeout_ms);

    public void showBouncer(int animationDuration);

    public void hideBouncer(int animationDuration);
}
