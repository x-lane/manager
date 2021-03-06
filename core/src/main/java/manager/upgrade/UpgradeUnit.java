package manager.upgrade;

import codex.component.dialog.Dialog;
import codex.instance.IInstanceListener;
import codex.instance.Instance;
import codex.instance.InstanceCommunicationService;
import codex.log.Logger;
import codex.service.ServiceRegistry;
import codex.task.ITaskExecutorService;
import codex.task.TaskManager;
import codex.unit.AbstractUnit;
import codex.utils.ImageUtils;
import codex.utils.Language;
import manager.xml.Version;
import manager.xml.VersionsDocument;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.text.MessageFormat;
import java.util.AbstractMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

public final class UpgradeUnit extends AbstractUnit implements IInstanceListener {
    
    private final static ImageIcon ICON = ImageUtils.resize(ImageUtils.getByPath("/images/upgrade.png"), 17, 17);
    private final static InstanceCommunicationService ICS = (InstanceCommunicationService) ServiceRegistry.getInstance().lookupService(InstanceCommunicationService.class);
    
    private final Version currentVersion;
    private final AtomicBoolean skip = new AtomicBoolean(false);

    private final Queue<Map.Entry<Instance, VersionsDocument>> providers = new PriorityQueue<Map.Entry<Instance, VersionsDocument>>((entry1, entry2) -> {
        Version v1 = Version.Factory.newInstance();
        Version v2 = Version.Factory.newInstance();
        v1.setNumber(entry1.getValue().getVersions().getCurrent());
        v2.setNumber(entry2.getValue().getVersions().getCurrent());
        return UpgradeService.VER_COMPARATOR.compare(v2, v1);
    }) {
        @Override
        public boolean add(Map.Entry<Instance, VersionsDocument> entry) {
            boolean result = super.add(entry);
            updateDialog();
            return result;
        }

        @Override
        public boolean remove(Object entry) {
            boolean result = super.remove(entry);
            updateDialog();
            return result;
        }

        private void updateDialog() {
            SwingUtilities.invokeLater(() -> {
                Map.Entry<Instance, VersionsDocument> nextProvider = providers.peek();
                if (nextProvider != null) {
                    synchronized (providers) {
                        dialog.updateInfo(nextProvider.getValue(), providers.size());
                    }
                }
                dialog.setVisible(!providers.isEmpty());
            });
        }
    };

    private final UpgradeDialog dialog = new UpgradeDialog(event -> {
        synchronized (skip) {
            skip.set(true);
            if (event.getID() == Dialog.OK) {
                ITaskExecutorService TES = (ITaskExecutorService) ServiceRegistry.getInstance().lookupService(TaskManager.TaskExecutorService.class);
                TES.quietTask(new LoadUpgrade(providers.peek().getKey()));
            } else {
                Logger.getLogger().debug("Upgrade skipped by user");
                providers.clear();
            }
        }
    });
    
    public UpgradeUnit() {
        Logger.getLogger().debug("Initialize unit: Upgrade Manager");
        ICS.addInstanceListener(this);
        currentVersion = UpgradeService.getVersion();
    }

    @Override
    public JComponent createViewport() {
        JLabel label = new JLabel(
                MessageFormat.format(Language.get("current"), currentVersion.getNumber()),
                ICON, SwingConstants.CENTER
        ) {{
            setBorder(new EmptyBorder(new Insets(2, 10, 2, 10)));
        }};
        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                UpgradeDialog dialog = new UpgradeDialog(null);
                dialog.updateInfo(UpgradeService.getHistory(), 0);
                dialog.setVisible(true);
            }
        });
        return label;
    }

    @Override
    public void instanceLinked(Instance instance)  {
        synchronized (skip) {
            if (skip.get()) return;
        }
        new Thread(() -> {
            try {
                IUpgradeService remoteUpService = (IUpgradeService) instance.getService(UpgradeService.class);
                Version availVersion = remoteUpService.getCurrentVersion();
                if (availVersion != null && UpgradeService.VER_COMPARATOR.compare(currentVersion, availVersion) < 0) {
                    VersionsDocument diff = remoteUpService.getDiffVersions(currentVersion, availVersion);
                    Map.Entry<Instance, VersionsDocument> entry = new AbstractMap.SimpleImmutableEntry<>(instance, diff);
                    synchronized (providers) {
                        Logger.getLogger().debug(
                                "Add upgrade provider: {0} ({1} -> {2})",
                                instance, currentVersion.getNumber(), availVersion.getNumber()
                        );
                        providers.add(entry);
                    }
                } else if (availVersion != null) {
                    Logger.getLogger().debug("Remote instance {0} version: {1}", instance, availVersion.getNumber());
                }
            } catch (RemoteException | NotBoundException e) {
                // Do nothing
            }
        }).start();
    }

    @Override
    public void instanceUnlinked(Instance instance) {
        synchronized (skip) {
            if (skip.get()) return;
        }
        synchronized (providers) {
            providers.stream()
                    .filter(entry -> entry.getKey().equals(instance))
                    .findFirst().ifPresent(entry -> {
                        Logger.getLogger().debug("Remove upgrade provider: {0}", instance);
                        providers.remove(entry);
                    }
            );
        }
    }

}
