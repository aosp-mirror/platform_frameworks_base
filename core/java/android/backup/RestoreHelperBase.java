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

package android.backup;

import android.content.Context;
import android.util.Log;

import java.io.InputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

class RestoreHelperBase {
    private static final String TAG = "RestoreHelperBase";
    private static final int BUF_SIZE = 8 * 1024;

    Context mContext;
    byte[] mBuf = new byte[BUF_SIZE];
    boolean mExceptionLogged;
    
    RestoreHelperBase(Context context) {
        mContext = context;
    }

    void writeFile(File f, InputStream in) {
        boolean success = false;
        FileOutputStream out = null;
        try {
            // Create the enclosing directory.
            File parent = f.getParentFile();
            parent.mkdirs();

            // Copy the file.
            int sum = 0;
            out = new FileOutputStream(f);
            byte[] buf = mBuf;
            int amt;
            while ((amt = in.read(buf)) > 0) {
                out.write(buf, 0, amt);
                sum += amt;
            }

            // TODO: Set the permissions of the file.

            // We're done
            success = true;
            out = null;
        } catch (IOException ex) {
            // Bail on this entity.  Only log one exception per helper object.
            if (!mExceptionLogged) {
                Log.e(TAG, "Failed restoring file '" + f + "' for app '"
                    + mContext.getPackageName() + '\'', ex);
                mExceptionLogged = true;
            }
        }
        finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ex) {
                }
            }
            if (!success) {
                // Something didn't work out, delete the file
                f.delete();
            }
        }
    }
}


