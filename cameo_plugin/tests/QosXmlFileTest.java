package com.rti.connext.cameo.dds;

import com.nomagic.actions.ActionsCategory;
import com.nomagic.actions.NMStateAction;
import com.rti.connext.cameo.core.ModelTopicScanner;
import com.rti.connext.cameo.core.TopicModel;
import com.rti.dds.domain.DomainParticipantFactory;
import com.rti.dds.domain.DomainParticipantFactoryQos;
import com.rti.dds.domain.DomainParticipantQos;
import com.rti.dds.infrastructure.DurabilityQosPolicyKind;
import com.rti.dds.infrastructure.ReliabilityQosPolicyKind;
import com.rti.dds.publication.DataWriterQos;
import com.rti.dds.publication.PublisherQos;
import com.rti.dds.subscription.DataReaderQos;
import com.rti.dds.subscription.SubscriberQos;
import com.rti.dds.topic.TopicQos;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.swing.Action;
import javax.swing.JMenu;
import javax.swing.SwingUtilities;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import java.io.File;
import java.io.StringReader;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

public final class QosXmlFileTest {

    private static final String QOS_XML = """
            <dds>
              <qos_library name="Base">
                <qos_profile name="Shared" base_name="BuiltinQosLib::Generic.StrictReliable">
                  <domain_participant_qos>
                    <participant_name><name>QoS XML Participant</name></participant_name>
                                        <transport_builtin><mask>MASK_NONE</mask></transport_builtin>
                  </domain_participant_qos>
                                    <publisher_qos>
                                        <publisher_name><name>testPublisher</name></publisher_name>
                                        <partition><name><element>external-partition</element></name></partition>
                                    </publisher_qos>
                                    <subscriber_qos>
                                        <subscriber_name><name>testSubscriber</name></subscriber_name>
                                        <partition><name><element>external-partition</element></name></partition>
                                    </subscriber_qos>
                  <topic_qos><deadline><period><sec>2</sec><nanosec>0</nanosec></period></deadline></topic_qos>
                  <datawriter_qos><durability><kind>TRANSIENT_LOCAL_DURABILITY_QOS</kind></durability></datawriter_qos>
                </qos_profile>
              </qos_library>
              <qos_library name="External">
                <qos_profile name="Selected" base_name="Base::Shared">
                                    <datawriter_qos>
                                        <publication_name><name>testDataWriter</name><role_name>writerRole</role_name></publication_name>
                                        <history><kind>KEEP_LAST_HISTORY_QOS</kind><depth>11</depth></history>
                                    </datawriter_qos>
                                    <datareader_qos>
                                        <subscription_name><name>testDataReader</name><role_name>readerRole</role_name></subscription_name>
                                        <history><kind>KEEP_LAST_HISTORY_QOS</kind><depth>13</depth></history>
                                    </datareader_qos>
                </qos_profile>
                <qos_profile name="Empty"/>
              </qos_library>
              <types><struct name="ShapeType"><member name="wrong" type="int32"/></struct></types>
              <domain_library name="Ignored"><domain name="Ignored" domain_id="99"/></domain_library>
              <domain_participant_library name="IgnoredParticipants"/>
            </dds>
            """;

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("cameo-qos-test-");
        try {
            verifyConnextVersion(directory);
            Path file = directory.resolve("profiles.xml");
            Files.writeString(file, QOS_XML);
            QosXmlFile qos = QosXmlFile.read(file.toFile());
            require(qos.getProfileNames().equals(List.of("Base::Shared", "External::Selected", "External::Empty")),
                    "All profiles must be selectable by qualified name");
                verifySelection(file);
            verifyProfileMenu(file, qos.getProfileNames());
            TopicModel model = model();
            String original = DdsXmlGenerator.generate(model, 217, null);
            String combined = qos.mergeInto(DdsXmlGenerator.generateWithExternalQos(model, 217, "External::Selected"),
                    "External::Selected");
            verifyComposition(original, combined);
            String modelQos = DdsXmlGenerator.generate(model, 217,
                    new ModelTopicScanner.QosProfileInfo("Model", null, 3, true));
            require(modelQos.contains("base_name=\"QosLibrary::Model\"") && modelQos.contains("<depth>3</depth>"),
                    "Existing model QoS must still be generated");
            expectFailure(() -> qos.mergeInto(original, "External::Missing"));
            expectFailure(() -> DdsXmlGenerator.generateWithExternalQos(model, 217, " "));
            for (String invalid : List.of(
                    "<dds/>",
                    "<not_dds/>",
                    "<dds><qos_library name='X'><qos_profile name='P'/><qos_profile name='P'/></qos_library></dds>",
                    "<dds><include file='other.xml'/></dds>",
                    "<!DOCTYPE dds [<!ENTITY external SYSTEM 'file:///nonexistent-cameo-qos-secret'>]><dds>&external;</dds>")) {
                Files.writeString(file, invalid);
                expectFailure(() -> QosXmlFile.read(file.toFile()));
            }
            Files.writeString(file, "<dds><qos_library name='Changed'><qos_profile name='New'/></qos_library></dds>");
            expectFailure(() -> QosXmlFile.read(file.toFile()).mergeInto(original, "External::Selected"));
            Files.delete(file);
            expectFailure(() -> QosXmlFile.read(file.toFile()));
            System.out.println("PASS: QoS selection/reset, stale-config protection, QoS-only merge, model types, entity references and invalid files");
            if (args.length > 0 && "--connext".equals(args[0])) {
                verifyWithConnext(combined, qos.mergeInto(
                        DdsXmlGenerator.generateWithExternalQos(model, 217, "External::Empty"), "External::Empty"));
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static TopicModel model() {
        TopicModel model = new TopicModel();
        TopicModel.StructType type = new TopicModel.StructType("ShapeType");
        type.fields.add(new TopicModel.Field("color", "string", null, true, 128, null, null));
        type.fields.add(new TopicModel.Field("x", "int32", null, false, null, null, null));
        model.types.put(type.name, type);
        TopicModel.Topic topic = new TopicModel.Topic("Square", type.name);
        topic.publisherBlockNames.add("ShapeApplication");
        topic.subscriberBlockNames.add("ShapeApplication");
        model.topics.put(topic.topicName, topic);
        return model;
    }

    private static void verifyConnextVersion(Path directory) throws Exception {
        Path metadata = directory.resolve("rti_versions.xml");
        for (String version : List.of("7.3.0", "7.7.0", "8.0.1")) {
            Files.writeString(metadata, "<rti><host><base_version>" + version + "</base_version></host>"
                    + "<addon><version>99.0.0</version></addon></rti>");
            require(version.equals(DdsXmlGenerator.readConnextVersion(directory.toFile())),
                    "Use the host base version, not an add-on or hard-coded version");
        }
        Files.writeString(metadata, "<rti><host><base_version>invalid</base_version></host></rti>");
        expectFailure(() -> DdsXmlGenerator.readConnextVersion(directory.toFile()));
        Files.delete(metadata);
        expectFailure(() -> DdsXmlGenerator.readConnextVersion(directory.toFile()));
    }

    private static void verifyProfileMenu(Path file, List<String> profiles) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            LoadQosXmlAction load = new LoadQosXmlAction();
                require(load.getProfileMenu().isEnabled(), "QoS menu is available before loading XML");
                require("QoS".equals(load.getProfileMenu().getValue(Action.NAME)), "One QoS menu");
                require(load.getProfileMenu().getActions().size() == 2, "Initial menu contains load and model QoS actions");
                require(load.getProfileMenu().getActions().get(0) == load, "Load XML from the profile selection menu");
                require("Use Model QoS".equals(load.getProfileMenu().getActions().get(1).getValue(Action.NAME)),
                    "Model QoS is in the same menu");
            load.populateProfileMenu(file.toFile(), profiles);
                var actions = load.getProfileMenu().getActions();
                require(actions.get(0) == load && actions.size() == 4, "Loading XML preserves menu commands");
                var libraries = actions.subList(2, actions.size());
            require(libraries.size() == 2, "One submenu per QoS library");
            require("Base".equals(libraries.get(0).getValue(Action.NAME)), "Display library name");
                ActionsCategory base = (ActionsCategory) libraries.get(0);
            ActionsCategory external = (ActionsCategory) libraries.get(1);
                NMStateAction shared = (NMStateAction) base.getActions().get(0);
                require(base.getSmallIcon() == null && external.getSmallIcon() == null,
                    "No library is marked when model QoS is selected");
            NMStateAction selected = (NMStateAction) external.getActions().get(0);
            NMStateAction empty = (NMStateAction) external.getActions().get(1);
            require("Selected".equals(selected.getValue(Action.NAME)), "Display profile name");
            selected.actionPerformed(new ActionEvent(selected, ActionEvent.ACTION_PERFORMED, "select"));
            require(DdsRuntimeConfig.isQosProfileSelected(file.toFile(), "External::Selected"), "Menu selects profile");
            require(selected.getState() && !empty.getState(), "Check the active profile only");
                require(external.getSmallIcon() != null && base.getSmallIcon() == null,
                    "Mark only the library containing the selected profile");
                verifyLibraryIcon(external);
            require(actions.get(1).isEnabled(), "Model QoS reset is enabled after profile selection");
            empty.actionPerformed(new ActionEvent(empty, ActionEvent.ACTION_PERFORMED, "select"));
            require(!selected.getState() && empty.getState(), "Changing profiles updates checkmarks");
                require(external.getSmallIcon() != null && base.getSmallIcon() == null,
                    "Switching profiles within a library keeps that library marked");
                shared.actionPerformed(new ActionEvent(shared, ActionEvent.ACTION_PERFORMED, "select"));
                require(shared.getState() && !selected.getState() && !empty.getState(),
                    "Switching libraries updates profile checkmarks");
                require(base.getSmallIcon() != null && external.getSmallIcon() == null,
                    "Switching libraries moves the library marker");
            DdsRuntimeConfig.useModelQos();
                base.updateState();
                external.updateState();
                require(base.getSmallIcon() == null && external.getSmallIcon() == null,
                    "Model QoS clears library markers independently of profile refresh order");
                shared.updateState();
            selected.updateState();
            empty.updateState();
            require(!selected.getState() && !empty.getState(), "Model QoS clears profile checkmarks");
            load.populateProfileMenu(file.toFile(), List.of("External::Empty"));
            require(load.getProfileMenu().getActions().size() == 3, "Loading another profile list replaces old library entries");
            require(load.getProfileMenu().getActions().get(0) == load, "Load XML remains available after replacement");
            require(!load.getProfileMenu().getActions().get(1).isEnabled(), "Model QoS reset is disabled after clearing selection");
        });
    }

    private static void verifyLibraryIcon(ActionsCategory library) {
        JMenu menu = new JMenu(library);
        var icon = menu.getIcon();
        require(icon != null, "The selected library icon reaches the submenu");
        BufferedImage image = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            icon.paintIcon(menu, graphics, 0, 0);
        } finally {
            graphics.dispose();
        }
        for (int row = 0; row < image.getHeight(); row++) {
            for (int column = 0; column < image.getWidth(); column++) {
                if ((image.getRGB(column, row) >>> 24) != 0) {
                    return;
                }
            }
        }
        throw new AssertionError("The selected library icon must render when its submenu is closed");
    }

    private static void verifySelection(Path file) throws Exception {
        DdsRuntimeConfig.useModelQos();
        require(!DdsRuntimeConfig.hasExternalQos(), "Model QoS is the default");
        DdsRuntimeConfig.useQosXml(file.toFile(), "External::Selected");
        require(DdsRuntimeConfig.hasExternalQos(), "External QoS selection");
        expectFailure(() -> DdsRuntimeConfig.useQosXml(file.toFile(), "External::Missing"));
        require(DdsRuntimeConfig.hasExternalQos(), "An invalid replacement must not clear the selection");
        DdsRuntimeConfig.useModelQos();
        require(!DdsRuntimeConfig.hasExternalQos(), "Return to model QoS");
        String override = System.getProperty("com.rti.connext.cameo.xmlConfig");
        try {
            System.setProperty("com.rti.connext.cameo.xmlConfig", file.toString());
            expectFailure(() -> DdsRuntimeConfig.useQosXml(file.toFile(), "External::Selected"));
            require(!DdsRuntimeConfig.hasExternalQos(), "Reject conflicting full-XML override");
        } finally {
            if (override == null) {
                System.clearProperty("com.rti.connext.cameo.xmlConfig");
            } else {
                System.setProperty("com.rti.connext.cameo.xmlConfig", override);
            }
        }
        var resolve = DDSTopicPublisher.class.getDeclaredMethod("resolveXmlUrl");
        resolve.setAccessible(true);
        DDSTopicPublisher.useGeneratedConfig(file.toFile());
        require(((String) resolve.invoke(null)).endsWith("profiles.xml"), "Generated config was set");
        DDSTopicPublisher.useGeneratedConfig(null);
        expectFailure(() -> resolve.invoke(null));
    }

    private static void verifyComposition(String original, String combined) throws Exception {
        Document originalDocument = parse(original);
        Document combinedDocument = parse(combined);
        String version = DdsXmlGenerator.readConnextVersion(new File(System.getenv("NDDSHOME")));
        for (Document document : List.of(originalDocument, combinedDocument)) {
            require(version.equals(document.getDocumentElement().getAttribute("version")), "XML version follows NDDSHOME");
            require(("https://community.rti.com/schema/" + version + "/rti_dds_profiles.xsd").equals(
                document.getDocumentElement().getAttribute("xsi:noNamespaceSchemaLocation")), "Correct DDS schema URL");
        }
        require(originalDocument.getElementsByTagName("types").item(0)
                .isEqualNode(combinedDocument.getElementsByTagName("types").item(0)), "Model types must remain identical");
        var xpath = XPathFactory.newInstance().newXPath();
        require("217".equals(xpath.evaluate("/dds/domain_library/domain/@domain_id", combinedDocument)),
                "Model domain must be retained");
        require("2".equals(xpath.evaluate("count(/dds/qos_library)", combinedDocument)), "Preserve both QoS libraries");
        require(!combined.contains("Ignored") && !combined.contains("name=\"wrong\""), "Ignore external types and topology");
        require("0".equals(xpath.evaluate("count(/dds/domain_library/domain/topic/topic_qos)", combinedDocument)),
            "Do not generate topic QoS references");
        for (String path : List.of(
                "/dds/domain_participant_library/domain_participant/domain_participant_qos",
                "/dds/domain_participant_library/domain_participant/publisher/publisher_qos",
                "/dds/domain_participant_library/domain_participant/subscriber/subscriber_qos",
                "/dds/domain_participant_library/domain_participant/publisher/data_writer/datawriter_qos",
                "/dds/domain_participant_library/domain_participant/subscriber/data_reader/datareader_qos")) {
            require("External::Selected".equals(xpath.evaluate(path + "/@base_name", combinedDocument)), path);
        }
    }

    private static void verifyWithConnext(String combined, String emptyProfile) {
        try {
            DomainParticipantFactory factory = DomainParticipantFactory.get_instance();
            DomainParticipantFactoryQos factoryQos = new DomainParticipantFactoryQos();
            factory.get_qos(factoryQos);
            factoryQos.profile.ignore_user_profile = true;
            factoryQos.profile.ignore_environment_profile = true;
            factoryQos.profile.ignore_resource_profile = true;
            factoryQos.entity_factory.autoenable_created_entities = false;
            factoryQos.profile.url_profile.clear();
            factoryQos.profile.string_profile.clear();
            factoryQos.profile.string_profile.add(combined);
            factory.set_qos(factoryQos);
            factory.reload_profiles();
            require(factory.get_typecode_from_config("ShapeType") != null, "RTI must load the model type");
            DomainParticipantQos participant = new DomainParticipantQos();
            factory.get_participant_qos_from_profile(participant, "External", "Selected");
            require("QoS XML Participant".equals(participant.participant_name.name), "Preserve external participant QoS");
            PublisherQos publisher = new PublisherQos();
            factory.get_publisher_qos_from_profile(publisher, "External", "Selected");
            require("external-partition".equals(publisher.partition.name.get(0)), "Publisher partition");
            SubscriberQos subscriber = new SubscriberQos();
            factory.get_subscriber_qos_from_profile(subscriber, "External", "Selected");
            require("external-partition".equals(subscriber.partition.name.get(0)), "Subscriber partition");
            DataWriterQos writer = new DataWriterQos();
            factory.get_datawriter_qos_from_profile(writer, "External", "Selected");
            require(writer.history.depth == 11, "Writer history depth");
            require(writer.reliability.kind.equals(ReliabilityQosPolicyKind.RELIABLE_RELIABILITY_QOS), "Built-in inheritance");
            require(writer.durability.kind.equals(DurabilityQosPolicyKind.TRANSIENT_LOCAL_DURABILITY_QOS), "Library inheritance");
            DataReaderQos reader = new DataReaderQos();
            factory.get_datareader_qos_from_profile(reader, "External", "Selected");
            require(reader.history.depth == 13, "Reader history depth");
            TopicQos topic = new TopicQos();
            factory.get_topic_qos_from_profile(topic, "External", "Selected");
            require(topic.deadline.period.sec == 2, "Topic deadline");
            var created = factory.create_participant_from_config("DomainParticipantLibrary::ShapeApplication");
            require(created != null, "Create isolated participant from generated XML");
            try {
                var createdReader = created.lookup_datareader_by_name("Subscriber::SquareReader");
                require(createdReader != null, "Reader lookup must retain generated names despite imported QoS names");
                var createdWriter = created.lookup_datawriter_by_name("Publisher::SquareWriter");
                require(createdWriter != null, "Writer lookup must retain generated names despite imported QoS names");
                createdReader.get_qos(reader);
                createdWriter.get_qos(writer);
                require(reader.history.depth == 13 && writer.history.depth == 11, "Keep imported endpoint QoS");
                require("readerRole".equals(reader.subscription_name.role_name), "Keep imported reader role");
                require("writerRole".equals(writer.publication_name.role_name), "Keep imported writer role");
            } finally {
                created.delete_contained_entities();
                factory.delete_participant(created);
            }
            factoryQos.profile.string_profile.clear();
            factoryQos.profile.string_profile.add(emptyProfile);
            factory.set_qos(factoryQos);
            factory.reload_profiles();
            factory.get_subscriber_qos_from_profile(subscriber, "External", "Empty");
            require(subscriber.partition.name.size() == 0, "Omitted QoS sections use defaults");
            System.out.println("PASS: RTI XML loading, built-in/library inheritance, partitions, endpoint/topic QoS and omitted sections");
        } finally {
            DomainParticipantFactory.finalize_instance();
        }
    }

    private static Document parse(String xml) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expectFailure(CheckedOperation operation) throws Exception {
        try {
            operation.run();
        } catch (Exception expected) {
            return;
        }
        throw new AssertionError("Expected invalid QoS configuration to be rejected");
    }

    private interface CheckedOperation {
        void run() throws Exception;
    }
}