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

package android.test;

import android.content.Context;

/**
 * {@hide}
 * More complex interface for test cases.
 *
 * <p>Just implementing Runnable is enough for many test cases.  If you
 * have additional setup or teardown, this interface might be for you, 
 * especially if you need to share it between different test cases, or your
 * teardown code must execute regardless of whether your test passed.
 *
 * <p>See the android.test package documentation (click the more... link)
 * for a full description
 */

@Deprecated
public interface TestCase extends Runnable
{
    /**
     * Called before run() is called.
     */
    public void setUp(Context context);

    /**
     * Called after run() is called, even if run() threw an exception, but
     * not if setUp() threw an execption.
     */
    public void tearDown();
}

