/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.webkit;

import com.android.internal.R;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.webkit.WebView;

/**
 * Represents a plugin (Java equivalent of the PluginPackageAndroid
 * C++ class in libs/WebKitLib/WebKit/WebCore/plugins/android/)
 */
public class Plugin {
    public interface PreferencesClickHandler {
        public void handleClickEvent(Context context);
    }

    private String mName;
    private String mPath;
    private String mFileName;
    private String mDescription;
    private PreferencesClickHandler mHandler;

    public Plugin(String name,
                  String path,
                  String fileName,
                  String description) {
        mName = name;
        mPath = path;
        mFileName = fileName;
        mDescription = description;
        mHandler = new DefaultClickHandler();
    }

    public String toString() {
        return mName;
    }

    public String getName() {
        return mName;
    }

    public String getPath() {
        return mPath;
    }

    public String getFileName() {
        return mFileName;
    }

    public String getDescription() {
        return mDescription;
    }

    public void setName(String name) {
        mName = name;
    }

    public void setPath(String path) {
        mPath = path;
    }

    public void setFileName(String fileName) {
        mFileName = fileName;
    }

    public void setDescription(String description) {
        mDescription = description;
    }

    public void setClickHandler(PreferencesClickHandler handler) {
        mHandler = handler;
    }

   /**
    * Invokes the click handler for this plugin.
    */
    public void dispatchClickEvent(Context context) {
        if (mHandler != null) {
            mHandler.handleClickEvent(context);
        }
    }

   /**
    * Default click handler. The plugins should implement their own.
    */
    private class DefaultClickHandler implements PreferencesClickHandler,
                                                 DialogInterface.OnClickListener {
        private AlertDialog mDialog;

        public void handleClickEvent(Context context) {
            // Show a simple popup dialog containing the description
            // string of the plugin.
            if (mDialog == null) {
                mDialog = new AlertDialog.Builder(context)
                        .setTitle(mName)
                        .setMessage(mDescription)
                        .setPositiveButton(R.string.ok, this)
                        .setCancelable(false)
                        .show();
            }
        }

        public void onClick(DialogInterface dialog, int which) {
            mDialog.dismiss();
            mDialog = null;
        }
    }
}
