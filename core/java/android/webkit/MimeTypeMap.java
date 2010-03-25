/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.webkit;

import java.util.HashMap;
import java.util.regex.Pattern;

/**
 * Two-way map that maps MIME-types to file extensions and vice versa.
 */
public class MimeTypeMap {

    /**
     * Singleton MIME-type map instance:
     */
    private static MimeTypeMap sMimeTypeMap;

    /**
     * MIME-type to file extension mapping:
     */
    private HashMap<String, String> mMimeTypeToExtensionMap;

    /**
     * File extension to MIME type mapping:
     */
    private HashMap<String, String> mExtensionToMimeTypeMap;

    /**
     * Creates a new MIME-type map.
     */
    private MimeTypeMap() {
        mMimeTypeToExtensionMap = new HashMap<String, String>();
        mExtensionToMimeTypeMap = new HashMap<String, String>();
    }

    /**
     * Returns the file extension or an empty string iff there is no
     * extension. This method is a convenience method for obtaining the
     * extension of a url and has undefined results for other Strings.
     * @param url
     * @return The file extension of the given url.
     */
    public static String getFileExtensionFromUrl(String url) {
        if (url != null && url.length() > 0) {
            int query = url.lastIndexOf('?');
            if (query > 0) {
                url = url.substring(0, query);
            }
            int filenamePos = url.lastIndexOf('/');
            String filename =
                0 <= filenamePos ? url.substring(filenamePos + 1) : url;

            // if the filename contains special characters, we don't
            // consider it valid for our matching purposes:
            if (filename.length() > 0 &&
                Pattern.matches("[a-zA-Z_0-9\\.\\-\\(\\)]+", filename)) {
                int dotPos = filename.lastIndexOf('.');
                if (0 <= dotPos) {
                    return filename.substring(dotPos + 1);
                }
            }
        }

        return "";
    }

    /**
     * Load an entry into the map. This does not check if the item already
     * exists, it trusts the caller!
     */
    private void loadEntry(String mimeType, String extension) {
        //
        // if we have an existing x --> y mapping, we do not want to
        // override it with another mapping x --> ?
        // this is mostly because of the way the mime-type map below
        // is constructed (if a mime type maps to several extensions
        // the first extension is considered the most popular and is
        // added first; we do not want to overwrite it later).
        //
        if (!mMimeTypeToExtensionMap.containsKey(mimeType)) {
            mMimeTypeToExtensionMap.put(mimeType, extension);
        }

        mExtensionToMimeTypeMap.put(extension, mimeType);
    }

    /**
     * Return true if the given MIME type has an entry in the map.
     * @param mimeType A MIME type (i.e. text/plain)
     * @return True iff there is a mimeType entry in the map.
     */
    public boolean hasMimeType(String mimeType) {
        if (mimeType != null && mimeType.length() > 0) {
            return mMimeTypeToExtensionMap.containsKey(mimeType);
        }

        return false;
    }

    /**
     * Return the MIME type for the given extension.
     * @param extension A file extension without the leading '.'
     * @return The MIME type for the given extension or null iff there is none.
     */
    public String getMimeTypeFromExtension(String extension) {
        if (extension != null && extension.length() > 0) {
            return mExtensionToMimeTypeMap.get(extension);
        }

        return null;
    }

    // Static method called by jni.
    private static String mimeTypeFromExtension(String extension) {
        return getSingleton().getMimeTypeFromExtension(extension);
    }

    /**
     * Return true if the given extension has a registered MIME type.
     * @param extension A file extension without the leading '.'
     * @return True iff there is an extension entry in the map.
     */
    public boolean hasExtension(String extension) {
        if (extension != null && extension.length() > 0) {
            return mExtensionToMimeTypeMap.containsKey(extension);
        }
        return false;
    }

    /**
     * Return the registered extension for the given MIME type. Note that some
     * MIME types map to multiple extensions. This call will return the most
     * common extension for the given MIME type.
     * @param mimeType A MIME type (i.e. text/plain)
     * @return The extension for the given MIME type or null iff there is none.
     */
    public String getExtensionFromMimeType(String mimeType) {
        if (mimeType != null && mimeType.length() > 0) {
            return mMimeTypeToExtensionMap.get(mimeType);
        }

        return null;
    }

    /**
     * Get the singleton instance of MimeTypeMap.
     * @return The singleton instance of the MIME-type map.
     */
    public static MimeTypeMap getSingleton() {
        if (sMimeTypeMap == null) {
            sMimeTypeMap = new MimeTypeMap();

            // The following table is based on /etc/mime.types data minus
            // chemical/* MIME types and MIME types that don't map to any
            // file extensions. We also exclude top-level domain names to
            // deal with cases like:
            //
            // mail.google.com/a/google.com
            //
            // and "active" MIME types (due to potential security issues).

            sMimeTypeMap.loadEntry("application/andrew-inset", "ez");
            sMimeTypeMap.loadEntry("application/dsptype", "tsp");
            sMimeTypeMap.loadEntry("application/futuresplash", "spl");
            sMimeTypeMap.loadEntry("application/hta", "hta");
            sMimeTypeMap.loadEntry("application/mac-binhex40", "hqx");
            sMimeTypeMap.loadEntry("application/mac-compactpro", "cpt");
            sMimeTypeMap.loadEntry("application/mathematica", "nb");
            sMimeTypeMap.loadEntry("application/msaccess", "mdb");
            sMimeTypeMap.loadEntry("application/oda", "oda");
            sMimeTypeMap.loadEntry("application/ogg", "ogg");
            sMimeTypeMap.loadEntry("application/pdf", "pdf");
            sMimeTypeMap.loadEntry("application/pgp-keys", "key");
            sMimeTypeMap.loadEntry("application/pgp-signature", "pgp");
            sMimeTypeMap.loadEntry("application/pics-rules", "prf");
            sMimeTypeMap.loadEntry("application/rar", "rar");
            sMimeTypeMap.loadEntry("application/rdf+xml", "rdf");
            sMimeTypeMap.loadEntry("application/rss+xml", "rss");
            sMimeTypeMap.loadEntry("application/zip", "zip");
            sMimeTypeMap.loadEntry("application/vnd.android.package-archive", 
                    "apk");
            sMimeTypeMap.loadEntry("application/vnd.cinderella", "cdy");
            sMimeTypeMap.loadEntry("application/vnd.ms-pki.stl", "stl");
            sMimeTypeMap.loadEntry(
                    "application/vnd.oasis.opendocument.database", "odb");
            sMimeTypeMap.loadEntry(
                    "application/vnd.oasis.opendocument.formula", "odf");
            sMimeTypeMap.loadEntry(
                    "application/vnd.oasis.opendocument.graphics", "odg");
            sMimeTypeMap.loadEntry(
                    "application/vnd.oasis.opendocument.graphics-template",
                    "otg");
            sMimeTypeMap.loadEntry(
                    "application/vnd.oasis.opendocument.image", "odi");
            sMimeTypeMap.loadEntry(
                    "application/vnd.oasis.opendocument.spreadsheet", "ods");
            sMimeTypeMap.loadEntry(
                    "application/vnd.oasis.opendocument.spreadsheet-template",
                    "ots");
            sMimeTypeMap.loadEntry(
                    "application/vnd.oasis.opendocument.text", "odt");
            sMimeTypeMap.loadEntry(
                    "application/vnd.oasis.opendocument.text-master", "odm");
            sMimeTypeMap.loadEntry(
                    "application/vnd.oasis.opendocument.text-template", "ott");
            sMimeTypeMap.loadEntry(
                    "application/vnd.oasis.opendocument.text-web", "oth");
            sMimeTypeMap.loadEntry("application/msword", "doc");
            sMimeTypeMap.loadEntry("application/msword", "dot");
            sMimeTypeMap.loadEntry(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "docx");
            sMimeTypeMap.loadEntry(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.template",
                    "dotx");
            sMimeTypeMap.loadEntry("application/vnd.ms-excel", "xls");
            sMimeTypeMap.loadEntry("application/vnd.ms-excel", "xlt");
            sMimeTypeMap.loadEntry(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "xlsx");
            sMimeTypeMap.loadEntry(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.template",
                    "xltx");
            sMimeTypeMap.loadEntry("application/vnd.ms-powerpoint", "ppt");
            sMimeTypeMap.loadEntry("application/vnd.ms-powerpoint", "pot");
            sMimeTypeMap.loadEntry("application/vnd.ms-powerpoint", "pps");
            sMimeTypeMap.loadEntry(
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "pptx");
            sMimeTypeMap.loadEntry(
                    "application/vnd.openxmlformats-officedocument.presentationml.template",
                    "potx");
            sMimeTypeMap.loadEntry(
                    "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
                    "ppsx");
            sMimeTypeMap.loadEntry("application/vnd.rim.cod", "cod");
            sMimeTypeMap.loadEntry("application/vnd.smaf", "mmf");
            sMimeTypeMap.loadEntry("application/vnd.stardivision.calc", "sdc");
            sMimeTypeMap.loadEntry("application/vnd.stardivision.draw", "sda");
            sMimeTypeMap.loadEntry(
                    "application/vnd.stardivision.impress", "sdd");
            sMimeTypeMap.loadEntry(
                    "application/vnd.stardivision.impress", "sdp");
            sMimeTypeMap.loadEntry("application/vnd.stardivision.math", "smf");
            sMimeTypeMap.loadEntry("application/vnd.stardivision.writer",
                    "sdw");
            sMimeTypeMap.loadEntry("application/vnd.stardivision.writer",
                    "vor");
            sMimeTypeMap.loadEntry(
                    "application/vnd.stardivision.writer-global", "sgl");
            sMimeTypeMap.loadEntry("application/vnd.sun.xml.calc", "sxc");
            sMimeTypeMap.loadEntry(
                    "application/vnd.sun.xml.calc.template", "stc");
            sMimeTypeMap.loadEntry("application/vnd.sun.xml.draw", "sxd");
            sMimeTypeMap.loadEntry(
                    "application/vnd.sun.xml.draw.template", "std");
            sMimeTypeMap.loadEntry("application/vnd.sun.xml.impress", "sxi");
            sMimeTypeMap.loadEntry(
                    "application/vnd.sun.xml.impress.template", "sti");
            sMimeTypeMap.loadEntry("application/vnd.sun.xml.math", "sxm");
            sMimeTypeMap.loadEntry("application/vnd.sun.xml.writer", "sxw");
            sMimeTypeMap.loadEntry(
                    "application/vnd.sun.xml.writer.global", "sxg");
            sMimeTypeMap.loadEntry(
                    "application/vnd.sun.xml.writer.template", "stw");
            sMimeTypeMap.loadEntry("application/vnd.visio", "vsd");
            sMimeTypeMap.loadEntry("application/x-abiword", "abw");
            sMimeTypeMap.loadEntry("application/x-apple-diskimage", "dmg");
            sMimeTypeMap.loadEntry("application/x-bcpio", "bcpio");
            sMimeTypeMap.loadEntry("application/x-bittorrent", "torrent");
            sMimeTypeMap.loadEntry("application/x-cdf", "cdf");
            sMimeTypeMap.loadEntry("application/x-cdlink", "vcd");
            sMimeTypeMap.loadEntry("application/x-chess-pgn", "pgn");
            sMimeTypeMap.loadEntry("application/x-cpio", "cpio");
            sMimeTypeMap.loadEntry("application/x-debian-package", "deb");
            sMimeTypeMap.loadEntry("application/x-debian-package", "udeb");
            sMimeTypeMap.loadEntry("application/x-director", "dcr");
            sMimeTypeMap.loadEntry("application/x-director", "dir");
            sMimeTypeMap.loadEntry("application/x-director", "dxr");
            sMimeTypeMap.loadEntry("application/x-dms", "dms");
            sMimeTypeMap.loadEntry("application/x-doom", "wad");
            sMimeTypeMap.loadEntry("application/x-dvi", "dvi");
            sMimeTypeMap.loadEntry("application/x-flac", "flac");
            sMimeTypeMap.loadEntry("application/x-font", "pfa");
            sMimeTypeMap.loadEntry("application/x-font", "pfb");
            sMimeTypeMap.loadEntry("application/x-font", "gsf");
            sMimeTypeMap.loadEntry("application/x-font", "pcf");
            sMimeTypeMap.loadEntry("application/x-font", "pcf.Z");
            sMimeTypeMap.loadEntry("application/x-freemind", "mm");
            sMimeTypeMap.loadEntry("application/x-futuresplash", "spl");
            sMimeTypeMap.loadEntry("application/x-gnumeric", "gnumeric");
            sMimeTypeMap.loadEntry("application/x-go-sgf", "sgf");
            sMimeTypeMap.loadEntry("application/x-graphing-calculator", "gcf");
            sMimeTypeMap.loadEntry("application/x-gtar", "gtar");
            sMimeTypeMap.loadEntry("application/x-gtar", "tgz");
            sMimeTypeMap.loadEntry("application/x-gtar", "taz");
            sMimeTypeMap.loadEntry("application/x-hdf", "hdf");
            sMimeTypeMap.loadEntry("application/x-ica", "ica");
            sMimeTypeMap.loadEntry("application/x-internet-signup", "ins");
            sMimeTypeMap.loadEntry("application/x-internet-signup", "isp");
            sMimeTypeMap.loadEntry("application/x-iphone", "iii");
            sMimeTypeMap.loadEntry("application/x-iso9660-image", "iso");
            sMimeTypeMap.loadEntry("application/x-jmol", "jmz");
            sMimeTypeMap.loadEntry("application/x-kchart", "chrt");
            sMimeTypeMap.loadEntry("application/x-killustrator", "kil");
            sMimeTypeMap.loadEntry("application/x-koan", "skp");
            sMimeTypeMap.loadEntry("application/x-koan", "skd");
            sMimeTypeMap.loadEntry("application/x-koan", "skt");
            sMimeTypeMap.loadEntry("application/x-koan", "skm");
            sMimeTypeMap.loadEntry("application/x-kpresenter", "kpr");
            sMimeTypeMap.loadEntry("application/x-kpresenter", "kpt");
            sMimeTypeMap.loadEntry("application/x-kspread", "ksp");
            sMimeTypeMap.loadEntry("application/x-kword", "kwd");
            sMimeTypeMap.loadEntry("application/x-kword", "kwt");
            sMimeTypeMap.loadEntry("application/x-latex", "latex");
            sMimeTypeMap.loadEntry("application/x-lha", "lha");
            sMimeTypeMap.loadEntry("application/x-lzh", "lzh");
            sMimeTypeMap.loadEntry("application/x-lzx", "lzx");
            sMimeTypeMap.loadEntry("application/x-maker", "frm");
            sMimeTypeMap.loadEntry("application/x-maker", "maker");
            sMimeTypeMap.loadEntry("application/x-maker", "frame");
            sMimeTypeMap.loadEntry("application/x-maker", "fb");
            sMimeTypeMap.loadEntry("application/x-maker", "book");
            sMimeTypeMap.loadEntry("application/x-maker", "fbdoc");
            sMimeTypeMap.loadEntry("application/x-mif", "mif");
            sMimeTypeMap.loadEntry("application/x-ms-wmd", "wmd");
            sMimeTypeMap.loadEntry("application/x-ms-wmz", "wmz");
            sMimeTypeMap.loadEntry("application/x-msi", "msi");
            sMimeTypeMap.loadEntry("application/x-ns-proxy-autoconfig", "pac");
            sMimeTypeMap.loadEntry("application/x-nwc", "nwc");
            sMimeTypeMap.loadEntry("application/x-object", "o");
            sMimeTypeMap.loadEntry("application/x-oz-application", "oza");
            sMimeTypeMap.loadEntry("application/x-pkcs12", "p12");
            sMimeTypeMap.loadEntry("application/x-pkcs7-certreqresp", "p7r");
            sMimeTypeMap.loadEntry("application/x-pkcs7-crl", "crl");
            sMimeTypeMap.loadEntry("application/x-quicktimeplayer", "qtl");
            sMimeTypeMap.loadEntry("application/x-shar", "shar");
            sMimeTypeMap.loadEntry("application/x-shockwave-flash", "swf");
            sMimeTypeMap.loadEntry("application/x-stuffit", "sit");
            sMimeTypeMap.loadEntry("application/x-sv4cpio", "sv4cpio");
            sMimeTypeMap.loadEntry("application/x-sv4crc", "sv4crc");
            sMimeTypeMap.loadEntry("application/x-tar", "tar");
            sMimeTypeMap.loadEntry("application/x-texinfo", "texinfo");
            sMimeTypeMap.loadEntry("application/x-texinfo", "texi");
            sMimeTypeMap.loadEntry("application/x-troff", "t");
            sMimeTypeMap.loadEntry("application/x-troff", "roff");
            sMimeTypeMap.loadEntry("application/x-troff-man", "man");
            sMimeTypeMap.loadEntry("application/x-ustar", "ustar");
            sMimeTypeMap.loadEntry("application/x-wais-source", "src");
            sMimeTypeMap.loadEntry("application/x-wingz", "wz");
            sMimeTypeMap.loadEntry("application/x-webarchive", "webarchive");
            sMimeTypeMap.loadEntry("application/x-x509-ca-cert", "crt");
            sMimeTypeMap.loadEntry("application/x-x509-user-cert", "crt");
            sMimeTypeMap.loadEntry("application/x-xcf", "xcf");
            sMimeTypeMap.loadEntry("application/x-xfig", "fig");
            sMimeTypeMap.loadEntry("application/xhtml+xml", "xhtml");
            sMimeTypeMap.loadEntry("audio/3gpp", "3gpp");
            sMimeTypeMap.loadEntry("audio/basic", "snd");
            sMimeTypeMap.loadEntry("audio/midi", "mid");
            sMimeTypeMap.loadEntry("audio/midi", "midi");
            sMimeTypeMap.loadEntry("audio/midi", "kar");
            sMimeTypeMap.loadEntry("audio/mpeg", "mpga");
            sMimeTypeMap.loadEntry("audio/mpeg", "mpega");
            sMimeTypeMap.loadEntry("audio/mpeg", "mp2");
            sMimeTypeMap.loadEntry("audio/mpeg", "mp3");
            sMimeTypeMap.loadEntry("audio/mpeg", "m4a");
            sMimeTypeMap.loadEntry("audio/mpegurl", "m3u");
            sMimeTypeMap.loadEntry("audio/prs.sid", "sid");
            sMimeTypeMap.loadEntry("audio/x-aiff", "aif");
            sMimeTypeMap.loadEntry("audio/x-aiff", "aiff");
            sMimeTypeMap.loadEntry("audio/x-aiff", "aifc");
            sMimeTypeMap.loadEntry("audio/x-gsm", "gsm");
            sMimeTypeMap.loadEntry("audio/x-mpegurl", "m3u");
            sMimeTypeMap.loadEntry("audio/x-ms-wma", "wma");
            sMimeTypeMap.loadEntry("audio/x-ms-wax", "wax");
            sMimeTypeMap.loadEntry("audio/x-pn-realaudio", "ra");
            sMimeTypeMap.loadEntry("audio/x-pn-realaudio", "rm");
            sMimeTypeMap.loadEntry("audio/x-pn-realaudio", "ram");
            sMimeTypeMap.loadEntry("audio/x-realaudio", "ra");
            sMimeTypeMap.loadEntry("audio/x-scpls", "pls");
            sMimeTypeMap.loadEntry("audio/x-sd2", "sd2");
            sMimeTypeMap.loadEntry("audio/x-wav", "wav");
            sMimeTypeMap.loadEntry("image/bmp", "bmp");
            sMimeTypeMap.loadEntry("image/gif", "gif");
            sMimeTypeMap.loadEntry("image/ico", "cur");
            sMimeTypeMap.loadEntry("image/ico", "ico");
            sMimeTypeMap.loadEntry("image/ief", "ief");
            sMimeTypeMap.loadEntry("image/jpeg", "jpeg");
            sMimeTypeMap.loadEntry("image/jpeg", "jpg");
            sMimeTypeMap.loadEntry("image/jpeg", "jpe");
            sMimeTypeMap.loadEntry("image/pcx", "pcx");
            sMimeTypeMap.loadEntry("image/png", "png");
            sMimeTypeMap.loadEntry("image/svg+xml", "svg");
            sMimeTypeMap.loadEntry("image/svg+xml", "svgz");
            sMimeTypeMap.loadEntry("image/tiff", "tiff");
            sMimeTypeMap.loadEntry("image/tiff", "tif");
            sMimeTypeMap.loadEntry("image/vnd.djvu", "djvu");
            sMimeTypeMap.loadEntry("image/vnd.djvu", "djv");
            sMimeTypeMap.loadEntry("image/vnd.wap.wbmp", "wbmp");
            sMimeTypeMap.loadEntry("image/x-cmu-raster", "ras");
            sMimeTypeMap.loadEntry("image/x-coreldraw", "cdr");
            sMimeTypeMap.loadEntry("image/x-coreldrawpattern", "pat");
            sMimeTypeMap.loadEntry("image/x-coreldrawtemplate", "cdt");
            sMimeTypeMap.loadEntry("image/x-corelphotopaint", "cpt");
            sMimeTypeMap.loadEntry("image/x-icon", "ico");
            sMimeTypeMap.loadEntry("image/x-jg", "art");
            sMimeTypeMap.loadEntry("image/x-jng", "jng");
            sMimeTypeMap.loadEntry("image/x-ms-bmp", "bmp");
            sMimeTypeMap.loadEntry("image/x-photoshop", "psd");
            sMimeTypeMap.loadEntry("image/x-portable-anymap", "pnm");
            sMimeTypeMap.loadEntry("image/x-portable-bitmap", "pbm");
            sMimeTypeMap.loadEntry("image/x-portable-graymap", "pgm");
            sMimeTypeMap.loadEntry("image/x-portable-pixmap", "ppm");
            sMimeTypeMap.loadEntry("image/x-rgb", "rgb");
            sMimeTypeMap.loadEntry("image/x-xbitmap", "xbm");
            sMimeTypeMap.loadEntry("image/x-xpixmap", "xpm");
            sMimeTypeMap.loadEntry("image/x-xwindowdump", "xwd");
            sMimeTypeMap.loadEntry("model/iges", "igs");
            sMimeTypeMap.loadEntry("model/iges", "iges");
            sMimeTypeMap.loadEntry("model/mesh", "msh");
            sMimeTypeMap.loadEntry("model/mesh", "mesh");
            sMimeTypeMap.loadEntry("model/mesh", "silo");
            sMimeTypeMap.loadEntry("text/calendar", "ics");
            sMimeTypeMap.loadEntry("text/calendar", "icz");
            sMimeTypeMap.loadEntry("text/comma-separated-values", "csv");
            sMimeTypeMap.loadEntry("text/css", "css");
            sMimeTypeMap.loadEntry("text/html", "htm");
            sMimeTypeMap.loadEntry("text/html", "html");
            sMimeTypeMap.loadEntry("text/h323", "323");
            sMimeTypeMap.loadEntry("text/iuls", "uls");
            sMimeTypeMap.loadEntry("text/mathml", "mml");
            // add it first so it will be the default for ExtensionFromMimeType
            sMimeTypeMap.loadEntry("text/plain", "txt");
            sMimeTypeMap.loadEntry("text/plain", "asc");
            sMimeTypeMap.loadEntry("text/plain", "text");
            sMimeTypeMap.loadEntry("text/plain", "diff");
            sMimeTypeMap.loadEntry("text/plain", "po");     // reserve "pot" for vnd.ms-powerpoint
            sMimeTypeMap.loadEntry("text/richtext", "rtx");
            sMimeTypeMap.loadEntry("text/rtf", "rtf");
            sMimeTypeMap.loadEntry("text/texmacs", "ts");
            sMimeTypeMap.loadEntry("text/text", "phps");
            sMimeTypeMap.loadEntry("text/tab-separated-values", "tsv");
            sMimeTypeMap.loadEntry("text/xml", "xml");
            sMimeTypeMap.loadEntry("text/x-bibtex", "bib");
            sMimeTypeMap.loadEntry("text/x-boo", "boo");
            sMimeTypeMap.loadEntry("text/x-c++hdr", "h++");
            sMimeTypeMap.loadEntry("text/x-c++hdr", "hpp");
            sMimeTypeMap.loadEntry("text/x-c++hdr", "hxx");
            sMimeTypeMap.loadEntry("text/x-c++hdr", "hh");
            sMimeTypeMap.loadEntry("text/x-c++src", "c++");
            sMimeTypeMap.loadEntry("text/x-c++src", "cpp");
            sMimeTypeMap.loadEntry("text/x-c++src", "cxx");
            sMimeTypeMap.loadEntry("text/x-chdr", "h");
            sMimeTypeMap.loadEntry("text/x-component", "htc");
            sMimeTypeMap.loadEntry("text/x-csh", "csh");
            sMimeTypeMap.loadEntry("text/x-csrc", "c");
            sMimeTypeMap.loadEntry("text/x-dsrc", "d");
            sMimeTypeMap.loadEntry("text/x-haskell", "hs");
            sMimeTypeMap.loadEntry("text/x-java", "java");
            sMimeTypeMap.loadEntry("text/x-literate-haskell", "lhs");
            sMimeTypeMap.loadEntry("text/x-moc", "moc");
            sMimeTypeMap.loadEntry("text/x-pascal", "p");
            sMimeTypeMap.loadEntry("text/x-pascal", "pas");
            sMimeTypeMap.loadEntry("text/x-pcs-gcd", "gcd");
            sMimeTypeMap.loadEntry("text/x-setext", "etx");
            sMimeTypeMap.loadEntry("text/x-tcl", "tcl");
            sMimeTypeMap.loadEntry("text/x-tex", "tex");
            sMimeTypeMap.loadEntry("text/x-tex", "ltx");
            sMimeTypeMap.loadEntry("text/x-tex", "sty");
            sMimeTypeMap.loadEntry("text/x-tex", "cls");
            sMimeTypeMap.loadEntry("text/x-vcalendar", "vcs");
            sMimeTypeMap.loadEntry("text/x-vcard", "vcf");
            sMimeTypeMap.loadEntry("video/3gpp", "3gpp");
            sMimeTypeMap.loadEntry("video/3gpp", "3gp");
            sMimeTypeMap.loadEntry("video/3gpp", "3g2");
            sMimeTypeMap.loadEntry("video/dl", "dl");
            sMimeTypeMap.loadEntry("video/dv", "dif");
            sMimeTypeMap.loadEntry("video/dv", "dv");
            sMimeTypeMap.loadEntry("video/fli", "fli");
            sMimeTypeMap.loadEntry("video/m4v", "m4v");
            sMimeTypeMap.loadEntry("video/mpeg", "mpeg");
            sMimeTypeMap.loadEntry("video/mpeg", "mpg");
            sMimeTypeMap.loadEntry("video/mpeg", "mpe");
            sMimeTypeMap.loadEntry("video/mp4", "mp4");
            sMimeTypeMap.loadEntry("video/mpeg", "VOB");
            sMimeTypeMap.loadEntry("video/quicktime", "qt");
            sMimeTypeMap.loadEntry("video/quicktime", "mov");
            sMimeTypeMap.loadEntry("video/vnd.mpegurl", "mxu");
            sMimeTypeMap.loadEntry("video/x-la-asf", "lsf");
            sMimeTypeMap.loadEntry("video/x-la-asf", "lsx");
            sMimeTypeMap.loadEntry("video/x-mng", "mng");
            sMimeTypeMap.loadEntry("video/x-ms-asf", "asf");
            sMimeTypeMap.loadEntry("video/x-ms-asf", "asx");
            sMimeTypeMap.loadEntry("video/x-ms-wm", "wm");
            sMimeTypeMap.loadEntry("video/x-ms-wmv", "wmv");
            sMimeTypeMap.loadEntry("video/x-ms-wmx", "wmx");
            sMimeTypeMap.loadEntry("video/x-ms-wvx", "wvx");
            sMimeTypeMap.loadEntry("video/x-msvideo", "avi");
            sMimeTypeMap.loadEntry("video/x-sgi-movie", "movie");
            sMimeTypeMap.loadEntry("x-conference/x-cooltalk", "ice");
            sMimeTypeMap.loadEntry("x-epoc/x-sisx-app", "sisx");
        }

        return sMimeTypeMap;
    }
}
