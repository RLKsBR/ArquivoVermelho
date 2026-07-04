# Relatorio do app Arquivo Vermelho 1.6.1-teste

Data: 2026-07-04

## Objetivo

Criar uma versao de teste mantendo os recursos da 1.6, incluindo controle de velocidade do Text To Speech, mas removendo a capacidade do app de baixar atualizacao de APK internamente ou abrir instalador de APK.

## Mudancas da 1.6.1-teste

- Versao interna: `versionCode=8`, `versionName=1.6.1-teste`.
- Mantido o controle de velocidade do Text To Speech.
- Removida a permissao `android.permission.REQUEST_INSTALL_PACKAGES`.
- Removido o fluxo interno que baixava APK de atualizacao pelo `DownloadManager`.
- Removido o fluxo que abria o instalador do Android apos baixar o APK.
- A verificacao de atualizacao agora consulta o manifesto e abre o site oficial no navegador externo quando houver atualizacao.
- Links de APK dentro do app sao desviados para o navegador externo/site oficial, evitando download interno de APK pelo app.
- APK assinado com chave release fixa local.

## Comparativo de permissoes

| Versao | Permissoes |
| --- | --- |
| 1.5 | `android.permission.INTERNET`; `android.permission.REQUEST_INSTALL_PACKAGES`; `android.permission.WRITE_EXTERNAL_STORAGE` com `maxSdkVersion=28` |
| 1.6 | `android.permission.INTERNET`; `android.permission.REQUEST_INSTALL_PACKAGES`; `android.permission.WRITE_EXTERNAL_STORAGE` com `maxSdkVersion=28` |
| 1.6.1-teste | `android.permission.INTERNET`; `android.permission.WRITE_EXTERNAL_STORAGE` com `maxSdkVersion=28` |

## Comparativo de assinatura

| Versao | Certificado | SHA-256 |
| --- | --- | --- |
| 1.5 | `CN=Arquivo Vermelho, OU=Codex, O=Arquivo Vermelho, L=Maceio, ST=AL, C=BR` | `b45ce2ead1f01aa5d16cc28bf11b726bfdf20923f28ee8a0b5352016ff8633f6` |
| 1.6 | `CN=Arquivo Vermelho, OU=Codex, O=Arquivo Vermelho, L=Maceio, ST=AL, C=BR` | `b45ce2ead1f01aa5d16cc28bf11b726bfdf20923f28ee8a0b5352016ff8633f6` |
| 1.6.1-teste | `CN=Arquivo Vermelho, OU=Release, O=Arquivo Vermelho, L=Maceio, ST=AL, C=BR` | `9633db32da1d4ff75b6e79b5afa90467b04f92c5a098a26ed4f9052cd25526b8` |

## Observacao importante

Como a 1.6.1-teste usa uma chave release diferente da chave das versoes 1.5 e 1.6, o Android pode bloquear a instalacao por cima da versao antiga. Para testar esta versao, pode ser necessario desinstalar o app antigo antes de instalar a 1.6.1-teste.

Essa mudanca reduz o comportamento sensivel que poderia gerar alerta do Play Protect, mas nao garante aprovacao automatica, porque o APK ainda e distribuido fora da Play Store.
