package com.android.hotspot2.omadm;

import com.android.hotspot2.osu.OSUError;
import com.android.hotspot2.osu.OSUStatus;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

public class OMAConstants {
    private OMAConstants() {
    }

    public static final String MOVersion = "1.0";
    public static final String PPS_URN = "urn:wfa:mo:hotspot2dot0-perprovidersubscription:1.0";
    public static final String DevInfoURN = "urn:oma:mo:oma-dm-devinfo:1.0";
    public static final String DevDetailURN = "urn:oma:mo:oma-dm-devdetail:1.0";
    public static final String DevDetailXURN = "urn:wfa:mo-ext:hotspot2dot0-devdetail-ext:1.0";

    public static final String[] SupportedMO_URNs = {
            PPS_URN, DevInfoURN, DevDetailURN, DevDetailXURN
    };

    public static final String SppMOAttribute = "spp:moURN";
    public static final String TAG_PostDevData = "spp:sppPostDevData";
    public static final String TAG_SupportedVersions = "spp:supportedSPPVersions";
    public static final String TAG_SupportedMOs = "spp:supportedMOList";
    public static final String TAG_UpdateResponse = "spp:sppUpdateResponse";
    public static final String TAG_MOContainer = "spp:moContainer";
    public static final String TAG_Version = "spp:sppVersion";

    public static final String TAG_SessionID = "spp:sessionID";
    public static final String TAG_Status = "spp:sppStatus";
    public static final String TAG_Error = "spp:sppError";

    public static final String SyncMLVersionTag = "VerDTD";
    public static final String OMAVersion = "1.2";
    public static final String SyncML = "syncml:dmddf1.2";

    private static final byte[] INDENT = new byte[1024];

    private static final Map<OSUStatus, String> sStatusStrings = new EnumMap<>(OSUStatus.class);
    private static final Map<String, OSUStatus> sStatusEnums = new HashMap<>();
    private static final Map<OSUError, String> sErrorStrings = new EnumMap<>(OSUError.class);
    private static final Map<String, OSUError> sErrorEnums = new HashMap<>();

    static {
        sStatusStrings.put(OSUStatus.OK, "OK");
        sStatusStrings.put(OSUStatus.ProvComplete,
                "Provisioning complete, request sppUpdateResponse");
        sStatusStrings.put(OSUStatus.RemediationComplete,
                "Remediation complete, request sppUpdateResponse");
        sStatusStrings.put(OSUStatus.UpdateComplete, "Update complete, request sppUpdateResponse");
        sStatusStrings.put(OSUStatus.ExchangeComplete, "Exchange complete, release TLS connection");
        sStatusStrings.put(OSUStatus.Unknown, "No update available at this time");
        sStatusStrings.put(OSUStatus.Error, "Error occurred");

        for (Map.Entry<OSUStatus, String> entry : sStatusStrings.entrySet()) {
            sStatusEnums.put(entry.getValue().toLowerCase(), entry.getKey());
        }

        sErrorStrings.put(OSUError.SPPversionNotSupported, "SPP version not supported");
        sErrorStrings.put(OSUError.MOsNotSupported, "One or more mandatory MOs not supported");
        sErrorStrings.put(OSUError.CredentialsFailure,
                "Credentials cannot be provisioned at this time");
        sErrorStrings.put(OSUError.RemediationFailure,
                "Remediation cannot be completed at this time");
        sErrorStrings.put(OSUError.ProvisioningFailed,
                "Provisioning cannot be completed at this time");
        sErrorStrings.put(OSUError.ExistingCertificate, "Continue to use existing certificate");
        sErrorStrings.put(OSUError.CookieInvalid, "Cookie invalid");
        sErrorStrings.put(OSUError.WebSessionID,
                "No corresponding web-browser-connection Session ID");
        sErrorStrings.put(OSUError.PermissionDenied, "Permission denied");
        sErrorStrings.put(OSUError.CommandFailed, "Command failed");
        sErrorStrings.put(OSUError.MOaddOrUpdateFailed, "MO addition or update failed");
        sErrorStrings.put(OSUError.DeviceFull, "Device full");
        sErrorStrings.put(OSUError.BadTreeURI, "Bad management tree URI");
        sErrorStrings.put(OSUError.TooLarge, "Requested entity too large");
        sErrorStrings.put(OSUError.CommandNotAllowed, "Command not allowed");
        sErrorStrings.put(OSUError.UserAborted, "Command not executed due to user");
        sErrorStrings.put(OSUError.NotFound, "Not found");
        sErrorStrings.put(OSUError.Other, "Other");

        for (Map.Entry<OSUError, String> entry : sErrorStrings.entrySet()) {
            sErrorEnums.put(entry.getValue().toLowerCase(), entry.getKey());
        }
        Arrays.fill(INDENT, (byte) ' ');
    }

    public static String mapStatus(OSUStatus status) {
        return sStatusStrings.get(status);
    }

    public static OSUStatus mapStatus(String status) {
        return sStatusEnums.get(status.toLowerCase());
    }

    public static String mapError(OSUError error) {
        return sErrorStrings.get(error);
    }

    public static OSUError mapError(String error) {
        return sErrorEnums.get(error.toLowerCase());
    }

    public static void serializeString(String s, OutputStream out) throws IOException {
        byte[] octets = s.getBytes(StandardCharsets.UTF_8);
        byte[] prefix = String.format("%x:", octets.length).getBytes(StandardCharsets.UTF_8);
        out.write(prefix);
        out.write(octets);
    }

    public static void indent(int level, OutputStream out) throws IOException {
        out.write(INDENT, 0, level);
    }

    public static String deserializeString(InputStream in) throws IOException {
        StringBuilder prefix = new StringBuilder();
        for (; ; ) {
            byte b = (byte) in.read();
            if (b == '.')
                return null;
            else if (b == ':')
                break;
            else if (b > ' ')
                prefix.append((char) b);
        }
        int length = Integer.parseInt(prefix.toString(), 16);
        byte[] octets = new byte[length];
        int offset = 0;
        while (offset < octets.length) {
            int amount = in.read(octets, offset, octets.length - offset);
            if (amount <= 0)
                throw new EOFException();
            offset += amount;
        }
        return new String(octets, StandardCharsets.UTF_8);
    }

    public static String readURN(InputStream in) throws IOException {
        StringBuilder urn = new StringBuilder();

        for (; ; ) {
            byte b = (byte) in.read();
            if (b == ')')
                break;
            urn.append((char) b);
        }
        return urn.toString();
    }
}
