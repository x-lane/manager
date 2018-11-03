package manager.commands.offshoot;

import codex.command.EntityCommand;
import codex.component.dialog.Dialog;
import codex.component.messagebox.MessageBox;
import codex.component.messagebox.MessageType;
import codex.log.Logger;
import codex.task.AbstractTask;
import codex.type.IComplexType;
import codex.utils.ImageUtils;
import codex.utils.Language;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.MessageFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import manager.nodes.Offshoot;
import manager.type.WCStatus;
import org.apache.commons.io.FileDeleteStrategy;


public class DeleteWC extends EntityCommand<Offshoot> {
    
    public DeleteWC() {
        super(
                "clean", 
                "title", 
                ImageUtils.resize(ImageUtils.getByPath("/images/minus.png"), 28, 28), 
                Language.get("desc"),
                (offshoot) -> {
                    return !offshoot.getWCStatus().equals(WCStatus.Absent);
                }
        );
    }
    
    @Override
    public boolean multiContextAllowed() {
        return true;
    }

    @Override
    public void execute(Offshoot offshoot, Map<String, IComplexType> map) {
        executeTask(offshoot, new DeleteTask(offshoot), true);
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        SwingUtilities.invokeLater(() -> {
            String message; 
            if (getContext().size() == 1) {
                message = MessageFormat.format(
                        Language.get("confirm@clean.single"), 
                        getContext().get(0)
                );
            } else {
                StringBuilder entityList = new StringBuilder();
                getContext().forEach((entity) -> {
                    entityList.append("<br>&emsp;&#9913&nbsp;&nbsp;").append(entity.toString());
                });
                message = MessageFormat.format(
                        Language.get("confirm@clean.range"), 
                        entityList.toString()
                );
            }
            MessageBox.show(
                    MessageType.CONFIRMATION, null, message,
                    (close) -> {
                        if (close.getID() == Dialog.OK) {
                            Logger.getLogger().debug("Perform command [{0}]. Context: {1}", getName(), getContext());
                            getContext().stream()
                                    .filter((offshoot) -> (!offshoot.model.existReferencies()))
                                    .forEachOrdered((offshoot) -> {
                                        execute(offshoot, null);
                                    });
                        }
                    }
            );
        });
    }

    @Override
    public Kind getKind() {
        return Kind.Admin;
    }

    public class DeleteTask extends AbstractTask<Void> {
        
        private final Offshoot offshoot;

        public DeleteTask(Offshoot offshoot) {
            super(Language.get(DeleteWC.class.getSimpleName(), "title") + ": "+offshoot.getLocalPath());
            this.offshoot = offshoot;
        }

        @Override
        public boolean isPauseable() {
            return true;
        }

        @Override
        public Void execute() throws Exception {
            String wcPath = offshoot.getLocalPath();
            offshoot.setWCLoaded(false);
            offshoot.model.commit(false);
            
            setProgress(0, Language.get(DeleteWC.class.getSimpleName(), "command@calc"));
            long totalFiles = Files.walk(Paths.get(wcPath)).count();

            AtomicInteger processed = new AtomicInteger(0);
            Files.walkFileTree(Paths.get(wcPath), new SimpleFileVisitor<Path>() {

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    return processPath(file);
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    return processPath(dir);
                }
                
                private FileVisitResult processPath(Path path) {
                    checkPaused();
                    if (isCancelled()) {
                        return FileVisitResult.TERMINATE;
                    }
                    try {
                        FileDeleteStrategy.NORMAL.delete(path.toFile());
                        processed.addAndGet(1);
                        String fileName = path.toString().replace(wcPath + File.separator, "");
                        setProgress(
                                (int) (processed.get() * 100 / totalFiles),
                                MessageFormat.format(
                                        Language.get(DeleteWC.class.getSimpleName(), "command@progress"),
                                        fileName
                                )
                        );
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
         
            try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(Paths.get(wcPath).getParent());) {
                if (!dirStream.iterator().hasNext()) {
                    FileDeleteStrategy.NORMAL.delete(new File(wcPath).getParentFile());
                }
            }
            return null;
        }

        @Override
        public void finished(Void t) {
            SwingUtilities.invokeLater(() -> {
                if (!isCancelled() && offshoot.getWorkingCopyStatus() == WCStatus.Absent) {
                    offshoot.model.remove();
                } else {
                    offshoot.model.read();
                }
            });
        }
    
    }
    
}
