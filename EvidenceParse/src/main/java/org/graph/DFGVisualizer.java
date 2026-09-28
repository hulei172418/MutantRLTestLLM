package org.graph;

import soot.*;
import soot.toolkits.scalar.*;
import soot.toolkits.graph.*;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;

import org.utils.MethodSignature;

import soot.util.dot.DotGraph;

public class DFGVisualizer {
    private String suffix = "graph";
    private String className = null;
    private String methodName = null;
    private String outputDir = null;

    public DFGVisualizer(String className, String methodName, String outputDir) {
        this.className = className;
        this.methodName = methodName;
        this.outputDir = outputDir + "/" + suffix;
    }

    public void analyze() {
        SootClass sootClass = Scene.v().getSootClass(className);
        SootMethod method = null;

        for (SootMethod m : sootClass.getMethods()) {
            if (!m.isConcrete() || !MethodSignature.matchesSootLikeSignature(m, methodName))
                continue;
            method = m;
            break;
        }
        if (method == null) {
            System.err.println("Method " + methodName + " not found in class " + className);
            return;
        }

        if (method.isConcrete()) {
            Body body = method.retrieveActiveBody();

            generateTextDFG(body);
            generateDotDFG(body);
        }
    }

    private void generateTextDFG(Body body) {
        if (methodName.equals("<init>")) {
            methodName = "init";
        }

        File demo_path = new File(outputDir);
        if (!demo_path.exists()) {
            boolean created = demo_path.mkdirs();
            if (!created) {
                System.err.println("Failed to create directory: " + demo_path.getAbsolutePath());
            }
        }

        String fileName = outputDir + "/DFG.txt";
        try (PrintWriter out = new PrintWriter(new FileWriter(fileName))) {
            Map<Value, Set<Unit>> defSites = new HashMap<>();
            Map<Value, Set<Unit>> useSites = new HashMap<>();

            Map<Unit, Integer> unitToIndex = new HashMap<>();
            int index = 0;
            for (Unit unit : body.getUnits()) {
                unitToIndex.put(unit, index++);

                for (ValueBox defBox : unit.getDefBoxes()) {
                    Value value = defBox.getValue();
                    defSites.computeIfAbsent(value, k -> new HashSet<>()).add(unit);
                }

                for (ValueBox useBox : unit.getUseBoxes()) {
                    Value value = useBox.getValue();
                    useSites.computeIfAbsent(value, k -> new HashSet<>()).add(unit);
                }
            }

            out.println("=== Data Dependencies ===");
            for (Unit unit : body.getUnits()) {
                out.println("\nStatement " + unitToIndex.get(unit) + ": " + unit);

                for (ValueBox useBox : unit.getUseBoxes()) {
                    Value value = useBox.getValue();
                    if (defSites.containsKey(value)) {
                        out.println("  Uses: " + value);
                        for (Unit defUnit : defSites.get(value)) {
                            out.println("    Defined at: " + unitToIndex.get(defUnit) + ": " + defUnit);
                        }
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void generateDotDFG(Body body) {
        DotGraph dotGraph = new DotGraph("DFG_" + className + "_" + methodName);
        dotGraph.setNodeShape("box");
        dotGraph.setGraphLabel("Data Flow Graph for " + className + "." + methodName);

        Map<Value, Set<Unit>> defSites = new HashMap<>();
        Map<Unit, Integer> unitToIndex = new HashMap<>();

        int index = 0;
        for (Unit unit : body.getUnits()) {
            unitToIndex.put(unit, index++);

            for (ValueBox defBox : unit.getDefBoxes()) {
                Value value = defBox.getValue();
                defSites.computeIfAbsent(value, k -> new HashSet<>()).add(unit);
            }
        }

        for (Unit unit : body.getUnits()) {
            String nodeLabel = unitToIndex.get(unit) + ": " + unit.toString();
            dotGraph.drawNode(nodeLabel).setLabel(nodeLabel);
        }

        for (Unit unit : body.getUnits()) {
            String targetLabel = unitToIndex.get(unit) + ": " + unit.toString();

            for (ValueBox useBox : unit.getUseBoxes()) {
                Value value = useBox.getValue();
                if (defSites.containsKey(value)) {
                    for (Unit defUnit : defSites.get(value)) {
                        String sourceLabel = unitToIndex.get(defUnit) + ": " + defUnit.toString();
                        dotGraph.drawEdge(sourceLabel, targetLabel).setLabel(value.toString());
                    }
                }
            }
        }

        if (methodName.equals("<init>")) {
            methodName = "init";
        }
        String fileName = outputDir + "/DFG.dot";
        dotGraph.plot(fileName);
    }

    private void printDataFlowInfo(Body body, UnitGraph cfg) {
        System.out.println("\n=== Data Flow Analysis Details ===");

        SimpleLiveLocals liveLocals = new SimpleLiveLocals(cfg);

        Map<Unit, Integer> unitToIndex = new HashMap<>();
        int index = 0;
        for (Unit unit : body.getUnits()) {
            unitToIndex.put(unit, index++);
        }

        for (Unit unit : body.getUnits()) {
            System.out.println("\nStatement " + unitToIndex.get(unit) + ": " + unit);

            System.out.println("Defined variables:");
            for (ValueBox defBox : unit.getDefBoxes()) {
                System.out.println("  " + defBox.getValue());
            }

            System.out.println("Used variables:");
            for (ValueBox useBox : unit.getUseBoxes()) {
                System.out.println("  " + useBox.getValue());
            }

            System.out.println("Live variables before:");
            for (Local local : liveLocals.getLiveLocalsBefore(unit)) {
                System.out.println("  " + local);
            }
        }
    }
}
