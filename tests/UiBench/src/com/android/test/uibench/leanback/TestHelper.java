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
package com.android.test.uibench.leanback;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.support.v17.leanback.app.BackgroundManager;
import android.support.v17.leanback.widget.ArrayObjectAdapter;
import android.support.v17.leanback.widget.HeaderItem;
import android.support.v17.leanback.widget.ListRow;
import android.support.v17.leanback.widget.ListRowPresenter;
import android.support.v17.leanback.widget.ObjectAdapter;
import android.support.v17.leanback.widget.Presenter;
import android.util.DisplayMetrics;
import android.util.TypedValue;

public class TestHelper {

    public static final String EXTRA_BACKGROUND = "extra_bg";
    public static final String EXTRA_ROWS = "extra_rows";
    public static final String EXTRA_CARDS_PER_ROW = "extra_cards_per_row";
    public static final String EXTRA_CARD_HEIGHT_DP = "extra_card_height";
    public static final String EXTRA_CARD_WIDTH_DP = "extra_card_width";
    public static final String EXTRA_CARD_SHADOW = "extra_card_shadow";
    public static final String EXTRA_CARD_ROUND_RECT = "extra_card_round_rect";
    public static final String EXTRA_ENTRANCE_TRANSITION = "extra_entrance_transition";
    public static final String EXTRA_BITMAP_UPLOAD = "extra_bitmap_upload";
    public static final String EXTRA_SHOW_FAST_LANE = "extra_show_fast_lane";

    /**
     * Dont change the default values, they gave baseline for measuring the performance
     */
    static final int DEFAULT_CARD_HEIGHT_DP = 180;
    static final int DEFAULT_CARD_WIDTH_DP = 125;
    static final int DEFAULT_CARDS_PER_ROW = 20;
    static final int DEFAULT_ROWS = 10;
    static final boolean DEFAULT_ENTRANCE_TRANSITION = false;
    static final boolean DEFAULT_BACKGROUND = true;
    static final boolean DEFAULT_CARD_SHADOW = true;
    static final boolean DEFAULT_CARD_ROUND_RECT = true;
    static final boolean DEFAULT_BITMAP_UPLOAD = true;
    static final boolean DEFAULT_SHOW_FAST_LANE = true;

    static long sCardIdSeed = 0;
    static long sRowIdSeed = 0;

    public static class ListRowPresenterBuilder {

        boolean mShadow = DEFAULT_CARD_SHADOW;
        boolean mRoundedCorner = DEFAULT_CARD_ROUND_RECT;

        ListRowPresenterBuilder(Context context) {
        }

        public ListRowPresenterBuilder configShadow(boolean shadow) {
            mShadow = shadow;
            return this;
        }

        public ListRowPresenterBuilder configRoundedCorner(boolean roundedCorner) {
            mRoundedCorner = roundedCorner;
            return this;
        }

        public ListRowPresenter build() {
            ListRowPresenter listRowPresenter = new ListRowPresenter();
            listRowPresenter.setShadowEnabled(mShadow);
            listRowPresenter.enableChildRoundedCorners(mRoundedCorner);
            return listRowPresenter;
        }
    }

    public static class CardPresenterBuilder {
        Context mContext;
        int mWidthDP = DEFAULT_CARD_WIDTH_DP;
        int mHeightDP = DEFAULT_CARD_HEIGHT_DP;

        CardPresenterBuilder(Context context) {
            mContext = context;
        }

        public CardPresenterBuilder configWidthDP(int widthDP) {
            mWidthDP = widthDP;
            return this;
        }

        public CardPresenterBuilder configHeightDP(int hightDP) {
            mHeightDP = hightDP;
            return this;
        }

        public Presenter build() {
            DisplayMetrics dm = mContext.getResources().getDisplayMetrics();
            return new CardPresenter(
                    (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, mWidthDP, dm),
                    (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, mHeightDP, dm));
        }
    }

    public static class RowsAdapterBuilder {

        Context mContext;
        int mCardsPerRow = DEFAULT_CARDS_PER_ROW;
        int mRows = DEFAULT_ROWS;
        CardPresenterBuilder mCardPresenterBuilder;
        ListRowPresenterBuilder mListRowPresenterBuilder;
        Presenter mCardPresenter;
        boolean mBitmapUpload = DEFAULT_BITMAP_UPLOAD;

        static final String[] sSampleStrings = new String[] {
                "Hello world", "This is a test", "Android TV", "UI Jank Test",
                "Scroll Up", "Scroll Down", "Load Bitmaps"
        };

        /**
         * Create a RowsAdapterBuilder with default settings
         */
        public RowsAdapterBuilder(Context context) {
            mContext = context;
            mCardPresenterBuilder = new CardPresenterBuilder(context);
            mListRowPresenterBuilder = new ListRowPresenterBuilder(context);
        }

        public ListRowPresenterBuilder getListRowPresenterBuilder() {
            return mListRowPresenterBuilder;
        }

        public CardPresenterBuilder getCardPresenterBuilder() {
            return mCardPresenterBuilder;
        }

        public RowsAdapterBuilder configRows(int rows) {
            mRows = rows;
            return this;
        }

        public RowsAdapterBuilder configCardsPerRow(int cardsPerRow) {
            mCardsPerRow = cardsPerRow;
            return this;
        }

        public RowsAdapterBuilder configBitmapUpLoad(boolean bitmapUpload) {
            mBitmapUpload = bitmapUpload;
            return this;
        }

        public ListRow buildListRow() {
            ArrayObjectAdapter listRowAdapter = new ArrayObjectAdapter(mCardPresenter);
            ListRow listRow = new ListRow(new HeaderItem(sRowIdSeed++, "Row"), listRowAdapter);
            int indexSample = 0;
            for (int i = 0; i < mCardsPerRow; i++) {
                // when doing bitmap upload, use different id so each card has different bitmap
                // otherwise all cards share the same bitmap
                listRowAdapter.add(new PhotoItem(sSampleStrings[indexSample],
                        (mBitmapUpload ? sCardIdSeed++ : 0)));
                indexSample++;
                if (indexSample >= sSampleStrings.length) {
                    indexSample = 0;
                }
            }
            return listRow;
        }

        public ObjectAdapter build() {
            try {
                mCardPresenter = mCardPresenterBuilder.build();
                ArrayObjectAdapter adapter = new ArrayObjectAdapter(
                        mListRowPresenterBuilder.build());
                for (int i = 0; i < mRows; i++) {
                    adapter.add(buildListRow());
                }
                return adapter;
            } finally {
                mCardPresenter = null;
            }
        }
    }

    public static boolean runEntranceTransition(Activity activity) {
        return activity.getIntent().getBooleanExtra(EXTRA_ENTRANCE_TRANSITION,
                DEFAULT_ENTRANCE_TRANSITION);
    }

    public static RowsAdapterBuilder initRowsAdapterBuilder(Activity activity) {
        RowsAdapterBuilder builder = new RowsAdapterBuilder(activity);
        boolean shadow = activity.getIntent().getBooleanExtra(EXTRA_CARD_SHADOW,
                DEFAULT_CARD_SHADOW);
        boolean roundRect = activity.getIntent().getBooleanExtra(EXTRA_CARD_ROUND_RECT,
                DEFAULT_CARD_ROUND_RECT);
        int widthDp = activity.getIntent().getIntExtra(EXTRA_CARD_WIDTH_DP,
                DEFAULT_CARD_WIDTH_DP);
        int heightDp = activity.getIntent().getIntExtra(EXTRA_CARD_HEIGHT_DP,
                DEFAULT_CARD_HEIGHT_DP);
        int rows = activity.getIntent().getIntExtra(EXTRA_ROWS, DEFAULT_ROWS);
        int cardsPerRow = activity.getIntent().getIntExtra(EXTRA_CARDS_PER_ROW,
                DEFAULT_CARDS_PER_ROW);
        boolean bitmapUpload = activity.getIntent().getBooleanExtra(EXTRA_BITMAP_UPLOAD,
                DEFAULT_BITMAP_UPLOAD);
        builder.configRows(rows)
                .configCardsPerRow(cardsPerRow)
                .configBitmapUpLoad(bitmapUpload);
        builder.getListRowPresenterBuilder()
                .configRoundedCorner(roundRect)
                .configShadow(shadow);
        builder.getCardPresenterBuilder()
                .configWidthDP(widthDp)
                .configHeightDP(heightDp);
        return builder;
    }

    public static void initBackground(Activity activity) {
        if (activity.getIntent().getBooleanExtra(EXTRA_BACKGROUND, DEFAULT_BACKGROUND)) {
            BackgroundManager manager = BackgroundManager.getInstance(activity);
            manager.attach(activity.getWindow());
            DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            int width = metrics.widthPixels;
            int height = metrics.heightPixels;
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawARGB(255, 128, 128, 128);
            canvas.setBitmap(null);
            manager.setBitmap(bitmap);
        }
    }

    public static void initHeaderState(BrowseFragment fragment) {
        if (!fragment.getActivity().getIntent()
                .getBooleanExtra(EXTRA_SHOW_FAST_LANE, DEFAULT_SHOW_FAST_LANE)) {
            fragment.setHeadersState(BrowseFragment.HEADERS_HIDDEN);
        }
    }
}
