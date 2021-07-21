/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.screenshot;

import android.annotation.Nullable;
import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import androidx.concurrent.futures.CallbackToFutureAdapter;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.inject.Inject;

/** Loads images. */
public class ImageLoader {
    private final ContentResolver mResolver;

    static class Result {
        @Nullable Uri uri;
        @Nullable File fileName;
        @Nullable Bitmap bitmap;

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder("Result{");
            sb.append("uri=").append(uri);
            sb.append(", fileName=").append(fileName);
            sb.append(", bitmap=").append(bitmap);
            sb.append('}');
            return sb.toString();
        }
    }

    @Inject
    ImageLoader(ContentResolver resolver) {
        mResolver = resolver;
    }

    /**
     * Loads an image via URI from ContentResolver.
     *
     * @param uri the identifier of the image to load
     * @return a listenable future result containing a bitmap
     */
    ListenableFuture<Result> load(Uri uri) {
        return CallbackToFutureAdapter.getFuture(completer -> {
            Result result = new Result();
            try (InputStream in = mResolver.openInputStream(uri)) {
                result.uri = uri;
                result.bitmap = BitmapFactory.decodeStream(in);
                completer.set(result);
            }
            catch (IOException e) {
                completer.setException(e);
            }
            return "BitmapFactory#decodeStream";
        });
    }

    /**
     * Loads an image by physical filesystem name. The current user must have filesystem
     * permissions to read this file/path.
     *
     * @param file the system file path of the image to load
     * @return a listenable future result containing a bitmap
     */
    ListenableFuture<Result> load(File file) {
        return CallbackToFutureAdapter.getFuture(completer -> {
            try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
                Result result = new Result();
                result.fileName = file;
                result.bitmap = BitmapFactory.decodeStream(in);
                completer.set(result);
            } catch (IOException e) {
                completer.setException(e);
            }
            return "BitmapFactory#decodeStream";
        });
    }
}
