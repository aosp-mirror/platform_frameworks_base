/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.layoutlib.bridge.impl;

import android.graphics.Canvas;
import android.graphics.Region;

import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;

/**
 * Class representing a graphics context snapshot, as well as a context stack as a linked list.
 * <p>
 * This is based on top of {@link Graphics2D} but can operate independently if none are available
 * yet when setting transforms and clip information.
 *
 */
public class GcSnapshot {

    private final GcSnapshot mPrevious;
    private final int mFlags;

    private Graphics2D mGraphics2D = null;
    /** temp transform in case transformation are set before a Graphics2D exists */
    private AffineTransform mTransform = null;
    /** temp clip in case clipping is set before a Graphics2D exists */
    private Area mClip = null;

    /**
     * Creates a new {@link GcSnapshot} on top of another one.
     * <p/>
     * This is basically the equivalent of {@link Canvas#save(int)}
     * @param previous the previous snapshot head.
     * @param flags the flags regarding what should be saved.
     */
    public GcSnapshot(GcSnapshot previous, int flags) {
        assert previous != null;
        mPrevious = previous;
        mFlags = flags;
        mGraphics2D = (Graphics2D) previous.mGraphics2D.create();
    }

    /**
     * Creates the root snapshot.
     * {@link #setGraphics2D(Graphics2D)} will have to be called on it when possible.
     */
    public GcSnapshot() {
        mPrevious = null;
        mFlags = 0;
    }

    public void dispose() {
        if (mGraphics2D != null) {
            mGraphics2D.dispose();
        }

        if (mPrevious != null) {
            mPrevious.dispose();
        }
    }

    /**
     * Restores the top {@link GcSnapshot}, and returns the next one.
     */
    public GcSnapshot restore() {
        return doRestore();
    }

    /**
     * Restores the {@link GcSnapshot} to <var>saveCount</var>.
     * @param saveCount the saveCount or -1 to only restore 1.
     *
     * @return the new head of the Gc snapshot stack.
     */
    public GcSnapshot restoreTo(int saveCount) {
        return doRestoreTo(size(), saveCount);
    }

    public int size() {
        if (mPrevious != null) {
            return mPrevious.size() + 1;
        }

        return 1;
    }

    /**
     * Sets the Graphics2D object for this snapshot if it was created through {@link #GcSnapshot()}.
     * If any transform or clip information was set before, they are put into the Graphics object.
     * @param graphics2D the graphics object to set.
     */
    public void setGraphics2D(Graphics2D graphics2D) {
        mGraphics2D = graphics2D;
        if (mTransform != null) {
            mGraphics2D.setTransform(mTransform);
            mTransform = null;
        }

        if (mClip != null) {
            mGraphics2D.setClip(mClip);
            mClip = null;
        }
    }

    /**
     * Creates and return a copy of the current {@link Graphics2D}.
     * @return a new {@link Graphics2D}.
     */
    public Graphics2D create() {
        assert mGraphics2D != null;
        return (Graphics2D) mGraphics2D.create();
    }

    public void translate(float dx, float dy) {
        if (mGraphics2D != null) {
            mGraphics2D.translate(dx, dy);
        } else {
            if (mTransform == null) {
                mTransform = new AffineTransform();
            }
            mTransform.translate(dx, dy);
        }
    }

    public void rotate(double radians) {
        if (mGraphics2D != null) {
            mGraphics2D.rotate(radians);
        } else {
            if (mTransform == null) {
                mTransform = new AffineTransform();
            }
            mTransform.rotate(radians);
        }
    }

    public void scale(float sx, float sy) {
        if (mGraphics2D != null) {
            mGraphics2D.scale(sx, sy);
        } else {
            if (mTransform == null) {
                mTransform = new AffineTransform();
            }
            mTransform.scale(sx, sy);
        }
    }

    public AffineTransform getTransform() {
        if (mGraphics2D != null) {
            return mGraphics2D.getTransform();
        } else {
            if (mTransform == null) {
                mTransform = new AffineTransform();
            }
            return mTransform;
        }
    }

    public void setTransform(AffineTransform transform) {
        if (mGraphics2D != null) {
            mGraphics2D.setTransform(transform);
        } else {
            if (mTransform == null) {
                mTransform = new AffineTransform();
            }
            mTransform.setTransform(transform);
        }
    }

    public boolean clipRect(float left, float top, float right, float bottom, int regionOp) {
        if (mGraphics2D != null) {
            if (regionOp == Region.Op.DIFFERENCE.nativeInt) {
                Area newClip = new Area(mGraphics2D.getClip());
                newClip.subtract(new Area(
                        new Rectangle2D.Float(left, top, right - left, bottom - top)));
                mGraphics2D.setClip(newClip);

            } else if (regionOp == Region.Op.INTERSECT.nativeInt) {
                mGraphics2D.clipRect((int) left, (int) top,
                        (int) (right - left), (int) (bottom - top));

            } else if (regionOp == Region.Op.UNION.nativeInt) {
                Area newClip = new Area(mGraphics2D.getClip());
                newClip.add(new Area(
                        new Rectangle2D.Float(left, top, right - left, bottom - top)));
                mGraphics2D.setClip(newClip);

            } else if (regionOp == Region.Op.XOR.nativeInt) {
                Area newClip = new Area(mGraphics2D.getClip());
                newClip.exclusiveOr(new Area(
                        new Rectangle2D.Float(left, top, right - left, bottom - top)));
                mGraphics2D.setClip(newClip);

            } else if (regionOp == Region.Op.REVERSE_DIFFERENCE.nativeInt) {
                Area newClip = new Area(
                        new Rectangle2D.Float(left, top, right - left, bottom - top));
                newClip.subtract(new Area(mGraphics2D.getClip()));
                mGraphics2D.setClip(newClip);
            } else if (regionOp == Region.Op.REPLACE.nativeInt) {
                mGraphics2D.setClip((int) left, (int) top,
                        (int) (right - left), (int) (bottom - top));
            }

            return mGraphics2D.getClip().getBounds().isEmpty() == false;
        } else {
            if (mClip == null) {
                mClip = new Area();
            }

            if (regionOp == Region.Op.DIFFERENCE.nativeInt) {
                //FIXME
            } else if (regionOp == Region.Op.DIFFERENCE.nativeInt) {
            } else if (regionOp == Region.Op.INTERSECT.nativeInt) {
            } else if (regionOp == Region.Op.UNION.nativeInt) {
            } else if (regionOp == Region.Op.XOR.nativeInt) {
            } else if (regionOp == Region.Op.REVERSE_DIFFERENCE.nativeInt) {
            } else if (regionOp == Region.Op.REPLACE.nativeInt) {
            }

            return mClip.getBounds().isEmpty() == false;
        }
    }

    public Shape getClip() {
        if (mGraphics2D != null) {
            return mGraphics2D.getClip();
        } else {
            if (mClip == null) {
                mClip = new Area();
            }
            return mClip;
        }
    }

    private GcSnapshot doRestoreTo(int size, int saveCount) {
        if (size <= saveCount) {
            return this;
        }

        // restore the current one first.
        GcSnapshot previous = doRestore();

        if (size == saveCount + 1) { // this was the only one that needed restore.
            return previous;
        } else {
            return previous.doRestoreTo(size - 1, saveCount);
        }
    }

    private GcSnapshot doRestore() {
        // if this snapshot does not save everything, then set the previous snapshot
        // to this snapshot content
        if (mPrevious != null) {
            // didn't save the matrix? set the current matrix on the previous snapshot
            if ((mFlags & Canvas.MATRIX_SAVE_FLAG) == 0) {
                mPrevious.mGraphics2D.setTransform(getTransform());
            }

            // didn't save the clip? set the current clip on the previous snapshot
            if ((mFlags & Canvas.CLIP_SAVE_FLAG) == 0) {
                mPrevious.mGraphics2D.setClip(mGraphics2D.getClip());
            }
        }

        if (mGraphics2D != null) {
            mGraphics2D.dispose();
        }

        return mPrevious;
    }

}
