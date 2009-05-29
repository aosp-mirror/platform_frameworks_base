/*
 * Copyright (C) 2009 The Android Open Source Project
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


package android.gesture;

import android.content.Context;
import android.util.Log;
import static android.gesture.GestureConstants.LOG_TAG;
import static android.gesture.LetterRecognizer.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;

public final class LetterRecognizers {
    public final static int RECOGNIZER_LATIN_LOWERCASE = 0;

    private LetterRecognizers() {
    }

    public static LetterRecognizer fromType(Context context, int type) {
        switch (type) {
            case RECOGNIZER_LATIN_LOWERCASE: {
                return createFromResource(context, com.android.internal.R.raw.latin_lowercase);
            }
        }
        return null;
    }

    public static LetterRecognizer fromResource(Context context, int resourceId) {
        return createFromResource(context, resourceId);
    }

    public static LetterRecognizer fromFile(String path) {
        return fromFile(new File(path));
    }

    public static LetterRecognizer fromFile(File file) {
        try {
            return createFromStream(new FileInputStream(file));
        } catch (FileNotFoundException e) {
            Log.d(LOG_TAG, "Failed to load handwriting data from file " + file, e);
        }
        return null;
    }

    public static LetterRecognizer fromStream(InputStream stream) {
        return createFromStream(stream);
    }
}
