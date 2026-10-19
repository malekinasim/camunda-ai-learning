package com.nasim.camundaai.delegation.listener;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks every BPMN file at build time: any element that uses the delegation
 * listener must declare all inputs the listener needs. Without this, a task
 * added in the modeler without its inputs would only fail at runtime, as an incident.
 */
class DelegationWiringTest {

    private static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    private static final String ZEEBE_NS = "http://camunda.org/schema/zeebe/1.0";

    @Test
    void everyTaskUsingTheListenerDeclaresItsInputs() throws Exception {
        File[] files = new File("src/main/resources").listFiles((dir, name) -> name.endsWith(".bpmn"));
        assertNotNull(files, "BPMN folder not found - run tests from the project root");

        List<String> problems = new ArrayList<>();
        int checked = 0;
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);

        for (File file : files) {
            var doc = factory.newDocumentBuilder().parse(file);
            NodeList listeners = doc.getElementsByTagNameNS(ZEEBE_NS, "executionListener");
            for (int i = 0; i < listeners.getLength(); i++) {
                Element listener = (Element) listeners.item(i);
                if (!ResolveAssigneeListener.JOB_TYPE.equals(listener.getAttribute("type"))) {
                    continue;
                }
                checked++;
                // executionListener -> executionListeners -> extensionElements -> the BPMN element
                Element task = (Element) listener.getParentNode().getParentNode().getParentNode();
                String where = file.getName() + "/" + task.getAttribute("id");

                if (!"start".equals(listener.getAttribute("eventType"))) {
                    problems.add(where + ": listener must run on 'start', before the assignee is evaluated");
                }
                if (!BPMN_NS.equals(task.getNamespaceURI()) || !"userTask".equals(task.getLocalName())) {
                    problems.add(where + ": listener is only meant for user tasks");
                }

                Set<String> declared = new HashSet<>();
                NodeList inputs = task.getElementsByTagNameNS(ZEEBE_NS, "input");
                for (int j = 0; j < inputs.getLength(); j++) {
                    declared.add(((Element) inputs.item(j)).getAttribute("target"));
                }
                for (String required : ResolveAssigneeListener.REQUIRED_INPUTS) {
                    if (!declared.contains(required)) {
                        problems.add(where + ": missing input '" + required + "'");
                    }
                }
            }
        }

        assertTrue(checked > 0, "No task uses the '" + ResolveAssigneeListener.JOB_TYPE + "' listener");
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }
}
