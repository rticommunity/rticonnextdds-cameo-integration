package com.rti.connext.cameo.dds;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class QosXmlFile {

    private final List<Element> libraries;
    private final List<String> profileNames;

    private QosXmlFile(List<Element> libraries, List<String> profileNames) {
        this.libraries = libraries;
        this.profileNames = Collections.unmodifiableList(profileNames);
    }

    public static QosXmlFile read(File file) throws Exception {
        Document document = newDocumentBuilder().parse(file);
        Element root = requireDdsRoot(document);
        List<Element> libraries = new ArrayList<>();
        List<String> profiles = new ArrayList<>();
        Set<String> libraryNames = new HashSet<>();
        Set<String> qualifiedNames = new HashSet<>();
        for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (!(child instanceof Element)) {
                continue;
            }
            Element element = (Element) child;
            if ("include".equals(element.getTagName())) {
                throw new IllegalArgumentException("Use a self-contained QoS XML file without external includes.");
            }
            if (!"qos_library".equals(element.getTagName())) {
                continue;
            }
            String libraryName = requireName(element);
            if (!libraryNames.add(libraryName)) {
                throw new IllegalArgumentException("Duplicate QoS library: " + libraryName);
            }
            libraries.add(element);
            for (Node profileNode = element.getFirstChild(); profileNode != null;
                 profileNode = profileNode.getNextSibling()) {
                if (profileNode instanceof Element && "qos_profile".equals(profileNode.getNodeName())) {
                    String name = libraryName + "::" + requireName((Element) profileNode);
                    if (!qualifiedNames.add(name)) {
                        throw new IllegalArgumentException("Duplicate QoS profile: " + name);
                    }
                    profiles.add(name);
                }
            }
        }
        if (profiles.isEmpty()) {
            throw new IllegalArgumentException("No named QoS profiles were found in the XML file.");
        }
        return new QosXmlFile(libraries, profiles);
    }

    public List<String> getProfileNames() {
        return profileNames;
    }

    public String mergeInto(String generatedXml, String profileName) throws Exception {
        if (!profileNames.contains(profileName)) {
            throw new IllegalArgumentException("QoS profile no longer exists in the XML file: " + profileName);
        }
        Document generated = newDocumentBuilder().parse(new InputSource(new StringReader(generatedXml)));
        Element root = requireDdsRoot(generated);
        for (Node child = root.getFirstChild(); child != null;) {
            Node next = child.getNextSibling();
            if (child instanceof Element && "qos_library".equals(child.getNodeName())) {
                root.removeChild(child);
            }
            child = next;
        }
        Node insertionPoint = root.getFirstChild();
        for (Element library : libraries) {
            root.insertBefore(generated.importNode(library, true), insertionPoint);
        }
        TransformerFactory factory = TransformerFactory.newDefaultInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        Transformer transformer = factory.newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        StringWriter output = new StringWriter();
        transformer.transform(new DOMSource(generated), new StreamResult(output));
        return output.toString();
    }

    private static DocumentBuilder newDocumentBuilder() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder();
    }

    private static Element requireDdsRoot(Document document) {
        Element root = document.getDocumentElement();
        if (!"dds".equals(root.getTagName()) || root.getNamespaceURI() != null) {
            throw new IllegalArgumentException("Expected an RTI QoS XML file with an unqualified <dds> root.");
        }
        return root;
    }

    private static String requireName(Element element) {
        String name = element.getAttribute("name");
        if (name.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing name on <" + element.getTagName() + ">.");
        }
        return name;
    }
}