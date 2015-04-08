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

package android.databinding.test.independentlibrary;

import android.test.ActivityInstrumentationTestCase2;
import android.view.View;
import android.widget.TextView;

public class LibraryActivityTest extends ActivityInstrumentationTestCase2<LibraryActivity> {
    public LibraryActivityTest() {
        super(LibraryActivity.class);
    }

    public void testTextViewContents() throws Throwable {
        final LibraryActivity activity = getActivity();
        assertNotNull("test sanity", activity);
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                TextView textView = (TextView) activity.findViewById(R.id.fooTextView);
                final String expected = LibraryActivity.FIELD_VALUE + " " +
                        LibraryActivity.FIELD_VALUE;
                assertEquals(expected, textView.getText().toString());
            }
        });
    }
}
