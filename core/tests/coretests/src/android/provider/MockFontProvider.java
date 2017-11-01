/*
 * Copyright (C) 2017 The Android Open Source Project
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
package android.provider;

import static android.provider.FontsContract.Columns;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.fonts.FontVariationAxis;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.util.ArraySet;
import android.util.SparseArray;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.android.internal.annotations.GuardedBy;

public class MockFontProvider extends ContentProvider {
    final static String AUTHORITY = "android.provider.fonts.font";

    private static final long BLOCKING_TIMEOUT_MS = 10000;  // 10 sec
    private static final Lock sLock = new ReentrantLock();
    private static final Condition sCond = sLock.newCondition();
    @GuardedBy("sLock")
    private static boolean sSignaled;

    private static void blockUntilSignal() {
        long remaining = TimeUnit.MILLISECONDS.toNanos(BLOCKING_TIMEOUT_MS);
        sLock.lock();
        try {
            sSignaled = false;
            while (!sSignaled) {
                try {
                    remaining = sCond.awaitNanos(remaining);
                } catch (InterruptedException e) {
                    // do nothing.
                }
                if (sSignaled) {
                    return;
                }
                if (remaining <= 0) {
                    // Timed out
                    throw new RuntimeException("Timeout during waiting");
                }
            }
        } finally {
            sLock.unlock();
        }
    }

    public static void unblock() {
        sLock.lock();
        try {
            sSignaled = true;
            sCond.signal();
        } finally {
            sLock.unlock();
        }
    }

    final static String[] FONT_FILES = {
        "samplefont1.ttf",
    };
    private static final int NO_FILE_ID = 255;
    private static final int SAMPLE_FONT_FILE_0_ID = 0;

    static class Font {
        public Font(int id, int fileId, int ttcIndex, String varSettings, int weight, int italic,
                int resultCode) {
            mId = id;
            mFileId = fileId;
            mTtcIndex = ttcIndex;
            mVarSettings = varSettings;
            mWeight = weight;
            mItalic = italic;
            mResultCode = resultCode;
        }

        public int getId() {
            return mId;
        }

        public int getTtcIndex() {
            return mTtcIndex;
        }

        public String getVarSettings() {
            return mVarSettings;
        }

        public int getWeight() {
            return mWeight;
        }

        public int getItalic() {
            return mItalic;
        }

        public int getResultCode() {
            return mResultCode;
        }

        public int getFileId() {
            return mFileId;
        }

        private int mId;
        private int mFileId;
        private int mTtcIndex;
        private String mVarSettings;
        private int mWeight;
        private int mItalic;
        private int mResultCode;
    };

    public static final String BLOCKING_QUERY = "queryBlockingQuery";
    public static final String NULL_FD_QUERY = "nullFdQuery";

    private static Map<String, Font[]> QUERY_MAP;
    static {
        HashMap<String, Font[]> map = new HashMap<>();
        int id = 0;

        map.put("singleFontFamily", new Font[] {
            new Font(id++, SAMPLE_FONT_FILE_0_ID, 0, null, 400, 0, Columns.RESULT_CODE_OK),
        });

        map.put("singleFontFamily2", new Font[] {
            new Font(id++, SAMPLE_FONT_FILE_0_ID, 0, null, 700, 0, Columns.RESULT_CODE_OK),
        });

        map.put(BLOCKING_QUERY, new Font[] {
            new Font(id++, SAMPLE_FONT_FILE_0_ID, 0, null, 700, 0, Columns.RESULT_CODE_OK),
        });

        map.put(NULL_FD_QUERY, new Font[] {
            new Font(id++, NO_FILE_ID, 0, null, 700, 0, Columns.RESULT_CODE_OK),
        });

        QUERY_MAP = Collections.unmodifiableMap(map);
    }

    private static Cursor buildCursor(Font[] in) {
        MatrixCursor cursor = new MatrixCursor(new String[] {
                Columns._ID, Columns.TTC_INDEX, Columns.VARIATION_SETTINGS, Columns.WEIGHT,
                Columns.ITALIC, Columns.RESULT_CODE, Columns.FILE_ID});
        for (Font font : in) {
            MatrixCursor.RowBuilder builder = cursor.newRow();
            builder.add(Columns._ID, font.getId());
            builder.add(Columns.FILE_ID, font.getFileId());
            builder.add(Columns.TTC_INDEX, font.getTtcIndex());
            builder.add(Columns.VARIATION_SETTINGS, font.getVarSettings());
            builder.add(Columns.WEIGHT, font.getWeight());
            builder.add(Columns.ITALIC, font.getItalic());
            builder.add(Columns.RESULT_CODE, font.getResultCode());
        }
        return cursor;
    }

    public MockFontProvider() {
    }

    public static void prepareFontFiles(Context context) {
        final AssetManager mgr = context.getAssets();
        for (String file : FONT_FILES) {
            try (InputStream is = mgr.open("fonts/" + file)) {
                Files.copy(is, getCopiedFile(context, file).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static void cleanUpFontFiles(Context context) {
        for (String file : FONT_FILES) {
            getCopiedFile(context, file).delete();
        }
    }

    public static File getCopiedFile(Context context, String path) {
        return new File(context.getFilesDir(), path);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) {
        final int id = (int)ContentUris.parseId(uri);
        if (id == NO_FILE_ID) {
            return null;
        }
        final File targetFile = getCopiedFile(getContext(), FONT_FILES[id]);
        try {
            return ParcelFileDescriptor.open(targetFile, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.android.provider.font";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        final String query = selectionArgs[0];
        if (query.equals(BLOCKING_QUERY)) {
            blockUntilSignal();
        }
        return buildCursor(QUERY_MAP.get(query));
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("insert is not supported.");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("delete is not supported.");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("update is not supported.");
    }
}
