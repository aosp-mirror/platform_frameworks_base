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

/**
 * Represents a plugin (Java equivalent of the PluginPackageAndroid
 * C++ class in libs/WebKitLib/WebKit/WebCore/plugins/android/)
 *
 * @hide
 * @deprecated This interface was intended to be used by Gears. Since Gears was
 * deprecated, so is this class.
 */
@Deprecated
public class Plugin {
    /*
     * @hide
     * @deprecated This interface was intended to be used by Gears. Since Gears was
     * deprecated, so is this class.
     */
    public interface PreferencesClickHandler {
        /*
         * @hide
         * @deprecated This interface was intended to be used by Gears. Since Gears was
         * deprecated, so is this class.
         */
        public void handleClickEvent(Context context);
    }

    private String mName;
    private String mPath;
    private String mFileName;
    private String mDescription;
    private PreferencesClickHandler mHandler;

    /**
     * @hide
     * @deprecated This interface was intended to be used by Gears. Since Gears was
     * deprecated, so is this class.
     */
    @Deprecated
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

    /**
     * @hide
     * @deprecated This interface was intended to be used by Gears. Since Gears was
     * deprecated, so is this class.
     */
    @Deprecated
    public String toString() {
        return mName;
    }

    /**
     * @hide
     * @deprecated This interface was intended to be used by Gears. Since Gears was
     * deprecated, so is this class.
     */
    @Deprecated
    public String getName() {
        return mName;
    }

    /**
     * @hide
     * @deprecated This interface was intended to be used by Gears. Since Gears was
     * deprecated, so is this class.
     */
    @Deprecated
    public String getPath() {
        return mPath;
    }

    /**
     * @hide
     * @deprecated This interface was intended to be used by Gears. Since Gears was
     * deprecated, so is this class.
     */
    @Deprecated
    public String getFileName() {
        return mFileName;
    }

    /**
     * @hide
     * @deprecated This interface was intended to be used by Gears. Since Gears was
     * deprecated, so is this class.
     */
    @Deprecated
    public String getDescription() {
        return mDescription;
    }

    /**
     * @hide
     * @deprecated This interface was intended to be used by Gears. Since Gears was
     * deprecated, so is this class.
     */
    @Deprecated
    public void setName(String name) {
        mName = name;
    }

    /**
     * @hide
     * @deprecated This interface was intended to be used by Gears. Since Gears was
     * deprecated, so is this class.
     */
    @Deprecated
    public void setPath(String path) {
        mPath = path;
    }

    /**
     * @hide
     * @deprecated This interface was intended to be used by Gears. Since Gears was
     * deprecated, so is this class.
     */
    @Deprecated
    public void setFileName(String fileName) {
        mFileName = fileName;
    }

    /**
     * @hide
     * @deprecated This interface was intended to be used by Gears. Since Gears was
     * deprecated, so is this class.
     */
    @Deprecated
    public void setDescription(String description) {
        mDescription = description;
    }

    /**
     * @hide
     * @deprecated This interface was intended to be used by Gears. Since Gears was
     * deprecated, so is this class.
     */
    @Deprecated
    public void setClickHandler(PreferencesClickHandler handler) {
        mHandler = handler;
    }

   /**
    * Invokes the click handler for this plugin.
    *
    * @hide
    * @deprecated This interface was intended to be used by Gears. Since Gears was
    * deprecated, so is this class.
    */
    @Deprecated
    public void dispatchClickEvent(Context context) {
        if (mHandler != null) {
            mHandler.handleClickEvent(context);
        }
    }

   /**
    * Default click handler. The plugins should implement their own.
    *
    * @hide
    * @deprecated This interface was intended to be used by Gears. Since Gears was
    * deprecated, so is this class.
    */
    @Deprecated
    private class DefaultClickHandler implements PreferencesClickHandler,
                                                 DialogInterface.OnClickListener {
        private AlertDialog mDialog;
        @Deprecated
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
        /**
         * @hide
         * @deprecated This interface was intended to be used by Gears. Since Gears was
         * deprecated, so is this class.
         */
        @Deprecated
        public void onClick(DialogInterface dialog, int which) {
            mDialog.dismiss();
            mDialog = null;
        }
    }
}
