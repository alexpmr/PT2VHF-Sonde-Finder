# Backlog — PT2VHF Sonde Finder

Itens relatados em testes. Registrar aqui; implementar quando o mantenedor pedir.

## Aberto

- **Busca histórica retorna sempre 8 sondas, qualquer que seja a janela** (relato de teste, v1.6.0).
  Aumentar a janela (dias/meses/1 ano) não traz mais resultados.
  Hipótese: o `GET /sondes` do SondeHub só consulta os índices de telemetria recentes
  (`telm-*`, retenção curta), então a janela longa não alcança voos antigos. Investigar
  `GET /sondes/summary` (com `from`/`to`, `lat`/`lon`/`distance`, `predictions_only=false`,
  limite 200) e o arquivo S3 (`date/AAAA/MM/DD/<serial>.json`, resumo primeiro/máx./último
  frame) como fontes para o modo histórico. Conferir também se o raio está sendo respeitado.

- **Atualização automática**: o site ainda não publica `product=sonde` em
  `update.php`/`download.php`; falta também a chave de assinatura permanente nos secrets.
