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

package android.core;

import android.test.suitebuilder.annotation.MediumTest;
import java.util.logging.Logger;
import junit.framework.TestCase;

public class MiscRegressionTest extends TestCase {

    // Regression test for #951285: Suitable LogHandler should be chosen
    // depending on the environment.
    @MediumTest
    public void testAndroidLogHandler() throws Exception {
        Logger.global.severe("This has logging Level.SEVERE, should become ERROR");
        Logger.global.warning("This has logging Level.WARNING, should become WARN");
        Logger.global.info("This has logging Level.INFO, should become INFO");
        Logger.global.config("This has logging Level.CONFIG, should become DEBUG");
        Logger.global.fine("This has logging Level.FINE, should become VERBOSE");
        Logger.global.finer("This has logging Level.FINER, should become VERBOSE");
        Logger.global.finest("This has logging Level.FINEST, should become VERBOSE");
    }
}
