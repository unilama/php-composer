package org.jenkinsci.plugins.phpcomposer;

import hudson.Extension;
import hudson.FilePath;
import hudson.ProxyConfiguration;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import hudson.tools.ToolInstallation;
import hudson.tools.ToolInstaller;
import hudson.tools.ToolInstallerDescriptor;
import hudson.util.ArgumentListBuilder;
import hudson.util.IOUtils;
import jenkins.MasterToSlaveFileCallable;
import org.apache.commons.io.input.CountingInputStream;
import org.kohsuke.stapler.DataBoundConstructor;


import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;

import static hudson.FilePath.TarCompression.GZIP;

/**
 * Created by unilama on 23.07.15.
 */
public class ComposerInstaller extends ToolInstaller {

    public static final String COMPOSER_EXE_NAME = "composer";
    private final String composerDependencies;

    public long getComposerDepsRefreshHours() {
        return composerDepsRefreshHours;
    }

    public String getComposerDependencies() {
        return composerDependencies;
    }

    private final long composerDepsRefreshHours;
    public static final String COMPOSER_INSTALL_URL = "https://getcomposer.org/installer";
    public static final String COMPOSER_DEPENDENCIES_CHECKSUM = ".composerGlobalDependencies";
    private static final String COMPOSER_DEPENDENCIES_LAST_UPDATE = ".composerLastUpdate";

    @DataBoundConstructor
    public ComposerInstaller(String id, String composerDependencies, long composerDepsRefreshHours) {
        super(id);
        this.composerDependencies = composerDependencies;
        this.composerDepsRefreshHours = composerDepsRefreshHours;
    }

    @Override
    public FilePath performInstallation(ToolInstallation tool, Node node, TaskListener log) throws IOException, InterruptedException {
        FilePath filePath = preferredLocation(tool, node);

        //download installation script
        if( installIfNecessaryFrom(new URL(COMPOSER_INSTALL_URL), log, "Downloading PHP composer installation script...", filePath) ) {

            //execute installation script
            ArgumentListBuilder exeInstall = new ArgumentListBuilder();
            exeInstall.add("php");
            exeInstall.add(filePath.child("installer"));
            exeInstall.add("--");
            exeInstall.addKeyValuePair("--", "install-dir", filePath.getRemote(), false);
            exeInstall.addKeyValuePair("--", "filename", COMPOSER_EXE_NAME, false);

            // run and cleanup
            hudson.Launcher launcher = node.createLauncher(log);
            int exitCode = launcher.launch().cmds(exeInstall).stdout(log).join();

            if (exitCode != 0) {
                log.error(Messages.ComposerInstaller_InstallationFailed());
                return filePath;
            }
            log.getLogger().println(Messages.ComposerInstaller_InstallationCompleted());
        }else {
            //TODO move to messages
            log.getLogger().println("Installation skipped...");
        }


        if( requireDependencies(filePath, node, log)){
            log.getLogger().println(Messages.ComposerInstaller_GlobalDependenciesInstalled());
        }else{
            log.error(Messages.ComposerInstaller_GlobalDependenciesInstallationProblem());
        }

        if( isComposerUpdateNeeded(filePath) ) {
            if( composerSelfUpdate(filePath, node, log) && composerUpdateDeps(filePath, node, log) ) {
                filePath.child(COMPOSER_DEPENDENCIES_LAST_UPDATE).write(Long.toString(System.currentTimeMillis()), "UTF-8");
            }
        }

        return filePath;
    }

    /**
     * Copy paste from FilePath.java since we don't need unarchive installer
     * @param archive
     * @param listener
     * @param message
     * @return
     * @throws IOException
     * @throws InterruptedException
     */
    private boolean installIfNecessaryFrom(@Nonnull URL archive, @CheckForNull TaskListener listener, @Nonnull String message, @Nonnull FilePath baseDir) throws IOException, InterruptedException {
        try {
            FilePath timestamp = baseDir.child(".timestamp");
            long lastModified = timestamp.lastModified();
            URLConnection con;
            try {
                con = ProxyConfiguration.open(archive);
                if (lastModified != 0) {
                    con.setIfModifiedSince(lastModified);
                }
                con.connect();
            } catch (IOException x) {
                if (baseDir.exists()) {
                    // Cannot connect now, so assume whatever was last unpacked is still OK.
                    if (listener != null) {
                        listener.getLogger().println("Skipping installation of " + archive + " to " + baseDir.getRemote() + ": " + x);
                    }
                    return false;
                } else {
                    throw x;
                }
            }

            if (lastModified != 0 && con instanceof HttpURLConnection) {
                HttpURLConnection httpCon = (HttpURLConnection) con;
                int responseCode = httpCon.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
                    return false;
                } else if (responseCode != HttpURLConnection.HTTP_OK) {
                    listener.getLogger().println("Skipping installation of " + archive + " to " + baseDir.getRemote() + " due to server error: " + responseCode + " " + httpCon.getResponseMessage());
                    return false;
                }
            }

            long sourceTimestamp = con.getLastModified();

            if(baseDir.exists()) {
                if (lastModified != 0 && sourceTimestamp == lastModified) {
                    listener.getLogger().println("Skipping installation of " + archive + " to " + baseDir.getRemote() + " due to file is up to date "+sourceTimestamp+" "+lastModified);
                    return false;   // already up to date
                }
                baseDir.deleteContents();
            } else {
                baseDir.mkdirs();
            }

            if(listener!=null)
                listener.getLogger().println(message);

            if (baseDir.isRemote()) {
                // First try to download from the slave machine.
                try {
                    baseDir.act(new JustCopy( getInstallerFile(baseDir).toURI().toURL()));
                    timestamp.touch(sourceTimestamp);
                    return true;
                } catch (IOException x) {
                    if (listener != null) {
                        x.printStackTrace(listener.error("Failed to download " + archive + " from slave; will retry from master"));
                    }
                }
            }

            // for HTTP downloads, enable automatic retry for added resilience
            InputStream in = archive.getProtocol().startsWith("http") ? ProxyConfiguration.getInputStream(archive) : con.getInputStream();
            CountingInputStream cis = new CountingInputStream(in);
            IOUtils.copy(cis, getInstallerFile(baseDir));
            timestamp.touch(sourceTimestamp);
            return true;
        } catch (IOException e) {
            throw new IOException("Failed to install "+archive+" to "+baseDir.getRemote(),e);
        }
    }

    protected File getInstallerFile(FilePath baseDir){
        return new File(baseDir.child("installer").getRemote());
    }

    // again copy paste form Unpack this reads from arbitrary URL
    private final class JustCopy extends MasterToSlaveFileCallable<Void> {
        private final URL archive;
        JustCopy(URL archive) {
            this.archive = archive;
        }
        @Override public Void invoke(File dir, VirtualChannel channel) throws IOException, InterruptedException {
            InputStream in = archive.openStream();
            try {
                CountingInputStream cis = new CountingInputStream(in);
                IOUtils.copy(cis, dir);
            } finally {
                in.close();
            }
            return null;
        }
    }

    private boolean isComposerUpdateNeeded(FilePath filePath) throws IOException, InterruptedException {
        FilePath lastUpdateFile = filePath.child(COMPOSER_DEPENDENCIES_LAST_UPDATE);
        return lastUpdateFile.exists() && Long.getLong(lastUpdateFile.readToString())+ TimeUnit.HOURS.toMillis(getComposerDepsRefreshHours()) > System.currentTimeMillis();
    }

    private boolean composerUpdateDeps(FilePath filePath, Node node, TaskListener log) throws IOException, InterruptedException {
        ArgumentListBuilder install = new ArgumentListBuilder();
        install.add("php");
        install.add(filePath.child(COMPOSER_EXE_NAME));
        install.add("global");
        install.add("update");

        hudson.Launcher launcher = node.createLauncher(log);
        return launcher.launch().cmds(install).stdout(log).join() == 0;
    }

    private boolean composerSelfUpdate(FilePath filePath, Node node, TaskListener log) throws IOException, InterruptedException {
        ArgumentListBuilder install = new ArgumentListBuilder();
        install.add("php");
        install.add(filePath.child(COMPOSER_EXE_NAME));
        install.add("self-update");

        hudson.Launcher launcher = node.createLauncher(log);
        return launcher.launch().cmds(install).stdout(log).join() == 0;
    }

    private boolean requireDependencies(FilePath filePath, Node node, TaskListener log) throws IOException, InterruptedException {

        if( getComposerDependencies() == null || getComposerDependencies().isEmpty() || isDependencyListUpToDate(filePath) ){
            log.getLogger().println("Skipping dependencies installation...");
            return true;
        }

        ArgumentListBuilder install = new ArgumentListBuilder();
        install.add("php");
        install.add(filePath.child(COMPOSER_EXE_NAME));
        install.add("global");
        install.add("require");

        for(String packageName : getComposerDependencies().split("\\s")){
            install.add(packageName);
        }


        hudson.Launcher launcher = node.createLauncher(log);
        boolean installResult = launcher.launch().cmds(install).stdout(log).join() == 0;
        //in case of successfully added required dependencies we store checksum
        if( installResult ) {
            saveDependenciesList(filePath);
        }
        return installResult;
    }

    private void saveDependenciesList(FilePath filePath) throws IOException, InterruptedException {
        filePath.child(COMPOSER_DEPENDENCIES_CHECKSUM).write(getDependenciesChecksum(), "UTF-8");
    }

    private boolean isDependencyListUpToDate(FilePath filePath) throws IOException, InterruptedException {
        FilePath checksumFile = filePath.child(COMPOSER_DEPENDENCIES_CHECKSUM);
        return checksumFile.exists() && checksumFile.readToString().equals(getDependenciesChecksum());
    }

    private String getDependenciesChecksum() {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return new String( md.digest(this.composerDependencies.getBytes("UTF-8")) );
        } catch (NoSuchAlgorithmException e) {
            return this.composerDependencies;
        } catch (UnsupportedEncodingException e) {
            return this.composerDependencies;
        }
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    @Extension
    public static final class DescriptorImpl extends ToolInstallerDescriptor<ComposerInstaller> {
        public DescriptorImpl() {
            load();
        }

        public String getDisplayName() {
            return Messages.ComposerInstaller_DescriptorImpl_displayName();
        }

        @Override
        public String getId() {
            // For backward compatibility
            return "org.jenkinsci.plugins.phpcomposer.ComposerInstaller";
        }

        @Override
        public boolean isApplicable(Class<? extends ToolInstallation> toolType) {
            return toolType == ComposerInstallation.class;
        }


    }
}
