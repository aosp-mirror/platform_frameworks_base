/*
 * Copyright 2020 The Android Open Source Project
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
package com.android.server.tv.tunerresourcemanager;

import android.media.tv.TvInputService;
import android.media.tv.TvInputService.PriorityHintUseCaseType;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;

import com.android.internal.annotations.VisibleForTesting;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * This class provides the Tuner Resource Manager use case priority hints config info including a
 * parser that can read the xml config from the vendors.
 *
 * @hide
 */
public class UseCasePriorityHints {
    private static final String TAG = "UseCasePriorityHints";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final String PATH_TO_VENDOR_CONFIG_XML =
            "/vendor/etc/tunerResourceManagerUseCaseConfig.xml";
    private static final int INVALID_PRIORITY_VALUE = -1;
    private static final int INVALID_USE_CASE = -1;

    /**
     * Array of the configured use case priority hints. Key is the use case id. Value is a size 2
     * int array. The first element carries the priority of the use case on foreground. The second
     * shows the background priority.
     */
    SparseArray<int[]> mPriorityHints = new SparseArray<>();

    Set<Integer> mVendorDefinedUseCase = new HashSet<>();

    private int mDefaultForeground = 150;
    private int mDefaultBackground = 50;

    int getForegroundPriority(int useCase) {
        if (mPriorityHints.get(useCase) != null && mPriorityHints.get(useCase).length == 2) {
            return mPriorityHints.get(useCase)[0];
        }
        return mDefaultForeground;
    }

    int getBackgroundPriority(int useCase) {
        if (mPriorityHints.get(useCase) != null && mPriorityHints.get(useCase).length == 2) {
            return mPriorityHints.get(useCase)[1];
        }
        return mDefaultBackground;
    }

    boolean isDefinedUseCase(int useCase) {
        return (mVendorDefinedUseCase.contains(useCase) || isPredefinedUseCase(useCase));
    }

    /**
     * To parse the vendor use case config.
     */
    public void parse() {
        // Override the default priority with vendor setting if available.
        File file = new File(PATH_TO_VENDOR_CONFIG_XML);
        if (file.exists()) {
            try {
                InputStream in = new FileInputStream(file);
                parseInternal(in);
                return;
            } catch (IOException e) {
                Slog.e(TAG, "Error reading vendor file: " + file, e);
            } catch (XmlPullParserException e) {
                Slog.e(TAG, "Unable to parse vendor file: " + file, e);
            }
        } else {
            if (DEBUG) {
                Slog.i(TAG, "no vendor priority configuration available. Using default priority");
            }
            addNewUseCasePriority(TvInputService.PRIORITY_HINT_USE_CASE_TYPE_BACKGROUND, 180, 100);
            addNewUseCasePriority(TvInputService.PRIORITY_HINT_USE_CASE_TYPE_SCAN, 450, 200);
            addNewUseCasePriority(TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK, 480, 300);
            addNewUseCasePriority(TvInputService.PRIORITY_HINT_USE_CASE_TYPE_LIVE, 490, 400);
            addNewUseCasePriority(TvInputService.PRIORITY_HINT_USE_CASE_TYPE_RECORD, 600, 500);
        }
    }

    // We don't use namespaces
    private static final String NS = null;

    @VisibleForTesting
    protected void parseInternal(InputStream in)
            throws IOException, XmlPullParserException {
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(in, null);
            parser.nextTag();
            readUseCase(parser);
            in.close();
        } catch (IOException | XmlPullParserException e) {
            throw e;
        }
        for (int i = 0; i < mPriorityHints.size(); i++) {
            int useCase = mPriorityHints.keyAt(i);
            int[] priorities = mPriorityHints.get(useCase);
            if (DEBUG) {
                Slog.d(TAG, "{defaultFg=" + mDefaultForeground
                        + ", defaultBg=" + mDefaultBackground + "}");
                Slog.d(TAG, "{useCase=" + useCase
                        + ", fg=" + priorities[0]
                        + ", bg=" + priorities[1]
                        + "}");
            }
        }
    }

    private void readUseCase(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        parser.require(XmlPullParser.START_TAG, NS, "config");
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }
            String name = parser.getName();
            int useCase;
            if (name.equals("useCaseDefault")) {
                mDefaultForeground = readAttributeToInt("fgPriority", parser);
                mDefaultBackground = readAttributeToInt("bgPriority", parser);
                parser.nextTag();
                parser.require(XmlPullParser.END_TAG, NS, name);
            } else if (name.equals("useCasePreDefined")) {
                useCase = formatTypeToNum("type", parser);
                if (useCase == INVALID_USE_CASE) {
                    Slog.e(TAG, "Wrong predefined use case name given in the vendor config.");
                    continue;
                }
                addNewUseCasePriority(useCase,
                        readAttributeToInt("fgPriority", parser),
                        readAttributeToInt("bgPriority", parser));
                parser.nextTag();
                parser.require(XmlPullParser.END_TAG, NS, name);
            } else if (name.equals("useCaseVendor")) {
                useCase = readAttributeToInt("id", parser);
                addNewUseCasePriority(useCase,
                        readAttributeToInt("fgPriority", parser),
                        readAttributeToInt("bgPriority", parser));
                mVendorDefinedUseCase.add(useCase);
                parser.nextTag();
                parser.require(XmlPullParser.END_TAG, NS, name);
            } else {
                skip(parser);
            }
        }
    }

    private void skip(XmlPullParser parser) throws XmlPullParserException, IOException {
        if (parser.getEventType() != XmlPullParser.START_TAG) {
            throw new IllegalStateException();
        }
        int depth = 1;
        while (depth != 0) {
            switch (parser.next()) {
                case XmlPullParser.END_TAG:
                    depth--;
                    break;
                case XmlPullParser.START_TAG:
                    depth++;
                    break;
            }
        }
    }

    private int readAttributeToInt(String attributeName, XmlPullParser parser) {
        return Integer.valueOf(parser.getAttributeValue(null, attributeName));
    }

    private void addNewUseCasePriority(int useCase, int fgPriority, int bgPriority) {
        int[] priorities = {fgPriority, bgPriority};
        mPriorityHints.append(useCase, priorities);
    }

    @PriorityHintUseCaseType
    private static int formatTypeToNum(String attributeName, XmlPullParser parser) {
        String useCaseName = parser.getAttributeValue(null, attributeName);
        switch (useCaseName) {
            case "USE_CASE_BACKGROUND":
                return TvInputService.PRIORITY_HINT_USE_CASE_TYPE_BACKGROUND;
            case "USE_CASE_SCAN":
                return TvInputService.PRIORITY_HINT_USE_CASE_TYPE_SCAN;
            case "USE_CASE_PLAYBACK":
                return TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK;
            case "USE_CASE_LIVE":
                return TvInputService.PRIORITY_HINT_USE_CASE_TYPE_LIVE;
            case "USE_CASE_RECORD":
                return TvInputService.PRIORITY_HINT_USE_CASE_TYPE_RECORD;
            default:
                return INVALID_USE_CASE;
        }
    }

    private static boolean isPredefinedUseCase(int useCase) {
        switch (useCase) {
            case TvInputService.PRIORITY_HINT_USE_CASE_TYPE_BACKGROUND:
            case TvInputService.PRIORITY_HINT_USE_CASE_TYPE_SCAN:
            case TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK:
            case TvInputService.PRIORITY_HINT_USE_CASE_TYPE_LIVE:
            case TvInputService.PRIORITY_HINT_USE_CASE_TYPE_RECORD:
                return true;
            default:
                return false;
        }
    }
}
