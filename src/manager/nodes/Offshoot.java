package manager.nodes;

import codex.explorer.ExplorerAccessService;
import codex.explorer.IExplorerAccessService;
import codex.explorer.tree.INode;
import codex.model.Access;
import codex.model.Entity;
import codex.model.EntityModel;
import codex.service.ServiceRegistry;
import codex.type.Bool;
import codex.type.Enum;
import codex.type.Int;
import codex.type.Str;
import codex.utils.ImageUtils;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.StringJoiner;
import manager.commands.DeleteWC;
import manager.commands.UpdateWC;
import manager.svn.SVN;
import manager.type.WCStatus;
import org.tmatesoft.svn.core.wc.SVNInfo;
import org.tmatesoft.svn.core.wc.SVNRevision;
import org.tmatesoft.svn.core.wc.SVNWCUtil;


public class Offshoot extends BinarySource {
    
    private final static SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("dd-MM-yyyy hh:mm");

    public Offshoot(INode parent, String title) {
        super(parent, ImageUtils.getByPath("/images/branch.png"), title);
        
        model.addDynamicProp("version", new Str(null), null, () -> {
            return model.getPID();
        });
        model.addDynamicProp("wcStatus", new Enum(WCStatus.Absent), Access.Edit, () -> {
            if (model.getValue(EntityModel.OWN) != null) {
                return getStatus();
            } else {
                return WCStatus.Absent;
            }
        });
        model.addDynamicProp("localRev", new Str(null), null, () -> {
            if (((WCStatus) model.getValue("wcStatus")).equals(WCStatus.Succesfull)) {
                return getRevision().getNumber()+" / "+DATE_FORMAT.format(getRevisionDate());
            }
            return null;
        }, "wcStatus");
        model.addUserProp("loaded",      new Bool(false), true, Access.Any);
        model.addUserProp("builtRev",    new Int(null), false, null);
        
        model.getEditor("builtRev").setEditable(false);
        
        addCommand(new DeleteWC());
        addCommand(new UpdateWC());
    }

    @Override
    public Class getChildClass() {
        return null;
    }
    
    public final String getWCPath() {
        IExplorerAccessService EAS = (IExplorerAccessService) ServiceRegistry.getInstance().lookupService(ExplorerAccessService.class);
        String workDir = EAS.getEntitiesByClass(Common.class).get(0).model.getValue("workDir").toString();
        String repoUrl = (String) Entity.getOwner(this).model.getValue("repoUrl");
        
        StringJoiner wcPath = new StringJoiner(File.separator)
            .add(workDir)
            .add("versions")
            .add(repoUrl.replaceAll("svn(|\\+[\\w]+)://([\\w\\./\\d]+)", "$2").replaceAll("[/\\\\]{1}", "."))
            .add(model.getPID());
        
        return wcPath.toString();
    }
    
    public final WCStatus getStatus() {
        String wcPath = getWCPath();
        
        final File localDir = new File(wcPath);
        if (!localDir.exists()) {
            return WCStatus.Absent;
        } else if (!SVNWCUtil.isVersionedDirectory(localDir)) {
            return WCStatus.Invalid;
        } else {
            SVNInfo info = SVN.info(wcPath, false, null, null);
            if (
                    info == null || 
                    info.getCommittedDate() == null || 
                    info.getCommittedRevision() == null ||
                    info.getCommittedRevision() == SVNRevision.UNDEFINED
            ) {
                return WCStatus.Interrupted;
            } else {
                return WCStatus.Succesfull;
            }
        }
    }
    
    public final SVNRevision getRevision() {
        String wcPath = getWCPath();
        SVNInfo info = SVN.info(wcPath, false, null, null);
        return info.getCommittedRevision();
    }
    
    public final Date getRevisionDate() {
        String wcPath = getWCPath();
        SVNInfo info = SVN.info(wcPath, false, null, null);
        return info.getCommittedDate();
    }
    
}