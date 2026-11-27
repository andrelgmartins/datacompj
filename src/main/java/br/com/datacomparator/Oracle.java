package br.com.datacomparator;

import java.io.Console;
import java.io.IOException;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

final class Oracle {
    static final String DATE_FORMAT = "SYYYY-MM-DD HH24:MI:SS";
    static final String TIMESTAMP_FORMAT = DATE_FORMAT + ".FF9";
    static final List<String> SESSION_SETTINGS = List.of(
            "ALTER SESSION SET NLS_CALENDAR = 'GREGORIAN'",
            "ALTER SESSION SET NLS_COMP = 'BINARY'",
            "ALTER SESSION SET NLS_SORT = 'BINARY'");
    private static final Set<String> CHARACTER_TYPES = Set.of("CHAR", "VARCHAR2", "NCHAR", "NVARCHAR2");
    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "CHAR", "VARCHAR2", "NCHAR", "NVARCHAR2", "NUMBER", "FLOAT",
            "BINARY_FLOAT", "BINARY_DOUBLE", "DATE", "TIMESTAMP", "RAW", "CLOB", "BOOLEAN");

    private static final String COLUMNS_QUERY = """
            SELECT c.column_name, c.data_type, c.nullable, c.char_length,
                   c.collation, c.virtual_column, i.generation_type, c.default_length,
                   c.default_on_null, c.data_precision, c.data_scale
              FROM all_tab_cols c
              JOIN all_tables t ON t.owner = c.owner AND t.table_name = c.table_name
              LEFT JOIN all_tab_identity_cols i
                ON i.owner = c.owner AND i.table_name = c.table_name
               AND i.column_name = c.column_name
             WHERE c.owner = ? AND c.table_name = ? AND c.user_generated = 'YES'
             ORDER BY c.internal_column_id
            """;

    private Oracle() {}

    record Column(String name, String type, boolean nullable, int charLength, String collation,
                  boolean virtual, String identity, Integer defaultLength, boolean defaultOnNull,
                  Integer precision, Integer scale) {
        boolean generatedAlways() {
            return "ALWAYS".equals(identity);
        }
    }

    @FunctionalInterface
    interface RowConsumer {
        void accept(Object[] row) throws IOException;
    }

    static String qualified(String schema, String table) {
        return schema + "." + table;
    }

    static Connection connect(Config config, Config.Endpoint endpoint, Console console, String label)
            throws SQLException {
        boolean radius = config.oracleAuthentication().equals("radius");
        String credential = radius ? "Senha ou código RADIUS" : "Senha";
        char[] password = console.readPassword("%s da conexão de %s (%s): ",
                credential, label, endpoint.user());
        if (password == null) {
            throw new IllegalArgumentException("Leitura da senha interrompida.");
        }
        var credentials = new Properties();
        try {
            credentials.setProperty("user", endpoint.user());
            credentials.setProperty("password", new String(password));
            if (!config.oracleConfigDir().isEmpty()) {
                credentials.setProperty("oracle.net.tns_admin", config.oracleConfigDir());
            }
            String mode = config.oracleMode().equals("thick") ? "oci" : "thin";
            if (radius && mode.equals("thin")) {
                credentials.setProperty("oracle.net.authentication_services", "(RADIUS)");
            }
            return DriverManager.getConnection("jdbc:oracle:" + mode + ":@" + endpoint.dsn(),
                    credentials);
        } finally {
            Arrays.fill(password, '\0');
            credentials.remove("password");
        }
    }

    static void startSnapshot(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement()) {
            for (String sql : SESSION_SETTINGS) {
                statement.execute(sql);
            }
            statement.execute("SET TRANSACTION READ ONLY");
        }
    }

    static Map<String, Column> columns(Connection connection, String schema, String table)
            throws SQLException {
        var result = new LinkedHashMap<String, Column>();
        try (var statement = connection.prepareStatement(COLUMNS_QUERY)) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String name = rows.getString(1);
                    String type = rows.getString(2);
                    if (type.startsWith("TIMESTAMP(") && !type.contains("TIME ZONE")) {
                        type = "TIMESTAMP";
                    }
                    result.put(name, new Column(name, type, "Y".equals(rows.getString(3)),
                            rows.getInt(4), rows.getString(5), "YES".equals(rows.getString(6)),
                            rows.getString(7), nullableInt(rows, 8), "YES".equals(rows.getString(9)),
                            nullableInt(rows, 10), nullableInt(rows, 11)));
                }
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("Tabela não encontrada ou sem acesso: " + schema + "." + table);
        }
        return result;
    }

    private static Integer nullableInt(ResultSet rows, int column) throws SQLException {
        int value = rows.getInt(column);
        return rows.wasNull() ? null : value;
    }

    static List<Column> validateColumns(Map<String, Column> source, Map<String, Column> target,
                                        List<String> keys) {
        for (String key : keys) {
            if (!source.containsKey(key)) {
                throw new IllegalArgumentException("Coluna chave ausente na origem: " + key);
            }
        }
        var projection = new ArrayList<Column>();
        for (Column column : source.values()) {
            Column other = target.get(column.name());
            if (other == null) {
                throw new IllegalArgumentException("Coluna ausente no destino: " + column.name());
            }
            if (!Config.isUnquotedIdentifier(column.name())) {
                throw new IllegalArgumentException(
                        "Nome de coluna incompatível com SQL sem aspas: " + column.name());
            }
            if (!column.type().equals(other.type()) || !SUPPORTED_TYPES.contains(column.type())) {
                throw new IllegalArgumentException("Tipos incompatíveis ou sem suporte em %s: %s → %s."
                        .formatted(column.name(), column.type(), other.type()));
            }
            if (!acceptsPrecision(column, other)) {
                throw new IllegalArgumentException("O destino pode arredondar ou rejeitar " + column.name());
            }
            if (keys.contains(column.name())) {
                validateKey(column, other);
            }
            projection.add(other);
        }
        return projection;
    }

    private static boolean acceptsPrecision(Column source, Column target) {
        return switch (source.type()) {
            case "TIMESTAMP" -> orDefault(source.scale(), 0) <= orDefault(target.scale(), 0);
            case "FLOAT" -> orDefault(source.precision(), 126) <= orDefault(target.precision(), 126);
            case "NUMBER" -> target.scale() == null || (
                    source.scale() != null && source.scale() <= target.scale()
                    && orDefault(source.precision(), 38) - source.scale()
                    <= orDefault(target.precision(), 38) - target.scale());
            default -> true;
        };
    }

    private static int orDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static void validateKey(Column source, Column target) {
        String type = source.type();
        if (Set.of("CLOB", "BINARY_FLOAT", "BINARY_DOUBLE").contains(type)) {
            throw new IllegalArgumentException("Tipo não aceito em chave: " + source.name() + " (" + type + ").");
        }
        if (CHARACTER_TYPES.contains(type) && (!binary(source.collation()) || !binary(target.collation()))) {
            throw new IllegalArgumentException("A chave textual " + source.name() + " precisa usar collation binária.");
        }
        if (Set.of("CHAR", "NCHAR").contains(type) && source.charLength() != target.charLength()) {
            throw new IllegalArgumentException("O comprimento da chave textual fixa difere: " + source.name());
        }
    }

    private static boolean binary(String collation) {
        return collation == null || collation.equals("BINARY") || collation.equals("USING_NLS_COMP");
    }

    static void readRows(Connection connection, String schema, String table, List<Column> columns,
                         int batchSize, RowConsumer consumer) throws SQLException, IOException {
        String projection = columns.stream().map(Oracle::selectExpression).collect(Collectors.joining(", "));
        String query = "SELECT " + projection + " FROM " + qualified(schema, table);
        try (var statement = connection.createStatement()) {
            statement.setFetchSize(batchSize);
            try (var rows = statement.executeQuery(query)) {
                while (rows.next()) {
                    Object[] values = new Object[columns.size()];
                    for (int i = 0; i < values.length; i++) {
                        values[i] = readValue(rows, i + 1, columns.get(i).type());
                    }
                    consumer.accept(values);
                }
            }
        }
    }

    private static String selectExpression(Column column) {
        String name = column.name();
        return switch (column.type()) {
            case "DATE" -> "TO_CHAR(" + name + ", '" + DATE_FORMAT + "')";
            case "TIMESTAMP" -> "TO_CHAR(" + name + ", '" + TIMESTAMP_FORMAT + "')";
            default -> name;
        };
    }

    private static Object readValue(ResultSet rows, int index, String type) throws SQLException, IOException {
        return switch (type) {
            case "NUMBER", "FLOAT" -> rows.getBigDecimal(index);
            case "BINARY_FLOAT" -> {
                float value = rows.getFloat(index);
                yield rows.wasNull() ? null : Float.valueOf(value);
            }
            case "BINARY_DOUBLE" -> {
                double value = rows.getDouble(index);
                yield rows.wasNull() ? null : Double.valueOf(value);
            }
            case "RAW" -> {
                byte[] bytes = rows.getBytes(index);
                yield bytes == null ? null : HexFormat.of().withUpperCase().formatHex(bytes);
            }
            case "BOOLEAN" -> {
                boolean value = rows.getBoolean(index);
                yield rows.wasNull() ? null : value;
            }
            case "NCHAR", "NVARCHAR2" -> rows.getNString(index);
            case "CLOB" -> readClob(rows, index);
            default -> rows.getString(index);
        };
    }

    private static String readClob(ResultSet rows, int index) throws SQLException, IOException {
        var clob = rows.getClob(index);
        if (clob == null) {
            return null;
        }
        try (var reader = clob.getCharacterStream()) {
            var buffer = new StringWriter();
            reader.transferTo(buffer);
            return buffer.toString();
        } finally {
            clob.free();
        }
    }
}
