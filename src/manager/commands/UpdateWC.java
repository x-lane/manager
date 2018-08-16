package manager.commands;

import codex.command.EntityCommand;
import codex.explorer.tree.INode;
import codex.log.Logger;
import codex.model.Entity;
import codex.service.ServiceRegistry;
import codex.task.AbstractTask;
import codex.task.ITaskExecutorService;
import codex.task.TaskManager;
import codex.type.IComplexType;
import codex.utils.ImageUtils;
import codex.utils.Language;
import java.io.File;
import java.nio.channels.ClosedChannelException;
import java.text.MessageFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.swing.SwingUtilities;
import manager.nodes.Offshoot;
import manager.svn.SVN;
import manager.type.WCStatus;
import org.tmatesoft.svn.core.SVNCancelException;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNNodeKind;
import org.tmatesoft.svn.core.wc.ISVNEventHandler;
import org.tmatesoft.svn.core.wc.SVNEvent;
import org.tmatesoft.svn.core.wc.SVNEventAction;
import org.tmatesoft.svn.core.wc.SVNRevision;


public class UpdateWC extends EntityCommand {

    public UpdateWC() {
        super(
                "update", 
                "title", 
                ImageUtils.resize(ImageUtils.getByPath("/images/update.png"), 28, 28), 
                Language.get("desc"), 
                (entity) -> {
                    return !entity.model.getValue("wcStatus").equals(WCStatus.Invalid);
                }
        );
        setGroupId("update");
    }

    @Override
    public boolean multiContextAllowed() {
        return true;
    }

    @Override
    public void execute(Entity entity, Map<String, IComplexType> map) {
        ((ITaskExecutorService) ServiceRegistry.getInstance().lookupService(TaskManager.TaskExecutorService.class)).enqueueTask(
                new UpdateTask((Offshoot) entity)
        );
    }
    
    public class UpdateTask extends AbstractTask<Void> {

        private final Offshoot offshoot;

        public UpdateTask(Offshoot offshoot) {
            super(Language.get(UpdateWC.class.getSimpleName(), "title") + ": "+offshoot.getWCPath());
            this.offshoot = offshoot;
        }

        @Override
        public Void execute() throws Exception {
            offshoot.model.setValue("loaded", false);
            offshoot.model.commit();
            
            String wcPath  = offshoot.getWCPath();
            String repoUrl = Entity.getOwner(offshoot).model.getValue("repoUrl")+"/dev/"+offshoot.model.getPID();
            
            setProgress(0, Language.get(UpdateWC.class.getSimpleName(), "command@calc"));
            try {
                Long changes = SVN.diff(wcPath, repoUrl, SVNRevision.HEAD, null, null, new ISVNEventHandler() {
                    @Override
                    public void handleEvent(SVNEvent svne, double d) throws SVNException {}

                    @Override
                    public void checkCancelled() throws SVNCancelException {
                        if (UpdateTask.this.isCancelled()) {
                            throw new SVNCancelException();
                        }
                    }
                });
                if (changes > 0) {
                    Logger.getLogger().info(
                            "UPDATE [{0}] started", 
                            new Object[]{wcPath}
                    );
                    AtomicInteger loaded   = new AtomicInteger(0);
                    AtomicInteger added    = new AtomicInteger(0);
                    AtomicInteger deleted  = new AtomicInteger(0);
                    AtomicInteger restored = new AtomicInteger(0);
                    AtomicInteger changed  = new AtomicInteger(0);

                    SVN.update(repoUrl, wcPath, SVNRevision.HEAD, null, null, new ISVNEventHandler() {
                            @Override
                            public void handleEvent(SVNEvent event, double d) throws SVNException {
                                if (event.getAction() != SVNEventAction.UPDATE_STARTED && event.getAction() != SVNEventAction.UPDATE_COMPLETED) {
                                    if (event.getNodeKind() != SVNNodeKind.DIR) {
                                        loaded.addAndGet(1);
                                        int percent = (int) (loaded.get() * 100 / changes);
                                        setProgress(
                                                percent > 100 ? 100 : percent, 
                                                MessageFormat.format(
                                                        Language.get(UpdateWC.class.getSimpleName(), "command@progress"), 
                                                        event.getFile().getPath().replace(wcPath+File.separator, "")
                                                )
                                        );
                                        SVNEventAction action = event.getAction();
                                        if (action == SVNEventAction.UPDATE_ADD) {
                                            added.addAndGet(1);
                                        } else if (action == SVNEventAction.UPDATE_DELETE) {
                                            deleted.addAndGet(1);
                                        } else if (action == SVNEventAction.UPDATE_UPDATE) {
                                            changed.addAndGet(1);
                                        } else if (action == SVNEventAction.RESTORE) {
                                            restored.addAndGet(1);
                                        } else {
                                            System.err.println(action + " / " + event.getFile().getPath().replace(wcPath+File.separator, ""));
                                        }
                                    }
                                }
                            }

                            @Override
                            public void checkCancelled() throws SVNCancelException {
                                if (UpdateTask.this.isCancelled()) {
                                    throw new SVNCancelException();
                                }
                            }
                        }
                    );

                    Logger.getLogger().info(
                            "UPDATE [{0}] finished\n"+
                            (added.get()    == 0 ? "" : "                     * Added:    {1}\n")+
                            (deleted.get()  == 0 ? "" : "                     * Deleted:  {2}\n")+
                            (restored.get() == 0 ? "" : "                     * Restored: {3}\n")+
                            (changed.get()  == 0 ? "" : "                     * Changed:  {4}\n")+
                                                        "                     * Total:    {5}", 
                            new Object[]{wcPath, added.get(), deleted.get(), restored.get(), changed.get(), loaded.get()}
                    );
                } else {
                    Logger.getLogger().info("No need to update working copy: {0}", wcPath);
                }
            } catch (SVNException e) {
                Optional<Throwable> rootCause = Stream
                        .iterate(e, Throwable::getCause)
                        .filter(element -> element.getCause() == null)
                        .findFirst();
                if (rootCause.get() instanceof SVNCancelException || rootCause.get() instanceof ClosedChannelException) {
                    Logger.getLogger().warn(
                            "UPDATE [{0}] canceled", 
                            new Object[]{wcPath}
                    );
                }
            }
            return null;
        }

        @Override
        public void finished(Void res) {
            SwingUtilities.invokeLater(() -> {
                WCStatus status = offshoot.getStatus();
                offshoot.model.setValue("wcStatus", status);
                offshoot.model.setValue("loaded",  !status.equals(WCStatus.Absent));
                offshoot.model.commit();
                offshoot.model.updateDynamicProp("localRev");
                offshoot.setMode(INode.MODE_SELECTABLE + (status.equals(WCStatus.Absent) ? 0 : INode.MODE_ENABLED));
            });
        }
    
    }
}