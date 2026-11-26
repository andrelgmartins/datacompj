# DataComparator em Java

Compara tabelas de dois esquemas Oracle, registra cada chave em CSV e gera arquivos SQL para aplicação posterior no destino. As duas conexões de leitura podem apontar para o mesmo banco ou para bancos diferentes.

## Compilar e executar

Use JDK 21 e Maven instalados na distribuição Linux do WSL. No Bash do WSL, a partir da pasta `javacode`:

```bash
mvn package
cp -i config/conexoes.exemplo.json config/conexoes.json
cp -i config/tabelas.exemplo.xlsx config/tabelas.xlsx
```

Preencha `source` e `target` no JSON. Para cada conexão, informe `user`, `schema` e os campos `host`, `port` e `service_name`. Use `dsn` no lugar dos três últimos campos para informar um alias TNS, uma string Easy Connect ou um descritor. Os caminhos em `workbook` e `output_directory` são relativos à pasta do JSON.

Na aba `Tabelas` da planilha, use uma linha por tabela. Preencha `habilitada` com `SIM` ou `NÃO` e separe as colunas de uma chave composta por vírgulas, como `ID_CLIENTE, SEQUENCIA`. Em seguida, execute no Bash do WSL:

```bash
java -jar target/data-comparator-0.1.0.jar --config config/conexoes.json
```

O Maven empacota as dependências no JAR. O Java pede as duas senhas com digitação oculta.

## Dependências e conexão

| Dependência | Uso |
|---|---|
| `com.oracle.database.jdbc:ojdbc17` | Oracle JDBC |
| `org.apache.poi:poi-ooxml` | Leitura do Excel |
| `com.fasterxml.jackson.core:jackson-databind` | Configuração JSON e chaves nos logs |

O driver configurado tem suporte documentado para Oracle Database 19c, 21c e 26ai. No modo padrão `thin`, o cliente Oracle não é necessário. Se usar um alias TNS, informe `oracle_config_dir` no JSON. Se o ambiente exigir o modo `thick`, instale as bibliotecas OCI para Linux na distribuição WSL e configure o caminho delas no ambiente do processo Java. Para alias TNS em OCI, configure `TNS_ADMIN`.

## Saída

- `SUMARIO.csv` contém os horários de início e término, duração, contagens e status por tabela.
- `TABELA__LOG__DATA.csv` identifica todas as chaves da tabela e indica `INSERT`, `UPDATE`, `SEM_ALTERACAO` ou `SOMENTE_NO_DESTINO`. Para updates, registra as colunas diferentes.
- Os arquivos `TABELA__INSERT__DATA.SQL` e `TABELA__UPDATE__DATA.SQL` só são criados quando há DML para aquela operação. Com `commit_every_rows: 0`, o arquivo sai sem commits. Um intervalo positivo se aplica a cada arquivo e inclui o lote restante.
- A tabela do destino é indexada em memória. Os CLOBs são comparados pelo conteúdo e preservam a distinção entre vazio e nulo. O volume dos CLOBs e a largura das linhas influenciam o uso de memória nas maiores tabelas.

## Aplicar os scripts

Abra o SQL*Plus ou SQLcl no WSL, conectado ao destino, e execute o arquivo com `@arquivo.sql`. Use o cliente em UTF-8. No SQL*Plus, configure o componente de charset de `NLS_LANG` como `AL32UTF8` antes de abrir o cliente. Os scripts desligam a substituição de variáveis com `&` e o autocommit.

Com `commit_every_rows: 0`, mantenha a sessão aberta até decidir entre `COMMIT` e `ROLLBACK`. Em caso de erro SQL, o script pede ao cliente para sair com rollback da transação pendente. Lotes já confirmados permanecem aplicados. Respeite a ordem exigida pelas chaves estrangeiras do destino.
