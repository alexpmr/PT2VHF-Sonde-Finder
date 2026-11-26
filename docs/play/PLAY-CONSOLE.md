# PT2VHF Sonde Finder — cadastro no Google Play

Material e roteiro para criar o app na Play Console e abrir o teste fechado.

- Pacote: `com.pt2vhf.sondefinder`
- Política de privacidade: https://www.pt2vhf.com/privacidade
- Página do app: https://www.pt2vhf.com/sonde
- Ícone 512×512: `docs/play/icon-512.png`
- Gráfico de destaque 1024×500: `docs/play/feature-graphic-1024x500.png`
- Capturas de tela: a fazer (mínimo 2, do celular, versão atual)
- Chave de upload: chave própria do Sonde Finder (alias `sondefinder`), guardada pelo mantenedor;
  secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.

## Ficha da loja

**Nome:** PT2VHF Sonde Finder

**Descrição curta (pt-BR, até 80):**
Encontre e resgate radiossondas perto de você, inclusive as que caíram há meses.

**Short description (en-US):**
Find and recover radiosondes near you, even the ones that landed months ago.

**Descrição completa (pt-BR):**
O PT2VHF Sonde Finder ajuda radioamadores e caçadores de sondas a encontrar e resgatar radiossondas meteorológicas.

• Mapa com as sondas em voo e as que já caíram perto de você, de horas a um ano atrás, com dados abertos do SondeHub.
• Raio de busca ajustável (1 a 25 km) e janela de tempo; lista por distância ou por idade.
• Sondas já recuperadas marcadas, com os relatos de resgate do SondeHub.
• Bússola com agulha estilo ADF apontando para a última posição recebida ou para o pouso previsto, com distância, rumo e elevação para a antena.
• Detector de proximidade: bipes e vibração cada vez mais rápidos ao se aproximar, como um detector de metais.
• Rumo pela bússola do celular ou pelo GPS em movimento; mapa vetorial com norte ou direção para cima e feixe que mostra para onde você aponta.
• Idade do último pacote, aviso quando o sinal se perdeu ainda no alto e navegação até o ponto pelo seu app de mapas.
• Em português, inglês, espanhol, alemão, francês, italiano e polonês.

Dados das sondas: SondeHub (sondehub.org). Mapa: © colaboradores do OpenStreetMap, OpenFreeMap.

**Full description (en-US):**
PT2VHF Sonde Finder helps radio amateurs and sonde hunters find and recover weather radiosondes.

• Map of the sondes in flight and the ones that landed near you, from hours to a year ago, using SondeHub open data.
• Adjustable search radius (1 to 25 km) and time window; list by distance or by age.
• Already recovered sondes marked, from SondeHub recovery reports.
• ADF-style compass needle pointing to the last received position or to the predicted landing, with distance, bearing and elevation for the antenna.
• Proximity detector: beeps and vibration that speed up as you get closer, like a metal detector.
• Heading from the phone's compass or from the GPS while moving; vector map with north-up or heading-up and a beam showing where you point.
• Age of the last packet, a warning when the signal was lost while still high up, and navigation to the spot with your maps app.
• In English, Portuguese, Spanish, German, French, Italian and Polish.

Sonde data: SondeHub (sondehub.org). Map: © OpenStreetMap contributors, OpenFreeMap.

**Categoria:** Ferramentas (Tools). **Contato:** alexpmr@gmail.com · https://www.pt2vhf.com/sonde

## Conteúdo do app (respostas)

- Política de privacidade: https://www.pt2vhf.com/privacidade
- Anúncios: não contém.
- Acesso ao app: todas as funções sem login.
- Público-alvo: 18+ (não direcionado a crianças).
- Classificação de conteúdo: questionário IARC, categoria "Utilitário/Ferramenta", tudo "não".
- App de notícias: não. App governamental: não. Recursos financeiros: não. Saúde: não.
- Segurança dos dados:
  - Coleta: **Localização aproximada e precisa** — enviada ao SondeHub (api.v2.sondehub.org) só para buscar
    as sondas próximas; não armazenada pelo PT2VHF; não vinculada à identidade; não usada para publicidade.
    Marcar como "compartilhada" com terceiro (SondeHub) para "Funcionalidade do app", processada em trânsito,
    obrigatória para a função principal.
  - Dados criptografados em trânsito: sim (HTTPS). Exclusão de dados: não há conta nem dados guardados.
  - Nenhum outro dado (sem conta, sem contatos, sem identificadores de publicidade, sem análise).
- Permissões sensíveis: localização em primeiro plano (sem localização em segundo plano);
  `REQUEST_INSTALL_PACKAGES` deve sair da versão do Play (o Play não permite atualização fora da loja).

## Teste fechado

1. Testes › Teste fechado › Criar faixa ("Testadores").
2. Países: todos.
3. Testadores: lista de e-mails "Sonde Finder" com os inscritos (pendentes em
   `https://www.pt2vhf.com/testers_export.php?app=sonde`).
4. Feedback: alexpmr@gmail.com.
5. Primeiro AAB assinado com a chave de upload: enviado manualmente nesta faixa; depois, pelo GitHub Actions.
6. Conta pessoal recente: 12 testadores por 14 dias antes de pedir produção.

## Roteiro para o Claude Desktop (Claude no Chrome)

> Abra a Play Console (play.google.com/console) na conta do PT2VHF e crie o app "PT2VHF Sonde Finder"
> (app, gratuito, idioma padrão português do Brasil). Preencha a ficha da loja, o conteúdo do app e o teste
> fechado exatamente como em github.com/alexpmr/PT2VHF-Sonde-Finder/blob/main/docs/play/PLAY-CONSOLE.md,
> usando o ícone e o gráfico de destaque da pasta docs/play. Não envie nada para revisão enquanto não houver
> AAB. Antes de cada passo irreversível (criar o app, salvar a declaração de segurança dos dados), me mostre
> o que vai preencher e espere minha confirmação. No fim, me diga o que ficou pendente.
