package org.jenkinsci.plugins.phpcomposer;

import com.google.common.base.Throwables;
import hudson.*;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Run;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;

/**
 * Created by marcin on 24.07.15.
 */
public class ComposerBuildWrapper extends BuildWrapper {

    private String composerInstallationName;

    @DataBoundConstructor
    public ComposerBuildWrapper(String composerInstallationName) {
        this.composerInstallationName = composerInstallationName;
    }

    @Override
    public Environment setUp(AbstractBuild build, Launcher launcher,
                             BuildListener listener) throws IOException, InterruptedException {
        return new Environment(){
            @Override
            public boolean tearDown(AbstractBuild build, BuildListener listener)
                    throws IOException, InterruptedException {
                return true;
            }
        };
    }

    public String getComposerInstallationName() {
        return composerInstallationName;
    }

    @Override
    public Launcher decorateLauncher(final AbstractBuild build, final Launcher launcher, BuildListener listener) throws IOException, InterruptedException, Run.RunnerAbortedException {
        return new Launcher.DecoratedLauncher(launcher){
            @Override
            public Proc launch(ProcStarter starter) throws IOException {
                // Avoiding potential NPE when calling starter.envs()
                // Yes, this is weird...
                String[] starterEnvs;
                try {
                    starterEnvs = starter.envs();
                } catch (NullPointerException ex) {
                    starterEnvs = new String[0];
                }

                EnvVars vars = toEnvVars(starterEnvs);

                ComposerInstallation composerInstallation =
                              ComposerPlugin.instance().findInstallationByName(composerInstallationName);

                try {
                    composerInstallation = composerInstallation.forNode(build.getBuiltOn(), listener)
                            .forEnvironment(vars);
                } catch (InterruptedException e) {
                    Throwables.propagate(e);
                }

                composerInstallation.buildEnvVars(vars);
                vars.override("PATH+PATH", build.getWorkspace().child("vendor/bin").getRemote());


                return super.launch(starter.envs(Util.mapToEnv(vars)));
            }
            private EnvVars toEnvVars(String[] envs) {
                EnvVars vars = new EnvVars();
                for (String line : envs) {
                    vars.addLine(line);
                }
                return vars;
            }
        };
    }

    @Extension
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    public static final class DescriptorImpl extends BuildWrapperDescriptor {

        public DescriptorImpl() {
            super(ComposerBuildWrapper.class);
        }

        @Override
        public boolean isApplicable(AbstractProject<?, ?> item) {
            return true;
        }

        /**
         * @return available composer installations
         */
        public ComposerInstallation[] getInstallations() {
            return ComposerPlugin.instance().getInstallations();
        }

        public String getDisplayName() {
            return Messages.ComposerInstaller_ComposerPlugin_displayName();
        }
    }

}
