# Backlog — PT2VHF Sonde Finder

Itens relatados em testes. Registrar aqui; implementar quando o mantenedor pedir.

## Aberto

- **Atualização automática**: o site ainda não publica `product=sonde` em
  `update.php`/`download.php`; falta também a chave de assinatura permanente nos secrets.

## Resolvido

- **Busca histórica retornava só ~8 sondas** (v1.7.0). O `GET /sondes` só enxerga ~3–4 dias.
  Janelas acima de 3 dias agora usam `GET /sondes/summary` (fatias de 30 dias, divididas quando
  batem o limite de 200), consultado numa área maior porque o servidor casa a área por outro ponto
  do voo; o app filtra pela última posição recebida ou pelo pouso previsto dentro do raio.
  Teste real (Brasília, 25 km, 1 ano): 13 consultas, ~8 s, 164 voos.
