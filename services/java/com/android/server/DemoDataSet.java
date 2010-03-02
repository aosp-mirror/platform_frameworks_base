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

package com.android.server;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Environment;
import android.provider.Contacts;
import android.provider.Settings;
import android.provider.MediaStore.Images;
import android.util.Config;
import android.util.Slog;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;

public class DemoDataSet
{
    private final static String LOG_TAG = "DemoDataSet";

    private ContentResolver mContentResolver;

    public final void add(Context context)
    {
        mContentResolver = context.getContentResolver();

        // Remove all the old data
        mContentResolver.delete(Contacts.People.CONTENT_URI, null, null);

        // Add the new data
        addDefaultData();
        
        // Add images from /android/images
        addDefaultImages();
    }

    private final void addDefaultImages()
    {
        File rootDirectory = Environment.getRootDirectory();
        String [] files
            = new File(rootDirectory, "images").list();
        int count = files.length;

        if (count == 0) {
            Slog.i(LOG_TAG, "addDefaultImages: no images found!");
            return;
        }

        for (int i = 0; i < count; i++)
        {
            String name = files[i];
            String path = rootDirectory + "/" + name;
            
            try {
                Images.Media.insertImage(mContentResolver, path, name, null);
            } catch (FileNotFoundException e) {
                Slog.e(LOG_TAG, "Failed to import image " + path, e);
            }
        }
    }
    
    private final void addDefaultData()
    {
        Slog.i(LOG_TAG, "Adding default data...");

//       addImage("Violet", "images/violet.png");
//       addImage("Corky", "images/corky.png");

        // PENDING: should this be done here?!?!
        Intent intent = new Intent(
                Intent.ACTION_CALL, Uri.fromParts("voicemail", "", null));
        addShortcut("1", intent);
    }

    private final Uri addImage(String name, Uri file)
    {
        ContentValues imagev = new ContentValues();
        imagev.put("name", name);

        Uri url = null;

        AssetManager ass = AssetManager.getSystem();
        InputStream in = null;
        OutputStream out = null;
        
        try
        {
            in = ass.open(file.toString());

            url = mContentResolver.insert(Images.Media.INTERNAL_CONTENT_URI, imagev);
            out = mContentResolver.openOutputStream(url);

            final int size = 8 * 1024;
            byte[] buf = new byte[size];

            int count = 0;
            do
            {
                count = in.read(buf, 0, size);
                if (count > 0) {
                    out.write(buf, 0, count);
                }
            } while (count > 0);
        }
        catch (Exception e)
        {
            Slog.e(LOG_TAG, "Failed to insert image '" + file + "'", e);
            url = null;
        }

        return url;
    }

    private final Uri addShortcut(String shortcut, Intent intent)
    {
        if (Config.LOGV) Slog.v(LOG_TAG, "addShortcut: shortcut=" + shortcut + ", intent=" + intent);
        return Settings.Bookmarks.add(mContentResolver, intent, null, null,
                                      shortcut != null ? shortcut.charAt(0) : 0, 0);
    }
}
