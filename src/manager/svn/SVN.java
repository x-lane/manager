package manager.svn;

import codex.log.Logger;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.tmatesoft.svn.core.ISVNDirEntryHandler;
import org.tmatesoft.svn.core.SVNCancelException;
import org.tmatesoft.svn.core.SVNDepth;
import org.tmatesoft.svn.core.SVNDirEntry;
import org.tmatesoft.svn.core.SVNErrorCode;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNNodeKind;
import org.tmatesoft.svn.core.SVNProperties;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.auth.BasicAuthenticationManager;
import org.tmatesoft.svn.core.auth.ISVNAuthenticationManager;
import org.tmatesoft.svn.core.auth.SVNAuthentication;
import org.tmatesoft.svn.core.auth.SVNPasswordAuthentication;
import org.tmatesoft.svn.core.internal.io.svn.SVNRepositoryFactoryImpl;
import org.tmatesoft.svn.core.internal.wc.DefaultSVNOptions;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.io.SVNRepositoryFactory;
import org.tmatesoft.svn.core.wc.ISVNEventHandler;
import org.tmatesoft.svn.core.wc.ISVNStatusHandler;
import org.tmatesoft.svn.core.wc.SVNClientManager;
import org.tmatesoft.svn.core.wc.SVNInfo;
import org.tmatesoft.svn.core.wc.SVNLogClient;
import org.tmatesoft.svn.core.wc.SVNRevision;
import org.tmatesoft.svn.core.wc.SVNStatus;
import org.tmatesoft.svn.core.wc.SVNStatusClient;
import org.tmatesoft.svn.core.wc.SVNStatusType;
import org.tmatesoft.svn.core.wc.SVNUpdateClient;
import org.tmatesoft.svn.core.wc.SVNWCClient;
import org.tmatesoft.svn.core.wc.SVNWCUtil;
import org.tmatesoft.svn.core.wc2.SvnDiffSummarize;
import org.tmatesoft.svn.core.wc2.SvnOperationFactory;
import org.tmatesoft.svn.core.wc2.SvnTarget;


public class SVN {
    
    public static SVNInfo info(String url, boolean remote, String user, String pass){
        SVNInfo info = null;
        
        final SVNAuthentication auth = new SVNPasswordAuthentication(user, pass, false);
        final ISVNAuthenticationManager authMgr = new BasicAuthenticationManager(new SVNAuthentication[] { auth });
        final SVNClientManager clientMgr = SVNClientManager.newInstance(new DefaultSVNOptions(), authMgr);
        
        try {
            if (remote) {
                SVNURL svnUrl = SVNURL.parseURIEncoded(url);
                info = clientMgr.getWCClient().doInfo(svnUrl, SVNRevision.HEAD, SVNRevision.HEAD);
            } else if (new File(url).exists()) {
                info = clientMgr.getWCClient().doInfo(new File(url), SVNRevision.WORKING);
            }
        } catch (SVNException e) {
            Logger.getLogger().warn("SVN operation ''info'' error: {0}", e.getErrorMessage());
        } finally {
            clientMgr.dispose();
        }
        return info;
    }
    
    public static List<SVNDirEntry> list(String url, String user, String pass) throws SVNException {
        final List<SVNDirEntry> entries = new LinkedList<>();
        
        final SVNAuthentication auth = new SVNPasswordAuthentication(user, pass, false);
        final ISVNAuthenticationManager authMgr = new BasicAuthenticationManager(new SVNAuthentication[] { auth });
        final SVNClientManager clientMgr = SVNClientManager.newInstance(new DefaultSVNOptions(), authMgr);
        
        try {
            SVNURL svnUrl = SVNURL.parseURIEncoded(url);
            SVNLogClient client = clientMgr.getLogClient();
            client.doList(svnUrl, SVNRevision.HEAD, SVNRevision.HEAD, true, false, (entry) -> {
                entries.add(entry);
            });
        } catch (SVNException e) {
            Logger.getLogger().warn("SVN operation ''list'' error: {0}", e.getErrorMessage());
            throw e;
        } finally {
            clientMgr.dispose();
        }
        return entries;
    }
    
    public static Long diff(String path, String url, SVNRevision revision, String user, String pass, ISVNEventHandler handler) throws SVNException {
        AtomicLong changes = new AtomicLong(0);
        
        final SVNAuthentication auth = new SVNPasswordAuthentication(user, pass, false);
        final ISVNAuthenticationManager authMgr = new BasicAuthenticationManager(new SVNAuthentication[] { auth });
        final SVNClientManager clientMgr = SVNClientManager.newInstance(new DefaultSVNOptions(), authMgr);
        
        final File localDir = new File(path);
        
        try {
            if (!SVNWCUtil.isVersionedDirectory(localDir)) {
                // Checkout
                SVNURL svnUrl = SVNURL.parseURIEncoded(url);
                SVNLogClient logClient = clientMgr.getLogClient();
                if (handler != null) {
                    logClient.setEventHandler(handler);
                }
                logClient.doList(svnUrl, revision, revision, true, SVNDepth.INFINITY, 1, new ISVNDirEntryHandler() {
                    @Override
                    public void handleDirEntry(SVNDirEntry entry) throws SVNException {
                        if (entry.getKind() != SVNNodeKind.DIR) {
                            changes.addAndGet(1);
                        }
                    }
                });
            } else {
                AtomicBoolean incomplete = new AtomicBoolean(false);
                SVNStatusClient statusClient = clientMgr.getStatusClient();
                if (handler != null) {
                    statusClient.setEventHandler(handler);
                }
                try {    
                    statusClient.doStatus(localDir, SVNRevision.WORKING, SVNDepth.INFINITY, true, true, false, false, new ISVNStatusHandler() {
                        @Override
                        public void handleStatus(SVNStatus status) throws SVNException {
                            incomplete.set(status.getNodeStatus() == SVNStatusType.STATUS_INCOMPLETE);
                            if (incomplete.get()) {
                                throw new SVNCancelException();
                            }
                        }
                    }, null);
                } catch (SVNCancelException e) {}
                
                if (incomplete.get()) {
                    statusClient.doStatus(localDir, SVNRevision.WORKING, SVNDepth.INFINITY, true, true, false, false, new ISVNStatusHandler() {
                        @Override
                        public void handleStatus(SVNStatus status) throws SVNException {
                                if (
                                        // Remote.ADDED - для недокаченной копии
                                        status.getCombinedRemoteNodeAndContentsStatus() == SVNStatusType.STATUS_ADDED || 
                                        // Local.MISSING - для удаленных
                                        status.getCombinedNodeAndContentsStatus() == SVNStatusType.STATUS_MISSING
                                ) {
                                    if (status.getRemoteKind() != SVNNodeKind.DIR) {
                                        changes.addAndGet(1);
                                    }
                                }
                        }
                    }, null);
                } else {
                    // Update from Rx to Ry
                    SVNRevision current = statusClient.doStatus(localDir, false).getRevision();
                    final SvnDiffSummarize diff = new SvnOperationFactory().createDiffSummarize();
                    diff.setSources(
                            SvnTarget.fromFile(localDir, current),
                            SvnTarget.fromURL(SVNURL.parseURIEncoded(url), revision)
                    );
                    diff.setRecurseIntoDeletedDirectories(true);
                    diff.setReceiver((target, status) -> {
                        if (handler != null) {
                            handler.checkCancelled();
                        }
                        changes.addAndGet(1);
                    });
                    diff.run();
                }
            }
        } finally {
            clientMgr.dispose();
        }
        return changes.get();
    }
    
    public static void update(String url, String path, SVNRevision revision, String user, String pass, ISVNEventHandler handler) throws SVNException {
        final SVNAuthentication auth = new SVNPasswordAuthentication(user, pass, false);
        final ISVNAuthenticationManager authMgr = new BasicAuthenticationManager(new SVNAuthentication[] { auth });
        final SVNClientManager clientMgr = SVNClientManager.newInstance(new DefaultSVNOptions(), authMgr);
        
        final SVNUpdateClient updateClient = clientMgr.getUpdateClient();
        updateClient.setIgnoreExternals(false);
        if (handler != null) {
            updateClient.setEventHandler(handler);
        }
        final File localDir = new File(path);
        try {
            if (!SVNWCUtil.isVersionedDirectory(localDir)) {
                SVNURL svnUrl = SVNURL.parseURIEncoded(url);
                updateClient.doCheckout(svnUrl, localDir, SVNRevision.UNDEFINED, revision, SVNDepth.INFINITY, false);
            } else {
                boolean needCleanup = false;
                do {
                    try {
                        if (needCleanup) {
                            SVNWCClient client = clientMgr.getWCClient();
                            client.doCleanup(localDir, true, true, true, false, false, true);
                            Logger.getLogger().info("Continue update after recovery");
                        }
                        updateClient.doUpdate(localDir, revision, SVNDepth.INFINITY, false, true);
                    } catch (SVNException e) {
                        if (e.getErrorMessage().getErrorCode().getCode() == SVNErrorCode.WC_LOCKED.getCode()) {
                            Logger.getLogger().warn("Perform cleanup");
                            needCleanup = true;
                            continue;
                        } else {
                            throw e;
                        }
                    }
                    break;
                } while (true);
            }
        } finally {
            clientMgr.dispose();
        }
    }
    
    public final static void export(String url, String path, String user, String pass) {
        export(url, path, user, pass, null);
    }
    
    public final static void export(String url, String path, String user, String pass, SVNDepth depth) {
        final SVNAuthentication auth = new SVNPasswordAuthentication(user, pass, false);
        final ISVNAuthenticationManager authMgr = new BasicAuthenticationManager(new SVNAuthentication[] { auth });
        final SVNClientManager clientMgr = SVNClientManager.newInstance(new DefaultSVNOptions(), authMgr);
        
        try {
            SVNURL svnUrl = SVNURL.parseURIEncoded(url);
            SVNUpdateClient client = clientMgr.getUpdateClient();
            client.doExport(svnUrl, new File(path), SVNRevision.HEAD, SVNRevision.HEAD, null, true, depth != null ? depth : SVNDepth.INFINITY);
        } catch (SVNException e) {
            Logger.getLogger().warn("SVN operation ''export'' error: {0}", e.getErrorMessage());
        } finally {
            clientMgr.dispose();
        }
    }
    
    public final static InputStream readFile(String url, String path, String user, String pass) {
        final SVNAuthentication auth = new SVNPasswordAuthentication(user, pass, false);
        final ISVNAuthenticationManager authMgr = new BasicAuthenticationManager(new SVNAuthentication[] { auth });
        
        try {
            SVNRepositoryFactoryImpl.setup();
            SVNRepository repository = SVNRepositoryFactory.create(SVNURL.parseURIEncoded(url));
            repository.setAuthenticationManager(authMgr);
            repository.setTunnelProvider(SVNWCUtil.createDefaultOptions(true));
            
            SVNProperties properties = new SVNProperties();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            repository.getFile(path, -1, properties, baos);
            
            return new ByteArrayInputStream(baos.toByteArray());
        } catch (SVNException e) {
            Logger.getLogger().warn("SVN operation ''read'' error: {0}", e.getErrorMessage());
        }
        return null;
    }
    
}
