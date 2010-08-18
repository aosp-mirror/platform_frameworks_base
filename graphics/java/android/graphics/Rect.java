/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.graphics;

import android.os.Parcel;
import android.os.Parcelable;

import java.io.PrintWriter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rect holds four integer coordinates for a rectangle. The rectangle is
 * represented by the coordinates of its 4 edges (left, top, right bottom).
 * These fields can be accessed directly. Use width() and height() to retrieve
 * the rectangle's width and height. Note: most methods do not check to see that
 * the coordinates are sorted correctly (i.e. left <= right and top <= bottom).
 */
public final class Rect implements Parcelable {
    public int left;
    public int top;
    public int right;
    public int bottom;

    private static final Pattern FLATTENED_PATTERN = Pattern.compile(
            "(-?\\d+) (-?\\d+) (-?\\d+) (-?\\d+)");

    /**
     * Create a new empty Rect. All coordinates are initialized to 0.
     */
    public Rect() {}

    /**
     * Create a new rectangle with the specified coordinates. Note: no range
     * checking is performed, so the caller must ensure that left <= right and
     * top <= bottom.
     *
     * @param left   The X coordinate of the left side of the rectagle
     * @param top    The Y coordinate of the top of the rectangle
     * @param right  The X coordinate of the right side of the rectagle
     * @param bottom The Y coordinate of the bottom of the rectangle
     */
    public Rect(int left, int top, int right, int bottom) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    /**
     * Create a new rectangle, initialized with the values in the specified
     * rectangle (which is left unmodified).
     *
     * @param r The rectangle whose coordinates are copied into the new
     *          rectangle.
     */
    public Rect(Rect r) {
        left = r.left;
        top = r.top;
        right = r.right;
        bottom = r.bottom;
    }

    @Override
    public boolean equals(Object obj) {
        Rect r = (Rect) obj;
        if (r != null) {
            return left == r.left && top == r.top && right == r.right
                    && bottom == r.bottom;
        }
        return false;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(32);
        sb.append("Rect("); sb.append(left); sb.append(", ");
        sb.append(top); sb.append(" - "); sb.append(right);
        sb.append(", "); sb.append(bottom); sb.append(")");
        return sb.toString();
    }

    /**
     * Return a string representation of the rectangle in a compact form.
     */
    public String toShortString() {
        return toShortString(new StringBuilder(32));
    }
    
    /**
     * Return a string representation of the rectangle in a compact form.
     * @hide
     */
    public String toShortString(StringBuilder sb) {
        sb.setLength(0);
        sb.append('['); sb.append(left); sb.append(',');
        sb.append(top); sb.append("]["); sb.append(right);
        sb.append(','); sb.append(bottom); sb.append(']');
        return sb.toString();
    }

    /**
     * Return a string representation of the rectangle in a well-defined format.
     *
     * <p>You can later recover the Rect from this string through
     * {@link #unflattenFromString(String)}.
     * 
     * @return Returns a new String of the form "left top right bottom"
     */
    public String flattenToString() {
        StringBuilder sb = new StringBuilder(32);
        // WARNING: Do not change the format of this string, it must be
        // preserved because Rects are saved in this flattened format.
        sb.append(left);
        sb.append(' ');
        sb.append(top);
        sb.append(' ');
        sb.append(right);
        sb.append(' ');
        sb.append(bottom);
        return sb.toString();
    }

    /**
     * Returns a Rect from a string of the form returned by {@link #flattenToString},
     * or null if the string is not of that form.
     */
    public static Rect unflattenFromString(String str) {
        Matcher matcher = FLATTENED_PATTERN.matcher(str);
        if (!matcher.matches()) {
            return null;
        }
        return new Rect(Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)),
                Integer.parseInt(matcher.group(4)));
    }
    
    /**
     * Print short representation to given writer.
     * @hide
     */
    public void printShortString(PrintWriter pw) {
        pw.print('['); pw.print(left); pw.print(',');
        pw.print(top); pw.print("]["); pw.print(right);
        pw.print(','); pw.print(bottom); pw.print(']');
    }
    
    /**
     * Returns true if the rectangle is empty (left >= right or top >= bottom)
     */
    public final boolean isEmpty() {
        return left >= right || top >= bottom;
    }

    /**
     * @return the rectangle's width. This does not check for a valid rectangle
     * (i.e. left <= right) so the result may be negative.
     */
    public final int width() {
        return right - left;
    }

    /**
     * @return the rectangle's height. This does not check for a valid rectangle
     * (i.e. top <= bottom) so the result may be negative.
     */
    public final int height() {
        return bottom - top;
    }
    
    /**
     * @return the horizontal center of the rectangle. If the computed value
     *         is fractional, this method returns the largest integer that is
     *         less than the computed value.
     */
    public final int centerX() {
        return (left + right) >> 1;
    }
    
    /**
     * @return the vertical center of the rectangle. If the computed value
     *         is fractional, this method returns the largest integer that is
     *         less than the computed value.
     */
    public final int centerY() {
        return (top + bottom) >> 1;
    }
    
    /**
     * @return the exact horizontal center of the rectangle as a float.
     */
    public final float exactCenterX() {
        return (left + right) * 0.5f;
    }
    
    /**
     * @return the exact vertical center of the rectangle as a float.
     */
    public final float exactCenterY() {
        return (top + bottom) * 0.5f;
    }

    /**
     * Set the rectangle to (0,0,0,0)
     */
    public void setEmpty() {
        left = right = top = bottom = 0;
    }

    /**
     * Set the rectangle's coordinates to the specified values. Note: no range
     * checking is performed, so it is up to the caller to ensure that
     * left <= right and top <= bottom.
     *
     * @param left   The X coordinate of the left side of the rectagle
     * @param top    The Y coordinate of the top of the rectangle
     * @param right  The X coordinate of the right side of the rectagle
     * @param bottom The Y coordinate of the bottom of the rectangle
     */
    public void set(int left, int top, int right, int bottom) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    /**
     * Copy the coordinates from src into this rectangle.
     *
     * @param src The rectangle whose coordinates are copied into this
     *           rectangle.
     */
    public void set(Rect src) {
        this.left = src.left;
        this.top = src.top;
        this.right = src.right;
        this.bottom = src.bottom;
    }

    /**
     * Offset the rectangle by adding dx to its left and right coordinates, and
     * adding dy to its top and bottom coordinates.
     *
     * @param dx The amount to add to the rectangle's left and right coordinates
     * @param dy The amount to add to the rectangle's top and bottom coordinates
     */
    public void offset(int dx, int dy) {
        left += dx;
        top += dy;
        right += dx;
        bottom += dy;
    }

    /**
     * Offset the rectangle to a specific (left, top) position,
     * keeping its width and height the same.
     *
     * @param newLeft   The new "left" coordinate for the rectangle
     * @param newTop    The new "top" coordinate for the rectangle
     */
    public void offsetTo(int newLeft, int newTop) {
        right += newLeft - left;
        bottom += newTop - top;
        left = newLeft;
        top = newTop;
    }

    /**
     * Inset the rectangle by (dx,dy). If dx is positive, then the sides are
     * moved inwards, making the rectangle narrower. If dx is negative, then the
     * sides are moved outwards, making the rectangle wider. The same holds true
     * for dy and the top and bottom.
     *
     * @param dx The amount to add(subtract) from the rectangle's left(right)
     * @param dy The amount to add(subtract) from the rectangle's top(bottom)
     */
    public void inset(int dx, int dy) {
        left += dx;
        top += dy;
        right -= dx;
        bottom -= dy;
    }

    /**
     * Returns true if (x,y) is inside the rectangle. The left and top are
     * considered to be inside, while the right and bottom are not. This means
     * that for a x,y to be contained: left <= x < right and top <= y < bottom.
     * An empty rectangle never contains any point.
     *
     * @param x The X coordinate of the point being tested for containment
     * @param y The Y coordinate of the point being tested for containment
     * @return true iff (x,y) are contained by the rectangle, where containment
     *              means left <= x < right and top <= y < bottom
     */
    public boolean contains(int x, int y) {
        return left < right && top < bottom  // check for empty first
               && x >= left && x < right && y >= top && y < bottom;
    }

    /**
     * Returns true iff the 4 specified sides of a rectangle are inside or equal
     * to this rectangle. i.e. is this rectangle a superset of the specified
     * rectangle. An empty rectangle never contains another rectangle.
     *
     * @param left The left side of the rectangle being tested for containment
     * @param top The top of the rectangle being tested for containment
     * @param right The right side of the rectangle being tested for containment
     * @param bottom The bottom of the rectangle being tested for containment
     * @return true iff the the 4 specified sides of a rectangle are inside or
     *              equal to this rectangle
     */
    public boolean contains(int left, int top, int right, int bottom) {
               // check for empty first
        return this.left < this.right && this.top < this.bottom
               // now check for containment
                && this.left <= left && this.top <= top
                && this.right >= right && this.bottom >= bottom;
    }

    /**
     * Returns true iff the specified rectangle r is inside or equal to this
     * rectangle. An empty rectangle never contains another rectangle.
     *
     * @param r The rectangle being tested for containment.
     * @return true iff the specified rectangle r is inside or equal to this
     *              rectangle
     */
    public boolean contains(Rect r) {
               // check for empty first
        return this.left < this.right && this.top < this.bottom
               // now check for containment
               && left <= r.left && top <= r.top
               && right >= r.right && bottom >= r.bottom;
    }

    /**
     * If the rectangle specified by left,top,right,bottom intersects this
     * rectangle, return true and set this rectangle to that intersection,
     * otherwise return false and do not change this rectangle. No check is
     * performed to see if either rectangle is empty. Note: To just test for
     * intersection, use {@link #intersects(Rect, Rect)}.
     *
     * @param left The left side of the rectangle being intersected with this
     *             rectangle
     * @param top The top of the rectangle being intersected with this rectangle
     * @param right The right side of the rectangle being intersected with this
     *              rectangle.
     * @param bottom The bottom of the rectangle being intersected with this
     *             rectangle.
     * @return true if the specified rectangle and this rectangle intersect
     *              (and this rectangle is then set to that intersection) else
     *              return false and do not change this rectangle.
     */
    public boolean intersect(int left, int top, int right, int bottom) {
        if (this.left < right && left < this.right
                && this.top < bottom && top < this.bottom) {
            if (this.left < left) {
                this.left = left;
            }
            if (this.top < top) {
                this.top = top;
            }
            if (this.right > right) {
                this.right = right;
            }
            if (this.bottom > bottom) {
                this.bottom = bottom;
            }
            return true;
        }
        return false;
    }
    
    /**
     * If the specified rectangle intersects this rectangle, return true and set
     * this rectangle to that intersection, otherwise return false and do not
     * change this rectangle. No check is performed to see if either rectangle
     * is empty. To just test for intersection, use intersects()
     *
     * @param r The rectangle being intersected with this rectangle.
     * @return true if the specified rectangle and this rectangle intersect
     *              (and this rectangle is then set to that intersection) else
     *              return false and do not change this rectangle.
     */
    public boolean intersect(Rect r) {
        return intersect(r.left, r.top, r.right, r.bottom);
    }

    /**
     * If rectangles a and b intersect, return true and set this rectangle to
     * that intersection, otherwise return false and do not change this
     * rectangle. No check is performed to see if either rectangle is empty.
     * To just test for intersection, use intersects()
     *
     * @param a The first rectangle being intersected with
     * @param b The second rectangle being intersected with
     * @return true iff the two specified rectangles intersect. If they do, set
     *              this rectangle to that intersection. If they do not, return
     *              false and do not change this rectangle.
     */
    public boolean setIntersect(Rect a, Rect b) {
        if (a.left < b.right && b.left < a.right
                && a.top < b.bottom && b.top < a.bottom) {
            left = Math.max(a.left, b.left);
            top = Math.max(a.top, b.top);
            right = Math.min(a.right, b.right);
            bottom = Math.min(a.bottom, b.bottom);
            return true;
        }
        return false;
    }

    /**
     * Returns true if this rectangle intersects the specified rectangle.
     * In no event is this rectangle modified. No check is performed to see
     * if either rectangle is empty. To record the intersection, use intersect()
     * or setIntersect().
     *
     * @param left The left side of the rectangle being tested for intersection
     * @param top The top of the rectangle being tested for intersection
     * @param right The right side of the rectangle being tested for
     *              intersection
     * @param bottom The bottom of the rectangle being tested for intersection
     * @return true iff the specified rectangle intersects this rectangle. In
     *              no event is this rectangle modified.
     */
    public boolean intersects(int left, int top, int right, int bottom) {
        return this.left < right && left < this.right
               && this.top < bottom && top < this.bottom;
    }

    /**
     * Returns true iff the two specified rectangles intersect. In no event are
     * either of the rectangles modified. To record the intersection,
     * use {@link #intersect(Rect)} or {@link #setIntersect(Rect, Rect)}.
     *
     * @param a The first rectangle being tested for intersection
     * @param b The second rectangle being tested for intersection
     * @return true iff the two specified rectangles intersect. In no event are
     *              either of the rectangles modified.
     */
    public static boolean intersects(Rect a, Rect b) {
        return a.left < b.right && b.left < a.right
               && a.top < b.bottom && b.top < a.bottom;
    }

    /**
     * Update this Rect to enclose itself and the specified rectangle. If the
     * specified rectangle is empty, nothing is done. If this rectangle is empty
     * it is set to the specified rectangle.
     *
     * @param left The left edge being unioned with this rectangle
     * @param top The top edge being unioned with this rectangle
     * @param right The right edge being unioned with this rectangle
     * @param bottom The bottom edge being unioned with this rectangle
     */
    public void union(int left, int top, int right, int bottom) {
        if ((left < right) && (top < bottom)) {
            if ((this.left < this.right) && (this.top < this.bottom)) {
                if (this.left > left)
                    this.left = left;
                if (this.top > top)
                    this.top = top;
                if (this.right < right)
                    this.right = right;
                if (this.bottom < bottom)
                    this.bottom = bottom;
            } else {
                this.left = left;
                this.top = top;
                this.right = right;
                this.bottom = bottom;
            }
        }
    }

    /**
     * Update this Rect to enclose itself and the specified rectangle. If the
     * specified rectangle is empty, nothing is done. If this rectangle is empty
     * it is set to the specified rectangle.
     *
     * @param r The rectangle being unioned with this rectangle
     */
    public void union(Rect r) {
        union(r.left, r.top, r.right, r.bottom);
    }
    
    /**
     * Update this Rect to enclose itself and the [x,y] coordinate. There is no
     * check to see that this rectangle is non-empty.
     *
     * @param x The x coordinate of the point to add to the rectangle
     * @param y The y coordinate of the point to add to the rectangle
     */
    public void union(int x, int y) {
        if (x < left) {
            left = x;
        } else if (x > right) {
            right = x;
        }
        if (y < top) {
            top = y;
        } else if (y > bottom) {
            bottom = y;
        }
    }

    /**
     * Swap top/bottom or left/right if there are flipped (i.e. left > right
     * and/or top > bottom). This can be called if
     * the edges are computed separately, and may have crossed over each other.
     * If the edges are already correct (i.e. left <= right and top <= bottom)
     * then nothing is done.
     */
    public void sort() {
        if (left > right) {
            int temp = left;
            left = right;
            right = temp;
        }
        if (top > bottom) {
            int temp = top;
            top = bottom;
            bottom = temp;
        }
    }

    /**
     * Parcelable interface methods
     */
    public int describeContents() {
        return 0;
    }

    /**
     * Write this rectangle to the specified parcel. To restore a rectangle from
     * a parcel, use readFromParcel()
     * @param out The parcel to write the rectangle's coordinates into
     */
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(left);
        out.writeInt(top);
        out.writeInt(right);
        out.writeInt(bottom);
    }

    public static final Parcelable.Creator<Rect> CREATOR = new Parcelable.Creator<Rect>() {
        /**
         * Return a new rectangle from the data in the specified parcel.
         */
        public Rect createFromParcel(Parcel in) {
            Rect r = new Rect();
            r.readFromParcel(in);
            return r;
        }

        /**
         * Return an array of rectangles of the specified size.
         */
        public Rect[] newArray(int size) {
            return new Rect[size];
        }
    };

    /**
     * Set the rectangle's coordinates from the data stored in the specified
     * parcel. To write a rectangle to a parcel, call writeToParcel().
     *
     * @param in The parcel to read the rectangle's coordinates from
     */
    public void readFromParcel(Parcel in) {
        left = in.readInt();
        top = in.readInt();
        right = in.readInt();
        bottom = in.readInt();
    }

    /**
     * Scales up the rect by the given scale.
     * @hide
     */
    public void scale(float scale) {
        if (scale != 1.0f) {
            left = (int) (left * scale + 0.5f);
            top = (int) (top * scale + 0.5f);
            right = (int) (right * scale + 0.5f);
            bottom = (int) (bottom * scale + 0.5f);
        }
    }
}
