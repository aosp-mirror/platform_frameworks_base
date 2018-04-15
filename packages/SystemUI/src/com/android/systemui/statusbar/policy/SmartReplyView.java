package com.android.systemui.statusbar.policy;

import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.text.Layout;
import android.text.TextPaint;
import android.text.method.TransformationMethod;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import com.android.internal.annotations.VisibleForTesting;
import com.android.keyguard.KeyguardHostView.OnDismissAction;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.SmartReplyLogger;
import com.android.systemui.statusbar.notification.NotificationUtils;
import com.android.systemui.statusbar.phone.KeyguardDismissUtil;

import java.text.BreakIterator;
import java.util.Comparator;
import java.util.PriorityQueue;

/** View which displays smart reply buttons in notifications. */
public class SmartReplyView extends ViewGroup {

    private static final String TAG = "SmartReplyView";

    private static final int MEASURE_SPEC_ANY_WIDTH =
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);

    private static final Comparator<View> DECREASING_MEASURED_WIDTH_WITHOUT_PADDING_COMPARATOR =
            (v1, v2) -> ((v2.getMeasuredWidth() - v2.getPaddingLeft() - v2.getPaddingRight())
                    - (v1.getMeasuredWidth() - v1.getPaddingLeft() - v1.getPaddingRight()));

    private static final int SQUEEZE_FAILED = -1;

    private final SmartReplyConstants mConstants;
    private final KeyguardDismissUtil mKeyguardDismissUtil;

    /**
     * The upper bound for the height of this view in pixels. Notifications are automatically
     * recreated on density or font size changes so caching this should be fine.
     */
    private final int mHeightUpperLimit;

    /** Spacing to be applied between views. */
    private final int mSpacing;

    /** Horizontal padding of smart reply buttons if all of them use only one line of text. */
    private final int mSingleLineButtonPaddingHorizontal;

    /** Horizontal padding of smart reply buttons if at least one of them uses two lines of text. */
    private final int mDoubleLineButtonPaddingHorizontal;

    /** Increase in width of a smart reply button as a result of using two lines instead of one. */
    private final int mSingleToDoubleLineButtonWidthIncrease;

    private final BreakIterator mBreakIterator;

    private PriorityQueue<Button> mCandidateButtonQueueForSqueezing;

    public SmartReplyView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mConstants = Dependency.get(SmartReplyConstants.class);
        mKeyguardDismissUtil = Dependency.get(KeyguardDismissUtil.class);

        mHeightUpperLimit = NotificationUtils.getFontScaledHeight(mContext,
            R.dimen.smart_reply_button_max_height);

        int spacing = 0;
        int singleLineButtonPaddingHorizontal = 0;
        int doubleLineButtonPaddingHorizontal = 0;

        final TypedArray arr = context.obtainStyledAttributes(attrs, R.styleable.SmartReplyView,
                0, 0);
        final int length = arr.getIndexCount();
        for (int i = 0; i < length; i++) {
            int attr = arr.getIndex(i);
            switch (attr) {
                case R.styleable.SmartReplyView_spacing:
                    spacing = arr.getDimensionPixelSize(i, 0);
                    break;
                case R.styleable.SmartReplyView_singleLineButtonPaddingHorizontal:
                    singleLineButtonPaddingHorizontal = arr.getDimensionPixelSize(i, 0);
                    break;
                case R.styleable.SmartReplyView_doubleLineButtonPaddingHorizontal:
                    doubleLineButtonPaddingHorizontal = arr.getDimensionPixelSize(i, 0);
                    break;
            }
        }
        arr.recycle();

        mSpacing = spacing;
        mSingleLineButtonPaddingHorizontal = singleLineButtonPaddingHorizontal;
        mDoubleLineButtonPaddingHorizontal = doubleLineButtonPaddingHorizontal;
        mSingleToDoubleLineButtonWidthIncrease =
                2 * (doubleLineButtonPaddingHorizontal - singleLineButtonPaddingHorizontal);


        mBreakIterator = BreakIterator.getLineInstance();
        reallocateCandidateButtonQueueForSqueezing();
    }

    /**
     * Returns an upper bound for the height of this view in pixels. This method is intended to be
     * invoked before onMeasure, so it doesn't do any analysis on the contents of the buttons.
     */
    public int getHeightUpperLimit() {
       return mHeightUpperLimit;
    }

    private void reallocateCandidateButtonQueueForSqueezing() {
        // Instead of clearing the priority queue, we re-allocate so that it would fit all buttons
        // exactly. This avoids (1) wasting memory because PriorityQueue never shrinks and
        // (2) growing in onMeasure.
        // The constructor throws an IllegalArgument exception if initial capacity is less than 1.
        mCandidateButtonQueueForSqueezing = new PriorityQueue<>(
                Math.max(getChildCount(), 1), DECREASING_MEASURED_WIDTH_WITHOUT_PADDING_COMPARATOR);
    }

    public void setRepliesFromRemoteInput(RemoteInput remoteInput, PendingIntent pendingIntent,
            SmartReplyLogger smartReplyLogger, NotificationData.Entry entry) {
        removeAllViews();
        if (remoteInput != null && pendingIntent != null) {
            CharSequence[] choices = remoteInput.getChoices();
            if (choices != null) {
                for (int i = 0; i < choices.length; ++i) {
                    Button replyButton = inflateReplyButton(
                            getContext(), this, i, choices[i], remoteInput, pendingIntent,
                            smartReplyLogger, entry);
                    addView(replyButton);
                }
            }
        }
        reallocateCandidateButtonQueueForSqueezing();
    }

    public static SmartReplyView inflate(Context context, ViewGroup root) {
        return (SmartReplyView)
                LayoutInflater.from(context).inflate(R.layout.smart_reply_view, root, false);
    }

    @VisibleForTesting
    Button inflateReplyButton(Context context, ViewGroup root, int replyIndex,
            CharSequence choice, RemoteInput remoteInput, PendingIntent pendingIntent,
            SmartReplyLogger smartReplyLogger, NotificationData.Entry entry) {
        Button b = (Button) LayoutInflater.from(context).inflate(
                R.layout.smart_reply_button, root, false);
        b.setText(choice);

        OnDismissAction action = () -> {
            Bundle results = new Bundle();
            results.putString(remoteInput.getResultKey(), choice.toString());
            Intent intent = new Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            RemoteInput.addResultsToIntent(new RemoteInput[]{remoteInput}, intent, results);
            RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_CHOICE);
            try {
                pendingIntent.send(context, 0, intent);
            } catch (PendingIntent.CanceledException e) {
                Log.w(TAG, "Unable to send smart reply", e);
            }
            smartReplyLogger.smartReplySent(entry, replyIndex);
            return false; // do not defer
        };

        b.setOnClickListener(view -> {
            mKeyguardDismissUtil.dismissKeyguardThenExecute(
                    action, null /* cancelAction */, false /* afterKeyguardGone */);
        });
        return b;
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new LayoutParams(mContext, attrs);
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    @Override
    protected ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams params) {
        return new LayoutParams(params.width, params.height);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int targetWidth = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
                ? Integer.MAX_VALUE : MeasureSpec.getSize(widthMeasureSpec);

        // Mark all buttons as hidden and un-squeezed.
        resetButtonsLayoutParams();

        if (!mCandidateButtonQueueForSqueezing.isEmpty()) {
            Log.wtf(TAG, "Single line button queue leaked between onMeasure calls");
            mCandidateButtonQueueForSqueezing.clear();
        }

        int measuredWidth = mPaddingLeft + mPaddingRight;
        int maxChildHeight = 0;
        int displayedChildCount = 0;
        int buttonPaddingHorizontal = mSingleLineButtonPaddingHorizontal;

        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();
            if (child.getVisibility() != View.VISIBLE || !(child instanceof Button)) {
                continue;
            }

            child.setPadding(buttonPaddingHorizontal, child.getPaddingTop(),
                    buttonPaddingHorizontal, child.getPaddingBottom());
            child.measure(MEASURE_SPEC_ANY_WIDTH, heightMeasureSpec);

            final int lineCount = ((Button) child).getLineCount();
            if (lineCount < 1 || lineCount > 2) {
                // If smart reply has no text, or more than two lines, then don't show it.
                continue;
            }

            if (lineCount == 1) {
                mCandidateButtonQueueForSqueezing.add((Button) child);
            }

            // Remember the current measurements in case the current button doesn't fit in.
            final int originalMaxChildHeight = maxChildHeight;
            final int originalMeasuredWidth = measuredWidth;
            final int originalButtonPaddingHorizontal = buttonPaddingHorizontal;

            final int spacing = displayedChildCount == 0 ? 0 : mSpacing;
            final int childWidth = child.getMeasuredWidth();
            final int childHeight = child.getMeasuredHeight();
            measuredWidth += spacing + childWidth;
            maxChildHeight = Math.max(maxChildHeight, childHeight);

            // Do we need to increase the number of lines in smart reply buttons to two?
            final boolean increaseToTwoLines =
                    buttonPaddingHorizontal == mSingleLineButtonPaddingHorizontal
                            && (lineCount == 2 || measuredWidth > targetWidth);
            if (increaseToTwoLines) {
                measuredWidth += (displayedChildCount + 1) * mSingleToDoubleLineButtonWidthIncrease;
                buttonPaddingHorizontal = mDoubleLineButtonPaddingHorizontal;
            }

            // If the last button doesn't fit into the remaining width, try squeezing preceding
            // smart reply buttons.
            if (measuredWidth > targetWidth) {
                // Keep squeezing preceding and current smart reply buttons until they all fit.
                while (measuredWidth > targetWidth
                        && !mCandidateButtonQueueForSqueezing.isEmpty()) {
                    final Button candidate = mCandidateButtonQueueForSqueezing.poll();
                    final int squeezeReduction = squeezeButton(candidate, heightMeasureSpec);
                    if (squeezeReduction != SQUEEZE_FAILED) {
                        maxChildHeight = Math.max(maxChildHeight, candidate.getMeasuredHeight());
                        measuredWidth -= squeezeReduction;
                    }
                }

                // If the current button still doesn't fit after squeezing all buttons, undo the
                // last squeezing round.
                if (measuredWidth > targetWidth) {
                    measuredWidth = originalMeasuredWidth;
                    maxChildHeight = originalMaxChildHeight;
                    buttonPaddingHorizontal = originalButtonPaddingHorizontal;

                    // Mark all buttons from the last squeezing round as "failed to squeeze", so
                    // that they're re-measured without squeezing later.
                    markButtonsWithPendingSqueezeStatusAs(LayoutParams.SQUEEZE_STATUS_FAILED, i);

                    // The current button doesn't fit, so there's no point in measuring further
                    // buttons.
                    break;
                }

                // The current button fits, so mark all squeezed buttons as "successfully squeezed"
                // to prevent them from being un-squeezed in a subsequent squeezing round.
                markButtonsWithPendingSqueezeStatusAs(LayoutParams.SQUEEZE_STATUS_SUCCESSFUL, i);
            }

            lp.show = true;
            displayedChildCount++;
        }

        // We're done squeezing buttons, so we can clear the priority queue.
        mCandidateButtonQueueForSqueezing.clear();

        // Finally, we need to re-measure some buttons.
        remeasureButtonsIfNecessary(buttonPaddingHorizontal, maxChildHeight);

        setMeasuredDimension(
                resolveSize(Math.max(getSuggestedMinimumWidth(), measuredWidth), widthMeasureSpec),
                resolveSize(Math.max(getSuggestedMinimumHeight(),
                        mPaddingTop + maxChildHeight + mPaddingBottom), heightMeasureSpec));
    }

    private void resetButtonsLayoutParams() {
        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();
            lp.show = false;
            lp.squeezeStatus = LayoutParams.SQUEEZE_STATUS_NONE;
        }
    }

    private int squeezeButton(Button button, int heightMeasureSpec) {
        final int estimatedOptimalTextWidth = estimateOptimalSqueezedButtonTextWidth(button);
        if (estimatedOptimalTextWidth == SQUEEZE_FAILED) {
            return SQUEEZE_FAILED;
        }
        return squeezeButtonToTextWidth(button, heightMeasureSpec, estimatedOptimalTextWidth);
    }

    private int estimateOptimalSqueezedButtonTextWidth(Button button) {
        // Find a line-break point in the middle of the smart reply button text.
        final String rawText = button.getText().toString();

        // The button sometimes has a transformation affecting text layout (e.g. all caps).
        final TransformationMethod transformation = button.getTransformationMethod();
        final String text = transformation == null ?
                rawText : transformation.getTransformation(rawText, button).toString();
        final int length = text.length();
        mBreakIterator.setText(text);

        if (mBreakIterator.preceding(length / 2) == BreakIterator.DONE) {
            if (mBreakIterator.next() == BreakIterator.DONE) {
                // Can't find a single possible line break in either direction.
                return SQUEEZE_FAILED;
            }
        }

        final TextPaint paint = button.getPaint();
        final int initialPosition = mBreakIterator.current();
        final float initialLeftTextWidth = Layout.getDesiredWidth(text, 0, initialPosition, paint);
        final float initialRightTextWidth =
                Layout.getDesiredWidth(text, initialPosition, length, paint);
        float optimalTextWidth = Math.max(initialLeftTextWidth, initialRightTextWidth);

        if (initialLeftTextWidth != initialRightTextWidth) {
            // See if there's a better line-break point (leading to a more narrow button) in
            // either left or right direction.
            final boolean moveLeft = initialLeftTextWidth > initialRightTextWidth;
            final int maxSqueezeRemeasureAttempts = mConstants.getMaxSqueezeRemeasureAttempts();
            for (int i = 0; i < maxSqueezeRemeasureAttempts; i++) {
                final int newPosition =
                        moveLeft ? mBreakIterator.previous() : mBreakIterator.next();
                if (newPosition == BreakIterator.DONE) {
                    break;
                }

                final float newLeftTextWidth = Layout.getDesiredWidth(text, 0, newPosition, paint);
                final float newRightTextWidth =
                        Layout.getDesiredWidth(text, newPosition, length, paint);
                final float newOptimalTextWidth = Math.max(newLeftTextWidth, newRightTextWidth);
                if (newOptimalTextWidth < optimalTextWidth) {
                    optimalTextWidth = newOptimalTextWidth;
                } else {
                    break;
                }

                boolean tooFar = moveLeft
                        ? newLeftTextWidth <= newRightTextWidth
                        : newLeftTextWidth >= newRightTextWidth;
                if (tooFar) {
                    break;
                }
            }
        }

        return (int) Math.ceil(optimalTextWidth);
    }

    private int squeezeButtonToTextWidth(Button button, int heightMeasureSpec, int textWidth) {
        int oldWidth = button.getMeasuredWidth();
        if (button.getPaddingLeft() != mDoubleLineButtonPaddingHorizontal) {
            // Correct for the fact that the button was laid out with single-line horizontal
            // padding.
            oldWidth += mSingleToDoubleLineButtonWidthIncrease;
        }

        // Re-measure the squeezed smart reply button.
        button.setPadding(mDoubleLineButtonPaddingHorizontal, button.getPaddingTop(),
                mDoubleLineButtonPaddingHorizontal, button.getPaddingBottom());
        final int widthMeasureSpec = MeasureSpec.makeMeasureSpec(
                2 * mDoubleLineButtonPaddingHorizontal + textWidth, MeasureSpec.AT_MOST);
        button.measure(widthMeasureSpec, heightMeasureSpec);

        final int newWidth = button.getMeasuredWidth();

        final LayoutParams lp = (LayoutParams) button.getLayoutParams();
        if (button.getLineCount() > 2 || newWidth >= oldWidth) {
            lp.squeezeStatus = LayoutParams.SQUEEZE_STATUS_FAILED;
            return SQUEEZE_FAILED;
        } else {
            lp.squeezeStatus = LayoutParams.SQUEEZE_STATUS_PENDING;
            return oldWidth - newWidth;
        }
    }

    private void remeasureButtonsIfNecessary(
            int buttonPaddingHorizontal, int maxChildHeight) {
        final int maxChildHeightMeasure =
                MeasureSpec.makeMeasureSpec(maxChildHeight, MeasureSpec.EXACTLY);

        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();
            if (!lp.show) {
                continue;
            }

            boolean requiresNewMeasure = false;
            int newWidth = child.getMeasuredWidth();

            // Re-measure reason 1: The button needs to be un-squeezed (either because it resulted
            // in more than two lines or because it was unnecessary).
            if (lp.squeezeStatus == LayoutParams.SQUEEZE_STATUS_FAILED) {
                requiresNewMeasure = true;
                newWidth = Integer.MAX_VALUE;
            }

            // Re-measure reason 2: The button's horizontal padding is incorrect (because it was
            // measured with the wrong number of lines).
            if (child.getPaddingLeft() != buttonPaddingHorizontal) {
                requiresNewMeasure = true;
                if (buttonPaddingHorizontal == mSingleLineButtonPaddingHorizontal) {
                    // Decrease padding (2->1 line).
                    newWidth -= mSingleToDoubleLineButtonWidthIncrease;
                } else {
                    // Increase padding (1->2 lines).
                    newWidth += mSingleToDoubleLineButtonWidthIncrease;
                }
                child.setPadding(buttonPaddingHorizontal, child.getPaddingTop(),
                        buttonPaddingHorizontal, child.getPaddingBottom());
            }

            // Re-measure reason 3: The button's height is less than the max height of all buttons
            // (all should have the same height).
            if (child.getMeasuredHeight() != maxChildHeight) {
                requiresNewMeasure = true;
            }

            if (requiresNewMeasure) {
                child.measure(MeasureSpec.makeMeasureSpec(newWidth, MeasureSpec.AT_MOST),
                        maxChildHeightMeasure);
            }
        }
    }

    private void markButtonsWithPendingSqueezeStatusAs(int squeezeStatus, int maxChildIndex) {
        for (int i = 0; i <= maxChildIndex; i++) {
            final View child = getChildAt(i);
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();
            if (lp.squeezeStatus == LayoutParams.SQUEEZE_STATUS_PENDING) {
                lp.squeezeStatus = squeezeStatus;
            }
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        final boolean isRtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;

        final int width = right - left;
        int position = isRtl ? width - mPaddingRight : mPaddingLeft;

        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();
            if (!lp.show) {
                continue;
            }

            final int childWidth = child.getMeasuredWidth();
            final int childHeight = child.getMeasuredHeight();
            final int childLeft = isRtl ? position - childWidth : position;
            child.layout(childLeft, 0, childLeft + childWidth, childHeight);

            final int childWidthWithSpacing = childWidth + mSpacing;
            if (isRtl) {
                position -= childWidthWithSpacing;
            } else {
                position += childWidthWithSpacing;
            }
        }
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        final LayoutParams lp = (LayoutParams) child.getLayoutParams();
        return lp.show && super.drawChild(canvas, child, drawingTime);
    }

    @VisibleForTesting
    static class LayoutParams extends ViewGroup.LayoutParams {

        /** Button is not squeezed. */
        private static final int SQUEEZE_STATUS_NONE = 0;

        /**
         * Button was successfully squeezed, but it might be un-squeezed later if the squeezing
         * turns out to have been unnecessary (because there's still not enough space to add another
         * button).
         */
        private static final int SQUEEZE_STATUS_PENDING = 1;

        /** Button was successfully squeezed and it won't be un-squeezed. */
        private static final int SQUEEZE_STATUS_SUCCESSFUL = 2;

        /**
         * Button wasn't successfully squeezed. The squeezing resulted in more than two lines of
         * text or it didn't reduce the button's width at all. The button will have to be
         * re-measured to use only one line of text.
         */
        private static final int SQUEEZE_STATUS_FAILED = 3;

        private boolean show = false;
        private int squeezeStatus = SQUEEZE_STATUS_NONE;

        private LayoutParams(Context c, AttributeSet attrs) {
            super(c, attrs);
        }

        private LayoutParams(int width, int height) {
            super(width, height);
        }

        @VisibleForTesting
        boolean isShown() {
            return show;
        }
    }
}
