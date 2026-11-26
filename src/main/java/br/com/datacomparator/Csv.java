package br.com.datacomparator;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class Csv implements AutoCloseable {
    private final BufferedWriter output;

    Csv(Path path) throws IOException {
        output = Files.newBufferedWriter(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        output.write('\uFEFF');
    }

    void row(Object... values) throws IOException {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                output.write(';');
            }
            String value = values[i] == null ? "" : values[i].toString();
            if (value.indexOf(';') >= 0 || value.indexOf('"') >= 0
                    || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                output.write('"');
                output.write(value.replace("\"", "\"\""));
                output.write('"');
            } else {
                output.write(value);
            }
        }
        output.write("\r\n");
    }

    void flush() throws IOException {
        output.flush();
    }

    @Override
    public void close() throws IOException {
        output.close();
    }
}
