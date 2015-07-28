package org.jenkinsci.plugins.phpcomposer;

import hudson.Plugin;
import jenkins.model.Jenkins;

import java.io.IOException;

/**
 * Created by marcin on 24.07.15.
 */
public class ComposerPlugin extends Plugin {


    ComposerInstallation[] installations;

    public ComposerPlugin(){
        super();
    }

    @Override
    public void start() throws Exception {
        super.start();

        this.load();

        // If installations have not been read in nodejs.xml, let's initialize them
        if(this.installations == null){
            this.installations = new ComposerInstallation[0];
        }
    }

    public ComposerInstallation[] getInstallations() {
        return installations;
    }

    public ComposerInstallation findInstallationByName(String name) {
        for(ComposerInstallation nodeJSInstallation : getInstallations()){
            if(name.equals(nodeJSInstallation.getName())){
                return nodeJSInstallation;
            }
        }
        throw new IllegalArgumentException("NodeJS Installation not found : "+name);
    }

    public void setInstallations(ComposerInstallation[] installations) {
        this.installations = installations;
        try {
            this.save();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static ComposerPlugin instance() {
        return Jenkins.getInstance().getPlugin(ComposerPlugin.class);
    }}
