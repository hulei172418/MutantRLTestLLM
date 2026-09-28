# RIP-RL: Program Evidence Selection for LLM-Based Mutation Test Case Generation

This repository contains the prototype and replication package for **RIP-RL**, a method for LLM-based mutation test case generation. RIP-RL treats pre-generation program-evidence selection as a contextual bandit problem: for each target mutant, it constructs candidate evidence about mutation semantics, reach-infect-propagate conditions, callable entries, object construction, dependencies, compilation constraints, and observable assertions, then uses feedback from compilation and differential execution to adapt future evidence choices.

The implementation is organized around three core modules:

- `CodeKB/`: builds project-level knowledge from Java source code, including types, signatures, constructors, fields, imports, call relations, and dependency facts.
- `EvidenceParse/`: extracts and organizes multi-source program evidence for target mutants, producing `graph/output.json`.
- `MuTestLLM/`: implements contextual-bandit-based evidence selection, LLM-guided JUnit test generation, compilation repair, mutant execution, and experimental result collection.

The paper reports experiments on 12 Java projects. In total, 125,157 mutants were generated, and 106,130 non-equivalent mutants were used as test-generation targets. RIP-RL achieves a compile rate of 99.32%, an execution rate of 97.43%, a target kill rate of 75.03%, and a suite-level mutation score of 89.24%.

## Overview

Mutation testing evaluates whether tests can distinguish an original program from its mutants. For a generated test to kill a target mutant, it must satisfy the Reach, Infect, and Propagate conditions:

- Reach: the test executes the mutation site.
- Infect: the mutation causes an internal state difference.
- Propagate: the difference reaches an observable return value, exception, object state, or test verdict.

LLMs can generate useful tests, but a fixed prompt context is often insufficient. Different mutants may require different evidence: a private mutated method needs a public callable entry; a stateful mutation needs receiver construction and observation evidence; a relational mutation may need path and argument constraints; a compilation-prone target needs stronger dependency and API evidence.

RIP-RL therefore selects evidence adaptively before generation. It keeps canonical core evidence, ranks optional evidence under a context budget, and uses a LinUCB-style contextual bandit policy to choose among evidence configurations. Compilation, execution, target-kill results, repair cost, and failure attribution provide feedback for policy updates and regeneration.

## Main Contributions

- A structured program-evidence space for mutation-targeted LLM test generation.
- A contextual-bandit formulation for pre-generation evidence selection.
- A feedback loop that uses compilation and differential execution results to repair, regenerate, and update evidence-selection decisions.
- A large-scale evaluation on 106,130 non-equivalent Java mutants showing improved compilation, execution, and mutation-killing effectiveness over fixed-context and baseline approaches.

## Repository Structure

```text
MutantRLTestLLM/
|-- CodeKB/
|   |-- src/main/java/org/codekb/
|   `-- pom.xml
|-- EvidenceParse/
|   |-- src/main/java/org/
|   |-- README.md
|   |-- README_zh.md
|   `-- pom.xml
|-- MuTestLLM/
|   |-- src/main/java/mujava/
|   |-- src/main/resources/
|   |-- README.md
|   |-- mujava.config
|   |-- mujavaCLI.config
|   |-- libraries.json
|   `-- pom.xml
|-- pom.xml
`-- README.md
```

## Method Workflow

```text
Java subject projects
  -> CodeKB project knowledge construction
  -> MuJava mutant generation
  -> Mutant metadata Excel
  -> Structured evidence generation: graph/output.json
  -> PromptEvidence construction and evidence selection
  -> LLM-based JUnit4 test generation
  -> javac compilation and targeted repair
  -> Original/mutant differential execution
  -> Reward calculation and LinUCB policy update
  -> CSV/JSON result aggregation
```

The Excel metadata file is the central index for experiments. It connects mutant identities, source paths, evidence paths, generated tests, execution reports, and merged statistical results.

## Environment

Recommended environment:

- Java: JDK 8 is recommended for MuJava and JUnit4 compatibility.
- Build system: Maven.
- Testing framework: JUnit 4.12 and Hamcrest.
- Static analysis: Soot, JavaParser, GumTree.
- Data processing: Apache POI and Jackson.
- Optional visualization: Graphviz.
- LLM access: OpenAI-compatible Chat Completion API.
- Baselines: Randoop and EvoSuite are used only for comparison experiments.

Important configuration files:

```text
MuTestLLM/mujava.config
MuTestLLM/mujavaCLI.config
MuTestLLM/libraries.json
MuTestLLM/src/main/resources/llm.properties
```

`mujava.config` defines MuJava paths, subject-project paths, result directories, class directories, and Maven repository paths. `libraries.json` records project-specific dependencies. `llm.properties` configures the LLM provider, model, API URL, API key, runtime budget, and repair settings.

## Input Data

A typical mutant metadata Excel file contains:

| Column | Field | Meaning |
|---:|---|---|
| 0 | `operator` / `mutantName` | Mutant name, e.g., `AOIS_1` |
| 1 | `lineNo` | Mutation source line |
| 2 | `methodSignature` | Target method signature |
| 3 | `className` | Target class name |
| 4 | `classNameF` | Source-file class name |
| 5 | `mutationStatement` | Mutation statement or source-level diff |
| 6 | `packageName` / `targetClassName` | Package name or MuJava target class |
| 7 | `projectName` | Subject project name |
| 8 | `file_path` | Mutant file path or result-module path |
| 9 | `original_graph_path` | Original-side source/graph path |
| 10 | `mutant_graph_path` | Mutant-side source/graph path |
| 11 | `is_killed` | Optional historical label or reference result |

The main identity key is:

```text
projectName + targetClassName + methodSignature + mutantName
```

## Reproduction Pipeline

### 1. Generate MuJava mutants

```bash
java -cp <classpath> mujava.cmd.MutantsGenerator <args>
```

or:

```bash
java -cp <classpath> mujava.TraditionalMutantsGeneratorCLI <args>
```

Expected output:

```text
<project>/result/<targetClassName>/traditional_mutants/
```

### 2. Generate structured evidence

```bash
java -Xmx16g -cp <classpath> org.OutputJsonBatchRunner \
  --excel <mutant_metadata.xlsx> \
  --mode process \
  --workers 8 \
  --rewrite true \
  --rowStart 1 \
  --rowEnd -1
```

Expected output:

```text
<mutantDir>/graph/output.json
```

### 3. Generate and compile LLM tests

```bash
java -Xmx16g \
  -Dpath.to.mujava.config=<mujava.config> \
  -Dllm.provider=<provider> \
  -Dllm.threads=4 \
  -Dllm.force.regenerate=false \
  -cp <classpath> \
  mujava.testgenerator.LLMTestGeneratorBatch <mutant_metadata.xlsx>
```

Expected output:

```text
<resultModuleHome>/llm/src/
<resultModuleHome>/llm/classes/
<resultModuleHome>/llm/report/llm_generation_results.jsonl
```

### 4. Execute generated tests

```bash
java -Xmx16g -cp <classpath> mujava.testgenerator.LLMTestExecutor \
  <mutant_metadata.xlsx> \
  5000
```

Useful options:

```bash
-Dllm.executor.granularity=method
-Dllm.executor.quickPrimaryOnly=false
-Dllm.executor.startRow=1
-Dllm.executor.endRow=10000
```

Expected output:

```text
<resultModuleHome>/llm/execution-report/<runId>/
```

### 5. Merge LLM kill results

```bash
java -Xmx16g -cp <classpath> mujava.testgenerator.LlmMutantKillDetailCollector \
  <ProgramsRoot> \
  <mutant_metadata.xlsx> \
  <output_csv>
```

### 6. Optional baseline and diagnostics

EvoSuite is used only as a comparison baseline:

```bash
java -Xmx16g -cp <classpath> mujava.testgenerator.EvoSuiteTestGenerator
java -Xmx16g -cp <classpath> mujava.testgenerator.EvoSuiteCleaner
java -Xmx16g -cp <classpath> mujava.testgenerator.EvoSuiteMutantKillDetailCollector \
  <ProgramsRoot> \
  <mutant_metadata.xlsx> \
  <output_csv>
```

Artifact and failure-analysis tools include:

```text
mujava.testgenerator.LlmArtifactTableCounter
mujava.testgenerator.JsonlCompiledFalseToExcel
mujava.testgenerator.MissingCompiledClassExcelExporter
mujava.testgenerator.MissingFailureEvidenceCollector
mujava.testgenerator.LLMCompiledTestRestorer
```

## Main Outputs

```text
# Evidence
<mutantDir>/graph/output.json

# LLM generation
<resultModuleHome>/llm/report/llm_generation_results.jsonl
<resultModuleHome>/llm/src/
<resultModuleHome>/llm/classes/

# Execution
<resultModuleHome>/llm/execution-report/<runId>/llm_target_kill_results.csv
<resultModuleHome>/llm/execution-report/<runId>/llm_suite_kill_results.json
<resultModuleHome>/llm/execution-report/<runId>/all_method_summary.csv
<resultModuleHome>/llm/execution-report/<runId>/all_class_summary.csv

# Merged statistics
llm_mutant_kill_detail_merged.csv
evosuite_mutant_kill_detail_merged.csv
llm_artifact_statistics.csv
compiled_false.xlsx
missing_llm_class_rows.xlsx
grouped_minimal_failure_evidence_for_llm.json
```

## Metrics

| Metric | Meaning |
|---|---|
| CompR | Percentage of targets whose generated tests compile successfully |
| ExecR | Percentage of targets whose generated tests execute successfully on the original program |
| TKR | Target kill rate: whether a generated test kills its corresponding target mutant |
| MS | Suite-level mutation score over non-equivalent target mutants |
| `tEvidenceConstruct` | Evidence construction time |
| `tPromptEvidence` | PromptEvidence organization time |
| `tGeneration` | LLM generation time |
| `Repairavg` | Average number of compilation repair rounds |

## Citation

If you use this repository, please cite the RIP-RL paper and the original MuJava work.

```bibtex
@article{rip_rl_llm_mutation_test_generation,
  title   = {RIP-RL: Program Evidence Selection for LLM-Based Mutation Test Case Generation},
  author  = {Hu, Lei and Yao, Xiangjuan and Wei, Changqing and Gong, Dunwei and Sun, Baicai},
  journal = {Journal of Systems and Software},
  year    = {2026}
}
```

```bibtex
@inproceedings{ma2006mujava,
  title     = {MuJava: A mutation system for Java},
  author    = {Ma, Yu-Seung and Offutt, Jeff and Kwon, Yong-Rae},
  booktitle = {Proceedings of the 28th International Conference on Software Engineering},
  pages     = {827--830},
  year      = {2006}
}
```

## Contact

For questions about the code or replication package, please contact Lei Hu at `hulei172418@gmail.com`.
