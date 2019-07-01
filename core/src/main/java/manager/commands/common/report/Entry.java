package manager.commands.common.report;

import codex.command.EntityCommand;
import codex.config.ConfigStoreService;
import codex.config.IConfigStoreService;
import codex.model.Catalog;
import codex.model.CommandRegistry;
import codex.model.Entity;
import codex.service.ServiceRegistry;
import codex.type.*;
import codex.utils.ImageUtils;
import codex.utils.Language;
import manager.commands.common.DiskUsageReport;
import manager.nodes.Repository;
import manager.nodes.RepositoryBranch;
import org.atteo.classindex.IndexSubclasses;
import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@IndexSubclasses
@BranchLink()
public class Entry extends Catalog {

    private final static IConfigStoreService CAS = (IConfigStoreService) ServiceRegistry.getInstance().lookupService(ConfigStoreService.class);

    public  final static String PROP_USED = "used";
    private final static String PROP_SIZE = "size";

    private Long size = 0L;
    //protected final Entity entity;

    static {
        CommandRegistry.getInstance().registerCommand(DeleteEntry.class);
    }

    public Entry(EntityRef owner, String filePath) {
        this(owner, null, filePath);
    }

    public Entry(EntityRef owner, ImageIcon icon, String filePath) {
        super(owner, icon, filePath, null);
        if (filePath != null) {
            File   file = new File(filePath);
            String name = file.getName();
            setTitle(name);
            //entity = findEntity(name);
        }/* else {
            entity = null;
        }*/

        // Properties
        model.addDynamicProp(PROP_USED, new ArrStr(), null, () -> {
            Entity entity = findEntity();
            if (entity != null && entity.getID() != null) {
                return CAS.findReferencedEntries(entity.getClass(), entity.getID()).stream()
                        .map((link) -> EntityRef.build(link.entryClass, link.entryID).getValue().getPID())
                        .collect(Collectors.toList());
            }
            return Collections.emptyList();
        });
        model.addDynamicProp(PROP_SIZE, new Str(null), null, null);
    }

    @Override
    protected boolean isAutoGenerated() {
        return true;
    }

    protected boolean canDeleteUsed() {
        return true;
    }

    public boolean isLocked() {
        if (getPID() == null) {
            return false;
        } else {
            File file = new File(getPID());
            return !file.renameTo(file);
        }
    }

    @Override
    public Class<? extends Entity> getChildClass() {
        return null;
    }

    public final boolean isUsed() {
        return !((List<String>) model.getValue(PROP_USED)).isEmpty();
    }

    public final void setSize(Long size) {
        model.setValue(PROP_SIZE, DiskUsageReport.formatFileSize(size));
        this.size = size;
    }

    public long getOriginalSize() {
        return size;
    }

    public boolean skipDirectory(Path dir) {
        return false;
    }

    public long getActualSize() {
        File file = new File(getPID());
        if (!file.exists()) {
            return 0;
        } else if (file.isFile()) {
            return file.length();
        } else {
            AtomicLong size = new AtomicLong(0);
            try {
                Files.walkFileTree(file.toPath(), new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        size.addAndGet(attrs.size());
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (skipDirectory(dir)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                e.printStackTrace();
                // Do nothing
            }
            return size.get();
        }
    }


    protected Entity findEntity() {
        if (getOwner() != null) {
            Repository repo = (Repository) getOwner();
            RepoView view = Entity.newInstance(RepoView.class, repo.toRef(), Repository.urlToDirName(repo.getRepoUrl()));
            Class<? extends RepositoryBranch> branchCatalogClass = getClass().getAnnotation(BranchLink.class).branchCatalogClass();
            if (!Modifier.isAbstract(branchCatalogClass.getModifiers())) {
                RepositoryBranch repositoryBranch = Entity.newPrototype(branchCatalogClass);
                Class<? extends Entity> branchClass = repositoryBranch.getChildClass();
                return view.getLinkedEntities().stream()
                        .filter(entity -> entity.getClass().equals(branchClass) && entity.getPID().equals(new File(getPID()).getName()))
                        .findFirst()
                        .orElse(Entity.newInstance(branchClass, repo.toRef(), getPID()));
            }
        }
        return null;
    }

    protected void deleteEntry() {
        throw new UnsupportedOperationException("Not supported yet");
    }

    private class DeleteEntry extends EntityCommand<Entry> {

        DeleteEntry() {
            super(
                    "delete",
                    Language.get(DiskUsageReport.class, "delete@title"),
                    ImageUtils.getByPath("/images/minus.png"),
                    Language.get(DiskUsageReport.class, "delete@title"),
                    (entry) -> entry.model.getValue(PROP_SIZE) != null && (!entry.isUsed() || entry.canDeleteUsed())
            );
        }

        @Override
        public boolean multiContextAllowed() {
            return true;
        }

        @Override
        public void execute(Entry context, Map<String, IComplexType> params) {
            context.deleteEntry();
        }
    }
}
