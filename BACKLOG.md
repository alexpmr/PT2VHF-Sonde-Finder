# Backlog — PT2VHF Sonde Finder

Itens relatados em testes. Registrar aqui; implementar quando o mantenedor pedir.

## Aberto

- **Contagem de usuários pela verificação de versão** (pedido do mantenedor, 26/11/2026). O app consulta o site
  (`update.php?product=sonde`) enviando um identificador único e anônimo da instalação (UUID gerado no primeiro
  uso, guardado só no aparelho), independente da versão, para o site contar usuários totais e ativos.
  Vale também para a versão do Play (só a consulta, sem o atualizador). Informar na política de privacidade.
  Ligado ao item do site "contadores de usuários do Sonde Finder".

- **Atualização automática**: depende do site publicar `product=sonde` (feito na v1.8.0) e dos secrets da chave
  permanente no GitHub (o CI assina com eles; sem eles gera `-DEBUG-KEY`).

## Resolvido

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
