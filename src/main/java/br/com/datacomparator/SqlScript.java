package br.com.datacomparator;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class SqlScript implements AutoCloseable {
    record Field(Oracle.Column column, Object value) {}
    private record Assignment(String name, String expression) {}
    private record Lob(String variable, String text) {}

    private static final String CONTINUATION = " ||\n      ";
    private final Path path;
    private final int commitEvery;
    private BufferedWriter output;
    private long count;

    SqlScript(Path path, int commitEvery) {
        this.path = path;
        this.commitEvery = commitEvery;
    }

    long count() {
        return count;
    }

    void write(String table, List<Field> values, List<Field> keys) throws IOException {
        openIfNeeded();
        var assignments = new ArrayList<Assignment>();
        var lobs = new ArrayList<Lob>();
        for (Field field : values) {
            String sqlValue;
            if (field.column().type().equals("CLOB") && field.value() instanceof String text
                    && !text.isEmpty()) {
                sqlValue = "v_clob_" + (lobs.size() + 1);
                lobs.add(new Lob(sqlValue, text));
            } else {
                sqlValue = expression(field);
            }
            assignments.add(new Assignment(field.column().name(), sqlValue));
        }

        boolean block = !lobs.isEmpty();
        if (block) {
            output.write("DECLARE\n");
            for (Lob lob : lobs) {
                output.write("  " + lob.variable() + " CLOB;\n");
            }
            output.write("BEGIN\n");
        }
        for (Lob lob : lobs) {
            output.write("  DBMS_LOB.CREATETEMPORARY(" + lob.variable() + ", TRUE, DBMS_LOB.CALL);\n");
            textParts(lob.text(), part ->
                    output.write("  DBMS_LOB.APPEND(" + lob.variable() + ", TO_CLOB(" + part + "));\n"));
        }

        String indent = block ? "  " : "";
        if (keys == null) {
            var names = assignments.stream().map(Assignment::name).toList();
            var expressions = assignments.stream().map(Assignment::expression).toList();
            output.write(indent + "INSERT INTO " + table + " (\n    " + String.join(",\n    ", names)
                    + "\n  ) VALUES (\n    " + String.join(",\n    ", expressions) + "\n  );\n");
        } else {
            var changes = assignments.stream().map(item -> item.name() + " = " + item.expression()).toList();
            var predicates = new ArrayList<String>();
            for (Field key : keys) {
                String name = key.column().name();
                predicates.add(key.value() == null
                        ? name + " IS NULL" : name + " = (" + expression(key) + ")");
            }
            output.write(indent + "UPDATE " + table + "\n    SET " + String.join(",\n    ", changes)
                    + "\n    WHERE " + String.join("\n    AND ", predicates) + ";\n");
        }
        for (Lob lob : lobs) {
            output.write("  DBMS_LOB.FREETEMPORARY(" + lob.variable() + ");\n");
        }
        if (block) {
            output.write("END;\n/\n");
        }
        output.write("\n");
        count++;
        if (commitEvery > 0 && count % commitEvery == 0) {
            output.write("COMMIT;\n\n");
        }
    }

    private void openIfNeeded() throws IOException {
        if (output != null) {
            return;
        }
        output = Files.newBufferedWriter(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        output.write("""
                SET DEFINE OFF
                SET AUTOCOMMIT OFF
                SET SQLBLANKLINES ON
                WHENEVER SQLERROR EXIT FAILURE ROLLBACK
                """);
        for (String sessionSetting : Oracle.SESSION_SETTINGS) {
            output.write(sessionSetting + ";\n");
        }
        output.write("\n");
    }

    void finish() throws IOException {
        if (output != null && commitEvery > 0 && count % commitEvery != 0) {
            output.write("COMMIT;\n");
        }
    }

    @Override
    public void close() throws IOException {
        if (output != null) {
            output.close();
        }
    }

    private static String literal(String text) {
        return "'" + text.replace("'", "''") + "'";
    }

    @FunctionalInterface
    private interface TextPartConsumer {
        void accept(String value) throws IOException;
    }

    // Mantém cada literal abaixo dos limites do cliente e retira controles da linha física.
    private static void textParts(String text, TextPartConsumer consumer) throws IOException {
        var literalText = new StringBuilder();
        int bytes = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            boolean control = codePoint < 32 || codePoint == 127;
            int width = escapedUtf8Length(codePoint);
            if (control || bytes + width > 1000) {
                if (!literalText.isEmpty()) {
                    consumer.accept(literal(literalText.toString()));
                    literalText.setLength(0);
                    bytes = 0;
                }
            }
            if (control) {
                consumer.accept("CHR(" + codePoint + ")");
            } else {
                literalText.appendCodePoint(codePoint);
                bytes += width;
            }
        }
        if (!literalText.isEmpty()) {
            consumer.accept(literal(literalText.toString()));
        }
    }

    private static int escapedUtf8Length(int codePoint) {
        if (codePoint == '\'') {
            return 2;
        }
        if (codePoint <= 0x7F) {
            return 1;
        }
        if (codePoint <= 0x7FF) {
            return 2;
        }
        return codePoint <= 0xFFFF ? 3 : 4;
    }

    private static String characterText(String value) throws IOException {
        var parts = new ArrayList<String>();
        textParts(value, parts::add);
        return parts.isEmpty() ? "NULL" : String.join(CONTINUATION, parts);
    }

    private static String nationalText(String value) {
        var parts = new ArrayList<String>();
        int codePoints = value.codePointCount(0, value.length());
        for (int offset = 0; offset < value.length();) {
            int length = Math.min(100, codePoints);
            int end = value.offsetByCodePoints(offset, length);
            var escaped = new StringBuilder("UNISTR('");
            for (int i = offset; i < end; i++) {
                escaped.append(String.format(Locale.ROOT, "\\%04X", (int) value.charAt(i)));
            }
            parts.add(escaped.append("')").toString());
            offset = end;
            codePoints -= length;
        }
        return parts.isEmpty() ? "NULL" : String.join(CONTINUATION, parts);
    }

    private static String raw(String hex) {
        var parts = new ArrayList<String>();
        for (int offset = 0; offset < hex.length(); offset += 1000) {
            String piece = hex.substring(offset, Math.min(hex.length(), offset + 1000));
            parts.add("HEXTORAW('" + piece + "')");
        }
        while (parts.size() > 1) {
            var grouped = new ArrayList<String>();
            for (int offset = 0; offset < parts.size(); offset += 8) {
                var group = parts.subList(offset, Math.min(parts.size(), offset + 8));
                grouped.add(group.size() == 1 ? group.getFirst()
                        : "UTL_RAW.CONCAT(\n      " + String.join(",\n      ", group) + ")");
            }
            parts = grouped;
        }
        return parts.isEmpty() ? "NULL" : parts.getFirst();
    }

    private static String binaryFloat(String type, Number value) {
        double number = value.doubleValue();
        if (Double.isNaN(number)) {
            return type + "_NAN";
        }
        if (Double.isInfinite(number)) {
            return (number < 0 ? "-" : "") + type + "_INFINITY";
        }
        return value + (type.equals("BINARY_FLOAT") ? "f" : "d");
    }

    private static String expression(Field field) throws IOException {
        Object value = field.value();
        if (value == null) {
            return "NULL";
        }
        String type = field.column().type();
        return switch (type) {
            case "CHAR", "VARCHAR2" -> characterText((String) value);
            case "NCHAR", "NVARCHAR2" -> nationalText((String) value);
            case "NUMBER", "FLOAT" -> ((BigDecimal) value).toPlainString();
            case "BINARY_FLOAT", "BINARY_DOUBLE" -> binaryFloat(type, (Number) value);
            case "DATE" -> "TO_DATE(" + literal((String) value) + ", '" + Oracle.DATE_FORMAT + "')";
            case "TIMESTAMP" -> "TO_TIMESTAMP(" + literal((String) value) + ", '" + Oracle.TIMESTAMP_FORMAT + "')";
            case "RAW" -> raw((String) value);
            case "BOOLEAN" -> Boolean.TRUE.equals(value) ? "TRUE" : "FALSE";
            case "CLOB" -> "EMPTY_CLOB()"; // CLOB com conteúdo é escrito em um bloco PL/SQL.
            default -> throw new IllegalArgumentException("Sem expressão SQL para " + field.column().name());
        };
    }
}
