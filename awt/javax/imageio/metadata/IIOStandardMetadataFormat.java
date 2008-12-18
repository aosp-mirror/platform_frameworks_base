/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package javax.imageio.metadata;

import javax.imageio.ImageTypeSpecifier;
import java.util.ArrayList;

/**
 * The class IIOStandardMetadataFormat describes the rules of the standard
 * metadata format.
 * 
 * @since Android 1.0
 */
class IIOStandardMetadataFormat extends IIOMetadataFormatImpl {

    /**
     * Instantiates a new IIOStandardMetadataFormat.
     */
    public IIOStandardMetadataFormat() {
        super(standardMetadataFormatName, CHILD_POLICY_SOME);
        buildDTD();
    }

    @Override
    public boolean canNodeAppear(String elementName, ImageTypeSpecifier imageType) {
        return true;
    }

    /**
     * Builds the DTD that describes the standard metadata format.
     */
    private void buildDTD() {
        // CHROMA
        addElement("Chroma", standardMetadataFormatName, CHILD_POLICY_SOME);

        addElement("ColorSpaceType", "Chroma", CHILD_POLICY_EMPTY);

        ArrayList<String> values = new ArrayList<String>(27);
        values.add("XYZ");
        values.add("Lab");
        values.add("Luv");
        values.add("YCbCr");
        values.add("Yxy");
        values.add("YCCK");
        values.add("PhotoYCC");
        values.add("RGB");
        values.add("GRAY");
        values.add("HSV");
        values.add("HLS");
        values.add("CMYK");
        values.add("CMY");
        values.add("2CLR");
        values.add("3CLR");
        values.add("4CLR");
        values.add("5CLR");
        values.add("6CLR");
        values.add("7CLR");
        values.add("8CLR");
        values.add("9CLR");
        values.add("ACLR");
        values.add("BCLR");
        values.add("CCLR");
        values.add("DCLR");
        values.add("ECLR");
        values.add("FCLR");
        addAttribute("ColorSpaceType", "name", DATATYPE_STRING, true, null, values);

        addElement("NumChannels", "Chroma", CHILD_POLICY_EMPTY);
        addAttribute("NumChannels", "value", DATATYPE_INTEGER, true, 0, Integer.MAX_VALUE); // list
        // -
        // why
        // ?

        addElement("Gamma", "Chroma", CHILD_POLICY_EMPTY);
        addAttribute("Gamma", "value", DATATYPE_FLOAT, true, null);

        addElement("BlackIsZero", "Chroma", CHILD_POLICY_EMPTY);
        addBooleanAttribute("BlackIsZero", "value", true, true);

        addElement("Palette", "Chroma", 0, Integer.MAX_VALUE); // CHILD_POLICY_REPEAT
        addElement("PaletteEntry", "Palette", CHILD_POLICY_EMPTY);
        addAttribute("PaletteEntry", "index", DATATYPE_INTEGER, true, null);
        addAttribute("PaletteEntry", "red", DATATYPE_INTEGER, true, null);
        addAttribute("PaletteEntry", "green", DATATYPE_INTEGER, true, null);
        addAttribute("PaletteEntry", "blue", DATATYPE_INTEGER, true, null);
        addAttribute("PaletteEntry", "alpha", DATATYPE_INTEGER, false, "255");

        addElement("BackgroundIndex", "Chroma", CHILD_POLICY_EMPTY);
        addAttribute("BackgroundIndex", "value", DATATYPE_INTEGER, true, null);

        addElement("BackgroundColor", "Chroma", CHILD_POLICY_EMPTY);
        addAttribute("BackgroundColor", "red", DATATYPE_INTEGER, true, null);
        addAttribute("BackgroundColor", "green", DATATYPE_INTEGER, true, null);
        addAttribute("BackgroundColor", "blue", DATATYPE_INTEGER, true, null);

        // COMPRESSION
        addElement("Compression", standardMetadataFormatName, CHILD_POLICY_SOME);

        addElement("CompressionTypeName", "Compression", CHILD_POLICY_EMPTY);
        addAttribute("CompressionTypeName", "value", DATATYPE_STRING, true, null);

        addElement("Lossless", "Compression", CHILD_POLICY_EMPTY);
        addBooleanAttribute("Lossless", "value", true, true);

        addElement("NumProgressiveScans", "Compression", CHILD_POLICY_EMPTY);
        addAttribute("NumProgressiveScans", "value", DATATYPE_INTEGER, true, null);

        addElement("BitRate", "Compression", CHILD_POLICY_EMPTY);
        addAttribute("BitRate", "value", DATATYPE_FLOAT, true, null);

        // DATA
        addElement("Data", standardMetadataFormatName, CHILD_POLICY_SOME);

        addElement("PlanarConfiguration", "Data", CHILD_POLICY_EMPTY);
        values = new ArrayList<String>(4);
        values.add("PixelInterleaved");
        values.add("PlaneInterleaved");
        values.add("LineInterleaved");
        values.add("TileInterleaved");
        addAttribute("PlanarConfiguration", "value", DATATYPE_STRING, true, null, values);

        addElement("SampleFormat", "Data", CHILD_POLICY_EMPTY);
        values = new ArrayList<String>(4);
        values.add("SignedIntegral");
        values.add("UnsignedIntegral");
        values.add("Real");
        values.add("Index");
        addAttribute("SampleFormat", "value", DATATYPE_STRING, true, null, values);

        addElement("BitsPerSample", "Data", CHILD_POLICY_EMPTY);
        addAttribute("BitsPerSample", "value", DATATYPE_INTEGER, true, 1, Integer.MAX_VALUE); // list

        addElement("SignificantBitsPerSample", "Data", CHILD_POLICY_EMPTY);
        addAttribute("SignificantBitsPerSample", "value", DATATYPE_INTEGER, true, 1,
                Integer.MAX_VALUE); // list

        addElement("SampleMSB", "Data", CHILD_POLICY_EMPTY);
        addAttribute("SampleMSB", "value", DATATYPE_INTEGER, true, 1, Integer.MAX_VALUE); // list

        // DIMENSION
        addElement("Dimension", standardMetadataFormatName, CHILD_POLICY_SOME);

        addElement("PixelAspectRatio", "Dimension", CHILD_POLICY_EMPTY);
        addAttribute("PixelAspectRatio", "value", DATATYPE_FLOAT, true, null);

        addElement("ImageOrientation", "Dimension", CHILD_POLICY_EMPTY);
        values = new ArrayList<String>(8);
        values.add("Normal");
        values.add("Rotate90");
        values.add("Rotate180");
        values.add("Rotate270");
        values.add("FlipH");
        values.add("FlipV");
        values.add("FlipHRotate90");
        values.add("FlipVRotate90");
        addAttribute("ImageOrientation", "value", DATATYPE_STRING, true, null, values);

        addElement("HorizontalPixelSize", "Dimension", CHILD_POLICY_EMPTY);
        addAttribute("HorizontalPixelSize", "value", DATATYPE_FLOAT, true, null);

        addElement("VerticalPixelSize", "Dimension", CHILD_POLICY_EMPTY);
        addAttribute("VerticalPixelSize", "value", DATATYPE_FLOAT, true, null);

        addElement("HorizontalPhysicalPixelSpacing", "Dimension", CHILD_POLICY_EMPTY);
        addAttribute("HorizontalPhysicalPixelSpacing", "value", DATATYPE_FLOAT, true, null);

        addElement("VerticalPhysicalPixelSpacing", "Dimension", CHILD_POLICY_EMPTY);
        addAttribute("VerticalPhysicalPixelSpacing", "value", DATATYPE_FLOAT, true, null);

        addElement("HorizontalPosition", "Dimension", CHILD_POLICY_EMPTY);
        addAttribute("HorizontalPosition", "value", DATATYPE_FLOAT, true, null);

        addElement("VerticalPosition", "Dimension", CHILD_POLICY_EMPTY);
        addAttribute("VerticalPosition", "value", DATATYPE_FLOAT, true, null);

        addElement("HorizontalPixelOffset", "Dimension", CHILD_POLICY_EMPTY);
        addAttribute("HorizontalPixelOffset", "value", DATATYPE_INTEGER, true, null);

        addElement("VerticalPixelOffset", "Dimension", CHILD_POLICY_EMPTY);
        addAttribute("VerticalPixelOffset", "value", DATATYPE_INTEGER, true, null);

        addElement("HorizontalScreenSize", "Dimension", CHILD_POLICY_EMPTY);
        addAttribute("HorizontalScreenSize", "value", DATATYPE_INTEGER, true, null);

        addElement("VerticalScreenSize", "Dimension", CHILD_POLICY_EMPTY);
        addAttribute("VerticalScreenSize", "value", DATATYPE_INTEGER, true, null);

        // DOCUMENT
        addElement("Document", standardMetadataFormatName, CHILD_POLICY_SOME);

        addElement("FormatVersion", "Document", CHILD_POLICY_EMPTY);
        addAttribute("FormatVersion", "value", DATATYPE_STRING, true, null);

        addElement("SubimageInterpretation", "Document", CHILD_POLICY_EMPTY);
        values = new ArrayList<String>(14);
        values.add("Standalone");
        values.add("SinglePage");
        values.add("FullResolution");
        values.add("ReducedResolution");
        values.add("PyramidLayer");
        values.add("Preview");
        values.add("VolumeSlice");
        values.add("ObjectView");
        values.add("Panorama");
        values.add("AnimationFrame");
        values.add("TransparencyMask");
        values.add("CompositingLayer");
        values.add("SpectralSlice");
        values.add("Unknown");
        addAttribute("SubimageInterpretation", "value", DATATYPE_STRING, true, null, values);

        addElement("ImageCreationTime", "Document", CHILD_POLICY_EMPTY);
        addAttribute("ImageCreationTime", "year", DATATYPE_INTEGER, true, null);
        addAttribute("ImageCreationTime", "month", DATATYPE_INTEGER, true, null, "1", "12", true,
                true);
        addAttribute("ImageCreationTime", "day", DATATYPE_INTEGER, true, null, "1", "31", true,
                true);
        addAttribute("ImageCreationTime", "hour", DATATYPE_INTEGER, false, "0", "0", "23", true,
                true);
        addAttribute("ImageCreationTime", "minute", DATATYPE_INTEGER, false, "0", "0", "59", true,
                true);
        addAttribute("ImageCreationTime", "second", DATATYPE_INTEGER, false, "0", "0", "60", true,
                true);

        addElement("ImageModificationTime", "Document", CHILD_POLICY_EMPTY);
        addAttribute("ImageModificationTime", "year", DATATYPE_INTEGER, true, null);
        addAttribute("ImageModificationTime", "month", DATATYPE_INTEGER, true, null, "1", "12",
                true, true);
        addAttribute("ImageModificationTime", "day", DATATYPE_INTEGER, true, null, "1", "31", true,
                true);
        addAttribute("ImageModificationTime", "hour", DATATYPE_INTEGER, false, "0", "0", "23",
                true, true);
        addAttribute("ImageModificationTime", "minute", DATATYPE_INTEGER, false, "0", "0", "59",
                true, true);
        addAttribute("ImageModificationTime", "second", DATATYPE_INTEGER, false, "0", "0", "60",
                true, true);

        // TEXT
        addElement("Text", standardMetadataFormatName, 0, Integer.MAX_VALUE); // CHILD_POLICY_REPEAT

        addElement("TextEntry", "Text", CHILD_POLICY_EMPTY);
        addAttribute("TextEntry", "keyword", DATATYPE_STRING, false, null);
        addAttribute("TextEntry", "value", DATATYPE_STRING, true, null);
        addAttribute("TextEntry", "language", DATATYPE_STRING, false, null);
        addAttribute("TextEntry", "encoding", DATATYPE_STRING, false, null);
        values = new ArrayList<String>(5);
        values.add("none");
        values.add("lzw");
        values.add("zip");
        values.add("bzip");
        values.add("other");
        addAttribute("TextEntry", "compression", DATATYPE_STRING, false, "none", values);

        // TRANSPARENCY
        addElement("Transparency", standardMetadataFormatName, CHILD_POLICY_SOME);

        addElement("Alpha", "Transparency", CHILD_POLICY_EMPTY);
        values = new ArrayList<String>(3);
        values.add("none");
        values.add("premultiplied");
        values.add("nonpremultiplied");
        addAttribute("Alpha", "value", DATATYPE_STRING, false, "none", values);

        addElement("TransparentIndex", "Transparency", CHILD_POLICY_EMPTY);
        addAttribute("TransparentIndex", "value", DATATYPE_INTEGER, true, null);

        addElement("TransparentColor", "Transparency", CHILD_POLICY_EMPTY);
        addAttribute("TransparentColor", "value", DATATYPE_INTEGER, true, 0, Integer.MAX_VALUE);

        addElement("TileTransparencies", "Transparency", 0, Integer.MAX_VALUE); // CHILD_POLICY_REPEAT

        addElement("TransparentTile", "TileTransparencies", CHILD_POLICY_EMPTY);
        addAttribute("TransparentTile", "x", DATATYPE_INTEGER, true, null);
        addAttribute("TransparentTile", "y", DATATYPE_INTEGER, true, null);

        addElement("TileOpacities", "Transparency", 0, Integer.MAX_VALUE); // CHILD_POLICY_REPEAT

        addElement("OpaqueTile", "TileOpacities", CHILD_POLICY_EMPTY);
        addAttribute("OpaqueTile", "x", DATATYPE_INTEGER, true, null);
        addAttribute("OpaqueTile", "y", DATATYPE_INTEGER, true, null);
    }
}
