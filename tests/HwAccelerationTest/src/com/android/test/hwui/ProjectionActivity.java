package com.android.test.hwui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;

import android.app.Activity;
import android.util.AttributeSet;
import android.view.RenderNode;
import android.view.View;
import android.widget.LinearLayout;

public class ProjectionActivity extends Activity {
    /**
     * The content from this view should be projected in between the background of the
     * ProjecteeLayout and its children, unclipped.
     *
     * This view doesn't clip to its bounds (because its parent has clipChildren=false) so that
     * when it is projected onto the ProjecteeLayout, it draws outside its view bounds.
     */
    public static class ProjectedView extends View {
        private final Paint mPaint = new Paint();
        private final RectF mRectF = new RectF();

        public ProjectedView(Context context) {
            this(context, null);
        }

        public ProjectedView(Context context, AttributeSet attrs) {
            this(context, attrs, 0);
        }

        public ProjectedView(Context context, AttributeSet attrs, int defStyleAttr) {
            super(context, attrs, defStyleAttr);

            setOnClickListener(new OnClickListener() {
                boolean toggle = false;
                @Override
                public void onClick(View v) {
                    toggle = !toggle;
                    setProject(toggle);
                }
            });
        }

        private void setProject(boolean value) {
            RenderNode renderNode = updateDisplayListIfDirty();
            if (renderNode != null) {
                renderNode.setProjectBackwards(value);
            }
            // NOTE: we can't invalidate ProjectedView for the redraw because:
            // 1) the view won't preserve displayList properties that it doesn't know about
            // 2) the damage rect won't be big enough

            // instead, twiddle properties on the container, so that enough area of the screen is
            // redrawn without rerecording any DisplayLists.
            container.setTranslationX(100f);
            container.setTranslationX(0.0f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            // TODO: set projection flag
            final int w = getWidth();
            final int h = getHeight();
            mRectF.set(0, -h, w, 2 * h);
            mPaint.setAntiAlias(true);
            mPaint.setColor(0x5f00ff00);
            canvas.drawOval(mRectF, mPaint);
        }
    }

    static View container;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.projection);
        container = findViewById(R.id.container);
    }
}
