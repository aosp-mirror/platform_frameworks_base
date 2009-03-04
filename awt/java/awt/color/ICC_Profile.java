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
/**
 * @author Oleg V. Khaschansky
 * @version $Revision$
 */

package java.awt.color;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamException;
import java.io.OutputStream;
import java.io.Serializable;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.StringTokenizer;

import org.apache.harmony.awt.gl.color.ICC_ProfileHelper;
import org.apache.harmony.awt.gl.color.NativeCMM;
import org.apache.harmony.awt.internal.nls.Messages;

/**
 * The ICC_Profile class represents a color profile data for color spaces based
 * on the International Color Consortium Specification ICC.1:2001-12, File
 * Format for Color Profiles.
 * 
 * @since Android 1.0
 */
public class ICC_Profile implements Serializable {

    /**
     * The Constant serialVersionUID.
     */
    private static final long serialVersionUID = -3938515861990936766L;

    // NOTE: Constant field values are noted in 1.5 specification.

    /**
     * The Constant CLASS_INPUT indicates that profile class is input.
     */
    public static final int CLASS_INPUT = 0;

    /**
     * The Constant CLASS_DISPLAY indicates that profile class is display.
     */
    public static final int CLASS_DISPLAY = 1;

    /**
     * The Constant CLASS_OUTPUT indicates that profile class is output.
     */
    public static final int CLASS_OUTPUT = 2;

    /**
     * The Constant CLASS_DEVICELINK indicates that profile class is device
     * link.
     */
    public static final int CLASS_DEVICELINK = 3;

    /**
     * The Constant CLASS_COLORSPACECONVERSION indicates that profile class is
     * color space conversion.
     */
    public static final int CLASS_COLORSPACECONVERSION = 4;

    /**
     * The Constant CLASS_ABSTRACT indicates that profile class is abstract.
     */
    public static final int CLASS_ABSTRACT = 5;

    /**
     * The Constant CLASS_NAMEDCOLOR indicates that profile class is named
     * color.
     */
    public static final int CLASS_NAMEDCOLOR = 6;

    /**
     * The Constant icSigXYZData - ICC Profile Color Space Type Signature.
     */
    public static final int icSigXYZData = 1482250784;

    /**
     * The Constant icSigLabData - ICC Profile Color Space Type Signature.
     */
    public static final int icSigLabData = 1281450528;

    /**
     * The Constant icSigLuvData - ICC Profile Color Space Type Signature.
     */
    public static final int icSigLuvData = 1282766368;

    /**
     * The Constant icSigYCbCrData - ICC Profile Color Space Type Signature.
     */
    public static final int icSigYCbCrData = 1497588338;

    /**
     * The Constant icSigYxyData - ICC Profile Color Space Type Signature.
     */
    public static final int icSigYxyData = 1501067552;

    /**
     * The Constant icSigRgbData - ICC Profile Color Space Type Signature.
     */
    public static final int icSigRgbData = 1380401696;

    /**
     * The Constant icSigGrayData - ICC Profile Color Space Type Signature.
     */
    public static final int icSigGrayData = 1196573017;

    /**
     * The Constant icSigHsvData - ICC Profile Color Space Type Signature.
     */
    public static final int icSigHsvData = 1213421088;

    /**
     * The Constant icSigHlsData - ICC Profile Color Space Type Signature.
     */
    public static final int icSigHlsData = 1212961568;

    /**
     * The Constant icSigCmykData - ICC Profile Color Space Type Signature.
     */
    public static final int icSigCmykData = 1129142603;

    /**
     * The Constant icSigCmyData - ICC Profile Color Space Type Signature.
     */
    public static final int icSigCmyData = 1129142560;

    /**
     * The Constant icSigSpace2CLR - ICC Profile Color Space Type Signature.
     */
    public static final int icSigSpace2CLR = 843271250;

    /**
     * The Constant icSigSpace3CLR - ICC Profile Color Space Type Signature.
     */
    public static final int icSigSpace3CLR = 860048466;

    /**
     * The Constant icSigSpace4CLR - ICC Profile Color Space Type Signature.
     */
    public static final int icSigSpace4CLR = 876825682;

    /**
     * The Constant icSigSpace5CLR - ICC Profile Color Space Type Signature.
     */
    public static final int icSigSpace5CLR = 893602898;

    /**
     * The Constant icSigSpace6CLR - ICC Profile Color Space Type Signature.
     */
    public static final int icSigSpace6CLR = 910380114;

    /**
     * The Constant icSigSpace7CLR - ICC Profile Color Space Type Signature.
     */
    public static final int icSigSpace7CLR = 927157330;

    /**
     * The Constant icSigSpace8CLR - ICC Profile Color Space Type Signature.
     */
    public static final int icSigSpace8CLR = 943934546;

    /**
     * The Constant icSigSpace9CLR - ICC Profile Color Space Type Signature.
     */
    public static final int icSigSpace9CLR = 960711762;

    /**
     * The Constant icSigSpaceACLR - ICC Profile Color Space Type Signature.
     */
    public static final int icSigSpaceACLR = 1094929490;

    /**
     * The Constant icSigSpaceBCLR - ICC Profile Color Space Type Signature.
     */
    public static final int icSigSpaceBCLR = 1111706706;

    /**
     * The Constant icSigSpaceCCLR - ICC Profile Color Space Type Signature.
     */
    public static final int icSigSpaceCCLR = 1128483922;

    /**
     * The Constant icSigSpaceDCLR - ICC Profile Color Space Type Signature.
     */
    public static final int icSigSpaceDCLR = 1145261138;

    /**
     * The Constant icSigSpaceECLR - ICC Profile Color Space Type Signature.
     */
    public static final int icSigSpaceECLR = 1162038354;

    /**
     * The Constant icSigSpaceFCLR - ICC Profile Color Space Type Signature.
     */
    public static final int icSigSpaceFCLR = 1178815570;

    /**
     * The Constant icSigInputClass - ICC Profile Class Signature.
     */
    public static final int icSigInputClass = 1935896178;

    /**
     * The Constant icSigDisplayClass - ICC Profile Class Signature.
     */
    public static final int icSigDisplayClass = 1835955314;

    /**
     * The Constant icSigOutputClass - ICC Profile Class Signature.
     */
    public static final int icSigOutputClass = 1886549106;

    /**
     * The Constant icSigLinkClass - ICC Profile Class Signature.
     */
    public static final int icSigLinkClass = 1818848875;

    /**
     * The Constant icSigAbstractClass - ICC Profile Class Signature.
     */
    public static final int icSigAbstractClass = 1633842036;

    /**
     * The Constant icSigColorantOrderTag - ICC Profile Tag Signature.
     */
    public static final int icSigColorantOrderTag = 1668051567;

    /**
     * The Constant icSigColorantTableTag - ICC Profile Tag Signature.
     */
    public static final int icSigColorantTableTag = 1668051572;

    /**
     * The Constant icSigColorSpaceClass - ICC Profile Tag Signature.
     */
    public static final int icSigColorSpaceClass = 1936744803;

    /**
     * The Constant icSigNamedColorClass - ICC Profile Tag Signature.
     */
    public static final int icSigNamedColorClass = 1852662636;

    /**
     * The Constant icPerceptual - ICC Profile Rendering Intent.
     */
    public static final int icPerceptual = 0;

    /**
     * The Constant icRelativeColorimetric - ICC Profile Rendering Intent.
     */
    public static final int icRelativeColorimetric = 1;

    /**
     * The Constant icSaturation - ICC Profile Rendering Intent.
     */
    public static final int icSaturation = 2;

    /**
     * The Constant icAbsoluteColorimetric - ICC Profile Rendering Intent.
     */
    public static final int icAbsoluteColorimetric = 3;

    /**
     * The Constant icSigHead - ICC Profile Tag Signature.
     */
    public static final int icSigHead = 1751474532;

    /**
     * The Constant icSigAToB0Tag - ICC Profile Tag Signature.
     */
    public static final int icSigAToB0Tag = 1093812784;

    /**
     * The Constant icSigAToB1Tag - ICC Profile Tag Signature.
     */
    public static final int icSigAToB1Tag = 1093812785;

    /**
     * The Constant icSigAToB2Tag - ICC Profile Tag Signature.
     */
    public static final int icSigAToB2Tag = 1093812786;

    /**
     * The Constant icSigBlueColorantTag - ICC Profile Tag Signature.
     */
    public static final int icSigBlueColorantTag = 1649957210;

    /**
     * The Constant icSigBlueMatrixColumnTag - ICC Profile Tag Signature.
     */
    public static final int icSigBlueMatrixColumnTag = 1649957210;

    /**
     * The Constant icSigBlueTRCTag - ICC Profile Tag Signature.
     */
    public static final int icSigBlueTRCTag = 1649693251;

    /**
     * The Constant icSigBToA0Tag - ICC Profile Tag Signature.
     */
    public static final int icSigBToA0Tag = 1110589744;

    /**
     * The Constant icSigBToA1Tag - ICC Profile Tag Signature.
     */
    public static final int icSigBToA1Tag = 1110589745;

    /**
     * The Constant icSigBToA2Tag - ICC Profile Tag Signature.
     */
    public static final int icSigBToA2Tag = 1110589746;

    /**
     * The Constant icSigCalibrationDateTimeTag - ICC Profile Tag Signature.
     */
    public static final int icSigCalibrationDateTimeTag = 1667329140;

    /**
     * The Constant icSigCharTargetTag - ICC Profile Tag Signature.
     */
    public static final int icSigCharTargetTag = 1952543335;

    /**
     * The Constant icSigCopyrightTag - ICC Profile Tag Signature.
     */
    public static final int icSigCopyrightTag = 1668313716;

    /**
     * The Constant icSigCrdInfoTag - ICC Profile Tag Signature.
     */
    public static final int icSigCrdInfoTag = 1668441193;

    /**
     * The Constant icSigDeviceMfgDescTag - ICC Profile Tag Signature.
     */
    public static final int icSigDeviceMfgDescTag = 1684893284;

    /**
     * The Constant icSigDeviceModelDescTag - ICC Profile Tag Signature.
     */
    public static final int icSigDeviceModelDescTag = 1684890724;

    /**
     * The Constant icSigDeviceSettingsTag - ICC Profile Tag Signature.
     */
    public static final int icSigDeviceSettingsTag = 1684371059;

    /**
     * The Constant icSigGamutTag - ICC Profile Tag Signature.
     */
    public static final int icSigGamutTag = 1734438260;

    /**
     * The Constant icSigGrayTRCTag - ICC Profile Tag Signature.
     */
    public static final int icSigGrayTRCTag = 1800688195;

    /**
     * The Constant icSigGreenColorantTag - ICC Profile Tag Signature.
     */
    public static final int icSigGreenColorantTag = 1733843290;

    /**
     * The Constant icSigGreenMatrixColumnTag - ICC Profile Tag Signature.
     */
    public static final int icSigGreenMatrixColumnTag = 1733843290;

    /**
     * The Constant icSigGreenTRCTag - ICC Profile Tag Signature.
     */
    public static final int icSigGreenTRCTag = 1733579331;

    /**
     * The Constant icSigLuminanceTag - ICC Profile Tag Signature.
     */
    public static final int icSigLuminanceTag = 1819635049;

    /**
     * The Constant icSigMeasurementTag - ICC Profile Tag Signature.
     */
    public static final int icSigMeasurementTag = 1835360627;

    /**
     * The Constant icSigMediaBlackPointTag - ICC Profile Tag Signature.
     */
    public static final int icSigMediaBlackPointTag = 1651208308;

    /**
     * The Constant icSigMediaWhitePointTag - ICC Profile Tag Signature.
     */
    public static final int icSigMediaWhitePointTag = 2004119668;

    /**
     * The Constant icSigNamedColor2Tag - ICC Profile Tag Signature.
     */
    public static final int icSigNamedColor2Tag = 1852009522;

    /**
     * The Constant icSigOutputResponseTag - ICC Profile Tag Signature.
     */
    public static final int icSigOutputResponseTag = 1919251312;

    /**
     * The Constant icSigPreview0Tag - ICC Profile Tag Signature.
     */
    public static final int icSigPreview0Tag = 1886545200;

    /**
     * The Constant icSigPreview1Tag - ICC Profile Tag Signature.
     */
    public static final int icSigPreview1Tag = 1886545201;

    /**
     * The Constant icSigPreview2Tag - ICC Profile Tag Signature.
     */
    public static final int icSigPreview2Tag = 1886545202;

    /**
     * The Constant icSigProfileDescriptionTag - ICC Profile Tag Signature.
     */
    public static final int icSigProfileDescriptionTag = 1684370275;

    /**
     * The Constant icSigProfileSequenceDescTag - ICC Profile Tag Signature.
     */
    public static final int icSigProfileSequenceDescTag = 1886610801;

    /**
     * The Constant icSigPs2CRD0Tag - ICC Profile Tag Signature.
     */
    public static final int icSigPs2CRD0Tag = 1886610480;

    /**
     * The Constant icSigPs2CRD1Tag - ICC Profile Tag Signature.
     */
    public static final int icSigPs2CRD1Tag = 1886610481;

    /**
     * The Constant icSigPs2CRD2Tag - ICC Profile Tag Signature.
     */
    public static final int icSigPs2CRD2Tag = 1886610482;

    /**
     * The Constant icSigPs2CRD3Tag - ICC Profile Tag Signature.
     */
    public static final int icSigPs2CRD3Tag = 1886610483;

    /**
     * The Constant icSigPs2CSATag - ICC Profile Tag Signature.
     */
    public static final int icSigPs2CSATag = 1886597747;

    /**
     * The Constant icSigPs2RenderingIntentTag - ICC Profile Tag Signature.
     */
    public static final int icSigPs2RenderingIntentTag = 1886597737;

    /**
     * The Constant icSigRedColorantTag - ICC Profile Tag Signature.
     */
    public static final int icSigRedColorantTag = 1918392666;

    /**
     * The Constant icSigRedMatrixColumnTag - ICC Profile Tag Signature.
     */
    public static final int icSigRedMatrixColumnTag = 1918392666;

    /**
     * The Constant icSigRedTRCTag - ICC Profile Tag Signature.
     */
    public static final int icSigRedTRCTag = 1918128707;

    /**
     * The Constant icSigScreeningDescTag - ICC Profile Tag Signature.
     */
    public static final int icSigScreeningDescTag = 1935897188;

    /**
     * The Constant icSigScreeningTag - ICC Profile Tag Signature.
     */
    public static final int icSigScreeningTag = 1935897198;

    /**
     * The Constant icSigTechnologyTag - ICC Profile Tag Signature.
     */
    public static final int icSigTechnologyTag = 1952801640;

    /**
     * The Constant icSigUcrBgTag - ICC Profile Tag Signature.
     */
    public static final int icSigUcrBgTag = 1650877472;

    /**
     * The Constant icSigViewingCondDescTag - ICC Profile Tag Signature.
     */
    public static final int icSigViewingCondDescTag = 1987405156;

    /**
     * The Constant icSigViewingConditionsTag - ICC Profile Tag Signature.
     */
    public static final int icSigViewingConditionsTag = 1986618743;

    /**
     * The Constant icSigChromaticAdaptationTag - ICC Profile Tag Signature.
     */
    public static final int icSigChromaticAdaptationTag = 1667785060;

    /**
     * The Constant icSigChromaticityTag - ICC Profile Tag Signature.
     */
    public static final int icSigChromaticityTag = 1667789421;

    /**
     * The Constant icHdrSize - ICC Profile Header Location.
     */
    public static final int icHdrSize = 0;

    /**
     * The Constant icHdrCmmId - ICC Profile Header Location.
     */
    public static final int icHdrCmmId = 4;

    /**
     * The Constant icHdrVersion - ICC Profile Header Location.
     */
    public static final int icHdrVersion = 8;

    /**
     * The Constant icHdrDeviceClass - ICC Profile Header Location.
     */
    public static final int icHdrDeviceClass = 12;

    /**
     * The Constant icHdrColorSpace - ICC Profile Header Location.
     */
    public static final int icHdrColorSpace = 16;

    /**
     * The Constant icHdrPcs - ICC Profile Header Location.
     */
    public static final int icHdrPcs = 20;

    /**
     * The Constant icHdrDate - ICC Profile Header Location.
     */
    public static final int icHdrDate = 24;

    /**
     * The Constant icHdrMagic - ICC Profile Header Location.
     */
    public static final int icHdrMagic = 36;

    /**
     * The Constant icHdrPlatform - ICC Profile Header Location.
     */
    public static final int icHdrPlatform = 40;

    /**
     * The Constant icHdrProfileID - ICC Profile Header Location.
     */
    public static final int icHdrProfileID = 84;

    /**
     * The Constant icHdrFlags - ICC Profile Header Location.
     */
    public static final int icHdrFlags = 44;

    /**
     * The Constant icHdrManufacturer - ICC Profile Header Location.
     */
    public static final int icHdrManufacturer = 48;

    /**
     * The Constant icHdrModel - ICC Profile Header Location.
     */
    public static final int icHdrModel = 52;

    /**
     * The Constant icHdrAttributes - ICC Profile Header Location.
     */
    public static final int icHdrAttributes = 56;

    /**
     * The Constant icHdrRenderingIntent - ICC Profile Header Location.
     */
    public static final int icHdrRenderingIntent = 64;

    /**
     * The Constant icHdrIlluminant - ICC Profile Header Location.
     */
    public static final int icHdrIlluminant = 68;

    /**
     * The Constant icHdrCreator - ICC Profile Header Location.
     */
    public static final int icHdrCreator = 80;

    /**
     * The Constant icICCAbsoluteColorimetric - ICC Profile Rendering Intent.
     */
    public static final int icICCAbsoluteColorimetric = 3;

    /**
     * The Constant icMediaRelativeColorimetric - ICC Profile Rendering Intent.
     */
    public static final int icMediaRelativeColorimetric = 1;

    /**
     * The Constant icTagType - ICC Profile Constant.
     */
    public static final int icTagType = 0;

    /**
     * The Constant icTagReserved - ICC Profile Constant.
     */
    public static final int icTagReserved = 4;

    /**
     * The Constant icCurveCount - ICC Profile Constant.
     */
    public static final int icCurveCount = 8;

    /**
     * The Constant icCurveData - ICC Profile Constant.
     */
    public static final int icCurveData = 12;

    /**
     * The Constant icXYZNumberX - ICC Profile Constant.
     */
    public static final int icXYZNumberX = 8;

    /**
     * Size of a profile header.
     */
    private static final int headerSize = 128;

    /**
     * header magic number.
     */
    private static final int headerMagicNumber = 0x61637370;

    // Cache of predefined profiles
    /**
     * The s rgb profile.
     */
    private static ICC_Profile sRGBProfile;

    /**
     * The xyz profile.
     */
    private static ICC_Profile xyzProfile;

    /**
     * The gray profile.
     */
    private static ICC_Profile grayProfile;

    /**
     * The pycc profile.
     */
    private static ICC_Profile pyccProfile;

    /**
     * The linear rgb profile.
     */
    private static ICC_Profile linearRGBProfile;

    /**
     * Handle to the current profile.
     */
    private transient long profileHandle = 0;

    /**
     * If handle is used by another class this object is not responsible for
     * closing profile.
     */
    private transient boolean handleStolen = false;

    /**
     * Cached header data.
     */
    private transient byte[] headerData = null;

    /**
     * Serialization support.
     */
    private transient ICC_Profile openedProfileObject;

    /**
     * Instantiates a new ICC profile with the given data.
     * 
     * @param data
     *            the data.
     */
    private ICC_Profile(byte[] data) {
        profileHandle = NativeCMM.cmmOpenProfile(data);
        NativeCMM.addHandle(this, profileHandle);
    }

    /**
     * Used to instantiate dummy ICC_ProfileStub objects.
     */
    ICC_Profile() {
    }

    /**
     * Used to instantiate subclasses (ICC_ProfileGrey and ICC_ProfileRGB).
     * 
     * @param profileHandle
     *            - should be valid handle to opened color profile
     */
    ICC_Profile(long profileHandle) {
        this.profileHandle = profileHandle;
        // A new object reference, need to add it.
        NativeCMM.addHandle(this, profileHandle);
    }

    /**
     * Writes the ICC_Profile to a file with the specified name.
     * 
     * @param fileName
     *            the file name.
     * @throws IOException
     *             if an I/O exception has occurred during writing or opening
     *             the file.
     */
    public void write(String fileName) throws IOException {
        FileOutputStream oStream = new FileOutputStream(fileName);
        oStream.write(getData());
        oStream.close();
    }

    /**
     * Serializable implementation.
     * 
     * @param s
     *            the s
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    private void writeObject(ObjectOutputStream s) throws IOException {
        s.defaultWriteObject();
        s.writeObject(null);
        s.writeObject(getData());
    }

    /**
     * Serializable implementation.
     * 
     * @param s
     *            the s
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     * @throws ClassNotFoundException
     *             the class not found exception
     */
    private void readObject(ObjectInputStream s) throws IOException, ClassNotFoundException {
        s.defaultReadObject();
        String colorSpaceStr = (String)s.readObject();
        byte[] data = (byte[])s.readObject();

        if (colorSpaceStr != null) {
            if (colorSpaceStr.equals("CS_sRGB")) { //$NON-NLS-1$
                openedProfileObject = getInstance(ColorSpace.CS_sRGB);
            } else if (colorSpaceStr.equals("CS_GRAY")) { //$NON-NLS-1$
                openedProfileObject = getInstance(ColorSpace.CS_GRAY);
            } else if (colorSpaceStr.equals("CS_LINEAR_RGB")) { //$NON-NLS-1$
                openedProfileObject = getInstance(ColorSpace.CS_LINEAR_RGB);
            } else if (colorSpaceStr.equals("CS_CIEXYZ")) { //$NON-NLS-1$
                openedProfileObject = getInstance(ColorSpace.CS_CIEXYZ);
            } else if (colorSpaceStr.equals("CS_PYCC")) { //$NON-NLS-1$
                openedProfileObject = getInstance(ColorSpace.CS_PYCC);
            } else {
                openedProfileObject = ICC_Profile.getInstance(data);
            }
        } else {
            openedProfileObject = ICC_Profile.getInstance(data);
        }
    }

    /**
     * Resolves instances being deserialized into instances registered with CMM.
     * 
     * @return ICC_Profile object for profile registered with CMM.
     * @throws ObjectStreamException
     *             if there is an error in the serialized files or during the
     *             process of reading them.
     */
    protected Object readResolve() throws ObjectStreamException {
        return openedProfileObject;
    }

    /**
     * Writes the ICC_Profile to an OutputStream.
     * 
     * @param s
     *            the OutputStream.
     * @throws IOException
     *             signals that an I/O exception has occurred during writing or
     *             opening OutputStream.
     */
    public void write(OutputStream s) throws IOException {
        s.write(getData());
    }

    /**
     * Sets a tagged data element in the profile from a byte array.
     * 
     * @param tagSignature
     *            the ICC tag signature for the data element to be set.
     * @param tagData
     *            the data to be set for the specified tag signature.
     */
    public void setData(int tagSignature, byte[] tagData) {
        NativeCMM.cmmSetProfileElement(profileHandle, tagSignature, tagData);
        // Remove cached header data if header is modified
        if (tagSignature == icSigHead) {
            headerData = null;
        }
    }

    /**
     * Gets a tagged data element from the profile as a byte array. Elements are
     * identified by tag signatures as defined in the ICC specification.
     * 
     * @param tagSignature
     *            the ICC tag signature for the data element to get.
     * @return a byte array that contains the tagged data element.
     */
    public byte[] getData(int tagSignature) {
        int tagSize = 0;
        try {
            tagSize = NativeCMM.cmmGetProfileElementSize(profileHandle, tagSignature);
        } catch (CMMException e) {
            // We'll get this exception if there's no element with
            // the specified tag signature
            return null;
        }

        byte[] data = new byte[tagSize];
        NativeCMM.cmmGetProfileElement(profileHandle, tagSignature, data);
        return data;
    }

    /**
     * Gets a data byte array of this ICC_Profile.
     * 
     * @return a byte array that contains the ICC Profile data.
     */
    public byte[] getData() {
        int profileSize = NativeCMM.cmmGetProfileSize(profileHandle);
        byte[] data = new byte[profileSize];
        NativeCMM.cmmGetProfile(profileHandle, data);
        return data;
    }

    /**
     * Frees the resources associated with an ICC_Profile object.
     */
    @Override
    protected void finalize() {
        if (profileHandle != 0 && !handleStolen) {
            NativeCMM.cmmCloseProfile(profileHandle);
        }

        // Always remove because key no more exist
        // when object is destroyed
        NativeCMM.removeHandle(this);
    }

    /**
     * Gets the profile class.
     * 
     * @return the profile class constant.
     */
    public int getProfileClass() {
        int deviceClassSignature = getIntFromHeader(icHdrDeviceClass);

        switch (deviceClassSignature) {
            case icSigColorSpaceClass:
                return CLASS_COLORSPACECONVERSION;
            case icSigDisplayClass:
                return CLASS_DISPLAY;
            case icSigOutputClass:
                return CLASS_OUTPUT;
            case icSigInputClass:
                return CLASS_INPUT;
            case icSigLinkClass:
                return CLASS_DEVICELINK;
            case icSigAbstractClass:
                return CLASS_ABSTRACT;
            case icSigNamedColorClass:
                return CLASS_NAMEDCOLOR;
            default:
        }

        // Not an ICC profile class
        // awt.15F=Profile class does not comply with ICC specification
        throw new IllegalArgumentException(Messages.getString("awt.15F")); //$NON-NLS-1$

    }

    /**
     * Gets the color space type of the Profile Connection Space (PCS).
     * 
     * @return the PCS type.
     */
    public int getPCSType() {
        return csFromSignature(getIntFromHeader(icHdrPcs));
    }

    /**
     * Gets the number of components of this ICC Profile.
     * 
     * @return the number of components of this ICC Profile.
     */
    public int getNumComponents() {
        switch (getIntFromHeader(icHdrColorSpace)) {
            // The most common cases go first to increase speed
            case icSigRgbData:
            case icSigXYZData:
            case icSigLabData:
                return 3;
            case icSigCmykData:
                return 4;
                // Then all other
            case icSigGrayData:
                return 1;
            case icSigSpace2CLR:
                return 2;
            case icSigYCbCrData:
            case icSigLuvData:
            case icSigYxyData:
            case icSigHlsData:
            case icSigHsvData:
            case icSigCmyData:
            case icSigSpace3CLR:
                return 3;
            case icSigSpace4CLR:
                return 4;
            case icSigSpace5CLR:
                return 5;
            case icSigSpace6CLR:
                return 6;
            case icSigSpace7CLR:
                return 7;
            case icSigSpace8CLR:
                return 8;
            case icSigSpace9CLR:
                return 9;
            case icSigSpaceACLR:
                return 10;
            case icSigSpaceBCLR:
                return 11;
            case icSigSpaceCCLR:
                return 12;
            case icSigSpaceDCLR:
                return 13;
            case icSigSpaceECLR:
                return 14;
            case icSigSpaceFCLR:
                return 15;
            default:
        }

        // awt.160=Color space doesn't comply with ICC specification
        throw new ProfileDataException(Messages.getString("awt.160") //$NON-NLS-1$
        );
    }

    /**
     * Gets the minor version of this ICC profile.
     * 
     * @return the minor version of this ICC profile.
     */
    public int getMinorVersion() {
        return getByteFromHeader(icHdrVersion + 1);
    }

    /**
     * Gets the major version of this ICC profile.
     * 
     * @return the major version of this ICC profile.
     */
    public int getMajorVersion() {
        return getByteFromHeader(icHdrVersion);
    }

    /**
     * Gets the color space type of this ICC_Profile.
     * 
     * @return the color space type.
     */
    public int getColorSpaceType() {
        return csFromSignature(getIntFromHeader(icHdrColorSpace));
    }

    /**
     * Tries to open the file at the specified path. Path entries can be divided
     * by a separator character.
     * 
     * @param path
     *            the path to the file.
     * @param fileName
     *            the file name.
     * @return the input stream to read the file.
     */
    private static FileInputStream tryPath(String path, String fileName) {
        FileInputStream fiStream = null;

        if (path == null) {
            return null;
        }

        StringTokenizer st = new StringTokenizer(path, File.pathSeparator);

        while (st.hasMoreTokens()) {
            String pathEntry = st.nextToken();
            try {
                fiStream = new FileInputStream(pathEntry + File.separatorChar + fileName);
                if (fiStream != null) {
                    return fiStream;
                }
            } catch (FileNotFoundException e) {
            }
        }

        return fiStream;
    }

    /**
     * Gets the single instance of ICC_Profile from data in the specified file.
     * 
     * @param fileName
     *            the specified name of file with ICC profile data.
     * @return single instance of ICC_Profile.
     * @throws IOException
     *             signals that an I/O error occurred while reading the file or
     *             the file does not exist.
     */
    public static ICC_Profile getInstance(String fileName) throws IOException {
        final String fName = fileName; // to use in the privileged block

        FileInputStream fiStream = (FileInputStream)AccessController
                .doPrivileged(new PrivilegedAction<FileInputStream>() {
                    public FileInputStream run() {
                        FileInputStream fiStream = null;

                        // Open absolute path
                        try {
                            fiStream = new FileInputStream(fName);
                            if (fiStream != null) {
                                return fiStream;
                            }
                        } catch (FileNotFoundException e) {
                        }

                        // Check java.iccprofile.path entries
                        fiStream = tryPath(System.getProperty("java.iccprofile.path"), fName); //$NON-NLS-1$
                        if (fiStream != null) {
                            return fiStream;
                        }

                        // Check java.class.path entries
                        fiStream = tryPath(System.getProperty("java.class.path"), fName); //$NON-NLS-1$
                        if (fiStream != null) {
                            return fiStream;
                        }

                        // Check directory with java sample profiles
                        String home = System.getProperty("java.home"); //$NON-NLS-1$
                        if (home != null) {
                            fiStream = tryPath(home + File.separatorChar
                                    + "lib" + File.separatorChar + "cmm", fName //$NON-NLS-1$ //$NON-NLS-2$
                            );
                        }

                        return fiStream;
                    }
                });

        if (fiStream == null) {
            // awt.161=Unable to open file {0}
            throw new IOException(Messages.getString("awt.161", fileName)); //$NON-NLS-1$
        }

        ICC_Profile pf = getInstance(fiStream);
        fiStream.close();
        return pf;
    }

    /**
     * Gets the single instance of ICC_Profile with data in the specified
     * InputStream.
     * 
     * @param s
     *            the InputStream with ICC profile data.
     * @return single instance of ICC_Profile.
     * @throws IOException
     *             if an I/O exception has occurred during reading from
     *             InputStream.
     * @throws IllegalArgumentException
     *             if the file does not contain valid ICC Profile data.
     */
    public static ICC_Profile getInstance(InputStream s) throws IOException {
        byte[] header = new byte[headerSize];
        // awt.162=Invalid ICC Profile Data
        String invalidDataMessage = Messages.getString("awt.162"); //$NON-NLS-1$

        // Get header from the input stream
        if (s.read(header) != headerSize) {
            throw new IllegalArgumentException(invalidDataMessage);
        }

        // Check the profile data for consistency
        if (ICC_ProfileHelper.getBigEndianFromByteArray(header, icHdrMagic) != headerMagicNumber) {
            throw new IllegalArgumentException(invalidDataMessage);
        }

        // Get profile size from header, create an array for profile data
        int profileSize = ICC_ProfileHelper.getBigEndianFromByteArray(header, icHdrSize);
        byte[] profileData = new byte[profileSize];

        // Copy header into it
        System.arraycopy(header, 0, profileData, 0, headerSize);

        // Read the profile itself
        if (s.read(profileData, headerSize, profileSize - headerSize) != profileSize - headerSize) {
            throw new IllegalArgumentException(invalidDataMessage);
        }

        return getInstance(profileData);
    }

    /**
     * Gets the single instance of ICC_Profile from the specified data in a byte
     * array.
     * 
     * @param data
     *            the byte array of ICC profile.
     * @return single instance of ICC_Profile from the specified data in a byte
     *         array.
     * @throws IllegalArgumentException
     *             if the file does not contain valid ICC Profile data.
     */
    public static ICC_Profile getInstance(byte[] data) {
        ICC_Profile res = null;

        try {
            res = new ICC_Profile(data);
        } catch (CMMException e) {
            // awt.162=Invalid ICC Profile Data
            throw new IllegalArgumentException(Messages.getString("awt.162")); //$NON-NLS-1$
        }

        if (System.getProperty("os.name").toLowerCase().indexOf("windows") >= 0) { //$NON-NLS-1$ //$NON-NLS-2$
            try {
                if (res.getColorSpaceType() == ColorSpace.TYPE_RGB
                        && res.getDataSize(icSigMediaWhitePointTag) > 0
                        && res.getDataSize(icSigRedColorantTag) > 0
                        && res.getDataSize(icSigGreenColorantTag) > 0
                        && res.getDataSize(icSigBlueColorantTag) > 0
                        && res.getDataSize(icSigRedTRCTag) > 0
                        && res.getDataSize(icSigGreenTRCTag) > 0
                        && res.getDataSize(icSigBlueTRCTag) > 0) {
                    res = new ICC_ProfileRGB(res.getProfileHandle());
                } else if (res.getColorSpaceType() == ColorSpace.TYPE_GRAY
                        && res.getDataSize(icSigMediaWhitePointTag) > 0
                        && res.getDataSize(icSigGrayTRCTag) > 0) {
                    res = new ICC_ProfileGray(res.getProfileHandle());
                }

            } catch (CMMException e) { /* return res in this case */
            }
        }

        return res;
    }

    /**
     * Gets the single instance of ICC_Profile with the specific color space
     * defined by the ColorSpace class: CS_sRGB, CS_LINEAR_RGB, CS_CIEXYZ,
     * CS_PYCC, CS_GRAY.
     * 
     * @param cspace
     *            the type of color space defined in the ColorSpace class.
     * @return single instance of ICC_Profile.
     * @throws IllegalArgumentException
     *             is not one of the defined color space types.
     */
    public static ICC_Profile getInstance(int cspace) {
        try {
            switch (cspace) {

                case ColorSpace.CS_sRGB:
                    if (sRGBProfile == null) {
                        sRGBProfile = getInstance("sRGB.pf"); //$NON-NLS-1$
                    }
                    return sRGBProfile;

                case ColorSpace.CS_CIEXYZ:
                    if (xyzProfile == null) {
                        xyzProfile = getInstance("CIEXYZ.pf"); //$NON-NLS-1$
                    }
                    return xyzProfile;

                case ColorSpace.CS_GRAY:
                    if (grayProfile == null) {
                        grayProfile = getInstance("GRAY.pf"); //$NON-NLS-1$
                    }
                    return grayProfile;

                case ColorSpace.CS_PYCC:
                    if (pyccProfile == null) {
                        pyccProfile = getInstance("PYCC.pf"); //$NON-NLS-1$
                    }
                    return pyccProfile;

                case ColorSpace.CS_LINEAR_RGB:
                    if (linearRGBProfile == null) {
                        linearRGBProfile = getInstance("LINEAR_RGB.pf"); //$NON-NLS-1$
                    }
                    return linearRGBProfile;
            }

        } catch (IOException e) {
            // awt.163=Can't open color profile
            throw new IllegalArgumentException(Messages.getString("Can't open color profile")); //$NON-NLS-1$
        }

        // awt.164=Not a predefined color space
        throw new IllegalArgumentException(Messages.getString("Not a predefined color space")); //$NON-NLS-1$
    }

    /**
     * Reads an integer from the profile header at the specified position.
     * 
     * @param idx
     *            - offset in bytes from the beginning of the header
     * @return the integer value from header
     */
    private int getIntFromHeader(int idx) {
        if (headerData == null) {
            headerData = getData(icSigHead);
        }

        return ((headerData[idx] & 0xFF) << 24) | ((headerData[idx + 1] & 0xFF) << 16)
                | ((headerData[idx + 2] & 0xFF) << 8) | ((headerData[idx + 3] & 0xFF));
    }

    /**
     * Reads byte from the profile header at the specified position.
     * 
     * @param idx
     *            - offset in bytes from the beginning of the header
     * @return the byte from header
     */
    private byte getByteFromHeader(int idx) {
        if (headerData == null) {
            headerData = getData(icSigHead);
        }

        return headerData[idx];
    }

    /**
     * Converts ICC color space signature to the java predefined color space
     * type.
     * 
     * @param signature
     *            the signature
     * @return the int
     */
    private int csFromSignature(int signature) {
        switch (signature) {
            case icSigRgbData:
                return ColorSpace.TYPE_RGB;
            case icSigXYZData:
                return ColorSpace.TYPE_XYZ;
            case icSigCmykData:
                return ColorSpace.TYPE_CMYK;
            case icSigLabData:
                return ColorSpace.TYPE_Lab;
            case icSigGrayData:
                return ColorSpace.TYPE_GRAY;
            case icSigHlsData:
                return ColorSpace.TYPE_HLS;
            case icSigLuvData:
                return ColorSpace.TYPE_Luv;
            case icSigYCbCrData:
                return ColorSpace.TYPE_YCbCr;
            case icSigYxyData:
                return ColorSpace.TYPE_Yxy;
            case icSigHsvData:
                return ColorSpace.TYPE_HSV;
            case icSigCmyData:
                return ColorSpace.TYPE_CMY;
            case icSigSpace2CLR:
                return ColorSpace.TYPE_2CLR;
            case icSigSpace3CLR:
                return ColorSpace.TYPE_3CLR;
            case icSigSpace4CLR:
                return ColorSpace.TYPE_4CLR;
            case icSigSpace5CLR:
                return ColorSpace.TYPE_5CLR;
            case icSigSpace6CLR:
                return ColorSpace.TYPE_6CLR;
            case icSigSpace7CLR:
                return ColorSpace.TYPE_7CLR;
            case icSigSpace8CLR:
                return ColorSpace.TYPE_8CLR;
            case icSigSpace9CLR:
                return ColorSpace.TYPE_9CLR;
            case icSigSpaceACLR:
                return ColorSpace.TYPE_ACLR;
            case icSigSpaceBCLR:
                return ColorSpace.TYPE_BCLR;
            case icSigSpaceCCLR:
                return ColorSpace.TYPE_CCLR;
            case icSigSpaceDCLR:
                return ColorSpace.TYPE_DCLR;
            case icSigSpaceECLR:
                return ColorSpace.TYPE_ECLR;
            case icSigSpaceFCLR:
                return ColorSpace.TYPE_FCLR;
            default:
        }

        // awt.165=Color space doesn't comply with ICC specification
        throw new IllegalArgumentException(Messages.getString("awt.165")); //$NON-NLS-1$
    }

    /**
     * Gets the profile handle.
     * 
     * @return the profile handle
     */
    private long getProfileHandle() {
        handleStolen = true;
        return profileHandle;
    }

    /**
     * Gets the data size.
     * 
     * @param tagSignature
     *            the tag signature
     * @return the data size
     */
    private int getDataSize(int tagSignature) {
        return NativeCMM.cmmGetProfileElementSize(profileHandle, tagSignature);
    }

    /**
     * Reads XYZ value from the tag data.
     * 
     * @param tagSignature
     *            the tag signature
     * @return the XYZ value
     */
    float[] getXYZValue(int tagSignature) {
        float[] res = new float[3];
        byte[] data = getData(tagSignature);

        // Convert from ICC s15Fixed16Number type
        // 1 (float) = 0x10000 (s15Fixed16Number),
        // hence dividing by 0x10000
        res[0] = ICC_ProfileHelper.getIntFromByteArray(data, 0) / 65536.f;
        res[1] = ICC_ProfileHelper.getIntFromByteArray(data, 4) / 65536.f;
        res[2] = ICC_ProfileHelper.getIntFromByteArray(data, 8) / 65536.f;

        return res;
    }

    /**
     * Gets the media white point.
     * 
     * @return the media white point.
     */
    float[] getMediaWhitePoint() {
        return getXYZValue(icSigMediaWhitePointTag);
    }

    /**
     * If TRC is not a table returns gamma via return value and sets dataTRC to
     * null. If TRC is a table returns 0 and fills dataTRC with values.
     * 
     * @param tagSignature
     *            the tag signature
     * @param dataTRC
     *            the data trc
     * @return - gamma or zero if TRC is a table
     */
    private float getGammaOrTRC(int tagSignature, short[] dataTRC) {
        byte[] data = getData(tagSignature);
        int trcSize = ICC_ProfileHelper.getIntFromByteArray(data, icCurveCount);

        dataTRC = null;

        if (trcSize == 0) {
            return 1.0f;
        }

        if (trcSize == 1) {
            // Cast from ICC u8Fixed8Number to float
            return ICC_ProfileHelper.getShortFromByteArray(data, icCurveData) / 256.f;
        }

        // TRC is a table
        dataTRC = new short[trcSize];
        for (int i = 0, pos = icCurveData; i < trcSize; i++, pos += 2) {
            dataTRC[i] = ICC_ProfileHelper.getShortFromByteArray(data, pos);
        }
        return 0;
    }

    /**
     * Gets the gamma.
     * 
     * @param tagSignature
     *            the tag signature
     * @return the gamma
     */
    float getGamma(int tagSignature) {
        short[] dataTRC = null;
        float gamma = getGammaOrTRC(tagSignature, dataTRC);

        if (dataTRC == null) {
            return gamma;
        }
        // awt.166=TRC is not a simple gamma value.
        throw new ProfileDataException(Messages.getString("awt.166")); //$NON-NLS-1$
    }

    /**
     * Gets the TRC.
     * 
     * @param tagSignature
     *            the tag signature
     * @return the tRC
     */
    short[] getTRC(int tagSignature) {
        short[] dataTRC = null;
        getGammaOrTRC(tagSignature, dataTRC);

        if (dataTRC == null) {
            // awt.167=TRC is a gamma value, not a table.
            throw new ProfileDataException(Messages.getString("awt.167")); //$NON-NLS-1$
        }
        return dataTRC;
    }
}
