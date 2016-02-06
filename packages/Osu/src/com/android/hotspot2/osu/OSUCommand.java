package com.android.hotspot2.osu;

import com.android.hotspot2.omadm.OMAException;
import com.android.hotspot2.omadm.XMLNode;
import com.android.hotspot2.osu.commands.BrowserURI;
import com.android.hotspot2.osu.commands.ClientCertInfo;
import com.android.hotspot2.osu.commands.GetCertData;
import com.android.hotspot2.osu.commands.MOData;
import com.android.hotspot2.osu.commands.MOURN;
import com.android.hotspot2.osu.commands.OSUCommandData;

import java.util.HashMap;
import java.util.Map;

public class OSUCommand {
    private final OSUCommandID mOSUCommand;
    private final ExecCommand mExecCommand;
    private final OSUCommandData mCommandData;

    private static final Map<String, OSUCommandID> sCommands = new HashMap<>();
    private static final Map<String, ExecCommand> sExecs = new HashMap<>();

    static {
        sCommands.put("exec", OSUCommandID.Exec);
        sCommands.put("addmo", OSUCommandID.AddMO);
        sCommands.put("updatenode", OSUCommandID.UpdateNode);      // Multi
        sCommands.put("nomoupdate", OSUCommandID.NoMOUpdate);

        sExecs.put("launchbrowsertouri", ExecCommand.Browser);
        sExecs.put("getcertificate", ExecCommand.GetCert);
        sExecs.put("useclientcerttls", ExecCommand.UseClientCertTLS);
        sExecs.put("uploadmo", ExecCommand.UploadMO);
    }

    public OSUCommand(XMLNode child) throws OMAException {
        mOSUCommand = sCommands.get(child.getStrippedTag());

        switch (mOSUCommand) {
            case Exec:
                /*
                 * Receipt of this element by a mobile device causes the following command
                 * to be executed.
                 */
                child = child.getSoleChild();
                mExecCommand = sExecs.get(child.getStrippedTag());
                if (mExecCommand == null) {
                    throw new OMAException("Unrecognized exec command: " + child.getStrippedTag());
                }
                switch (mExecCommand) {
                    case Browser:
                        /*
                         * When the mobile device receives this command, it launches its default
                         * browser to the URI contained in this element. The URI must use HTTPS as
                         * the protocol and must contain an FQDN.
                         */
                        mCommandData = new BrowserURI(child);
                        break;
                    case GetCert:
                        mCommandData = new GetCertData(child);
                        break;
                    case UploadMO:
                        mCommandData = new MOURN(child);
                        break;
                    case UseClientCertTLS:
                        /*
                         * Command to mobile to re-negotiate the TLS connection using a client
                         * certificate of the accepted type or Issuer to authenticate with the
                         * Subscription server.
                         */
                        mCommandData = new ClientCertInfo(child);
                        break;
                    default:
                        mCommandData = null;
                        break;
                }
                break;
            case AddMO:
                /*
                 * This command causes an management object in the mobile devices management tree
                 * at the specified location to be added.
                 * If there is already a management object at that location, the object is replaced.
                 */
                mExecCommand = null;
                mCommandData = new MOData(child);
                break;
            case UpdateNode:
                /*
                 * This command causes the update of an interior node and its child nodes (if any)
                 * at the location specified in the management tree URI attribute. The content of
                 * this element is the MO node XML.
                 */
                mExecCommand = null;
                mCommandData = new MOData(child);
                break;
            case NoMOUpdate:
                /*
                 * This response is used when there is no command to be executed nor update of
                 * any MO required.
                 */
                mExecCommand = null;
                mCommandData = null;
                break;
            default:
                mExecCommand = null;
                mCommandData = null;
                break;
        }
    }

    public OSUCommandID getOSUCommand() {
        return mOSUCommand;
    }

    public ExecCommand getExecCommand() {
        return mExecCommand;
    }

    public OSUCommandData getCommandData() {
        return mCommandData;
    }

    @Override
    public String toString() {
        return "OSUCommand{" +
                "OSUCommand=" + mOSUCommand +
                ", execCommand=" + mExecCommand +
                ", commandData=" + mCommandData +
                '}';
    }
}
