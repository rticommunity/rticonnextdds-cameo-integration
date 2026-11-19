package com.rti.connext.cameo.dds;

import com.nomagic.actions.ActionsCategory;
import com.nomagic.actions.NMAction;
import com.nomagic.magicdraw.actions.MDAction;
import com.nomagic.magicdraw.actions.MDStateAction;

import javax.swing.Action;
import javax.swing.Icon;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.plaf.metal.MetalIconFactory;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.event.ActionEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LoadQosXmlAction extends MDAction {

    private final ActionsCategory profileMenu = new ActionsCategory("RTI_QOS_PROFILE_MENU", "QoS");
    private final List<ProfileAction> profileActions = new ArrayList<>();
    private final List<LibraryCategory> libraryActions = new ArrayList<>();
    private final UseModelQosAction useModelQosAction = new UseModelQosAction();

    public LoadQosXmlAction() {
        super("RTI_LOAD_QOS_XML", "Load QoS XML...", null, null);
        profileMenu.setNested(true);
        profileMenu.addAction(this);
        profileMenu.addAction(useModelQosAction);
    }

    public ActionsCategory getProfileMenu() {
        return profileMenu;
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Load QoS XML");
        chooser.setFileFilter(new FileNameExtensionFilter("RTI QoS XML files", "xml"));
        chooser.setAcceptAllFileFilterUsed(false);
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        try {
            List<String> profiles = QosXmlFile.read(file).getProfileNames();
            String profile = profiles.size() == 1 ? profiles.get(0) : (String) JOptionPane.showInputDialog(
                    null, "QoS profile:", "Load QoS XML", JOptionPane.QUESTION_MESSAGE,
                    null, profiles.toArray(new String[0]), profiles.get(0));
            if (profile == null) {
                return;
            }
            DdsRuntimeConfig.useQosXml(file, profile);
            populateProfileMenu(file, profiles);
            JOptionPane.showMessageDialog(null,
                    "QoS file: " + file.getName() + "\nProfile: " + profile + "\nApplies at the next simulation start."
                        + "\n\nTo select another profile, use:"
                        + "\nTools > RTI Connext DDS > QoS > Library > Profile.",
                    "Load QoS XML", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(null, "Could not load QoS XML:\n" + ex.getMessage(),
                    "Load QoS XML", JOptionPane.ERROR_MESSAGE);
        }
    }

    void populateProfileMenu(File file, List<String> profiles) {
        Map<String, LibraryCategory> libraries = new LinkedHashMap<>();
        profileActions.clear();
        libraryActions.clear();
        for (String profile : profiles) {
            int separator = profile.indexOf("::");
            String libraryName = profile.substring(0, separator);
            ActionsCategory library = libraries.computeIfAbsent(libraryName, name -> {
                LibraryCategory category = new LibraryCategory(name);
                libraryActions.add(category);
                return category;
            });
            ProfileAction action = new ProfileAction(file, profile, profile.substring(separator + 2));
            profileActions.add(action);
            library.addAction(action);
        }
        List<NMAction> actions = new ArrayList<>();
        actions.add(this);
        actions.add(useModelQosAction);
        actions.addAll(libraries.values());
        profileMenu.setActions(actions);
        profileMenu.putValue(Action.SHORT_DESCRIPTION, file.getAbsolutePath());
        updateQosState();
    }

    private void updateQosState() {
        for (ProfileAction action : profileActions) {
            action.updateState();
        }
        for (LibraryCategory library : libraryActions) {
            library.updateState();
        }
        useModelQosAction.updateState();
    }

    private static final class LibraryCategory extends ActionsCategory {
        private final Icon selectedIcon = new SelectedLibraryIcon();

        private LibraryCategory(String name) {
            super("RTI_QOS_LIBRARY_" + name, name);
            setNested(true);
        }

        @Override
        public void updateState() {
            boolean selected = getActions().stream().anyMatch(action -> action instanceof ProfileAction profileAction
                    && DdsRuntimeConfig.isQosProfileSelected(profileAction.file, profileAction.profile));
            setSmallIcon(selected ? selectedIcon : null);
        }
    }

    private static final class SelectedLibraryIcon implements Icon {
        private final JCheckBoxMenuItem marker = new JCheckBoxMenuItem();

        private Icon delegate() {
            Icon icon = UIManager.getIcon("CheckBoxMenuItem.checkIcon");
            return icon != null ? icon : MetalIconFactory.getCheckBoxMenuItemIcon();
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int left, int top) {
            marker.setSelected(true);
            marker.setEnabled(component.isEnabled());
            marker.setForeground(component.getForeground());
            marker.setBackground(component.getBackground());
            delegate().paintIcon(marker, graphics, left, top);
        }

        @Override
        public int getIconWidth() {
            return delegate().getIconWidth();
        }

        @Override
        public int getIconHeight() {
            return delegate().getIconHeight();
        }
    }

    private final class UseModelQosAction extends MDAction {
        private UseModelQosAction() {
            super("RTI_USE_MODEL_QOS", "Use Model QoS", null, null);
            updateState();
        }

        @Override
        public void actionPerformed(ActionEvent event) {
            DdsRuntimeConfig.useModelQos();
            updateQosState();
            JOptionPane.showMessageDialog(null, "Model QoS will be used at the next simulation start.",
                    "Use Model QoS", JOptionPane.INFORMATION_MESSAGE);
        }

        @Override
        public void updateState() {
            setEnabled(DdsRuntimeConfig.hasExternalQos());
        }
    }

    private final class ProfileAction extends MDStateAction {
        private final File file;
        private final String profile;

        private ProfileAction(File file, String profile, String name) {
            super("RTI_QOS_PROFILE_" + profile, name, null, null);
            this.file = file.getAbsoluteFile();
            this.profile = profile;
            putValue(Action.SHORT_DESCRIPTION, profile + " - " + this.file);
            updateState();
        }

        @Override
        public void actionPerformed(ActionEvent event) {
            try {
                DdsRuntimeConfig.useQosXml(file, profile);
                updateQosState();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(null, "Could not select QoS profile:\n" + ex.getMessage(),
                        "QoS Profile", JOptionPane.ERROR_MESSAGE);
            }
        }

        @Override
        public void updateState() {
            setState(DdsRuntimeConfig.isQosProfileSelected(file, profile));
        }
    }
}