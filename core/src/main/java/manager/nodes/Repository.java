package manager.nodes;

import codex.component.messagebox.MessageBox;
import codex.component.messagebox.MessageType;
import codex.config.ConfigStoreService;
import codex.config.IConfigStoreService;
import codex.explorer.tree.INode;
import codex.mask.RegexMask;
import codex.model.Access;
import codex.model.CommandRegistry;
import codex.model.Entity;
import codex.property.PropertyHolder;
import codex.service.ServiceRegistry;
import codex.type.*;
import codex.type.Enum;
import codex.utils.ImageUtils;
import codex.utils.Language;
import manager.commands.repository.LoadWC;
import manager.svn.SVN;
import manager.type.SVNAuth;
import manager.xml.RepositoryConfigDocument;
import org.apache.xmlbeans.XmlException;
import org.atteo.classindex.ClassIndex;
import org.tmatesoft.svn.core.SVNErrorCode;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.auth.BasicAuthenticationManager;
import org.tmatesoft.svn.core.auth.ISVNAuthenticationManager;
import org.tmatesoft.svn.core.auth.SVNAuthentication;
import org.tmatesoft.svn.core.auth.SVNPasswordAuthentication;
import java.io.IOException;
import java.io.InputStream;
import java.text.MessageFormat;
import java.util.*;
import java.util.Map;
import java.util.stream.Collectors;

public class Repository extends Entity {

    static final int ERR_AUTH_FAILED   = 170000;
    static final int ERR_NOT_AVAILABLE = 210000;
    
    public final static String PROP_REPO_URL  = "repoUrl";
    public final static String PROP_ARCHIVE   = "loadArchive";
    public final static String PROP_AUTH_MODE = "authMode";
    public final static String PROP_SVN_USER  = "svnUser";
    public final static String PROP_SVN_PASS  = "svnPass";
    public final static String PROP_LOCKED    = "locked";

    private final static String REPO_CONFIG_FILE = "config/repository.xml";
    private final static Map<String, Class<? extends RepositoryBranch>> BRANCHES = new HashMap<>();
    private final static IConfigStoreService CAS = (IConfigStoreService) ServiceRegistry.getInstance().lookupService(ConfigStoreService.class);

    static {
        ClassIndex.getSubclasses(RepositoryBranch.class).forEach(branchClass ->
                BRANCHES.put(branchClass.getAnnotation(RepositoryBranch.Branch.class).remoteDir(), branchClass));
        CommandRegistry.getInstance().registerCommand(LoadWC.class);
    }

    public Repository(EntityRef owner, String title) {
        super(owner, ImageUtils.getByPath("/images/repository.png"), title, null);
        
        PropertyHolder svnUser = new PropertyHolder(PROP_SVN_USER, new Str(null), true);
        PropertyHolder svnPass = new PropertyHolder(PROP_SVN_PASS, new Str(null), true);
        
        // Properties
        model.addUserProp(PROP_REPO_URL,  new Str(null).setMask(new RegexMask("svn(|\\+[\\w]+)://[\\w\\./\\d]+", "Invalid SVN url")), true, null);
        model.addUserProp(PROP_ARCHIVE,   new Bool(false), true, Access.Select);
        model.addUserProp(PROP_AUTH_MODE, new Enum(SVNAuth.None), false, Access.Select);
        model.addUserProp(svnUser, Access.Select);
        model.addUserProp(svnPass, Access.Select);
        model.addUserProp(PROP_LOCKED,    new Bool(false), false, Access.Any);
        
        // Property settings
        model.setPropUnique(PROP_REPO_URL);
        svnUser.setRequired(getAuthMode(true) == SVNAuth.Password);
        svnPass.setRequired(getAuthMode(true) == SVNAuth.Password);
        
        // Editor settings
        model.addPropertyGroup(Language.get("group@auth"), PROP_AUTH_MODE, PROP_SVN_USER, PROP_SVN_PASS);

        model.getEditor(PROP_SVN_USER).setVisible(getAuthMode(true) == SVNAuth.Password);
        model.getEditor(PROP_SVN_PASS).setVisible(getAuthMode(true) == SVNAuth.Password);
        
        // Handlers
        lockEditors(!isLocked(false));
        model.addChangeListener((name, oldValue, newValue) -> {
           switch (name) {
                case PROP_AUTH_MODE:
                    model.getEditor(PROP_SVN_USER).setVisible(getAuthMode(true) == SVNAuth.Password);
                    model.getEditor(PROP_SVN_PASS).setVisible(getAuthMode(true) == SVNAuth.Password);
                    svnUser.setRequired(getAuthMode(true) == SVNAuth.Password);
                    svnPass.setRequired(getAuthMode(true) == SVNAuth.Password);
                    break;
               case PROP_LOCKED:
                   lockEditors(Boolean.FALSE.equals(newValue));
            }
        });
        
        setMode((isLocked(false) && getChildCount() > 0 ? INode.MODE_ENABLED : INode.MODE_NONE) + INode.MODE_SELECTABLE);
    }

    private void lockEditors(boolean editable) {
        model.getEditor(PROP_REPO_URL).setEditable(editable);
        model.getEditor(PROP_ARCHIVE).setEditable(editable);
        model.getEditor(PROP_AUTH_MODE).setEditable(editable);
        model.getEditor(PROP_SVN_USER).setEditable(editable);
        model.getEditor(PROP_SVN_PASS).setEditable(editable);
    }
    
    public final String getRepoUrl() {
        return (String) model.getValue(PROP_REPO_URL);
    }

    public final Boolean isLoadArchive() {
        return Boolean.TRUE.equals(model.getValue(PROP_ARCHIVE));
    }

    public final SVNAuth getAuthMode(boolean unsaved) {
        return (SVNAuth) (unsaved ? model.getUnsavedValue(PROP_AUTH_MODE) : model.getValue(PROP_AUTH_MODE));
    }
    
    public final String getSvnUser(boolean unsaved) {
        return (String) (unsaved ? model.getUnsavedValue(PROP_SVN_USER) : model.getValue(PROP_SVN_USER));
    }
    
    public final String getSvnPass(boolean unsaved) {
        return (String) (unsaved ? model.getUnsavedValue(PROP_SVN_PASS) : model.getValue(PROP_SVN_PASS));
    }
    
    public final boolean isLocked(boolean unsaved) {
        return (unsaved ? model.getUnsavedValue(PROP_LOCKED) : model.getValue(PROP_LOCKED)) == Boolean.TRUE;
    }
    
    public final void setRepoUrl(SVNAuth value) {
        model.setValue(PROP_REPO_URL, value);
    }
    
    public final void setAuthMode(String value) {
        model.setValue(PROP_AUTH_MODE, value);
    }
    
    public final void setSvnUser(String value) {
        model.setValue(PROP_SVN_USER, value);
    }
    
    public final void setSvnPass(String value) {
        model.setValue(PROP_SVN_PASS, value);
    }
    
    public final void setLocked(boolean value) {
        model.setValue(PROP_LOCKED, value);
    }

    @Override
    public void setParent(INode parent) {
        super.setParent(parent);
        if (parent != null) {
            getCommand(LoadWC.class).execute(this, null);
        }
    }
    
    public ISVNAuthenticationManager getAuthManager() {
        SVNAuth authType = getAuthMode(true);
        switch (authType) {
            case Password:
                return new BasicAuthenticationManager(new SVNAuthentication[]{
                    new SVNPasswordAuthentication(
                            getSvnUser(true), 
                            getSvnPass(true), 
                            false
                    )
                });
            default:
                return new BasicAuthenticationManager(new SVNAuthentication[] {});
        }
    }

    public String getSystemName() throws SVNException {
        try {
            ISVNAuthenticationManager authMgr = getAuthManager();
            InputStream in = SVN.readFile(getRepoUrl(), REPO_CONFIG_FILE, authMgr);
            RepositoryConfigDocument repoConfig = RepositoryConfigDocument.Factory.parse(in);
            return repoConfig.getRepositoryConfig().getTitle();
        } catch (XmlException | IOException e) {
            //
        }
        return null;
    }

    private List<RepositoryBranch> getLocalBranches() {
        return BRANCHES.values().stream()
                .map(branchClass -> (RepositoryBranch) Entity.newInstance(branchClass, this.toRef(), null))
                .filter(branchClass -> !CAS.readCatalogEntries(this.getID(), branchClass.getChildClass()).isEmpty())
                .collect(Collectors.toList());
    }

    private List<RepositoryBranch> getBranches() {
        List<RepositoryBranch> branches = new LinkedList<>();
        try {
            SVN.list(getRepoUrl(), getAuthManager()).forEach(svnDirEntry -> {
                if (BRANCHES.containsKey(svnDirEntry.getName())) {
                    branches.add(Entity.newInstance(
                            BRANCHES.get(svnDirEntry.getName()),
                            this.toRef(),
                            null
                    ));
                }
            });
        } catch (SVNException e) {
            branches.addAll(getLocalBranches());
        }
        return branches;
    }

    public void loadBranches() {
        getBranches().forEach(RepositoryBranch::loadBranch);
    }

    public void unloadBranches() {
        getBranches().forEach(RepositoryBranch::unloadBranch);
    }

    public boolean isRepositoryOnline(boolean showDialog) {
        try {
            return SVN.checkConnection(getRepoUrl(), getAuthManager());
        } catch (SVNException | IOException e) {
            if (showDialog) {
                MessageBox.show(
                        MessageType.WARNING,
                        formatErrorMessage(MessageFormat.format(Language.get(Repository.class, "fail@connect"), getPID()), e)
                );
            }
            return false;
        }
    }

    public static String formatErrorMessage(String whatsWrong, Exception exception) {
        if (exception instanceof SVNException) {
            SVNErrorCode code = ((SVNException) exception).getErrorMessage().getErrorCode();
            switch (code.getCategory()) {
                case ERR_AUTH_FAILED:
                    return MessageFormat.format(Language.get(Repository.class, "error@unavailable.auth"), whatsWrong);
                case ERR_NOT_AVAILABLE:
                    return MessageFormat.format(Language.get(Repository.class, "error@unavailable.repo"), whatsWrong);
                default:
                    return MessageFormat.format(Language.get(Repository.class, "error@unavailable.other"), whatsWrong, code.getDescription());
            }
        }
        return MessageFormat.format(Language.get(Repository.class, "error@unavailable.other"), whatsWrong, exception.getMessage());
    }
    
    public static String urlToDirName(String url) {
        return url.replaceAll("svn(|\\+[\\w]+)://([\\w\\./\\d]+)", "$2").replaceAll("[/\\\\]{1}", ".");
    }
    
}
