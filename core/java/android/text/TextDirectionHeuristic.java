// Copyright 2011 Google Inc. All Rights Reserved.

package android.text;

/**
 * Interface for objects that guess at the paragraph direction by examining text.
 *
 * @hide
 */
public interface TextDirectionHeuristic {
    /** @hide */ boolean isRtl(CharSequence text, int start, int end);
    /** @hide */ boolean isRtl(char[] text, int start, int count);
}
