# Backlog — PT2VHF Sonde Finder

Itens relatados em testes. Registrar aqui; implementar quando o mantenedor pedir.

## Aberto

- **Seta do selo de distância (mapa) aponta para o lado errado** (captura de teste, v1.7.0): selo dizia
  "159° S" com o mapa em norte para cima, mas a seta azul apontava para oeste. A bússola e a linha no mapa
  estavam certas. Revisar o cálculo da rotação do ícone (`chipBearing - 45 - map.bearing`) com o MapLibre.
- **Lista de sondas: a altitude quebra linha** ("1.075 m" vira "1.075" + "m"). Usar espaço não separável
  ou layout em duas linhas por item.

- **Confirmar antes de fechar o app** (pedido de teste, v1.7.0). Ao tocar em Voltar na tela
  principal, perguntar se quer mesmo sair (evita fechar sem querer durante a busca/detector).

- **Atualização automática**: o site ainda não publica `product=sonde` em
  `update.php`/`download.php`; falta também a chave de assinatura permanente nos secrets.

## Resolvido

- **Busca histórica retornava só ~8 sondas** (v1.7.0). O `GET /sondes` só enxerga ~3–4 dias.
  Janelas acima de 3 dias agora usam `GET /sondes/summary` (fatias de 30 dias, divididas quando
  batem o limite de 200), consultado numa área maior porque o servidor casa a área por outro ponto
  do voo; o app filtra pela última posição recebida ou pelo pouso previsto dentro do raio.
  Teste real (Brasília, 25 km, 1 ano): 13 consultas, ~8 s, 164 voos.
  Confirmado em campo pelo testador: passou a retornar mais sondas.
