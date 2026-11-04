# AGENTS.md

Instruções para agentes de código (opencode, com DeepSeek Flash e GLM) que trabalham neste repositório. Leia tudo antes de alterar qualquer arquivo.

## Sobre o projeto

Distill é um backend em Java que recebe documentos, processa de forma assíncrona com um LLM e devolve resumo, pontos-chave e tags.

Fluxo principal: cliente cria o documento na API, sobe o arquivo direto no S3 por URL pré-assinada, o S3 manda um evento para o SQS, o worker consome, chama o LLM e grava o resultado no DynamoDB. A API só lê e escreve metadados.

O README.md é a fonte de verdade sobre arquitetura e decisões de design. Se algo aqui conflitar com ele, pergunte antes de seguir.

## Stack

- Java 21, Spring Boot 3, Maven multi-módulo
- AWS SDK for Java v2 (S3, SQS, DynamoDB, Bedrock Runtime)
- LLM: Ollama no perfil `local`, Amazon Bedrock no perfil `aws`
- AWS CDK em Java para infraestrutura
- JUnit 5, Mockito, Testcontainers (LocalStack)

Não adicione frameworks ou bibliotecas novas sem justificar o motivo. Prefira o que já está no projeto.

## Estrutura dos módulos

```
core/     domínio, DTOs, interface LlmClient, prompts, validação do JSON do LLM
api/      controllers REST, services de documento, presigned URL
worker/   listener SQS, extração de texto, orquestração da chamada ao LLM
infra/    stacks do CDK
docs/     ADRs e diagramas
```

Regras de dependência:

- `core` não depende de `api`, `worker` nem do Spring Web.
- `api` e `worker` dependem de `core`, nunca um do outro.
- Código de AWS fica em classes de adaptador (por exemplo `S3Storage`, `DocumentRepository`), nunca espalhado pelo domínio.

## Comandos

```bash
./mvnw clean verify                  # build e todos os testes
./mvnw -pl core test                 # testes de um módulo
./mvnw -pl api spring-boot:run       # sobe a API (perfil local)
./mvnw -pl worker spring-boot:run    # sobe o worker (perfil local)
docker compose up -d                 # LocalStack e Ollama
```

Antes de dizer que terminou uma tarefa, rode `./mvnw clean verify` e confirme que passa.

## Convenções de código

- Use `record` para DTOs e objetos de valor imutáveis.
- Injeção de dependência por construtor. Não use `@Autowired` em campo.
- Nomes de classes, métodos e variáveis em inglês. Comentários e mensagens de commit podem ser em português.
- Métodos pequenos, com uma responsabilidade. Evite classes com mais de 200 linhas.
- Nada de `System.out.println`. Use SLF4J.
- Não engula exceções. Trate, registre com contexto ou propague.
- Evite `null`. Use `Optional` em retornos quando fizer sentido.
- Não deixe código comentado nem TODO sem descrição.
- Configurações ficam em `application.yml` e em classes `@ConfigurationProperties`. Nenhum valor fixo no código.

## Regras específicas do domínio

**Idempotência.** O SQS entrega a mesma mensagem mais de uma vez. O worker deve usar escrita condicional no DynamoDB (verificando `documentId` e o status atual) antes de processar. Nunca assuma que uma mensagem chega uma vez só.

**Status.** O ciclo é `PENDING`, `PROCESSING`, `DONE` e `FAILED`. Transições inválidas (por exemplo `DONE` voltar para `PROCESSING`) devem ser rejeitadas no código.

**LLM.** Todo acesso ao modelo passa pela interface `LlmClient`. Nenhum outro código chama Ollama ou Bedrock diretamente. A saída do modelo é sempre JSON no formato definido em `core`, e deve ser validada antes de ser salva. Resposta inválida conta como falha e entra no fluxo de retry.

**Prompts.** Ficam em arquivos de recurso (`src/main/resources/prompts/`), não em strings soltas no código. Versione-os e documente a mudança no commit.

**Limites.** Respeite o tamanho máximo de arquivo e o chunking de texto. Não envie documentos inteiros ao modelo sem checar o tamanho.

**Retry e DLQ.** Falhas temporárias usam retry com backoff. Falhas permanentes (arquivo inválido, JSON impossível de validar após as tentativas) vão para a DLQ e marcam o documento como `FAILED`.

## Segurança

- Nunca coloque chaves, tokens ou credenciais no código, nos testes ou no histórico do git.
- Credenciais AWS vêm do ambiente ou de roles IAM. Em desenvolvimento local, use as credenciais falsas do LocalStack.
- Roles IAM no CDK seguem privilégio mínimo. Não use `*` em ações ou recursos sem um motivo documentado.
- Valide tamanho, tipo e nome do arquivo antes de gerar a URL pré-assinada.
- Não registre o conteúdo dos documentos em logs. Registre apenas IDs e metadados.

## Testes

- Toda funcionalidade nova vem com teste. Todo bug corrigido vem com um teste que reproduz o erro.
- Testes unitários para regra de negócio, sem subir Spring quando não precisar.
- Testes de integração com Testcontainers e LocalStack para S3, SQS e DynamoDB.
- O `LlmClient` é sempre mockado ou substituído por um fake nos testes. Testes automatizados não chamam modelo real.
- Nomeie os testes de forma que descrevam o comportamento, por exemplo `shouldMarkDocumentAsFailedAfterMaxRetries`.

## Como trabalhar

1. Leia os arquivos relevantes antes de editar. Não invente classes, métodos ou configurações que não existem.
2. Faça mudanças pequenas e focadas, uma tarefa por vez.
3. Se a tarefa for ambígua ou exigir uma decisão de arquitetura, pare e pergunte em vez de assumir.
4. Não refatore código fora do escopo pedido.
5. Ao terminar, explique em poucas linhas o que mudou e quais arquivos foram tocados.

## O que não fazer sem autorização explícita

- Alterar `README.md`, `AGENTS.md` ou arquivos em `docs/adr/`
- Mudar versões do Java, Spring Boot ou do AWS SDK
- Adicionar dependências novas ao `pom.xml`
- Alterar a estrutura de módulos ou as regras de dependência
- Mexer em `infra/` de forma que crie recursos pagos na AWS (NAT Gateway, instâncias maiores, etc.)
- Apagar ou reescrever testes existentes para fazê-los passar
- Executar `cdk deploy`, `cdk destroy` ou qualquer comando que altere a conta AWS real
- Fazer commit ou push. O commit é sempre manual

## Commits

Mensagens curtas, no imperativo, seguindo o padrão Conventional Commits:

```
feat(worker): consome mensagens do SQS e atualiza status
fix(api): valida tamanho máximo antes de gerar a URL
test(core): cobre validação do JSON retornado pelo LLM
docs: atualiza diagrama de arquitetura
```

## Definição de pronto

Uma tarefa só está concluída quando:

- o código compila e `./mvnw clean verify` passa
- existem testes para o que foi feito
- não há warnings novos nem código morto
- nenhuma regra deste arquivo foi violada
