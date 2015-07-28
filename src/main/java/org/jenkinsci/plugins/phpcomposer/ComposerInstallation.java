package org.jenkinsci.plugins.phpcomposer;

import hudson.EnvVars;
import hudson.Extension;
import hudson.model.EnvironmentSpecific;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.NodeSpecific;
import hudson.tools.ToolDescriptor;
import hudson.tools.ToolInstallation;
import hudson.tools.ToolInstaller;
import hudson.tools.ToolProperty;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * Created by unilama on 23.07.15.
 */
public class ComposerInstallation extends ToolInstallation implements NodeSpecific<ComposerInstallation>, EnvironmentSpecific<ComposerInstallation>, Serializable {

    @DataBoundConstructor
    public ComposerInstallation(String name, String home, List<? extends ToolProperty<?>> properties) {
        super(name, home, properties);
    }

    @Override
    public void buildEnvVars(EnvVars env) {
        String home = getHome();
        if (home == null) {
            return;
        }

        env.override("PATH+COMPOSER_HOME", home);
        env.override("PATH+COMPOSER_GLOBAL_BIN", home + "/vendor/bin");
        env.override("COMPOSER_HOME", home);
    }

    @Override
    public ComposerInstallation forEnvironment(EnvVars environment) {
        return new ComposerInstallation(getName(), environment.expand(getHome()),getProperties());
    }

    @Override
    public ComposerInstallation forNode(Node node, TaskListener log) throws IOException, InterruptedException {
        return new ComposerInstallation(getName(), translateFor(node, log),getProperties());
    }

    @Extension
    public static class DescriptorImpl extends ToolDescriptor<ComposerInstallation>{

        @Override
        public List<? extends ToolInstaller> getDefaultInstallers() {
            return Collections.singletonList(new ComposerInstaller(null, "", 72));
        }

        @Override
        public String getDisplayName() {
            return Messages.installer_displayName();
        }

        @Override
        public void setInstallations(ComposerInstallation... installations) {
            ComposerPlugin.instance().setInstallations(installations);
        }

        @Override
        public ComposerInstallation[] getInstallations() {
            return ComposerPlugin.instance().getInstallations();
        }
    }
}
