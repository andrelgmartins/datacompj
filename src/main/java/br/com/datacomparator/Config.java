package br.com.datacomparator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

record Config(Endpoint source, Endpoint target, List<Table> tables, Path output,
              int commitEvery, int batchSize, String oracleMode, String oracleConfigDir) {

    record Endpoint(String user, String schema, String dsn) {}
    record Table(String name, List<String> keys, boolean enabled) {}

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Z][A-Z0-9_$#]{0,127}");
    static final ObjectMapper JSON = new ObjectMapper();

    static Config read(Path path) throws IOException {
        JsonNode root = JSON.readTree(path.toFile());
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("A configuração deve ser um objeto JSON.");
        }
        Path base = path.toAbsolutePath().normalize().getParent();
        String mode = text(root, "oracle_mode", "thin");
        if (!Set.of("thin", "thick").contains(mode)) {
            throw new IllegalArgumentException("oracle_mode deve ser thin ou thick.");
        }
        Path workbook = base.resolve(text(root, "workbook", null));
        return new Config(endpoint(root.path("source")), endpoint(root.path("target")),
                tables(workbook, text(root, "workbook_sheet", "Tabelas")),
                base.resolve(text(root, "output_directory", "saida")),
                integer(root, "commit_every_rows", 0, 0),
                integer(root, "fetch_batch_size", 500, 1),
                mode, text(root, "oracle_config_dir", ""));
    }

    private static Endpoint endpoint(JsonNode node) {
        if (!node.isObject()) {
            throw new IllegalArgumentException("Cada conexão deve ser um objeto JSON.");
        }
        if (node.has("password") || node.has("senha")) {
            throw new IllegalArgumentException("Retire a senha do JSON. Ela será pedida no terminal.");
        }
        String user = text(node, "user", null);
        String schema = identifier(text(node, "schema", user));
        if (node.has("dsn")) {
            if (node.has("host") || node.has("port") || node.has("service_name")) {
                throw new IllegalArgumentException("Informe dsn ou host/port/service_name.");
            }
            return new Endpoint(user, schema, text(node, "dsn", null));
        }
        int port = integer(node, "port", 1521, 1);
        if (port > 65535) {
            throw new IllegalArgumentException("port deve estar entre 1 e 65535.");
        }
        String dsn = "%s:%d/%s".formatted(text(node, "host", null), port,
                text(node, "service_name", null));
        return new Endpoint(user, schema, dsn);
    }

    private static List<Table> tables(Path path, String sheetName) throws IOException {
        try (var input = Files.newInputStream(path); var workbook = new XSSFWorkbook(input)) {
            var sheet = workbook.getSheet(sheetName);
            if (sheet == null || sheet.getRow(0) == null) {
                throw new IllegalArgumentException("Aba ausente ou vazia: " + sheetName);
            }
            var headers = new HashMap<String, Integer>();
            for (Cell cell : sheet.getRow(0)) {
                String name = cellText(cell).strip().toLowerCase(Locale.ROOT);
                if (!name.isEmpty() && headers.put(name, cell.getColumnIndex()) != null) {
                    throw new IllegalArgumentException("Cabeçalho repetido: " + name);
                }
            }
            List<String> required = List.of("tabela", "habilitada", "colunas_chave");
            if (!headers.keySet().containsAll(required)) {
                throw new IllegalArgumentException("A planilha precisa dos campos: " + required);
            }

            var result = new ArrayList<Table>();
            var names = new HashSet<String>();
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || isBlank(row)) {
                    continue;
                }
                try {
                    String name = identifier(cellText(row.getCell(headers.get("tabela"))));
                    String enabled = cellText(row.getCell(headers.get("habilitada")))
                            .strip().toUpperCase(Locale.ROOT);
                    if (!Set.of("SIM", "NÃO", "NAO").contains(enabled)) {
                        throw new IllegalArgumentException("habilitada deve ser SIM ou NÃO.");
                    }
                    var keys = Arrays.stream(cellText(row.getCell(headers.get("colunas_chave")))
                                    .split(",", -1))
                            .map(Config::identifier).toList();
                    if (new HashSet<>(keys).size() != keys.size()) {
                        throw new IllegalArgumentException("Há colunas repetidas na chave.");
                    }
                    if (!names.add(name)) {
                        throw new IllegalArgumentException("Tabela repetida: " + name);
                    }
                    result.add(new Table(name, keys, enabled.equals("SIM")));
                } catch (IllegalArgumentException error) {
                    throw new IllegalArgumentException("Planilha, linha " + (i + 1) + ": "
                            + error.getMessage(), error);
                }
            }
            if (result.stream().noneMatch(Table::enabled)) {
                throw new IllegalArgumentException("A planilha não contém tabelas habilitadas.");
            }
            return List.copyOf(result);
        }
    }

    private static boolean isBlank(Row row) {
        for (Cell cell : row) {
            if (cell.getCellType() != CellType.BLANK) {
                return false;
            }
        }
        return true;
    }

    private static String cellText(Cell cell) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return "";
        }
        if (cell.getCellType() != CellType.STRING) {
            throw new IllegalArgumentException("Use texto na configuração da planilha.");
        }
        return cell.getStringCellValue();
    }

    private static String identifier(String input) {
        String name = input.strip().toUpperCase(Locale.ROOT);
        if (!IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("Identificador Oracle inválido: " + input);
        }
        return name;
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        if ((value == null || value.isNull() && fallback != null && fallback.isEmpty())
                && fallback != null) {
            return fallback;
        }
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(field + " deve ser um texto preenchido.");
        }
        return value.textValue().strip();
    }

    private static int integer(JsonNode node, String field, int fallback, int minimum) {
        JsonNode value = node.get(field);
        if (value == null) {
            return fallback;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < minimum) {
            throw new IllegalArgumentException(field + " deve ser um inteiro a partir de " + minimum);
        }
        return value.intValue();
    }
}
