package manager.commands.offshoot;

import codex.command.EntityCommand;
import codex.property.PropertyHolder;
import codex.task.GroupTask;
import codex.type.Bool;
import codex.type.IComplexType;
import codex.utils.ImageUtils;
import codex.utils.Language;
import java.util.Map;
import manager.commands.offshoot.build.BuildKernelTask;
import manager.commands.offshoot.build.BuildSourceTask;
import manager.nodes.Offshoot;
import manager.type.WCStatus;

public class RefreshWC extends EntityCommand<Offshoot> {

    public RefreshWC() {
        super(
                "refresh", 
                "title", 
                ImageUtils.getByPath("/images/rebuild.png"),
                Language.get("desc"), 
                (offshoot) -> !offshoot.getWCStatus().equals(WCStatus.Invalid)
        );
        setParameters(
                new PropertyHolder("clean", new Bool(Boolean.FALSE), true)
        );
    }
    
    @Override
    public boolean multiContextAllowed() {
        return true;
    }

    @Override
    public void execute(Offshoot offshoot, Map<String, IComplexType> map) {
        if (!offshoot.getRepository().isRepositoryOnline(true)) return;
        executeTask(
                offshoot, 
                new GroupTask<>(
                        Language.get("title") + ": "+(offshoot).getLocalPath(),
                        ((UpdateWC) offshoot.getCommand("update")).new UpdateTask(offshoot),
                        new BuildKernelTask(offshoot),
                        new BuildSourceTask(offshoot, map.get("clean").getValue() == Boolean.TRUE)
                ), 
                false
        );
    }
    
}
