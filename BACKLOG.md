# Backlog — PT2VHF Sonde Finder

Itens relatados em testes. Registrar aqui; implementar quando o mantenedor pedir.

## Aberto

- **Rumo pelo GPS por padrão** (27/11/2026): nos testes, a bússola funcionou melhor com o GPS do que com o
  magnetômetro. Deixar "GPS em movimento" como padrão (`gpsWhenMoving = true` em instalações novas e em quem
  nunca mudou a opção). Parado, sem rumo do GPS, continua usando a bússola do celular.

## Resolvido

- **v1.12.0** — Campo de pouso: não funcionava porque mostrava "—" quando a sonda não estava caindo ou estava sem
  sinal há mais de 10 min; agora mostra quanto falta enquanto cai e o horário estimado do pouso quando já caiu
  (previsão, senão altitude / velocidade de descida a partir do último pacote). Local do pouso com rua, bairro e
  cidade (Geocoder do Android, Nominatim como reserva; consulta só quando o ponto muda). Tipo de mapa (inclusive
  satélite) nas Configurações. Card do mapa recolhível pela alça. Bússola: sem o card de temperatura/pressão e
  sem a linha de rumo em texto, distância menor e bússola maior, balão/paraquedas no centro, endereço do alvo.
- **v1.11.0** — Tela de Configurações (⚙): altitude em metros ou pés (vale para todas as altitudes; distâncias e
  velocidades continuam métricas), temperatura em °C ou °F, e o alarme de pouso a X km (saiu do Filtro).
  Campos de temperatura (`temp`) e pressão (`pressure`, hPa) do último pacote no mapa e na bússola ("—" quando a
  sonda não envia). Nome e versão no topo: "PT2VHF Sonde Finder 1.11.0". OTA completo: APK do site pelo
  `update.php` (release assinada → `sync-sonde-release.yml` → aviso no app, download conferido por SHA-256 e
  certificado) e, na versão `play`, Google Play In-App Updates (flexível). Mapa de satélite de fundo
  (estilo "Satélite": Esri World Imagery + camada de fronteiras e nomes de lugares, com atribuição), pedido em 27/11/2026.
  Nova chave de assinatura (27/11/2026, SHA-256 `db5e19d5…121d`): a senha da chave da v1.9.0 se perdeu; quem tem o
  APK 1.9.0 do site precisa desinstalar uma vez antes de instalar a 1.11.0 (no Play, pedir a troca da chave de envio).

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
