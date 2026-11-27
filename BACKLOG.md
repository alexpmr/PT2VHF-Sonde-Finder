# Backlog — PT2VHF Sonde Finder

Itens relatados em testes. Registrar aqui; implementar quando o mantenedor pedir.

## Aberto

- **Tela de Configurações com unidades** (27/11/2026): altitude e distância em metros ou pés; temperatura em
  °C ou °F. Escolha salva no aparelho e aplicada em todas as telas.
- **Temperatura e pressão nas duas telas** (27/11/2026): adicionar um campo de temperatura e outro de pressão
  no mapa e na bússola, com os valores do último pacote da sonda selecionada (quando a sonda transmitir esses
  dados; senão "—"). Temperatura segue a unidade escolhida nas Configurações.
- **Alerta de pouso a X km nas Configurações** (27/11/2026): configurar ali o alerta para quando uma sonda tiver
  previsão de cair a X km de onde está o aparelho. Já existe desde a v1.10.0 em Filtro › Alarme de pouso
  (2–50 km, com o app aberto); o pedido é tê-lo na tela de Configurações.
- **Adicionar OTA** (27/11/2026): atualização pelo próprio app. Hoje a versão `github` (APK do site) já consulta
  `update.php?product=sonde` uma vez por dia e pelo ⓘ, mas o site ainda não publica versão assinada (aguarda os
  secrets do CI); a versão `play` não tem OTA. Decisão do mantenedor (27/11/2026): **OTA completo** —
  (a) versão `github`: OTA do APK do site funcionando de ponta a ponta (release assinada → site → aviso, download
  conferido por SHA-256 e instalação); (b) versão `play`: aviso de atualização do Google Play (In-App Updates).
- **Versão no topo** (27/11/2026): mostrar a versão do app em cima, junto com o nome (ex.: "PT2VHF Sonde Finder
  1.10.0").

## Resolvido

- **v1.10.0** — Ícone conforme a fase do voo: balão subindo, paraquedas descendo (ponto quando parada ou sem sinal
  há mais de 10 min). Alarme de pouso configurável (Filtro › 2–50 km): som, vibração e aviso quando o pouso
  previsto de uma sonda no ar (até 400 km) ficar a essa distância do usuário; verifica a cada minuto, com o app
  aberto. Conexão mais persistente: consulta a cada 5 s, uma por vez (a rodada é pulada se a anterior não
  respondeu, sem fila; causa do atraso de 25 min relatado em 26/11/2026), trajetória e previsão numa thread
  própria, tempos limite curtos e selo "Atualizado há X" quando não chega dado novo.
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
