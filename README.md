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

O Maven empacota as dependências no JAR. O Java pede as credenciais com digitação oculta.

## Dependências e conexão

| Dependência | Uso |
|---|---|
| `com.oracle.database.jdbc:ojdbc17` | Oracle JDBC |
| `org.apache.poi:poi-ooxml` | Leitura do Excel |
| `com.fasterxml.jackson.core:jackson-databind` | Configuração JSON e chaves nos logs |

O driver configurado tem suporte documentado para Oracle Database 19c, 21c e 26ai. No modo padrão `thin`, o cliente Oracle não é necessário. Se usar um alias TNS, informe `oracle_config_dir` no JSON. Um caminho relativo aponta para uma pasta a partir do diretório do JSON.

### Autenticação RADIUS

Defina `"oracle_authentication": "radius"` no JSON. Em modo Thin, o programa configura a propriedade JDBC `oracle.net.authentication_services=(RADIUS)` nas duas conexões. Digite a senha ou o código RADIUS quando solicitado. O fluxo aceita uma credencial por conexão e não implementa uma etapa adicional de desafio e resposta.

Se o ambiente exigir `"oracle_mode": "thick"`, instale as bibliotecas OCI para Linux na distribuição WSL e configure o caminho delas no ambiente do processo Java. Aponte `TNS_ADMIN` para o diretório cujo `sqlnet.ora` define `SQLNET.AUTHENTICATION_SERVICES = (RADIUS)`. Para uma conta Oracle com senha convencional, use `"oracle_authentication": "password"` ou omita o campo e use o perfil Oracle Net correspondente.

## Saída

Cada execução usa a mesma data e hora local no nome da pasta e no sufixo `DATA` dos arquivos, por exemplo `2026-11-27_15h27m30s_000000`. Os seis últimos dígitos representam os microssegundos.

- `SUMARIO__DATA.csv` contém os horários de início e término, duração, contagens e status por tabela.
- `SUMARIO__DATA.txt` contém os mesmos campos, em formato de texto.
- `TABELA__LOG__DATA.csv` identifica todas as chaves da tabela e indica `INSERT`, `UPDATE`, `SEM_ALTERACAO` ou `SOMENTE_NO_DESTINO`. Para updates, registra as colunas diferentes.
- Os arquivos `TABELA__INSERT__DATA.SQL` e `TABELA__UPDATE__DATA.SQL` só são criados quando há DML para aquela operação. Com `commit_every_rows: 0`, o arquivo sai sem commits. Um intervalo positivo se aplica a cada arquivo e inclui o lote restante.
- Os scripts usam nomes de esquemas, tabelas e colunas sem aspas. Cada update é emitido como SQL direto. Quando é necessário montar um CLOB com conteúdo, o comando fica dentro de um bloco PL/SQL.
- A tabela do destino é indexada em memória. Os CLOBs são comparados pelo conteúdo e preservam a distinção entre vazio e nulo. O volume dos CLOBs e a largura das linhas influenciam o uso de memória nas maiores tabelas.


### Condições do UPDATE

As colunas abaixo são opcionais na aba `Tabelas`. Planilhas anteriores continuam funcionando.

| Colunas da planilha | Condição no destino |
|---|---|
| `coluna_maior`, `valor_maior` | `COLUNA > valor` |
| `coluna_menor`, `valor_menor` | `COLUNA < valor` |
| `coluna_igual`, `valor_igual` | `COLUNA = valor` |
| `colunas_conferir_destino` | Igualdade com o valor lido no destino, para cada coluna da lista. |

Preencha ou deixe em branco os dois campos de cada par. Os campos `valor_*` aceitam números do Excel e texto, sem aspas de SQL nem fórmulas. Use texto quando precisar preservar zeros à esquerda, números com muitos dígitos ou datas. Para números digitados como texto, use ponto decimal. Para datas, use formato ISO, como `2026-11-27` ou `2026-11-27 14:30:00`. Para `TIMESTAMP`, é possível incluir até nove casas decimais nos segundos. `CLOB`, `RAW` e `BOOLEAN` aceitam apenas igualdade. Use hexadecimal para `RAW` e `TRUE` ou `FALSE` para `BOOLEAN`.

Na lista de conferência, separe os nomes com vírgula. Por exemplo, `STATUS, VERSAO` inclui `AND STATUS = ('PENDENTE') AND VERSAO = (7)` se esses forem os valores lidos no destino. Um valor anterior nulo produz `IS NULL`. É possível indicar colunas que só existem no destino.

As condições são combinadas com `AND` depois da chave. Elas valem na aplicação do `UPDATE`; não filtram a leitura nem os `INSERT`s. Se uma condição não for satisfeita, aquele comando pode afetar zero linhas.

### Legenda do log por tabela

| `resultado` | Significado |
|---|---|
| `INSERT` | A chave só existia na origem e gerou um `INSERT`. |
| `UPDATE` | A chave existia nos dois lados e gerou um `UPDATE`. A coluna `colunas_alteradas` lista os campos incluídos no `SET`. |
| `SEM_ALTERACAO` | A chave existia nos dois lados e os valores comparados eram iguais. |
| `SOMENTE_NO_DESTINO` | A chave só existia no destino. |

O log e o sumário registram a geração dos arquivos. A contagem de `updates_gerados` inclui os comandos com condições adicionais, mesmo que depois algum deles afete zero linhas. No sumário TXT, cada tabela tem um grupo com um campo por linha.

## Aplicar os scripts

Abra o SQL*Plus ou SQLcl no WSL, conectado ao destino, e execute o arquivo com `@arquivo.sql`. Use o cliente em UTF-8. No SQL*Plus, configure o componente de charset de `NLS_LANG` como `AL32UTF8` antes de abrir o cliente. Os scripts desligam a substituição de variáveis com `&` e o autocommit.

Com `commit_every_rows: 0`, mantenha a sessão aberta até decidir entre `COMMIT` e `ROLLBACK`. Em caso de erro SQL, o script pede ao cliente para sair com rollback da transação pendente. Lotes já confirmados permanecem aplicados. Respeite a ordem exigida pelas chaves estrangeiras do destino.
