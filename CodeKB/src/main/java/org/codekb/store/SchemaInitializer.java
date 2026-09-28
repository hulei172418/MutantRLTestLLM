package org.codekb.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

public final class SchemaInitializer {
    public void initialize(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS projects (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "name TEXT NOT NULL UNIQUE" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS modules (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "project_id INTEGER NOT NULL," +
                    "module_name TEXT NOT NULL," +
                    "module_root TEXT NOT NULL," +
                    "build_system TEXT," +
                    "UNIQUE(project_id, module_root)," +
                    "FOREIGN KEY(project_id) REFERENCES projects(id)" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS source_roots (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "project_id INTEGER NOT NULL," +
                    "module_id INTEGER," +
                    "root_path TEXT NOT NULL," +
                    "root_kind TEXT NOT NULL," +
                    "UNIQUE(project_id, root_path)," +
                    "FOREIGN KEY(project_id) REFERENCES projects(id)," +
                    "FOREIGN KEY(module_id) REFERENCES modules(id)" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS program_variants (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "project_id INTEGER NOT NULL," +
                    "variant_kind TEXT NOT NULL," +
                    "variant_name TEXT NOT NULL," +
                    "source_path TEXT," +
                    "UNIQUE(project_id, variant_kind, variant_name)," +
                    "FOREIGN KEY(project_id) REFERENCES projects(id)" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS mutants (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "project_id INTEGER NOT NULL," +
                    "mutant_id TEXT NOT NULL," +
                    "operator TEXT," +
                    "line_number INTEGER," +
                    "method_name TEXT," +
                    "class_name TEXT," +
                    "class_f TEXT," +
                    "package_name TEXT," +
                    "mutation_statement TEXT," +
                    "UNIQUE(project_id, mutant_id)," +
                    "FOREIGN KEY(project_id) REFERENCES projects(id)" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS mutant_artifacts (" +
                    "mutant_row_id INTEGER PRIMARY KEY," +
                    "file_path TEXT," +
                    "original_graph_path TEXT," +
                    "mutant_graph_path TEXT," +
                    "is_killed TEXT," +
                    "FOREIGN KEY(mutant_row_id) REFERENCES mutants(id)" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS files (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "variant_id INTEGER NOT NULL," +
                    "absolute_path TEXT NOT NULL," +
                    "relative_path TEXT," +
                    "package_name TEXT," +
                    "UNIQUE(variant_id, absolute_path)," +
                    "FOREIGN KEY(variant_id) REFERENCES program_variants(id)" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS types (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "file_id INTEGER NOT NULL," +
                    "qualified_name TEXT NOT NULL," +
                    "simple_name TEXT NOT NULL," +
                    "kind TEXT," +
                    "super_class TEXT," +
                    "visibility TEXT NOT NULL DEFAULT 'package-private'," +
                    "is_static INTEGER NOT NULL DEFAULT 0," +
                    "is_final INTEGER NOT NULL DEFAULT 0," +
                    "is_abstract INTEGER NOT NULL DEFAULT 0," +
                    "UNIQUE(file_id, qualified_name)," +
                    "FOREIGN KEY(file_id) REFERENCES files(id)" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS methods (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "type_id INTEGER NOT NULL," +
                    "name TEXT NOT NULL," +
                    "signature TEXT NOT NULL," +
                    "return_type TEXT," +
                    "is_constructor INTEGER NOT NULL DEFAULT 0," +
                    "visibility TEXT NOT NULL DEFAULT 'package-private'," +
                    "is_static INTEGER NOT NULL DEFAULT 0," +
                    "is_final INTEGER NOT NULL DEFAULT 0," +
                    "is_abstract INTEGER NOT NULL DEFAULT 0," +
                    "is_public INTEGER NOT NULL DEFAULT 0," +
                    "begin_line INTEGER," +
                    "end_line INTEGER," +
                    "UNIQUE(type_id, signature)," +
                    "FOREIGN KEY(type_id) REFERENCES types(id)" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS method_parameters (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "method_id INTEGER NOT NULL," +
                    "position INTEGER NOT NULL," +
                    "name TEXT NOT NULL," +
                    "param_type TEXT NOT NULL," +
                    "is_varargs INTEGER NOT NULL DEFAULT 0," +
                    "UNIQUE(method_id, position)," +
                    "FOREIGN KEY(method_id) REFERENCES methods(id)" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS method_throws (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "method_id INTEGER NOT NULL," +
                    "position INTEGER NOT NULL," +
                    "exception_type TEXT NOT NULL," +
                    "UNIQUE(method_id, position, exception_type)," +
                    "FOREIGN KEY(method_id) REFERENCES methods(id)" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS fields (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "type_id INTEGER NOT NULL," +
                    "name TEXT NOT NULL," +
                    "field_type TEXT," +
                    "visibility TEXT NOT NULL DEFAULT 'package-private'," +
                    "is_static INTEGER NOT NULL DEFAULT 0," +
                    "is_final INTEGER NOT NULL DEFAULT 0," +
                    "is_public INTEGER NOT NULL DEFAULT 0," +
                    "UNIQUE(type_id, name)," +
                    "FOREIGN KEY(type_id) REFERENCES types(id)" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS type_hierarchy (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "child_type_id INTEGER NOT NULL," +
                    "parent_type_id INTEGER," +
                    "parent_qualified_name TEXT NOT NULL," +
                    "relation_kind TEXT NOT NULL," +
                    "distance INTEGER NOT NULL DEFAULT 1," +
                    "UNIQUE(child_type_id, parent_qualified_name, relation_kind)," +
                    "FOREIGN KEY(child_type_id) REFERENCES types(id)," +
                    "FOREIGN KEY(parent_type_id) REFERENCES types(id)" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS method_calls (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "caller_method_id INTEGER NOT NULL," +
                    "callee_method_id INTEGER," +
                    "callee_owner TEXT," +
                    "callee_method_name TEXT," +
                    "callee_signature TEXT," +
                    "line_number INTEGER," +
                    "FOREIGN KEY(caller_method_id) REFERENCES methods(id)," +
                    "FOREIGN KEY(callee_method_id) REFERENCES methods(id)" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS field_accesses (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "method_id INTEGER NOT NULL," +
                    "field_id INTEGER," +
                    "owner_type TEXT," +
                    "field_name TEXT," +
                    "access_kind TEXT NOT NULL," +
                    "line_number INTEGER," +
                    "FOREIGN KEY(method_id) REFERENCES methods(id)," +
                    "FOREIGN KEY(field_id) REFERENCES fields(id)" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS import_facts (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "import_value TEXT NOT NULL," +
                    "source_kind TEXT NOT NULL," +
                    "UNIQUE(import_value, source_kind)" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS import_usage_links (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "file_id INTEGER NOT NULL," +
                    "method_id INTEGER," +
                    "import_fact_id INTEGER NOT NULL," +
                    "usage_context TEXT," +
                    "priority INTEGER NOT NULL DEFAULT 0," +
                    "FOREIGN KEY(file_id) REFERENCES files(id)," +
                    "FOREIGN KEY(method_id) REFERENCES methods(id)," +
                    "FOREIGN KEY(import_fact_id) REFERENCES import_facts(id)" +
                ")"
            );
            stmt.executeUpdate("DROP VIEW IF EXISTS candidate_imports");
            stmt.executeUpdate(
                "CREATE VIEW candidate_imports AS " +
                    "SELECT l.id AS id, l.file_id AS file_id, l.method_id AS method_id, " +
                    "f.import_value AS import_value, f.source_kind AS source_kind, " +
                    "l.usage_context AS usage_context, l.priority AS priority " +
                    "FROM import_usage_links l JOIN import_facts f ON l.import_fact_id = f.id"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS mutant_file_links (" +
                    "mutant_row_id INTEGER NOT NULL," +
                    "original_file_id INTEGER," +
                    "mutant_file_id INTEGER," +
                    "PRIMARY KEY(mutant_row_id)," +
                    "FOREIGN KEY(mutant_row_id) REFERENCES mutants(id)," +
                    "FOREIGN KEY(original_file_id) REFERENCES files(id)," +
                    "FOREIGN KEY(mutant_file_id) REFERENCES files(id)" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS mutant_method_links (" +
                    "mutant_row_id INTEGER NOT NULL," +
                    "original_method_id INTEGER," +
                    "mutant_method_id INTEGER," +
                    "PRIMARY KEY(mutant_row_id)," +
                    "FOREIGN KEY(mutant_row_id) REFERENCES mutants(id)," +
                    "FOREIGN KEY(original_method_id) REFERENCES methods(id)," +
                    "FOREIGN KEY(mutant_method_id) REFERENCES methods(id)" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS entry_candidates (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "mutant_row_id INTEGER NOT NULL," +
                    "method_id INTEGER NOT NULL," +
                    "entry_kind TEXT NOT NULL," +
                    "access_level_score REAL NOT NULL DEFAULT 0.0," +
                    "returns_observable_value INTEGER NOT NULL DEFAULT 0," +
                    "is_preferred_entry INTEGER NOT NULL DEFAULT 0," +
                    "priority REAL NOT NULL DEFAULT 0.0," +
                    "reason_summary TEXT," +
                    "UNIQUE(mutant_row_id, method_id, entry_kind)," +
                    "FOREIGN KEY(mutant_row_id) REFERENCES mutants(id)," +
                    "FOREIGN KEY(method_id) REFERENCES methods(id)" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS observable_candidates (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "mutant_row_id INTEGER NOT NULL," +
                    "method_id INTEGER," +
                    "observable_kind TEXT NOT NULL," +
                    "expression TEXT," +
                    "priority REAL NOT NULL DEFAULT 0.0," +
                    "is_primary INTEGER NOT NULL DEFAULT 0," +
                    "reason_summary TEXT," +
                    "UNIQUE(mutant_row_id, method_id, observable_kind, expression)," +
                    "FOREIGN KEY(mutant_row_id) REFERENCES mutants(id)," +
                    "FOREIGN KEY(method_id) REFERENCES methods(id)" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS field_observer_links (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "mutant_row_id INTEGER NOT NULL," +
                    "field_id INTEGER NOT NULL," +
                    "observer_method_id INTEGER NOT NULL," +
                    "observer_kind TEXT NOT NULL," +
                    "distance INTEGER NOT NULL DEFAULT 1," +
                    "priority REAL NOT NULL DEFAULT 0.0," +
                    "reason_summary TEXT," +
                    "UNIQUE(mutant_row_id, field_id, observer_method_id, observer_kind)," +
                    "FOREIGN KEY(mutant_row_id) REFERENCES mutants(id)," +
                    "FOREIGN KEY(field_id) REFERENCES fields(id)," +
                    "FOREIGN KEY(observer_method_id) REFERENCES methods(id)" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS mutant_witness_candidates (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "mutant_row_id INTEGER NOT NULL," +
                    "entry_method_id INTEGER," +
                    "observable_method_id INTEGER," +
                    "witness_rank INTEGER NOT NULL DEFAULT 0," +
                    "receiver_setup_json TEXT," +
                    "argument_setup_json TEXT," +
                    "predicate_chain_json TEXT," +
                    "expected_original_outcome TEXT," +
                    "expected_mutant_outcome TEXT," +
                    "outcome_kind TEXT," +
                    "assertion_sketch TEXT," +
                    "reason_summary TEXT," +
                    "UNIQUE(mutant_row_id, witness_rank)," +
                    "FOREIGN KEY(mutant_row_id) REFERENCES mutants(id)," +
                    "FOREIGN KEY(entry_method_id) REFERENCES methods(id)," +
                    "FOREIGN KEY(observable_method_id) REFERENCES methods(id)" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS equivalent_suspicions (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "mutant_id INTEGER NOT NULL UNIQUE," +
                    "suspicion INTEGER NOT NULL DEFAULT 0," +
                    "reason TEXT," +
                    "confidence REAL NOT NULL DEFAULT 0.0," +
                    "FOREIGN KEY(mutant_id) REFERENCES mutants(id)" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS kb_feedback_events (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "mutant_row_id INTEGER," +
                    "mutant_id TEXT NOT NULL," +
                    "failure_stage TEXT NOT NULL," +
                    "root_cause_type TEXT NOT NULL," +
                    "primary_fix_target TEXT NOT NULL," +
                    "secondary_fix_target TEXT," +
                    "symptom TEXT," +
                    "auto_repairable INTEGER NOT NULL DEFAULT 0," +
                    "attribution_why TEXT," +
                    "signal_ledger_json TEXT," +
                    "confidence REAL NOT NULL DEFAULT 0.0," +
                    "evidence_action TEXT," +
                    "failure_reason TEXT," +
                    "source_run_id TEXT," +
                    "created_at TEXT NOT NULL," +
                    "FOREIGN KEY(mutant_row_id) REFERENCES mutants(id)" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS kb_feedback_candidates (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "feedback_event_id INTEGER NOT NULL," +
                    "candidate_kind TEXT NOT NULL," +
                    "candidate_value TEXT NOT NULL," +
                    "source_kind TEXT," +
                    "usage_context TEXT," +
                    "priority INTEGER NOT NULL DEFAULT 0," +
                    "scope_type TEXT," +
                    "scope_key TEXT," +
                    "support_count INTEGER NOT NULL DEFAULT 1," +
                    "accepted INTEGER NOT NULL DEFAULT 0," +
                    "accepted_at TEXT," +
                    "FOREIGN KEY(feedback_event_id) REFERENCES kb_feedback_events(id)" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS schema_comments (" +
                    "table_name TEXT NOT NULL," +
                    "column_name TEXT NOT NULL," +
                    "comment_text TEXT NOT NULL," +
                    "PRIMARY KEY(table_name, column_name)" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_mutants_project ON mutants(project_id)"
            );
            stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_mutants_class_method ON mutants(class_name, method_name)"
            );
            stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_files_variant ON files(variant_id)"
            );
            stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_modules_project ON modules(project_id)"
            );
            stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_source_roots_project ON source_roots(project_id)"
            );
            stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_methods_type ON methods(type_id)"
            );
            stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_entry_candidates_mutant ON entry_candidates(mutant_row_id)"
            );
            stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_observable_candidates_mutant ON observable_candidates(mutant_row_id)"
            );
            stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_field_observer_links_mutant ON field_observer_links(mutant_row_id)"
            );
            stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_mutant_witness_candidates_mutant ON mutant_witness_candidates(mutant_row_id)"
            );
            stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_method_parameters_method ON method_parameters(method_id)"
            );
            stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_method_throws_method ON method_throws(method_id)"
            );
            stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_type_hierarchy_child ON type_hierarchy(child_type_id)"
            );
            stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_type_hierarchy_parent_name ON type_hierarchy(parent_qualified_name)"
            );
            stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_calls_caller ON method_calls(caller_method_id)"
            );
            stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_calls_callee_method ON method_calls(callee_method_id)"
            );
            stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_field_accesses_method ON field_accesses(method_id)"
            );
            stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_field_accesses_field ON field_accesses(field_id)"
            );
            stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_import_usage_links_file ON import_usage_links(file_id)"
            );
            stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_import_usage_links_method ON import_usage_links(method_id)"
            );
            stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_import_usage_links_fact ON import_usage_links(import_fact_id)"
            );
            stmt.executeUpdate(
                "CREATE UNIQUE INDEX IF NOT EXISTS uq_import_usage_links_file_scope " +
                    "ON import_usage_links(file_id, import_fact_id) WHERE method_id IS NULL"
            );
            stmt.executeUpdate(
                "CREATE UNIQUE INDEX IF NOT EXISTS uq_import_usage_links_method_scope " +
                    "ON import_usage_links(method_id, import_fact_id) WHERE method_id IS NOT NULL"
            );
            stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_kb_feedback_events_mutant ON kb_feedback_events(mutant_id)"
            );
            stmt.executeUpdate(
                "CREATE UNIQUE INDEX IF NOT EXISTS uq_kb_feedback_events_dedupe ON kb_feedback_events(" +
                    "mutant_id, failure_stage, root_cause_type, primary_fix_target, secondary_fix_target, " +
                    "symptom, auto_repairable, attribution_why, signal_ledger_json, evidence_action, failure_reason, source_run_id" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_kb_feedback_events_stage ON kb_feedback_events(failure_stage, root_cause_type)"
            );
            stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_kb_feedback_candidates_event ON kb_feedback_candidates(feedback_event_id)"
            );
            stmt.executeUpdate(
                "CREATE UNIQUE INDEX IF NOT EXISTS uq_kb_feedback_candidates_dedupe ON kb_feedback_candidates(" +
                    "feedback_event_id, candidate_kind, candidate_value, source_kind, usage_context, scope_type, scope_key" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_kb_feedback_candidates_kind ON kb_feedback_candidates(candidate_kind, accepted)"
            );
        }
        ensureColumn(connection, "types", "visibility", "TEXT NOT NULL DEFAULT 'package-private'");
        ensureColumn(connection, "types", "is_static", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn(connection, "types", "is_final", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn(connection, "types", "is_abstract", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn(connection, "methods", "visibility", "TEXT NOT NULL DEFAULT 'package-private'");
        ensureColumn(connection, "methods", "is_static", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn(connection, "methods", "is_final", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn(connection, "methods", "is_abstract", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn(connection, "fields", "visibility", "TEXT NOT NULL DEFAULT 'package-private'");
        ensureColumn(connection, "fields", "is_final", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn(connection, "kb_feedback_events", "symptom", "TEXT");
        ensureColumn(connection, "kb_feedback_events", "auto_repairable", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn(connection, "kb_feedback_events", "attribution_why", "TEXT");
        ensureColumn(connection, "kb_feedback_events", "signal_ledger_json", "TEXT");
        seedComments(connection);
    }

    private void seedComments(Connection connection) throws SQLException {
        insertComment(connection, "projects", "id", "项目主键。");
        insertComment(connection, "projects", "name", "项目名称，来自 Excel 的 project 字段。");

        insertComment(connection, "program_variants", "id", "程序变体主键。");
        insertComment(connection, "program_variants", "project_id", "所属项目 ID。");
        insertComment(connection, "program_variants", "variant_kind", "变体类型，例如 ORIGINAL 或 MUTANT。");
        insertComment(connection, "program_variants", "variant_name", "变体名称，original 或具体 mutant_id。");
        insertComment(connection, "program_variants", "source_path", "该变体对应的源码入口路径。");

        insertComment(connection, "mutants", "id", "变异体行主键。");
        insertComment(connection, "mutants", "project_id", "所属项目 ID。");
        insertComment(connection, "mutants", "mutant_id", "变异体唯一标识，通常取自 mutant_graph_path。");
        insertComment(connection, "mutants", "operator", "变异算子，例如 COD_1、COR_4。");
        insertComment(connection, "mutants", "line_number", "变异发生的源代码行号。");
        insertComment(connection, "mutants", "method_name", "Excel 中 method 字段。");
        insertComment(connection, "mutants", "class_name", "简单类名，常用于按行号回绑变异方法。");
        insertComment(connection, "mutants", "class_f", "Excel 中 class_f 全限定类名字段。");
        insertComment(connection, "mutants", "package_name", "Excel 中 package 字段。");
        insertComment(connection, "mutants", "mutation_statement", "Excel 中 mutation_statement 字段。");

        insertComment(connection, "mutant_artifacts", "mutant_row_id", "关联 mutants.id。");
        insertComment(connection, "mutant_artifacts", "file_path", "Excel 中 file_path，原始记录路径。");
        insertComment(connection, "mutant_artifacts", "original_graph_path", "原程序证据图路径。");
        insertComment(connection, "mutant_artifacts", "mutant_graph_path", "变异体证据图路径。");
        insertComment(connection, "mutant_artifacts", "is_killed", "Excel 中 is_kille 原始字段值。");

        insertComment(connection, "files", "id", "源码文件主键。");
        insertComment(connection, "files", "variant_id", "所属 program_variants.id。");
        insertComment(connection, "files", "absolute_path", "源码绝对路径。");
        insertComment(connection, "files", "relative_path", "轻量相对路径或文件名。");
        insertComment(connection, "files", "package_name", "文件声明的 package 名。");

        insertComment(connection, "types", "id", "类型主键。");
        insertComment(connection, "types", "file_id", "所属源码文件 ID。");
        insertComment(connection, "types", "qualified_name", "全限定类型名。");
        insertComment(connection, "types", "simple_name", "简单类型名。");
        insertComment(connection, "types", "kind", "类型种类，例如 class 或 interface。");
        insertComment(connection, "types", "super_class", "父类全限定名。");

        insertComment(connection, "methods", "id", "方法主键。");
        insertComment(connection, "methods", "type_id", "所属类型 ID。");
        insertComment(connection, "methods", "name", "方法名或构造器名。");
        insertComment(connection, "methods", "signature", "方法签名，作为类型内唯一键。");
        insertComment(connection, "methods", "return_type", "返回值类型。");
        insertComment(connection, "methods", "is_constructor", "是否为构造器。");
        insertComment(connection, "methods", "is_public", "是否为 public 方法。");
        insertComment(connection, "methods", "begin_line", "方法起始行。");
        insertComment(connection, "methods", "end_line", "方法结束行。");

        insertComment(connection, "fields", "id", "字段主键。");
        insertComment(connection, "fields", "type_id", "所属类型 ID。");
        insertComment(connection, "fields", "name", "字段名。");
        insertComment(connection, "fields", "field_type", "字段类型。");
        insertComment(connection, "fields", "is_static", "是否为 static 字段。");
        insertComment(connection, "fields", "is_public", "是否为 public 字段。");

        insertComment(connection, "method_calls", "id", "方法调用关系主键。");
        insertComment(connection, "method_calls", "caller_method_id", "调用方方法 ID。");
        insertComment(connection, "method_calls", "callee_method_id", "被调用方法 ID；可解析时优先使用。");
        insertComment(connection, "method_calls", "callee_owner", "被调用方所属类型文本，仅在未解析到 callee_method_id 时保留。");
        insertComment(connection, "method_calls", "callee_method_name", "被调用方法名文本，仅在未解析到 callee_method_id 时保留。");
        insertComment(connection, "method_calls", "callee_signature", "被调用方法签名文本，仅在未解析到 callee_method_id 时保留。");
        insertComment(connection, "method_calls", "line_number", "调用发生行号。");

        insertComment(connection, "field_accesses", "id", "字段访问关系主键。");
        insertComment(connection, "field_accesses", "method_id", "访问发生所在方法 ID。");
        insertComment(connection, "field_accesses", "field_id", "被访问字段 ID；可解析时优先使用。");
        insertComment(connection, "field_accesses", "owner_type", "字段所属类型文本，仅在未解析到 field_id 时保留。");
        insertComment(connection, "field_accesses", "field_name", "字段名文本，仅在未解析到 field_id 时保留。");
        insertComment(connection, "field_accesses", "access_kind", "访问类型，例如 READ 或 WRITE。");
        insertComment(connection, "field_accesses", "line_number", "访问发生行号。");

        insertComment(connection, "import_facts", "id", "Import 事实主键。");
        insertComment(connection, "import_facts", "import_value", "唯一 import 文本，通常是全限定类型名或显式 import 语句。");
        insertComment(connection, "import_facts", "source_kind", "Import 来源类型，例如 SOURCE_IMPORT、PARAMETER_TYPE、RETURN_TYPE。");

        insertComment(connection, "import_usage_links", "id", "Import 使用关系主键。");
        insertComment(connection, "import_usage_links", "file_id", "该 import 使用发生在哪个源文件。");
        insertComment(connection, "import_usage_links", "method_id", "该 import 使用发生在哪个方法；为空表示文件级显式 import。");
        insertComment(connection, "import_usage_links", "import_fact_id", "关联 import_facts.id。");
        insertComment(connection, "import_usage_links", "usage_context", "Import 的使用上下文，如参数名、构造表达式或显式 import 说明。");
        insertComment(connection, "import_usage_links", "priority", "Import 使用优先级；数值越大表示对测试生成越关键。");

        insertComment(connection, "mutant_file_links", "mutant_row_id", "关联 mutants.id。");
        insertComment(connection, "mutant_file_links", "original_file_id", "原程序源码文件 ID。");
        insertComment(connection, "mutant_file_links", "mutant_file_id", "变异体源码文件 ID。");

        insertComment(connection, "mutant_method_links", "mutant_row_id", "关联 mutants.id。");
        insertComment(connection, "mutant_method_links", "original_method_id", "原程序变异方法 ID。");
        insertComment(connection, "mutant_method_links", "mutant_method_id", "变异体变异方法 ID。");

        insertComment(connection, "equivalent_suspicions", "id", "等价怀疑记录主键。");
        insertComment(connection, "equivalent_suspicions", "mutant_id", "关联 mutants.id。");
        insertComment(connection, "equivalent_suspicions", "suspicion", "是否怀疑等价，0/1。");
        insertComment(connection, "equivalent_suspicions", "reason", "等价怀疑原因说明。");
        insertComment(connection, "equivalent_suspicions", "confidence", "等价怀疑置信度。");

        insertComment(connection, "schema_comments", "table_name", "被注释表名。");
        insertComment(connection, "schema_comments", "column_name", "被注释列名。");
        insertComment(connection, "schema_comments", "comment_text", "列注释正文。");
    }

    private void insertComment(Connection connection, String tableName, String columnName, String commentText) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
            "INSERT INTO schema_comments(table_name, column_name, comment_text) VALUES (?, ?, ?) " +
                "ON CONFLICT(table_name, column_name) DO UPDATE SET comment_text = excluded.comment_text")) {
            stmt.setString(1, tableName);
            stmt.setString(2, columnName);
            stmt.setString(3, commentText);
            stmt.executeUpdate();
        }
    }

    private void ensureColumn(Connection connection, String tableName, String columnName, String definition) throws SQLException {
        if (columnExists(connection, tableName, columnName)) {
            return;
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + definition);
        }
    }

    private boolean columnExists(Connection connection, String tableName, String columnName) throws SQLException {
        try (java.sql.ResultSet rs = connection.createStatement().executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (rs.next()) {
                if (columnName.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }
}
