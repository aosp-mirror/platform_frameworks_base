/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.graphics.fonts;

import static com.android.server.graphics.fonts.FontManagerService.SystemFontException;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.graphics.fonts.FontManager;
import android.graphics.fonts.FontUpdateRequest;
import android.graphics.fonts.FontVariationAxis;
import android.graphics.fonts.SystemFonts;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.ShellCommand;
import android.text.FontConfig;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.util.TypedXmlPullParser;
import android.util.Xml;

import com.android.internal.util.DumpUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A shell command implementation of font service.
 */
public class FontManagerShellCommand extends ShellCommand {
    private static final String TAG = "FontManagerShellCommand";

    /**
     * The maximum size of signature file.  This is just to avoid potential abuse.
     *
     * This is copied from VerityUtils.java.
     */
    private static final int MAX_SIGNATURE_FILE_SIZE_BYTES = 8192;

    @NonNull private final FontManagerService mService;

    FontManagerShellCommand(@NonNull FontManagerService service) {
        mService = service;
    }

    @Override
    public int onCommand(String cmd) {
        final int callingUid = Binder.getCallingUid();
        if (callingUid != Process.ROOT_UID && callingUid != Process.SHELL_UID) {
            // Do not change this string since this string is expected in the CTS.
            getErrPrintWriter().println("Only shell or root user can execute font command.");
            return 1;
        }
        return execCommand(this, cmd);
    }

    @Override
    public void onHelp() {
        PrintWriter w = getOutPrintWriter();
        w.println("Font service (font) commands");
        w.println("help");
        w.println("    Print this help text.");
        w.println();
        w.println("dump [family name]");
        w.println("    Dump all font files in the specified family name.");
        w.println("    Dump current system font configuration if no family name was specified.");
        w.println();
        w.println("update [font file path] [signature file path]");
        w.println("    Update installed font files with new font file.");
        w.println();
        w.println("update-family [family definition XML path]");
        w.println("    Update font families with the new definitions.");
        w.println();
        w.println("clear");
        w.println("    Remove all installed font files and reset to the initial state.");
        w.println();
        w.println("restart");
        w.println("    Restart FontManagerService emulating device reboot.");
        w.println("    WARNING: this is not a safe operation. Other processes may misbehave if");
        w.println("    they are using fonts updated by FontManagerService.");
        w.println("    This command exists merely for testing.");
        w.println();
        w.println("status");
        w.println("    Prints status of current system font configuration.");
    }

    /* package */ void dumpAll(@NonNull IndentingPrintWriter w) {
        FontConfig fontConfig = mService.getSystemFontConfig();
        dumpFontConfig(w, fontConfig);
    }

    private void dumpSingleFontConfig(
            @NonNull IndentingPrintWriter w,
            @NonNull FontConfig.Font font
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("style = ");
        sb.append(font.getStyle());
        sb.append(", path = ");
        sb.append(font.getFile().getAbsolutePath());
        if (font.getTtcIndex() != 0) {
            sb.append(", index = ");
            sb.append(font.getTtcIndex());
        }
        if (!font.getFontVariationSettings().isEmpty()) {
            sb.append(", axes = ");
            sb.append(font.getFontVariationSettings());
        }
        if (font.getFontFamilyName() != null) {
            sb.append(", fallback = ");
            sb.append(font.getFontFamilyName());
        }
        w.println(sb.toString());

        if (font.getOriginalFile() != null) {
            w.increaseIndent();
            w.println("Font is updated from " + font.getOriginalFile());
            w.decreaseIndent();
        }
    }

    private void dumpFontConfig(
            @NonNull IndentingPrintWriter w,
            @NonNull FontConfig fontConfig
    ) {
        // Dump named font family first.
        List<FontConfig.FontFamily> families = fontConfig.getFontFamilies();

        w.println("Named Font Families");
        w.increaseIndent();
        for (int i = 0; i < families.size(); ++i) {
            final FontConfig.FontFamily family = families.get(i);

            // Here, only dump the named family only.
            if (family.getName() == null) continue;

            w.println("Named Family (" + family.getName() + ")");
            final List<FontConfig.Font> fonts = family.getFontList();
            w.increaseIndent();
            for (int j = 0; j < fonts.size(); ++j) {
                dumpSingleFontConfig(w, fonts.get(j));
            }
            w.decreaseIndent();
        }
        w.decreaseIndent();

        // Dump Fallback fonts.
        w.println("Dump Fallback Families");
        w.increaseIndent();
        int c = 0;
        for (int i = 0; i < families.size(); ++i) {
            final FontConfig.FontFamily family = families.get(i);

            // Skip named font family since they are already dumped.
            if (family.getName() != null) continue;

            StringBuilder sb = new StringBuilder("Fallback Family [");
            sb.append(c++);
            sb.append("]: lang=\"");
            sb.append(family.getLocaleList().toLanguageTags());
            sb.append("\"");
            if (family.getVariant() != FontConfig.FontFamily.VARIANT_DEFAULT) {
                sb.append(", variant=");
                switch (family.getVariant()) {
                    case FontConfig.FontFamily.VARIANT_COMPACT:
                        sb.append("Compact");
                        break;
                    case FontConfig.FontFamily.VARIANT_ELEGANT:
                        sb.append("Elegant");
                        break;
                    default:
                        sb.append("Unknown");
                        break;
                }
            }
            w.println(sb.toString());

            final List<FontConfig.Font> fonts = family.getFontList();
            w.increaseIndent();
            for (int j = 0; j < fonts.size(); ++j) {
                dumpSingleFontConfig(w, fonts.get(j));
            }
            w.decreaseIndent();
        }
        w.decreaseIndent();

        // Dump aliases
        w.println("Dump Family Aliases");
        w.increaseIndent();
        List<FontConfig.Alias> aliases = fontConfig.getAliases();
        for (int i = 0; i < aliases.size(); ++i) {
            final FontConfig.Alias alias = aliases.get(i);
            w.println("alias = " + alias.getName() + ", reference = " + alias.getOriginal()
                    + ", width = " + alias.getWeight());
        }
        w.decreaseIndent();
    }

    private void dumpFallback(@NonNull IndentingPrintWriter writer,
            @NonNull FontFamily[] families) {
        for (FontFamily family : families) {
            dumpFamily(writer, family);
        }
    }

    private void dumpFamily(@NonNull IndentingPrintWriter writer, @NonNull FontFamily family) {
        StringBuilder sb = new StringBuilder("Family:");
        if (family.getLangTags() != null) {
            sb.append(" langTag = ");
            sb.append(family.getLangTags());
        }
        if (family.getVariant() != FontConfig.FontFamily.VARIANT_DEFAULT) {
            sb.append(" variant = ");
            switch (family.getVariant()) {
                case FontConfig.FontFamily.VARIANT_COMPACT:
                    sb.append("Compact");
                    break;
                case FontConfig.FontFamily.VARIANT_ELEGANT:
                    sb.append("Elegant");
                    break;
                default:
                    sb.append("UNKNOWN");
                    break;
            }

        }
        writer.println(sb.toString());
        for (int i = 0; i < family.getSize(); ++i) {
            writer.increaseIndent();
            try {
                dumpFont(writer, family.getFont(i));
            } finally {
                writer.decreaseIndent();
            }
        }
    }

    private void dumpFont(@NonNull IndentingPrintWriter writer, @NonNull Font font) {
        File file = font.getFile();
        StringBuilder sb = new StringBuilder();
        sb.append(font.getStyle());
        sb.append(", path = ");
        sb.append(file == null ? "[Not a file]" : file.getAbsolutePath());
        if (font.getTtcIndex() != 0) {
            sb.append(", index = ");
            sb.append(font.getTtcIndex());
        }
        FontVariationAxis[] axes = font.getAxes();
        if (axes != null && axes.length != 0) {
            sb.append(", axes = \"");
            sb.append(FontVariationAxis.toFontVariationSettings(axes));
            sb.append("\"");
        }
        writer.println(sb.toString());
    }

    private void writeCommandResult(ShellCommand shell, SystemFontException e) {
        // Print short summary to the stderr.
        PrintWriter pw = shell.getErrPrintWriter();
        pw.println(e.getErrorCode());
        pw.println(e.getMessage());

        // Dump full stack trace to logcat.

        Slog.e(TAG, "Command failed: " + Arrays.toString(shell.getAllArgs()), e);
    }

    private int dump(ShellCommand shell) {
        final Context ctx = mService.getContext();

        if (!DumpUtils.checkDumpPermission(ctx, TAG, shell.getErrPrintWriter())) {
            return 1;
        }
        final IndentingPrintWriter writer =
                new IndentingPrintWriter(shell.getOutPrintWriter(), "  ");
        String nextArg = shell.getNextArg();
        FontConfig fontConfig = mService.getSystemFontConfig();
        if (nextArg == null) {
            dumpFontConfig(writer, fontConfig);
        } else {
            final Map<String, FontFamily[]> fallbackMap =
                    SystemFonts.buildSystemFallback(fontConfig);
            FontFamily[] families = fallbackMap.get(nextArg);
            if (families == null) {
                writer.println("Font Family \"" + nextArg + "\" not found");
            } else {
                dumpFallback(writer, families);
            }
        }
        return 0;
    }

    private int update(ShellCommand shell) throws SystemFontException {
        String fontPath = shell.getNextArg();
        if (fontPath == null) {
            throw new SystemFontException(
                    FontManager.RESULT_ERROR_INVALID_SHELL_ARGUMENT,
                    "Font file path argument is required.");
        }
        String signaturePath = shell.getNextArg();
        if (signaturePath == null) {
            throw new SystemFontException(
                    FontManager.RESULT_ERROR_INVALID_SHELL_ARGUMENT,
                    "Signature file argument is required.");
        }

        try (ParcelFileDescriptor fontFd = shell.openFileForSystem(fontPath, "r");
             ParcelFileDescriptor sigFd = shell.openFileForSystem(signaturePath, "r")) {
            if (fontFd == null) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_FAILED_TO_OPEN_FONT_FILE,
                        "Failed to open font file");
            }

            if (sigFd == null) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_FAILED_TO_OPEN_SIGNATURE_FILE,
                        "Failed to open signature file");
            }

            byte[] signature;
            try (FileInputStream sigFis = new FileInputStream(sigFd.getFileDescriptor())) {
                int len = sigFis.available();
                if (len > MAX_SIGNATURE_FILE_SIZE_BYTES) {
                    throw new SystemFontException(
                            FontManager.RESULT_ERROR_SIGNATURE_TOO_LARGE,
                            "Signature file is too large");
                }
                signature = new byte[len];
                if (sigFis.read(signature, 0, len) != len) {
                    throw new SystemFontException(
                            FontManager.RESULT_ERROR_INVALID_SIGNATURE_FILE,
                            "Invalid read length");
                }
            } catch (IOException e) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_INVALID_SIGNATURE_FILE,
                        "Failed to read signature file.", e);
            }
            mService.update(
                    -1, Collections.singletonList(new FontUpdateRequest(fontFd, signature)));
        } catch (IOException e) {
            // We should reach here only when close() threw IOException.
            // shell.openFileForSystem() and FontManagerService.update() don't throw IOException.
            Slog.w(TAG, "Error while closing files", e);
        }

        shell.getOutPrintWriter().println("Success");  // TODO: Output more details.
        return 0;
    }

    private int updateFamily(ShellCommand shell) throws SystemFontException {
        String xmlPath = shell.getNextArg();
        if (xmlPath == null) {
            throw new SystemFontException(
                    FontManager.RESULT_ERROR_INVALID_SHELL_ARGUMENT,
                    "XML file path argument is required.");
        }

        List<FontUpdateRequest> requests;
        try (ParcelFileDescriptor xmlFd = shell.openFileForSystem(xmlPath, "r")) {
            requests = parseFontFamilyUpdateXml(new FileInputStream(xmlFd.getFileDescriptor()));
        } catch (IOException e) {
            throw new SystemFontException(
                    FontManager.RESULT_ERROR_FAILED_TO_OPEN_XML_FILE,
                    "Failed to open XML file.", e);
        }
        mService.update(-1, requests);
        shell.getOutPrintWriter().println("Success");
        return 0;
    }

    /**
     * Parses XML representing {@link android.graphics.fonts.FontFamilyUpdateRequest}.
     *
     * <p>The format is like:
     * <pre>{@code
     *   <fontFamilyUpdateRequest>
     *       <family name="family-name">
     *           <font name="postScriptName"/>
     *       </family>
     *   </fontFamilyUpdateRequest>
     * }</pre>
     */
    private static List<FontUpdateRequest> parseFontFamilyUpdateXml(InputStream inputStream)
            throws SystemFontException {
        try {
            TypedXmlPullParser parser = Xml.resolvePullParser(inputStream);
            List<FontUpdateRequest> requests = new ArrayList<>();
            int type;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (type != XmlPullParser.START_TAG) {
                    continue;
                }
                final int depth = parser.getDepth();
                final String tag = parser.getName();
                if (depth == 1) {
                    if (!"fontFamilyUpdateRequest".equals(tag)) {
                        throw new SystemFontException(FontManager.RESULT_ERROR_INVALID_XML,
                                "Expected <fontFamilyUpdateRequest> but got: " + tag);
                    }
                } else if (depth == 2) {
                    // TODO: Support including FontFileUpdateRequest
                    if ("family".equals(tag)) {
                        requests.add(new FontUpdateRequest(
                                FontUpdateRequest.Family.readFromXml(parser)));
                    } else {
                        throw new SystemFontException(FontManager.RESULT_ERROR_INVALID_XML,
                                "Expected <family> but got: " + tag);
                    }
                }
            }
            return requests;
        } catch (IOException | XmlPullParserException e) {
            throw new SystemFontException(0, "Failed to parse xml", e);
        }
    }

    private int clear(ShellCommand shell) {
        mService.clearUpdates();
        shell.getOutPrintWriter().println("Success");
        return 0;
    }

    private int restart(ShellCommand shell) {
        mService.restart();
        shell.getOutPrintWriter().println("Success");
        return 0;
    }

    private int status(ShellCommand shell) {
        final IndentingPrintWriter writer =
                new IndentingPrintWriter(shell.getOutPrintWriter(), "  ");
        FontConfig config = mService.getSystemFontConfig();

        writer.println("Current Version: " + config.getConfigVersion());
        LocalDateTime dt = LocalDateTime.ofEpochSecond(config.getLastModifiedTimeMillis(), 0,
                ZoneOffset.UTC);
        writer.println("Last Modified Date: " + dt.format(DateTimeFormatter.ISO_DATE_TIME));

        Map<String, File> fontFileMap = mService.getFontFileMap();
        writer.println("Number of updated font files: " + fontFileMap.size());
        return 0;
    }

    private int execCommand(@NonNull ShellCommand shell, @Nullable String cmd) {
        if (cmd == null) {
            return shell.handleDefaultCommands(null);
        }

        try {
            switch (cmd) {
                case "dump":
                    return dump(shell);
                case "update":
                    return update(shell);
                case "update-family":
                    return updateFamily(shell);
                case "clear":
                    return clear(shell);
                case "restart":
                    return restart(shell);
                case "status":
                    return status(shell);
                default:
                    return shell.handleDefaultCommands(cmd);
            }
        } catch (SystemFontException e) {
            writeCommandResult(shell, e);
            return 1;
        }
    }
}
