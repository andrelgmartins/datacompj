package br.com.datacomparator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class Main {
    private static final DateTimeFormatter RUN_ID =
            DateTimeFormatter.ofPattern("uuuuMMdd_HHmmss_SSSSSS", Locale.ROOT);
    private static final String USAGE =
            "Uso: java -jar target/data-comparator-0.1.0.jar --config config/conexoes.json";

    private Main() {}

    public static void main(String[] args) {
        System.exit(execute(args));
    }

    private static int execute(String[] args) {
        if (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h"))) {
            System.out.println(USAGE);
            return 0;
        }
        if (args.length != 2 || !args[0].equals("--config")) {
            System.err.println(USAGE);
            return 2;
        }
        try {
            return generate(Config.read(Path.of(args[1])));
        } catch (Exception failure) {
            System.err.println("Erro: " + failure.getMessage());
            return 1;
        }
    }

    private static int generate(Config config) throws Exception {
        var console = System.console();
        if (console == null) {
            throw new IllegalStateException("Execute em um terminal para digitar as senhas com entrada oculta.");
        }
        try (var source = Oracle.connect(config, config.source(), console, "origem");
             var target = Oracle.connect(config, config.target(), console, "destino")) {
            Oracle.startSnapshot(source);
            Oracle.startSnapshot(target);

            Files.createDirectories(config.output());
            String runId = RUN_ID.format(LocalDateTime.now());
            Path directory = Files.createDirectory(config.output().resolve(runId));
            System.out.println("Saída: " + directory.toAbsolutePath().normalize());
            var comparator = new TableComparator(config, source, target);
            boolean failed = false;
            try (var report = new Csv(directory.resolve("SUMARIO.csv"))) {
                TableComparator.Summary.writeHeader(report);
                for (int i = 0; i < config.tables().size(); i++) {
                    var table = config.tables().get(i);
                    System.out.printf("[%d/%d] %s%n", i + 1, config.tables().size(), table.name());
                    var result = comparator.generate(table, directory, runId);
                    result.writeTo(report);
                    report.flush();
                    System.out.printf("  %s | INSERT: %d | UPDATE: %d%n",
                            result.status, result.inserts, result.updates);
                    if (!result.error.isEmpty()) {
                        System.err.println("  " + result.error);
                    }
                    failed |= result.status.equals("ERRO");
                }
            }
            return failed ? 1 : 0;
        }
    }
}
