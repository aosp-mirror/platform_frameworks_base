/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.google.android.test.handwritingime;

import android.R;
import android.annotation.Nullable;
import android.graphics.PointF;
import android.graphics.RectF;
import android.inputmethodservice.InputMethodService;
import android.os.CancellationSignal;
import android.os.Handler;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.DeleteGesture;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.HandwritingGesture;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InsertGesture;
import android.view.inputmethod.InsertModeGesture;
import android.view.inputmethod.JoinOrSplitGesture;
import android.view.inputmethod.PreviewableHandwritingGesture;
import android.view.inputmethod.RemoveSpaceGesture;
import android.view.inputmethod.SelectGesture;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Toast;

import java.util.Random;
import java.util.function.IntConsumer;

public class HandwritingIme extends InputMethodService {
    private static final int OP_NONE = 0;
    // ------- PreviewableHandwritingGesture BEGIN -----
    private static final int OP_SELECT = 1;
    private static final int OP_DELETE = 2;
    // ------- PreviewableHandwritingGesture END -----
    private static final int OP_INSERT = 3;
    private static final int OP_REMOVE_SPACE = 4;
    private static final int OP_JOIN_OR_SPLIT = 5;
    private static final int OP_INSERT_MODE = 6;

    private InkView mInk;

    static final String TAG = "HandwritingIme";
    private int mRichGestureMode = OP_NONE;
    private int mRichGestureGranularity = -1;
    private Spinner mRichGestureModeSpinner;
    private Spinner mRichGestureGranularitySpinner;
    private PointF mRichGestureStartPoint;

    static final int BOUNDS_INFO_NONE = 0;
    static final int BOUNDS_INFO_VISIBLE_LINE_BOUNDS = 1;
    static final int BOUNDS_INFO_EDITOR_BOUNDS = 2;
    private int mBoundsInfoMode = BOUNDS_INFO_NONE;
    private LinearLayout mBoundsInfoCheckBoxes;

    private final IntConsumer mResultConsumer = value -> Log.d(TAG, "Gesture result: " + value);

    private CancellationSignal mCancellationSignal = new CancellationSignal();
    private boolean mUsePreview;
    private CheckBox mGesturePreviewCheckbox;

    interface HandwritingFinisher {
        void finish();
    }

    interface StylusListener {
        void onStylusEvent(MotionEvent me);
    }

    final class StylusConsumer implements StylusListener {
        @Override
        public void onStylusEvent(MotionEvent me) {
            HandwritingIme.this.onStylusEvent(me);
        }
    }

    final class HandwritingFinisherImpl implements HandwritingFinisher {

        HandwritingFinisherImpl() {}

        @Override
        public void finish() {
            finishStylusHandwriting();
            Log.d(TAG, "HandwritingIme called finishStylusHandwriting() ");
        }
    }

    private void onStylusEvent(@Nullable MotionEvent event) {
        // TODO Hookup recognizer here
        HandwritingGesture gesture;
        switch (event.getAction()) {
            case MotionEvent.ACTION_MOVE:
                if (mUsePreview && areRichGesturesEnabled()) {
                    gesture = computeGesture(event, true /* isPreview */);
                    if (gesture == null) {
                        Log.e(TAG, "Preview not supported for gesture: " + mRichGestureMode);
                        return;
                    }
                    performGesture(gesture, true /* isPreview */);
                }
                break;
            case MotionEvent.ACTION_UP:
                if (areRichGesturesEnabled()) {
                    gesture = computeGesture(event, false /* isPreview */);
                    if (gesture == null) {
                        // This shouldn't happen
                        Log.e(TAG, "Unrecognized gesture mode: " + mRichGestureMode);
                        return;
                    }
                    performGesture(gesture, false /* isPreview */);
                } else {
                    // insert random ASCII char
                    sendKeyChar((char) (56 + new Random().nextInt(66)));
                }
                return;
            case MotionEvent.ACTION_DOWN: {
                if (areRichGesturesEnabled()) {
                    mRichGestureStartPoint = new PointF(event.getX(), event.getY());
                }
            }
        }
    }

    private HandwritingGesture computeGesture(MotionEvent event, boolean isPreview) {
        HandwritingGesture gesture = null;
        switch (mRichGestureMode) {
            case OP_SELECT:
                gesture = new SelectGesture.Builder()
                        .setGranularity(mRichGestureGranularity)
                        .setSelectionArea(getSanitizedRectF(mRichGestureStartPoint.x,
                                mRichGestureStartPoint.y, event.getX(), event.getY()))
                        .setFallbackText("fallback text")
                        .build();
                break;
            case OP_DELETE:
                gesture = new DeleteGesture.Builder()
                        .setGranularity(mRichGestureGranularity)
                        .setDeletionArea(getSanitizedRectF(mRichGestureStartPoint.x,
                                mRichGestureStartPoint.y, event.getX(), event.getY()))
                        .setFallbackText("fallback text")
                        .build();
                break;
            case OP_INSERT:
                gesture = new InsertGesture.Builder()
                        .setInsertionPoint(new PointF(
                                mRichGestureStartPoint.x, mRichGestureStartPoint.y))
                        .setTextToInsert(" ")
                        .setFallbackText("fallback text")
                        .build();
                break;
            case OP_REMOVE_SPACE:
                if (isPreview) {
                    break;
                }
                gesture = new RemoveSpaceGesture.Builder()
                        .setPoints(
                                new PointF(mRichGestureStartPoint.x,
                                        mRichGestureStartPoint.y),
                                new PointF(event.getX(), event.getY()))
                        .setFallbackText("fallback text")
                        .build();
                break;
            case OP_JOIN_OR_SPLIT:
                if (isPreview) {
                    break;
                }
                gesture = new JoinOrSplitGesture.Builder()
                        .setJoinOrSplitPoint(new PointF(
                                mRichGestureStartPoint.x, mRichGestureStartPoint.y))
                        .setFallbackText("fallback text")
                        .build();
                break;
            case OP_INSERT_MODE:
                if (isPreview) {
                    break;
                }
                mCancellationSignal = new CancellationSignal();
                InsertModeGesture img = new InsertModeGesture.Builder()
                        .setInsertionPoint(new PointF(
                                mRichGestureStartPoint.x, mRichGestureStartPoint.y))
                        .setFallbackText("fallback text")
                        .setCancellationSignal(mCancellationSignal)
                        .build();
                gesture = img;
                new Handler().postDelayed(() -> img.getCancellationSignal().cancel(), 5000);
                break;
        }
        return gesture;
    }

    /**
     * sanitize values to support rectangles in all cases.
     */
    private RectF getSanitizedRectF(float left, float top, float right, float bottom) {
        // swap values when left > right OR top > bottom.
        if (left > right) {
            float temp = left;
            left = right;
            right = temp;
        }
        if (top > bottom) {
            float temp = top;
            top = bottom;
            bottom = temp;
        }
        // increment by a pixel so that RectF.isEmpty() isn't true.
        if (left == right) {
            right++;
        }
        if (top == bottom) {
            bottom++;
        }

        RectF rectF = new RectF(left, top, right, bottom);
        Log.d(TAG, "Sending RichGesture " + rectF.toShortString());
        return rectF;
    }

    private void performGesture(HandwritingGesture gesture, boolean isPreview) {
        InputConnection ic = getCurrentInputConnection();
        if (getCurrentInputStarted() && ic != null) {
            if (isPreview) {
                ic.previewHandwritingGesture((PreviewableHandwritingGesture) gesture, null);
            } else {
                ic.performHandwritingGesture(gesture, Runnable::run, mResultConsumer);
            }
        } else {
            // This shouldn't happen
            Log.e(TAG, "No active InputConnection");
        }
    }

    @Override
    public View onCreateInputView() {
        Log.d(TAG, "onCreateInputView");
        final ViewGroup view = new FrameLayout(this);
        view.setPadding(0, 0, 0, 0);

        LinearLayout layout = new LinearLayout(this);
        layout.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(getRichGestureActionsSpinner());
        layout.addView(getRichGestureGranularitySpinner());
        layout.addView(getBoundsInfoCheckBoxes());
        layout.addView(getPreviewCheckBox());
        layout.setBackgroundColor(getColor(R.color.holo_green_light));
        view.addView(layout);

        return view;
    }

    private View getPreviewCheckBox() {
        mGesturePreviewCheckbox = new CheckBox(this);
        mGesturePreviewCheckbox.setText("Use Gesture Previews (for Previewable Gestures)");
        mGesturePreviewCheckbox.setOnCheckedChangeListener(
                (buttonView, isChecked) -> mUsePreview = isChecked);
        return mGesturePreviewCheckbox;
    }

    private View getRichGestureActionsSpinner() {
        if (mRichGestureModeSpinner != null) {
            return mRichGestureModeSpinner;
        }
        mRichGestureModeSpinner = new Spinner(this);
        mRichGestureModeSpinner.setPadding(100, 0, 100, 0);
        mRichGestureModeSpinner.setTooltipText("Handwriting IME mode");
        String[] items = new String[]{
                "Handwriting IME - Rich gesture disabled",
                "Rich gesture SELECT",
                "Rich gesture DELETE",
                "Rich gesture INSERT",
                "Rich gesture REMOVE SPACE",
                "Rich gesture JOIN OR SPLIT",
                "Rich gesture INSERT MODE",
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mRichGestureModeSpinner.setAdapter(adapter);
        mRichGestureModeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                mRichGestureMode = position;
                boolean supportsGranularityAndPreview =
                        mRichGestureMode == OP_SELECT || mRichGestureMode == OP_DELETE;
                mRichGestureGranularitySpinner.setEnabled(supportsGranularityAndPreview);
                mGesturePreviewCheckbox.setEnabled(supportsGranularityAndPreview);
                if (!supportsGranularityAndPreview) {
                    mUsePreview = false;
                }
                Log.d(TAG, "Setting RichGesture Mode " + mRichGestureMode);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                mRichGestureMode = OP_NONE;
                mRichGestureGranularitySpinner.setEnabled(false);
            }
        });
        mRichGestureModeSpinner.setSelection(0); // default disabled
        return mRichGestureModeSpinner;
    }

    private void updateCursorAnchorInfo(int boundsInfoMode) {
        final InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        if (boundsInfoMode == BOUNDS_INFO_NONE) {
            ic.requestCursorUpdates(0);
            return;
        }

        final int cursorUpdateMode = InputConnection.CURSOR_UPDATE_MONITOR;
        int cursorUpdateFilter = 0;
        if ((boundsInfoMode & BOUNDS_INFO_EDITOR_BOUNDS) != 0) {
            cursorUpdateFilter |= InputConnection.CURSOR_UPDATE_FILTER_EDITOR_BOUNDS;
        }

        if ((boundsInfoMode & BOUNDS_INFO_VISIBLE_LINE_BOUNDS) != 0) {
            cursorUpdateFilter |= InputConnection.CURSOR_UPDATE_FILTER_VISIBLE_LINE_BOUNDS;
        }
        ic.requestCursorUpdates(cursorUpdateMode | cursorUpdateFilter);
    }

    private void updateBoundsInfoMode() {
        if (mInk != null) {
            mInk.setBoundsInfoMode(mBoundsInfoMode);
        }
        updateCursorAnchorInfo(mBoundsInfoMode);
    }

    private View getBoundsInfoCheckBoxes() {
        if (mBoundsInfoCheckBoxes != null) {
            return mBoundsInfoCheckBoxes;
        }
        mBoundsInfoCheckBoxes = new LinearLayout(this);
        mBoundsInfoCheckBoxes.setPadding(100, 0, 100, 0);
        mBoundsInfoCheckBoxes.setOrientation(LinearLayout.HORIZONTAL);

        final CheckBox editorBoundsInfoCheckBox = new CheckBox(this);
        editorBoundsInfoCheckBox.setText("EditorBoundsInfo");
        editorBoundsInfoCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                mBoundsInfoMode |= BOUNDS_INFO_EDITOR_BOUNDS;
            } else {
                mBoundsInfoMode &= ~BOUNDS_INFO_EDITOR_BOUNDS;
            }
            updateBoundsInfoMode();
        });

        final CheckBox visibleLineBoundsInfoCheckBox = new CheckBox(this);
        visibleLineBoundsInfoCheckBox.setText("VisibleLineBounds");
        visibleLineBoundsInfoCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                mBoundsInfoMode |= BOUNDS_INFO_VISIBLE_LINE_BOUNDS;
            } else {
                mBoundsInfoMode &= ~BOUNDS_INFO_VISIBLE_LINE_BOUNDS;
            }
            updateBoundsInfoMode();
        });

        mBoundsInfoCheckBoxes.addView(editorBoundsInfoCheckBox);
        mBoundsInfoCheckBoxes.addView(visibleLineBoundsInfoCheckBox);
        return mBoundsInfoCheckBoxes;
    }

    private View getRichGestureGranularitySpinner() {
        if (mRichGestureGranularitySpinner != null) {
            return mRichGestureGranularitySpinner;
        }
        mRichGestureGranularitySpinner = new Spinner(this);
        mRichGestureGranularitySpinner.setPadding(100, 0, 100, 0);
        mRichGestureGranularitySpinner.setTooltipText(" Granularity");
        String[] items =
                new String[] { "Granularity - UNDEFINED",
                        "Granularity - WORD", "Granularity - CHARACTER"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mRichGestureGranularitySpinner.setAdapter(adapter);
        mRichGestureGranularitySpinner.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                mRichGestureGranularity = position;
                Log.d(TAG, "Setting RichGesture Granularity " + mRichGestureGranularity);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                mRichGestureGranularity = 0;
            }
        });
        mRichGestureGranularitySpinner.setSelection(1);
        return mRichGestureGranularitySpinner;
    }

    public void onPrepareStylusHandwriting() {
        Log.d(TAG, "onPrepareStylusHandwriting ");
        if (mInk == null) {
            mInk = new InkView(this, new HandwritingFinisherImpl(), new StylusConsumer());
            mInk.setBoundsInfoMode(mBoundsInfoMode);
        }
    }

    @Override
    public boolean onStartStylusHandwriting() {
        Log.d(TAG, "onStartStylusHandwriting ");
        Toast.makeText(this, "START HW", Toast.LENGTH_SHORT).show();
        Window inkWindow = getStylusHandwritingWindow();
        inkWindow.setContentView(mInk, mInk.getLayoutParams());
        return true;
    }

    @Override
    public void onFinishStylusHandwriting() {
        Log.d(TAG, "onFinishStylusHandwriting ");
        Toast.makeText(this, "Finish HW", Toast.LENGTH_SHORT).show();
        // Free-up
        ((ViewGroup) mInk.getParent()).removeView(mInk);
        mInk = null;
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    private boolean areRichGesturesEnabled() {
        return mRichGestureMode != OP_NONE;
    }

    @Override
    public void onUpdateCursorAnchorInfo(CursorAnchorInfo cursorAnchorInfo) {
        if (mInk != null) {
            mInk.setCursorAnchorInfo(cursorAnchorInfo);
        }
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        updateCursorAnchorInfo(mBoundsInfoMode);
    }
}
