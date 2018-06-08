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

import static com.android.internal.util.dump.DumpUtils.writeComponentName;

import android.annotation.NonNull;
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

import com.android.internal.util.dump.DualDumpOutputStream;

/**
 * Utilities for dumping print related proto buffer
 */
public class DumpUtils {
    /**
     * Write a {@link PrinterId} to a proto.
     *
     * @param proto The proto to write to
     * @param idName Clear text name of the proto-id
     * @param id The proto-id of the component name
     * @param printerId The printer id to write
     */
    public static void writePrinterId(@NonNull DualDumpOutputStream proto, String idName, long id,
            @NonNull PrinterId printerId) {
        long token = proto.start(idName, id);
        writeComponentName(proto, "service_name", PrinterIdProto.SERVICE_NAME,
                printerId.getServiceName());
        proto.write("local_id", PrinterIdProto.LOCAL_ID, printerId.getLocalId());
        proto.end(token);
    }

    /**
     * Write a {@link PrinterCapabilitiesInfo} to a proto.
     *
     * @param proto The proto to write to
     * @param idName Clear text name of the proto-id
     * @param id The proto-id of the component name
     * @param cap The capabilities to write
     */
    public static void writePrinterCapabilities(@NonNull Context context,
            @NonNull DualDumpOutputStream proto, String idName, long id,
            @NonNull PrinterCapabilitiesInfo cap) {
        long token = proto.start(idName, id);
        writeMargins(proto, "min_margins", PrinterCapabilitiesProto.MIN_MARGINS,
                cap.getMinMargins());

        int numMediaSizes = cap.getMediaSizes().size();
        for (int i = 0; i < numMediaSizes; i++) {
            writeMediaSize(context, proto, "media_sizes", PrinterCapabilitiesProto.MEDIA_SIZES,
                    cap.getMediaSizes().get(i));
        }

        int numResolutions = cap.getResolutions().size();
        for (int i = 0; i < numResolutions; i++) {
            writeResolution(proto, "resolutions", PrinterCapabilitiesProto.RESOLUTIONS,
                    cap.getResolutions().get(i));
        }

        if ((cap.getColorModes() & PrintAttributes.COLOR_MODE_MONOCHROME) != 0) {
            proto.write("color_modes", PrinterCapabilitiesProto.COLOR_MODES,
                    PrintAttributesProto.COLOR_MODE_MONOCHROME);
        }
        if ((cap.getColorModes() & PrintAttributes.COLOR_MODE_COLOR) != 0) {
            proto.write("color_modes", PrinterCapabilitiesProto.COLOR_MODES,
                    PrintAttributesProto.COLOR_MODE_COLOR);
        }

        if ((cap.getDuplexModes() & PrintAttributes.DUPLEX_MODE_NONE) != 0) {
            proto.write("duplex_modes", PrinterCapabilitiesProto.DUPLEX_MODES,
                    PrintAttributesProto.DUPLEX_MODE_NONE);
        }
        if ((cap.getDuplexModes() & PrintAttributes.DUPLEX_MODE_LONG_EDGE) != 0) {
            proto.write("duplex_modes", PrinterCapabilitiesProto.DUPLEX_MODES,
                    PrintAttributesProto.DUPLEX_MODE_LONG_EDGE);
        }
        if ((cap.getDuplexModes() & PrintAttributes.DUPLEX_MODE_SHORT_EDGE) != 0) {
            proto.write("duplex_modes", PrinterCapabilitiesProto.DUPLEX_MODES,
                    PrintAttributesProto.DUPLEX_MODE_SHORT_EDGE);
        }

        proto.end(token);
    }

    /**
     * Write a {@link PrinterInfo} to a proto.
     *
     * @param context The context used to resolve resources
     * @param proto The proto to write to
     * @param idName Clear text name of the proto-id
     * @param id The proto-id of the component name
     * @param info The printer info to write
     */
    public static void writePrinterInfo(@NonNull Context context,
            @NonNull DualDumpOutputStream proto, String idName, long id,
            @NonNull PrinterInfo info) {
        long token = proto.start(idName, id);
        writePrinterId(proto, "id", PrinterInfoProto.ID, info.getId());
        proto.write("name", PrinterInfoProto.NAME, info.getName());
        proto.write("status", PrinterInfoProto.STATUS, info.getStatus());
        proto.write("description", PrinterInfoProto.DESCRIPTION, info.getDescription());

        PrinterCapabilitiesInfo cap = info.getCapabilities();
        if (cap != null) {
            writePrinterCapabilities(context, proto, "capabilities", PrinterInfoProto.CAPABILITIES,
                    cap);
        }

        proto.end(token);
    }

    /**
     * Write a {@link PrintAttributes.MediaSize} to a proto.
     *
     * @param context The context used to resolve resources
     * @param proto The proto to write to
     * @param idName Clear text name of the proto-id
     * @param id The proto-id of the component name
     * @param mediaSize The media size to write
     */
    public static void writeMediaSize(@NonNull Context context, @NonNull DualDumpOutputStream proto,
            String idName, long id, @NonNull PrintAttributes.MediaSize mediaSize) {
        long token = proto.start(idName, id);
        proto.write("id", MediaSizeProto.ID, mediaSize.getId());
        proto.write("label", MediaSizeProto.LABEL, mediaSize.getLabel(context.getPackageManager()));
        proto.write("height_mils", MediaSizeProto.HEIGHT_MILS, mediaSize.getHeightMils());
        proto.write("width_mils", MediaSizeProto.WIDTH_MILS, mediaSize.getWidthMils());
        proto.end(token);
    }

    /**
     * Write a {@link PrintAttributes.Resolution} to a proto.
     *
     * @param proto The proto to write to
     * @param idName Clear text name of the proto-id
     * @param id The proto-id of the component name
     * @param res The resolution to write
     */
    public static void writeResolution(@NonNull DualDumpOutputStream proto, String idName, long id,
            @NonNull PrintAttributes.Resolution res) {
        long token = proto.start(idName, id);
        proto.write("id", ResolutionProto.ID, res.getId());
        proto.write("label", ResolutionProto.LABEL, res.getLabel());
        proto.write("horizontal_DPI", ResolutionProto.HORIZONTAL_DPI, res.getHorizontalDpi());
        proto.write("veritical_DPI", ResolutionProto.VERTICAL_DPI, res.getVerticalDpi());
        proto.end(token);
    }

    /**
     * Write a {@link PrintAttributes.Margins} to a proto.
     *
     * @param proto The proto to write to
     * @param idName Clear text name of the proto-id
     * @param id The proto-id of the component name
     * @param margins The margins to write
     */
    public static void writeMargins(@NonNull DualDumpOutputStream proto, String idName, long id,
            @NonNull PrintAttributes.Margins margins) {
        long token = proto.start(idName, id);
        proto.write("top_mils", MarginsProto.TOP_MILS, margins.getTopMils());
        proto.write("left_mils", MarginsProto.LEFT_MILS, margins.getLeftMils());
        proto.write("right_mils", MarginsProto.RIGHT_MILS, margins.getRightMils());
        proto.write("bottom_mils", MarginsProto.BOTTOM_MILS, margins.getBottomMils());
        proto.end(token);
    }

    /**
     * Write a {@link PrintAttributes} to a proto.
     *
     * @param context The context used to resolve resources
     * @param proto The proto to write to
     * @param idName Clear text name of the proto-id
     * @param id The proto-id of the component name
     * @param attributes The attributes to write
     */
    public static void writePrintAttributes(@NonNull Context context,
            @NonNull DualDumpOutputStream proto, String idName, long id,
            @NonNull PrintAttributes attributes) {
        long token = proto.start(idName, id);

        PrintAttributes.MediaSize mediaSize = attributes.getMediaSize();
        if (mediaSize != null) {
            writeMediaSize(context, proto, "media_size", PrintAttributesProto.MEDIA_SIZE, mediaSize);
            proto.write("is_portrait", PrintAttributesProto.IS_PORTRAIT, attributes.isPortrait());
        }

        PrintAttributes.Resolution res = attributes.getResolution();
        if (res != null) {
            writeResolution(proto, "resolution", PrintAttributesProto.RESOLUTION, res);
        }

        PrintAttributes.Margins minMargins = attributes.getMinMargins();
        if (minMargins != null) {
            writeMargins(proto, "min_margings", PrintAttributesProto.MIN_MARGINS, minMargins);
        }

        proto.write("color_mode", PrintAttributesProto.COLOR_MODE, attributes.getColorMode());
        proto.write("duplex_mode", PrintAttributesProto.DUPLEX_MODE, attributes.getDuplexMode());
        proto.end(token);
    }

    /**
     * Write a {@link PrintDocumentInfo} to a proto.
     *
     * @param proto The proto to write to
     * @param idName Clear text name of the proto-id
     * @param id The proto-id of the component name
     * @param info The info to write
     */
    public static void writePrintDocumentInfo(@NonNull DualDumpOutputStream proto, String idName,
            long id, @NonNull PrintDocumentInfo info) {
        long token = proto.start(idName, id);
        proto.write("name", PrintDocumentInfoProto.NAME, info.getName());

        int pageCount = info.getPageCount();
        if (pageCount != PrintDocumentInfo.PAGE_COUNT_UNKNOWN) {
            proto.write("page_count", PrintDocumentInfoProto.PAGE_COUNT, pageCount);
        }

        proto.write("content_type", PrintDocumentInfoProto.CONTENT_TYPE, info.getContentType());
        proto.write("data_size", PrintDocumentInfoProto.DATA_SIZE, info.getDataSize());
        proto.end(token);
    }

    /**
     * Write a {@link PageRange} to a proto.
     *
     * @param proto The proto to write to
     * @param idName Clear text name of the proto-id
     * @param id The proto-id of the component name
     * @param range The range to write
     */
    public static void writePageRange(@NonNull DualDumpOutputStream proto, String idName, long id,
            @NonNull PageRange range) {
        long token = proto.start(idName, id);
        proto.write("start", PageRangeProto.START, range.getStart());
        proto.write("end", PageRangeProto.END, range.getEnd());
        proto.end(token);
    }

    /**
     * Write a {@link PrintJobInfo} to a proto.
     *
     * @param context The context used to resolve resources
     * @param proto The proto to write to
     * @param idName Clear text name of the proto-id
     * @param id The proto-id of the component name
     * @param printJobInfo The print job info to write
     */
    public static void writePrintJobInfo(@NonNull Context context,
            @NonNull DualDumpOutputStream proto, String idName, long id,
            @NonNull PrintJobInfo printJobInfo) {
        long token = proto.start(idName, id);
        proto.write("label", PrintJobInfoProto.LABEL, printJobInfo.getLabel());

        PrintJobId printJobId = printJobInfo.getId();
        if (printJobId != null) {
            proto.write("print_job_id", PrintJobInfoProto.PRINT_JOB_ID,
                    printJobId.flattenToString());
        }

        int state = printJobInfo.getState();
        if (state >= PrintJobInfoProto.STATE_CREATED && state <= PrintJobInfoProto.STATE_CANCELED) {
            proto.write("state", PrintJobInfoProto.STATE, state);
        } else {
            proto.write("state", PrintJobInfoProto.STATE, PrintJobInfoProto.STATE_UNKNOWN);
        }

        PrinterId printer = printJobInfo.getPrinterId();
        if (printer != null) {
            writePrinterId(proto, "printer", PrintJobInfoProto.PRINTER, printer);
        }

        String tag = printJobInfo.getTag();
        if (tag != null) {
            proto.write("tag", PrintJobInfoProto.TAG, tag);
        }

        proto.write("creation_time", PrintJobInfoProto.CREATION_TIME,
                printJobInfo.getCreationTime());

        PrintAttributes attributes = printJobInfo.getAttributes();
        if (attributes != null) {
            writePrintAttributes(context, proto, "attributes", PrintJobInfoProto.ATTRIBUTES,
                    attributes);
        }

        PrintDocumentInfo docInfo = printJobInfo.getDocumentInfo();
        if (docInfo != null) {
            writePrintDocumentInfo(proto, "document_info", PrintJobInfoProto.DOCUMENT_INFO,
                    docInfo);
        }

        proto.write("is_canceling", PrintJobInfoProto.IS_CANCELING, printJobInfo.isCancelling());

        PageRange[] pages = printJobInfo.getPages();
        if (pages != null) {
            for (int i = 0; i < pages.length; i++) {
                writePageRange(proto, "pages", PrintJobInfoProto.PAGES, pages[i]);
            }
        }

        proto.write("has_advanced_options", PrintJobInfoProto.HAS_ADVANCED_OPTIONS,
                printJobInfo.getAdvancedOptions() != null);
        proto.write("progress", PrintJobInfoProto.PROGRESS, printJobInfo.getProgress());

        CharSequence status = printJobInfo.getStatus(context.getPackageManager());
        if (status != null) {
            proto.write("status", PrintJobInfoProto.STATUS, status.toString());
        }

        proto.end(token);
    }
}
