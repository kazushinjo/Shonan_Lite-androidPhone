# Shonan_Lite-doroidPhone

Pluto直結DVB-S2トランシーバー「Shonan」のAndroidスマートフォン版(`android/`)。タブレット版
[Shonan_Lite-android](https://github.com/kazushinjo/Shonan_Lite-android)を元に、狭い画面幅に合わせて
画面上部のタブで機能を切り替える単一画面構成にしている。詳細な構成・アーキテクチャは
[`android/README.md`](android/README.md)を参照。

Android smartphone version of "Shonan", a DVB-S2 transceiver directly connected to a Pluto (`android/`).
Based on the tablet version [Shonan_Lite-android](https://github.com/kazushinjo/Shonan_Lite-android), it uses a
single screen whose functions are switched with tabs at the top, to fit a narrow phone screen. See
[`android/README.md`](android/README.md) for the detailed architecture and implementation.

## 主な機能

- **画面構成**: 上部のタブ(送信・受信・周波数・シンボルレート・誤り訂正・変調方式・映像ソース・配信先・
  受信感度・送信出力・設定・ヘルプ)で切り替える。上部バーに「アプリ再起動」「終了」。
  タブレット版にあるRSSI測定・機器試験は搭載していない。
- **送信**: 周波数・シンボルレート(250k〜2 Msym/s)・誤り訂正(1/2・3/5・8/9)・変調方式(QPSK・8PSK)・
  出力減衰量(-70〜0 dB)を設定し、UDP-TSでPlutoへ送ってPluto内蔵の変調器で送信する(送信先ポートは8282固定)。
  送信映像はHD(1280x720)・30fps。
- **映像ソース**: 背面カメラ(既定)・前面カメラ・写真(写真フォルダーから選択)・テストパターン。
  写真にはコールサイン・備考を焼き込める。マイク音声の送信ON/OFF。
- **受信**: 外部復調機器からのUDP-TS受信(既定)、またはオンデバイス復調(GNU Radio、Pluto 1台でのRFループバック
  試験にも使える)。ロックすると映像を全画面表示し、タップで5秒間だけ状態を表示する。
- **その他**: Plutoの自動検出(端末の接続中ネットワークを探索し、見つからなければESP32ブリッジに問い合わせ。
  起動時にも1回実行)、起動時のPluto再起動、日本語/英語表示、アプリ内ヘルプ。

★アプリID(パッケージ名)`com.shinjo.shonanandroid`はタブレット版と同じ。同じ端末に両方を並べて
インストールすることはできない(後から入れた方が前の方を上書きしようとし、署名が異なるとインストールに失敗する)。

## インストール

### 前提条件

- macOS + [Android Studio](https://developer.android.com/studio)(Android SDKが同梱されます)
- Android Studio の SDK Manager > SDK Tools から **NDK (Side by side) 27.3.13750724** をインストール
- Android端末(minSdk 26以上、arm64-v8a)。端末の開発者向けオプションでUSBデバッグを有効化

### 未クローンのマシンで初めて使う場合

このリポジトリはPrivateなので匿名の`curl | bash`は使えない。`gh auth login`済みの
マシンであれば、`bootstrap.sh`だけ先に取得してから実行することでclone不要で始められる:

```sh
gh api repos/kazushinjo/Shonan_Lite-doroidPhone/contents/bootstrap.sh \
  --jq '.content' | base64 -d > bootstrap.sh
chmod +x bootstrap.sh
./bootstrap.sh                       # $HOME/Shonan_Lite-doroidPhone へclone
./bootstrap.sh ~/path/to/dir         # clone先を指定する場合
./bootstrap.sh ~/path/to/dir --build-only  # install.shへの追加引数も渡せる
```

git-lfsが未インストールなら自動で`brew install git-lfs`を試みる。既に`~/Shonan_Lite-doroidPhone`
等にcloneが存在する場合は`git pull --ff-only`で更新してから続行する。

### 既にクローン済みの場合の手順

```sh
./install.sh
```

- Android SDK/NDKの検出、`android/local.properties`の自動生成、`assembleDebug`ビルド、接続中の端末への`adb install`までを自動で行う
- 端末が1台も接続されていない場合は、生成されたAPK(`android/app/build/outputs/apk/debug/app-debug.apk`)を手動で端末へ転送してインストールしてください
- 複数端末が接続されている場合は、対象を指定した`adb install`コマンド例を表示するので、それに従ってください

### オプション

```sh
./install.sh --build-only   # ビルドのみ行い、adb installはしない
./install.sh --clean        # ビルド前に ./gradlew clean を実行する
./install.sh --release      # assembleReleaseでビルド
```

`--release`は、`android/keystore/keystore.properties`(と鍵ファイル。Git管理外)があれば署名して端末へインストール
まで行います。無い場合は未署名APKになり、そのままでは端末にインストールできません。

### Android Studioなしでビルドする場合(一般ユーザー向け)

Android Studioをインストールしていない場合でも、`cli-install.sh`を使えばコマンドラインのみで
ソースコードからビルド・インストールできます。Android SDK Command-line Toolsの取得、
必要なplatform/NDKのセットアップまで自動で行い、最後に`install.sh`へ処理を引き継ぎます。

```sh
brew install openjdk@17   # Javaが未インストールの場合のみ
./cli-install.sh
```

`install.sh`と同じオプション(`--build-only`、`--clean`、`--release`)がそのまま渡せます。

```sh
./cli-install.sh --build-only
```

## Installation

### Prerequisites

- macOS + [Android Studio](https://developer.android.com/studio) (bundles the Android SDK)
- Install **NDK (Side by side) 27.3.13750724** from Android Studio's SDK Manager > SDK Tools
- An Android device (minSdk 26+, arm64-v8a). Enable USB debugging under Developer Options

### First use on a machine with no clone yet

This repository is private, so an anonymous `curl | bash` won't work. On a machine
already signed in with `gh auth login`, you can fetch just `bootstrap.sh` first and
run it to get started without cloning by hand:

```sh
gh api repos/kazushinjo/Shonan_Lite-doroidPhone/contents/bootstrap.sh \
  --jq '.content' | base64 -d > bootstrap.sh
chmod +x bootstrap.sh
./bootstrap.sh                       # clones to $HOME/Shonan_Lite-doroidPhone
./bootstrap.sh ~/path/to/dir         # clone to a specific directory
./bootstrap.sh ~/path/to/dir --build-only  # extra args are passed through to install.sh
```

If git-lfs isn't installed, it automatically tries `brew install git-lfs`. If a clone
already exists (e.g. under `~/Shonan_Lite-doroidPhone`), it updates it with `git pull --ff-only`
before continuing.

### Steps if you already have a clone

```sh
./install.sh
```

- Automatically detects the Android SDK/NDK, generates `android/local.properties`, runs an `assembleDebug` build, and runs `adb install` on any connected device
- If no device is connected, manually transfer the built APK (`android/app/build/outputs/apk/debug/app-debug.apk`) to your device and install it
- If multiple devices are connected, it prints an `adb install` command for each device — follow the printed instructions to pick one

### Options

```sh
./install.sh --build-only   # build only, skip adb install
./install.sh --clean        # run ./gradlew clean before building
./install.sh --release      # build with assembleRelease
```

With `--release`, if `android/keystore/keystore.properties` (and the key file, both outside Git) exist, the APK is signed
and installed on the device. Otherwise the APK is unsigned and cannot be installed on a device as is.

### Building without Android Studio (for general users)

If you don't have Android Studio installed, `cli-install.sh` lets you build and install
from source using only the command line. It automatically downloads the Android SDK
Command-line Tools, sets up the required platform/NDK, and then hands off to `install.sh`.

```sh
brew install openjdk@17   # only if Java isn't installed yet
./cli-install.sh
```

It accepts the same options as `install.sh` (`--build-only`, `--clean`, `--release`).

```sh
./cli-install.sh --build-only
```

## クレジット

- 受信部の方式考案・受信部原システム設計: 山崎慎慈氏(JE1BTA) — `rpi-dvbs2-receiver-gui`の設計に基づく
- 受信部安定化調査修正・再捕捉修正・本アプリ開発: 真城和一(JA6FUF/JH1XHX)
- 本アプリは、Dave Crump氏(G8GKQ)が開発したDATV送受信機プロジェクト「Portsdown」に啓発され、開発したものです。同氏の先駆的な取り組みに感謝いたします。

## ライセンス

本プログラムはフリーソフトウェアです。GNU General Public License v3(またはそれ以降のバージョン)の下で
再配布・改変することができます。

```
Copyright (C) 2026  Kazuichi Shinjo
Copyright of `rpi-dvbs2-receiver-gui` is held by Shinji Yamazaki.

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
```

ライセンス全文は<https://www.gnu.org/licenses/gpl-3.0.html>を参照してください。

## Credits

- Reception method design and original receiver system design: Shinji Yamazaki (JE1BTA) — based on the design of `rpi-dvbs2-receiver-gui`
- Receiver stabilization investigation/fixes, re-acquisition fixes, and app development: Kazuichi Shinjo (JA6FUF/JH1XHX)
- This application was developed inspired by "Portsdown", the DATV transceiver project created by Dave Crump (G8GKQ). We extend our deep gratitude for his pioneering work.

## License

This program is free software: you can redistribute it and/or modify it under the terms of
the GNU General Public License v3 (or any later version).

```
Copyright (C) 2026  Kazuichi Shinjo
Copyright of `rpi-dvbs2-receiver-gui` is held by Shinji Yamazaki.

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
```

See <https://www.gnu.org/licenses/gpl-3.0.html> for the full license text.
