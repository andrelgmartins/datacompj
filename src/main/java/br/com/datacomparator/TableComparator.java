package br.com.datacomparator;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

final class TableComparator {
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSxxx", Locale.ROOT);
    private final Config config;
    private final Connection source;
    private final Connection target;

    TableComparator(Config config, Connection source, Connection target) {
        this.config = config;
        this.source = source;
        this.target = target;
    }

    static final class Summary {
        private static final List<String> FIELDS = List.of(
                "tabela", "inicio", "termino", "duracao_segundos", "status", "lidas_origem",
                "lidas_destino", "comparadas", "inserts_gerados", "updates_gerados",
                "sem_alteracao", "somente_no_destino", "erro");
        private final String table;
        private String start = "";
        private String end = "";
        private String seconds = "";
        String status = "";
        long sourceRows;
        long targetRows;
        long matched;
        long inserts;
        long updates;
        long unchanged;
        long targetOnly;
        String error = "";

        Summary(String table) {
            this.table = table;
        }

        static void writeHeader(Csv csv) throws IOException {
            csv.row(FIELDS.toArray());
        }

        void writeTo(Csv csv, BufferedWriter text) throws IOException {
            var values = List.of(table, start, end, seconds, status, sourceRows, targetRows, matched,
                    inserts, updates, unchanged, targetOnly, error);
            csv.row(values.toArray());
            text.write("[" + table + "]\n");
            for (int i = 1; i < FIELDS.size(); i++) {
                String value = values.get(i).toString().replaceAll("\\R", " ");
                text.write(FIELDS.get(i) + ": " + value + "\n");
            }
            text.write("\n");
        }
    }

    private record Counts(long inserts, long updates) {}

    Summary generate(Config.Table table, Path runDirectory, String runId) {
        var summary = new Summary(table.name());
        if (!table.enabled()) {
            summary.status = "DESABILITADA";
            return summary;
        }
        long startNanos = System.nanoTime();
        summary.start = TIMESTAMP.format(OffsetDateTime.now());
        Path staging = null;
        var published = new ArrayList<Path>();
        try {
            staging = Files.createTempDirectory(runDirectory, ".gerando-");
            Counts counts = compare(table, staging, runId, summary);
            for (Path file : filesIn(staging)) {
                published.add(Files.move(file, runDirectory.resolve(file.getFileName())));
            }
            Files.delete(staging);
            summary.inserts = counts.inserts();
            summary.updates = counts.updates();
            summary.status = summary.inserts > 0 || summary.updates > 0 ? "GERADO" : "SEM_DML";
        } catch (SQLException | IOException | RuntimeException failure) {
            summary.status = "ERRO";
            summary.error = failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage();
            try {
                for (Path file : published) {
                    Files.deleteIfExists(file);
                }
                if (staging != null && Files.exists(staging)) {
                    for (Path file : filesIn(staging)) {
                        Files.deleteIfExists(file);
                    }
                    Files.delete(staging);
                }
            } catch (IOException cleanup) {
                summary.error += " | Falha ao remover arquivos incompletos: " + cleanup.getMessage();
            }
        } finally {
            summary.end = TIMESTAMP.format(OffsetDateTime.now());
            summary.seconds = String.format(Locale.ROOT, "%.3f", (System.nanoTime() - startNanos) / 1e9);
        }
        return summary;
    }

    private static List<Path> filesIn(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files.toList();
        }
    }

    private Counts compare(Config.Table table, Path directory, String runId, Summary summary)
            throws IOException, SQLException {
        var sourceColumns = Oracle.columns(source, config.source().schema(), table.name());
        var targetColumns = Oracle.columns(target, config.target().schema(), table.name());
        var columns = Oracle.validateColumns(sourceColumns, targetColumns, table.keys());
        var fixedCriteria = new ArrayList<SqlScript.Condition>();
        for (var filter : table.filters()) {
            var column = conditionColumn(targetColumns, filter.column());
            var value = SqlScript.filterValue(column, filter.operator(), filter.value());
            fixedCriteria.add(new SqlScript.Condition(column, filter.operator(), value));
        }
        var guardColumns = table.guards().stream().map(name -> conditionColumn(targetColumns, name)).toList();
        var targetProjection = new ArrayList<>(columns);
        for (var column : guardColumns) {
            if (!sourceColumns.containsKey(column.name())) {
                targetProjection.add(column);
            }
        }
        var guardIndexes = guardColumns.stream().map(targetProjection::indexOf).toList();
        var names = columns.stream().map(Oracle.Column::name).toList();
        var keyIndexes = table.keys().stream().map(names::indexOf).toList();
        var valueIndexes = new ArrayList<Integer>();
        for (int i = 0; i < names.size(); i++) {
            if (!keyIndexes.contains(i)) {
                valueIndexes.add(i);
            }
        }

        var targetRows = new LinkedHashMap<List<Object>, Object[]>();
        Oracle.readRows(target, config.target().schema(), table.name(), targetProjection, config.batchSize(), row -> {
            summary.targetRows++;
            var identity = key(row, keyIndexes);
            if (targetRows.putIfAbsent(identity, row) != null) {
                throw new IllegalArgumentException("Chave duplicada no destino: " + keyJson(table, identity));
            }
        });

        String qualifiedTable = Oracle.qualified(config.target().schema(), table.name());
        String prefix = table.name() + "__";
        var sourceKeys = new HashSet<List<Object>>();
        try (var log = new Csv(directory.resolve(prefix + "LOG__" + runId + ".csv"));
             var inserts = new SqlScript(directory.resolve(prefix + "INSERT__" + runId + ".SQL"), config.commitEvery());
             var updates = new SqlScript(directory.resolve(prefix + "UPDATE__" + runId + ".SQL"), config.commitEvery())) {
            log.row("resultado", "chave_json", "colunas_alteradas");
            Oracle.readRows(source, config.source().schema(), table.name(), columns, config.batchSize(), row -> {
                summary.sourceRows++;
                List<Object> identity = key(row, keyIndexes);
                String id = keyJson(table, identity);
                if (!sourceKeys.add(identity)) {
                    throw new IllegalArgumentException("Chave duplicada na origem: " + id);
                }
                Object[] before = targetRows.remove(identity);
                if (before == null) {
                    if (inserts.count() == 0) {
                        checkExtraColumns(targetColumns, sourceColumns.keySet());
                    }
                    var values = insertFields(columns, row);
                    inserts.write(qualifiedTable, values, null);
                    log.row("INSERT", id, "");
                    return;
                }

                summary.matched++;
                var changes = changedFields(columns, before, row, valueIndexes);
                if (changes.isEmpty()) {
                    summary.unchanged++;
                    log.row("SEM_ALTERACAO", id, "");
                } else {
                    var criteria = new ArrayList<SqlScript.Condition>();
                    for (int index : keyIndexes) {
                        criteria.add(new SqlScript.Condition(columns.get(index), "=", before[index]));
                    }
                    criteria.addAll(fixedCriteria);
                    for (int index : guardIndexes) {
                        if (!keyIndexes.contains(index)) {
                            criteria.add(new SqlScript.Condition(targetProjection.get(index), "=", before[index]));
                        }
                    }
                    updates.write(qualifiedTable, changes, criteria);
                    String changedColumns = changes.stream().map(field -> field.column().name())
                            .collect(Collectors.joining(","));
                    log.row("UPDATE", id, changedColumns);
                }
            });

            for (List<Object> identity : targetRows.keySet()) {
                log.row("SOMENTE_NO_DESTINO", keyJson(table, identity), "");
            }
            summary.targetOnly = targetRows.size();
            inserts.finish();
            updates.finish();
            return new Counts(inserts.count(), updates.count());
        }
    }

    private static Oracle.Column conditionColumn(Map<String, Oracle.Column> target, String name) {
        var column = target.get(name);
        if (column == null) {
            throw new IllegalArgumentException("Coluna de condição ausente no destino: " + name);
        }
        if (!Config.isUnquotedIdentifier(column.name()) || !Oracle.SUPPORTED_TYPES.contains(column.type())) {
            throw new IllegalArgumentException("Coluna de condição incompatível: " + name + " (" + column.type() + ").");
        }
        return column;
    }

    private static List<Object> key(Object[] row, List<Integer> indexes) {
        var result = new ArrayList<Object>(indexes.size());
        for (int index : indexes) {
            Object value = row[index];
            result.add(value instanceof BigDecimal decimal ? decimal.stripTrailingZeros() : value);
        }
        return result;
    }

    private static String keyJson(Config.Table table, List<Object> key) throws IOException {
        var fields = new LinkedHashMap<String, Object>();
        for (int i = 0; i < key.size(); i++) {
            Object value = key.get(i);
            fields.put(table.keys().get(i), value instanceof BigDecimal decimal ? decimal.toPlainString() : value);
        }
        return Config.JSON.writeValueAsString(fields);
    }

    private static void checkExtraColumns(Map<String, Oracle.Column> target, Set<String> sourceNames) {
        for (Oracle.Column column : target.values()) {
            if (!sourceNames.contains(column.name()) && !column.nullable() && !column.virtual()
                    && column.identity() == null && column.defaultLength() == null) {
                throw new IllegalArgumentException("Coluna extra obrigatória sem default: " + column.name());
            }
        }
    }

    private static List<SqlScript.Field> insertFields(List<Oracle.Column> columns, Object[] row) {
        var fields = new ArrayList<SqlScript.Field>();
        for (int i = 0; i < columns.size(); i++) {
            Oracle.Column column = columns.get(i);
            Object value = row[i];
            if (column.virtual() || column.generatedAlways()) {
                throw new IllegalArgumentException("INSERT não pode preservar a coluna gerada " + column.name());
            }
            if (value == null && (!column.nullable() || column.defaultOnNull())) {
                throw new IllegalArgumentException("INSERT não pode preservar NULL em " + column.name());
            }
            fields.add(new SqlScript.Field(column, value));
        }
        return fields;
    }

    private static List<SqlScript.Field> changedFields(List<Oracle.Column> columns, Object[] before,
                                                       Object[] after, List<Integer> indexes) {
        var changes = new ArrayList<SqlScript.Field>();
        for (int index : indexes) {
            if (equalValues(before[index], after[index])) {
                continue;
            }
            Oracle.Column column = columns.get(index);
            Object value = after[index];
            if (column.virtual() || column.generatedAlways()) {
                throw new IllegalArgumentException("Não é possível atualizar a coluna gerada " + column.name());
            }
            if (value == null && !column.nullable()) {
                throw new IllegalArgumentException("UPDATE não pode gravar NULL em " + column.name());
            }
            changes.add(new SqlScript.Field(column, value));
        }
        return changes;
    }

    private static boolean equalValues(Object before, Object after) {
        if (before instanceof BigDecimal left && after instanceof BigDecimal right) {
            return left.compareTo(right) == 0;
        }
        if (before instanceof Double left && after instanceof Double right) {
            return left.doubleValue() == right.doubleValue() || left.isNaN() && right.isNaN();
        }
        if (before instanceof Float left && after instanceof Float right) {
            return left.floatValue() == right.floatValue() || left.isNaN() && right.isNaN();
        }
        return Objects.equals(before, after);
    }
}
