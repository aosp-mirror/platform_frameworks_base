/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.perftests.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class BitmapUtils {
    private static final String TAG = "BitmapUtils";

    public static void saveBitmapIntoPNG(Context context, Bitmap bitmap, int resId) {
        // Save the image to the disk.
        FileOutputStream out = null;
        try {
            String originalFilePath = context.getResources().getString(resId);
            File originalFile = new File(originalFilePath);
            String fileFullName = originalFile.getName();
            String fileTitle = fileFullName.substring(0, fileFullName.lastIndexOf("."));

            File externalFilesDir = context.getExternalFilesDir(null);
            File outputFile = new File(externalFilesDir, fileTitle + "_generated.png");
            if (!outputFile.exists()) {
                outputFile.createNewFile();
            }

            out = new FileOutputStream(outputFile, false);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            if (out != null) {
                out.close();
            }
            Log.v(TAG, "Write test No." + outputFile.getAbsolutePath() + " to file successfully.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
