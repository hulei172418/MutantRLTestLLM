package org.graph;

import soot.*;
import soot.toolkits.graph.*;
import soot.jimple.*;
import soot.util.dot.DotGraph;

import java.io.*;
import java.util.*;

import org.utils.MethodSignature;

public class CFGVisualizer {
    private String suffix = "graph";
    private String className = null;
    private String methodName = null;
    private String outputDir = null;

    public CFGVisualizer(String className, String methodName, String outputDir) {
        this.className = className;
        this.methodName = methodName;
        this.outputDir = outputDir + "/" + suffix;
    }

    public void visualize() {
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
            UnitGraph cfg = new BriefUnitGraph(body);

            generateTextCFG(cfg);
            generateDotCFG(cfg);
        }
    }

    private void generateTextCFG(UnitGraph cfg) {
        if (methodName.equals("<init>")) {
            methodName = "init";
        }

        String fileName = outputDir + "/CFG.txt";
        try (PrintWriter out = new PrintWriter(new FileWriter(fileName))) {
            Set<String> processedEdges = new HashSet<>();

            if (!cfg.getHeads().isEmpty()) {
                Unit firstUnit = cfg.getHeads().get(0);
                out.println("[start] -> [" + firstUnit + "]");
            }

            for (Unit unit : cfg) {
                List<Unit> successors = cfg.getSuccsOf(unit);

                if (!successors.isEmpty()) {
                    for (Unit succ : successors) {
                        String edgeKey = unit + "->" + succ;
                        if (!processedEdges.contains(edgeKey)) {
                            String edgeLabel = "";
                            if (unit instanceof IfStmt) {
                                IfStmt ifStmt = (IfStmt) unit;
                                Stmt targetStmt = (Stmt) ifStmt.getTarget();

                                edgeLabel = (succ == targetStmt) ? " (true)" : " (false)";
                            } else if (unit instanceof GotoStmt) {
                                edgeLabel = " (goto)";
                            }

                            out.println("[" + unit + "] -> [" + succ + "]" + edgeLabel);
                            processedEdges.add(edgeKey);
                        }
                    }
                } else {
                    out.println("[" + unit + "] -> [end]");
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void generateDotCFG(UnitGraph cfg) {
        DotGraph dotGraph = new DotGraph("CFG_" + className + "_" + methodName);

        for (Unit unit : cfg) {
            dotGraph.drawNode(unit.toString());
        }

        for (Unit unit : cfg) {
            for (Unit succ : cfg.getSuccsOf(unit)) {
                String edgeLabel = "";
                if (unit instanceof IfStmt) {
                    IfStmt ifStmt = (IfStmt) unit;
                    Stmt targetStmt = (Stmt) ifStmt.getTarget();
                    edgeLabel = (succ == targetStmt) ? "true" : "false";
                }
                dotGraph.drawEdge(unit.toString(), succ.toString()).setLabel(edgeLabel);
            }
        }

        if (methodName.equals("<init>")) {
            methodName = "init";
        }
        String fileName = outputDir + "/CFG.dot";
        dotGraph.plot(fileName);
    }
}
