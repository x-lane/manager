package manager.nodes;

import codex.model.Access;
import codex.model.Entity;
import codex.type.ArrStr;
import codex.type.EntityRef;
import codex.utils.ImageUtils;
import java.util.LinkedList;
import java.util.List;

@RepositoryBranch.Branch(remoteDir = "dev", localDir = "sources", hasArchive = false)
public class Development extends RepositoryBranch {

    private final static String PROP_JVM_DESIGNER = "jvmDesigner";

    public Development(EntityRef owner) {
        this(owner, null);
    }

    public Development(EntityRef owner, String PID) {
        super(owner, ImageUtils.getByPath("/images/development.png"), PID, null);
        model.addUserProp(PROP_JVM_DESIGNER, new ArrStr("-Xmx6G"), false, Access.Select);
    }

    @Override
    public void loadBranch() {
        getRepository().insert(this);
    }

    @Override
    public void unloadBranch() {
        new LinkedList<>(childrenList()).forEach(this::delete);
        getRepository().delete(this);
    }

    @Override
    public Class<? extends Entity> getChildClass() {
        return Offshoot.class;
    }

    @Override
    public boolean allowModifyChild() {
        return false;
    }

    @SuppressWarnings("unchecked")
    public final List<String> getJvmDesigner() {
        return (List<String>) model.getValue(PROP_JVM_DESIGNER);
    }

}
