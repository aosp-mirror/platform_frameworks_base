/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.companion;

import static org.xmlpull.v1.XmlPullParser.END_TAG;
import static org.xmlpull.v1.XmlPullParser.START_TAG;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.os.Environment;
import android.util.AtomicFile;
import android.util.Slog;

import com.android.internal.util.FunctionalUtils.ThrowingConsumer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileOutputStream;

final class DataStoreUtils {
    private static final String TAG = "CompanionDevice_DataStoreUtils";

    static boolean isStartOfTag(@NonNull XmlPullParser parser, @NonNull String tag)
            throws XmlPullParserException {
        return parser.getEventType() == START_TAG && tag.equals(parser.getName());
    }

    static boolean isEndOfTag(@NonNull XmlPullParser parser, @NonNull String tag)
            throws XmlPullParserException {
        return parser.getEventType() == END_TAG && tag.equals(parser.getName());
    }

    /**
     * Creates {@link AtomicFile} object that represents the back-up for the given user.
     *
     * IMPORTANT: the method will ALWAYS return the same {@link AtomicFile} object, which makes it
     * possible to synchronize reads and writes to the file using the returned object.
     *
     * @param userId              the userId to retrieve the storage file
     * @param fileName         the storage file name
     * @return an AtomicFile for the user
     */
    @NonNull
    static AtomicFile createStorageFileForUser(@UserIdInt int userId, String fileName) {
        return new AtomicFile(getBaseStorageFileForUser(userId, fileName));
    }

    @NonNull
    private static File getBaseStorageFileForUser(@UserIdInt int userId, String fileName) {
        return new File(Environment.getDataSystemDeDirectory(userId), fileName);
    }

    /**
     * Writing to file could fail, for example, if the user has been recently removed and so was
     * their DE (/data/system_de/<user-id>/) directory.
     */
    static void writeToFileSafely(
            @NonNull AtomicFile file, @NonNull ThrowingConsumer<FileOutputStream> consumer) {
        try {
            file.write(consumer);
        } catch (Exception e) {
            Slog.e(TAG, "Error while writing to file " + file, e);
        }
    }

    private DataStoreUtils() {
    }
}
