/*
 * Copyright (C) 2015 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.databinding.tool.writer;

import org.apache.commons.io.FileUtils;

import android.databinding.tool.util.L;

import java.io.File;
import java.io.IOException;

public abstract class JavaFileWriter {
    public abstract void writeToFile(String canonicalName, String contents);
    public void writeToFile(File exactPath, String contents) {
        File parent = exactPath.getParentFile();
        parent.mkdirs();
        try {
            L.d("writing file %s", exactPath.getAbsoluteFile());
            FileUtils.writeStringToFile(exactPath, contents);
        } catch (IOException e) {
            L.e(e, "Could not write to %s", exactPath);
        }
    }
}
