# HI-FIV Mobile (Android 10+)
1. Abra esta pasta no **Android Studio** (Hedgehog ou mais novo) e deixe o Gradle sincronizar.
2. Conecte o celular (depuração USB) e clique em Run, ou use Build > Build APK.
3. No app: escolha a saída, toque em INICIAR e aceite a captura. Abra o Spotify/YouTube e toque algo.
Limitações: apps com proteção (Netflix etc.) bloqueiam a captura; o som original continua tocando junto.

## Gerar o APK sem Android Studio
1. Crie um repositório no GitHub e envie TODOS os arquivos desta pasta (inclusive a pasta `.github`).
2. Abra a aba **Actions** > "Gerar APK" > aguarde ~5 min.
3. Baixe o `HIFIV-apk` (app-debug.apk), mande pro celular e instale (permita "fontes desconhecidas").
