# PT2VHF Sonde Finder

www.PT2VHF.com

App Android para localizar e resgatar radiossondas usando os dados abertos do [SondeHub](https://sondehub.org).
Funciona como um "detector de metais": mostra no mapa as sondas que caíram perto de você — inclusive há meses — e
uma bússola com agulha estilo ADF aponta para a escolhida, com a distância embaixo.

## Funções

- **Mapa** (MapLibre + OpenFreeMap/OpenStreetMap, tema escuro, ou **satélite** Esri World Imagery com nomes de lugares; nomes sempre de pé ao girar) com todas as sondas do filtro, sua posição, a trajetória e o pouso previsto da sonda selecionada.
- **Fase do voo no mapa**: balão enquanto a sonda sobe, paraquedas enquanto desce (ponto quando parada ou sem sinal há mais de 10 min).
- **Configurações** (⚙ no topo): altitude em metros ou pés, temperatura em °C ou °F e o alarme de pouso. Nome e versão no topo ("PT2VHF Sonde Finder 1.11.0").
- **Temperatura e pressão** do último pacote da sonda selecionada, no mapa e na bússola ("—" quando a sonda não envia o dado).
- **Filtro** de raio (10–500 km) e janela de tempo (1 h a 1 ano), salvo no aparelho.
- **Seleção** automática (a que transmitiu mais recentemente) ou manual (toque no mapa ou pela lista).
- **Bússola** com agulha ADF apontando para a última posição recebida ou para o pouso previsto; rumo, "vire X°", elevação para a antena e tempo até o pouso.
- **Idade do dado**: "há X s/min/d" com cor (verde < 30 s, âmbar < 2 min, vermelho acima).
- **Resgates**: relatos do SondeHub (`/recovered`) marcam sondas já recuperadas (verde) ou não achadas; opção de escondê-las.
- **Aviso** quando o último pacote foi recebido muito acima do solo (a sonda pode ter caído adiante, no rumo indicado).
- **Detector de proximidade**: bipe e vibração cada vez mais rápidos a partir de 3 km; som diferente abaixo de 50 m.
- **Alarme de pouso** (Configurações › Alarme de pouso: 2, 5, 10, 20 ou 50 km): som e vibração quando o pouso previsto de uma sonda no ar, a até 400 km, ficar a essa distância de você. Verifica a cada minuto, com o app aberto.
- Copiar coordenadas e abrir no app de navegação.
- **Idiomas**: inglês (padrão para idiomas sem tradução), português, espanhol, alemão, francês, italiano e polonês. No Android 13+ dá para escolher o idioma só do app em Configurações › Apps.

## Dados (SondeHub v2)

| Uso | Endpoint |
| --- | --- |
| Sondas na área (último pacote de cada) | `GET /sondes?lat&lon&distance&last` |
| Trajetória | `GET /sondes/telemetry?duration&serial` |
| Previsão de pouso | `GET /predictions?vehicles` |
| Relatos de resgate | `GET /recovered?lat&lon&distance&last` |

Consulta a cada 5 s (a cada 2 min em janelas maiores que 24 h sem sonda no ar), uma requisição por vez: se a anterior ainda não respondeu, a rodada é pulada, sem fila. Trajetória e previsão vêm em paralelo, a cada 30 s. Sem resposta nova por mais de 30 s, o selo "ao vivo" vira "Atualizado há X".

## Compilar

Requer JDK 17+ e Android SDK (compileSdk 36).

```bash
./gradlew assembleRelease
```

O APK sai em `app/build/outputs/apk/release/` (assinado com a chave de debug, para instalação direta).
### Assinatura e atualizações

A versão do Google Play usa o aviso de atualização do próprio Play (In-App Updates, flexível: baixa em segundo plano e pede para reiniciar). A versão do site (APK)
verifica uma vez por dia (e pelo botão ⓘ › Verificar atualizações) o catálogo
`https://www.pt2vhf.com/update.php?product=sonde&platform=android&kind=apk`, no mesmo formato do
PT2VHF Speed Alert (versão, notas, link `download.php?product=sonde&file=PT2VHF-Sonde-Finder-vX.Y.Z.apk`,
tamanho e SHA-256). O APK baixado é conferido e entregue ao instalador do Android.

Para atualizar por cima, todas as versões publicadas precisam da **mesma chave permanente**. Configure os
secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` e `ANDROID_KEY_PASSWORD`
no repositório; sem eles o workflow gera um APK com chave de debug (sufixo `-DEBUG-KEY`), que não deve ser publicado.

O workflow **Build APK** do GitHub Actions roda os testes e gera o APK e o AAB a cada push em `main` (aba Actions →
artefato). Com os secrets, confere que o certificado é o da chave permanente (SHA-256 `dfd3ebee…873b`, o mesmo da
v1.9.0) e cria a GitHub Release `vX.Y.Z` com o APK e o AAB. O workflow `sync-sonde-release.yml` do repositório do
site pega essa release (a cada hora ou sob demanda), confere hash e assinatura e publica o APK em
`downloads/sonde-finder/`, o `updates.json` (OTA) e o link do APK dos e-mails. O AAB vai para a Play Console.

Requer Android 8.0+ e permissão de localização.
