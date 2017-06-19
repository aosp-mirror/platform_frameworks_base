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

package com.android.internal.print;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.ComponentNameProto;
import android.content.Context;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentInfo;
import android.print.PrintJobId;
import android.print.PrintJobInfo;
import android.print.PrinterCapabilitiesInfo;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.service.print.MarginsProto;
import android.service.print.MediaSizeProto;
import android.service.print.PageRangeProto;
import android.service.print.PrintAttributesProto;
import android.service.print.PrintDocumentInfoProto;
import android.service.print.PrintJobInfoProto;
import android.service.print.PrinterCapabilitiesProto;
import android.service.print.PrinterIdProto;
import android.service.print.PrinterInfoProto;
import android.service.print.ResolutionProto;
import android.util.proto.ProtoOutputStream;

/**
 * Utilities for dumping print related proto buffer
 */
public class DumpUtils {
    /**
     * Write a string to a proto if the string is not {@code null}.
     *
     * @param proto The proto to write to
     * @param id The proto-id of the string
     * @param string The string to write
     */
    public static void writeStringIfNotNull(@NonNull ProtoOutputStream proto, long id,
            @Nullable String string) {
        if (string != null) {
            proto.write(id, string);
        }
    }

    /**
     * Write a {@link ComponentName} to a proto.
     *
     * @param proto The proto to write to
     * @param id The proto-id of the component name
     * @param component The component name to write
     */
    public static void writeComponentName(@NonNull ProtoOutputStream proto, long id,
            @NonNull ComponentName component) {
        long token = proto.start(id);
        proto.write(ComponentNameProto.PACKAGE_NAME, component.getPackageName());
        proto.write(ComponentNameProto.CLASS_NAME, component.getClassName());
        proto.end(token);
    }

    /**
     * Write a {@link PrinterId} to a proto.
     *
     * @param proto The proto to write to
     * @param id The proto-id of the component name
     * @param printerId The printer id to write
     */
    public static void writePrinterId(@NonNull ProtoOutputStream proto, long id,
            @NonNull PrinterId printerId) {
        long token = proto.start(id);
        writeComponentName(proto, PrinterIdProto.SERVICE_NAME, printerId.getServiceName());
        proto.write(PrinterIdProto.LOCAL_ID, printerId.getLocalId());
        proto.end(token);
    }

    /**
     * Write a {@link PrinterCapabilitiesInfo} to a proto.
     *
     * @param proto The proto to write to
     * @param id The proto-id of the component name
     * @param cap The capabilities to write
     */
    public static void writePrinterCapabilities(@NonNull Context context,
            @NonNull ProtoOutputStream proto, long id, @NonNull PrinterCapabilitiesInfo cap) {
        long token = proto.start(id);
        writeMargins(proto, PrinterCapabilitiesProto.MIN_MARGINS, cap.getMinMargins());

        int numMediaSizes = cap.getMediaSizes().size();
        for (int i = 0; i < numMediaSizes; i++) {
            writeMediaSize(context, proto, PrinterCapabilitiesProto.MEDIA_SIZES,
                    cap.getMediaSizes().get(i));
        }

        int numResolutions = cap.getResolutions().size();
        for (int i = 0; i < numResolutions; i++) {
            writeResolution(proto, PrinterCapabilitiesProto.RESOLUTIONS,
                    cap.getResolutions().get(i));
        }

        if ((cap.getColorModes() & PrintAttributes.COLOR_MODE_MONOCHROME) != 0) {
            proto.write(PrinterCapabilitiesProto.COLOR_MODES,
                    PrintAttributesProto.COLOR_MODE_MONOCHROME);
        }
        if ((cap.getColorModes() & PrintAttributes.COLOR_MODE_COLOR) != 0) {
            proto.write(PrinterCapabilitiesProto.COLOR_MODES,
                    PrintAttributesProto.COLOR_MODE_COLOR);
        }

        if ((cap.getDuplexModes() & PrintAttributes.DUPLEX_MODE_NONE) != 0) {
            proto.write(PrinterCapabilitiesProto.DUPLEX_MODES,
                    PrintAttributesProto.DUPLEX_MODE_NONE);
        }
        if ((cap.getDuplexModes() & PrintAttributes.DUPLEX_MODE_LONG_EDGE) != 0) {
            proto.write(PrinterCapabilitiesProto.DUPLEX_MODES,
                    PrintAttributesProto.DUPLEX_MODE_LONG_EDGE);
        }
        if ((cap.getDuplexModes() & PrintAttributes.DUPLEX_MODE_SHORT_EDGE) != 0) {
            proto.write(PrinterCapabilitiesProto.DUPLEX_MODES,
                    PrintAttributesProto.DUPLEX_MODE_SHORT_EDGE);
        }

        proto.end(token);
    }


    /**
     * Write a {@link PrinterInfo} to a proto.
     *
     * @param context The context used to resolve resources
     * @param proto The proto to write to
     * @param id The proto-id of the component name
     * @param info The printer info to write
     */
    public static void writePrinterInfo(@NonNull Context context, @NonNull ProtoOutputStream proto,
            long id, @NonNull PrinterInfo info) {
        long token = proto.start(id);
        writePrinterId(proto, PrinterInfoProto.ID, info.getId());
        proto.write(PrinterInfoProto.NAME, info.getName());
        proto.write(PrinterInfoProto.STATUS, info.getStatus());
        proto.write(PrinterInfoProto.DESCRIPTION, info.getDescription());

        PrinterCapabilitiesInfo cap = info.getCapabilities();
        if (cap != null) {
            writePrinterCapabilities(context, proto, PrinterInfoProto.CAPABILITIES, cap);
        }

        proto.end(token);
    }

    /**
     * Write a {@link PrintAttributes.MediaSize} to a proto.
     *
     * @param context The context used to resolve resources
     * @param proto The proto to write to
     * @param id The proto-id of the component name
     * @param mediaSize The media size to write
     */
    public static void writeMediaSize(@NonNull Context context, @NonNull ProtoOutputStream proto,
            long id, @NonNull PrintAttributes.MediaSize mediaSize) {
        long token = proto.start(id);
        proto.write(MediaSizeProto.ID, mediaSize.getId());
        proto.write(MediaSizeProto.LABEL, mediaSize.getLabel(context.getPackageManager()));
        proto.write(MediaSizeProto.HEIGHT_MILS, mediaSize.getHeightMils());
        proto.write(MediaSizeProto.WIDTH_MILS, mediaSize.getWidthMils());
        proto.end(token);
    }

    /**
     * Write a {@link PrintAttributes.Resolution} to a proto.
     *
     * @param proto The proto to write to
     * @param id The proto-id of the component name
     * @param res The resolution to write
     */
    public static void writeResolution(@NonNull ProtoOutputStream proto, long id,
            @NonNull PrintAttributes.Resolution res) {
        long token = proto.start(id);
        proto.write(ResolutionProto.ID, res.getId());
        proto.write(ResolutionProto.LABEL, res.getLabel());
        proto.write(ResolutionProto.HORIZONTAL_DPI, res.getHorizontalDpi());
        proto.write(ResolutionProto.VERTICAL_DPI, res.getVerticalDpi());
        proto.end(token);
    }

    /**
     * Write a {@link PrintAttributes.Margins} to a proto.
     *
     * @param proto The proto to write to
     * @param id The proto-id of the component name
     * @param margins The margins to write
     */
    public static void writeMargins(@NonNull ProtoOutputStream proto, long id,
            @NonNull PrintAttributes.Margins margins) {
        long token = proto.start(id);
        proto.write(MarginsProto.TOP_MILS, margins.getTopMils());
        proto.write(MarginsProto.LEFT_MILS, margins.getLeftMils());
        proto.write(MarginsProto.RIGHT_MILS, margins.getRightMils());
        proto.write(MarginsProto.BOTTOM_MILS, margins.getBottomMils());
        proto.end(token);
    }

    /**
     * Write a {@link PrintAttributes} to a proto.
     *
     * @param context The context used to resolve resources
     * @param proto The proto to write to
     * @param id The proto-id of the component name
     * @param attributes The attributes to write
     */
    public static void writePrintAttributes(@NonNull Context context,
            @NonNull ProtoOutputStream proto, long id, @NonNull PrintAttributes attributes) {
        long token = proto.start(id);

        PrintAttributes.MediaSize mediaSize = attributes.getMediaSize();
        if (mediaSize != null) {
            writeMediaSize(context, proto, PrintAttributesProto.MEDIA_SIZE, mediaSize);
        }

        proto.write(PrintAttributesProto.IS_PORTRAIT, attributes.isPortrait());

        PrintAttributes.Resolution res = attributes.getResolution();
        if (res != null) {
            writeResolution(proto, PrintAttributesProto.RESOLUTION, res);
        }

        PrintAttributes.Margins minMargins = attributes.getMinMargins();
        if (minMargins != null) {
            writeMargins(proto, PrintAttributesProto.MIN_MARGINS, minMargins);
        }

        proto.write(PrintAttributesProto.COLOR_MODE, attributes.getColorMode());
        proto.write(PrintAttributesProto.DUPLEX_MODE, attributes.getDuplexMode());
        proto.end(token);
    }

    /**
     * Write a {@link PrintDocumentInfo} to a proto.
     *
     * @param proto The proto to write to
     * @param id The proto-id of the component name
     * @param info The info to write
     */
    public static void writePrintDocumentInfo(@NonNull ProtoOutputStream proto, long id,
            @NonNull PrintDocumentInfo info) {
        long token = proto.start(id);
        proto.write(PrintDocumentInfoProto.NAME, info.getName());

        int pageCount = info.getPageCount();
        if (pageCount != PrintDocumentInfo.PAGE_COUNT_UNKNOWN) {
            proto.write(PrintDocumentInfoProto.PAGE_COUNT, pageCount);
        }

        proto.write(PrintDocumentInfoProto.CONTENT_TYPE, info.getContentType());
        proto.write(PrintDocumentInfoProto.DATA_SIZE, info.getDataSize());
        proto.end(token);
    }

    /**
     * Write a {@link PageRange} to a proto.
     *
     * @param proto The proto to write to
     * @param id The proto-id of the component name
     * @param range The range to write
     */
    public static void writePageRange(@NonNull ProtoOutputStream proto, long id,
            @NonNull PageRange range) {
        long token = proto.start(id);
        proto.write(PageRangeProto.START, range.getStart());
        proto.write(PageRangeProto.END, range.getEnd());
        proto.end(token);
    }

    /**
     * Write a {@link PrintJobInfo} to a proto.
     *
     * @param context The context used to resolve resources
     * @param proto The proto to write to
     * @param id The proto-id of the component name
     * @param printJobInfo The print job info to write
     */
    public static void writePrintJobInfo(@NonNull Context context, @NonNull ProtoOutputStream proto,
            long id, @NonNull PrintJobInfo printJobInfo) {
        long token = proto.start(id);
        proto.write(PrintJobInfoProto.LABEL, printJobInfo.getLabel());

        PrintJobId printJobId = printJobInfo.getId();
        if (printJobId != null) {
            proto.write(PrintJobInfoProto.PRINT_JOB_ID, printJobId.flattenToString());
        }

        int state = printJobInfo.getState();
        if (state >= PrintJobInfoProto.STATE_CREATED && state <= PrintJobInfoProto.STATE_CANCELED) {
            proto.write(PrintJobInfoProto.STATE, state);
        } else {
            proto.write(PrintJobInfoProto.STATE, PrintJobInfoProto.STATE_UNKNOWN);
        }

        PrinterId printer = printJobInfo.getPrinterId();
        if (printer != null) {
            writePrinterId(proto, PrintJobInfoProto.PRINTER, printer);
        }

        String tag = printJobInfo.getTag();
        if (tag != null) {
            proto.write(PrintJobInfoProto.TAG, tag);
        }

        proto.write(PrintJobInfoProto.CREATION_TIME, printJobInfo.getCreationTime());

        PrintAttributes attributes = printJobInfo.getAttributes();
        if (attributes != null) {
            writePrintAttributes(context, proto, PrintJobInfoProto.ATTRIBUTES, attributes);
        }

        PrintDocumentInfo docInfo = printJobInfo.getDocumentInfo();
        if (docInfo != null) {
            writePrintDocumentInfo(proto, PrintJobInfoProto.DOCUMENT_INFO, docInfo);
        }

        proto.write(PrintJobInfoProto.IS_CANCELING, printJobInfo.isCancelling());

        PageRange[] pages = printJobInfo.getPages();
        if (pages != null) {
            for (int i = 0; i < pages.length; i++) {
                writePageRange(proto, PrintJobInfoProto.PAGES, pages[i]);
            }
        }

        proto.write(PrintJobInfoProto.HAS_ADVANCED_OPTIONS,
                printJobInfo.getAdvancedOptions() != null);
        proto.write(PrintJobInfoProto.PROGRESS, printJobInfo.getProgress());

        CharSequence status = printJobInfo.getStatus(context.getPackageManager());
        if (status != null) {
            proto.write(PrintJobInfoProto.STATUS, status.toString());
        }

        proto.end(token);
    }
}
