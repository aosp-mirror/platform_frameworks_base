/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.print.pdf;

import android.content.Context;
import android.graphics.Rect;
import android.print.PrintAttributes;
import android.print.PrintAttributes.Margins;
import android.print.PrintAttributes.MediaSize;
import android.print.pdf.PdfDocument;
import android.print.pdf.PdfDocument.Page;
import android.print.pdf.PdfDocument.PageInfo;

import java.io.OutputStream;
import java.util.List;

/**
 * This class is a helper for printing content to a different media
 * size. This class is responsible for computing a correct page size
 * given some print constraints, i.e. {@link PrintAttributes}. It is
 * an adapter around a {@link PdfDocument}.
 */
public final class PrintedPdfDocument {
    private static final int MILS_PER_INCH = 1000;
    private static final int POINTS_IN_INCH = 72;

    private final PdfDocument mDocument = PdfDocument.open();
    private final Rect mPageSize = new Rect();
    private final Rect mContentSize = new Rect();

    /**
     * Opens a new document. The document pages are computed based on
     * the passes in {@link PrintAttributes}.
     * <p>
     * <strong>Note:</strong> You must close the document after you are
     * done by calling {@link #close()}
     * </p>
     *
     * @param context Context instance for accessing resources.
     * @param attributes The print attributes.
     * @return The document.
     *
     * @see #close()
     */
    public static PrintedPdfDocument open(Context context, PrintAttributes attributes) {
        return new PrintedPdfDocument(context, attributes);
    }

    /**
     * Creates a new instance.
     *
     * @param context Context instance for accessing resources and services.
     * @param attributes The {@link PrintAttributes} to user.
     */
    private PrintedPdfDocument(Context context, PrintAttributes attributes) {
        MediaSize mediaSize = attributes.getMediaSize();

        // Compute the size of the target canvas from the attributes.
        final int pageWidth = (int) (((float) mediaSize.getWidthMils() / MILS_PER_INCH)
                * POINTS_IN_INCH);
        final int pageHeight = (int) (((float) mediaSize.getHeightMils() / MILS_PER_INCH)
                * POINTS_IN_INCH);
        mPageSize.set(0, 0, pageWidth, pageHeight);

        // Compute the content size from the attributes.
        Margins margins = attributes.getMargins();
        final int marginLeft = (int) (((float) margins.getLeftMils() /MILS_PER_INCH)
                * POINTS_IN_INCH);
        final int marginTop = (int) (((float) margins.getTopMils() / MILS_PER_INCH)
                * POINTS_IN_INCH);
        final int marginRight = (int) (((float) margins.getRightMils() / MILS_PER_INCH)
                * POINTS_IN_INCH);
        final int marginBottom = (int) (((float) margins.getBottomMils() / MILS_PER_INCH)
                * POINTS_IN_INCH);
        mContentSize.set(mPageSize.left + marginLeft, mPageSize.top + marginTop,
                mPageSize.right - marginRight, mPageSize.bottom - marginBottom);
    }

    /**
     * Starts a page using a page size computed from the print attributes
     * passed in {@link #open(Context, PrintAttributes)} and the given page
     * number to create appropriate {@link PageInfo}.
     * <p>
     * After the page is created you can draw arbitrary content on the page's
     * canvas which you can get by calling {@link Page#getCanvas() Page.getCanvas()}.
     * After you are done drawing the content you should finish the page by calling
     * {@link #finishPage(Page)}. After the page is finished you should no longer
     * access the page or its canvas.
     * </p>
     * <p>
     * <strong>Note:</strong> Do not call this method after {@link #close()}.
     * </p>
     *
     * @param pageNumber The page number.
     * @return A blank page.
     *
     * @see #finishPage(Page)
     */
    public Page startPage(int pageNumber) {
        PageInfo pageInfo = new PageInfo
                .Builder(mPageSize, 0)
                .setContentSize(mContentSize)
                .create();
        Page page = mDocument.startPage(pageInfo);
        return page;
    }

    /**
     * Finishes a started page. You should always finish the last started page.
     * <p>
     * <strong>Note:</strong> Do not call this method after {@link #close()}.
     * </p>
     *
     * @param page The page.
     *
     * @see #startPage(int)
     */
    public void finishPage(Page page) {
        mDocument.finishPage(page);
    }

    /**
     * Writes the document to an output stream.
     * <p>
     * <strong>Note:</strong> Do not call this method after {@link #close()}.
     * </p>
     *
     * @param out The output stream.
     */
    public void writeTo(OutputStream out) {
        mDocument.writeTo(out);
    }

    /**
     * Gets the pages of the document.
     *
     * @return The pages.
     */
    public List<PageInfo> getPages() {
        return mDocument.getPages();
    }

    /**
     * Closes this document. This method should be called after you
     * are done working with the document. After this call the document
     * is considered closed and none of its methods should be called.
     */
    public void close() {
        mDocument.close();
    }
}
