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

import android.annotation.NonNull;
import android.content.Context;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.graphics.fonts.FontVariationAxis;
import android.os.ShellCommand;
import android.text.FontConfig;
import android.util.IndentingPrintWriter;

import com.android.internal.util.DumpUtils;

import java.io.File;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;

/**
 * A shell command implementation of font service.
 */
public class FontManagerShellCommand extends ShellCommand {
    private static final String TAG = "FontManagerShellCommand";

    @NonNull private final FontManagerService mService;

    FontManagerShellCommand(@NonNull FontManagerService service) {
        mService = service;
    }

    @Override
    public int onCommand(String cmd) {
        return execCommand(this, cmd);
    }

    @Override
    public void onHelp() {
        dumpHelp(getOutPrintWriter());
    }

    /* package */ void dumpAll(@NonNull IndentingPrintWriter w) {
        final FontManagerService.SystemFontSettings settings = mService.getCurrentFontSettings();
        dumpFontConfig(w, settings.getSystemFontConfig());
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
            if (family.getVariant() != FontConfig.FontFamily.VARIANT_DEFAULT) {
                sb.append("\", variant=");
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

    private static void dumpHelp(@NonNull PrintWriter w) {
        w.println("Font service (font) commands");
        w.println("help");
        w.println("    Print this help text.");
        w.println();
        w.println("dump [family name]");
        w.println("    Dump all font files in the specified family name.");
        w.println("    Dump current system font configuration if no family name was specified.");
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

    private int execCommand(@NonNull ShellCommand shell, @NonNull String cmd) {
        final Context ctx = mService.getContext();
        if (cmd == null) {
            return shell.handleDefaultCommands(null);
        }

        final FontManagerService.SystemFontSettings settings = mService.getCurrentFontSettings();

        switch (cmd) {
            case "dump":
                if (!DumpUtils.checkDumpPermission(ctx, TAG, shell.getErrPrintWriter())) {
                    return 1;
                }
                final IndentingPrintWriter writer =
                        new IndentingPrintWriter(shell.getOutPrintWriter(), "  ");
                String nextArg = shell.getNextArg();
                if (nextArg == null) {
                    dumpFontConfig(writer, settings.getSystemFontConfig());
                } else {
                    final Map<String, FontFamily[]> fallbackMap = settings.getSystemFallbackMap();
                    FontFamily[] families = fallbackMap.get(nextArg);
                    if (families == null) {
                        writer.println("Font Family \"" + nextArg + "\" not found");
                    } else {
                        dumpFallback(writer, families);
                    }
                }
                return 0;
            default:
                shell.handleDefaultCommands(cmd);
        }
        return 0;
    }
}
