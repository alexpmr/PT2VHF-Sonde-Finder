# PT2VHF Sonde Finder

www.PT2VHF.com

App Android para localizar e resgatar radiossondas usando os dados abertos do [SondeHub](https://sondehub.org).
Funciona como um "detector de metais": mostra no mapa as sondas que caíram perto de você — inclusive há meses — e
uma bússola com agulha estilo ADF aponta para a escolhida, com a distância embaixo.

## Funções

- **Mapa** (OpenStreetMap, tema escuro) com todas as sondas do filtro, sua posição, a trajetória e o pouso previsto da sonda selecionada.
- **Filtro** de raio (10–500 km) e janela de tempo (1 h a 1 ano), salvo no aparelho.
- **Seleção** automática (a que transmitiu mais recentemente) ou manual (toque no mapa ou pela lista).
- **Bússola** com agulha ADF apontando para a última posição recebida ou para o pouso previsto; rumo, "vire X°", elevação para a antena e tempo até o pouso.
- **Idade do dado**: "há X s/min/d" com cor (verde < 30 s, âmbar < 2 min, vermelho acima).
- **Resgates**: relatos do SondeHub (`/recovered`) marcam sondas já recuperadas (verde) ou não achadas; opção de escondê-las.
- **Aviso** quando o último pacote foi recebido muito acima do solo (a sonda pode ter caído adiante, no rumo indicado).
- **Detector de proximidade**: bipe e vibração cada vez mais rápidos a partir de 3 km; som diferente abaixo de 50 m.
- Copiar coordenadas e abrir no app de navegação.
- **Idiomas**: inglês (padrão para idiomas sem tradução), português, espanhol, alemão, francês, italiano e polonês. No Android 13+ dá para escolher o idioma só do app em Configurações › Apps.

## Dados (SondeHub v2)

| Uso | Endpoint |
| --- | --- |
| Sondas na área (último pacote de cada) | `GET /sondes?lat&lon&distance&last` |
| Trajetória | `GET /sondes/telemetry?duration&serial` |
| Previsão de pouso | `GET /predictions?vehicles` |
| Relatos de resgate | `GET /recovered?lat&lon&distance&last` |

Consulta a cada 10 s em janelas de até 24 h e a cada 2 min em janelas maiores.

## Compilar

Requer JDK 17+ e Android SDK (compileSdk 36).

```bash
./gradlew assembleRelease
```

O APK sai em `app/build/outputs/apk/release/` (assinado com a chave de debug, para instalação direta).
O workflow **Build APK** do GitHub Actions gera o APK a cada push em `main` (aba Actions → artefato).

Requer Android 8.0+ e permissão de localização.
