package manager.commands.common;

import codex.command.EntityCommand;
import codex.config.ConfigStoreService;
import codex.config.IConfigStoreService;
import codex.model.Catalog;
import codex.service.ServiceRegistry;
import codex.type.ArrStr;
import codex.type.EntityRef;
import codex.type.Enum;
import codex.type.IComplexType;
import codex.type.Str;
import codex.utils.ImageUtils;
import codex.utils.Language;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import manager.nodes.BinarySource;
import manager.nodes.Offshoot;
import manager.nodes.Release;
import manager.nodes.Repository;


class EntryEntity extends Catalog {
    
    private final static IConfigStoreService CAS = (IConfigStoreService) ServiceRegistry.getInstance().lookupService(ConfigStoreService.class);
    
    public final static String PROP_KIND = "kind";
    public final static String PROP_NAME = "name";
    public final static String PROP_USED = "used";
    public final static String PROP_SIZE = "size";
    
    DiskUsageReport.Entry entry;
    EntityRef entityRef;
    
    EntryEntity(EntityRef owner, String title) {
        super(null, null, title, null);
        
        model.addDynamicProp(PROP_KIND, new Enum(DiskUsageReport.EntryKind.None), null, () -> {
            return entry.kind;
        });
        model.addDynamicProp(PROP_NAME, new Str(null), null, () -> {
            switch (entry.kind) {
                case Dump:
                    return entry.file
                            .getParentFile()
                            .getParentFile()
                            .getParentFile()
                            .getParentFile()
                            .getName()
                            .concat(File.separator).concat(entry.file.getName());
            }
            return entry.file.getName();
        });
        model.addDynamicProp(PROP_USED, new ArrStr(), null, () -> {
            if (entityRef != null) {
                return CAS.findReferencedEntries(entityRef.getEntityClass(), entityRef.getId()).stream().map((link) -> {
                    try {
                        return EntityRef.build(Class.forName(link.entryClass), link.entryID).getValue().getPID();
                    } catch (ClassNotFoundException e) {
                        e.printStackTrace();
                    }
                    return null;
                }).filter((PID) -> {
                    return PID != null;
                }).collect(Collectors.toList());
            }
            return null;
        });
        model.addDynamicProp(PROP_SIZE, new Str(null), null, null);
        
        // Commands
        addCommand(new DeleteEntry());
    }
    
    final List<String> getUsed() {
        return (List<String>) model.getValue(PROP_USED);
    }
    
    final void setEntry(DiskUsageReport.Entry entry) {
        this.entry = entry;
        this.entityRef = findEntity();
        
        if (this.entityRef != null) {
            if (entityRef.getValue().islocked()) {
                try {
                    getLock().acquire();
                } catch (InterruptedException e) {}
            };
        }
        
        if (entry.kind == DiskUsageReport.EntryKind.Cache || entry.kind == DiskUsageReport.EntryKind.Sources) {
            StringJoiner starterPath = new StringJoiner(File.separator);
            starterPath.add(entry.file.getAbsolutePath());
            starterPath.add("org.radixware");
            starterPath.add("kernel");
            starterPath.add("starter");
            starterPath.add("bin");
            starterPath.add("dist");
            starterPath.add("starter.jar");
            
            File starter = new File(starterPath.toString());
            if (starter.exists() && !starter.renameTo(starter)) {
                try {
                    getLock().acquire();
                } catch (InterruptedException e) {}
            }
        }
    }
    
    final void setSize(Long size) {
        model.setValue(PROP_SIZE, DiskUsageReport.formatFileSize(size));
    }
    
    private EntityRef findEntity() {
        List<IConfigStoreService.ForeignLink> links = CAS.findReferencedEntries(Repository.class, entry.repo.getID());
        switch (entry.kind) {
            case Sources:
            case Cache:
                return links.stream().filter((link) -> {
                    return 
                            Boolean.FALSE.equals(link.isIncoming) && (
                                link.entryClass.equals(Offshoot.class.getCanonicalName()) ||
                                link.entryClass.equals(Release.class.getCanonicalName())
                            );
                }).map((link) -> {
                    try {
                        return EntityRef.build(Class.forName(link.entryClass), link.entryID);
                    } catch (ClassNotFoundException e) {
                        return null;
                    }
                }).filter((ref) -> {
                    return ref != null &&
                           ((BinarySource) ref.getValue()).getLocalPath().equals(entry.file.getAbsolutePath());
                }).findFirst().orElse(null);
        }
        return null;
    }

    @Override
    public final Class getChildClass() {
        return null;
    }
    
    long getFilesCount() throws IOException {
        return Files.walk(entry.file.toPath()).count();
    }
    
    long getSize() {
        if (!entry.file.exists()) {
            return 0;
        } else {
            AtomicLong size = new AtomicLong(0);
            try {
                Files.walkFileTree(entry.file.toPath(), new SimpleFileVisitor<Path>() {

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        size.addAndGet(attrs.size());
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {}
            return size.get();
        }
    }
    
    private class DeleteEntry extends EntityCommand<EntryEntity> {

        public DeleteEntry() {
            super(
                    "delete",
                    Language.get(DiskUsageReport.class.getSimpleName(), "delete@title"),
                    ImageUtils.resize(ImageUtils.getByPath("/images/minus.png"), 28, 28),
                    Language.get(DiskUsageReport.class.getSimpleName(), "delete@title"), 
                    (entryEntity) -> {
                        return 
                                entryEntity.model.getValue(PROP_SIZE) != null && (
                                    entryEntity.entry.kind == DiskUsageReport.EntryKind.Cache ||
                                    entryEntity.entry.kind == DiskUsageReport.EntryKind.Dump || (
                                        entryEntity.entry.kind == DiskUsageReport.EntryKind.Sources &&
                                        entryEntity.getUsed() == null
                                    )
                                );
                    }
            );
        }

        @Override
        public void execute(EntryEntity entryEntity, Map<String, IComplexType> params) {
            switch (entryEntity.entry.kind) {
                case Cache:
                    executeTask(entryEntity, new DiskUsageReport.DeleteCache(entryEntity), true);
                    break;
                case Sources:
                    DiskUsageReport.deleteSource(entryEntity);
                    break;
                case Dump:
                    DiskUsageReport.deleteDump(entryEntity);
                    break;
                default:
                    throw new UnsupportedOperationException("Not supported yet.");
            }
        }

        @Override
        public boolean multiContextAllowed() {
            return true;
        }
        
    }

}