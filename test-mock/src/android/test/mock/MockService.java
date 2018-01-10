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
package android.test.mock;

import android.app.Application;
import android.app.Service;
import android.content.Context;

/**
 * A mock {@link android.app.Service} class.
 *
 * <p>Provided for use by {@code android.test.ServiceTestCase}.
 *
 * @deprecated Use a mocking framework like <a href="https://github.com/mockito/mockito">Mockito</a>.
 * New tests should be written using the
 * <a href="{@docRoot}tools/testing-support-library/index.html">Android Testing Support Library</a>.
 */
@Deprecated
public class MockService {

    public static <T extends Service> void attachForTesting(Service service, Context context,
            String serviceClassName,
            Application application) {
        service.attach(
                context,
                null,               // ActivityThread not actually used in Service
                serviceClassName,
                null,               // token not needed when not talking with the activity manager
                application,
                null                // mocked services don't talk with the activity manager
        );
    }

    private MockService() {
    }
}
