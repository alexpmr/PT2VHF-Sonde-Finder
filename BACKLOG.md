# Backlog — PT2VHF Sonde Finder

Itens relatados em testes. Registrar aqui; implementar quando o mantenedor pedir.

## Aberto

- **Ícone da sonda no mapa conforme a fase do voo** — subindo: ícone de balão; caindo: ícone de
  paraquedas.
- **Alarme de pouso próximo** — alarme configurável que avisa quando houver previsão de uma sonda cair a
  até X km de onde o usuário está (X definido pelo usuário).
- **Conexão mais persistente** — verificar a conexão a cada 5 segundos, para não ficar muito tempo sem
  receber pacotes de uma sonda que está no ar.

## Resolvido

- **v1.9.0** — Contagem de usuários: o app envia ao site (`sonde_activity.php`), no máximo 2×/dia, um
  identificador aleatório da instalação (UUID guardado só no aparelho), a versão e a plataforma, nas versões do
  site e do Play; opção para desligar em ⓘ. Política própria em `/privacidade-sonde`. O item "Atualização
  automática pelo site" deixou de valer: a distribuição é só pelo Google Play (pedido do mantenedor).
- **v1.8.0** — Confirmar antes de fechar o app (Voltar no mapa pergunta; na bússola volta ao mapa; Android 8–16).
  Seta do selo de distância: estava certa, mas o ícone assimétrico girado parecia apontar para o outro lado;
  trocado por seta simétrica. Altitude/distância/idade sem quebra de linha na lista (espaço não separável).
  Versões `github` (APK do site, com OTA) e `play` (AAB, sem OTA nem `REQUEST_INSTALL_PACKAGES`).

- **Busca histórica retornava só ~8 sondas** (v1.7.0). O `GET /sondes` só enxerga ~3–4 dias.
  Janelas acima de 3 dias agora usam `GET /sondes/summary` (fatias de 30 dias, divididas quando
  batem o limite de 200), consultado numa área maior porque o servidor casa a área por outro ponto
  do voo; o app filtra pela última posição recebida ou pelo pouso previsto dentro do raio.
  Teste real (Brasília, 25 km, 1 ano): 13 consultas, ~8 s, 164 voos.
  Confirmado em campo pelo testador: passou a retornar mais sondas.
